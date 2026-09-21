package app.beyoureyes.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

private val REMOTE_CAPABILITY_IDS = setOf(
    "visual_target",
    "visible_state",
    "structured_reading",
)
private val REMOTE_TASK_SUMMARY_FIELDS = setOf(
    "schema_version",
    "account_id",
    "task_id",
    "task_revision",
    "title",
    "capability_id",
    "target_definition_mode",
    "monitoring_device_id",
)

/** Downloaded peer metadata for display only. It can never become a runnable local task. */
data class StoredRemoteTaskSummary(
    val accountId: String,
    val taskId: String,
    val revision: Long,
    val title: String,
    val capabilityId: String,
    val targetDefinitionMode: String,
    val monitoringDeviceId: String,
) {
    init {
        require(isAccountId(accountId)) { "accountId must be UUID" }
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        require(revision >= 1)
        require(title.isNotBlank() && title.length <= 100)
        require(capabilityId in REMOTE_CAPABILITY_IDS)
        require(targetDefinitionMode in setOf("object_detection", "reference_images", "none"))
        require(UuidV7.isValid(monitoringDeviceId))
    }
}

/** Editable product configuration; it is intentionally not a RuntimeSnapshot. */
data class StoredLocalTask(
    val taskId: String,
    val revision: Long,
    val title: String,
    val targetMode: String,
    val targetJson: String,
    val ruleJson: String,
    val materialRefsJson: String,
    val createdAtEpochMillis: Long,
    val runtimePackagePointer: ModelPackagePointer? = null,
) {
    init {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        require(revision >= 1)
        require(title.isNotBlank())
        require(targetMode.matches(Regex("^[a-z][a-z0-9_]{1,63}$")))
        require(targetJson.isNotBlank())
        require(ruleJson.isNotBlank())
        require(materialRefsJson.isNotBlank())
        require(createdAtEpochMillis >= 0)
    }
}

enum class LocalTaskInsertResult {
    INSERTED,
    CAPACITY_REACHED,
    ALREADY_EXISTS,
}

