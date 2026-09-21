package app.beyoureyes.monitor

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Rational
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.domain.Detection
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.vision.CameraPlaneBufferView
import app.beyoureyes.core.vision.CameraYuv420FrameMapper
import app.beyoureyes.core.vision.FrameNormalizationException
import app.beyoureyes.core.vision.FrameNormalizationFailure
import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.MonotonicFrameSampler
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

sealed interface SetupCameraStatus {
    data object Starting : SetupCameraStatus

    data class Ready(
        val imageWidth: Int,
        val imageHeight: Int,
        val rotationDegrees: Int,
        val imageCropRect: PixelRect,
    ) : SetupCameraStatus

    data class Error(val message: String) : SetupCameraStatus
}

data class RuntimeCameraConfig(
    val taskId: String,
    val taskRevision: Long,
    val roi: NormalizedRect,
    val viewPortWidth: Int,
    val viewPortHeight: Int,
    val targetRotation: Int,
    val resolvedSamplingConfig: ResolvedSamplingConfig,
    val manualReadingScanRegion: NormalizedRect? = null,
    val startWithBlackScreen: Boolean = false,
) {
    init {
        require(UuidV7.isValid(taskId)) { "runtime taskId must be UUIDv7" }
        require(taskRevision >= 1) { "runtime taskRevision must be at least 1" }
        require(roi.left < roi.right && roi.top < roi.bottom) { "runtime ROI must have positive area" }
        require(viewPortWidth > 0 && viewPortHeight > 0)
        require(resolvedSamplingConfig.taskId == taskId)
        require(resolvedSamplingConfig.taskRevision == taskRevision)
        require(targetRotation in setOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        ))
    }

    companion object {
        /** Validates raw persisted/Intent coordinates before creating an immutable runtime snapshot. */
        fun fromRaw(
            taskId: String,
            taskRevision: Long,
            roiLeft: Float,
            roiTop: Float,
            roiRight: Float,
            roiBottom: Float,
            viewPortWidth: Int,
            viewPortHeight: Int,
            targetRotation: Int,
            resolvedSamplingConfig: ResolvedSamplingConfig,
            readingTargetLeft: Float? = null,
            readingTargetTop: Float? = null,
            readingTargetRight: Float? = null,
            readingTargetBottom: Float? = null,
            startWithBlackScreen: Boolean = false,
        ): RuntimeCameraConfig = RuntimeCameraConfig(
            taskId = taskId,
            taskRevision = taskRevision,
            roi = NormalizedRect(roiLeft, roiTop, roiRight, roiBottom),
            viewPortWidth = viewPortWidth,
            viewPortHeight = viewPortHeight,
            targetRotation = targetRotation,
            resolvedSamplingConfig = resolvedSamplingConfig,
            manualReadingScanRegion = listOf(
                readingTargetLeft,
                readingTargetTop,
                readingTargetRight,
                readingTargetBottom,
            ).let { coordinates ->
                if (coordinates.all { it == null }) null else NormalizedRect(
                    requireNotNull(readingTargetLeft),
                    requireNotNull(readingTargetTop),
                    requireNotNull(readingTargetRight),
                    requireNotNull(readingTargetBottom),
                )
            },
            startWithBlackScreen = startWithBlackScreen,
        )
    }

    val analysisIntervalMillis: Long
        get() = resolvedSamplingConfig.intervalMillis
}

sealed interface MonitoringStartResult {
    data object Accepted : MonitoringStartResult
    data class Rejected(
        val message: String,
        val canOpenAccount: Boolean = false,
    ) : MonitoringStartResult
}

/** Executes a release only after every analyzer task accepted before this call has finished. */
internal suspend fun awaitSetupAnalyzerRelease(
    executor: Executor,
    release: () -> Unit,
) {
    suspendCoroutine<Unit> { continuation ->
        try {
            executor.execute {
                runCatching(release).fold(
                    onSuccess = { continuation.resume(Unit) },
                    onFailure = continuation::resumeWithException,
                )
            }
        } catch (error: RejectedExecutionException) {
            continuation.resumeWithException(error)
        }
    }
}

private fun registerSetupThermalStatusListener(
    powerManager: PowerManager?,
    executor: Executor,
    onThermalLevelChanged: (RuntimeThermalLevel) -> Unit,
): AutoCloseable? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) return null
    return Api29SetupThermalStatusRegistration(
        powerManager,
        executor,
        onThermalLevelChanged,
    )
}

@RequiresApi(Build.VERSION_CODES.Q)
private class Api29SetupThermalStatusRegistration(
    private val powerManager: PowerManager,
    executor: Executor,
    onThermalLevelChanged: (RuntimeThermalLevel) -> Unit,
) : AutoCloseable {
    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        onThermalLevelChanged(runtimeThermalLevelFromAndroidStatus(status))
    }

    init {
        powerManager.addThermalStatusListener(executor, listener)
    }

    override fun close() {
        powerManager.removeThermalStatusListener(listener)
    }
}

/** Remembers how to create fresh setup coordinators if a foreground-service handoff is rejected. */
internal class SetupPreviewRestartRequests {
    private var fieldValidation: (() -> Unit)? = null
    private var readingPreview: (() -> Unit)? = null

    fun rememberFieldValidation(restart: () -> Unit) {
        fieldValidation = restart
    }

    fun rememberReadingPreview(restart: () -> Unit) {
        readingPreview = restart
    }

    fun clearFieldValidation() {
        fieldValidation = null
    }

    fun clearReadingPreview() {
        readingPreview = null
    }

    fun clear() {
        clearFieldValidation()
        clearReadingPreview()
    }

    fun restartAfterRejectedHandoff() {
        fieldValidation?.invoke()
        readingPreview?.invoke()
    }
}

