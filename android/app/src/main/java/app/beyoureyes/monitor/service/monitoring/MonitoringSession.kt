package app.beyoureyes.monitor.service.monitoring

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import app.beyoureyes.core.data.EventTaskSnapshot
import app.beyoureyes.core.data.ModelPackageRuntimeLease
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.domain.ReadingSourceKind
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.RoomEventRepository
import app.beyoureyes.core.data.RoomLatestTaskReadingStore
import app.beyoureyes.core.data.RoomMonitorStore
import app.beyoureyes.core.data.ReferenceEventSnapshotStore
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.data.StoredLocalTask
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.domain.RestrictedEventPayload
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestResult
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestRunner
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntime
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.core.vision.PipelineTimings
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.VerifiedModelPackage
import app.beyoureyes.core.vision.Yuv420FrameNormalizer
import app.beyoureyes.monitor.AndroidEventNotificationPublisher
import app.beyoureyes.monitor.AndroidRuntimeEventNotificationTextFormatter
import app.beyoureyes.monitor.AndroidNotificationDeliveryDiagnosticSink
import app.beyoureyes.monitor.BuildConfig
import app.beyoureyes.monitor.EventNotificationContext
import app.beyoureyes.monitor.EventNotificationContextResolver
import app.beyoureyes.monitor.EventNotificationCoordinator
import app.beyoureyes.monitor.GenericMonitoringRuntimeBridge
import app.beyoureyes.core.domain.GenericObservationRuleEngine
import app.beyoureyes.monitor.LocalNotificationWorkScheduler
import app.beyoureyes.monitor.MonitoringRuntimeInitializationError
import app.beyoureyes.monitor.MonitoringRuntimeInitializationException
import app.beyoureyes.monitor.PendingNotificationRetryScheduler
import app.beyoureyes.monitor.RuntimeCameraConfig
import app.beyoureyes.core.domain.RuntimeMonitorRule
import app.beyoureyes.monitor.RuntimePackageBindingMutex
import app.beyoureyes.monitor.RuntimePackageSnapshot
import app.beyoureyes.monitor.RuntimeRoiFrameNormalizer
import app.beyoureyes.monitor.RuntimeThermalCadenceController
import app.beyoureyes.monitor.RuntimeThermalLevel
import app.beyoureyes.monitor.RuntimeThermalMode
import app.beyoureyes.monitor.StoredTaskRuntimeResolver
import app.beyoureyes.monitor.currentModelPreparationDevice
import app.beyoureyes.monitor.monitoringRuntimeSnapshotJson
import app.beyoureyes.monitor.resumedSourceSequenceBase
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

internal data class MonitoringSessionFrameResult(
    val runtimeResult: RuntimeFrameResult,
    val eventInserted: Boolean,
    val thermalMode: RuntimeThermalMode,
    val thermalModeChanged: Boolean,
    val processingNanos: Long = 0L,
    val ruleEvaluationNanos: Long = 0L,
    val eventWriteNanos: Long = 0L,
)

