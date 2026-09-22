package app.beyoureyes.core.data

import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.domain.ReadingOperator
import android.content.Context
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.core.data.reference.ReferenceDeletionJournal
import app.beyoureyes.core.data.reference.ReferenceImageRepository
import app.beyoureyes.core.data.reference.StagedReferenceSet
import app.beyoureyes.core.data.reference.StoredReference
import app.beyoureyes.core.domain.LatestReading
import app.beyoureyes.core.domain.MAX_LOCAL_MONITORS
import app.beyoureyes.core.domain.MAX_REFERENCE_IMAGES
import app.beyoureyes.core.domain.MIN_REFERENCE_IMAGES
import app.beyoureyes.core.domain.Monitor
import app.beyoureyes.core.domain.MonitorEvent
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorEventUnknownReason
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.ReadingFormatKind
import app.beyoureyes.core.domain.ReadingTargetConfig
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import app.beyoureyes.core.domain.MonitorReadingOperator
import app.beyoureyes.core.domain.MonitorVisualEventCondition
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.ReadingStatus
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.core.domain.ReferenceMaterialDeduplicator
import app.beyoureyes.core.domain.RemoteMonitor
import app.beyoureyes.core.domain.uniqueMonitorName
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.json.JSONArray
import org.json.JSONObject

class MonitorCapacityReachedException(
    val maximum: Int = MAX_LOCAL_MONITORS,
) : IllegalStateException("monitor capacity reached")

data class PersistedMonitor(
    val monitor: Monitor,
    val runtimePackagePointer: ModelPackagePointer?,
    val referenceThumbnailUri: String? = null,
)

/** A reference generation staged in private storage until camera confirmation. */
class StagedReferenceMonitor internal constructor(
    val task: StoredLocalTask,
    internal val generation: StagedReferenceSet,
    internal val draftMaterials: List<ReferenceMaterial>,
) {
    internal var lifecycle = StageLifecycle.READY
}

internal enum class StageLifecycle { READY, COMMITTED, DISCARDED }

data class MonitorRepositoryState(
    val local: List<PersistedMonitor> = emptyList(),
    val remote: List<RemoteMonitor> = emptyList(),
    val events: List<MonitorEvent> = emptyList(),
    val latestReadings: Map<String, LatestReading> = emptyMap(),
    val notificationsEnabledIds: Set<String> = emptySet(),
    val startedMonitorIds: Set<String> = emptySet(),
) {
    fun notificationsEnabled(monitorId: String) = monitorId in notificationsEnabledIds
    fun hasStarted(monitorId: String) = monitorId in startedMonitorIds
}