/** Owns setup Preview + ImageAnalysis and releases both before the camera FGS starts. */
internal class SetupCameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val resolvedSamplingConfig: ResolvedSamplingConfig?,
) : AutoCloseable {
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mutableStatus = MutableStateFlow<SetupCameraStatus>(SetupCameraStatus.Starting)
    private val mutableFieldValidationStatus =
        MutableStateFlow<SetupFieldValidationStatus>(SetupFieldValidationStatus.Idle)
    private val mutableFieldDetections = MutableStateFlow<List<Detection>>(emptyList())
    private val mutableReadingPreviewStatus =
        MutableStateFlow<ReadingPreviewStatus>(ReadingPreviewStatus.Idle)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val setupFrameSampler = resolvedSamplingConfig?.let { config ->
        MonotonicFrameSampler(FrameSamplingPolicy(config.manifestMinimumIntervalMillis))
    }
    private var providerFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var attachedView: PreviewView? = null
    private var viewPortSpec: ViewPortSpec? = null
    private var generation: Long = 0
    private var handedOff = false
    private var closed = false
    @Volatile
    private var setupThermalMode = nextSetupThermalMode(
        RuntimeThermalMode.NORMAL,
        currentThermalLevel(),
    )
    private var latestSetupProcessingDurationMillis: Long? = null
    private var thermalStatusRegistration: AutoCloseable? = null
    private var physicalTargetRotation: Int? = null
    private var physicalOrientationDetectionAvailable = false
    private val orientationWaitStartedAtMillis = SystemClock.elapsedRealtime()
    private val fieldValidationGeneration = AtomicLong(0)
    private var fieldValidationSourceSequence = 0L
    private var activeFieldValidation: ActiveFieldValidation? = null
    private val readingPreviewGeneration = AtomicLong(0)
    private var activeReadingPreview: ActiveReadingPreview? = null
    private var latestManualReadingScanRegion: NormalizedRect? = null
    private val previewRestartRequests = SetupPreviewRestartRequests()

    private val layoutChangeListener = View.OnLayoutChangeListener {
            view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom,
        ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            attach(view as PreviewView)
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val view = attachedView ?: return
            if (view.display?.displayId == displayId) attach(view)
        }
    }
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            val nextRotation = surfaceRotationForPhysicalOrientation(orientation) ?: return
            if (physicalTargetRotation == nextRotation) return
            physicalTargetRotation = nextRotation
            mainHandler.post {
                if (!closed && !handedOff) attachedView?.let(::attach)
            }
        }
    }
    init {
        displayManager.registerDisplayListener(displayListener, mainHandler)
        physicalOrientationDetectionAvailable = orientationListener.canDetectOrientation()
        if (physicalOrientationDetectionAvailable) orientationListener.enable()
        applySetupThermalSamplingPolicy(setupThermalMode)
        thermalStatusRegistration = runCatching {
            registerSetupThermalStatusListener(
                powerManager,
                mainExecutor,
                ::onThermalLevelChanged,
            )
        }.getOrNull()
        if (setupThermalMode == RuntimeThermalMode.PAUSED) {
            mutableStatus.value = SetupCameraStatus.Error(
                context.getString(R.string.camera_thermal_paused),
            )
        }
    }

    val status: StateFlow<SetupCameraStatus> = mutableStatus.asStateFlow()
    val fieldValidationStatus: StateFlow<SetupFieldValidationStatus> =
        mutableFieldValidationStatus.asStateFlow()
    val fieldDetections: StateFlow<List<Detection>> = mutableFieldDetections.asStateFlow()
    val readingPreviewStatus: StateFlow<ReadingPreviewStatus> =
        mutableReadingPreviewStatus.asStateFlow()

    fun attach(previewView: PreviewView) {
        if (closed || handedOff) return
        if (attachedView !== previewView) {
            attachedView?.removeOnLayoutChangeListener(layoutChangeListener)
            attachedView = previewView
            previewView.addOnLayoutChangeListener(layoutChangeListener)
        }
        if (setupThermalMode == RuntimeThermalMode.PAUSED) {
            mutableStatus.value = SetupCameraStatus.Error(
                context.getString(R.string.camera_thermal_paused),
            )
            return
        }
        val spec = previewView.currentViewPortSpec()
        if (spec == null) {
            previewView.postDelayed({ attach(previewView) }, ORIENTATION_RETRY_MILLIS)
            return
        }
        if (spec == viewPortSpec && (provider != null || providerFuture != null)) return
        bind(previewView, spec)
    }

    fun retryCamera() {
        if (closed || handedOff) return
        val view = attachedView
        if (view == null) {
            mutableStatus.value = SetupCameraStatus.Error(
                context.getString(R.string.camera_view_not_established),
            )
        } else {
            attach(view)
        }
    }

    /** Starts one explicit, bounded check. Merely opening the camera never runs the package. */
    fun startFieldValidation(
        spec: SimilarityFieldValidationSpec,
        roi: NormalizedRect,
    ) {
        if (closed || handedOff) return
        previewRestartRequests.rememberFieldValidation { startFieldValidation(spec, roi) }
        if (setupThermalMode == RuntimeThermalMode.PAUSED) {
            fieldValidationGeneration.incrementAndGet()
            mutableFieldValidationStatus.value = SetupFieldValidationStatus.Idle
            mutableFieldDetections.value = emptyList()
            return
        }
        setupFrameSampler?.reset()
        val identity = spec.identity(roi)
        val commandGeneration = fieldValidationGeneration.incrementAndGet()
        mutableFieldValidationStatus.value = SetupFieldValidationStatus.Opening(identity)
        mutableFieldDetections.value = emptyList()
        try {
            analyzerExecutor.execute {
                val previous = activeFieldValidation
                activeFieldValidation = null
                previous?.coordinator?.close()
                if (closed || handedOff || commandGeneration != fieldValidationGeneration.get()) {
                    return@execute
                }
                val runtimeOpenStartedNanos = System.nanoTime()
                val coordinator = try {
                    spec.open(identity)
                } catch (_: Exception) {
                    val runtimeOpenMillis =
                        (System.nanoTime() - runtimeOpenStartedNanos).nanosToMillis()
                    publishFieldValidationStatus(
                        commandGeneration,
                        SetupFieldValidationStatus.Failed(
                            identity,
                            emptyFieldValidationSummary(),
                        ),
                    )
                    RuntimeDiagnostics.record(
                        context,
                        "visual_setup_runtime_open",
                        mapOf(
                            "result" to "failed",
                            "runtime_open_ms" to runtimeOpenMillis.toString(),
                        ),
                    )
                    return@execute
                }
                val runtimeOpenMillis =
                    (System.nanoTime() - runtimeOpenStartedNanos).nanosToMillis()
                if (closed || handedOff || commandGeneration != fieldValidationGeneration.get()) {
                    coordinator.close()
                    return@execute
                }
                activeFieldValidation = ActiveFieldValidation(
                    commandGeneration = commandGeneration,
                    identity = identity,
                    coordinator = coordinator,
                    runtimeOpenMillis = runtimeOpenMillis,
                )
                publishFieldValidationStatus(
                    commandGeneration,
                    coordinator.state.toSetupStatus(),
                )
                if (coordinator.state is SimilarityFieldValidationUiState.Failed) {
                    RuntimeDiagnostics.record(
                        context,
                        "visual_setup_runtime_open",
                        mapOf(
                            "result" to "failed",
                            "runtime_open_ms" to runtimeOpenMillis.toString(),
                        ),
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            mutableFieldValidationStatus.value = SetupFieldValidationStatus.Failed(
                identity,
                emptyFieldValidationSummary(),
            )
        }
    }

    /** Invalidates a pass immediately when task revision, package pointer, or ROI changes. */
    fun ensureFieldValidationIdentity(currentIdentity: SimilarityFieldValidationIdentity?) {
        val current = mutableFieldValidationStatus.value
        val expected = current.expectedIdentity ?: return
        if (currentIdentity == expected) return
        previewRestartRequests.clearFieldValidation()
        val commandGeneration = fieldValidationGeneration.incrementAndGet()
        mutableFieldValidationStatus.value = SetupFieldValidationStatus.Invalidated(
            expectedIdentity = expected,
            currentIdentity = currentIdentity,
            summary = current.summaryOrEmpty(),
        )
        mutableFieldDetections.value = emptyList()
        try {
            analyzerExecutor.execute {
                val active = activeFieldValidation
                activeFieldValidation = null
                if (active != null) {
                    if (currentIdentity != null) {
                        runCatching { active.coordinator.ensureIdentity(currentIdentity) }
                    }
                    active.coordinator.close()
                }
                if (commandGeneration != fieldValidationGeneration.get()) return@execute
            }
        } catch (_: RejectedExecutionException) {
            // The public state is already terminal and fail-closed.
        }
    }

    fun clearFieldValidation() {
        previewRestartRequests.clearFieldValidation()
        fieldValidationGeneration.incrementAndGet()
        mutableFieldValidationStatus.value = SetupFieldValidationStatus.Idle
        mutableFieldDetections.value = emptyList()
        try {
            analyzerExecutor.execute {
                activeFieldValidation?.coordinator?.close()
                activeFieldValidation = null
            }
        } catch (_: RejectedExecutionException) {
            // Session shutdown owns any remaining analyzer resource.
        }
    }

    /** Runs the exact task-bound detector + reader on live visible frames; no frame is persisted. */
    fun startReadingPreview(
        spec: ReadingPreviewSpec,
        roi: NormalizedRect,
        manualScanRegion: NormalizedRect?,
    ) {
        if (closed || handedOff) return
        latestManualReadingScanRegion = manualScanRegion
        previewRestartRequests.rememberReadingPreview {
            startReadingPreview(spec, roi, latestManualReadingScanRegion)
        }
        if (setupThermalMode == RuntimeThermalMode.PAUSED) {
            readingPreviewGeneration.incrementAndGet()
            mutableReadingPreviewStatus.value = ReadingPreviewStatus.Idle
            return
        }
        setupFrameSampler?.reset()
        val identity = spec.identity(roi)
        val commandGeneration = readingPreviewGeneration.incrementAndGet()
        mutableReadingPreviewStatus.value = ReadingPreviewStatus.Opening(identity)
        try {
            analyzerExecutor.execute {
                activeReadingPreview?.coordinator?.close()
                activeReadingPreview = null
                if (closed || handedOff || commandGeneration != readingPreviewGeneration.get()) {
                    return@execute
                }
                val coordinator = try {
                    spec.open(identity)
                } catch (_: Exception) {
                    publishReadingPreviewStatus(
                        commandGeneration,
                        ReadingPreviewStatus.Failed(
                            identity,
                            "preview_open_failed",
                        ),
                    )
                    return@execute
                }
                if (closed || handedOff || commandGeneration != readingPreviewGeneration.get()) {
                    coordinator.close()
                    return@execute
                }
                activeReadingPreview = ActiveReadingPreview(
                    commandGeneration,
                    identity,
                    coordinator,
                )
                if (manualScanRegion != null && coordinator.state is ReadingPreviewStatus.Scanning) {
                    coordinator.updateManualScanRegion(identity, manualScanRegion)
                }
                publishReadingPreviewStatus(commandGeneration, coordinator.state)
            }
        } catch (_: RejectedExecutionException) {
            mutableReadingPreviewStatus.value = ReadingPreviewStatus.Failed(
                identity,
                "preview_executor_closed",
            )
        }
    }

    fun beginReadingFormatCorrection(sample: ReadingPreviewStatus.AwaitingConfirmation) {
        try {
            analyzerExecutor.execute {
                val active = activeReadingPreview ?: return@execute
                publishReadingPreviewStatus(
                    active.commandGeneration,
                    active.coordinator.beginFormatCorrection(sample),
                )
            }
        } catch (_: RejectedExecutionException) {
            // Session shutdown discards the selected sample.
        }
    }

    fun cancelReadingFormatCorrection(currentIdentity: ReadingPreviewIdentity?) {
        if (currentIdentity == null) return
        try {
            analyzerExecutor.execute {
                val active = activeReadingPreview ?: return@execute
                setupFrameSampler?.reset()
                publishReadingPreviewStatus(
                    active.commandGeneration,
                    active.coordinator.cancelFormatCorrection(currentIdentity),
                )
            }
        } catch (_: RejectedExecutionException) {
            // Session shutdown owns the runtime.
        }
    }

    fun confirmReadingPreview(sample: ReadingPreviewStatus.AwaitingConfirmation) {
        try {
            analyzerExecutor.execute {
                val active = activeReadingPreview ?: return@execute
                publishReadingPreviewStatus(
                    active.commandGeneration,
                    if (sample.editingFormat) {
                        active.coordinator.confirmFormatCorrection(sample)
                    } else {
                        active.coordinator.confirm(sample.expectedIdentity)
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            // Session shutdown leaves the preview unconfirmed.
        }
    }

    fun updateManualReadingScanRegion(
        currentIdentity: ReadingPreviewIdentity?,
        region: NormalizedRect?,
    ) {
        if (currentIdentity == null) return
        latestManualReadingScanRegion = region
        try {
            analyzerExecutor.execute {
                val active = activeReadingPreview ?: return@execute
                setupFrameSampler?.reset()
                latestSetupProcessingDurationMillis = null
                publishReadingPreviewStatus(
                    active.commandGeneration,
                    active.coordinator.updateManualScanRegion(
                        currentIdentity,
                        region,
                    ),
                )
            }
        } catch (_: RejectedExecutionException) {
            // Session shutdown owns the runtime.
        }
    }

    fun ensureReadingPreviewIdentity(currentIdentity: ReadingPreviewIdentity?) {
        val expected = mutableReadingPreviewStatus.value.expectedIdentity ?: return
        if (currentIdentity == expected) return
        previewRestartRequests.clearReadingPreview()
        readingPreviewGeneration.incrementAndGet()
        mutableReadingPreviewStatus.value = ReadingPreviewStatus.Invalidated(
            expected,
            currentIdentity,
        )
        try {
            analyzerExecutor.execute {
                val active = activeReadingPreview
                activeReadingPreview = null
                if (active != null) {
                    runCatching { active.coordinator.ensureIdentity(currentIdentity) }
                    active.coordinator.close()
                }
            }
        } catch (_: RejectedExecutionException) {
            activeReadingPreview?.coordinator?.close()
            activeReadingPreview = null
        }
    }

    fun clearReadingPreview() {
        previewRestartRequests.clearReadingPreview()
        readingPreviewGeneration.incrementAndGet()
        mutableReadingPreviewStatus.value = ReadingPreviewStatus.Idle
        try {
            analyzerExecutor.execute {
                activeReadingPreview?.coordinator?.close()
                activeReadingPreview = null
            }
        } catch (_: RejectedExecutionException) {
            activeReadingPreview?.coordinator?.close()
            activeReadingPreview = null
        }
    }

    suspend fun releaseForMonitoring(
        taskId: String,
        taskRevision: Long,
        roi: NormalizedRect,
        fieldValidationRequired: Boolean = false,
        currentFieldValidationIdentity: SimilarityFieldValidationIdentity? = null,
        currentFieldValidationStableEpisodeId: Long? = null,
        readingPreviewRequired: Boolean = false,
        currentReadingPreviewIdentity: ReadingPreviewIdentity? = null,
        resolvedSamplingOverride: ResolvedSamplingConfig? = null,
        manualReadingScanRegion: NormalizedRect? = null,
        startWithBlackScreen: Boolean = false,
        onReleased: (RuntimeCameraConfig) -> MonitoringStartResult,
    ): MonitoringStartResult = withContext(Dispatchers.Main.immediate) {
        if (handedOff) {
            return@withContext MonitoringStartResult.Rejected(
                context.getString(R.string.camera_handoff_in_progress),
            )
        }
        if (setupThermalMode == RuntimeThermalMode.PAUSED) {
            return@withContext MonitoringStartResult.Rejected(
                context.getString(R.string.camera_thermal_paused),
            )
        }
        val readyStatus = if (status.value is SetupCameraStatus.Ready && viewPortSpec != null) {
            status.value
        } else {
            withTimeoutOrNull(CAMERA_HANDOFF_READY_TIMEOUT_MILLIS) {
                status.first { it is SetupCameraStatus.Ready || it is SetupCameraStatus.Error }
            }
        }
        val spec = viewPortSpec
        if (readyStatus !is SetupCameraStatus.Ready || spec == null) {
            val result = MonitoringStartResult.Rejected(
                context.getString(R.string.camera_not_ready_to_start),
            )
            mutableStatus.value = SetupCameraStatus.Error(result.message)
            return@withContext result
        }
        if (fieldValidationRequired && !fieldValidationPassedForEpisode(
                mutableFieldValidationStatus.value,
                currentFieldValidationIdentity,
                currentFieldValidationStableEpisodeId,
            )
        ) {
            return@withContext MonitoringStartResult.Rejected(
                context.getString(R.string.camera_complete_field_check_first),
            )
        }
        if (readingPreviewRequired && !readingPreviewConfirmedFor(
                mutableReadingPreviewStatus.value,
                currentReadingPreviewIdentity,
            )
        ) {
            return@withContext MonitoringStartResult.Rejected(
                context.getString(R.string.camera_confirm_reading_first),
            )
        }
        val sampling = (resolvedSamplingOverride ?: resolvedSamplingConfig)?.takeIf {
            it.taskId == taskId && it.taskRevision == taskRevision
        }
        if (sampling == null) {
            val result = MonitoringStartResult.Rejected(
                context.getString(R.string.camera_detection_not_ready),
            )
            mutableStatus.value = SetupCameraStatus.Error(result.message)
            return@withContext result
        }
        val config = RuntimeCameraConfig(
            taskId = taskId,
            taskRevision = taskRevision,
            roi = roi,
            viewPortWidth = spec.width,
            viewPortHeight = spec.height,
            targetRotation = spec.targetRotation,
            resolvedSamplingConfig = sampling,
            manualReadingScanRegion = manualReadingScanRegion,
            startWithBlackScreen = startWithBlackScreen,
        )
        // AndroidView may receive one final update while the root switches to the clock. Mark the
        // ownership transfer first, stop CameraX on main, then queue a barrier behind every frame
        // already accepted by the single analyzer. The foreground service may acquire the package
        // only after that barrier closes both setup coordinators and their runtime leases.
        handedOff = true
        fieldValidationGeneration.incrementAndGet()
        readingPreviewGeneration.incrementAndGet()
        mutableFieldDetections.value = emptyList()
        releaseUseCases()
        val releaseFailure = runCatching {
            awaitSetupAnalyzerRelease(analyzerExecutor) {
                activeFieldValidation?.coordinator?.close()
                activeFieldValidation = null
                activeReadingPreview?.coordinator?.close()
                activeReadingPreview = null
            }
        }.exceptionOrNull()
        if (releaseFailure != null) {
            val rejected = MonitoringStartResult.Rejected(
                context.getString(R.string.camera_detection_release_failed),
            )
            restoreAfterRejectedStart(rejected.message)
            return@withContext rejected
        }
        val result = try {
            onReleased(config)
        } catch (_: RuntimeException) {
            MonitoringStartResult.Rejected(
                context.getString(R.string.camera_monitoring_start_failed_restored),
            )
        }
        if (result is MonitoringStartResult.Rejected) restoreAfterRejectedStart(result.message)
        result
    }

    private fun restoreAfterRejectedStart(message: String) {
        handedOff = false
        if (closed) return
        mutableStatus.value = SetupCameraStatus.Error(message)
        attachedView?.let(::attach)
        previewRestartRequests.restartAfterRejectedHandoff()
    }

    private fun currentThermalLevel(): RuntimeThermalLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) {
            return RuntimeThermalLevel.UNKNOWN
        }
        return runtimeThermalLevelFromAndroidStatus(
            runCatching { powerManager.currentThermalStatus }.getOrNull(),
        )
    }

    private fun onThermalLevelChanged(level: RuntimeThermalLevel) {
        if (closed || handedOff) return
        val previous = setupThermalMode
        val next = nextSetupThermalMode(previous, level)
        if (next == previous) return
        setupThermalMode = next
        applySetupThermalSamplingPolicy(next)
        when {
            next == RuntimeThermalMode.PAUSED -> {
                invalidateSetupChecksForThermalPause()
                releaseUseCases()
                mutableStatus.value = SetupCameraStatus.Error(
                    context.getString(R.string.camera_thermal_paused),
                )
            }
            previous == RuntimeThermalMode.PAUSED -> {
                setupFrameSampler?.reset()
                mutableStatus.value = SetupCameraStatus.Starting
                previewRestartRequests.restartAfterRejectedHandoff()
                mainHandler.post {
                    if (!closed && !handedOff && setupThermalMode != RuntimeThermalMode.PAUSED &&
                        provider == null && providerFuture == null &&
                        attachedView?.isAttachedToWindow == true
                    ) {
                        attachedView?.let(::attach)
                    }
                }
            }
        }
    }

    private fun invalidateSetupChecksForThermalPause() {
        fieldValidationGeneration.incrementAndGet()
        readingPreviewGeneration.incrementAndGet()
        mutableFieldValidationStatus.value = SetupFieldValidationStatus.Idle
        mutableFieldDetections.value = emptyList()
        mutableReadingPreviewStatus.value = ReadingPreviewStatus.Idle
        try {
            analyzerExecutor.execute {
                activeFieldValidation?.coordinator?.close()
                activeFieldValidation = null
                activeReadingPreview?.coordinator?.close()
                activeReadingPreview = null
            }
        } catch (_: RejectedExecutionException) {
            activeFieldValidation?.coordinator?.close()
            activeFieldValidation = null
            activeReadingPreview?.coordinator?.close()
            activeReadingPreview = null
        }
    }

    private fun applySetupThermalSamplingPolicy(mode: RuntimeThermalMode) {
        val config = resolvedSamplingConfig ?: return
        setupFrameSampler?.updatePolicy(
            FrameSamplingPolicy(
                setupThermalSamplingIntervalMillis(
                    mode = mode,
                    fastestIntervalMillis = config.manifestMinimumIntervalMillis,
                    signedDefaultIntervalMillis = config.intervalMillis,
                    signedMaximumIntervalMillis = config.manifestMaximumIntervalMillis,
                    adaptationAllowed = config.adaptiveEnabled,
                    processingDurationMillis = latestSetupProcessingDurationMillis,
                ),
            ),
        )
    }

    private fun bind(previewView: PreviewView, spec: ViewPortSpec) {
        if (setupThermalMode == RuntimeThermalMode.PAUSED) {
            mutableStatus.value = SetupCameraStatus.Error(
                context.getString(R.string.camera_thermal_paused),
            )
            return
        }
        releaseUseCases()
        mutableStatus.value = SetupCameraStatus.Starting
        val currentGeneration = ++generation
        viewPortSpec = spec

        val previewUseCase = Preview.Builder()
            .setTargetRotation(spec.targetRotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysisUseCase = ImageAnalysis.Builder()
            .setTargetRotation(spec.targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var firstFrameDelivered = false
        analysisUseCase.setAnalyzer(analyzerExecutor) { imageProxy ->
            try {
                if (!firstFrameDelivered) {
                    firstFrameDelivered = true
                    val crop = imageProxy.cropRect
                    val ready = SetupCameraStatus.Ready(
                        imageWidth = imageProxy.width,
                        imageHeight = imageProxy.height,
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        imageCropRect = PixelRect(crop.left, crop.top, crop.right, crop.bottom),
                    )
                    RuntimeDiagnostics.record(
                        context,
                        "setup_first_frame",
                        mapOf(
                            "image_rotation" to ready.rotationDegrees.toString(),
                            "target_rotation" to spec.targetRotation.toString(),
                            "source_size" to "${ready.imageWidth}x${ready.imageHeight}",
                            "crop" to ready.imageCropRect.toString(),
                            "viewport_size" to "${spec.width}x${spec.height}",
                        ),
                    )
                    mainExecutor.execute {
                        if (shouldAcceptSetupCameraProviderCallback(
                                callbackGeneration = currentGeneration,
                                currentGeneration = generation,
                                cameraWanted = setupThermalMode != RuntimeThermalMode.PAUSED,
                                closed = closed,
                                handedOff = handedOff,
                            )
                        ) {
                            mutableStatus.value = ready
                        }
                    }
                }
                if (setupThermalMode == RuntimeThermalMode.PAUSED) return@setAnalyzer
                val monotonicTimeMillis =
                    imageProxy.imageInfo.timestamp / NANOS_PER_MILLISECOND
                if (setupFrameSampler?.shouldProcess(monotonicTimeMillis) == false) {
                    return@setAnalyzer
                }
                val processingStartedNanos = SystemClock.elapsedRealtimeNanos()
                processSetupCheckFrame(imageProxy)
                latestSetupProcessingDurationMillis = elapsedNanosToCeilingMillis(
                    (SystemClock.elapsedRealtimeNanos() - processingStartedNanos)
                        .coerceAtLeast(0L),
                )
                applySetupThermalSamplingPolicy(setupThermalMode)
            } finally {
                imageProxy.close()
            }
        }

        preview = previewUseCase
        analysis = analysisUseCase
        val viewPort = ViewPort.Builder(
            Rational(spec.width, spec.height),
            spec.targetRotation,
        )
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(previewUseCase)
            .addUseCase(analysisUseCase)
            .build()

        val future = ProcessCameraProvider.getInstance(context)
        providerFuture = future
        future.addListener(
            {
                if (!shouldAcceptSetupCameraProviderCallback(
                        callbackGeneration = currentGeneration,
                        currentGeneration = generation,
                        cameraWanted = setupThermalMode != RuntimeThermalMode.PAUSED,
                        closed = closed,
                        handedOff = handedOff,
                    )
                ) return@addListener
                try {
                    val cameraProvider = future.get()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        useCaseGroup,
                    )
                    provider = cameraProvider
                    providerFuture = null
                } catch (error: Throwable) {
                    releaseUseCases()
                    mutableStatus.value = SetupCameraStatus.Error(
                        context.getString(R.string.camera_rear_open_failed),
                    )
                }
            },
            mainExecutor,
        )
    }

    private fun releaseUseCases() {
        ++generation
        analysis?.clearAnalyzer()
        val ownedUseCases = listOfNotNull(preview, analysis)
        if (ownedUseCases.isNotEmpty()) provider?.unbind(*ownedUseCases.toTypedArray())
        preview?.setSurfaceProvider(null)
        preview = null
        analysis = null
        providerFuture?.cancel(true)
        providerFuture = null
        provider = null
        viewPortSpec = null
    }

    private fun processSetupCheckFrame(imageProxy: ImageProxy) {
        processFieldValidationFrame(imageProxy)
        processReadingPreviewFrame(imageProxy)
    }

    private fun processFieldValidationFrame(imageProxy: ImageProxy) {
        val active = activeFieldValidation ?: return
        val currentStatus = mutableFieldValidationStatus.value
        if (active.commandGeneration != fieldValidationGeneration.get() ||
            (currentStatus !is SetupFieldValidationStatus.Checking &&
                currentStatus !is SetupFieldValidationStatus.NotFound &&
                currentStatus !is SetupFieldValidationStatus.Passed)
        ) return
        try {
            val frameStartedNanos = System.nanoTime()
            val sourceSequence = ++fieldValidationSourceSequence
            val cameraFrame = imageProxy.copyToSourceFrame(
                sourceSequence = sourceSequence,
                monotonicTimeMillis = imageProxy.imageInfo.timestamp / NANOS_PER_MILLISECOND,
                capturedAtEpochMillis = System.currentTimeMillis(),
            )
            val copiedNanos = System.nanoTime()
            val normalized = SetupFieldValidationFrameNormalizer.normalize(
                cameraFrame = cameraFrame,
                roi = active.identity.roi,
            )
            val normalizedNanos = System.nanoTime()
            val coordinatorState = active.coordinator.accept(active.identity, normalized)
            val detections = (active.coordinator.latestObservation as? Observation.Detections)
                ?.items
                ?.filter { it.label == active.identity.targetId }
                .orEmpty()
            val completedNanos = System.nanoTime()
            if (active.commandGeneration == fieldValidationGeneration.get()) {
                // Publish this frame's live feedback before the app-private diagnostic fsync.
                publishFieldValidationStatus(
                    active.commandGeneration,
                    coordinatorState.toSetupStatus(),
                )
                publishFieldDetections(active.commandGeneration, detections)
            }
            active.recordDiagnostics(
                context = context,
                state = coordinatorState,
                sourceSequence = sourceSequence,
                capturedMonotonicMillis = cameraFrame.monotonicTimeMillis,
                copyMillis = (copiedNanos - frameStartedNanos).nanosToMillis(),
                normalizeMillis = (normalizedNanos - copiedNanos).nanosToMillis(),
                runtimeMillis = (completedNanos - normalizedNanos).nanosToMillis(),
            )
        } catch (_: Exception) {
            active.coordinator.close()
            if (active.commandGeneration == fieldValidationGeneration.get()) {
                publishFieldDetections(active.commandGeneration, emptyList())
                publishFieldValidationStatus(
                    active.commandGeneration,
                    active.coordinator.state.toSetupStatus(),
                )
            }
        }
    }

    private fun processReadingPreviewFrame(imageProxy: ImageProxy) {
        val active = activeReadingPreview ?: return
        if (active.commandGeneration != readingPreviewGeneration.get() ||
            mutableReadingPreviewStatus.value !is ReadingPreviewStatus.Checking ||
            readingPreviewInferencePaused(active.coordinator.state)
        ) return
        try {
            val sourceSequence = ++fieldValidationSourceSequence
            val cameraFrame = imageProxy.copyToSourceFrame(
                sourceSequence = sourceSequence,
                monotonicTimeMillis = imageProxy.imageInfo.timestamp / NANOS_PER_MILLISECOND,
                capturedAtEpochMillis = System.currentTimeMillis(),
            )
            val normalized = SetupFieldValidationFrameNormalizer.normalize(
                cameraFrame = cameraFrame,
                roi = active.identity.roi,
            )
            val state = active.coordinator.accept(active.identity, normalized)
            recordReadingPreviewDiagnostic(active, state)
            if (active.commandGeneration == readingPreviewGeneration.get()) {
                publishReadingPreviewStatus(active.commandGeneration, state)
            }
        } catch (_: Exception) {
            active.coordinator.close()
            if (active.commandGeneration == readingPreviewGeneration.get()) {
                publishReadingPreviewStatus(
                    active.commandGeneration,
                    active.coordinator.state,
                )
            }
        }
    }

    /**
     * Camera and model work stays on the single analyzer executor, while every UI-observed state
     * transition is committed on the main thread. Rechecking the generation at delivery time also
     * prevents a queued result from reviving a cleared or replaced setup check.
     */
    private fun publishFieldValidationStatus(
        commandGeneration: Long,
        status: SetupFieldValidationStatus,
    ) {
        mainExecutor.execute {
            if (!closed && !handedOff && commandGeneration == fieldValidationGeneration.get()) {
                mutableFieldValidationStatus.value = status
            }
        }
    }

    private fun publishFieldDetections(
        commandGeneration: Long,
        detections: List<Detection>,
    ) {
        mainExecutor.execute {
            if (!closed && !handedOff && commandGeneration == fieldValidationGeneration.get()) {
                mutableFieldDetections.value = detections
            }
        }
    }

    private fun publishReadingPreviewStatus(
        commandGeneration: Long,
        status: ReadingPreviewStatus,
    ) {
        mainExecutor.execute {
            if (!closed && !handedOff && commandGeneration == readingPreviewGeneration.get()) {
                mutableReadingPreviewStatus.value = status
            }
        }
    }

    /**
     * Keep setup failures diagnosable without recording recognized text, images, or prompts.
     * Only the bounded runtime reason/code is retained, and each code is emitted once per preview
     * session so a persistently unreadable frame cannot fill the diagnostic file.
     */
    private fun recordReadingPreviewDiagnostic(
        active: ActiveReadingPreview,
        state: ReadingPreviewStatus,
    ) {
        val unavailable = (state as? ReadingPreviewStatus.Checking)?.live
            as? ReadingPreviewLiveState.Unavailable
        if (unavailable == null) {
            active.lastUnavailableDiagnosticCode = null
            return
        }
        val diagnosticCode = unavailable.diagnosticCode ?: "unspecified"
        if (active.lastUnavailableDiagnosticCode == diagnosticCode) return
        active.lastUnavailableDiagnosticCode = diagnosticCode
        RuntimeDiagnostics.record(
            context,
            "reading_preview_unavailable",
            mapOf(
                "reason" to unavailable.reason.name.lowercase(),
                "diagnostic" to diagnosticCode,
            ),
        )
    }

    private fun ImageProxy.copyToSourceFrame(
        sourceSequence: Long,
        monotonicTimeMillis: Long,
        capturedAtEpochMillis: Long,
    ) = if (format != ImageFormat.YUV_420_888 || planes.size != YUV_PLANE_COUNT) {
        throw FrameNormalizationException(
            FrameNormalizationFailure.UNSUPPORTED_PIXEL_FORMAT,
            "CameraX frame must have the YUV_420_888 three-plane layout",
        )
    } else {
        CameraYuv420FrameMapper.copyToSourceFrame(
            sourceSequence = sourceSequence,
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

    override fun close() {
        if (closed) return
        closed = true
        previewRestartRequests.clear()
        releaseUseCases()
        attachedView?.removeOnLayoutChangeListener(layoutChangeListener)
        attachedView = null
        displayManager.unregisterDisplayListener(displayListener)
        orientationListener.disable()
        runCatching { thermalStatusRegistration?.close() }
        thermalStatusRegistration = null
        fieldValidationGeneration.incrementAndGet()
        readingPreviewGeneration.incrementAndGet()
        mutableFieldDetections.value = emptyList()
        try {
            analyzerExecutor.execute {
                activeFieldValidation?.coordinator?.close()
                activeFieldValidation = null
                activeReadingPreview?.coordinator?.close()
                activeReadingPreview = null
            }
        } catch (_: RejectedExecutionException) {
            activeFieldValidation?.coordinator?.close()
            activeFieldValidation = null
            activeReadingPreview?.coordinator?.close()
            activeReadingPreview = null
        }
        analyzerExecutor.shutdown()
    }

    private fun PreviewView.currentViewPortSpec(): ViewPortSpec? {
        val rotation = physicalTargetRotation ?: run {
            if (physicalOrientationDetectionAvailable &&
                SystemClock.elapsedRealtime() - orientationWaitStartedAtMillis <
                ORIENTATION_INITIAL_WAIT_MILLIS
            ) {
                return null
            }
            display?.rotation ?: return null
        }
        if (width <= 0 || height <= 0) return null
        return ViewPortSpec(width, height, rotation)
    }

    private data class ViewPortSpec(
        val width: Int,
        val height: Int,
        val targetRotation: Int,
    )

    private data class ActiveFieldValidation(
        val commandGeneration: Long,
        val identity: SimilarityFieldValidationIdentity,
        val coordinator: SimilarityFieldValidationCoordinator,
        val runtimeOpenMillis: Long,
        var firstObservationRecorded: Boolean = false,
        var firstPresentRecorded: Boolean = false,
        var stableAbsentRecorded: Boolean = false,
        var stablePresentRecorded: Boolean = false,
        var failureRecorded: Boolean = false,
    ) {
        fun recordDiagnostics(
            context: Context,
            state: SimilarityFieldValidationUiState,
            sourceSequence: Long,
            capturedMonotonicMillis: Long,
            copyMillis: Long,
            normalizeMillis: Long,
            runtimeMillis: Long,
        ) {
            val firstObservation = !firstObservationRecorded && state.summary.processedFrames > 0
            val firstPresent = !firstPresentRecorded &&
                state.summary.latestSignal == SimilarityFieldValidationSignal.PRESENT
            val stableAbsent = state is SimilarityFieldValidationUiState.NotFound &&
                !stableAbsentRecorded
            val stablePresent = state is SimilarityFieldValidationUiState.Passed &&
                !stablePresentRecorded
            val failed = state is SimilarityFieldValidationUiState.Failed && !failureRecorded
            if (!firstObservation && !firstPresent && !stableAbsent && !stablePresent && !failed) return

            val milestones = mutableListOf<String>()
            if (firstObservation) {
                firstObservationRecorded = true
                milestones += "first_observation"
            }
            if (firstPresent) {
                firstPresentRecorded = true
                milestones += "first_present"
            }
            if (stableAbsent) {
                stableAbsentRecorded = true
                milestones += "stable_absent"
            }
            if (stablePresent) {
                stablePresentRecorded = true
                milestones += "stable_present"
            }
            if (failed) {
                failureRecorded = true
                milestones += "failed"
            }
            val event = when {
                firstObservation -> "visual_setup_first_observation"
                firstPresent -> "visual_setup_first_present"
                stableAbsent -> "visual_setup_stable_absent"
                stablePresent -> "visual_setup_stable_present"
                else -> "visual_setup_failed"
            }
            RuntimeDiagnostics.record(
                context,
                event,
                timingDetails(
                    state,
                    sourceSequence,
                    capturedMonotonicMillis,
                    copyMillis,
                    normalizeMillis,
                    runtimeMillis,
                    milestones,
                ),
            )
        }

        private fun timingDetails(
            state: SimilarityFieldValidationUiState,
            sourceSequence: Long,
            capturedMonotonicMillis: Long,
            copyMillis: Long,
            normalizeMillis: Long,
            runtimeMillis: Long,
            milestones: List<String>,
        ) = mapOf(
            "capture_monotonic_ms" to capturedMonotonicMillis.toString(),
            "copy_ms" to copyMillis.toString(),
            "normalize_ms" to normalizeMillis.toString(),
            "runtime_ms" to runtimeMillis.toString(),
            "runtime_open_ms" to runtimeOpenMillis.toString(),
            "latest_signal" to (state.summary.latestSignal?.name?.lowercase() ?: "unavailable"),
            "milestones" to milestones.joinToString(","),
            "frame_summary" to buildString {
                append("sequence=").append(sourceSequence)
                append(";present=").append(state.summary.presentFrames)
                append(";absent=").append(state.summary.absentFrames)
                append(";unavailable=").append(state.summary.unavailableFrames)
            },
        )
    }

    private data class ActiveReadingPreview(
        val commandGeneration: Long,
        val identity: ReadingPreviewIdentity,
        val coordinator: ReadingPreviewCoordinator,
        var lastUnavailableDiagnosticCode: String? = null,
    )

    private companion object {
        const val YUV_PLANE_COUNT = 3
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val ORIENTATION_RETRY_MILLIS = 50L
        const val ORIENTATION_INITIAL_WAIT_MILLIS = 500L
        const val CAMERA_HANDOFF_READY_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun shouldAcceptSetupCameraProviderCallback(
    callbackGeneration: Long,
    currentGeneration: Long,
    cameraWanted: Boolean,
    closed: Boolean,
    handedOff: Boolean,
): Boolean = callbackGeneration == currentGeneration && cameraWanted && !closed && !handedOff

internal fun surfaceRotationForPhysicalOrientation(orientationDegrees: Int): Int? = when {
    orientationDegrees == OrientationEventListener.ORIENTATION_UNKNOWN -> null
    orientationDegrees !in 0..359 -> null
    orientationDegrees < 45 || orientationDegrees >= 315 -> Surface.ROTATION_0
    orientationDegrees < 135 -> Surface.ROTATION_270
    orientationDegrees < 225 -> Surface.ROTATION_180
    else -> Surface.ROTATION_90
}

private fun Long.nanosToMillis(): Long = (this / 1_000_000L).coerceAtLeast(0L)