/** Owns one lease-bound model, rule engine and event transaction boundary. */
internal class MonitoringSession private constructor(
    private val context: Context,
    private val bridge: GenericMonitoringRuntimeBridge,
    private val thermalCadenceController: RuntimeThermalCadenceController,
    private val runtime: ModelPackageRuntime,
    private val lease: ModelPackageRuntimeLease,
    private val latestReadingStore: RoomLatestTaskReadingStore,
    private val taskId: String,
    private val taskRevision: Long,
    private val displayName: String,
    private val readingSourceKind: ReadingSourceKind?,
    private val readingBaselinePending: Boolean,
    private val baselinePromptStore: ReadingBaselinePromptStore,
    private val taskCreatedAtEpochMillis: Long,
    private val noHitReminderStore: NoHitReminderStore,
    private val monitorStore: RoomMonitorStore,
    private val sourceSequence: AtomicLong,
) : AutoCloseable {
    private var hitSeenInSession = false
    private var lastNoHitCheckAtMonotonicMillis: Long? = null

    suspend fun process(
        sourceSequence: Long,
        monotonicTimeMillis: Long,
        capturedAtEpochMillis: Long,
        frameFactory: () -> SourceFrame,
    ): MonitoringSessionFrameResult {
        val thermal = thermalCadenceController.update(
            observedAtMonotonicMillis = SystemClock.elapsedRealtime(),
            thermalLevel = currentThermalLevel(),
        )
        if (thermal.intervalChanged) {
            bridge.updateSamplingPolicy(FrameSamplingPolicy(thermal.intervalMillis))
        }
        maybePostNoHitReminder()
        if (thermal.mode == RuntimeThermalMode.PAUSED) {
            val runtimeResult = if (thermal.modeChanged) {
                thermalPauseResult(sourceSequence, monotonicTimeMillis, capturedAtEpochMillis)
            } else {
                RuntimeFrameResult.Skipped(sourceSequence)
            }
            val unavailable = (runtimeResult as? RuntimeFrameResult.Processed)
                ?.pipelineResult?.observation
            if (unavailable != null && readingSourceKind != null) {
                latestReadingStore.record(
                    taskId = taskId,
                    taskRevision = taskRevision,
                    observation = unavailable,
                    observedAtEpochMillis = capturedAtEpochMillis,
                    configuredUnit = null,
                    sourceKind = readingSourceKind,
                )
            }
            return MonitoringSessionFrameResult(
                runtimeResult = runtimeResult,
                eventInserted = false,
                thermalMode = thermal.mode,
                thermalModeChanged = thermal.modeChanged,
            )
        }
        val processingStartedNanos = SystemClock.elapsedRealtimeNanos()
        val accepted = bridge.acceptLazy(sourceSequence, monotonicTimeMillis, frameFactory)
        if (accepted.runtimeResult is RuntimeFrameResult.Processed) {
            thermalCadenceController.recordProcessingDuration(
                app.beyoureyes.monitor.elapsedNanosToCeilingMillis(
                    (SystemClock.elapsedRealtimeNanos() - processingStartedNanos).coerceAtLeast(0L),
                ),
            )
        }
        val processed = accepted.runtimeResult as? RuntimeFrameResult.Processed
        val observation = processed?.pipelineResult?.observation
        if (accepted.eventWriteResult != null) hitSeenInSession = true
        if (observation != null && readingSourceKind != null) {
            latestReadingStore.record(
                taskId = taskId,
                taskRevision = taskRevision,
                observation = observation,
                observedAtEpochMillis = capturedAtEpochMillis,
                configuredUnit = null,
                sourceKind = readingSourceKind,
            )
        }
        if (readingBaselinePending &&
            observation is Observation.Reading && observation.stable
        ) {
            maybePromptBaselineConfirmation(observation)
        }
        return MonitoringSessionFrameResult(
            runtimeResult = accepted.runtimeResult,
            eventInserted = accepted.eventWriteResult != null,
            thermalMode = thermal.mode,
            thermalModeChanged = thermal.modeChanged,
            processingNanos = (SystemClock.elapsedRealtimeNanos() - processingStartedNanos)
                .coerceAtLeast(0L),
            ruleEvaluationNanos = accepted.ruleEvaluationNanos,
            eventWriteNanos = accepted.eventWriteNanos,
        )
    }

    /**
     * A pending-baseline task posts exactly one local prompt when the first stable reading
     * appears. The prompt is not an Event, is never synchronized, and degrades to the in-app
     * detail panel when notifications are unavailable.
     */
    private fun maybePromptBaselineConfirmation(reading: Observation.Reading) {
        if (!baselinePromptStore.claim(taskId)) return
        val posted = app.beyoureyes.monitor.NotificationChannels.postReadingBaselinePrompt(
            context = context,
            taskId = taskId,
            monitorName = displayName,
            readingText = reading.text,
        )
        app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics.record(
            context,
            if (posted) {
                "reading_baseline_prompt_delivered"
            } else {
                "reading_baseline_prompt_skipped_unavailable"
            },
        )
    }

    /**
     * Anti-misconfiguration nudge: once the task is older than [NoHitReminderPolicy.REMINDER_AGE_MILLIS]
     * and still has no Event, post exactly one gentle local reminder suggesting the optional field
     * test. It never blocks or pauses monitoring, is not an Event, and is never synchronized.
     */
    private suspend fun maybePostNoHitReminder() {
        if (hitSeenInSession) return
        val nowMonotonicMillis = SystemClock.elapsedRealtime()
        if (!NoHitReminderPolicy.checkDue(lastNoHitCheckAtMonotonicMillis, nowMonotonicMillis)) {
            return
        }
        lastNoHitCheckAtMonotonicMillis = nowMonotonicMillis
        if (!NoHitReminderPolicy.reminderDue(
                createdAtEpochMillis = taskCreatedAtEpochMillis,
                nowEpochMillis = System.currentTimeMillis(),
            )
        ) {
            return
        }
        if (runCatching { monitorStore.hasAnyEvent(taskId) }.getOrDefault(true)) {
            hitSeenInSession = true
            return
        }
        if (!noHitReminderStore.claim(taskId)) return
        val posted = app.beyoureyes.monitor.NotificationChannels.postNoHitReminder(
            context = context,
            taskId = taskId,
            monitorName = displayName,
        )
        app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics.record(
            context,
            if (posted) {
                "no_hit_reminder_delivered"
            } else {
                "no_hit_reminder_skipped_unavailable"
            },
        )
    }

    private fun thermalPauseResult(
        sourceSequence: Long,
        monotonicTimeMillis: Long,
        capturedAtEpochMillis: Long,
    ) = RuntimeFrameResult.Processed(
        PipelineResult(
            sourceSequence = sourceSequence,
            monotonicTimeMillis = monotonicTimeMillis,
            capturedAtEpochMillis = capturedAtEpochMillis,
            observation = Observation.Unavailable(
                reason = app.beyoureyes.core.domain.UnavailableReason.THERMAL_PAUSE,
                diagnosticCode = "android_thermal_critical",
                sourceSequence = sourceSequence,
            ),
            timings = PipelineTimings(0, 0, 0, 0, 0),
        ),
    )

    private fun currentThermalLevel(): RuntimeThermalLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return RuntimeThermalLevel.UNKNOWN
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return RuntimeThermalLevel.UNKNOWN
        return app.beyoureyes.monitor.runtimeThermalLevelFromAndroidStatus(
            runCatching { manager.currentThermalStatus }.getOrNull(),
        )
    }

    override fun close() {
        try {
            runtime.close()
        } finally {
            lease.close()
        }
    }

    fun closeForMonitoringStop() {
        try {
            runBlocking {
                bridge.closeOpenReferenceEpisodeForMonitoringStop(
                    sourceSequence = sourceSequence.get(),
                    monotonicTimeMillis = SystemClock.elapsedRealtime(),
                )
            }
        } finally {
            close()
        }
    }

    companion object {
        fun open(
            context: Context,
            config: RuntimeCameraConfig,
            sourceSequence: AtomicLong,
        ): MonitoringSession {
        var lease: ModelPackageRuntimeLease? = null
        var runtime: ModelPackageRuntime? = null
        try {
            val roomDatabase = MonitorDatabaseFactory.open(context)
            val roomStore = RoomMonitorStore(roomDatabase)
            val artifactStore = ModelPackageStores.open(context.filesDir)
            val binding = runBlocking {
                RuntimePackageBindingMutex.mutex.withLock {
                    var acquiredLease: ModelPackageRuntimeLease? = null
                    try {
                        val initialTask = roomStore.find(config.taskId)
                            ?: throw MonitoringRuntimeInitializationException(
                                MonitoringRuntimeInitializationError.TASK_NOT_FOUND,
                            )
                        if (initialTask.revision != config.taskRevision) {
                            throw MonitoringRuntimeInitializationException(
                                MonitoringRuntimeInitializationError.TASK_REVISION_CHANGED,
                            )
                        }
                        val initialPointer = initialTask.runtimePackagePointer
                            ?: throw MonitoringRuntimeInitializationException(
                                MonitoringRuntimeInitializationError.TASK_PACKAGE_UNBOUND,
                            )
                        val initialSampling = roomStore.findResolvedSamplingConfig(config.taskId)
                            ?: throw MonitoringRuntimeInitializationException(
                                MonitoringRuntimeInitializationError.SAMPLING_CONFIG_INVALID,
                            )
                        if (initialSampling != config.resolvedSamplingConfig ||
                            initialSampling.packagePointer != initialPointer
                        ) {
                            throw MonitoringRuntimeInitializationException(
                                MonitoringRuntimeInitializationError.SAMPLING_CONFIG_INVALID,
                            )
                        }
                        acquiredLease = when (
                            val acquisition = artifactStore.acquireRuntimeLease(
                                initialPointer,
                                System.currentTimeMillis(),
                            )
                        ) {
                            is ModelPackageRuntimeLeaseResult.Acquired -> acquisition.lease
                            is ModelPackageRuntimeLeaseResult.Rejected ->
                                throw MonitoringRuntimeInitializationException(
                                    MonitoringRuntimeInitializationError.PACKAGE_UNAVAILABLE,
                                    packageRejections = acquisition.reasons,
                                )
                        }

                        // This is the final Room check after the exact package lease is held.
                        // Activation/binding uses the same process mutex, and the lease prevents
                        // a package hot swap for the complete runtime lifetime.
                        val finalTask = roomStore.find(config.taskId)
                        val finalSampling = roomStore.findResolvedSamplingConfig(config.taskId)
                        if (finalTask != initialTask || finalSampling != initialSampling) {
                            throw MonitoringRuntimeInitializationException(
                                MonitoringRuntimeInitializationError.SAMPLING_CONFIG_INVALID,
                            )
                        }
                        RuntimeLeaseBinding(
                            initialTask,
                            initialSampling,
                            checkNotNull(acquiredLease),
                        )
                    } catch (error: Exception) {
                        acquiredLease?.close()
                        throw error
                    }
                }
            }
            val storedTask = binding.task
            val persistedSamplingConfig = binding.samplingConfig
            val runtimePackagePointer = checkNotNull(storedTask.runtimePackagePointer)
            lease = binding.lease
            val task = try {
                StoredTaskRuntimeResolver(context.filesDir).resolve(
                    stored = storedTask,
                    samplingIntervalMillis = persistedSamplingConfig.intervalMillis,
                )
            } catch (error: Exception) {
                throw MonitoringRuntimeInitializationException(
                    MonitoringRuntimeInitializationError.TASK_INVALID,
                    cause = error,
                )
            }
            if (task.cameraRegion != config.roi) {
                throw MonitoringRuntimeInitializationException(
                    MonitoringRuntimeInitializationError.TASK_INVALID,
                )
            }
            val latestReadingStore = RoomLatestTaskReadingStore(roomDatabase)
            if (task.readingSourceKind != null) {
                val persistedSequence = runBlocking {
                    latestReadingStore.find(task.taskId)
                        ?.takeIf { it.taskRevision == task.revision }
                        ?.sourceSequence
                }
                if (persistedSequence != null) {
                    // A new Service process starts its in-memory counter at zero. Continue from
                    // the persisted Reading so the UI can advance immediately after recovery.
                    sourceSequence.set(
                        resumedSourceSequenceBase(sourceSequence.get(), persistedSequence),
                    )
                }
            }
            val manifest = try {
                SignedMetadataCodec.decodeStoredManifestDocument(
                    documentBytes = lease.canonicalManifest.copyBytes(),
                    expectedIdentity = lease.descriptor.identity,
                    expectedSha256 = lease.descriptor.canonicalManifestSha256,
                )
            } catch (error: Exception) {
                throw MonitoringRuntimeInitializationException(
                    MonitoringRuntimeInitializationError.MANIFEST_INVALID,
                    cause = error,
                )
            }
            val samplingMismatches = persistedSamplingConfig.exactMismatchReasons(
                expectedTaskId = storedTask.taskId,
                expectedTaskRevision = storedTask.revision,
                expectedCatalogVersion = persistedSamplingConfig.catalogVersion,
                expectedCapabilityId = task.capabilityId,
                expectedModelProfileKey = persistedSamplingConfig.modelProfileKey,
                expectedRecipeId = persistedSamplingConfig.recipeId,
                expectedIntentKey = persistedSamplingConfig.intentKey,
                expectedPackagePointer = runtimePackagePointer,
                expectedArtifactIdentitySha256 = lease.descriptor.descriptorSha256,
                expectedDeviceFingerprintSha256 = currentModelPreparationDevice(context)
                    .samplingFingerprintSha256,
                signedSamplingPolicy = manifest.parameterProfile.samplingPolicy,
            )
            if (samplingMismatches.isNotEmpty()) {
                throw MonitoringRuntimeInitializationException(
                    MonitoringRuntimeInitializationError.SAMPLING_CONFIG_INVALID,
                )
            }
            requireRuleFamilyCompatibility(task.rule, manifest.runtimeFamily)
            val buildChannel = BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)
                ?: throw MonitoringRuntimeInitializationException(
                    MonitoringRuntimeInitializationError.BUILD_CHANNEL_INVALID,
                )
            val verifiedPackage = VerifiedModelPackage(
                manifest = manifest,
                catalogEntryActive = lease.gateReport.catalogEntryActive,
                catalogSignatureValid = lease.gateReport.catalogSignatureValid,
                catalogManifestSha256Matches = lease.gateReport.catalogManifestHashValid,
                manifestSignatureValid = lease.gateReport.manifestSignatureValid,
                artifactSha256Valid = true,
                licenseTextSha256Valid = lease.gateReport.licenseGatePassed,
            )
            val normalizer = RuntimeRoiFrameNormalizer(
                delegate = Yuv420FrameNormalizer,
                roi = config.roi,
                cropToRoi = manifest.runtimeFamily in ROI_CROPPED_RUNTIME_FAMILIES,
            )
            val registry = ManifestRuntimeComponents.registry(
                referenceImageProvider = task.referenceImageProvider,
                referenceEmbeddingCache = ReferenceEmbeddingCaches.openAppPrivate(context.filesDir),
            )
            if (manifest.runtimeFamily == RecipeFamily.READING_PIPELINE_V1) {
                requireMonitoringKnownAnswerSelfTest(
                    ManifestKnownAnswerSelfTestRunner.runReading(
                        verifiedPackage = verifiedPackage,
                        artifactFilesByRole = lease.artifactFilesByRole,
                        buildChannel = buildChannel,
                        registry = registry,
                    ),
                )
            }
            val creation = ModelPackageRuntimeFactory(
                registry = registry,
                normalizer = normalizer,
                qualityGate = ManifestFrameQualityGates.forManifest(manifest),
            ).create(
                ModelRuntimeRequest(
                    verifiedPackage = verifiedPackage,
                    artifactFile = lease.artifactFilesByRole.getValue("primary"),
                    artifactFilesByRole = lease.artifactFilesByRole,
                    buildChannel = buildChannel,
                    targetProfile = task.targetProfile,
                    samplingPolicyOverride = FrameSamplingPolicy(config.analysisIntervalMillis),
                    confirmedReadingFormat = task.confirmedReadingFormat,
                    manualReadingScanRegion = config.manualReadingScanRegion,
                ),
            )
            runtime = when (creation) {
                is RuntimeCreationResult.Ready -> creation.runtime
                is RuntimeCreationResult.Unavailable -> throw MonitoringRuntimeInitializationException(
                    MonitoringRuntimeInitializationError.RUNTIME_INCOMPATIBLE,
                    activationErrors = creation.errors,
                )
            }
            val repository = RoomEventRepository(roomDatabase)
            val coordinator = EventNotificationCoordinator(
                eventSink = repository,
                notificationStore = repository,
                contextResolver = EventNotificationContextResolver { eventId ->
                    roomDatabase.monitoringDao().findEvent(eventId)?.let { event ->
                        EventNotificationContext(
                            taskId = event.taskId,
                            occurredAtEpochMillis = event.occurredAtEpochMillis,
                        )
                    }
                },
                publisher = AndroidEventNotificationPublisher(context),
                diagnosticSink = AndroidNotificationDeliveryDiagnosticSink(),
                retryScheduler = PendingNotificationRetryScheduler {
                    LocalNotificationWorkScheduler.enqueue(context)
                },
            )
            val taskSnapshot = EventTaskSnapshot(
                taskId = config.taskId,
                revision = config.taskRevision,
                runtimeSnapshotJson = monitoringRuntimeSnapshotJson(
                    config = config,
                    task = task,
                    runtimePackage = RuntimePackageSnapshot(
                        pointer = runtimePackagePointer,
                        runtimeFamily = manifest.runtimeFamily,
                        preprocessId = manifest.preprocessId,
                        adapterId = manifest.adapterId,
                        artifacts = lease.descriptor.artifacts,
                        artifactIdentitySha256 = lease.descriptor.descriptorSha256,
                    ),
                ),
            )
            runBlocking { repository.persistTaskSnapshot(taskSnapshot) }
            runBlocking { coordinator.recoverPending() }
            val eventSnapshots = ReferenceEventSnapshotStore.openAppPrivate(context.filesDir)
            val bridge = GenericMonitoringRuntimeBridge(
                runtime = runtime,
                taskSnapshot = taskSnapshot,
                coordinator = coordinator,
                ruleEngine = GenericObservationRuleEngine(
                    targetId = task.targetId,
                    displayName = task.displayName,
                    roi = config.roi,
                    rule = task.rule,
                    notificationTextFormatter = AndroidRuntimeEventNotificationTextFormatter(context),
                ),
                beforeEventPersist = { request, frame ->
                    val payload = request.payload as? RestrictedEventPayload.ObjectEpisode
                    if (payload?.condition == ObjectEventCondition.APPEARED) {
                        runCatching {
                            eventSnapshots.capture(request.task.taskId, request.eventId, frame)
                            val rollback: () -> Unit = {
                                eventSnapshots.deleteEvent(request.task.taskId, request.eventId)
                            }
                            rollback
                        }.getOrNull()
                    } else null
                },
            )
            val signedSampling = manifest.parameterProfile.samplingPolicy
            val thermalCadenceController = RuntimeThermalCadenceController(
                signedDefaultIntervalMillis = checkNotNull(signedSampling.defaultIntervalMillis),
                signedMinimumIntervalMillis = checkNotNull(signedSampling.minimumIntervalMillis),
                signedMaximumIntervalMillis = checkNotNull(signedSampling.maximumIntervalMillis),
                adaptationAllowed = signedSampling.adaptiveAllowed,
                initialIntervalMillis = persistedSamplingConfig.intervalMillis,
            )
            return MonitoringSession(
                context = context.applicationContext,
                bridge = bridge,
                thermalCadenceController = thermalCadenceController,
                runtime = runtime,
                lease = lease,
                latestReadingStore = latestReadingStore,
                taskId = task.taskId,
                taskRevision = task.revision,
                displayName = task.displayName,
                readingSourceKind = task.readingSourceKind,
                readingBaselinePending = task.readingBaselinePending,
                baselinePromptStore = ReadingBaselinePromptStore(context.applicationContext.filesDir),
                taskCreatedAtEpochMillis = storedTask.createdAtEpochMillis,
                noHitReminderStore = NoHitReminderStore(context.applicationContext.filesDir),
                monitorStore = roomStore,
                sourceSequence = sourceSequence,
            )
        } catch (error: Exception) {
            runtime?.close()
            lease?.close()
            throw error
        }
    }


    }
}