class RoomMonitorStore(
    database: MonitorDatabase,
    eventLimit: Int = 100,
) {
    private val dao = database.monitoringDao()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(eventLimit in 1..500)
    }

    val tasks: Flow<List<StoredLocalTask>> = dao.observeProductTasks().map { values ->
        values.map { it.toStoredTask() }
    }

    val remoteTasks: Flow<List<StoredRemoteTaskSummary>> =
        dao.observeRemoteTaskSnapshots().map { values ->
            values.mapNotNull(::readRemoteTaskSummary)
        }

    val events: Flow<List<TimelineEvent>> = dao.observeLatestEvents(eventLimit).map { values ->
        values.map { it.toTimelineEvent() }
    }

    fun eventsForTask(taskId: String, limit: Int = 100): Flow<List<TimelineEvent>> {
        require(UuidV7.isValid(taskId))
        require(limit in 1..500)
        return dao.observeLatestEventsForTask(taskId, limit).map { values ->
            values.map { it.toTimelineEvent() }
        }
    }

    /** One-shot existence probe used by the gentle no-hit reminder. */
    suspend fun hasAnyEvent(taskId: String): Boolean = withContext(Dispatchers.IO) {
        require(UuidV7.isValid(taskId))
        dao.hasAnyEventForTask(taskId)
    }

    suspend fun insert(
        task: StoredLocalTask,
        maximumTasks: Int,
    ): LocalTaskInsertResult = withContext(Dispatchers.IO) {
        require(task.targetMode != "structured_reading") {
            "reading creation requires an atomic configured task and sampling insert"
        }
        when (dao.insertProductTaskIfCapacity(task.toEntity(), maximumTasks)) {
            1 -> LocalTaskInsertResult.INSERTED
            0 -> LocalTaskInsertResult.CAPACITY_REACHED
            2 -> LocalTaskInsertResult.ALREADY_EXISTS
            else -> error("unknown local task insert result")
        }
    }

    /**
     * Inserts a fully configured manual or routed reading and its exact runtime/sampling binding
     * as one Room transaction. No incomplete product task is visible while camera confirmation is
     * still in memory.
     */
    suspend fun insertConfiguredReading(
        task: StoredLocalTask,
        sampling: ResolvedSamplingConfig,
        maximumTasks: Int,
    ): LocalTaskInsertResult = withContext(Dispatchers.IO) {
        requireAtomicConfiguredReadingInsert(task, sampling)
        when (
            dao.insertProductTaskAndSamplingIfCapacity(
                task.toEntity(),
                sampling.toEntity(),
                maximumTasks,
            )
        ) {
            1 -> LocalTaskInsertResult.INSERTED
            0 -> LocalTaskInsertResult.CAPACITY_REACHED
            2 -> LocalTaskInsertResult.ALREADY_EXISTS
            else -> error("unknown configured reading insert result")
        }
    }

    /** Atomically inserts one routed reference task and its exact resolved sampling generation. */
    suspend fun insertConfiguredReference(
        task: StoredLocalTask,
        sampling: ResolvedSamplingConfig,
        maximumTasks: Int,
    ): LocalTaskInsertResult = withContext(Dispatchers.IO) {
        requireAtomicConfiguredReferenceInsert(task, sampling)
        when (
            dao.insertProductTaskAndSamplingIfCapacity(
                task.toEntity(),
                sampling.toEntity(),
                maximumTasks,
            )
        ) {
            1 -> LocalTaskInsertResult.INSERTED
            0 -> LocalTaskInsertResult.CAPACITY_REACHED
            2 -> LocalTaskInsertResult.ALREADY_EXISTS
            else -> error("unknown configured reference insert result")
        }
    }

    /** Atomically inserts one finite object-class task and its exact runtime binding. */
    suspend fun insertConfiguredObject(
        task: StoredLocalTask,
        sampling: ResolvedSamplingConfig,
        maximumTasks: Int,
    ): LocalTaskInsertResult = withContext(Dispatchers.IO) {
        requireAtomicConfiguredObjectInsert(task, sampling)
        when (
            dao.insertProductTaskAndSamplingIfCapacity(
                task.toEntity(),
                sampling.toEntity(),
                maximumTasks,
            )
        ) {
            1 -> LocalTaskInsertResult.INSERTED
            0 -> LocalTaskInsertResult.CAPACITY_REACHED
            2 -> LocalTaskInsertResult.ALREADY_EXISTS
            else -> error("unknown configured object insert result")
        }
    }

    suspend fun find(taskId: String): StoredLocalTask? = withContext(Dispatchers.IO) {
        dao.findProductTask(taskId)?.toStoredTask()
    }

    /** Binds only the still-current task revision to the exact verified immutable package. */
    suspend fun bindRuntimePackage(config: ResolvedSamplingConfig): Boolean =
        withContext(Dispatchers.IO) {
            dao.bindRuntimePackageAndSampling(config.toEntity())
        }

    /** Atomically persists an ROI-bearing target revision without reselecting the same package. */
    suspend fun reviseTargetAndCarryRuntime(
        expected: ResolvedSamplingConfig,
        targetJson: String,
    ): ResolvedSamplingConfig? = withContext(Dispatchers.IO) {
        require(targetJson.isNotBlank())
        val nextRevision = Math.addExact(expected.taskRevision, 1L)
        if (dao.reviseProductTaskTargetAndCarrySampling(
                expected.toEntity(),
                nextRevision,
                targetJson,
            )
        ) {
            expected.copy(taskRevision = nextRevision)
        } else {
            null
        }
    }

    suspend fun findResolvedSamplingConfig(taskId: String): ResolvedSamplingConfig? =
        withContext(Dispatchers.IO) {
            require(UuidV7.isValid(taskId))
            dao.findResolvedSamplingConfig(taskId)?.toResolvedSamplingConfigOrNull()
        }

    /** Compare-and-set persistence for one already policy-validated current interval. */
    suspend fun updateResolvedSamplingConfig(
        expected: ResolvedSamplingConfig,
        replacement: ResolvedSamplingConfig,
    ): Boolean = withContext(Dispatchers.IO) {
        require(expected.copy(intervalMillis = replacement.intervalMillis) == replacement) {
            "sampling adjustment may only change intervalMillis"
        }
        dao.compareAndSetResolvedSamplingConfig(expected.toEntity(), replacement.toEntity())
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.productTaskCount() }

    suspend fun rename(taskId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        require(UuidV7.isValid(taskId))
        require(name.isNotBlank() && name.length <= 100)
        dao.renameMonitor(taskId, name) == 1
    }

    suspend fun reviseRuleAndCarryRuntime(
        expected: ResolvedSamplingConfig,
        ruleJson: String,
    ): ResolvedSamplingConfig? = withContext(Dispatchers.IO) {
        require(ruleJson.isNotBlank())
        val nextRevision = Math.addExact(expected.taskRevision, 1L)
        if (dao.reviseMonitorRuleAndCarrySampling(expected.toEntity(), nextRevision, ruleJson)) {
            expected.copy(taskRevision = nextRevision)
        } else {
            null
        }
    }

    suspend fun reviseReadingTargetAndRuleAndCarryRuntime(
        expected: ResolvedSamplingConfig,
        targetJson: String,
        ruleJson: String,
    ): ResolvedSamplingConfig? = withContext(Dispatchers.IO) {
        require(targetJson.isNotBlank() && ruleJson.isNotBlank())
        val nextRevision = Math.addExact(expected.taskRevision, 1L)
        if (dao.reviseReadingTargetAndRuleAndCarrySampling(
                expected.toEntity(),
                nextRevision,
                targetJson,
                ruleJson,
            )
        ) {
            expected.copy(taskRevision = nextRevision)
        } else {
            null
        }
    }

    suspend fun reviseReferenceMaterialsAndCarryRuntime(
        expected: ResolvedSamplingConfig,
        materialRefsJson: String,
    ): ResolvedSamplingConfig? = withContext(Dispatchers.IO) {
        require(materialRefsJson.isNotBlank())
        val nextRevision = Math.addExact(expected.taskRevision, 1L)
        if (dao.reviseReferenceMaterialsAndCarrySampling(
                expected.toEntity(),
                nextRevision,
                materialRefsJson,
            )
        ) {
            expected.copy(taskRevision = nextRevision)
        } else {
            null
        }
    }

    suspend fun reviseReferenceMaterialsWithoutRuntime(
        taskId: String,
        expectedRevision: Long,
        materialRefsJson: String,
    ): Long? = withContext(Dispatchers.IO) {
        require(UuidV7.isValid(taskId))
        require(expectedRevision >= 1)
        require(materialRefsJson.isNotBlank())
        val nextRevision = Math.addExact(expectedRevision, 1L)
        nextRevision.takeIf {
            dao.reviseReferenceMaterialsWithoutRuntime(
                taskId,
                expectedRevision,
                nextRevision,
                materialRefsJson,
            )
        }
    }

    suspend fun deleteTaskAndRuntime(taskId: String): Boolean = withContext(Dispatchers.IO) {
        dao.deleteProductTaskAndRuntime(taskId)
    }

    private fun StoredLocalTask.toEntity() = LocalTaskEntity(
        taskId,
        revision,
        title,
        targetMode,
        targetJson,
        ruleJson,
        materialRefsJson,
        createdAtEpochMillis,
        runtimePackagePointer?.identity?.packageId,
        runtimePackagePointer?.identity?.packageVersion,
        runtimePackagePointer?.canonicalManifestSha256,
    )

    private fun LocalTaskEntity.toStoredTask() = StoredLocalTask(
        taskId = taskId,
        revision = revision,
        title = title,
        targetMode = targetMode,
        targetJson = targetJson,
        ruleJson = ruleJson,
        materialRefsJson = materialRefsJson,
        createdAtEpochMillis = createdAtEpochMillis,
        runtimePackagePointer = runtimePackagePointerOrNull(),
    )

    private fun LocalTaskEntity.runtimePackagePointerOrNull(): ModelPackagePointer? {
        val values = listOf(runtimePackageId, runtimePackageVersion, runtimeManifestSha256)
        if (values.all { it == null }) return null
        if (values.any { it == null }) return null
        return runCatching {
            ModelPackagePointer(
                identity = ModelPackageIdentity(
                    checkNotNull(runtimePackageId),
                    checkNotNull(runtimePackageVersion),
                ),
                canonicalManifestSha256 = checkNotNull(runtimeManifestSha256),
            )
        }.getOrNull()
    }

    private fun ResolvedSamplingConfig.toEntity() = ResolvedSamplingConfigEntity(
        taskId,
        taskRevision,
        catalogVersion,
        capabilityId,
        modelProfileKey,
        recipeId,
        intentKey,
        packagePointer.identity.packageId,
        packagePointer.identity.packageVersion,
        packagePointer.canonicalManifestSha256,
        artifactIdentitySha256,
        deviceFingerprintSha256,
        intervalMillis,
        manifestMinimumIntervalMillis,
        manifestMaximumIntervalMillis,
        adaptiveEnabled,
    )

    private fun TimelineEventRecord.toTimelineEvent(): TimelineEvent {
        val summary = taskRuntimeSnapshotJson?.let(::readRemoteTaskSummaryFields)
        return TimelineEvent(
            eventId = event.eventId,
            taskId = event.taskId,
            taskRevision = event.taskRevision,
            occurredAtEpochMillis = event.occurredAtEpochMillis,
            notificationText = event.notificationText,
            payloadJson = event.payloadJson,
            remoteTaskTitle = summary?.first,
            remoteCapabilityId = summary?.second,
            remoteAccountId = summary?.third,
            isRemoteTask = summary != null,
        )
    }

    private fun readRemoteTaskSummary(entity: TaskEntity): StoredRemoteTaskSummary? {
        if (entity.isActive || !UuidV7.isValid(entity.taskId) || entity.revision < 1) return null
        val value = parseRemoteTaskSummary(entity.runtimeSnapshotJson) ?: return null
        if (value.stringOrNull("schema_version") != "remote_event_cache_v3") return null
        val accountId = value.stringOrNull("account_id") ?: return null
        val taskId = value.stringOrNull("task_id") ?: return null
        val revision = value.longOrNull("task_revision") ?: return null
        val title = value.stringOrNull("title") ?: return null
        val capabilityId = value.stringOrNull("capability_id") ?: return null
        val targetDefinitionMode = value.stringOrNull("target_definition_mode") ?: return null
        val monitoringDeviceId = value.stringOrNull("monitoring_device_id") ?: return null
        if (taskId != entity.taskId || revision != entity.revision) return null
        return runCatching {
            StoredRemoteTaskSummary(
                accountId,
                taskId,
                revision,
                title,
                capabilityId,
                targetDefinitionMode,
                monitoringDeviceId,
            )
        }.getOrNull()
    }

    private fun readRemoteTaskSummaryFields(raw: String): Triple<String?, String?, String?>? {
        val value = parseRemoteTaskSummary(raw) ?: return null
        return Triple(
            value.stringOrNull("title"),
            value.stringOrNull("capability_id"),
            value.stringOrNull("account_id"),
        )
    }

    private fun parseRemoteTaskSummary(raw: String): JsonObject? {
        val value = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return null
        if (value.stringOrNull("schema_version") != "remote_event_cache_v3") return null
        if (value.keys != REMOTE_TASK_SUMMARY_FIELDS) return null
        return value
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

}

