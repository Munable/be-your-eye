package app.beyoureyes.monitor.service.monitoring

import app.beyoureyes.monitor.*

import app.beyoureyes.monitor.MonitoringObservationSnapshot
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics
import app.beyoureyes.monitor.diagnostics.MonitoringHeartbeatStore
import app.beyoureyes.monitor.service.monitoring.MonitoringSession

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.beyoureyes.core.data.EventTaskSnapshot
import app.beyoureyes.core.data.ModelPackageRejection
import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ModelPackageRuntimeLease
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.data.RoomMonitorStore
import app.beyoureyes.core.data.RoomEventRepository
import app.beyoureyes.core.data.RoomLatestTaskReadingStore
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.data.StoredLocalTask
import app.beyoureyes.core.data.monitorPreferences
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.monitor.feature.subscription.ProductAccessState
import app.beyoureyes.monitor.feature.subscription.isGranted
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.CameraPlaneBufferView
import app.beyoureyes.core.vision.CameraYuv420FrameMapper
import app.beyoureyes.core.vision.FrameNormalizationException
import app.beyoureyes.core.vision.FrameNormalizationFailure
import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestResult
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestRunner
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntime
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import app.beyoureyes.core.vision.RuntimeActivationError
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameMetadata
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.VerifiedModelPackage
import app.beyoureyes.core.vision.Yuv420FrameNormalizer
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Camera FGS started only from the visible setup Activity; never from boot/background callbacks. */
class MonitoringService : LifecycleService() {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sourceSequence = AtomicLong(0)
    private val processedFrameCount = AtomicLong(0)
    private val unavailableFrameCount = AtomicLong(0)
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraPreview: Preview? = null
    private var startGeneration: Long = 0
    private var receivedFirstFrame = false
    private var preserveErrorOnDestroy = false
    private var explicitStopRequested = false
    private var stopFinalizationPending = false
    private var foregroundStarted = false
    private var activeCameraConfig: RuntimeCameraConfig? = null
    private var monitoringSession: MonitoringSession? = null
    private var runtimeInitializationPending = false
    private var lastLoggedObservationAvailable: Boolean? = null
    private val heartbeatStore by lazy { MonitoringHeartbeatStore(filesDir) }
    private var lastHeartbeatElapsedMillis = Long.MIN_VALUE

    /** Resolve every background status message against the current app locale. */
    private fun localizedString(id: Int, vararg args: Any): String =
        ContextCompat.getContextForLanguage(this).getString(id, *args)

