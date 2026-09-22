package app.beyoureyes.core.data.cloud

import androidx.room.withTransaction
import app.beyoureyes.core.data.EventEntity
import app.beyoureyes.core.data.LocalTaskEntity
import app.beyoureyes.core.data.MonitorDatabase
import app.beyoureyes.core.data.RemoteEventSnapshotCache
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.ResolvedSamplingConfigEntity
import app.beyoureyes.core.data.TaskEntity
import app.beyoureyes.core.data.toResolvedSamplingConfigOrNull
import app.beyoureyes.core.domain.RuntimeMonitorRule
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface CloudLocalBridge {
    /** Local identities are used only to fence optional sync by the signed-in account. */
    suspend fun localTaskIds(): Set<String> = emptySet()
    suspend fun taskWrites(deviceId: String): List<CloudTaskWrite>
    suspend fun readyEvents(nowEpochMillis: Long, limit: Int): List<CloudEventWrite>

    /**
     * Returns only outbox events belonging to the supplied local task identities. The default
     * implementation keeps small test/local bridges correct; Room overrides it with a bounded
     * SQL query so foreign-account outbox rows cannot starve the current account's queue.
     */
    suspend fun readyEventsForTasks(
        nowEpochMillis: Long,
        limit: Int,
        taskIds: Set<String>,
    ): List<CloudEventWrite> = readyEvents(nowEpochMillis, limit)
        .filter { it.taskId in taskIds }

    suspend fun markEventsUploaded(eventIds: Set<String>)
    suspend fun cacheRemoteTaskSummary(accountId: String, summary: CloudTaskSummary): Boolean
    suspend fun cacheRemoteEvent(accountId: String, event: CloudEventRow): Boolean
    suspend fun isRemoteNotificationPending(accountId: String, eventId: String): Boolean
    suspend fun pendingRemoteNotifications(accountId: String, limit: Int): List<CloudEventRow>
    suspend fun markRemoteNotificationHandled(
        accountId: String,
        eventId: String,
        handledAtEpochMillis: Long,
    ): Boolean
    suspend fun removeCachedRemoteEvent(accountId: String, eventId: String): Boolean
    suspend fun removeCachedRemoteTask(accountId: String, taskId: String): Boolean
    suspend fun clearRemoteCache(): Int
}

/**
 * Reuses the existing Room Task/Event/Outbox truth. Remote events deliberately do not receive a
 * new Outbox row, preventing a peer-download -> upload loop.
 */