internal fun ResolvedSamplingConfigEntity.toResolvedSamplingConfigOrNull() = runCatching {
    ResolvedSamplingConfig(
        taskId = taskId,
        taskRevision = taskRevision,
        catalogVersion = catalogVersion,
        capabilityId = capabilityId,
        modelProfileKey = modelProfileKey,
        recipeId = recipeId,
        intentKey = intentKey,
        packagePointer = ModelPackagePointer(
            identity = ModelPackageIdentity(packageId, packageVersion),
            canonicalManifestSha256 = manifestSha256,
        ),
        artifactIdentitySha256 = artifactIdentitySha256,
        deviceFingerprintSha256 = deviceFingerprintSha256,
        intervalMillis = intervalMillis,
        manifestMinimumIntervalMillis = manifestMinimumIntervalMillis,
        manifestMaximumIntervalMillis = manifestMaximumIntervalMillis,
        adaptiveEnabled = adaptiveEnabled,
    )
}.getOrNull()

internal fun requireAtomicConfiguredReadingInsert(
    task: StoredLocalTask,
    sampling: ResolvedSamplingConfig,
) {
    require(task.targetMode == "structured_reading") {
        "atomic configured creation only accepts a reading task"
    }
    requireAtomicTaskAndSamplingInsert(task, sampling)
}

