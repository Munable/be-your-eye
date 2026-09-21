package app.beyoureyes.core.data.cloud

import app.beyoureyes.core.data.UuidV7
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface CloudDeviceDescriptorProvider {
    suspend fun create(deviceId: String, notificationsEnabled: Boolean): CloudDeviceWrite
}

fun interface CloudNotificationPolicy {
    suspend fun enabled(): Boolean
}

/** Returns true only when an OS notification was actually shown. */
fun interface CloudEventNotificationSink {
    suspend fun publish(event: CloudEventRow): Boolean
}

data class CloudSyncResult(
    val uploadedEvents: Int,
    val downloadedEvents: Int,
    val lastCursor: Long,
)

/**
 * Local-first synchronization. A missing configuration/session is a successful no-op, while
 * network/auth failures throw so WorkManager can retry without mutating the Room Outbox.
 */
class CloudSyncCoordinator(
    private val dataPlane: CloudDataPlane,
    private val local: CloudLocalBridge,
    private val stateStore: CloudLocalStateStore,
    private val deviceProvider: CloudDeviceDescriptorProvider,
    private val notificationPolicy: CloudNotificationPolicy,
    private val notificationSink: CloudEventNotificationSink,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val operationMutex = Mutex()

    suspend fun sync(): CloudSyncResult = operationMutex.withLock { syncLocked() }

    private suspend fun syncLocked(): CloudSyncResult {
        if (!dataPlane.configured) return CloudSyncResult(0, 0, 0)
        val accountId = dataPlane.currentAccountId() ?: return CloudSyncResult(0, 0, 0)
        val deviceId = stateStore.deviceId(accountId)
        val localTaskIds = local.localTaskIds()
        val previouslyOwnedTaskIds = stateStore.localTaskIdsOwnedBy(accountId)
        val removedLocalTaskIds = previouslyOwnedTaskIds - localTaskIds
        removedLocalTaskIds.chunked(MAX_TASK_ID_FILTER).forEach { taskIdBatch ->
            stateStore.releaseLocalTaskOwnership(accountId, taskIdBatch.toSet())
        }
        localTaskIds.chunked(MAX_TASK_ID_FILTER).forEach { taskIdBatch ->
            stateStore.claimUnownedLocalTaskIds(accountId, taskIdBatch.toSet())
        }
        // Local tasks are account-independent for the manual flows, but their optional cloud
        // projection is not. A task claimed by another account remains on this phone and is never
        // uploaded, deleted remotely, or used to drain this account's outbox.
        // Always use the task-filtered outbox query, including when the local task set is empty.
        // An orphaned Event/Outbox row left by a failed delete must never be assigned to the next
        // account just because there are no current tasks to constrain an unfiltered query.
        val ownedTaskIds = stateStore.localTaskIdsOwnedBy(accountId)
        registerDeviceAndToken(accountId, deviceId)
        retryPendingRemoteNotifications(accountId, deviceId)
        val taskWrites = local.taskWrites(deviceId).filter { task -> task.taskId in ownedTaskIds }
        val currentTaskIds = taskWrites.map(CloudTaskWrite::taskId).toSet()
        val removedTaskIds = stateStore.syncedTaskIds(accountId) - currentTaskIds
        removedTaskIds.sorted().chunked(MAX_WRITE_BATCH).forEach { taskIds ->
            dataPlane.deleteTasks(taskIds)
        }
        val changedTasks = taskWrites.filter { task ->
            stateStore.syncFingerprint(accountId, "task:${task.taskId}") != fingerprint(task)
        }
        changedTasks.chunked(MAX_WRITE_BATCH).forEach { tasks ->
            dataPlane.upsertTasks(tasks)
            tasks.forEach { task ->
                stateStore.setSyncFingerprint(
                    accountId,
                    "task:${task.taskId}",
                    fingerprint(task),
                )
            }
        }
        stateStore.setSyncedTaskIds(accountId, currentTaskIds)

        var uploaded = 0
        for (batchIndex in 0 until MAX_EVENT_BATCHES_PER_RUN) {
            val events = local.readyEventsForTasks(
                nowEpochMillis(),
                MAX_WRITE_BATCH,
                ownedTaskIds,
            )
            if (events.isEmpty()) break
            val result = dataPlane.uploadEvents(events)
            val requested = events.map(CloudEventWrite::eventId).toSet()
            val delivered = (result.acceptedIds + result.duplicateIds).toSet()
            require(delivered.size == result.acceptedIds.size + result.duplicateIds.size) {
                "event batch response contains duplicate identities"
            }
            require(delivered.all(requested::contains)) {
                "event batch response contains an unrequested identity"
            }
            require(delivered == requested) { "event batch response omitted an identity" }
            local.markEventsUploaded(delivered)
            uploaded += delivered.size
            if (events.size < MAX_WRITE_BATCH) break
        }

        var cursor = stateStore.cursor(accountId)
        var downloaded = 0
        for (batchIndex in 0 until MAX_PULL_BATCHES_PER_RUN) {
            val changes = dataPlane.changesAfter(cursor, MAX_PULL_BATCH)
            if (changes.isEmpty()) break
            changes.forEach { change ->
                require(change.sequence > cursor) { "sync changes must be strictly ordered" }
                if (change.resourceType == "event" && change.operation == "upsert") {
                    val value = requireNotNull(change.value) { "event upsert requires a value" }
                    val event = json.decodeFromJsonElement<CloudEventRow>(value)
                    if (cacheAndMaybeNotify(accountId, event, deviceId)) downloaded++
                } else if (change.resourceType == "task" && change.operation == "upsert") {
                    val value = requireNotNull(change.value) { "task upsert requires a value" }
                    local.cacheRemoteTaskSummary(
                        accountId,
                        json.decodeFromJsonElement<CloudTaskSummary>(value),
                    )
                } else if (change.resourceType == "task" && change.operation == "delete") {
                    local.removeCachedRemoteTask(accountId, change.resourceId)
                } else if (change.resourceType == "event" && change.operation == "delete") {
                    local.removeCachedRemoteEvent(accountId, change.resourceId)
                }
                cursor = change.sequence
                stateStore.setCursor(accountId, cursor)
            }
            if (changes.size < MAX_PULL_BATCH) break
        }
        return CloudSyncResult(uploaded, downloaded, cursor)
    }

    suspend fun processPush(envelope: PushEnvelope): Boolean = operationMutex.withLock {
        processPushLocked(envelope)
    }

    private suspend fun processPushLocked(envelope: PushEnvelope): Boolean {
        if (!dataPlane.configured) return false
        val accountId = dataPlane.currentAccountId() ?: return false
        val event = dataPlane.event(envelope.eventId) ?: return false
        require(event.eventId == envelope.eventId) { "fetched Event identity does not match push" }
        return cacheAndMaybeNotify(accountId, event, stateStore.deviceId(accountId))
    }

    /**
     * Token changes share the sync mutex with auth fencing. Otherwise a queued FCM token worker
     * could register a token for account A after sign-out had already unregistered it.
     */
    suspend fun registerLatestPushToken(token: String) = operationMutex.withLock {
        require(token.length in 20..4096)
        stateStore.setPushToken(token)
        if (!dataPlane.configured) return@withLock
        val accountId = dataPlane.currentAccountId() ?: return@withLock
        val deviceId = stateStore.deviceId(accountId)
        registerDeviceAndToken(accountId, deviceId)
    }

    suspend fun unregisterCurrentPushToken(clearLocalToken: Boolean = false) =
        operationMutex.withLock {
            val accountId = if (dataPlane.configured) dataPlane.currentAccountId() else null
            if (accountId != null) {
                val deviceId = stateStore.deviceId(accountId)
                dataPlane.unregisterPushToken(deviceId)
                stateStore.setSyncFingerprint(accountId, "push:$deviceId", null)
            }
            if (clearLocalToken) stateStore.setPushToken(null)
        }

    /**
     * Re-registers the locally retained token for the still-active account after an auth attempt
     * failed before the SDK changed sessions. The operation is idempotent and shares the auth
     * mutex with unregister/register so a token cannot remain removed after a failed switch.
     */
    suspend fun restoreCurrentPushToken() = operationMutex.withLock {
        if (!dataPlane.configured) return@withLock
        val accountId = dataPlane.currentAccountId() ?: return@withLock
        registerDeviceAndToken(accountId, stateStore.deviceId(accountId))
    }

    suspend fun clearAccountState(accountId: String) = operationMutex.withLock {
        stateStore.clearAccountState(accountId)
    }

    suspend fun resetAccountSessionState(accountId: String) = operationMutex.withLock {
        stateStore.resetAccountSessionState(accountId)
    }

    /**
     * Records local ownership before an auth transition. This is deliberately local-only and
     * must run before sign-out/direct account switch so the next account cannot claim the old
     * account's manual monitoring rows just because they remain on the device. The same mutex as
     * sync prevents a concurrent worker from observing a partially fenced task set.
     */
    suspend fun claimLocalData(accountId: String) {
        operationMutex.withLock {
            local.localTaskIds().chunked(MAX_TASK_ID_FILTER).forEach { taskIdBatch ->
                stateStore.claimUnownedLocalTaskIds(accountId, taskIdBatch.toSet())
            }
        }
    }

    /** Claims one newly persisted task before another account can observe it on this device. */
    suspend fun claimLocalTask(accountId: String, taskId: String) {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        operationMutex.withLock {
            // The callback runs immediately before the Room insert and can race an auth
            // transition. Never let a stale A-account callback fence a task to A after the
            // Supabase session has already moved to B; the next signed-in sync may claim an
            // unowned local task under the session that actually exists.
            if (dataPlane.currentAccountId() != accountId) return@withLock
            stateStore.claimUnownedLocalTaskIds(accountId, setOf(taskId))
        }
    }

    /** Account deletion leaves local monitoring intact but makes it claimable by a later account. */
    suspend fun releaseLocalDataOwnership(accountId: String) {
        operationMutex.withLock {
            stateStore.releaseLocalTaskOwnership(accountId)
        }
    }

    suspend fun currentDeviceId(): String {
        val accountId = dataPlane.currentAccountId()
            ?: throw CloudUnavailableException("Supabase account session is required")
        return stateStore.deviceId(accountId)
    }

    suspend fun revokePeerDevice(deviceId: String): Boolean = operationMutex.withLock {
        require(dataPlane.configured) { "optional cloud is unavailable" }
        val accountId = requireNotNull(dataPlane.currentAccountId()) {
            "Supabase account session is required"
        }
        require(deviceId != stateStore.deviceId(accountId)) {
            "the current device cannot revoke itself"
        }
        dataPlane.revokeDevice(deviceId)
    }

    /** Removes only downloaded peer cache rows; local tasks, Events and Outbox remain intact. */
    suspend fun clearRemoteCache(): Int = operationMutex.withLock { local.clearRemoteCache() }

    private suspend fun registerDeviceAndToken(accountId: String, deviceId: String) {
        val notificationsEnabled = notificationPolicy.enabled()
        val device = deviceProvider.create(deviceId, notificationsEnabled)
        val deviceFingerprint = fingerprint(device)
        if (stateStore.syncFingerprint(accountId, "device:$deviceId") != deviceFingerprint) {
            dataPlane.upsertDevice(device)
            stateStore.setSyncFingerprint(accountId, "device:$deviceId", deviceFingerprint)
        }
        val token = stateStore.pushToken()
        val pushFingerprint = sha256("${token.orEmpty()}|$notificationsEnabled".encodeToByteArray())
        if (stateStore.syncFingerprint(accountId, "push:$deviceId") != pushFingerprint) {
            if (token != null && notificationsEnabled) dataPlane.registerPushToken(deviceId, token)
            else dataPlane.unregisterPushToken(deviceId)
            stateStore.setSyncFingerprint(accountId, "push:$deviceId", pushFingerprint)
        }
    }

    private fun fingerprint(value: CloudTaskWrite): String =
        sha256(json.encodeToString(CloudTaskWrite.serializer(), value).encodeToByteArray())

    private fun fingerprint(value: CloudDeviceWrite): String =
        sha256(json.encodeToString(CloudDeviceWrite.serializer(), value).encodeToByteArray())

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private suspend fun cacheAndMaybeNotify(
        accountId: String,
        event: CloudEventRow,
        deviceId: String,
    ): Boolean {
        val isNew = local.cacheRemoteEvent(accountId, event)
        val notificationPending = local.isRemoteNotificationPending(accountId, event.eventId)
        val now = Instant.ofEpochMilli(nowEpochMillis()).toString()
        dataPlane.addReceipt(event.eventId, deviceId, ReceiptType.FETCHED, now)
        if (!notificationPending) return isNew
        if (!notificationPolicy.enabled()) {
            check(local.markRemoteNotificationHandled(accountId, event.eventId, nowEpochMillis())) {
                "remote notification state disappeared before it was handled"
            }
            return true
        }
        if (!notificationSink.publish(event)) return isNew
        check(local.markRemoteNotificationHandled(accountId, event.eventId, nowEpochMillis())) {
            "remote notification state disappeared before it was handled"
        }
        // Commit the local exactly-once boundary before the informational network receipt. A
        // receipt outage must never make the OS notification display twice on Worker retry.
        dataPlane.addReceipt(event.eventId, deviceId, ReceiptType.DISPLAYED, now)
        return true
    }

    /**
     * A notification permission/channel failure must not be hidden by an already advanced sync
     * cursor. Room owns the pending rows, so every later sync retries them without depending on a
     * duplicate FCM message or a repeated server change.
     */
    private suspend fun retryPendingRemoteNotifications(accountId: String, deviceId: String) {
        val pending = local.pendingRemoteNotifications(accountId, MAX_PENDING_NOTIFICATION_RETRIES)
        if (pending.isEmpty()) return
        if (!notificationPolicy.enabled()) {
            pending.forEach { event ->
                check(local.markRemoteNotificationHandled(accountId, event.eventId, nowEpochMillis())) {
                    "remote notification state disappeared before it was handled"
                }
            }
            return
        }
        pending.forEach { event ->
            if (!notificationSink.publish(event)) return@forEach
            val now = Instant.ofEpochMilli(nowEpochMillis()).toString()
            check(local.markRemoteNotificationHandled(accountId, event.eventId, nowEpochMillis())) {
                "remote notification state disappeared before it was handled"
            }
            dataPlane.addReceipt(event.eventId, deviceId, ReceiptType.DISPLAYED, now)
        }
    }

    private companion object {
        const val MAX_WRITE_BATCH = 100
        const val MAX_PULL_BATCH = 500
        const val MAX_EVENT_BATCHES_PER_RUN = 20
        const val MAX_PULL_BATCHES_PER_RUN = 20
        const val MAX_PENDING_NOTIFICATION_RETRIES = 100
        const val MAX_TASK_ID_FILTER = 100
    }
}