class RoomCloudBridge(
    private val database: MonitorDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val remoteSnapshotCache: RemoteEventSnapshotCache? = null,
) : CloudLocalBridge {
    override suspend fun localTaskIds(): Set<String> =
        database.monitoringDao().productTaskIds().toSet()

    override suspend fun taskWrites(deviceId: String): List<CloudTaskWrite> {
        requireUuidV7(deviceId)
        return database.withTransaction {
            val dao = database.monitoringDao()
            dao.productTaskIds()
                .mapNotNull { taskId -> dao.findProductTask(taskId) }
                .sortedWith(
                    compareByDescending<LocalTaskEntity> { it.createdAtEpochMillis }
                        .thenByDescending { it.taskId },
                )
                .filter { task -> task.isSetupCompleteForCloud() }
                .map { task ->
                    task.toCloudWrite(
                        deviceId = deviceId,
                        sampling = dao.findResolvedSamplingConfig(task.taskId),
                    )
                }
        }
    }

    override suspend fun readyEvents(nowEpochMillis: Long, limit: Int): List<CloudEventWrite> {
        require(nowEpochMillis >= 0)
        require(limit in 1..100)
        return database.withTransaction {
            val dao = database.monitoringDao()
            dao.readyOutbox(nowEpochMillis, limit).mapNotNull { outbox ->
                dao.findEvent(outbox.eventId)?.toCloudWrite()
            }
        }
    }

    override suspend fun readyEventsForTasks(
        nowEpochMillis: Long,
        limit: Int,
        taskIds: Set<String>,
    ): List<CloudEventWrite> {
        require(nowEpochMillis >= 0)
        require(limit in 1..100)
        if (taskIds.isEmpty()) return emptyList()
        require(taskIds.all(::isUuidV7)) { "local task IDs must be UUIDv7" }
        return database.withTransaction {
            val dao = database.monitoringDao()
            taskIds
                .chunked(MAX_TASK_ID_FILTER)
                .flatMap { taskIdBatch ->
                    dao.readyOutboxForTasks(nowEpochMillis, limit, taskIdBatch).mapNotNull { outbox ->
                        dao.findEvent(outbox.eventId)?.toCloudWrite()?.let {
                            outbox.nextAttemptAtEpochMillis to it
                        }
                    }
                }
                .distinctBy { (_, event) -> event.eventId }
                .sortedWith(compareBy<Pair<Long, CloudEventWrite>> { it.first }.thenBy { it.second.eventId })
                .take(limit)
                .map { (_, event) -> event }
        }
    }

    override suspend fun markEventsUploaded(eventIds: Set<String>) {
        require(eventIds.size <= 100)
        database.withTransaction {
            eventIds.forEach { eventId ->
                requireUuidV7(eventId)
                database.monitoringDao().markEventDelivered(eventId)
            }
        }
    }

    override suspend fun cacheRemoteTaskSummary(
        accountId: String,
        summary: CloudTaskSummary,
    ): Boolean =
        database.withTransaction {
            requireAccountId(accountId)
            requireUuidV7(summary.taskId)
            upsertRemoteTaskSummary(accountId, summary)
        }

    override suspend fun cacheRemoteEvent(accountId: String, event: CloudEventRow): Boolean =
        database.withTransaction {
            requireAccountId(accountId)
            val dao = database.monitoringDao()
            dao.findEvent(event.eventId)?.let { existingEvent ->
                val existingTask = dao.findTask(existingEvent.taskId)
                if (existingTask != null &&
                    isRemoteSnapshot(existingTask.runtimeSnapshotJson) &&
                    remoteSnapshotAccountId(existingTask.runtimeSnapshotJson) != accountId &&
                    dao.findProductTask(existingTask.taskId) == null
                ) {
                    dao.deleteTask(existingTask.taskId)
                } else {
                    return@withTransaction false
                }
            }
            val currentTask = dao.findTask(event.taskId)
            if (currentTask == null) {
                upsertRemoteTaskSummary(accountId, event.fallbackTaskSummary())
            } else if (!isRemoteSnapshotOwnedBy(currentTask.runtimeSnapshotJson, accountId)) {
                if (isRemoteSnapshot(currentTask.runtimeSnapshotJson) &&
                    dao.findProductTask(currentTask.taskId) == null
                ) {
                    dao.deleteTask(currentTask.taskId)
                    upsertRemoteTaskSummary(accountId, event.fallbackTaskSummary())
                } else {
                    // A truly local task/runtime row always wins over downloaded peer data.
                    return@withTransaction false
                }
            }
            val occurredAtMillis = Instant.parse(event.occurredAt).toEpochMilli()
            dao.insertEvent(
                EventEntity(
                    event.eventId,
                    event.taskId,
                    event.taskRevision,
                    event.episodeId,
                    event.sourceSequence,
                    occurredAtMillis,
                    event.payload.toString(),
                    event.displayText(),
                    0,
                    // FCM/sync owns this durable pending state. DAO local-notification recovery
                    // explicitly excludes all remote_event_cache task snapshots.
                    null,
                ),
            ) != -1L
        }

    override suspend fun isRemoteNotificationPending(
        accountId: String,
        eventId: String,
    ): Boolean =
        database.withTransaction {
            requireAccountId(accountId)
            requireUuidV7(eventId)
            database.monitoringDao().isPendingRemoteNotification(
                eventId,
                remoteSnapshotPrefix(accountId),
            )
        }

    override suspend fun pendingRemoteNotifications(
        accountId: String,
        limit: Int,
    ): List<CloudEventRow> = database.withTransaction {
        requireAccountId(accountId)
        require(limit in 1..100)
        val dao = database.monitoringDao()
        dao.pendingRemoteNotifications(
            remoteSnapshotPrefix(accountId),
            limit,
        ).map { event ->
            val snapshot = checkNotNull(dao.findTask(event.taskId)).runtimeSnapshotJson
            event.toCloudEventRow(checkNotNull(remoteSnapshotMonitoringDeviceId(snapshot)))
        }
    }

    override suspend fun markRemoteNotificationHandled(
        accountId: String,
        eventId: String,
        handledAtEpochMillis: Long,
    ): Boolean = database.withTransaction {
        requireAccountId(accountId)
        requireUuidV7(eventId)
        require(handledAtEpochMillis >= 0)
        database.monitoringDao().markRemoteNotificationDelivered(
            eventId,
            handledAtEpochMillis,
            remoteSnapshotPrefix(accountId),
        ) == 1
    }

    override suspend fun removeCachedRemoteEvent(accountId: String, eventId: String): Boolean {
        requireAccountId(accountId)
        requireUuidV7(eventId)
        val removed = database.withTransaction {
            val dao = database.monitoringDao()
            val event = dao.findEvent(eventId) ?: return@withTransaction false
            if (dao.findProductTask(event.taskId) != null) return@withTransaction false
            val task = dao.findTask(event.taskId) ?: return@withTransaction false
            if (!isRemoteSnapshotOwnedBy(task.runtimeSnapshotJson, accountId)) {
                return@withTransaction false
            }
            dao.deleteEvent(eventId) == 1
        }
        if (removed) reconcileRemoteSnapshotCache(accountId)
        return removed
    }

    override suspend fun removeCachedRemoteTask(accountId: String, taskId: String): Boolean {
        val removed = database.withTransaction {
            requireAccountId(accountId)
            requireUuidV7(taskId)
            val dao = database.monitoringDao()
            // A server tombstone may clear peer-only cached history. A locally configured task
            // stays authoritative and will be reconciled back to the account on the next sync.
            if (dao.findProductTask(taskId) != null) return@withTransaction false
            val task = dao.findTask(taskId) ?: return@withTransaction false
            if (!isRemoteSnapshotOwnedBy(task.runtimeSnapshotJson, accountId)) {
                return@withTransaction false
            }
            dao.deleteTask(taskId) == 1
        }
        if (removed) reconcileRemoteSnapshotCache(accountId)
        return removed
    }

    override suspend fun clearRemoteCache(): Int = database.withTransaction {
        database.monitoringDao().deleteAllRemoteCacheTasks()
    }

    private suspend fun reconcileRemoteSnapshotCache(accountId: String) {
        val cache = remoteSnapshotCache ?: return
        val retained = database.withTransaction {
            database.monitoringDao().remoteEventIdsForAccount(accountId).toSet()
        }
        cache.retain(accountId, retained)
    }

    private fun LocalTaskEntity.toCloudWrite(
        deviceId: String,
        sampling: ResolvedSamplingConfigEntity?,
    ): CloudTaskWrite {
        requireUuidV7(taskId)
        val rawTarget = json.parseToJsonElement(targetJson).jsonObject
        val rawRule = json.parseToJsonElement(ruleJson).jsonObject
        val type = rawTarget["type"]?.jsonPrimitive?.contentOrNull
        val expectedCapability = when (type) {
            "reference_images", "object_detection" -> "visual_target"
            "structured_reading" -> "structured_reading"
            else -> error("unsupported monitor target")
        }
        val resolvedSampling = requireTaskBoundSampling(sampling, expectedCapability)
        val cloudRule = when (type) {
            "reference_images", "object_detection" -> {
                visualCloudRule(rawRule, resolvedSampling.intervalMillis)
            }
            "structured_reading" -> buildJsonObject {
                rawRule.forEach { (key, value) ->
                    // These fields govern only the local reader setup/runtime boundary and are not
                    // part of the shared Rule union.
                    if (key != "source_kind" && key != "setup_complete") put(key, value)
                }
            }
            else -> error("unsupported monitor target")
        }
        val roi = when (type) {
            "reference_images" -> fullFrameRoi()
            "structured_reading" -> when (val manual = rawTarget["manual_roi"]) {
                null, JsonNull -> fullFrameRoi()
                is JsonObject -> manual
                else -> error("manual ROI must be null or an object")
            }
            "object_detection" -> fullFrameRoi()
            else -> error("unsupported monitor target")
        }
        val targetDefinition = when (type) {
            "reference_images" -> buildJsonObject {
                put("mode", "reference_images")
            }
            "structured_reading" -> buildJsonObject {
                put("mode", "none")
                put("confirmed_format", checkNotNull(rawTarget["confirmed_format"]))
            }
            "object_detection" -> buildJsonObject {
                put("mode", "object_detection")
                put("target_id", checkNotNull(rawTarget["target_id"]))
                put("label_zh_cn", checkNotNull(rawTarget["label_zh_cn"]))
                put("label_en", checkNotNull(rawTarget["label_en"]))
                rawTarget["labels"]?.let { put("labels", it) }
            }
            else -> error("unsupported monitor target")
        }
        return CloudTaskWrite(
            taskId = taskId,
            revision = revision,
            catalogVersion = resolvedSampling.catalogVersion,
            capabilityId = expectedCapability,
            title = title.take(100),
            monitoringDeviceId = deviceId,
            config = defaultTaskConfig(targetDefinition, cloudRule, roi, resolvedSampling),
        )
    }

    private fun LocalTaskEntity.requireTaskBoundSampling(
        entity: ResolvedSamplingConfigEntity?,
        expectedCapabilityId: String,
    ): ResolvedSamplingConfig {
        val sampling = requireNotNull(entity?.toResolvedSamplingConfigOrNull()) {
            "visual target cloud projection requires valid persisted sampling"
        }
        require(sampling.taskId == taskId && sampling.taskRevision == revision) {
            "sampling must match the projected task generation"
        }
        require(sampling.capabilityId == expectedCapabilityId) {
            "sampling must match the projected task capability"
        }
        require(
            sampling.packagePointer.identity.packageId == runtimePackageId &&
                sampling.packagePointer.identity.packageVersion == runtimePackageVersion &&
                sampling.packagePointer.canonicalManifestSha256 == runtimeManifestSha256
        ) {
            "sampling must match the projected task package"
        }
        return sampling
    }

    private fun visualCloudRule(rawRule: JsonObject, samplingIntervalMillis: Long): JsonObject {
        require(rawRule.keys == setOf("type", "condition", "duration_ms")) {
            "visual target rule must use the current exact local contract"
        }
        val type = rawRule.getValue("type").jsonPrimitive.content
        val condition = rawRule.getValue("condition").jsonPrimitive.content
        val durationMillis = rawRule.getValue("duration_ms").jsonPrimitive.content.toLong()
        require(durationMillis in setOf(1_000L, 3_000L, 5_000L, 10_000L, 30_000L, 60_000L))
        val runtimeRule = when (condition) {
            "appears" -> {
                require(type == "presence_duration")
                RuntimeMonitorRule.PresenceEpisode(
                    appearanceConfirmMillis = durationMillis,
                    disappearanceConfirmMillis = RuntimeMonitorRule.PresenceEpisode
                        .PRODUCT_DISAPPEARANCE_CONFIRM_MILLIS,
                    samplingIntervalMillis = samplingIntervalMillis,
                )
            }
            "remains" -> {
                require(type == "presence_duration")
                RuntimeMonitorRule.Presence(durationMillis, samplingIntervalMillis)
            }
            "disappears" -> {
                require(type == "absence_duration")
                RuntimeMonitorRule.Absence(durationMillis, samplingIntervalMillis)
            }
            else -> error("unsupported visual condition")
        }
        return runtimeRule.toCloudRule()
    }

    private fun RuntimeMonitorRule.toCloudRule(): JsonObject = when (this) {
        is RuntimeMonitorRule.PresenceEpisode -> buildJsonObject {
            put("type", "presence_duration")
            put("condition", "appears")
            put("duration_ms", appearanceConfirmMillis)
            put("min_positive_count", minimumObservationCount)
            put("max_positive_gap_ms", maximumObservationGapMillis)
            put("rearm_absence_ms", disappearanceConfirmMillis)
        }
        is RuntimeMonitorRule.Presence -> buildJsonObject {
            put("type", "presence_duration")
            put("condition", "remains")
            put("duration_ms", durationMillis)
            put("min_positive_count", minimumPositiveCount)
            put("max_positive_gap_ms", maximumPositiveGapMillis)
            put("rearm_absence_ms", rearmAbsenceMillis)
        }
        is RuntimeMonitorRule.Absence -> buildJsonObject {
            put("type", "absence_duration")
            put("condition", "disappears")
            put("duration_ms", durationMillis)
            put("min_negative_count", minimumNegativeCount)
            put("max_observation_gap_ms", maximumObservationGapMillis)
            put("rearm_presence_ms", rearmPresenceMillis)
        }
        else -> error("visual target requires a visual runtime rule")
    }

    /** A reader draft is local-only until the user has confirmed a value and saved its rule. */
    private fun LocalTaskEntity.isSetupCompleteForCloud(): Boolean {
        val targetType = json.parseToJsonElement(targetJson).jsonObject["type"]
            ?.jsonPrimitive
            ?.contentOrNull
        if (targetType != "structured_reading") return true
        return json.parseToJsonElement(ruleJson).jsonObject["setup_complete"]
            ?.jsonPrimitive
            ?.contentOrNull == "true"
    }

    private fun defaultTaskConfig(
        targetDefinition: JsonObject,
        rule: JsonObject,
        roi: JsonObject,
        sampling: ResolvedSamplingConfig,
    ) =
        buildJsonObject {
            put("target_definition", targetDefinition)
            put("roi", roi)
            put("sampling_policy", buildJsonObject { put("mode", "package_default") })
            put("rule", rule)
            put("route_binding", buildJsonObject {
                put("model_profile_key", sampling.modelProfileKey)
                put("recipe_id", sampling.recipeId)
                put("intent_key", sampling.intentKey)
            })
            put("package_binding", buildJsonObject {
                put("package_id", sampling.packagePointer.identity.packageId)
                put("package_version", sampling.packagePointer.identity.packageVersion)
                put("manifest_sha256", sampling.packagePointer.canonicalManifestSha256)
                put("artifact_identity_sha256", sampling.artifactIdentitySha256)
            })
        }

    private fun fullFrameRoi(): JsonObject = buildJsonObject {
        put("left", 0)
        put("top", 0)
        put("right", 1)
        put("bottom", 1)
    }

    private fun EventEntity.toCloudWrite(): CloudEventWrite {
        requireUuidV7(eventId)
        requireUuidV7(taskId)
        requireUuidV7(episodeId)
        val payload = json.parseToJsonElement(payloadJson).jsonObject
        require(!payload.containsMediaKey()) { "Event payload must not contain media" }
        return CloudEventWrite(
            eventId = eventId,
            taskId = taskId,
            taskRevision = taskRevision,
            episodeId = episodeId,
            sourceSequence = sourceSequence,
            occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis).toString(),
            payload = payload,
        )
    }

    private fun EventEntity.toCloudEventRow(monitoringDeviceId: String): CloudEventRow {
        requireUuidV7(eventId)
        requireUuidV7(taskId)
        requireUuidV7(episodeId)
        require(taskRevision >= 1 && sourceSequence >= 0 && occurredAtEpochMillis >= 0)
        val payload = json.parseToJsonElement(payloadJson).jsonObject
        require(!payload.containsMediaKey()) { "Event payload must not contain media" }
        return CloudEventRow(
            eventId = eventId,
            taskId = taskId,
            taskRevision = taskRevision,
            episodeId = episodeId,
            sourceSequence = sourceSequence,
            occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis).toString(),
            monitoringDeviceId = monitoringDeviceId,
            payload = payload,
        )
    }

    private fun upsertRemoteTaskSummary(accountId: String, summary: CloudTaskSummary): Boolean {
        val dao = database.monitoringDao()
        if (dao.findProductTask(summary.taskId) != null) return false
        var existing = dao.findTask(summary.taskId)
        if (existing != null && !isRemoteSnapshot(existing.runtimeSnapshotJson)) {
            return false
        }
        if (existing != null && !isRemoteSnapshotOwnedBy(existing.runtimeSnapshotJson, accountId)) {
            dao.deleteTask(summary.taskId)
            existing = null
        }
        if (existing != null && existing.revision > summary.revision) return false
        val snapshot = remoteTaskSnapshot(accountId, summary)
        if (existing != null &&
            existing.revision == summary.revision &&
            existing.runtimeSnapshotJson == snapshot &&
            !existing.isActive
        ) {
            return false
        }
        dao.upsertTask(TaskEntity(summary.taskId, summary.revision, snapshot, false))
        return true
    }

    private fun remoteTaskSnapshot(accountId: String, summary: CloudTaskSummary): String =
        buildJsonObject {
            put("schema_version", REMOTE_SNAPSHOT_SCHEMA)
            put("account_id", accountId)
            put("task_id", summary.taskId)
            put("task_revision", summary.revision)
            put("title", summary.title)
            put("capability_id", summary.capabilityId)
            put("target_definition_mode", summary.targetDefinitionMode)
            put("monitoring_device_id", summary.monitoringDeviceId)
        }.toString()

    private fun isRemoteSnapshot(raw: String): Boolean = raw.startsWith(REMOTE_SNAPSHOT_PREFIX)

    private fun isRemoteSnapshotOwnedBy(raw: String, accountId: String): Boolean =
        remoteSnapshotAccountId(raw) == accountId

    private fun remoteSnapshotAccountId(raw: String): String? {
        if (!raw.startsWith(REMOTE_SNAPSHOT_PREFIX)) return null
        val value = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return null
        if (value["schema_version"]?.jsonPrimitive?.contentOrNull != REMOTE_SNAPSHOT_SCHEMA) {
            return null
        }
        return value["account_id"]?.jsonPrimitive?.contentOrNull
    }

    private fun remoteSnapshotMonitoringDeviceId(raw: String): String? {
        if (!raw.startsWith(REMOTE_SNAPSHOT_PREFIX)) return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrNull()
            ?.get("monitoring_device_id")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(::isUuidV7)
    }

    private fun remoteSnapshotPrefix(accountId: String): String =
        "{\"schema_version\":\"$REMOTE_SNAPSHOT_SCHEMA\",\"account_id\":\"$accountId\""

    private fun CloudEventRow.fallbackTaskSummary(): CloudTaskSummary {
        val type = payload.stringOrNull("type")
        val capability = when (type) {
            "reading_threshold_crossed" -> "structured_reading"
            "state_transition" -> "visible_state"
            else -> "visual_target"
        }
        val targetDefinitionMode = if (type in setOf("object_episode", "visual_condition_met")) "object_detection" else {
            if (capability == "structured_reading") "none" else "reference_images"
        }
        val title = when (type) {
            "object_episode", "visual_condition_met" -> payload.stringOrNull("target_id")
            "reading_threshold_crossed" -> "Reading monitor"
            "state_transition" -> "State monitor"
            else -> null
        } ?: "Monitor from another device"
        return CloudTaskSummary(
            schemaVersion = "4.0",
            taskId = taskId,
            revision = taskRevision,
            capabilityId = capability,
            title = title,
            targetDefinition = buildJsonObject { put("mode", targetDefinitionMode) },
            monitoringDeviceId = monitoringDeviceId,
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.containsMediaKey(): Boolean = when (this) {
        is JsonObject -> entries.any { (key, value) ->
            key in MEDIA_KEYS || value.containsMediaKey()
        }
        is JsonArray -> any { value -> value.containsMediaKey() }
        is JsonNull, is JsonPrimitive -> false
    }

    private companion object {
        const val MAX_TASK_ID_FILTER = 100
        const val REMOTE_SNAPSHOT_SCHEMA = "remote_event_cache_v3"
        const val REMOTE_SNAPSHOT_PREFIX = "{\"schema_version\":\"remote_event_cache_v3\""
        val MEDIA_KEYS = setOf(
            "image", "frame", "photo", "video", "audio", "blob", "data_url", "media_url",
        )
        val UUID_V7 = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

        fun requireUuidV7(value: String) {
            require(UUID_V7.matches(value)) { "identity must be UUIDv7" }
        }

        fun isUuidV7(value: String): Boolean = UUID_V7.matches(value)

        fun requireAccountId(value: String) {
            require(ACCOUNT_UUID.matches(value)) { "accountId must be UUID" }
        }

        val ACCOUNT_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
    }
}