internal fun requireAtomicConfiguredReferenceInsert(
    task: StoredLocalTask,
    sampling: ResolvedSamplingConfig,
) {
    require(task.targetMode == "reference_images") {
        "atomic configured creation only accepts a reference task"
    }
    requireAtomicTaskAndSamplingInsert(task, sampling)
}

internal fun requireAtomicConfiguredObjectInsert(
    task: StoredLocalTask,
    sampling: ResolvedSamplingConfig,
) {
    require(task.targetMode == "object_detection") {
        "atomic configured creation only accepts an object task"
    }
    requireAtomicTaskAndSamplingInsert(task, sampling)
}

private fun requireAtomicTaskAndSamplingInsert(
    task: StoredLocalTask,
    sampling: ResolvedSamplingConfig,
) {
    require(task.revision == 1L && sampling.taskRevision == 1L) {
        "a newly configured task must start at revision 1"
    }
    require(task.taskId == sampling.taskId) {
        "task and sampling identities must match"
    }
    require(task.runtimePackagePointer == sampling.packagePointer) {
        "task and sampling must carry the same exact runtime package pointer"
    }
    val expectedCapabilityId = when (task.targetMode) {
        "structured_reading" -> "structured_reading"
        "reference_images", "object_detection" -> "visual_target"
        else -> error("configured task target mode is invalid")
    }
    require(sampling.capabilityId == expectedCapabilityId) {
        "task and sampling must carry the same signed capability"
    }
}

private fun isAccountId(value: String): Boolean = runCatching {
    java.util.UUID.fromString(value).toString().equals(value, ignoreCase = true)
}.getOrDefault(false)