private data class RuntimeLeaseBinding(
    val task: StoredLocalTask,
    val samplingConfig: ResolvedSamplingConfig,
    val lease: ModelPackageRuntimeLease,
)

private fun requireMonitoringKnownAnswerSelfTest(result: ManifestKnownAnswerSelfTestResult) {
    if (result is ManifestKnownAnswerSelfTestResult.Failed) {
        throw MonitoringRuntimeInitializationException(
            MonitoringRuntimeInitializationError.RUNTIME_INCOMPATIBLE,
            activationErrors = result.errors,
        )
    }
}

private fun requireRuleFamilyCompatibility(rule: RuntimeMonitorRule, family: RecipeFamily) {
    if (!isRuleFamilyCompatible(rule, family)) {
        throw MonitoringRuntimeInitializationException(
            MonitoringRuntimeInitializationError.RULE_FAMILY_MISMATCH,
        )
    }
}

internal fun isRuleFamilyCompatible(rule: RuntimeMonitorRule, family: RecipeFamily): Boolean =
    when (rule) {
        is RuntimeMonitorRule.PresenceEpisode ->
            family == RecipeFamily.SIMILARITY_MATCH_V1 ||
                family == RecipeFamily.OBJECT_DETECTION_V1
        is RuntimeMonitorRule.Presence,
        is RuntimeMonitorRule.Absence,
        -> family == RecipeFamily.SIMILARITY_MATCH_V1 ||
            family == RecipeFamily.OBJECT_DETECTION_V1
        is RuntimeMonitorRule.ReadingThreshold -> family == RecipeFamily.READING_PIPELINE_V1
        is RuntimeMonitorRule.ObjectCount,
        is RuntimeMonitorRule.StateTransition,
        -> false
    }

private val ROI_CROPPED_RUNTIME_FAMILIES = setOf(
    RecipeFamily.SIMILARITY_MATCH_V1,
)
