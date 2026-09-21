package app.beyoureyes.core.data.cloud

import android.util.Base64
import androidx.room.withTransaction
import app.beyoureyes.core.data.MonitorDatabase
import app.beyoureyes.core.data.ReferenceEventSnapshotStore
import app.beyoureyes.core.data.RemoteEventSnapshotCache
import app.beyoureyes.core.data.UuidV7
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RemoteSnapshotRequestSpec(
    val eventId: String,
    val taskId: String,
    val sourceDeviceId: String,
) {
    init {
        require(listOf(eventId, taskId, sourceDeviceId).all(UuidV7::isValid))
    }
}

sealed interface RemoteSnapshotViewState {
    data object Loading : RemoteSnapshotViewState
    data class Ready(val privateUri: String) : RemoteSnapshotViewState
    data class Unavailable(val reason: RemoteSnapshotUnavailableReason) : RemoteSnapshotViewState
}

enum class RemoteSnapshotUnavailableReason {
    SOURCE_OFFLINE,
    PHOTO_UNAVAILABLE,
    SECURE_TRANSFER_FAILED,
}

/**
 * Online-only encrypted snapshot exchange. Supabase receives request metadata and HPKE ciphertext,
 * while plaintext exists only in each endpoint's app-private storage or memory.
 */
class RemoteSnapshotTransferController(
    private val accountState: StateFlow<CloudAccountState>,
    private val accessGranted: StateFlow<Boolean>,
    private val dataPlane: CloudDataPlane,
    private val relay: RemoteSnapshotRelay,
    private val stateStore: CloudLocalStateStore,
    private val database: MonitorDatabase,
    filesDir: java.io.File,
    private val json: Json = cloudWireJson(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val sourceSnapshots = ReferenceEventSnapshotStore.openAppPrivate(filesDir)
    private val remoteCache = RemoteEventSnapshotCache.openAppPrivate(filesDir)
    private val mutableStates = MutableStateFlow<Map<String, RemoteSnapshotViewState>>(emptyMap())
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val served = ConcurrentHashMap<String, ServedResponse>()
    private val fetchSemaphore = Semaphore(1)
    private val operationMutex = Mutex()
    private val sourceRateLimiter = SourceRateLimiter()

    @Volatile
    private var activeAccountId: String? = null

    @Volatile
    private var cacheGeneration = 0L

    @Volatile
    private var currentDeviceId: String? = null

    val states: StateFlow<Map<String, RemoteSnapshotViewState>> = mutableStates.asStateFlow()

    init {
        scope.launch {
            var previousAccountId: String? = null
            combine(accountState, accessGranted) { account, granted -> account to granted }
                .collectLatest { (state, granted) ->
                val accountId = (state as? CloudAccountState.SignedIn)?.accountId
                if (previousAccountId != null && previousAccountId != accountId) {
                    operationMutex.withLock {
                        cacheGeneration += 1
                        remoteCache.clearAccount(checkNotNull(previousAccountId))
                        mutableStates.value = emptyMap()
                    }
                }
                pending.values.forEach { it.response.cancel() }
                pending.clear()
                activeAccountId = accountId.takeIf { granted }
                previousAccountId = accountId
                if (accountId == null || !granted || !relay.configured) {
                    currentDeviceId = null
                    relay.disconnect()
                    return@collectLatest
                }
                currentDeviceId = stateStore.deviceId(accountId)
                relay.connect(accountId)
                coroutineScope {
                    launch { relay.requests.collect { request -> handleSourceRequest(accountId, request) } }
                    launch { relay.responses.collect(::handleRequesterResponse) }
                    awaitCancellation()
                }
                }
        }
    }

    fun request(spec: RemoteSnapshotRequestSpec, force: Boolean = false) {
        scope.launch {
            val target = operationMutex.withLock {
                val accountId = activeAccountId ?: return@withLock null
                remoteCache.uriFor(accountId, spec.sourceDeviceId, spec.eventId)?.let { uri ->
                    mutableStates.update { it + (spec.eventId to RemoteSnapshotViewState.Ready(uri)) }
                    return@withLock null
                }
                val existing = mutableStates.value[spec.eventId]
                if (!force && (existing is RemoteSnapshotViewState.Loading || existing is RemoteSnapshotViewState.Ready)) {
                    return@withLock null
                }
                mutableStates.update { it + (spec.eventId to RemoteSnapshotViewState.Loading) }
                accountId to cacheGeneration
            } ?: return@launch
            fetchSemaphore.withPermit { fetch(target.first, spec, target.second) }
        }
    }

    suspend fun reconcileCache() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val accountId = activeAccountId ?: return@withContext
            val retained = database.withTransaction {
                database.monitoringDao().remoteEventIdsForAccount(accountId).toSet()
            }
            remoteCache.retain(accountId, retained)
            mutableStates.update { current -> current.filterKeys(retained::contains) }
        }
    }

    suspend fun clearCurrentAccountCache() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            // AccountController calls this at the auth boundary, before the StateFlow collector may
            // observe the new identity. Cancel every in-flight request and served response so an old
            // relay callback cannot repopulate UI or private cache after the explicit clear.
            cacheGeneration += 1
            pending.values.forEach { it.response.cancel() }
            pending.clear()
            served.clear()
            activeAccountId?.let(remoteCache::clearAccount)
            mutableStates.value = emptyMap()
        }
    }

    private suspend fun fetch(accountId: String, spec: RemoteSnapshotRequestSpec, generation: Long) {
        if (activeAccountId != accountId || cacheGeneration != generation) {
            clearLoadingState(spec.eventId, generation)
            return
        }
        val requesterDeviceId = currentDeviceId
            ?: stateStore.deviceId(accountId).also { currentDeviceId = it }
        if (requesterDeviceId == spec.sourceDeviceId) {
            markUnavailable(accountId, spec.eventId, RemoteSnapshotUnavailableReason.PHOTO_UNAVAILABLE, generation)
            return
        }
        val issuedAt = nowEpochMillis()
        val recipient = runCatching { RemoteSnapshotCrypto.newRecipient() }.getOrElse {
            markUnavailable(accountId, spec.eventId, RemoteSnapshotUnavailableReason.SECURE_TRANSFER_FAILED, generation)
            return
        }
        val request = RemoteSnapshotRequest(
            requestId = UuidV7.generate(issuedAt),
            eventId = spec.eventId,
            taskId = spec.taskId,
            sourceDeviceId = spec.sourceDeviceId,
            requesterDeviceId = requesterDeviceId,
            recipientPublicKey = Base64.encodeToString(recipient.publicKeysetBytes, Base64.NO_WRAP),
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = issuedAt + REQUEST_TTL_MILLIS,
        )
        val deferred = CompletableDeferred<RemoteSnapshotResponse>()
        val pendingRequest = PendingRequest(accountId, spec, request, recipient, deferred)
        pending[request.requestId] = pendingRequest
        try {
            var response: RemoteSnapshotResponse? = null
            repeat(REQUEST_ATTEMPTS) { attempt ->
                if (response != null || activeAccountId != accountId) return@repeat
                val sent = runCatching {
                    relay.connect(accountId)
                    relay.send(request)
                }.isSuccess
                if (!sent) {
                    if (attempt + 1 < REQUEST_ATTEMPTS) delay(RETRY_DELAY_MILLIS)
                    return@repeat
                }
                response = withTimeoutOrNull(RESPONSE_TIMEOUT_MILLIS) { deferred.await() }
            }
            val received = response
            if (received == null) {
                markUnavailable(accountId, spec.eventId, RemoteSnapshotUnavailableReason.SOURCE_OFFLINE, generation)
                return
            }
            if (received.status == RemoteSnapshotResponse.STATUS_UNAVAILABLE) {
                markUnavailable(accountId, spec.eventId, RemoteSnapshotUnavailableReason.PHOTO_UNAVAILABLE, generation)
                return
            }
            val jpeg = runCatching {
                val ciphertext = Base64.decode(checkNotNull(received.ciphertextBase64), Base64.NO_WRAP)
                RemoteSnapshotCrypto.decrypt(
                    recipient,
                    ciphertext,
                    RemoteSnapshotCrypto.contextInfo(accountId, request),
                )
            }.getOrElse {
                markUnavailable(accountId, spec.eventId, RemoteSnapshotUnavailableReason.SECURE_TRANSFER_FAILED, generation)
                return
            }
            val uri = runCatching {
                operationMutex.withLock {
                    if (activeAccountId != accountId || cacheGeneration != generation) {
                        return@withLock null
                    }
                    remoteCache.store(accountId, spec.sourceDeviceId, spec.eventId, jpeg)
                }
            }.getOrElse {
                markUnavailable(accountId, spec.eventId, RemoteSnapshotUnavailableReason.SECURE_TRANSFER_FAILED, generation)
                return
            }
            if (uri == null) {
                clearLoadingState(spec.eventId, generation)
                return
            }
            if (activeAccountId == accountId && cacheGeneration == generation) {
                mutableStates.update { it + (spec.eventId to RemoteSnapshotViewState.Ready(uri)) }
            } else {
                clearLoadingState(spec.eventId, generation)
            }
        } finally {
            pending.remove(request.requestId)
        }
    }

    private fun handleRequesterResponse(response: RemoteSnapshotResponse) {
        val current = pending[response.requestId] ?: return
        if (
            response.eventId != current.spec.eventId ||
            response.sourceDeviceId != current.spec.sourceDeviceId ||
            response.requesterDeviceId != current.request.requesterDeviceId ||
            response.expiresAtEpochMillis != current.request.expiresAtEpochMillis ||
            nowEpochMillis() > response.expiresAtEpochMillis
        ) return
        current.response.complete(response)
    }

    private suspend fun handleSourceRequest(accountId: String, request: RemoteSnapshotRequest) {
        val sourceDeviceId = currentDeviceId ?: return
        val now = nowEpochMillis()
        if (
            activeAccountId != accountId ||
            request.sourceDeviceId != sourceDeviceId ||
            request.requesterDeviceId == sourceDeviceId ||
            now !in request.issuedAtEpochMillis..request.expiresAtEpochMillis
        ) return

        served.entries.removeIf { (_, value) -> value.expiresAtEpochMillis < now }
        served[request.requestId]?.let { cached ->
            runCatching { relay.send(cached.response) }
            return
        }
        if (!sourceRateLimiter.allow(request.requesterDeviceId, request.requestId, now)) return

        val requesterIsActive = runCatching {
            dataPlane.peerDevices().any { device ->
                device.deviceId == request.requesterDeviceId && device.revokedAt == null
            }
        }.getOrDefault(false)
        if (!requesterIsActive) return

        val jpeg = withContext(Dispatchers.IO) {
            database.withTransaction {
                val dao = database.monitoringDao()
                val event = dao.findEvent(request.eventId) ?: return@withTransaction null
                if (event.taskId != request.taskId || dao.findProductTask(event.taskId) == null) {
                    return@withTransaction null
                }
                val payload = runCatching { json.parseToJsonElement(event.payloadJson).jsonObject }
                    .getOrNull() ?: return@withTransaction null
                if (
                    payload["type"]?.jsonPrimitive?.contentOrNull != "object_episode" ||
                    payload["condition"]?.jsonPrimitive?.contentOrNull != "appeared"
                ) return@withTransaction null
                sourceSnapshots.remotePreviewBytes(event.taskId, event.eventId)
            }
        }

        val response = if (jpeg == null) {
            RemoteSnapshotResponse(
                requestId = request.requestId,
                eventId = request.eventId,
                sourceDeviceId = request.sourceDeviceId,
                requesterDeviceId = request.requesterDeviceId,
                status = RemoteSnapshotResponse.STATUS_UNAVAILABLE,
                expiresAtEpochMillis = request.expiresAtEpochMillis,
            )
        } else {
            val ciphertext = runCatching {
                RemoteSnapshotCrypto.encrypt(
                    Base64.decode(request.recipientPublicKey, Base64.NO_WRAP),
                    jpeg,
                    RemoteSnapshotCrypto.contextInfo(accountId, request),
                )
            }.getOrNull() ?: return
            RemoteSnapshotResponse(
                requestId = request.requestId,
                eventId = request.eventId,
                sourceDeviceId = request.sourceDeviceId,
                requesterDeviceId = request.requesterDeviceId,
                status = RemoteSnapshotResponse.STATUS_OK,
                ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                expiresAtEpochMillis = request.expiresAtEpochMillis,
            )
        }
        served[request.requestId] = ServedResponse(request.expiresAtEpochMillis, response)
        runCatching { relay.send(response) }
    }

    /**
     * A request may finish after auth changes. Do not publish its terminal state into the next
     * account's UI, even when the event id happens to be identical across accounts.
     */
    private fun markUnavailable(
        accountId: String,
        eventId: String,
        reason: RemoteSnapshotUnavailableReason,
        generation: Long,
    ) {
        mutableStates.update { current ->
            if (activeAccountId != accountId || cacheGeneration != generation) current
            else current + (eventId to RemoteSnapshotViewState.Unavailable(reason))
        }
    }

    private fun clearLoadingState(eventId: String, generation: Long) {
        if (cacheGeneration != generation) return
        mutableStates.update { current ->
            if (current[eventId] is RemoteSnapshotViewState.Loading) current - eventId else current
        }
    }

    private data class PendingRequest(
        val accountId: String,
        val spec: RemoteSnapshotRequestSpec,
        val request: RemoteSnapshotRequest,
        val recipient: RemoteSnapshotRecipient,
        val response: CompletableDeferred<RemoteSnapshotResponse>,
    )

    private data class ServedResponse(
        val expiresAtEpochMillis: Long,
        val response: RemoteSnapshotResponse,
    )

    private class SourceRateLimiter {
        private val windows = ConcurrentHashMap<String, ArrayDeque<Entry>>()

        fun allow(deviceId: String, requestId: String, now: Long): Boolean {
            val window = windows.getOrPut(deviceId) { ArrayDeque() }
            synchronized(window) {
                while (window.isNotEmpty() && now - window.first.timestamp > RATE_WINDOW_MILLIS) {
                    window.removeFirst()
                }
                if (window.any { it.requestId == requestId }) return true
                if (window.size >= MAX_REQUESTS_PER_WINDOW) return false
                window.addLast(Entry(requestId, now))
                return true
            }
        }

        private data class Entry(val requestId: String, val timestamp: Long)
    }

    private companion object {
        const val REQUEST_TTL_MILLIS = 30_000L
        const val RESPONSE_TIMEOUT_MILLIS = 4_000L
        const val RETRY_DELAY_MILLIS = 500L
        const val REQUEST_ATTEMPTS = 2
        const val RATE_WINDOW_MILLIS = 60_000L
        const val MAX_REQUESTS_PER_WINDOW = 6
    }
}