class MonitorRepository(
    context: Context,
    accountState: StateFlow<CloudAccountState>,
    private val roomStore: RoomMonitorStore = RoomMonitorStore(
        MonitorDatabaseFactory.open(context.applicationContext),
    ),
    private val latestReadingStore: RoomLatestTaskReadingStore = RoomLatestTaskReadingStore(
        MonitorDatabaseFactory.open(context.applicationContext),
    ),
    private val preferences: MonitoringPreferences = monitorPreferences(context),
    private val references: ReferenceImageRepository = ReferenceImageRepository(context),
    private val deleteEventNotification: (String) -> Unit = {},
    private val deleteReadingBaselineNotification: (String) -> Unit = {},
    private val onLocalTaskCreated: suspend (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deletionJournal = ReferenceDeletionJournal(appContext.filesDir)
    private val eventSnapshots = ReferenceEventSnapshotStore.openAppPrivate(appContext.filesDir)
    private val referenceStorageMutex = Mutex()
    private val activeReferenceStages = mutableMapOf<String, StagedReferenceMonitor>()

    val state: StateFlow<MonitorRepositoryState> = combine(
        roomStore.tasks,
        roomStore.remoteTasks,
        roomStore.events,
        latestReadingStore.readings,
        preferences.eventNotificationTaskIds,
        preferences.startedTaskIds,
        accountState,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val localRows = values[0] as List<StoredLocalTask>
        @Suppress("UNCHECKED_CAST")
        val remoteRows = values[1] as List<StoredRemoteTaskSummary>
        @Suppress("UNCHECKED_CAST")
        val eventRows = values[2] as List<TimelineEvent>
        @Suppress("UNCHECKED_CAST")
        val readings = values[3] as List<StoredLatestTaskReading>
        @Suppress("UNCHECKED_CAST")
        val notificationIds = values[4] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val startedIds = values[5] as Set<String>
        val account = values[6] as CloudAccountState
        val accountId = (account as? CloudAccountState.SignedIn)?.accountId
        MonitorRepositoryState(
            local = localRows.mapNotNull { row ->
                runCatching {
                    val decoded = MonitorStorageCodec.decode(row)
                    PersistedMonitor(
                        monitor = decoded.monitor,
                        runtimePackagePointer = row.runtimePackagePointer,
                        referenceThumbnailUri = decoded.references.firstOrNull()?.let {
                            references.load(row.taskId, decoded.references).first().thumbnailUri
                        },
                    )
                }.getOrNull()
            },
            remote = remoteRows.filter { it.accountId == accountId }.mapNotNull { row ->
                runCatching {
                    RemoteMonitor(
                        id = row.taskId,
                        revision = row.revision,
                        name = row.title,
                        kind = when {
                            row.capabilityId == "structured_reading" -> MonitorKind.READING
                            row.targetDefinitionMode == "object_detection" -> MonitorKind.OBJECT_DETECTION
                            row.capabilityId == "visual_target" || row.capabilityId == "visible_state" ->
                                MonitorKind.REFERENCE
                            else -> error("unsupported remote monitor kind")
                        },
                        monitoringDeviceId = row.monitoringDeviceId,
                    )
                }.getOrNull()
            },
            events = eventRows.filter { !it.isRemoteTask || it.remoteAccountId == accountId }
                .map { row ->
                    row.toMonitorEvent(
                        localTriggerSnapshotUri = if (row.isRemoteTask) null else {
                            eventSnapshots.uriFor(row.taskId, row.eventId)
                        },
                    )
                },
            latestReadings = readings.associate { row ->
                row.taskId to LatestReading(
                    monitorId = row.taskId,
                    monitorRevision = row.taskRevision,
                    status = when (row.status) {
                        LatestReadingStatus.CANDIDATE -> ReadingStatus.CANDIDATE
                        LatestReadingStatus.STABLE -> ReadingStatus.STABLE
                        LatestReadingStatus.UNAVAILABLE -> ReadingStatus.UNAVAILABLE
                    },
                    valueDecimal = row.valueDecimal,
                    updatedAtEpochMillis = row.observedAtEpochMillis,
                )
            },
            notificationsEnabledIds = notificationIds,
            startedMonitorIds = startedIds,
        )
    }.stateIn(scope, SharingStarted.Eagerly, MonitorRepositoryState())

    init {
        scope.launchSafely {
            referenceStorageMutex.withLock {
                reconcileDeletionJournal()
                reconcileReferenceStorage()
            }
        }
    }

    fun newReferenceDraftSession(): File = references.newDraftSession()

    fun referenceDraftSessionId(sessionDirectory: File): String =
        references.draftSessionId(sessionDirectory)

    fun persistReferenceDraftSession(
        sessionDirectory: File,
        name: String,
        materials: List<ReferenceMaterial>,
    ) {
        references.persistDraftSession(sessionDirectory, name, materials)
    }

    fun restoreReferenceDraftSession(
        sessionId: String,
    ): Triple<File, String, List<ReferenceMaterial>>? =
        references.restoreDraftSession(sessionId)

    fun discardReferenceDraftSession(sessionId: String) {
        references.discardDraftSession(sessionId)
    }

    /** 首页草稿恢复卡片的数据来源：最近一个仍有内容的参考图片草稿。 */
    fun latestReferenceDraft(): Triple<String, String, List<ReferenceMaterial>>? =
        references.latestReferenceDraft()

    suspend fun importReferenceImages(
        uris: List<android.net.Uri>,
        existing: List<ReferenceMaterial>,
        sessionDirectory: File,
    ) = references.inspect(uris, existing, sessionDirectory)

    fun discardReferenceDrafts(materials: Collection<ReferenceMaterial>) {
        references.discardDrafts(materials)
    }

    suspend fun stageReferenceMonitor(
        taskId: String,
        requestedName: String,
        materials: List<ReferenceMaterial>,
        rule: MonitorRule.TargetPresence = MonitorRule.TargetPresence(),
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): StagedReferenceMonitor = withContext(Dispatchers.IO) {
        referenceStorageMutex.withLock {
            require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
            require(materials.size in MIN_REFERENCE_IMAGES..MAX_REFERENCE_IMAGES)
            ReferenceMaterialDeduplicator.requireDistinct(materials)
            require(taskId !in activeReferenceStages) { "reference identity is already staged" }
            require(roomStore.find(taskId) == null) { "reserved monitor identity already exists" }
            val name = uniqueMonitorName(
                requestedName,
                state.value.local.map { it.monitor.name },
            )
            val generation = references.stagePersistentSet(taskId, materials)
            try {
                val monitor = Monitor(
                    id = taskId,
                    revision = 1,
                    name = name,
                    target = MonitorTarget.ReferenceImages(generation.references.size),
                    rule = rule,
                    createdAtEpochMillis = nowEpochMillis,
                )
                StagedReferenceMonitor(
                    task = MonitorStorageCodec.encode(monitor, generation.references),
                    generation = generation,
                    draftMaterials = materials.toList(),
                ).also { activeReferenceStages[taskId] = it }
            } catch (failure: Throwable) {
                references.discard(generation)
                throw failure
            }
        }
    }

    suspend fun createConfiguredReferenceMonitor(
        staged: StagedReferenceMonitor,
        resolvedSamplingConfig: ResolvedSamplingConfig,
    ): PersistedMonitor = withContext(NonCancellable + Dispatchers.IO) {
        referenceStorageMutex.withLock {
            require(staged.lifecycle == StageLifecycle.READY) { "reference stage is no longer pending" }
            require(activeReferenceStages[staged.task.taskId] === staged) { "reference stage is not owned" }
            require(resolvedSamplingConfig.taskId == staged.task.taskId)
            require(staged.task.revision == 1L && resolvedSamplingConfig.taskRevision == 1L)
            val committedTask = staged.task.copy(runtimePackagePointer = resolvedSamplingConfig.packagePointer)
            val insertion = try {
                onLocalTaskCreated(committedTask.taskId)
                roomStore.insertConfiguredReference(
                    task = committedTask,
                    sampling = resolvedSamplingConfig,
                    maximumTasks = MAX_LOCAL_MONITORS,
                )
            } catch (failure: Throwable) {
                discardPendingReference(staged)
                throw failure
            }
            when (insertion) {
                LocalTaskInsertResult.INSERTED -> {
                    staged.lifecycle = StageLifecycle.COMMITTED
                    activeReferenceStages.remove(staged.task.taskId)
                    runCatching { references.commitDrafts(staged.draftMaterials) }
                    runCatching { references.prune(staged.task.taskId, staged.generation.references) }
                    awaitMonitor(staged.task.taskId)
                }
                LocalTaskInsertResult.CAPACITY_REACHED -> {
                    discardPendingReference(staged)
                    throw MonitorCapacityReachedException()
                }
                LocalTaskInsertResult.ALREADY_EXISTS -> {
                    discardPendingReference(staged)
                    error("reserved monitor identity already exists")
                }
            }
        }
    }

    suspend fun discardStagedReferenceMonitor(staged: StagedReferenceMonitor) {
        withContext(NonCancellable + Dispatchers.IO) {
            referenceStorageMutex.withLock {
                if (staged.lifecycle == StageLifecycle.READY) {
                    require(activeReferenceStages[staged.task.taskId] === staged)
                    discardPendingReference(staged)
                }
            }
        }
    }

    suspend fun createReferenceMonitor(
        requestedName: String,
        materials: List<ReferenceMaterial>,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PersistedMonitor = withContext(Dispatchers.IO) {
        referenceStorageMutex.withLock {
            require(materials.size in MIN_REFERENCE_IMAGES..MAX_REFERENCE_IMAGES)
            ReferenceMaterialDeduplicator.requireDistinct(materials)
            val name = uniqueMonitorName(
                requestedName,
                state.value.local.map { it.monitor.name },
            )
            val id = UuidV7.generate(nowEpochMillis)
            val staged = references.stagePersistentSet(id, materials)
            val monitor = Monitor(
                id = id,
                revision = 1,
                name = name,
                target = MonitorTarget.ReferenceImages(staged.references.size),
                rule = MonitorRule.TargetPresence(),
                createdAtEpochMillis = nowEpochMillis,
            )
            val row = MonitorStorageCodec.encode(monitor, staged.references)
            val insertion = try {
                onLocalTaskCreated(id)
                roomStore.insert(row, MAX_LOCAL_MONITORS)
            } catch (failure: Throwable) {
                references.discard(staged)
                throw failure
            }
            when (insertion) {
                LocalTaskInsertResult.INSERTED -> {
                    runCatching { references.commitDrafts(materials) }
                    runCatching { references.prune(id, staged.references) }
                    awaitMonitor(id)
                }
                LocalTaskInsertResult.CAPACITY_REACHED -> {
                    references.discard(staged)
                    throw MonitorCapacityReachedException()
                }
                LocalTaskInsertResult.ALREADY_EXISTS -> {
                    references.discard(staged)
                    error("generated monitor identity already exists")
                }
            }
        }
    }

    /**
     * Persists a reading task whose baseline is not confirmed yet. Monitoring starts immediately,
     * keeps recording latest readings, and stays event-silent until the user confirms the first
     * stable value and configures the condition.
     */
    suspend fun createPendingReadingMonitor(
        taskId: String,
        requestedName: String,
        targetConfig: ReadingTargetConfig = ReadingTargetConfig(),
        resolvedSamplingConfig: ResolvedSamplingConfig,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PersistedMonitor = withContext(Dispatchers.IO) {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        require(targetConfig.confirmedFormat == null) {
            "a pending-baseline reading must not carry a confirmed format"
        }
        require(resolvedSamplingConfig.taskId == taskId) {
            "sampling task identity must match the reserved taskId"
        }
        require(resolvedSamplingConfig.taskRevision == 1L) {
            "a newly created pending reading must start at revision 1"
        }
        val monitor = Monitor(
            id = taskId,
            revision = 1,
            name = uniqueMonitorName(
                requestedName,
                state.value.local.map { it.monitor.name },
            ),
            target = MonitorTarget.NumericReading(targetConfig),
            rule = MonitorRule.ReadingThreshold.Single(
                comparison = ReadingComparison.GT,
                thresholdDecimal = "0",
                configured = false,
            ),
            createdAtEpochMillis = nowEpochMillis,
        )
        val row = MonitorStorageCodec.encode(
            monitor = monitor,
            references = emptyList(),
        ).copy(runtimePackagePointer = resolvedSamplingConfig.packagePointer)

        val insertion = run {
            onLocalTaskCreated(taskId)
            roomStore.insertConfiguredReading(
                task = row,
                sampling = resolvedSamplingConfig,
                maximumTasks = MAX_LOCAL_MONITORS,
            )
        }
        when (insertion) {
            LocalTaskInsertResult.INSERTED -> awaitMonitor(taskId)
            LocalTaskInsertResult.CAPACITY_REACHED -> throw MonitorCapacityReachedException()
            LocalTaskInsertResult.ALREADY_EXISTS -> error("reserved monitor identity already exists")
        }
    }

    /** Persists a reading only after the camera has produced a stable value and the user has confirmed it. */
    suspend fun createConfiguredReadingMonitor(        taskId: String,
        requestedName: String,
        rule: MonitorRule.ReadingThreshold,
        targetConfig: ReadingTargetConfig,
        resolvedSamplingConfig: ResolvedSamplingConfig,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PersistedMonitor = withContext(Dispatchers.IO) {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        require(rule.configured) { "configured reading rule must be complete" }
        require(targetConfig.confirmedFormat != null) {
            "configured reading target needs a confirmed format"
        }
        require(resolvedSamplingConfig.taskId == taskId) {
            "sampling task identity must match the reserved taskId"
        }
        require(resolvedSamplingConfig.taskRevision == 1L) {
            "a newly configured reading must start at revision 1"
        }
        val monitor = Monitor(
            id = taskId,
            revision = 1,
            name = uniqueMonitorName(
                requestedName,
                state.value.local.map { it.monitor.name },
            ),
            target = MonitorTarget.NumericReading(targetConfig),
            rule = rule,
            createdAtEpochMillis = nowEpochMillis,
        )
        val row = MonitorStorageCodec.encode(
            monitor = monitor,
            references = emptyList(),
        ).copy(runtimePackagePointer = resolvedSamplingConfig.packagePointer)

        val insertion = run {
            onLocalTaskCreated(taskId)
            roomStore.insertConfiguredReading(
                task = row,
                sampling = resolvedSamplingConfig,
                maximumTasks = MAX_LOCAL_MONITORS,
            )
        }
        when (insertion) {
            LocalTaskInsertResult.INSERTED -> awaitMonitor(taskId)
            LocalTaskInsertResult.CAPACITY_REACHED -> throw MonitorCapacityReachedException()
            LocalTaskInsertResult.ALREADY_EXISTS -> error("reserved monitor identity already exists")
        }
    }

    suspend fun createConfiguredObjectMonitor(
        taskId: String,
        requestedName: String,
        target: MonitorTarget.ObjectClass,
        rule: MonitorRule.TargetPresence = MonitorRule.TargetPresence(),
        resolvedSamplingConfig: ResolvedSamplingConfig,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PersistedMonitor = withContext(Dispatchers.IO) {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        require(resolvedSamplingConfig.taskId == taskId)
        require(resolvedSamplingConfig.taskRevision == 1L)
        val monitor = Monitor(
            id = taskId,
            revision = 1,
            name = uniqueMonitorName(
                requestedName,
                state.value.local.map { it.monitor.name },
            ),
            target = target,
            rule = rule,
            createdAtEpochMillis = nowEpochMillis,
        )
        val row = MonitorStorageCodec.encode(monitor, emptyList())
            .copy(runtimePackagePointer = resolvedSamplingConfig.packagePointer)
        val insertion = run {
            onLocalTaskCreated(taskId)
            roomStore.insertConfiguredObject(
                task = row,
                sampling = resolvedSamplingConfig,
                maximumTasks = MAX_LOCAL_MONITORS,
            )
        }
        when (insertion) {
            LocalTaskInsertResult.INSERTED -> awaitMonitor(taskId)
            LocalTaskInsertResult.CAPACITY_REACHED -> throw MonitorCapacityReachedException()
            LocalTaskInsertResult.ALREADY_EXISTS -> error("reserved monitor identity already exists")
        }
    }

    suspend fun configureReading(
        monitorId: String,
        expectedRevision: Long,
        rule: MonitorRule.ReadingThreshold,
        targetConfig: ReadingTargetConfig? = null,
    ): PersistedMonitor? = withContext(Dispatchers.IO) {
        require(rule.configured) { "configured reading rule must be complete" }
        val row = roomStore.find(monitorId) ?: return@withContext null
        if (row.revision != expectedRevision || row.targetMode != MonitorKind.READING.wireValue) {
            return@withContext null
        }
        val sampling = roomStore.findResolvedSamplingConfig(monitorId) ?: return@withContext null
        if (sampling.taskRevision != expectedRevision || sampling.packagePointer != row.runtimePackagePointer) {
            return@withContext null
        }
        val decoded = MonitorStorageCodec.decode(row)
        val effectiveTarget = targetConfig ?: (
            decoded.monitor.target as MonitorTarget.NumericReading
        ).config
        require(effectiveTarget.confirmedFormat != null) { "configured reading target needs a format" }
        val carried = roomStore.reviseReadingTargetAndRuleAndCarryRuntime(
            sampling,
            MonitorStorageCodec.encodeReadingTarget(effectiveTarget),
            MonitorStorageCodec.encodeRule(rule),
        ) ?: return@withContext null
        deleteReadingBaselineNotification(monitorId)
        awaitRevision(monitorId, carried.taskRevision)
    }

    suspend fun updatePresenceRule(
        monitorId: String,
        expectedRevision: Long,
        rule: MonitorRule.TargetPresence,
    ): PersistedMonitor? = withContext(Dispatchers.IO) {
        val row = roomStore.find(monitorId) ?: return@withContext null
        if (row.revision != expectedRevision || row.targetMode !in setOf(
                MonitorKind.REFERENCE.wireValue,
                MonitorKind.OBJECT_DETECTION.wireValue,
            )
        ) {
            return@withContext null
        }
        val sampling = roomStore.findResolvedSamplingConfig(monitorId) ?: return@withContext null
        if (sampling.taskRevision != expectedRevision || sampling.packagePointer != row.runtimePackagePointer) {
            return@withContext null
        }
        val carried = roomStore.reviseRuleAndCarryRuntime(
            sampling,
            MonitorStorageCodec.encodeRule(rule),
        ) ?: return@withContext null
        awaitRevision(monitorId, carried.taskRevision)
    }

    /**
     * Replaces one stopped reference monitor's complete material set.
     *
     * Files are committed as a new immutable generation before the Room compare-and-set. Room
     * either advances the task and sampling revision together or keeps the old generation. This
     * avoids an observable filesystem/database half-state across process death.
     */
    suspend fun replaceReferenceMaterials(
        monitorId: String,
        expectedRevision: Long,
        materials: List<ReferenceMaterial>,
    ): PersistedMonitor? = withContext(Dispatchers.IO) {
        referenceStorageMutex.withLock {
            require(materials.size in MIN_REFERENCE_IMAGES..MAX_REFERENCE_IMAGES)
            ReferenceMaterialDeduplicator.requireDistinct(materials)
            val row = roomStore.find(monitorId) ?: return@withLock null
            if (row.revision != expectedRevision || row.targetMode != MonitorKind.REFERENCE.wireValue) {
                return@withLock null
            }
            val previous = MonitorStorageCodec.decode(row).references
            val sampling = roomStore.findResolvedSamplingConfig(monitorId)
            if ((row.runtimePackagePointer == null) != (sampling == null)) return@withLock null
            if (sampling != null && sampling.taskRevision != expectedRevision) return@withLock null

            val staged = references.stagePersistentSet(monitorId, materials)
            var committed = false
            try {
                val encoded = MonitorStorageCodec.encodeReferences(staged.references).toString()
                val nextRevision = if (sampling == null) {
                    roomStore.reviseReferenceMaterialsWithoutRuntime(
                        monitorId,
                        expectedRevision,
                        encoded,
                    )
                } else {
                    roomStore.reviseReferenceMaterialsAndCarryRuntime(sampling, encoded)?.taskRevision
                } ?: run {
                    references.discard(staged)
                    return@withLock null
                }
                committed = true
                runCatching { references.commitDrafts(materials) }
                val retainedHashes = staged.references.mapTo(
                    linkedSetOf(),
                    StoredReference::exactSha256,
                )
                runCatching {
                    ReferenceEmbeddingCaches.openAppPrivate(appContext.filesDir).invalidateReferences(
                        previous.mapTo(linkedSetOf(), StoredReference::exactSha256) - retainedHashes,
                    )
                }
                runCatching { references.prune(monitorId, staged.references) }
                awaitRevision(monitorId, nextRevision)
            } catch (failure: Throwable) {
                if (!committed) references.discard(staged)
                throw failure
            }
        }
    }

    suspend fun rename(monitorId: String, name: String): Boolean =
        roomStore.rename(monitorId, name.trim())

    suspend fun setNotificationsEnabled(monitorId: String, enabled: Boolean) {
        preferences.setEventNotificationsEnabled(monitorId, enabled)
    }

    suspend fun delete(monitorId: String): Boolean = withContext(Dispatchers.IO) {
        referenceStorageMutex.withLock {
            val row = roomStore.find(monitorId) ?: return@withLock false
            val stored = MonitorStorageCodec.decode(row)
            val eventNotificationIds = state.value.events
                .filter { it.monitorId == monitorId && !it.isRemote }
                .map { it.id }
            val staged = deletionJournal.stage(monitorId)
            val deleted = try {
                roomStore.deleteTaskAndRuntime(monitorId)
            } catch (failure: Throwable) {
                staged?.let(deletionJournal::restore)
                throw failure
            }
            if (!deleted) {
                staged?.let(deletionJournal::restore)
                return@withLock false
            }
            preferences.clearTask(monitorId)
            ReferenceEmbeddingCaches.openAppPrivate(appContext.filesDir).invalidateReferences(
                stored.references.mapTo(linkedSetOf(), StoredReference::exactSha256),
            )
            staged?.let(deletionJournal::discard)
            eventSnapshots.deleteTask(monitorId)
            eventNotificationIds.forEach(deleteEventNotification)
            deleteReadingBaselineNotification(monitorId)
            true
        }
    }

    suspend fun findStored(monitorId: String): StoredLocalTask? = roomStore.find(monitorId)

    suspend fun findMonitor(monitorId: String): PersistedMonitor? = withContext(Dispatchers.IO) {
        roomStore.find(monitorId)?.let { row ->
            val decoded = MonitorStorageCodec.decode(row)
            PersistedMonitor(
                monitor = decoded.monitor,
                runtimePackagePointer = row.runtimePackagePointer,
                referenceThumbnailUri = decoded.references.firstOrNull()?.let {
                    references.load(row.taskId, decoded.references).first().thumbnailUri
                },
            )
        }
    }

    /**
     * Resolves the current monitor's app-private reference files for display or runtime setup.
     * The returned URIs never leave the process and every file is rechecked against its stored
     * digest before it is exposed.
     */
    suspend fun loadReferenceMaterials(monitorId: String): List<ReferenceMaterial> =
        withContext(Dispatchers.IO) {
            referenceStorageMutex.withLock {
                val row = roomStore.find(monitorId) ?: return@withLock emptyList()
                if (row.targetMode != MonitorKind.REFERENCE.wireValue) return@withLock emptyList()
                val decoded = MonitorStorageCodec.decode(row)
                references.load(monitorId, decoded.references)
            }
        }

    private suspend fun awaitMonitor(id: String): PersistedMonitor = state.first { snapshot ->
        snapshot.local.any { it.monitor.id == id }
    }.local.single { it.monitor.id == id }

    private suspend fun awaitRevision(id: String, revision: Long): PersistedMonitor =
        state.first { snapshot -> snapshot.local.any { it.monitor.id == id && it.monitor.revision == revision } }
            .local.single { it.monitor.id == id }

    private suspend fun reconcileDeletionJournal() {
        deletionJournal.entries().forEach { entry ->
            if (roomStore.find(entry.taskId) == null) deletionJournal.discard(entry)
            else deletionJournal.restore(entry)
        }
    }

    private suspend fun reconcileReferenceStorage() {
        val rows = roomStore.tasks.first()
        val expected = rows.filter { it.targetMode == MonitorKind.REFERENCE.wireValue }
            .associate { row -> row.taskId to MonitorStorageCodec.decode(row).references }
            .toMutableMap()
        activeReferenceStages.values.forEach { staged ->
            if (staged.lifecycle == StageLifecycle.READY) {
                check(expected.put(staged.task.taskId, staged.generation.references) == null)
            }
        }
        references.reconcile(expected)
    }

    private fun discardPendingReference(staged: StagedReferenceMonitor) {
        check(staged.lifecycle == StageLifecycle.READY)
        check(activeReferenceStages[staged.task.taskId] === staged)
        staged.lifecycle = StageLifecycle.DISCARDED
        activeReferenceStages.remove(staged.task.taskId)
        runCatching { references.discard(staged.generation) }
    }

    private fun CoroutineScope.launchSafely(block: suspend () -> Unit) =
        launch { runCatching { block() } }
}

data class DecodedStoredMonitor(
    val monitor: Monitor,
    val references: List<StoredReference>,
)

object MonitorStorageCodec {
    fun encode(
        monitor: Monitor,
        references: List<StoredReference>,
    ): StoredLocalTask {
        require(if (monitor.target is MonitorTarget.ReferenceImages) references.isNotEmpty() else references.isEmpty())
        return StoredLocalTask(
            taskId = monitor.id,
            revision = monitor.revision,
            title = monitor.name,
            targetMode = monitor.target.kind.wireValue,
            targetJson = when (monitor.target) {
                is MonitorTarget.ReferenceImages -> JSONObject()
                    .put("type", MonitorKind.REFERENCE.wireValue)
                    .toString()
                is MonitorTarget.ObjectClass -> run {
                    val target = monitor.target as MonitorTarget.ObjectClass
                    JSONObject()
                        .put("type", MonitorKind.OBJECT_DETECTION.wireValue)
                        .put("target_id", target.targetId)
                        .put("label_zh_cn", target.labelZhCn)
                        .put("label_en", target.labelEn)
                        .apply {
                            if (target.labels.isNotEmpty()) put("labels", JSONObject(target.labels))
                        }
                        .toString()
                }
                is MonitorTarget.NumericReading -> encodeReadingTarget(
                    (monitor.target as MonitorTarget.NumericReading).config,
                )
            },
            ruleJson = encodeRule(monitor.rule),
            materialRefsJson = encodeReferences(references).toString(),
            createdAtEpochMillis = monitor.createdAtEpochMillis,
        )
    }

    fun decode(row: StoredLocalTask): DecodedStoredMonitor {
        val references = decodeReferences(row.materialRefsJson)
        val target = when (row.targetMode) {
            MonitorKind.REFERENCE.wireValue -> {
                JSONObject(row.targetJson).requireExact("type").also {
                    require(it.getString("type") == MonitorKind.REFERENCE.wireValue)
                }
                MonitorTarget.ReferenceImages(references.size)
            }
            MonitorKind.OBJECT_DETECTION.wireValue -> {
                val value = JSONObject(row.targetJson)
                require(value.keys().asSequence().toSet() == setOf("type", "target_id", "label_zh_cn", "label_en") ||
                    value.keys().asSequence().toSet() == setOf("type", "target_id", "label_zh_cn", "label_en", "labels"))
                require(value.getString("type") == MonitorKind.OBJECT_DETECTION.wireValue)
                require(references.isEmpty())
                MonitorTarget.ObjectClass(
                    targetId = value.getString("target_id"),
                    labelZhCn = value.getString("label_zh_cn"),
                    labelEn = value.getString("label_en"),
                    labels = value.optJSONObject("labels")?.let { labels ->
                        labels.keys().asSequence().associateWith(labels::getString)
                    }.orEmpty(),
                )
            }
            MonitorKind.READING.wireValue -> {
                val value = JSONObject(row.targetJson).requireExact(
                    "type", "manual_roi", "confirmed_format",
                ).also {
                    require(it.getString("type") == MonitorKind.READING.wireValue)
                }
                require(references.isEmpty())
                MonitorTarget.NumericReading(decodeReadingTarget(value))
            }
            else -> error("unsupported stored monitor kind")
        }
        return DecodedStoredMonitor(
            Monitor(row.taskId, row.revision, row.title, target, decodeRule(row.ruleJson), row.createdAtEpochMillis),
            references,
        )
    }

    fun encodeRule(rule: MonitorRule): String = when (rule) {
        is MonitorRule.TargetPresence -> JSONObject().apply {
            put("type", if (rule.kind == PresenceRuleKind.DISAPPEARS) "absence_duration" else "presence_duration")
            put("condition", rule.kind.wireValue)
            put("duration_ms", Math.multiplyExact(rule.durationSeconds.toLong(), 1_000L))
        }.toString()
        is MonitorRule.ReadingThreshold.Single -> JSONObject().apply {
            put("type", "reading_threshold")
            put("operator", rule.comparison.wireValue)
            put("threshold_decimal", rule.thresholdDecimal)
            putCommonReadingRuleFields(rule.configured, rule.durationSeconds)
        }.toString()
        is MonitorRule.ReadingThreshold.Outside -> JSONObject().apply {
            put("type", "reading_threshold")
            put("operator", "outside")
            put("lower_threshold_decimal", rule.lowerThresholdDecimal)
            put("upper_threshold_decimal", rule.upperThresholdDecimal)
            putCommonReadingRuleFields(rule.configured, rule.durationSeconds)
        }.toString()
    }

    fun decodeRule(raw: String): MonitorRule {
        val value = JSONObject(raw)
        return when (value.getString("type")) {
            "presence_duration", "absence_duration" -> {
                value.requireExact("type", "condition", "duration_ms")
                val seconds = Math.toIntExact(value.getLong("duration_ms") / 1_000L)
                require(value.getLong("duration_ms") == seconds * 1_000L)
                val kind = PresenceRuleKind.entries.singleOrNull {
                    it.wireValue == value.getString("condition")
                } ?: error("unsupported presence condition")
                require(
                    if (kind == PresenceRuleKind.DISAPPEARS) {
                        value.getString("type") == "absence_duration"
                    } else {
                        value.getString("type") == "presence_duration"
                    },
                ) { "presence condition and rule type must match" }
                MonitorRule.TargetPresence(
                    kind = kind,
                    durationSeconds = seconds,
                )
            }
            "reading_threshold" -> {
                value.requireCommonReadingRuleFields()
                if (value.getString("operator") == "outside") {
                    value.requireExact(
                        "type", "operator", "lower_threshold_decimal", "upper_threshold_decimal",
                        "setup_complete", "hysteresis_decimal", "cooldown_ms",
                        "source_kind", "duration_ms",
                    )
                    MonitorRule.ReadingThreshold.Outside(
                        lowerThresholdDecimal = value.getString("lower_threshold_decimal"),
                        upperThresholdDecimal = value.getString("upper_threshold_decimal"),
                        configured = value.getBoolean("setup_complete"),
                        durationSeconds = value.requireReadingDurationSeconds(),
                    )
                } else {
                    value.requireExact(
                        "type", "operator", "threshold_decimal", "setup_complete",
                        "hysteresis_decimal", "cooldown_ms", "source_kind", "duration_ms",
                    )
                    MonitorRule.ReadingThreshold.Single(
                        comparison = ReadingComparison.entries.single {
                            it.wireValue == value.getString("operator")
                        },
                        thresholdDecimal = value.getString("threshold_decimal"),
                        configured = value.getBoolean("setup_complete"),
                        durationSeconds = value.requireReadingDurationSeconds(),
                    )
                }
            }
            else -> error("unsupported stored monitor rule")
        }
    }

    fun encodeReferences(references: List<StoredReference>) = JSONArray().apply {
        references.forEach { reference ->
            put(JSONObject().apply {
                put("relative_path", reference.relativePath)
                put("thumbnail_relative_path", reference.thumbnailRelativePath)
                put("sha256", reference.exactSha256)
                put("thumbnail_sha256", reference.thumbnailSha256)
                put("difference_hash", java.lang.Long.toUnsignedString(reference.differenceHash))
                put("mean_rgb", JSONArray(listOf(reference.meanRed, reference.meanGreen, reference.meanBlue)))
                put("width", reference.width)
                put("height", reference.height)
            })
        }
    }

    fun decodeReferences(raw: String): List<StoredReference> {
        val values = JSONArray(raw)
        return List(values.length()) { index ->
            val value = values.getJSONObject(index).requireExact(
                "relative_path", "thumbnail_relative_path", "sha256", "thumbnail_sha256",
                "difference_hash", "mean_rgb", "width", "height",
            )
            val rgb = value.getJSONArray("mean_rgb")
            require(rgb.length() == 3)
            StoredReference(
                relativePath = value.getString("relative_path"),
                thumbnailRelativePath = value.getString("thumbnail_relative_path"),
                exactSha256 = value.getString("sha256"),
                thumbnailSha256 = value.getString("thumbnail_sha256"),
                differenceHash = java.lang.Long.parseUnsignedLong(value.getString("difference_hash")),
                meanRed = rgb.getInt(0), meanGreen = rgb.getInt(1), meanBlue = rgb.getInt(2),
                width = value.getInt("width"), height = value.getInt("height"),
            )
        }
    }

    private fun JSONObject.requireExact(vararg names: String): JSONObject {
        require(keys().asSequence().toSet() == names.toSet())
        return this
    }

    private fun JSONObject.putCommonReadingRuleFields(
        configured: Boolean,
        durationSeconds: Int,
    ) {
        put("setup_complete", configured)
        put("duration_ms", Math.multiplyExact(durationSeconds.toLong(), 1_000L))
        put("hysteresis_decimal", "0")
        put("cooldown_ms", 0)
        put("source_kind", "digital_display")
    }

    private fun JSONObject.requireCommonReadingRuleFields() {
        require(getString("hysteresis_decimal") == "0")
        require(getLong("cooldown_ms") == 0L)
        require(getString("source_kind") == "digital_display")
    }

    private fun JSONObject.requireReadingDurationSeconds(): Int {
        val durationMillis = getLong("duration_ms")
        val seconds = Math.toIntExact(durationMillis / 1_000L)
        require(durationMillis == Math.multiplyExact(seconds.toLong(), 1_000L))
        return seconds
    }

    fun encodeReadingTarget(
        config: ReadingTargetConfig,
    ): String = JSONObject().apply {
        put("type", MonitorKind.READING.wireValue)
        put("manual_roi", config.manualRoi?.toJson() ?: JSONObject.NULL)
        put("confirmed_format", config.confirmedFormat?.toJson() ?: JSONObject.NULL)
    }.toString()

    private fun decodeReadingTarget(value: JSONObject) = ReadingTargetConfig(
        manualRoi = if (value.isNull("manual_roi")) null else value.getJSONObject("manual_roi").toRect(),
        confirmedFormat = if (value.isNull("confirmed_format")) null else {
            value.getJSONObject("confirmed_format").toConfirmedReadingFormat()
        },
    )

    private fun NormalizedRect.toJson() = JSONObject()
        .put("left", left.toDouble())
        .put("top", top.toDouble())
        .put("right", right.toDouble())
        .put("bottom", bottom.toDouble())

    private fun JSONObject.toRect(): NormalizedRect {
        requireExact("left", "top", "right", "bottom")
        return NormalizedRect(
            getDouble("left").toFloat(),
            getDouble("top").toFloat(),
            getDouble("right").toFloat(),
            getDouble("bottom").toFloat(),
        )
    }

    private fun ConfirmedReadingFormat.toJson() = JSONObject()
        .put("profile_id", ConfirmedReadingFormat.PROFILE_ID)
        .put("kind", kind.wireValue)
        .put("fractional_digits", fractionalDigits)
        .put("time_segments", timeSegments ?: JSONObject.NULL)
        .put("unit", unit ?: JSONObject.NULL)

    private fun JSONObject.toConfirmedReadingFormat(): ConfirmedReadingFormat {
        requireExact("profile_id", "kind", "fractional_digits", "time_segments", "unit")
        require(getString("profile_id") == ConfirmedReadingFormat.PROFILE_ID)
        return ConfirmedReadingFormat(
            kind = ReadingFormatKind.entries.single { it.wireValue == getString("kind") },
            fractionalDigits = getInt("fractional_digits"),
            timeSegments = if (isNull("time_segments")) null else getInt("time_segments"),
            unit = if (isNull("unit")) null else getString("unit"),
        )
    }
}

/** Converts the durable payload into product facts without trusting notification copy. */
internal fun TimelineEvent.toMonitorEvent(
    localTriggerSnapshotUri: String? = null,
): MonitorEvent = MonitorEvent(
    id = eventId,
    monitorId = taskId,
    text = notificationText,
    occurredAtEpochMillis = occurredAtEpochMillis,
    remoteMonitorName = remoteTaskTitle,
    isRemote = isRemoteTask,
    localTriggerSnapshotUri = localTriggerSnapshotUri,
    fact = MonitorEventFactJson.decode(payloadJson),
)

internal object MonitorEventFactJson {
    private val json = Json

    fun decode(raw: String): MonitorEventFact {
        val payload = try {
            json.parseToJsonElement(raw) as? JsonObject
                ?: return unknown(MonitorEventUnknownReason.MALFORMED_PAYLOAD)
        } catch (_: Exception) {
            return unknown(MonitorEventUnknownReason.MALFORMED_PAYLOAD)
        }
        val type = payload.stringOrNull("type")
            ?: return unknown(MonitorEventUnknownReason.INVALID_FIELDS)
        return try {
            when (type) {
                "object_episode" -> payload.decodeObjectEpisode()
                "visual_condition_met" -> payload.decodeVisualConditionMet()
                "reading_threshold_crossed" -> payload.decodeReadingThresholdCrossed()
                "state_transition" -> payload.decodeStateTransition()
                else -> unknown(MonitorEventUnknownReason.UNSUPPORTED_TYPE)
            }
        } catch (_: Exception) {
            unknown(MonitorEventUnknownReason.INVALID_FIELDS)
        }
    }

    private fun JsonObject.decodeObjectEpisode(): MonitorEventFact.ObjectEpisode {
        val durationMillis = numberLong("duration_ms")
        val count = numberLong("count")
        require(count in 0..Int.MAX_VALUE.toLong())
        return MonitorEventFact.ObjectEpisode(
            targetId = requiredString("target_id"),
            condition = when (requiredString("condition")) {
                "appeared" -> MonitorObjectEventCondition.APPEARED
                "disappeared" -> MonitorObjectEventCondition.DISAPPEARED
                "count_matched" -> MonitorObjectEventCondition.COUNT_MATCHED
                else -> error("unsupported object condition")
            },
            durationMillis = durationMillis,
            count = count.toInt(),
        )
    }

    private fun JsonObject.decodeVisualConditionMet(): MonitorEventFact.VisualConditionMet =
        MonitorEventFact.VisualConditionMet(
            targetId = requiredString("target_id"),
            condition = when (requiredString("condition")) {
                "present_for_duration" ->
                    MonitorVisualEventCondition.PRESENT_FOR_DURATION
                "absent_for_duration" ->
                    MonitorVisualEventCondition.ABSENT_FOR_DURATION
                else -> error("unsupported visual condition")
            },
            durationMillis = numberLong("duration_ms"),
        )

    private fun JsonObject.decodeReadingThresholdCrossed(): MonitorEventFact.ReadingThresholdCrossed {
        val reading = this["reading"] as? JsonObject ?: error("reading must be an object")
        require(reading.requiredString("type") == "structured_reading")
        require(reading.requiredString("status") == "stable")
        val displayText = reading.requiredString("display_text")
        val valueDecimal = reading.requiredString("value_decimal")
        require(reading.validNullableUnit())
        val unit = reading.stringOrNull("unit")
        val formatValue = reading["format"] as? JsonObject ?: error("format must be an object")
        require(
            formatValue.keys == setOf(
                "profile_id", "kind", "fractional_digits", "time_segments", "unit",
            ),
        )
        require(formatValue.requiredString("profile_id") == ConfirmedReadingFormat.PROFILE_ID)
        require(formatValue.validNullableUnit())
        val timeSegments = when (val raw = formatValue["time_segments"]) {
            JsonNull -> null
            is JsonPrimitive -> raw.takeUnless(JsonPrimitive::isString)?.longOrNull?.toInt()
            else -> null
        }
        val format = ConfirmedReadingFormat(
            kind = ReadingFormatKind.entries.single {
                it.wireValue == formatValue.requiredString("kind")
            },
            fractionalDigits = formatValue.numberLong("fractional_digits").toInt(),
            timeSegments = timeSegments,
            unit = formatValue.stringOrNull("unit"),
        )
        require(format.unit == unit)
        require(reading.numberDouble("confidence") in 0.0..1.0)
        require(reading.requiredString("source_kind") in READING_SOURCE_KINDS)
        Instant.parse(reading.requiredString("observed_at"))

        val operator = when (requiredString("operator")) {
            "gt" -> MonitorReadingOperator.GT
            "gte" -> MonitorReadingOperator.GTE
            "lt" -> MonitorReadingOperator.LT
            "lte" -> MonitorReadingOperator.LTE
            "eq" -> MonitorReadingOperator.EQ
            "outside" -> MonitorReadingOperator.OUTSIDE
            else -> error("unsupported reading operator")
        }
        return if (operator == MonitorReadingOperator.OUTSIDE) {
            MonitorEventFact.ReadingThresholdCrossed(
                displayText = displayText,
                valueDecimal = valueDecimal,
                format = format,
                unit = unit,
                operator = operator,
                lowerThresholdDecimal = requiredString("lower_threshold_decimal"),
                upperThresholdDecimal = requiredString("upper_threshold_decimal"),
            )
        } else {
            MonitorEventFact.ReadingThresholdCrossed(
                displayText = displayText,
                valueDecimal = valueDecimal,
                format = format,
                unit = unit,
                operator = operator,
                thresholdDecimal = requiredString("threshold_decimal"),
            )
        }
    }

    private fun JsonObject.decodeStateTransition() = MonitorEventFact.StateTransition(
        fromState = requiredString("from_state"),
        toState = requiredString("to_state"),
    )

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        requireNotNull(stringOrNull(name)).also { require(it.isNotBlank()) }

    private fun JsonObject.numberLong(name: String): Long =
        requireNotNull((this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull)

    private fun JsonObject.numberDouble(name: String): Double =
        requireNotNull((this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull)

    private fun JsonObject.validNullableUnit(): Boolean = when (val value = this["unit"]) {
        JsonNull -> true
        is JsonPrimitive -> value.isString && !value.contentOrNull.isNullOrBlank()
        else -> false
    }

    private fun unknown(reason: MonitorEventUnknownReason) = MonitorEventFact.Unknown(reason)

    private val READING_SOURCE_KINDS = setOf("digital_display", "counter", "analog_dial")
}