    private val frameWatchdog = Runnable {
        failAndStop(
            if (receivedFirstFrame) {
                localizedString(R.string.service_camera_stalled)
            } else {
                localizedString(R.string.service_camera_no_first_frame)
            },
        )
    }
    private val productAccessWatchdog = object : Runnable {
        override fun run() {
            if (activeCameraConfig == null || stopFinalizationPending) return
            if (appContainer.currentAccessDecision() != app.beyoureyes.monitor.feature.subscription.ProductAccessDecision.GRANTED) {
                failAndStop(localizedString(R.string.service_access_expired))
                return
            }
            mainHandler.postDelayed(this, PRODUCT_ACCESS_CHECK_INTERVAL_MILLIS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopMonitoring(appHidden = intent.getBooleanExtra(EXTRA_APP_HIDDEN, false))
            ACTION_START -> startMonitoring(intent.toRuntimeCameraConfig())
            else -> stopSelf(startId)
        }
        return Service.START_NOT_STICKY
    }

    private fun startMonitoring(config: RuntimeCameraConfig?) {
        if (runtimeInitializationPending || monitoringSession != null ||
            cameraProvider != null || cameraProviderFuture != null
        ) return

        preserveErrorOnDestroy = false
        explicitStopRequested = false
        receivedFirstFrame = false
        MonitoringRuntimeState.clearObservation()
        lastLoggedObservationAvailable = null
        processedFrameCount.set(0)
        unavailableFrameCount.set(0)
        val generation = ++startGeneration
        RuntimeDiagnostics.record(this, "monitoring_start_requested")
        try {
            NotificationChannels.ensureCreated(this)
            ServiceCompat.startForeground(
                this,
                NotificationChannels.MONITORING_NOTIFICATION_ID,
                NotificationChannels.startingCamera(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    0
                },
            )
            foregroundStarted = true
        } catch (error: RuntimeException) {
            logFailure("foreground_start_failed", error)
            failAndStop(localizedString(R.string.service_fgs_rejected))
            return
        }

        if (config == null) {
            failAndStop(localizedString(R.string.service_invalid_camera_config))
            return
        }
        if (appContainer.currentAccessDecision() != app.beyoureyes.monitor.feature.subscription.ProductAccessDecision.GRANTED) {
            MonitoringRuntimeState.update(
                MonitoringPhase.STOPPED,
                localizedString(R.string.service_access_required),
                MonitoringHealth.FATAL,
                monitorId = config.taskId,
            )
            stopForegroundIfStarted()
            stopSelf()
            return
        }
        activeCameraConfig = config
        MonitoringRuntimeState.setManualReadingScanRegion(config.manualReadingScanRegion)
        if (!heartbeatStore.begin(config.taskId, System.currentTimeMillis())) {
            failAndStop(localizedString(R.string.service_state_save_failed))
            return
        }
        lastHeartbeatElapsedMillis = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(productAccessWatchdog)
        mainHandler.postDelayed(productAccessWatchdog, PRODUCT_ACCESS_CHECK_INTERVAL_MILLIS)
        MonitoringRuntimeState.update(
            MonitoringPhase.STARTING,
            localizedString(R.string.service_waiting_first_frame),
            monitorId = config.taskId,
            initialBlackScreenPending = config.startWithBlackScreen,
            monitorRevision = config.taskRevision,
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failAndStop(localizedString(R.string.service_camera_permission_unavailable))
            return
        }

        runtimeInitializationPending = true
        MonitoringRuntimeState.update(MonitoringPhase.STARTING, localizedString(R.string.service_restoring_monitor))
        armFrameWatchdog()
        analysisExecutor.execute {
            val initialized = runCatching { MonitoringSession.open(this, config, sourceSequence) }
            mainHandler.post {
                runtimeInitializationPending = false
                if (generation != startGeneration) {
                    initialized.getOrNull()?.close()
                    return@post
                }
                initialized.fold(
                    onSuccess = { resources ->
                        monitoringSession = resources
                        bindCamera(config, generation)
                    },
                    onFailure = { error ->
                        logFailure("monitoring_runtime_initialization_failed", error)
                        failAndStop(runtimeInitializationMessage(error))
                    },
                )
            }
        }
    }

    private fun bindCamera(config: RuntimeCameraConfig, generation: Long) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture = providerFuture
        providerFuture.addListener(
            {
                if (generation != startGeneration) return@addListener
                try {
                    val provider = providerFuture.get()
                    val analysis = ImageAnalysis.Builder()
                        .setTargetRotation(config.targetRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val preview = Preview.Builder()
                        .setTargetRotation(config.targetRotation)
                        .build()
                    MonitoringCameraPreview.bind(preview)
                    cameraPreview = preview
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val session = monitoringSession
                            if (session == null || generation != startGeneration) return@setAnalyzer
                            val sequence = sourceSequence.incrementAndGet()
                            val receivedElapsedMillis = SystemClock.elapsedRealtime()
                            val captureTimestampNanos = imageProxy.imageInfo.timestamp
                            val monotonicTimeMillis = captureTimestampNanos / NANOS_PER_MILLISECOND
                            val capturedAtEpochMillis = System.currentTimeMillis()
                            val crop = imageProxy.cropRect
                            val metadata = try {
                                RuntimeFrameMetadata.resolve(
                                    sourceSequence = sequence,
                                    captureTimestampNanos = captureTimestampNanos,
                                    imageWidth = imageProxy.width,
                                    imageHeight = imageProxy.height,
                                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                    imageCropRect = PixelRect(
                                        crop.left,
                                        crop.top,
                                        crop.right,
                                        crop.bottom,
                                    ),
                                    uprightViewPortRoi = config.roi,
                                )
                            } catch (error: IllegalArgumentException) {
                                logFailure("frame_roi_mapping_failed", error)
                                mainHandler.post { onFrameMappingFailure(generation) }
                                return@setAnalyzer
                            }
                            if (!recordHeartbeatIfDue()) {
                                mainHandler.post {
                                    if (generation == startGeneration) {
                                        failAndStop(localizedString(R.string.service_state_save_failed))
                                    }
                                }
                                return@setAnalyzer
                            }
                            mainHandler.post { onFrame(generation, metadata) }
                            var frameCopyNanos = 0L
                            try {
                                val result = runBlocking {
                                    val accepted = session.process(
                                        sourceSequence = sequence,
                                        monotonicTimeMillis = monotonicTimeMillis,
                                        capturedAtEpochMillis = capturedAtEpochMillis,
                                    ) {
                                        val copyStartedNanos = SystemClock.elapsedRealtimeNanos()
                                        try {
                                            imageProxy.copyToSourceFrame(
                                                sequence = sequence,
                                                monotonicTimeMillis = monotonicTimeMillis,
                                                capturedAtEpochMillis = capturedAtEpochMillis,
                                            )
                                        } finally {
                                            frameCopyNanos = (
                                                SystemClock.elapsedRealtimeNanos() - copyStartedNanos
                                            ).coerceAtLeast(0L)
                                        }
                                    }
                                    accepted
                                }
                                val processed = result.runtimeResult as? RuntimeFrameResult.Processed
                                if (processed != null &&
                                    (sequence == 1L || sequence % TIMING_DIAGNOSTIC_FRAME_INTERVAL == 0L)
                                ) {
                                    RuntimeDiagnostics.record(
                                        this,
                                        "monitoring_frame_timing",
                                        mapOf(
                                            "frame_copy_ms" to nanosToMillis(frameCopyNanos),
                                            "normalize_ms" to nanosToMillis(
                                                processed.pipelineResult.timings.normalizeNanos,
                                            ),
                                            "quality_ms" to nanosToMillis(
                                                processed.pipelineResult.timings.qualityNanos,
                                            ),
                                            "preprocess_ms" to nanosToMillis(
                                                processed.pipelineResult.timings.preprocessNanos,
                                            ),
                                            "inference_ms" to nanosToMillis(
                                                processed.pipelineResult.timings.inferenceNanos,
                                            ),
                                            "adapter_ms" to nanosToMillis(
                                                processed.pipelineResult.timings.adapterNanos,
                                            ),
                                            // Temporal stability and the final rule decision are
                                            // one domain operation; keep this as one privacy-safe
                                            // measurement rather than exposing observations.
                                            "stability_rule_ms" to nanosToMillis(
                                                result.ruleEvaluationNanos,
                                            ),
                                            "event_write_ms" to nanosToMillis(result.eventWriteNanos),
                                        ),
                                    )
                                }
                                if (result.eventInserted) {
                                    RuntimeDiagnostics.record(this, "event_inserted")
                                }
                                val observation = processed?.pipelineResult?.observation
                                val unavailable = observation as? Observation.Unavailable
                                if (result.thermalModeChanged && observation == null) {
                                    mainHandler.post {
                                        onThermalModeChanged(generation, result.thermalMode)
                                    }
                                }
                                if (unavailable != null) {
                                    val unavailableCount = unavailableFrameCount.incrementAndGet()
                                    if (unavailableCount == 1L ||
                                        unavailableCount % DEBUG_PROGRESS_INTERVAL == 0L
                                    ) {
                                        Log.w(
                                            LOG_TAG,
                                            "observation_unavailable sequence=$sequence " +
                                                "reason=${unavailable.reason} " +
                                                "code=${unavailable.diagnosticCode}",
                                        )
                                    }
                                }
                                if (observation != null) {
                                    val completedElapsedMillis = SystemClock.elapsedRealtime()
                                    mainHandler.post {
                                        onObservationResult(
                                            generation,
                                            processed.pipelineResult,
                                            receivedElapsedMillis,
                                            completedElapsedMillis,
                                            result.thermalMode,
                                        )
                                    }
                                    val count = processedFrameCount.incrementAndGet()
                                    if (BuildConfig.DEBUG &&
                                        (count == 1L || count % DEBUG_PROGRESS_INTERVAL == 0L)
                                    ) {
                                        Log.i(
                                            LOG_TAG,
                                            "debug_pipeline_progress processed=$count " +
                                                "unavailable=${unavailableFrameCount.get()} " +
                                                "source_sequence=$sequence " +
                                                "pipeline_ms=" +
                                                (processed.pipelineResult.timings.totalNanos / 1_000_000.0),
                                        )
                                    }
                                }
                                if (result.thermalModeChanged &&
                                    shouldStopMonitoringForThermal(result.thermalMode)
                                ) {
                                    mainHandler.post { onCriticalThermalStop(generation) }
                                }
                            } catch (error: Exception) {
                                logFailure("event_persistence_or_runtime_failed", error)
                                mainHandler.post {
                                    onRuntimeProcessingFailure(generation)
                                }
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }
                    provider.unbindAll()
                    val viewPort = ViewPort.Builder(
                        Rational(config.viewPortWidth, config.viewPortHeight),
                        config.targetRotation,
                    )
                        .setScaleType(ViewPort.FILL_CENTER)
                        .build()
                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .build()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        useCaseGroup,
                    )
                    imageAnalysis = analysis
                    cameraProvider = provider
                    cameraProviderFuture = null
                } catch (error: Exception) {
                    logFailure("camera_bind_failed", error)
                    failAndStop(localizedString(R.string.service_camera_bind_failed))
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }


    private fun runtimeInitializationMessage(error: Throwable): String = when (
        (error as? MonitoringRuntimeInitializationException)?.error
    ) {
        MonitoringRuntimeInitializationError.TASK_NOT_FOUND,
        MonitoringRuntimeInitializationError.TASK_REVISION_CHANGED,
        MonitoringRuntimeInitializationError.TASK_INVALID,
        -> localizedString(R.string.service_setup_changed)
        MonitoringRuntimeInitializationError.TASK_PACKAGE_UNBOUND ->
            localizedString(R.string.service_detection_not_ready)
        MonitoringRuntimeInitializationError.SAMPLING_CONFIG_INVALID ->
            localizedString(R.string.service_detection_config_changed)
        MonitoringRuntimeInitializationError.PACKAGE_UNAVAILABLE ->
            localizedString(R.string.service_device_unsupported)
        MonitoringRuntimeInitializationError.MANIFEST_INVALID,
        MonitoringRuntimeInitializationError.BUILD_CHANNEL_INVALID,
        -> localizedString(R.string.service_security_check_failed)
        MonitoringRuntimeInitializationError.RULE_FAMILY_MISMATCH,
        MonitoringRuntimeInitializationError.RUNTIME_INCOMPATIBLE,
        -> localizedString(R.string.service_condition_unsupported)
        null -> localizedString(R.string.service_initialization_failed)
    }


    private fun onRuntimeProcessingFailure(generation: Long) {
        if (generation != startGeneration) return
        failAndStop(localizedString(R.string.service_processing_failed))
    }

    private fun onCriticalThermalStop(generation: Long) {
        if (generation != startGeneration) return
        failAndStop(localizedString(R.string.service_thermal_stop))
    }

    private fun ImageProxy.copyToSourceFrame(
        sequence: Long,
        monotonicTimeMillis: Long,
        capturedAtEpochMillis: Long,
    ) = if (format != ImageFormat.YUV_420_888 || planes.size != YUV_PLANE_COUNT) {
        throw FrameNormalizationException(
            FrameNormalizationFailure.UNSUPPORTED_PIXEL_FORMAT,
            "CameraX frame must have the YUV_420_888 three-plane layout",
        )
    } else {
        CameraYuv420FrameMapper.copyToSourceFrame(
            sourceSequence = sequence,
            monotonicTimeMillis = monotonicTimeMillis,
            capturedAtEpochMillis = capturedAtEpochMillis,
            width = width,
            height = height,
            cropRect = PixelRect(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom),
            rotationDegrees = imageInfo.rotationDegrees,
            yPlane = planes[0].toBufferView(),
            uPlane = planes[1].toBufferView(),
            vPlane = planes[2].toBufferView(),
        )
    }

    private fun ImageProxy.PlaneProxy.toBufferView() = CameraPlaneBufferView(
        buffer = buffer,
        rowStride = rowStride,
        pixelStride = pixelStride,
    )

    private fun onFrame(generation: Long, metadata: RuntimeFrameMetadata) {
        if (generation != startGeneration) return
        MonitoringRuntimeState.recordFrame(metadata)
        if (!receivedFirstFrame) {
            receivedFirstFrame = true
            activeCameraConfig?.taskId?.let { taskId ->
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { monitorPreferences(this@MonitoringService).markTaskStarted(taskId) }
                        .onFailure {
                            RuntimeDiagnostics.record(
                                this@MonitoringService,
                                "monitoring_started_state_write_failed",
                            )
                        }
                }
            }
            Log.i(LOG_TAG, "camera_analysis_first_frame sequence=${metadata.sourceSequence}")
            RuntimeDiagnostics.record(
                this,
                "monitoring_first_frame",
                mapOf(
                    "image_rotation" to metadata.rotationDegrees.toString(),
                    "target_rotation" to activeCameraConfig?.targetRotation.toString(),
                    "source_size" to "${metadata.imageWidth}x${metadata.imageHeight}",
                    "crop" to metadata.imageCropRect.toString(),
                    "viewport_size" to "${activeCameraConfig?.viewPortWidth}x${activeCameraConfig?.viewPortHeight}",
                ),
            )
            MonitoringRuntimeState.update(
                MonitoringPhase.RUNNING,
                localizedString(R.string.service_camera_connected_waiting),
                MonitoringHealth.WARMING,
            )
            try {
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(
                    NotificationChannels.MONITORING_NOTIFICATION_ID,
                    NotificationChannels.cameraRunning(this),
                )
            } catch (error: SecurityException) {
                logFailure("foreground_notification_update_failed", error)
                failAndStop(localizedString(R.string.service_notification_permission_unavailable))
                return
            }
        }
        armFrameWatchdog()
    }

    private fun onObservationResult(
        generation: Long,
        result: PipelineResult,
        receivedElapsedMillis: Long,
        completedElapsedMillis: Long,
        thermalMode: app.beyoureyes.monitor.RuntimeThermalMode,
    ) {
        if (generation != startGeneration || !receivedFirstFrame) return
        val config = activeCameraConfig ?: return
        val snapshot = MonitoringObservationSnapshot(
            monitorId = config.taskId,
            monitorRevision = config.taskRevision,
            observation = result.observation,
            capturedMonotonicMillis = result.monotonicTimeMillis,
            receivedElapsedMillis = receivedElapsedMillis,
            completedElapsedMillis = completedElapsedMillis,
            publishedElapsedMillis = SystemClock.elapsedRealtime(),
            inferenceDurationMillis = result.timings.inferenceNanos / 1_000_000,
            pipelineDurationMillis = result.timings.totalNanos / 1_000_000,
        )
        if (!MonitoringRuntimeState.recordObservation(snapshot)) return
        if (BuildConfig.DEBUG && !analysisExecutor.isShutdown &&
            (lastLoggedObservationAvailable == null || snapshot.sourceSequence % 10 == 0L)
        ) {
            analysisExecutor.execute {
                RuntimeDiagnostics.record(this, "monitoring_result_published", snapshot.diagnosticMetadata())
            }
        }
        val observation = snapshot.observation
        val unavailable = observation as? Observation.Unavailable
        val available = unavailable == null
        if (lastLoggedObservationAvailable != available) {
            lastLoggedObservationAvailable = available
            RuntimeDiagnostics.record(
                this,
                if (available) "monitoring_observation_available" else {
                    "monitoring_observation_unavailable"
                },
                unavailable?.let { value ->
                    mapOf(
                        "reason" to value.reason.name.lowercase(),
                        "diagnostic" to value.diagnosticCode.orEmpty(),
                    )
                }.orEmpty(),
            )
        }
        if (unavailable == null &&
            thermalMode == app.beyoureyes.monitor.RuntimeThermalMode.LIMITED
        ) {
            MonitoringRuntimeState.update(
                MonitoringPhase.RUNNING,
                localizedString(R.string.service_thermal_throttled),
                MonitoringHealth.THERMALLY_LIMITED,
            )
        } else if (unavailable == null) {
            MonitoringRuntimeState.update(
                MonitoringPhase.RUNNING,
                localizedString(R.string.service_observing),
                MonitoringHealth.OBSERVING,
            )
        } else {
            val message = when (unavailable.reason) {
                app.beyoureyes.core.domain.UnavailableReason.NO_FRAME ->
                    localizedString(R.string.service_no_frame)
                app.beyoureyes.core.domain.UnavailableReason.LOW_QUALITY ->
                    localizedString(R.string.service_low_quality)
                app.beyoureyes.core.domain.UnavailableReason.INFERENCE_ERROR,
                app.beyoureyes.core.domain.UnavailableReason.INCOMPATIBLE_OUTPUT,
                -> localizedString(R.string.service_inference_unavailable)
                app.beyoureyes.core.domain.UnavailableReason.THERMAL_PAUSE ->
                    localizedString(R.string.service_thermal_paused)
            }
            MonitoringRuntimeState.update(
                MonitoringPhase.RUNNING,
                message,
                MonitoringHealth.TEMPORARILY_UNAVAILABLE,
            )
        }
    }

    private fun onThermalModeChanged(
        generation: Long,
        mode: app.beyoureyes.monitor.RuntimeThermalMode,
    ) {
        if (generation != startGeneration || !receivedFirstFrame) return
        when (mode) {
            app.beyoureyes.monitor.RuntimeThermalMode.NORMAL -> {
                if (MonitoringRuntimeState.latestObservationSnapshot.value?.observation !is Observation.Unavailable) {
                    MonitoringRuntimeState.update(
                        MonitoringPhase.RUNNING,
                        localizedString(R.string.service_observing),
                        MonitoringHealth.OBSERVING,
                    )
                }
            }
            app.beyoureyes.monitor.RuntimeThermalMode.LIMITED ->
                MonitoringRuntimeState.update(
                    MonitoringPhase.RUNNING,
                    localizedString(R.string.service_thermal_throttled),
                    MonitoringHealth.THERMALLY_LIMITED,
                )
            app.beyoureyes.monitor.RuntimeThermalMode.PAUSED ->
                MonitoringRuntimeState.update(
                    MonitoringPhase.RUNNING,
                    localizedString(R.string.service_thermal_paused),
                    MonitoringHealth.TEMPORARILY_UNAVAILABLE,
                )
        }
    }

    private fun onFrameMappingFailure(generation: Long) {
        if (generation != startGeneration) return
        failAndStop(localizedString(R.string.service_frame_mapping_failed))
    }

    private fun armFrameWatchdog() {
        mainHandler.removeCallbacks(frameWatchdog)
        mainHandler.postDelayed(frameWatchdog, FRAME_TIMEOUT_MILLIS)
    }

    private fun failAndStop(message: String) {
        if (stopFinalizationPending) return
        stopFinalizationPending = true
        preserveErrorOnDestroy = true
        RuntimeDiagnostics.record(this, "monitoring_failed", mapOf("reason" to message))
        val monitorId = activeCameraConfig?.taskId ?: MonitoringRuntimeState.status.value.monitorId
        publishSafeStopProgress()
        // A fatal camera/runtime stop is still a monitoring stop. STOPPED is published only after
        // the analyzer queue has committed the close boundary and released its Room/runtime lease.
        releaseCameraCallbacks(closeOpenReferenceEpisode = true) { closeResult ->
            if (closeResult.isSuccess) {
                monitorId?.let(heartbeatStore::clear)
            } else {
                RuntimeDiagnostics.record(this, "monitoring_stop_boundary_deferred")
            }
            MonitoringRuntimeState.update(
                MonitoringPhase.STOPPED,
                if (closeResult.isSuccess) message else {
                    localizedString(R.string.service_stop_recovery_pending)
                },
                MonitoringHealth.FATAL,
                monitorId = monitorId,
            )
            stopForegroundIfStarted()
            stopSelf()
        }
    }

    private fun stopMonitoring(appHidden: Boolean = false) {
        if (stopFinalizationPending) return
        stopFinalizationPending = true
        explicitStopRequested = true
        preserveErrorOnDestroy = false
        RuntimeDiagnostics.record(this, "monitoring_stopped")
        val monitorId = activeCameraConfig?.taskId ?: MonitoringRuntimeState.status.value.monitorId
        publishSafeStopProgress()
        releaseCameraCallbacks(closeOpenReferenceEpisode = true) { closeResult ->
            if (closeResult.isSuccess) {
                monitorId?.let(heartbeatStore::clear)
                MonitoringRuntimeState.update(
                    MonitoringPhase.STOPPED,
                    message = if (appHidden) localizedString(R.string.service_stopped_app_hidden) else null,
                    stoppedWhenHidden = appHidden,
                )
            } else {
                RuntimeDiagnostics.record(this, "monitoring_stop_boundary_deferred")
                MonitoringRuntimeState.update(
                    MonitoringPhase.STOPPED,
                    localizedString(R.string.service_stop_recovery_pending),
                    MonitoringHealth.FATAL,
                    monitorId = monitorId,
                )
            }
            stopForegroundIfStarted()
            stopSelf()
        }
    }

    private fun publishSafeStopProgress() {
        val current = MonitoringRuntimeState.status.value
        if (current.phase == MonitoringPhase.STOPPED) return
        MonitoringRuntimeState.update(
            next = current.phase,
            message = localizedString(R.string.service_stopping_and_saving),
            health = current.health,
            monitorId = current.monitorId,
        )
    }

    private fun stopForegroundIfStarted() {
        if (!foregroundStarted) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun logFailure(event: String, error: Throwable) {
        // Stack traces can carry file paths or provider response text. Product logs keep only the
        // stable failure event and exception type; the user-facing state owns the safe message.
        Log.e(LOG_TAG, "$event type=${error.javaClass.simpleName}")
    }

    private fun releaseCameraCallbacks(
        closeOpenReferenceEpisode: Boolean,
        onReleased: ((Result<Unit>) -> Unit)? = null,
    ) {
        ++startGeneration
        runtimeInitializationPending = false
        // Keep generation-guarded callbacks on the looper. In particular, the asynchronous
        // MonitoringSession.open callback must run its generation-mismatch branch so a session
        // opened concurrently with Back/stop is closed; removing it here would leak its runtime
        // lease when the callback never gets a chance to release the just-created session.
        mainHandler.removeCallbacks(frameWatchdog)
        mainHandler.removeCallbacks(productAccessWatchdog)
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraPreview?.let(MonitoringCameraPreview::unbind)
        cameraPreview = null
        cameraProviderFuture?.cancel(true)
        cameraProviderFuture = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        val resourcesToClose = monitoringSession
        monitoringSession = null
        if (resourcesToClose != null) {
            val closeResources = Runnable {
                val result = runCatching {
                    if (closeOpenReferenceEpisode) {
                        resourcesToClose.closeForMonitoringStop()
                    } else {
                        resourcesToClose.close()
                    }
                }
                if (onReleased != null) mainHandler.post { onReleased(result) }
            }
            try {
                // The analyzer owns the backend and Room transaction boundary. Queue disposal
                // behind any in-flight frame so UI/notification stop cannot close them mid-run.
                analysisExecutor.execute(closeResources)
            } catch (_: RejectedExecutionException) {
                closeResources.run()
            }
        } else {
            onReleased?.invoke(Result.success(Unit))
        }
        activeCameraConfig = null
        receivedFirstFrame = false
        MonitoringRuntimeState.clearFrameMetadata()
        MonitoringRuntimeState.clearObservation()
    }

    private fun recordHeartbeatIfDue(): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastHeartbeatElapsedMillis < HEARTBEAT_INTERVAL_MILLIS) return true
        lastHeartbeatElapsedMillis = nowElapsed
        val monitorId = activeCameraConfig?.taskId ?: return false
        return heartbeatStore.heartbeat(monitorId, System.currentTimeMillis())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopMonitoring(appHidden = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val wasActive = activeCameraConfig != null ||
            runtimeInitializationPending ||
            foregroundStarted
        val interruptedMonitorId = activeCameraConfig?.taskId
            ?: MonitoringRuntimeState.status.value.monitorId
        if (BuildConfig.DEBUG) {
            Log.i(
                LOG_TAG,
                "debug_pipeline_final processed=${processedFrameCount.get()} " +
                    "unavailable=${unavailableFrameCount.get()} " +
                    "source_sequence=${sourceSequence.get()}",
            )
        }
        // Service teardown is an interruption boundary, not evidence that the target left. Queue
        // the explicit monitoring-stopped fact behind in-flight analysis. The heartbeat remains
        // deliberately, so the next process idempotently verifies or repairs that boundary.
        releaseCameraCallbacks(closeOpenReferenceEpisode = wasActive)
        stopForegroundIfStarted()
        analysisExecutor.shutdown()
        if (shouldReportUnexpectedMonitoringServiceDestroy(
                wasActive = wasActive,
                preserveErrorOnDestroy = preserveErrorOnDestroy,
                explicitStopRequested = explicitStopRequested,
            ) && interruptedMonitorId != null
        ) {
            RuntimeDiagnostics.record(this, "monitoring_service_destroyed_unexpectedly")
            MonitoringRuntimeState.update(
                next = MonitoringPhase.STOPPED,
                message = localizedString(R.string.service_unexpected_stop),
                health = MonitoringHealth.FATAL,
                monitorId = interruptedMonitorId,
            )
        } else if (!preserveErrorOnDestroy && !explicitStopRequested) {
            MonitoringRuntimeState.update(MonitoringPhase.STOPPED)
        }
        super.onDestroy()
    }

    private fun nanosToMillis(nanos: Long): String =
        (nanos.coerceAtLeast(0L) / 1_000_000.0).toString()

    companion object {
        private const val ACTION_START = "app.beyoureyes.monitor.action.START"
        private const val ACTION_STOP = "app.beyoureyes.monitor.action.STOP"
        private const val EXTRA_APP_HIDDEN = "app_hidden"
        private const val LOG_TAG = "BeYourEyeMonitor"
        private const val FRAME_TIMEOUT_MILLIS = 10_000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
        private const val PRODUCT_ACCESS_CHECK_INTERVAL_MILLIS = 5_000L
        private const val DEBUG_PROGRESS_INTERVAL = 120L
        private const val TIMING_DIAGNOSTIC_FRAME_INTERVAL = 60L
        private const val YUV_PLANE_COUNT = 3
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TASK_REVISION = "task_revision"
        private const val EXTRA_ROI_LEFT = "roi_left"
        private const val EXTRA_ROI_TOP = "roi_top"
        private const val EXTRA_ROI_RIGHT = "roi_right"
        private const val EXTRA_ROI_BOTTOM = "roi_bottom"
        private const val EXTRA_VIEWPORT_WIDTH = "viewport_width"
        private const val EXTRA_VIEWPORT_HEIGHT = "viewport_height"
        private const val EXTRA_TARGET_ROTATION = "target_rotation"
        private const val EXTRA_READING_TARGET_PRESENT = "reading_target_present"
        private const val EXTRA_READING_TARGET_LEFT = "reading_target_left"
        private const val EXTRA_READING_TARGET_TOP = "reading_target_top"
        private const val EXTRA_READING_TARGET_RIGHT = "reading_target_right"
        private const val EXTRA_READING_TARGET_BOTTOM = "reading_target_bottom"
        private const val EXTRA_START_WITH_BLACK_SCREEN = "start_with_black_screen"
        private const val EXTRA_SAMPLING_CATALOG_VERSION = "sampling_catalog_version"
        private const val EXTRA_SAMPLING_CAPABILITY_ID = "sampling_capability_id"
        private const val EXTRA_SAMPLING_MODEL_PROFILE_KEY = "sampling_model_profile_key"
        private const val EXTRA_SAMPLING_RECIPE_ID = "sampling_recipe_id"
        private const val EXTRA_SAMPLING_INTENT_KEY = "sampling_intent_key"
        private const val EXTRA_SAMPLING_PACKAGE_ID = "sampling_package_id"
        private const val EXTRA_SAMPLING_PACKAGE_VERSION = "sampling_package_version"
        private const val EXTRA_SAMPLING_MANIFEST_SHA256 = "sampling_manifest_sha256"
        private const val EXTRA_SAMPLING_ARTIFACT_IDENTITY_SHA256 =
            "sampling_artifact_identity_sha256"
        private const val EXTRA_SAMPLING_DEVICE_FINGERPRINT_SHA256 =
            "sampling_device_fingerprint_sha256"
        private const val EXTRA_SAMPLING_INTERVAL_MILLIS = "sampling_interval_millis"
        private const val EXTRA_SAMPLING_MIN_INTERVAL_MILLIS = "sampling_min_interval_millis"
        private const val EXTRA_SAMPLING_MAX_INTERVAL_MILLIS = "sampling_max_interval_millis"
        private const val EXTRA_SAMPLING_ADAPTIVE_ENABLED = "sampling_adaptive_enabled"

        fun startIntent(context: Context, config: RuntimeCameraConfig): Intent =
            Intent(context, MonitoringService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TASK_ID, config.taskId)
                .putExtra(EXTRA_TASK_REVISION, config.taskRevision)
                .putExtra(EXTRA_ROI_LEFT, config.roi.left)
                .putExtra(EXTRA_ROI_TOP, config.roi.top)
                .putExtra(EXTRA_ROI_RIGHT, config.roi.right)
                .putExtra(EXTRA_ROI_BOTTOM, config.roi.bottom)
                .putExtra(EXTRA_VIEWPORT_WIDTH, config.viewPortWidth)
                .putExtra(EXTRA_VIEWPORT_HEIGHT, config.viewPortHeight)
                .putExtra(EXTRA_TARGET_ROTATION, config.targetRotation)
                .putExtra(EXTRA_READING_TARGET_PRESENT, config.manualReadingScanRegion != null)
                .putExtra(EXTRA_READING_TARGET_LEFT, config.manualReadingScanRegion?.left ?: Float.NaN)
                .putExtra(EXTRA_READING_TARGET_TOP, config.manualReadingScanRegion?.top ?: Float.NaN)
                .putExtra(EXTRA_READING_TARGET_RIGHT, config.manualReadingScanRegion?.right ?: Float.NaN)
                .putExtra(EXTRA_READING_TARGET_BOTTOM, config.manualReadingScanRegion?.bottom ?: Float.NaN)
                .putExtra(EXTRA_START_WITH_BLACK_SCREEN, config.startWithBlackScreen)
                .putExtra(
                    EXTRA_SAMPLING_CATALOG_VERSION,
                    config.resolvedSamplingConfig.catalogVersion,
                )
                .putExtra(
                    EXTRA_SAMPLING_CAPABILITY_ID,
                    config.resolvedSamplingConfig.capabilityId,
                )
                .putExtra(
                    EXTRA_SAMPLING_MODEL_PROFILE_KEY,
                    config.resolvedSamplingConfig.modelProfileKey,
                )
                .putExtra(
                    EXTRA_SAMPLING_RECIPE_ID,
                    config.resolvedSamplingConfig.recipeId,
                )
                .putExtra(
                    EXTRA_SAMPLING_INTENT_KEY,
                    config.resolvedSamplingConfig.intentKey,
                )
                .putExtra(
                    EXTRA_SAMPLING_PACKAGE_ID,
                    config.resolvedSamplingConfig.packagePointer.identity.packageId,
                )
                .putExtra(
                    EXTRA_SAMPLING_PACKAGE_VERSION,
                    config.resolvedSamplingConfig.packagePointer.identity.packageVersion,
                )
                .putExtra(
                    EXTRA_SAMPLING_MANIFEST_SHA256,
                    config.resolvedSamplingConfig.packagePointer.canonicalManifestSha256,
                )
                .putExtra(
                    EXTRA_SAMPLING_ARTIFACT_IDENTITY_SHA256,
                    config.resolvedSamplingConfig.artifactIdentitySha256,
                )
                .putExtra(
                    EXTRA_SAMPLING_DEVICE_FINGERPRINT_SHA256,
                    config.resolvedSamplingConfig.deviceFingerprintSha256,
                )
                .putExtra(EXTRA_SAMPLING_INTERVAL_MILLIS, config.analysisIntervalMillis)
                .putExtra(
                    EXTRA_SAMPLING_MIN_INTERVAL_MILLIS,
                    config.resolvedSamplingConfig.manifestMinimumIntervalMillis,
                )
                .putExtra(
                    EXTRA_SAMPLING_MAX_INTERVAL_MILLIS,
                    config.resolvedSamplingConfig.manifestMaximumIntervalMillis,
                )
                .putExtra(
                    EXTRA_SAMPLING_ADAPTIVE_ENABLED,
                    config.resolvedSamplingConfig.adaptiveEnabled,
                )

        fun stopIntent(context: Context, appHidden: Boolean = false): Intent =
            Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)
                .putExtra(EXTRA_APP_HIDDEN, appHidden)

        private fun Intent?.toRuntimeCameraConfig(): RuntimeCameraConfig? = runCatching {
            requireNotNull(this)
            val taskId = requireNotNull(getStringExtra(EXTRA_TASK_ID))
            val taskRevision = getLongExtra(EXTRA_TASK_REVISION, 0)
            val samplingConfig = ResolvedSamplingConfig(
                taskId = taskId,
                taskRevision = taskRevision,
                catalogVersion = requireNotNull(getStringExtra(EXTRA_SAMPLING_CATALOG_VERSION)),
                capabilityId = requireNotNull(getStringExtra(EXTRA_SAMPLING_CAPABILITY_ID)),
                modelProfileKey = requireNotNull(
                    getStringExtra(EXTRA_SAMPLING_MODEL_PROFILE_KEY),
                ),
                recipeId = requireNotNull(getStringExtra(EXTRA_SAMPLING_RECIPE_ID)),
                intentKey = requireNotNull(getStringExtra(EXTRA_SAMPLING_INTENT_KEY)),
                packagePointer = ModelPackagePointer(
                    identity = ModelPackageIdentity(
                        requireNotNull(getStringExtra(EXTRA_SAMPLING_PACKAGE_ID)),
                        requireNotNull(getStringExtra(EXTRA_SAMPLING_PACKAGE_VERSION)),
                    ),
                    canonicalManifestSha256 = requireNotNull(
                        getStringExtra(EXTRA_SAMPLING_MANIFEST_SHA256),
                    ),
                ),
                artifactIdentitySha256 = requireNotNull(
                    getStringExtra(EXTRA_SAMPLING_ARTIFACT_IDENTITY_SHA256),
                ),
                deviceFingerprintSha256 = requireNotNull(
                    getStringExtra(EXTRA_SAMPLING_DEVICE_FINGERPRINT_SHA256),
                ),
                intervalMillis = getLongExtra(EXTRA_SAMPLING_INTERVAL_MILLIS, 0),
                manifestMinimumIntervalMillis = getLongExtra(
                    EXTRA_SAMPLING_MIN_INTERVAL_MILLIS,
                    0,
                ),
                manifestMaximumIntervalMillis = getLongExtra(
                    EXTRA_SAMPLING_MAX_INTERVAL_MILLIS,
                    0,
                ),
                adaptiveEnabled = getBooleanExtra(EXTRA_SAMPLING_ADAPTIVE_ENABLED, true),
            )
            RuntimeCameraConfig.fromRaw(
                taskId = taskId,
                taskRevision = taskRevision,
                roiLeft = getFloatExtra(EXTRA_ROI_LEFT, Float.NaN),
                roiTop = getFloatExtra(EXTRA_ROI_TOP, Float.NaN),
                roiRight = getFloatExtra(EXTRA_ROI_RIGHT, Float.NaN),
                roiBottom = getFloatExtra(EXTRA_ROI_BOTTOM, Float.NaN),
                viewPortWidth = getIntExtra(EXTRA_VIEWPORT_WIDTH, 0),
                viewPortHeight = getIntExtra(EXTRA_VIEWPORT_HEIGHT, 0),
                targetRotation = getIntExtra(EXTRA_TARGET_ROTATION, -1),
                resolvedSamplingConfig = samplingConfig,
                readingTargetLeft = if (getBooleanExtra(EXTRA_READING_TARGET_PRESENT, false)) {
                    getFloatExtra(EXTRA_READING_TARGET_LEFT, Float.NaN)
                } else null,
                readingTargetTop = if (getBooleanExtra(EXTRA_READING_TARGET_PRESENT, false)) {
                    getFloatExtra(EXTRA_READING_TARGET_TOP, Float.NaN)
                } else null,
                readingTargetRight = if (getBooleanExtra(EXTRA_READING_TARGET_PRESENT, false)) {
                    getFloatExtra(EXTRA_READING_TARGET_RIGHT, Float.NaN)
                } else null,
                readingTargetBottom = if (getBooleanExtra(EXTRA_READING_TARGET_PRESENT, false)) {
                    getFloatExtra(EXTRA_READING_TARGET_BOTTOM, Float.NaN)
                } else null,
                startWithBlackScreen = getBooleanExtra(EXTRA_START_WITH_BLACK_SCREEN, false),
            )
        }.getOrNull()
    }
}

internal fun shouldStopMonitoringForProductAccess(
    accessState: ProductAccessState,
): Boolean = !accessState.isGranted()

internal fun shouldReportUnexpectedMonitoringServiceDestroy(
    wasActive: Boolean,
    preserveErrorOnDestroy: Boolean,
    explicitStopRequested: Boolean,
): Boolean = wasActive && !preserveErrorOnDestroy && !explicitStopRequested

internal fun shouldStopMonitoringForThermal(
    thermalMode: RuntimeThermalMode,
): Boolean = thermalMode == RuntimeThermalMode.PAUSED
