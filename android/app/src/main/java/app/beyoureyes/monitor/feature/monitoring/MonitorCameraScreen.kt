package app.beyoureyes.monitor.feature.monitoring

import android.Manifest
import androidx.core.net.toUri
import android.os.Build
import android.content.pm.PackageManager
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.beyoureyes.core.data.MonitorRepositoryState
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.Detection
import app.beyoureyes.core.domain.LatestReading
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.ReadingTargetConfig
import app.beyoureyes.core.domain.ReadingStatus
import app.beyoureyes.monitor.FULL_FRAME_MONITOR_REGION
import app.beyoureyes.monitor.MonitoringPhase
import app.beyoureyes.monitor.MonitoringRuntimeState
import app.beyoureyes.monitor.MonitoringStartResult
import app.beyoureyes.monitor.MonitoringObservationSnapshot
import app.beyoureyes.monitor.MonitoringStatus
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.ReadingPreviewLiveState
import app.beyoureyes.monitor.ReadingPreviewStatus
import app.beyoureyes.monitor.SetupCameraSession
import app.beyoureyes.monitor.SetupCameraStatus
import app.beyoureyes.monitor.SetupFieldValidationStatus
import app.beyoureyes.monitor.SimilarityFieldValidationIdentity
import app.beyoureyes.monitor.SimilarityFieldValidationSignal
import app.beyoureyes.monitor.canRetryCheck
import app.beyoureyes.monitor.fieldValidationPassedFor
import app.beyoureyes.monitor.readingUnavailableHint
import app.beyoureyes.monitor.readingPreviewSuggestsManualRegion
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductMetric
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.ProductSwitch
import app.beyoureyes.monitor.design.ProductTone
import app.beyoureyes.monitor.design.ProductTopBar
import app.beyoureyes.monitor.design.StatusPill
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.localizedLabel
import app.beyoureyes.monitor.design.resolve
import app.beyoureyes.monitor.design.uiText
import app.beyoureyes.monitor.feature.history.productPresentation
import app.beyoureyes.monitor.feature.history.summary
import app.beyoureyes.monitor.feature.history.userVisibleRecordCount
import app.beyoureyes.monitor.feature.reading.ReadingConditionControls
import app.beyoureyes.monitor.feature.reading.ReadingConditionDraft
import app.beyoureyes.monitor.feature.reading.ReadingConditionMode
import app.beyoureyes.monitor.feature.reading.readingConditionDraft
import app.beyoureyes.monitor.feature.reading.readingConditionSubmission
import app.beyoureyes.monitor.feature.reading.TransientReadingSetupViewModel
import app.beyoureyes.monitor.summaryOrEmpty
import app.beyoureyes.monitor.service.monitoring.MonitoringCameraPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

internal object CameraTags {
    const val SCREEN = "monitor_camera"
    const val PERMISSION = "camera_permission"
    const val REFERENCE_CONFIRM = "reference_confirm"
    const val REFERENCE_REJECT = "reference_reject"
    const val REFERENCE_RECHECK = "reference_recheck"
    const val READING_CONFIRM = "reading_confirm"
    const val READING_CREATE_PENDING = "reading_create_pending"
    const val READING_TARGET_OVERLAY = "reading_target_overlay"
    const val READING_ROI_CLEAR = "reading_roi_clear"
    const val READING_RESCAN = "reading_rescan"
    const val READING_CORRECTION_TOGGLE = "reading_correction_toggle"
    const val READING_CORRECTION = "reading_correction"
    const val THRESHOLD = "reading_threshold"
    const val LOWER_THRESHOLD = "reading_lower_threshold"
    const val UPPER_THRESHOLD = "reading_upper_threshold"
    const val START = "monitor_start"
    const val RETRY = "monitor_retry"
    const val OPEN_ACCOUNT = "monitor_open_account"
    const val CAMERA_RETRY = "camera_retry"
    const val REFERENCE_RAIL = "reference_camera_rail"
    const val PRIVACY_BADGE = "camera_privacy_badge"
}

/** Shared handoff contract for reference-image and finite object-class setup. */
internal interface FieldSetupController {
    val state: StateFlow<MonitorCameraState>
    fun retry()
    fun setNotificationsEnabled(enabled: Boolean)
    suspend fun persist(): MonitorCameraState.Ready?
    fun leave(onLeft: () -> Unit)
}

internal object ActiveMonitoringTags {
    const val PREVIEW = "active_monitor_preview"
    const val TARGET_BOXES = "active_monitor_target_boxes"
    const val BLACK_TOGGLE = "active_monitor_black_toggle"
    const val BLACK_SCREEN = "active_monitor_black_screen"
    const val RESTORE_PREVIEW = "active_monitor_restore_preview"
    const val STOP = "active_monitor_stop"
    const val STOP_CONFIRM = "active_monitor_stop_confirm"
    const val HISTORY = "active_monitor_history"
    const val READING_HELP = "active_reading_help"
}

internal data class ActiveMonitoringSnapshot(
    val latestReading: LatestReading?,
    val latestObservation: Observation?,
    val observationSnapshot: MonitoringObservationSnapshot?,
    val eventCount: Int,
    val lastEventText: UiText?,
)

internal fun activeMonitoringSnapshot(
    repositoryState: MonitorRepositoryState,
    monitorId: String,
    expectedRevision: Long?,
    monitoringStatus: MonitoringStatus,
    latestObservationSnapshot: MonitoringObservationSnapshot?,
): ActiveMonitoringSnapshot {
    val events = repositoryState.events.filter { event ->
        event.monitorId == monitorId && !event.isRemote
    }
    val ownsActiveRuntime = monitoringStatus.monitorId == monitorId &&
        monitoringStatus.phase in setOf(MonitoringPhase.STARTING, MonitoringPhase.RUNNING)
    val currentObservation = latestObservationSnapshot?.takeIf {
        ownsActiveRuntime && it.monitorId == monitorId && it.monitorRevision == expectedRevision
    }
    val currentReading = repositoryState.latestReadings[monitorId]?.takeIf { reading ->
        currentObservation != null &&
            expectedRevision != null &&
            reading.monitorId == monitorId &&
            reading.monitorRevision == expectedRevision
    }
    return ActiveMonitoringSnapshot(
        latestReading = currentReading,
        latestObservation = currentObservation?.observation,
        observationSnapshot = currentObservation,
        eventCount = userVisibleRecordCount(events),
        lastEventText = events.firstOrNull()?.productPresentation()?.summary(),
    )
}

@Composable
internal fun MonitorCameraScreen(
    viewModel: MonitorCameraViewModel,
    repositoryState: MonitorRepositoryState,
    monitoringStatus: MonitoringStatus,
    latestObservationSnapshot: MonitoringObservationSnapshot?,
    cameraPermissionGranted: Boolean,
    cameraPermissionDenied: Boolean,
    onRequestCameraPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartMonitoring: (app.beyoureyes.monitor.RuntimeCameraConfig) -> MonitoringStartResult,
    onCheckProductAccess: () -> MonitoringStartResult.Rejected?,
    onStopMonitoring: () -> Unit,
    onReplaceReference: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        is MonitorCameraState.Loading -> LoadingScreen(current, onBack)
        is MonitorCameraState.Error -> ErrorScreen(
            state = current,
            retry = viewModel::retry,
            replaceReference = { viewModel.replaceReferenceImages(onReplaceReference) },
            onOpenAccount = onOpenAccount,
            onBack = onBack,
        )
        is MonitorCameraState.Ready -> {
            if (!cameraPermissionGranted) {
                PermissionScreen(
                    cameraPermissionDenied,
                    onRequestCameraPermission,
                    onOpenAppSettings,
                    onBack,
                )
            } else {
                ReadyCameraScreen(
                    state = current,
                    repositoryState = repositoryState,
                    monitoringStatus = monitoringStatus,
                    latestObservationSnapshot = latestObservationSnapshot,
                    onStartMonitoring = onStartMonitoring,
                    onCheckProductAccess = onCheckProductAccess,
                    onStopMonitoring = onStopMonitoring,
                    onOpenAccount = onOpenAccount,
                    onBack = onBack,
                                    onNotificationsEnabled = viewModel::setNotificationsEnabled,
                    onConfigureReading = viewModel::configureReadingAndRefresh,
                    onMonitoringStarted = {},
                )
            }
        }
    }
}

@Composable
internal fun TransientReadingCameraScreen(
    viewModel: TransientReadingSetupViewModel,
    initialCondition: ReadingConditionDraft? = null,
    repositoryState: MonitorRepositoryState,
    monitoringStatus: MonitoringStatus,
    latestObservationSnapshot: MonitoringObservationSnapshot?,
    cameraPermissionGranted: Boolean,
    cameraPermissionDenied: Boolean,
    onRequestCameraPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartMonitoring: (app.beyoureyes.monitor.RuntimeCameraConfig) -> MonitoringStartResult,
    onCheckProductAccess: () -> MonitoringStartResult.Rejected?,
    onStopMonitoring: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
    onMonitoringStarted: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        is MonitorCameraState.Loading -> LoadingScreen(current, onBack)
        is MonitorCameraState.Error -> ErrorScreen(
            state = current,
            retry = viewModel::retry,
            replaceReference = {},
            onOpenAccount = onOpenAccount,
            onBack = onBack,
        )
        is MonitorCameraState.Ready -> {
            if (!cameraPermissionGranted) {
                PermissionScreen(
                    cameraPermissionDenied,
                    onRequestCameraPermission,
                    onOpenAppSettings,
                    onBack,
                )
            } else {
                ReadyCameraScreen(
                    state = current,
                    initialReadingCondition = initialCondition,
                    repositoryState = repositoryState,
                    monitoringStatus = monitoringStatus,
                    latestObservationSnapshot = latestObservationSnapshot,
                    onStartMonitoring = onStartMonitoring,
                    onCheckProductAccess = onCheckProductAccess,
                    onStopMonitoring = onStopMonitoring,
                    onOpenAccount = onOpenAccount,
                    onBack = onBack,
                    onNotificationsEnabled = viewModel::setNotificationsEnabled,
                    onConfigureReading = viewModel::configureReadingAndPersist,
                    onPersistPendingReading = viewModel::persistPendingBaseline,
                    onMonitoringStarted = onMonitoringStarted,
                )
            }
        }
    }
}

@Composable
internal fun ReferenceCameraScreen(
    viewModel: FieldSetupController,
    repositoryState: MonitorRepositoryState,
    monitoringStatus: MonitoringStatus,
    latestObservationSnapshot: MonitoringObservationSnapshot?,
    cameraPermissionGranted: Boolean,
    cameraPermissionDenied: Boolean,
    onRequestCameraPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartMonitoring: (app.beyoureyes.monitor.RuntimeCameraConfig) -> MonitoringStartResult,
    onCheckProductAccess: () -> MonitoringStartResult.Rejected?,
    onStopMonitoring: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
    onReferencePersisted: () -> Unit,
    onMonitoringStarted: () -> Unit,
    onNotificationPreferenceChanged: (Boolean) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        is MonitorCameraState.Loading -> LoadingScreen(current, onBack)
        is MonitorCameraState.Error -> ErrorScreen(
            state = current,
            retry = viewModel::retry,
            replaceReference = {},
            onOpenAccount = onOpenAccount,
            onBack = onBack,
        )
        is MonitorCameraState.Ready -> {
            if (!cameraPermissionGranted) {
                PermissionScreen(
                    cameraPermissionDenied,
                    onRequestCameraPermission,
                    onOpenAppSettings,
                    onBack,
                )
            } else {
                ReadyCameraScreen(
                    state = current,
                    repositoryState = repositoryState,
                    monitoringStatus = monitoringStatus,
                    latestObservationSnapshot = latestObservationSnapshot,
                    onStartMonitoring = onStartMonitoring,
                    onCheckProductAccess = onCheckProductAccess,
                    onStopMonitoring = onStopMonitoring,
                    onOpenAccount = onOpenAccount,
                    onBack = onBack,
                    onNotificationsEnabled = { enabled ->
                        viewModel.setNotificationsEnabled(enabled)
                        onNotificationPreferenceChanged(enabled)
                    },
                    onConfigureReading = { _, _ -> null },
                    onMonitoringStarted = onMonitoringStarted,
                    onPersistReference = viewModel::persist,
                    onReferencePersisted = onReferencePersisted,
                )
            }
        }
    }
}

@Composable
private fun ReadyCameraScreen(
    state: MonitorCameraState.Ready,
    initialReadingCondition: ReadingConditionDraft? = null,
    repositoryState: MonitorRepositoryState,
    monitoringStatus: MonitoringStatus,
    latestObservationSnapshot: MonitoringObservationSnapshot?,
    onStartMonitoring: (app.beyoureyes.monitor.RuntimeCameraConfig) -> MonitoringStartResult,
    onCheckProductAccess: () -> MonitoringStartResult.Rejected?,
    onStopMonitoring: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
    onNotificationsEnabled: (Boolean) -> Unit,
    onConfigureReading: suspend (
        MonitorRule.ReadingThreshold,
        ReadingTargetConfig,
    ) -> MonitorCameraState.Ready?,
    onMonitoringStarted: () -> Unit,
    onPersistReference: (suspend () -> MonitorCameraState.Ready?)? = null,
    onReferencePersisted: () -> Unit = {},
    onPersistPendingReading: (suspend (ReadingTargetConfig) -> MonitorCameraState.Ready?)? = null,
) {
    val context = LocalContext.current
    val appLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val monitorSaveFailed = stringResource(R.string.error_monitor_save_failed)
    val invalidRecordCondition = stringResource(R.string.error_invalid_record_condition)
    val readingNotStable = stringResource(R.string.error_reading_not_stable)
    val setupChanged = stringResource(R.string.error_setup_changed_confirm_reading)
    val conditionSaveFailed = stringResource(R.string.error_condition_save_failed)
    val lifecycleOwner = LocalLifecycleOwner.current
    val monitor = state.persisted.monitor
    val pointer = state.runtime.config.packagePointer
    val region = if (monitor.kind == MonitorKind.REFERENCE) {
        FULL_FRAME_MONITOR_REGION
    } else {
        FULL_FRAME_MONITOR_REGION
    }
    val session = remember(monitor.id, pointer) {
        SetupCameraSession(context, lifecycleOwner, state.runtime.config)
    }
    DisposableEffect(session) { onDispose(session::close) }
    val cameraStatus by session.status.collectAsState()
    val fieldStatus by session.fieldValidationStatus.collectAsState()
    val fieldDetections by session.fieldDetections.collectAsState()
    val readingStatus by session.readingPreviewStatus.collectAsState()
    val readingPanelScroll = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onNotificationsEnabled(granted)
    }
    var initialNotificationPermissionRequested by rememberSaveable(monitor.id) {
        mutableStateOf(false)
    }
    LaunchedEffect(state.notificationsEnabled, initialNotificationPermissionRequested) {
        if (
            state.notificationsEnabled &&
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED &&
            !initialNotificationPermissionRequested
        ) {
            initialNotificationPermissionRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var handedOff by rememberSaveable(monitor.id) { mutableStateOf(false) }
    var startRequested by remember(monitor.id) { mutableStateOf(false) }
    var referenceMatchRejected by rememberSaveable(monitor.id, monitor.revision) {
        mutableStateOf(false)
    }
    var startingReading by remember(monitor.id) { mutableStateOf(false) }
    var startingPendingReading by remember(monitor.id) { mutableStateOf(false) }
    val configuredRule = monitor.rule as? MonitorRule.ReadingThreshold
    val persistedReadingFormat = (monitor.target as? MonitorTarget.NumericReading)?.confirmedFormat
    val persistedDraft = remember(monitor.id, monitor.revision, initialReadingCondition) {
        initialReadingCondition
            ?: configuredRule?.let {
                readingConditionDraft(
                    it,
                    confirmedFormat = persistedReadingFormat,
                    locale = appLocale,
                )
            }
            ?: ReadingConditionDraft(ReadingConditionMode.ABOVE)
    }
    var threshold by rememberSaveable(monitor.id) { mutableStateOf(persistedDraft.threshold) }
    var conditionMode by rememberSaveable(monitor.id) { mutableStateOf(persistedDraft.mode) }
    var lowerThreshold by rememberSaveable(monitor.id) {
        mutableStateOf(persistedDraft.lowerThreshold)
    }
    var upperThreshold by rememberSaveable(monitor.id) {
        mutableStateOf(persistedDraft.upperThreshold)
    }
    var readingDurationSeconds by rememberSaveable(monitor.id) {
        mutableIntStateOf(persistedDraft.durationSeconds)
    }
    var startError by remember { mutableStateOf<String?>(null) }
    var startCanOpenAccount by remember { mutableStateOf(false) }
    var startWithBlackScreen by rememberSaveable(monitor.id) { mutableStateOf(false) }
    var manualScanRegion by remember(monitor.id, monitor.revision) {
        mutableStateOf((monitor.target as? MonitorTarget.NumericReading)?.manualRoi)
    }
    var readingRoiTutorialDismissed by rememberSaveable(monitor.id, monitor.revision) {
        mutableStateOf(manualScanRegion != null)
    }
    val fieldSpec = state.runtime.fieldValidationSpec
    val readingSpec = state.runtime.readingPreviewSpec
    val currentFieldValidationIdentity = fieldSpec?.identity(region)
    fun productAccessRejected(): Boolean {
        val rejection = onCheckProductAccess() ?: return false
        startError = rejection.message
        startCanOpenAccount = rejection.canOpenAccount
        return true
    }
    // 待确认基准：读不到稳定值时也允许先创建并开始监控，首个稳定值稍后再确认。
    val startPendingReading: () -> Unit = startPending@{
        val persistPending = onPersistPendingReading ?: return@startPending
        if (startingPendingReading || startingReading || handedOff || startRequested) {
            return@startPending
        }
        if (productAccessRejected()) return@startPending
        startError = null
        startCanOpenAccount = false
        startingPendingReading = true
        coroutineScope.launch {
            try {
                val readyForStart = persistPending(
                    ReadingTargetConfig(manualRoi = manualScanRegion, confirmedFormat = null),
                ) ?: return@launch
                if (readyForStart.persisted.monitor.id != monitor.id ||
                    readyForStart.runtime.config.packagePointer != pointer
                ) {
                    startError = monitorSaveFailed
                    startCanOpenAccount = false
                    return@launch
                }
                val result = session.releaseForMonitoring(
                    taskId = readyForStart.persisted.monitor.id,
                    taskRevision = readyForStart.persisted.monitor.revision,
                    roi = region,
                    resolvedSamplingOverride = readyForStart.runtime.config,
                    manualReadingScanRegion = manualScanRegion,
                    startWithBlackScreen = startWithBlackScreen,
                    onReleased = onStartMonitoring,
                )
                handedOff = result is MonitoringStartResult.Accepted
                if (handedOff) onMonitoringStarted()
                if (result is MonitoringStartResult.Rejected) {
                    startError = result.message
                    startCanOpenAccount = result.canOpenAccount
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                startError = monitorSaveFailed
                startCanOpenAccount = false
            } finally {
                if (!handedOff) startingPendingReading = false
            }
        }
    }
    // 已创建的待确认监控再次进入相机页：监控与采样配置都已持久化，无需再次保存，直接放行相机开始
    // 监控。新画的手动框只作为本次会话的运行参数；确认基准时会连同规则一起持久化。
    val startPersistedPendingReading: () -> Unit = startPersistedPending@{
        if (onPersistPendingReading != null || configuredRule?.configured != false) {
            return@startPersistedPending
        }
        if (startingPendingReading || startingReading || handedOff || startRequested) {
            return@startPersistedPending
        }
        if (productAccessRejected()) return@startPersistedPending
        startError = null
        startCanOpenAccount = false
        startingPendingReading = true
        coroutineScope.launch {
            val result = session.releaseForMonitoring(
                taskId = monitor.id,
                taskRevision = monitor.revision,
                roi = region,
                resolvedSamplingOverride = state.runtime.config,
                manualReadingScanRegion = manualScanRegion,
                startWithBlackScreen = startWithBlackScreen,
                onReleased = onStartMonitoring,
            )
            handedOff = result is MonitoringStartResult.Accepted
            if (handedOff) onMonitoringStarted()
            if (result is MonitoringStartResult.Rejected) {
                startError = result.message
                startCanOpenAccount = result.canOpenAccount
            }
            if (!handedOff) startingPendingReading = false
        }
    }
    LaunchedEffect(
        session,
        pointer,
        monitor.kind,
        currentFieldValidationIdentity,
        readingSpec?.taskId,
        readingSpec?.taskRevision,
        readingSpec?.packagePointer,
    ) {
        when (monitor.kind) {
            MonitorKind.REFERENCE,
            MonitorKind.OBJECT_DETECTION,
            -> fieldSpec?.let { session.startFieldValidation(it, region) }
            MonitorKind.READING -> if (readingStatus is ReadingPreviewStatus.Idle) {
                session.startReadingPreview(checkNotNull(readingSpec), region, manualScanRegion)
            }
        }
    }

    // 测试识别是可选工具：用户可以在任何识别状态下直接保存并开始监控。
    LaunchedEffect(startRequested, handedOff, monitor.revision) {
        if (!startRequested || handedOff ||
            monitor.kind !in setOf(MonitorKind.REFERENCE, MonitorKind.OBJECT_DETECTION)
        ) {
            return@LaunchedEffect
        }
        if (productAccessRejected()) {
            startRequested = false
            return@LaunchedEffect
        }
        try {
            val readyForStart = if (onPersistReference != null) onPersistReference() else state
            if (readyForStart == null ||
                readyForStart.persisted.monitor.id != monitor.id ||
                readyForStart.persisted.monitor.revision != monitor.revision ||
                readyForStart.runtime.config.packagePointer != pointer
            ) {
                startError = monitorSaveFailed
                startCanOpenAccount = false
            } else {
                if (onPersistReference != null) onReferencePersisted()
                val result = session.releaseForMonitoring(
                    taskId = readyForStart.persisted.monitor.id,
                    taskRevision = readyForStart.persisted.monitor.revision,
                    roi = region,
                    resolvedSamplingOverride = readyForStart.runtime.config,
                    startWithBlackScreen = startWithBlackScreen,
                    onReleased = onStartMonitoring,
                )
                handedOff = result is MonitoringStartResult.Accepted
                if (handedOff) onMonitoringStarted()
                if (result is MonitoringStartResult.Rejected) {
                    startError = result.message
                    startCanOpenAccount = result.canOpenAccount
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            startError = monitorSaveFailed
            startCanOpenAccount = false
        } finally {
            startRequested = false
        }
    }

    val cameraError = cameraStatus as? SetupCameraStatus.Error
    if (cameraError != null) {
        CameraFailureScreen(cameraError.message, session::retryCamera, onBack)
        return
    }

    if (monitoringStatus.monitorId == monitor.id &&
        monitoringStatus.phase in setOf(MonitoringPhase.STARTING, MonitoringPhase.RUNNING)
    ) {
        val active = activeMonitoringSnapshot(
            repositoryState = repositoryState,
            monitorId = monitor.id,
            expectedRevision = monitor.revision,
            monitoringStatus = monitoringStatus,
            latestObservationSnapshot = latestObservationSnapshot,
        )
        ActiveMonitoringScreen(
            name = monitor.name,
            kind = monitor.kind,
            rule = monitor.rule,
            targetLabel = (monitor.target as? MonitorTarget.ObjectClass)?.localizedLabel(),
            targetId = (monitor.target as? MonitorTarget.ObjectClass)?.targetId,
            confirmedReadingFormat = persistedReadingFormat,
            latestReading = active.latestReading,
            latestObservation = active.latestObservation,
            observationSnapshot = active.observationSnapshot,
            eventCount = active.eventCount,
            lastEventText = active.lastEventText,
            status = monitoringStatus,
            onDetails = null,
            onStop = {
                // Pop the transient setup route before STOPPED is published. Otherwise the
                // same entry recomposes as the stale condition form while the service shuts down.
                onBack()
                onStopMonitoring()
            },
            onBack = onBack,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag(CameraTags.SCREEN)) {
        val keyboardHeight = with(LocalDensity.current) {
            WindowInsets.ime.getBottom(this).toDp()
        }.coerceAtMost(maxHeight)
        // Keep the camera's viewport fixed while the form covers more of it during typing.
        val readingPanelHeight = maxHeight * (1f - CAMERA_VIEWPORT_FRACTION) +
            keyboardHeight * CAMERA_VIEWPORT_FRACTION
        Box(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .fillMaxHeight(CAMERA_VIEWPORT_FRACTION),
        ) {
            AndroidView(
                factory = {
                    PreviewView(it).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // Compose changes the controls while observations arrive. TextureView avoids
                        // SurfaceView/Compose measure-layout re-entry on API 36 during that update.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                },
                update = session::attach,
                modifier = Modifier.fillMaxSize(),
            )
            if (monitor.kind == MonitorKind.READING) {
                val previewValue = readingStatus.currentReadingPreviewValue()
                ReadingTargetOverlay(
                    automaticBox = previewValue?.anchorBox,
                    manualRegion = manualScanRegion,
                    editable = !handedOff && !startingReading,
                    onInteractionStarted = { readingRoiTutorialDismissed = true },
                    onManualRegionChanged = { region ->
                        manualScanRegion = region
                        session.updateManualReadingScanRegion(
                            readingStatus.expectedIdentity,
                            region,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ReadingTargetInstruction(
                        text = if (manualScanRegion != null) {
                            stringResource(R.string.reading_roi_instruction_adjust)
                        } else if (readingPreviewSuggestsManualRegion(readingStatus)) {
                            stringResource(R.string.reading_roi_instruction_incomplete)
                        } else {
                            stringResource(R.string.reading_roi_instruction_default)
                        },
                        waitingForSelection = manualScanRegion == null &&
                            readingPreviewSuggestsManualRegion(readingStatus),
                    )
                    if (manualScanRegion != null && !handedOff && !startingReading) {
                        OutlinedButton(
                            onClick = {
                                manualScanRegion = null
                                session.updateManualReadingScanRegion(
                                    readingStatus.expectedIdentity,
                                    null,
                                )
                            },
                            modifier = Modifier.testTag(CameraTags.READING_ROI_CLEAR),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                ProductColors.Error.copy(alpha = 0.85f),
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = ProductColors.Error,
                                containerColor = ProductColors.ErrorSoft.copy(alpha = 0.92f),
                            ),
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                stringResource(R.string.reading_roi_clear),
                                modifier = Modifier.padding(start = 6.dp),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else if (!handedOff && !startingReading) {
                        OutlinedButton(
                            onClick = {
                                session.updateManualReadingScanRegion(
                                    readingStatus.expectedIdentity,
                                    null,
                                )
                            },
                            modifier = Modifier.testTag(CameraTags.READING_RESCAN),
                        ) {
                            Text(stringResource(R.string.reading_rescan))
                        }
                    }
                }
            } else {
                TargetDetectionOverlay(
                    detections = fieldDetections,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                color = Color(0xC9080C0F),
                contentColor = ProductColors.TextPrimary,
            ) {
                ProductTopBar(
                    title = when (monitor.kind) {
                        MonitorKind.REFERENCE -> stringResource(R.string.camera_title_field_check)
                        MonitorKind.OBJECT_DETECTION -> stringResource(R.string.object_creation_title)
                        MonitorKind.READING -> stringResource(R.string.camera_title_live_reading)
                    },
                    onBack = onBack,
                    modifier = Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                    trailing = {
                        if (cameraStatus !is SetupCameraStatus.Ready) {
                            StatusPill(stringResource(R.string.status_opening), ProductTone.WAITING)
                        }
                    },
                )
            }
            if (monitor.kind == MonitorKind.REFERENCE && state.referenceMaterials.isNotEmpty()) {
                ReferenceCameraRail(
                    materials = state.referenceMaterials,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()
                        .padding(start = 16.dp, top = 72.dp),
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(
                        end = 16.dp,
                        top = if (monitor.kind == MonitorKind.REFERENCE) 128.dp else 72.dp,
                    )
                    .testTag(CameraTags.PRIVACY_BADGE),
                color = ProductColors.Background.copy(alpha = 0.86f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(R.string.camera_privacy_badge),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(
                if (monitor.kind == MonitorKind.READING) {
                    Modifier.height(readingPanelHeight)
                } else {
                    Modifier
                },
            ),
            color = ProductColors.Surface.copy(alpha = 0.97f),
            contentColor = ProductColors.TextPrimary,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                modifier = Modifier.navigationBarsPadding().imePadding().then(
                    if (monitor.kind == MonitorKind.READING) {
                        Modifier.verticalScroll(readingPanelScroll)
                    } else {
                        Modifier
                    },
                ).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.model_files_ready),
                    color = ProductColors.Cyan,
                    style = MaterialTheme.typography.labelMedium,
                )
                when (monitor.kind) {
                    MonitorKind.READING -> ReadingConditionControls(
                        status = readingStatus,
                        mode = conditionMode,
                        threshold = threshold,
                        lowerThreshold = lowerThreshold,
                        upperThreshold = upperThreshold,
                        durationSeconds = readingDurationSeconds,
                        saving = state.savingCondition || startingReading || startingPendingReading,
                        error = state.conditionError?.resolve(),
                        notificationsEnabled = state.notificationsEnabled,
                        onCreatePending = when {
                            configuredRule?.configured == true ||
                                readingStatus is ReadingPreviewStatus.Confirmed -> null
                            onPersistPendingReading != null -> startPendingReading
                            configuredRule?.configured == false -> startPersistedPendingReading
                            else -> null
                        },
                        pendingActionLabelRes = if (onPersistPendingReading != null) {
                            R.string.reading_create_pending_action
                        } else {
                            R.string.action_start_monitoring
                        },
                        locale = appLocale,
                        onConfirm = { reading ->
                            session.confirmReadingPreview(reading)
                            val draft = initialReadingCondition
                                ?: readingConditionDraft(
                                    persistedRule = checkNotNull(configuredRule),
                                    confirmedBaseline = reading.value.text,
                                    locale = appLocale,
                                )
                            conditionMode = draft.mode
                            threshold = draft.threshold
                            lowerThreshold = draft.lowerThreshold
                            upperThreshold = draft.upperThreshold
                            readingDurationSeconds = draft.durationSeconds
                        },
                        onBeginFormatCorrection = session::beginReadingFormatCorrection,
                        onCancelFormatCorrection = {
                            session.cancelReadingFormatCorrection(readingStatus.expectedIdentity)
                        },
                        onModeChange = { conditionMode = it },
                        onThresholdChange = { threshold = it },
                        onLowerThresholdChange = { lowerThreshold = it },
                        onUpperThresholdChange = { upperThreshold = it },
                        onDurationSecondsChange = { readingDurationSeconds = it },
                        onStart = {
                            if (productAccessRejected()) return@ReadingConditionControls
                            val submission = readingConditionSubmission(
                                persistedRule = checkNotNull(configuredRule),
                                confirmedReadingFormat = (
                                    readingStatus as? ReadingPreviewStatus.Confirmed
                                )?.confirmedFormat ?: return@ReadingConditionControls,
                                draft = ReadingConditionDraft(
                                    mode = conditionMode,
                                    threshold = threshold,
                                    lowerThreshold = lowerThreshold,
                                    upperThreshold = upperThreshold,
                                    durationSeconds = readingDurationSeconds,
                                ),
                                locale = appLocale,
                            )
                            if (submission == null) {
                                startError = invalidRecordCondition
                                startCanOpenAccount = false
                            } else if (!startingReading && !handedOff) {
                                val confirmedBeforeSave = readingStatus
                                    as? ReadingPreviewStatus.Confirmed
                                if (confirmedBeforeSave?.live !is ReadingPreviewLiveState.Stable) {
                                    startError = readingNotStable
                                    startCanOpenAccount = false
                                    return@ReadingConditionControls
                                }
                                startError = null
                                startCanOpenAccount = false
                                startingReading = true
                                coroutineScope.launch {
                                    try {
                                        val readyForStart = if (
                                            submission.requiresPersistence ||
                                            (monitor.target as MonitorTarget.NumericReading).config !=
                                            ReadingTargetConfig(
                                                manualRoi = manualScanRegion,
                                                confirmedFormat = currentConfirmedFormat(readingStatus),
                                            )
                                        ) {
                                            onConfigureReading(
                                                submission.rule,
                                                ReadingTargetConfig(
                                                    manualRoi = manualScanRegion,
                                                    confirmedFormat = currentConfirmedFormat(readingStatus),
                                                ),
                                            )
                                        } else {
                                            state
                                        } ?: return@launch
                                        val current = session.readingPreviewStatus.value
                                            as? ReadingPreviewStatus.Confirmed
                                        if (current == null ||
                                            current.expectedIdentity.taskId !=
                                            readyForStart.persisted.monitor.id ||
                                            current.expectedIdentity !=
                                            confirmedBeforeSave.expectedIdentity ||
                                            current.expectedIdentity.packagePointer !=
                                            readyForStart.persisted.runtimePackagePointer
                                        ) {
                                            startError = setupChanged
                                            startCanOpenAccount = false
                                            return@launch
                                        }
                                        // Saving the condition advances the persisted revision, not
                                        // the observed frame. Keep the exact user-confirmed preview
                                        // identity while handing off the verified saved runtime.
                                        if (current.live !is ReadingPreviewLiveState.Stable) {
                                            startError = readingNotStable
                                            startCanOpenAccount = false
                                            return@launch
                                        }
                                        val result = session.releaseForMonitoring(
                                            taskId = readyForStart.persisted.monitor.id,
                                            taskRevision = readyForStart.persisted.monitor.revision,
                                            roi = region,
                                            readingPreviewRequired = true,
                                            currentReadingPreviewIdentity = current.expectedIdentity,
                                            resolvedSamplingOverride = readyForStart.runtime.config,
                                            manualReadingScanRegion = manualScanRegion,
                                            startWithBlackScreen = startWithBlackScreen,
                                            onReleased = onStartMonitoring,
                                        )
                                        handedOff = result is MonitoringStartResult.Accepted
                                        if (handedOff) onMonitoringStarted()
                                        if (result is MonitoringStartResult.Rejected) {
                                            startError = result.message
                                            startCanOpenAccount = result.canOpenAccount
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        startError = conditionSaveFailed
                                        startCanOpenAccount = false
                                    } finally {
                                        if (!handedOff) startingReading = false
                                    }
                                }
                            }
                        },
                        onRetry = {
                            session.startReadingPreview(checkNotNull(readingSpec), region, manualScanRegion)
                        },
                        onNotificationsChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onNotificationsEnabled(enabled)
                            }
                        },
                    )
                    MonitorKind.REFERENCE,
                    MonitorKind.OBJECT_DETECTION,
                    -> ReferenceFieldStatus(
                        status = fieldStatus,
                        currentIdentity = currentFieldValidationIdentity,
                        targetLabel = (monitor.target as? MonitorTarget.ObjectClass)?.localizedLabel(),
                        rule = monitor.rule as MonitorRule.TargetPresence,
                        notificationsEnabled = state.notificationsEnabled,
                        starting = startRequested,
                        rejectedCurrentMatch = referenceMatchRejected,
                        onNotificationsChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onNotificationsEnabled(enabled)
                            }
                        },
                        onConfirm = {
                            startError = null
                            startCanOpenAccount = false
                            startRequested = true
                        },
                        onReject = {
                            startError = null
                            startCanOpenAccount = false
                            startRequested = false
                            referenceMatchRejected = true
                            session.clearFieldValidation()
                        },
                        onRecheck = {
                            startError = null
                            startCanOpenAccount = false
                            referenceMatchRejected = false
                            session.startFieldValidation(checkNotNull(fieldSpec), region)
                        },
                        onRetry = {
                            startError = null
                            startCanOpenAccount = false
                            session.startFieldValidation(checkNotNull(fieldSpec), region)
                        },
                    )
                }
                if (monitor.kind == MonitorKind.READING && manualScanRegion == null &&
                    !readingRoiTutorialDismissed
                ) {
                    ReadingRoiEducationCard()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.black_screen_start_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.black_screen_start_body),
                            color = ProductColors.TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ProductSwitch(
                        checked = startWithBlackScreen,
                        onCheckedChange = { startWithBlackScreen = it },
                        accessibilityLabel = stringResource(R.string.black_screen_start_title),
                    )
                }
                startError?.let {
                    Text(it, color = ProductColors.Error, style = MaterialTheme.typography.bodyMedium)
                    if (startCanOpenAccount) {
                        OutlinedButton(
                            onClick = onOpenAccount,
                            modifier = Modifier.testTag(CameraTags.OPEN_ACCOUNT),
                        ) { Text(stringResource(R.string.action_open_account)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingPreviewStatus.currentReadingPreviewValue() = when (this) {
    is ReadingPreviewStatus.Scanning -> when (val current = live) {
        is ReadingPreviewLiveState.Candidate -> current.value
        is ReadingPreviewLiveState.Stable -> current.value
        else -> null
    }
    is ReadingPreviewStatus.AwaitingConfirmation -> live.value
    is ReadingPreviewStatus.Confirmed -> when (val current = live) {
        is ReadingPreviewLiveState.Candidate -> current.value
        is ReadingPreviewLiveState.Stable -> current.value
        else -> value
    }
    else -> null
}

private fun ReadingPreviewLiveState.stableValueOrNull() =
    (this as? ReadingPreviewLiveState.Stable)?.value

private fun currentConfirmedFormat(status: ReadingPreviewStatus): app.beyoureyes.core.domain.ConfirmedReadingFormat? =
    (status as? ReadingPreviewStatus.Confirmed)?.confirmedFormat

@Composable
internal fun ReadingTargetOverlay(
    automaticBox: NormalizedRect?,
    manualRegion: NormalizedRect?,
    editable: Boolean,
    onManualRegionChanged: (NormalizedRect?) -> Unit,
    modifier: Modifier = Modifier,
    onInteractionStarted: () -> Unit = {},
) {
    // A short numeric line may be much thinner than a touch target; handles retain their hit area.
    val minimumDimensionPixels = with(LocalDensity.current) { 16.dp.toPx() }
    val handleHitRadiusPixels = with(LocalDensity.current) { 30.dp.toPx() }
    val handleRadiusPixels = with(LocalDensity.current) { 11.dp.toPx() }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var draftRegion by remember { mutableStateOf<NormalizedRect?>(null) }
    val overlayDescription = stringResource(
        when {
            !editable && manualRegion != null -> R.string.reading_roi_active_manual
            !editable -> R.string.reading_roi_active_automatic
            manualRegion == null -> R.string.reading_roi_overlay_automatic
            else -> R.string.reading_roi_overlay_manual
        },
    )
    val moveTopLeftLeft = stringResource(R.string.reading_roi_move_top_left_left)
    val moveTopLeftRight = stringResource(R.string.reading_roi_move_top_left_right)
    val moveTopLeftUp = stringResource(R.string.reading_roi_move_top_left_up)
    val moveTopLeftDown = stringResource(R.string.reading_roi_move_top_left_down)
    val moveBottomRightLeft = stringResource(R.string.reading_roi_move_bottom_right_left)
    val moveBottomRightRight = stringResource(R.string.reading_roi_move_bottom_right_right)
    val moveBottomRightUp = stringResource(R.string.reading_roi_move_bottom_right_up)
    val moveBottomRightDown = stringResource(R.string.reading_roi_move_bottom_right_down)
    fun nudge(
        region: NormalizedRect,
        mode: ReadingBoxDragMode,
        deltaX: Float,
        deltaY: Float,
    ): Boolean {
        val updated = nudgeManualRegion(region, mode, deltaX, deltaY) ?: return false
        if (updated == region) return false
        onInteractionStarted()
        onManualRegionChanged(updated)
        return true
    }
    Canvas(
        modifier = modifier.testTag(CameraTags.READING_TARGET_OVERLAY)
            .systemGestureExclusion { coordinates ->
                manualRegion?.let { box ->
                    val center = Offset(
                        box.left * coordinates.size.width,
                        box.top * coordinates.size.height,
                    )
                    Rect(
                        left = center.x - handleHitRadiusPixels,
                        top = center.y - handleHitRadiusPixels,
                        right = center.x + handleHitRadiusPixels,
                        bottom = center.y + handleHitRadiusPixels,
                    )
                } ?: Rect.Zero
            }
            .systemGestureExclusion { coordinates ->
                manualRegion?.let { box ->
                    val center = Offset(
                        box.right * coordinates.size.width,
                        box.bottom * coordinates.size.height,
                    )
                    Rect(
                        left = center.x - handleHitRadiusPixels,
                        top = center.y - handleHitRadiusPixels,
                        right = center.x + handleHitRadiusPixels,
                        bottom = center.y + handleHitRadiusPixels,
                    )
                } ?: Rect.Zero
            }
            .pointerInput(editable, manualRegion) {
                if (!editable) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startRegion = manualRegion
                    val mode = readingBoxDragMode(
                        down = down.position,
                        manualRegion = startRegion,
                        width = size.width,
                        height = size.height,
                        handleHitRadiusPixels = handleHitRadiusPixels,
                    )
                    var moved = false
                    dragStart = down.position
                    dragEnd = down.position
                    draftRegion = startRegion
                    down.consume()
                    drag(down.id) { change ->
                        if (!moved && change.position != down.position) {
                            moved = true
                            onInteractionStarted()
                        }
                        dragEnd = change.position
                        draftRegion = when (mode) {
                            ReadingBoxDragMode.NEW -> normalizedDragRegion(
                                start = down.position,
                                end = change.position,
                                width = size.width,
                                height = size.height,
                                minimumPixels = 1f,
                            )
                            ReadingBoxDragMode.TOP_LEFT,
                            ReadingBoxDragMode.BOTTOM_RIGHT,
                            -> startRegion?.let {
                                resizeManualRegion(
                                    region = it,
                                    mode = mode,
                                    pointer = change.position,
                                    width = size.width,
                                    height = size.height,
                                    minimumPixels = minimumDimensionPixels,
                                )
                            }
                        }
                        change.consume()
                    }
                    if (moved) {
                        val completed = when (mode) {
                            ReadingBoxDragMode.NEW -> normalizedDragRegion(
                                start = down.position,
                                end = dragEnd ?: down.position,
                                width = size.width,
                                height = size.height,
                                minimumPixels = minimumDimensionPixels,
                            )
                            ReadingBoxDragMode.TOP_LEFT,
                            ReadingBoxDragMode.BOTTOM_RIGHT,
                            -> draftRegion
                        }
                        completed?.let(onManualRegionChanged)
                    }
                    dragStart = null
                    dragEnd = null
                    draftRegion = null
                }
            }.semantics {
                contentDescription = overlayDescription
                customActions = if (editable && manualRegion != null) {
                    listOf(
                        CustomAccessibilityAction(moveTopLeftLeft) {
                            nudge(manualRegion, ReadingBoxDragMode.TOP_LEFT, -0.03f, 0f)
                        },
                        CustomAccessibilityAction(moveTopLeftRight) {
                            nudge(manualRegion, ReadingBoxDragMode.TOP_LEFT, 0.03f, 0f)
                        },
                        CustomAccessibilityAction(moveTopLeftUp) {
                            nudge(manualRegion, ReadingBoxDragMode.TOP_LEFT, 0f, -0.03f)
                        },
                        CustomAccessibilityAction(moveTopLeftDown) {
                            nudge(manualRegion, ReadingBoxDragMode.TOP_LEFT, 0f, 0.03f)
                        },
                        CustomAccessibilityAction(moveBottomRightLeft) {
                            nudge(manualRegion, ReadingBoxDragMode.BOTTOM_RIGHT, -0.03f, 0f)
                        },
                        CustomAccessibilityAction(moveBottomRightRight) {
                            nudge(manualRegion, ReadingBoxDragMode.BOTTOM_RIGHT, 0.03f, 0f)
                        },
                        CustomAccessibilityAction(moveBottomRightUp) {
                            nudge(manualRegion, ReadingBoxDragMode.BOTTOM_RIGHT, 0f, -0.03f)
                        },
                        CustomAccessibilityAction(moveBottomRightDown) {
                            nudge(manualRegion, ReadingBoxDragMode.BOTTOM_RIGHT, 0f, 0.03f)
                        },
                    )
                } else {
                    emptyList()
                }
            },
    ) {
        if (manualRegion == null && draftRegion == null) automaticBox?.let { box ->
            drawFocusCorners(
                box = box,
                color = ProductColors.Cyan,
            )
        }
        val displayedManualRegion = draftRegion ?: manualRegion
        displayedManualRegion?.let { box ->
            drawRect(
                color = ProductColors.Amber,
                topLeft = Offset(box.left * size.width, box.top * size.height),
                size = androidx.compose.ui.geometry.Size(
                    (box.right - box.left) * size.width,
                    (box.bottom - box.top) * size.height,
                ),
                style = Stroke(width = 6f),
            )
            if (editable) {
                listOf(
                    Offset(box.left * size.width, box.top * size.height),
                    Offset(box.right * size.width, box.bottom * size.height),
                ).forEach { center ->
                    drawCircle(
                        color = Color(0xE80B1110),
                        radius = handleRadiusPixels + 3f,
                        center = center,
                    )
                    drawCircle(
                        color = ProductColors.Amber,
                        radius = handleRadiusPixels,
                        center = center,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = handleRadiusPixels * 0.33f,
                        center = center,
                    )
                }
            }
        }
        val start = dragStart
        val end = dragEnd
        if (draftRegion == null && start != null && end != null && start != end) {
            drawRect(
                color = ProductColors.Amber,
                topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                size = androidx.compose.ui.geometry.Size(
                    kotlin.math.abs(end.x - start.x),
                    kotlin.math.abs(end.y - start.y),
                ),
                style = Stroke(width = 5f),
            )
        }
    }
}

@Composable
internal fun TargetDetectionOverlay(
    detections: List<Detection>,
    modifier: Modifier = Modifier,
) {
    if (detections.isEmpty()) return
    val overlayDescription = stringResource(R.string.target_detection_overlay)
    Canvas(
        modifier = modifier.testTag(ActiveMonitoringTags.TARGET_BOXES).semantics {
            contentDescription = overlayDescription
        },
    ) {
        detections.forEach { detection ->
            drawFocusCorners(
                box = detection.box,
                color = ProductColors.Green,
            )
        }
    }
}

internal fun visibleTargetDetections(
    observation: Observation?,
    targetId: String?,
): List<Detection> = if (targetId == null) {
    emptyList()
} else {
    (observation as? Observation.Detections)?.items?.filter { it.label == targetId }.orEmpty()
}

internal enum class ReadingBoxDragMode {
    NEW,
    TOP_LEFT,
    BOTTOM_RIGHT,
}

internal fun readingBoxDragMode(
    down: Offset,
    manualRegion: NormalizedRect?,
    width: Int,
    height: Int,
    handleHitRadiusPixels: Float,
): ReadingBoxDragMode {
    if (manualRegion == null || width <= 0 || height <= 0 || handleHitRadiusPixels <= 0f) {
        return ReadingBoxDragMode.NEW
    }
    val topLeft = Offset(manualRegion.left * width, manualRegion.top * height)
    val bottomRight = Offset(manualRegion.right * width, manualRegion.bottom * height)
    val radiusSquared = handleHitRadiusPixels * handleHitRadiusPixels
    val topLeftDistance = (down - topLeft).getDistanceSquared()
    val bottomRightDistance = (down - bottomRight).getDistanceSquared()
    return when {
        topLeftDistance <= radiusSquared && topLeftDistance <= bottomRightDistance ->
            ReadingBoxDragMode.TOP_LEFT
        bottomRightDistance <= radiusSquared -> ReadingBoxDragMode.BOTTOM_RIGHT
        else -> ReadingBoxDragMode.NEW
    }
}

internal fun resizeManualRegion(
    region: NormalizedRect,
    mode: ReadingBoxDragMode,
    pointer: Offset,
    width: Int,
    height: Int,
    minimumPixels: Float = 48f,
): NormalizedRect? {
    if (width <= 0 || height <= 0 || minimumPixels <= 0f ||
        minimumPixels >= width || minimumPixels >= height || mode == ReadingBoxDragMode.NEW
    ) return null
    val minimumWidth = (minimumPixels / width).coerceIn(0f, 1f)
    val minimumHeight = (minimumPixels / height).coerceIn(0f, 1f)
    val normalizedX = (pointer.x / width).coerceIn(0f, 1f)
    val normalizedY = (pointer.y / height).coerceIn(0f, 1f)
    return when (mode) {
        ReadingBoxDragMode.TOP_LEFT -> NormalizedRect(
            left = normalizedX.coerceAtMost(region.right - minimumWidth),
            top = normalizedY.coerceAtMost(region.bottom - minimumHeight),
            right = region.right,
            bottom = region.bottom,
        )
        ReadingBoxDragMode.BOTTOM_RIGHT -> NormalizedRect(
            left = region.left,
            top = region.top,
            right = normalizedX.coerceAtLeast(region.left + minimumWidth),
            bottom = normalizedY.coerceAtLeast(region.top + minimumHeight),
        )
        ReadingBoxDragMode.NEW -> null
    }
}

internal fun nudgeManualRegion(
    region: NormalizedRect,
    mode: ReadingBoxDragMode,
    deltaX: Float,
    deltaY: Float,
): NormalizedRect? = resizeManualRegion(
    region = region,
    mode = mode,
    pointer = when (mode) {
        ReadingBoxDragMode.TOP_LEFT -> Offset(
            x = (region.left + deltaX) * 1_000f,
            y = (region.top + deltaY) * 1_000f,
        )
        ReadingBoxDragMode.BOTTOM_RIGHT -> Offset(
            x = (region.right + deltaX) * 1_000f,
            y = (region.bottom + deltaY) * 1_000f,
        )
        ReadingBoxDragMode.NEW -> return null
    },
    width = 1_000,
    height = 1_000,
    minimumPixels = 48f,
)

internal fun normalizedDragRegion(
    start: Offset,
    end: Offset,
    width: Int,
    height: Int,
    minimumPixels: Float = 48f,
): NormalizedRect? {
    if (width <= 0 || height <= 0) return null
    if (kotlin.math.abs(end.x - start.x) < minimumPixels ||
        kotlin.math.abs(end.y - start.y) < minimumPixels
    ) return null
    return NormalizedRect(
        left = (minOf(start.x, end.x) / width).coerceIn(0f, 1f),
        top = (minOf(start.y, end.y) / height).coerceIn(0f, 1f),
        right = (maxOf(start.x, end.x) / width).coerceIn(0f, 1f),
        bottom = (maxOf(start.y, end.y) / height).coerceIn(0f, 1f),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusCorners(
    box: NormalizedRect,
    color: Color,
) {
    val left = box.left * size.width
    val top = box.top * size.height
    val right = box.right * size.width
    val bottom = box.bottom * size.height
    val length = minOf(right - left, bottom - top, 28f).coerceAtLeast(8f)
    val stroke = Stroke(width = 4f)
    listOf(
        Offset(left, top) to Offset(left + length, top), Offset(left, top) to Offset(left, top + length),
        Offset(right, top) to Offset(right - length, top), Offset(right, top) to Offset(right, top + length),
        Offset(left, bottom) to Offset(left + length, bottom), Offset(left, bottom) to Offset(left, bottom - length),
        Offset(right, bottom) to Offset(right - length, bottom), Offset(right, bottom) to Offset(right, bottom - length),
    ).forEach { (from, to) -> drawLine(color, from, to, strokeWidth = stroke.width) }
}

@Composable
private fun ReadingTargetInstruction(
    text: String,
    waitingForSelection: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xD9080C0F),
        contentColor = if (waitingForSelection) ProductColors.Amber else ProductColors.Cyan,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ReferenceCameraRail(
    materials: List<app.beyoureyes.core.domain.ReferenceMaterial>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(CameraTags.REFERENCE_RAIL),
        color = ProductColors.Background.copy(alpha = 0.86f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                stringResource(R.string.reference_images_short),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
            )
            materials.take(3).forEachIndexed { index, material ->
                val imageDescription = stringResource(R.string.reference_image_number, index + 1)
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = imageDescription
                        }
                    },
                    update = { image ->
                        val uri = material.thumbnailUri ?: material.sourceUri
                        if (image.tag != uri) {
                            image.tag = uri
                            image.setImageURI(uri.toUri())
                        }
                    },
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)),
                )
            }
            if (materials.size > 3) {
                Text(
                    stringResource(R.string.reference_images_more_count, materials.size - 3),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

internal enum class ReferenceFieldFeedback {
    WAITING,
    PRESENT_CANDIDATE,
    PRESENT_CONFIRMED,
    ABSENT,
    UNAVAILABLE,
    FAILED,
    INVALIDATED,
}

internal fun referenceFieldFeedback(
    status: SetupFieldValidationStatus,
    currentIdentity: SimilarityFieldValidationIdentity?,
): ReferenceFieldFeedback {
    val latestSignal = status.summaryOrEmpty().latestSignal
    return when {
        fieldValidationPassedFor(status, currentIdentity) ->
            ReferenceFieldFeedback.PRESENT_CONFIRMED
        status is SetupFieldValidationStatus.Failed -> ReferenceFieldFeedback.FAILED
        status is SetupFieldValidationStatus.Invalidated ||
            status is SetupFieldValidationStatus.Passed -> ReferenceFieldFeedback.INVALIDATED
        latestSignal == SimilarityFieldValidationSignal.PRESENT ->
            ReferenceFieldFeedback.PRESENT_CANDIDATE
        latestSignal == SimilarityFieldValidationSignal.UNAVAILABLE ->
            ReferenceFieldFeedback.UNAVAILABLE
        latestSignal == SimilarityFieldValidationSignal.ABSENT ||
            status is SetupFieldValidationStatus.NotFound -> ReferenceFieldFeedback.ABSENT
        else -> ReferenceFieldFeedback.WAITING
    }
}

@Composable
private fun ReferenceFieldStatus(
    status: SetupFieldValidationStatus,
    currentIdentity: SimilarityFieldValidationIdentity?,
    targetLabel: String? = null,
    rule: MonitorRule.TargetPresence,
    notificationsEnabled: Boolean,
    starting: Boolean,
    rejectedCurrentMatch: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onRecheck: () -> Unit,
    onRetry: () -> Unit,
) {
    if (rejectedCurrentMatch) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductIconBadge(
                icon = Icons.Outlined.ImageSearch,
                contentDescription = null,
                tint = ProductColors.Amber,
                background = ProductColors.AmberSoft,
            )
            Text(
                stringResource(R.string.field_match_ignored),
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                color = ProductColors.Amber,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(stringResource(R.string.field_adjust_camera_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.field_adjust_camera_body),
            color = ProductColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        ProductPrimaryButton(
            text = stringResource(R.string.field_continue_check),
            onClick = onRecheck,
            leadingIcon = Icons.Outlined.Refresh,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag(CameraTags.REFERENCE_RECHECK),
        )
        return
    }
    val feedback = referenceFieldFeedback(status, currentIdentity)
    val showProgress = feedback in setOf(
        ReferenceFieldFeedback.WAITING,
        ReferenceFieldFeedback.PRESENT_CANDIDATE,
        ReferenceFieldFeedback.UNAVAILABLE,
    )
    val stateLabel = when (feedback) {
        ReferenceFieldFeedback.WAITING -> stringResource(R.string.field_status_observing)
        ReferenceFieldFeedback.PRESENT_CANDIDATE -> stringResource(R.string.field_status_confirming)
        ReferenceFieldFeedback.PRESENT_CONFIRMED -> stringResource(R.string.field_status_found)
        ReferenceFieldFeedback.ABSENT -> stringResource(R.string.field_status_absent)
        ReferenceFieldFeedback.UNAVAILABLE -> stringResource(R.string.field_status_unavailable)
        ReferenceFieldFeedback.FAILED -> stringResource(R.string.field_status_failed)
        ReferenceFieldFeedback.INVALIDATED -> stringResource(R.string.field_status_invalidated)
    }
    val title = when (feedback) {
        ReferenceFieldFeedback.WAITING -> if (
            status is SetupFieldValidationStatus.Idle ||
            status is SetupFieldValidationStatus.Opening
        ) {
            stringResource(R.string.field_put_target_in_frame)
        } else {
            if (targetLabel == null) stringResource(R.string.field_comparing_reference)
            else stringResource(R.string.field_looking_for_target, targetLabel)
        }
        ReferenceFieldFeedback.PRESENT_CANDIDATE ->
            if (targetLabel == null) stringResource(R.string.field_reference_detected)
            else stringResource(R.string.field_target_detected, targetLabel)
        ReferenceFieldFeedback.PRESENT_CONFIRMED -> stringResource(R.string.field_confirm_target_question)
        ReferenceFieldFeedback.ABSENT ->
            if (targetLabel == null) stringResource(R.string.field_reference_not_found)
            else stringResource(R.string.field_target_not_found, targetLabel)
        ReferenceFieldFeedback.UNAVAILABLE -> stringResource(R.string.field_waiting_clear_frame)
        ReferenceFieldFeedback.FAILED -> stringResource(R.string.field_check_incomplete)
        ReferenceFieldFeedback.INVALIDATED -> stringResource(R.string.field_setup_changed)
    }
    val body = when (feedback) {
        ReferenceFieldFeedback.WAITING ->
            if (targetLabel == null) stringResource(R.string.field_move_for_reference)
            else stringResource(R.string.field_move_for_target, targetLabel)
        ReferenceFieldFeedback.PRESENT_CANDIDATE ->
            stringResource(R.string.field_candidate_body)
        ReferenceFieldFeedback.PRESENT_CONFIRMED ->
            targetPresenceSetupBody(rule, targetLabel)
        ReferenceFieldFeedback.ABSENT ->
            if (targetLabel == null) stringResource(R.string.field_absent_reference_body)
            else stringResource(R.string.field_absent_target_body, targetLabel)
        ReferenceFieldFeedback.UNAVAILABLE ->
            stringResource(R.string.field_unavailable_body)
        ReferenceFieldFeedback.FAILED -> stringResource(R.string.field_failed_body)
        ReferenceFieldFeedback.INVALIDATED -> stringResource(R.string.field_invalidated_body)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductIconBadge(
            icon = when (feedback) {
                ReferenceFieldFeedback.WAITING,
                ReferenceFieldFeedback.PRESENT_CANDIDATE,
                -> Icons.Outlined.Visibility
                ReferenceFieldFeedback.PRESENT_CONFIRMED -> Icons.Outlined.CheckCircle
                ReferenceFieldFeedback.UNAVAILABLE,
                ReferenceFieldFeedback.FAILED,
                -> Icons.Outlined.ErrorOutline
                ReferenceFieldFeedback.ABSENT,
                ReferenceFieldFeedback.INVALIDATED,
                -> Icons.Outlined.ImageSearch
            },
            contentDescription = null,
            tint = when (feedback) {
                ReferenceFieldFeedback.WAITING,
                ReferenceFieldFeedback.PRESENT_CANDIDATE,
                ReferenceFieldFeedback.ABSENT,
                -> ProductColors.Cyan
                ReferenceFieldFeedback.PRESENT_CONFIRMED -> ProductColors.Green
                ReferenceFieldFeedback.FAILED -> ProductColors.Error
                ReferenceFieldFeedback.UNAVAILABLE,
                ReferenceFieldFeedback.INVALIDATED,
                -> ProductColors.Amber
            },
            background = when (feedback) {
                ReferenceFieldFeedback.WAITING,
                ReferenceFieldFeedback.PRESENT_CANDIDATE,
                ReferenceFieldFeedback.ABSENT,
                -> ProductColors.CyanSoft
                ReferenceFieldFeedback.PRESENT_CONFIRMED -> ProductColors.GreenSoft
                ReferenceFieldFeedback.FAILED -> ProductColors.ErrorSoft
                ReferenceFieldFeedback.UNAVAILABLE,
                ReferenceFieldFeedback.INVALIDATED,
                -> ProductColors.AmberSoft
            },
        )
        Text(
            stateLabel,
            modifier = Modifier.weight(1f).padding(start = 12.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            },
            color = when (feedback) {
                ReferenceFieldFeedback.PRESENT_CONFIRMED -> ProductColors.Green
                ReferenceFieldFeedback.WAITING,
                ReferenceFieldFeedback.PRESENT_CANDIDATE,
                ReferenceFieldFeedback.ABSENT,
                -> ProductColors.Cyan
                ReferenceFieldFeedback.FAILED -> ProductColors.Error
                ReferenceFieldFeedback.UNAVAILABLE,
                ReferenceFieldFeedback.INVALIDATED,
                -> ProductColors.TextSecondary
            },
            style = MaterialTheme.typography.labelLarge,
        )
        if (showProgress) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(body, color = ProductColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    // 测试识别不是门槛：任何识别状态下都可以保存并开始监控。
    val confirmed = fieldValidationPassedFor(status, currentIdentity)
    LocalNotificationSetupPanel(
        checked = notificationsEnabled,
        onCheckedChange = onNotificationsChange,
    )
    ProductPrimaryButton(
        text = when {
            starting -> stringResource(R.string.status_starting)
            confirmed && targetLabel == null -> stringResource(R.string.field_yes_start)
            confirmed -> stringResource(R.string.field_this_target)
            else -> stringResource(R.string.field_start_without_match)
        },
        onClick = onConfirm,
        enabled = !starting,
        leadingIcon = if (starting) null else Icons.Outlined.CheckCircle,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag(CameraTags.REFERENCE_CONFIRM),
    )
    if (confirmed) {
        OutlinedButton(
            onClick = onReject,
            enabled = !starting,
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag(CameraTags.REFERENCE_REJECT),
        ) {
            Text(stringResource(R.string.field_not_this_target))
        }
    } else if (status.canRetryCheck()) {
        OutlinedButton(
            onClick = onRetry,
            enabled = !starting,
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag(CameraTags.RETRY),
        ) {
            Text(stringResource(R.string.action_recheck))
        }
    }
}

@Composable
internal fun ActiveMonitoringScreen(
    name: String,
    kind: MonitorKind?,
    rule: MonitorRule? = null,
    targetLabel: String? = null,
    targetId: String? = null,
    confirmedReadingFormat: ConfirmedReadingFormat? = null,
    latestReading: LatestReading?,
    latestObservation: Observation?,
    observationSnapshot: MonitoringObservationSnapshot?,
    eventCount: Int,
    lastEventText: UiText?,
    status: MonitoringStatus,
    onDetails: (() -> Unit)?,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onOpenHistory: (() -> Unit)? = null,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nowElapsed by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var confirmStop by remember { mutableStateOf(false) }
    var showReadingHelp by remember { mutableStateOf(false) }
    var blackScreen by remember(status.monitorId) {
        mutableStateOf(status.initialBlackScreenPending)
    }
    val eventHighlight = remember(status.monitorId) { Animatable(0f) }
    var previousEventCount by remember(status.monitorId) { mutableIntStateOf(eventCount) }
    LaunchedEffect(status.monitorId, eventCount, blackScreen) {
        val hasNewEvent = eventCount > previousEventCount
        previousEventCount = eventCount
        eventHighlight.snapTo(0f)
        if (hasNewEvent && !blackScreen) {
            eventHighlight.animateTo(1f, tween(120))
            eventHighlight.animateTo(0f, tween(360))
        }
    }
    val recordCountDescription = pluralStringResource(
        R.plurals.metric_record_count_accessibility,
        eventCount,
        eventCount,
    )
    LaunchedEffect(status.monitorId) {
        if (MonitoringRuntimeState.consumeInitialBlackScreen(status.monitorId)) {
            blackScreen = true
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            nowElapsed = android.os.SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val liveText = liveObservationText(
        kind,
        latestObservation,
        latestReading,
        confirmedReadingFormat,
    ).resolve()
    val presentation = observationPresentation(
        kind,
        latestObservation,
        latestReading,
        confirmedReadingFormat,
    )
    val health = monitoringHealthPresentation(status)
    val fixedManualReadingScanRegion by MonitoringRuntimeState.manualReadingScanRegion.collectAsState()
    val elapsed = if (status.startedAtEpochMillis == null) {
        stringResource(R.string.time_just_now)
    } else {
        formatElapsed(status.startedAtEpochMillis, now)
    }
    val condition = when (val activeRule = rule) {
        is MonitorRule.TargetPresence -> targetPresenceRuleSummary(activeRule, targetLabel)
        is MonitorRule.ReadingThreshold -> readingRuleSummary(activeRule, confirmedReadingFormat).resolve()
        else -> stringResource(
            if (kind == MonitorKind.REFERENCE) R.string.condition_record_when_found
            else R.string.condition_record_when_met,
        )
    }

    if (blackScreen) {
        BlackMonitoringScreen(
            name = name,
            health = health,
            onRestorePreview = { blackScreen = false },
        )
        return
    }

    val observationTone by animateColorAsState(presentation.tone, tween(220), label = "observationTone")
    val observationForeground by animateColorAsState(
        presentation.foreground,
        tween(220),
        label = "observationForeground",
    )
    val healthColor by animateColorAsState(health.color, tween(220), label = "monitoringHealth")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .fillMaxHeight(CAMERA_VIEWPORT_FRACTION),
        ) {
            MonitoringCameraPreviewView(
                Modifier.fillMaxSize().testTag(ActiveMonitoringTags.PREVIEW),
            )
            val readingObservation = latestObservation as? Observation.Reading
            TargetDetectionOverlay(
                detections = visibleTargetDetections(latestObservation, targetId),
                modifier = Modifier.fillMaxSize(),
            )
            if (kind == MonitorKind.READING &&
                (fixedManualReadingScanRegion != null || readingObservation != null)
            ) {
                ReadingTargetOverlay(
                    automaticBox = readingObservation?.anchorBox,
                    manualRegion = fixedManualReadingScanRegion,
                    editable = false,
                    onManualRegionChanged = {},
                    modifier = Modifier.fillMaxSize(),
                )
                ReadingTargetInstruction(
                    text = if (fixedManualReadingScanRegion != null) {
                        stringResource(R.string.reading_roi_active_manual)
                    } else {
                        stringResource(R.string.reading_roi_active_automatic)
                    },
                    waitingForSelection = false,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                color = Color(0xB8080C0F),
                contentColor = Color.White,
            ) {
                Row(
                    modifier = Modifier.statusBarsPadding()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = healthColor,
                        ) {}
                        Column(Modifier.padding(start = 9.dp)) {
                            Text(
                                stringResource(R.string.monitoring_active_elapsed, elapsed),
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                name,
                                color = Color.White.copy(alpha = 0.74f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        onClick = { blackScreen = true },
                        modifier = Modifier.testTag(ActiveMonitoringTags.BLACK_TOGGLE),
                    ) {
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.black_screen_button),
                            modifier = Modifier.padding(start = 5.dp),
                            color = Color.White,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .fillMaxHeight(ACTIVE_PANEL_FRACTION),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = ProductColors.Background,
            contentColor = ProductColors.TextPrimary,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = lerp(observationTone, ProductColors.CyanSoft, eventHighlight.value),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                lerp(
                                    observationForeground.copy(alpha = 0.28f),
                                    ProductColors.Cyan,
                                    eventHighlight.value,
                                ),
                            ),
                            modifier = Modifier.size(56.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    presentation.icon,
                                    contentDescription = null,
                                    tint = observationForeground,
                                    modifier = Modifier.size(27.dp),
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(
                                presentation.primary.resolve(),
                                style = if (kind == MonitorKind.READING) {
                                    MaterialTheme.typography.headlineMedium.copy(
                                        fontSize = 32.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                } else MaterialTheme.typography.headlineSmall,
                                color = ProductColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                condition,
                                color = ProductColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(7.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = healthColor,
                        ) {}
                        Text(
                            if (kind == MonitorKind.READING && latestObservation is Observation.Unavailable) {
                                readingUnavailableHint(latestObservation.diagnosticCode)?.resolve()
                                    ?: status.message ?: liveText
                            } else status.message ?: liveText,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            color = healthColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (kind == MonitorKind.READING) {
                            IconButton(
                                onClick = { showReadingHelp = true },
                                modifier = Modifier.testTag(ActiveMonitoringTags.READING_HELP),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.HelpOutline,
                                    contentDescription = stringResource(R.string.reading_help_title),
                                    tint = ProductColors.TextSecondary,
                                )
                            }
                        }
                    }
                    if (kind == MonitorKind.READING && observationSnapshot != null) {
                        val ageSeconds = ((nowElapsed - observationSnapshot.publishedElapsedMillis)
                            .coerceAtLeast(0L) / 1_000)
                        Text(
                            text = if (ageSeconds == 0L) stringResource(R.string.reading_result_just_updated)
                            else stringResource(R.string.reading_result_updated_seconds_ago, ageSeconds),
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            color = ProductColors.TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 14.dp),
                        color = ProductColors.Border,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().height(68.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProductMetric(
                            eventCount.toString(),
                            stringResource(R.string.metric_records),
                            Modifier.weight(1f).semantics(mergeDescendants = true) {
                                contentDescription = recordCountDescription
                            },
                        )
                        Surface(
                            modifier = Modifier.size(width = 1.dp, height = 40.dp),
                            color = ProductColors.Border,
                        ) {}
                        ProductMetric(elapsed, stringResource(R.string.metric_runtime), Modifier.weight(1f))
                    }
                    if (lastEventText != null) {
                        HorizontalDivider(color = ProductColors.Border)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .then(
                                    if (onOpenHistory != null) Modifier.clickable(
                                        role = Role.Button,
                                        onClickLabel = stringResource(R.string.action_view_records),
                                        onClick = onOpenHistory,
                                    ).testTag(ActiveMonitoringTags.HISTORY) else Modifier,
                                ).heightIn(min = 48.dp).padding(vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.latest_record),
                                color = ProductColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                lastEventText.resolve(),
                                modifier = Modifier.weight(1f).padding(start = 16.dp),
                                color = ProductColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (onOpenHistory != null) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = stringResource(R.string.action_view_records),
                                    tint = ProductColors.Cyan,
                                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = ProductColors.Border)
                ProductPrimaryButton(
                    text = stringResource(R.string.action_home_keep_monitoring),
                    onClick = onBack,
                    leadingIcon = Icons.Outlined.Home,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onDetails != null) {
                        TextButton(
                            onClick = onDetails,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Text(stringResource(R.string.monitor_settings))
                        }
                    }
                    TextButton(
                        onClick = { confirmStop = true },
                        modifier = Modifier.weight(1f).height(48.dp).testTag(ActiveMonitoringTags.STOP),
                    ) {
                        Text(stringResource(R.string.action_stop_monitoring), color = ProductColors.Error)
                    }
                }
            }
        }
    }

    if (showReadingHelp) {
        AlertDialog(
            onDismissRequest = { showReadingHelp = false },
            title = { Text(stringResource(R.string.reading_help_title)) },
            text = { Text(stringResource(R.string.reading_help_body)) },
            confirmButton = {
                TextButton(onClick = { showReadingHelp = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.stop_monitoring_title)) },
            text = { Text(stringResource(R.string.stop_monitoring_body)) },
            confirmButton = {
                Button(
                    onClick = { confirmStop = false; onStop() },
                    modifier = Modifier.testTag(ActiveMonitoringTags.STOP_CONFIRM),
                ) { Text(stringResource(R.string.action_stop_monitoring)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.action_continue_monitoring))
                }
            },
        )
    }
}

@Composable
private fun MonitoringCameraPreviewView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val surfaceProvider = remember(previewView) { previewView.surfaceProvider }
    DisposableEffect(surfaceProvider) {
        MonitoringCameraPreview.attach(surfaceProvider)
        onDispose { MonitoringCameraPreview.detach(surfaceProvider) }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
private fun BlackMonitoringScreen(
    name: String,
    health: MonitoringHealthPresentation,
    onRestorePreview: () -> Unit,
) {
    val accessibilityDescription = stringResource(R.string.black_screen_accessibility)
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .clickable(onClick = onRestorePreview)
            .semantics { contentDescription = accessibilityDescription }
            .testTag(ActiveMonitoringTags.BLACK_SCREEN),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = health.color,
            ) {}
            Text(
                stringResource(R.string.monitoring_still_running),
                modifier = Modifier.padding(top = 12.dp),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                name,
                modifier = Modifier.padding(top = 4.dp),
                color = Color.White.copy(alpha = 0.42f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.black_screen_boundary),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                stringResource(R.string.restore_camera_preview),
                modifier = Modifier.padding(top = 18.dp)
                    .testTag(ActiveMonitoringTags.RESTORE_PREVIEW),
                color = Color.White.copy(alpha = 0.42f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal const val CAMERA_VIEWPORT_FRACTION = 0.55f
private const val ACTIVE_PANEL_FRACTION = 0.50f

private data class MonitoringHealthPresentation(val color: Color)

private fun monitoringHealthPresentation(status: MonitoringStatus): MonitoringHealthPresentation =
    when (status.health) {
        app.beyoureyes.monitor.MonitoringHealth.TEMPORARILY_UNAVAILABLE ->
            MonitoringHealthPresentation(ProductColors.Amber)
        app.beyoureyes.monitor.MonitoringHealth.THERMALLY_LIMITED ->
            MonitoringHealthPresentation(ProductColors.Amber)
        app.beyoureyes.monitor.MonitoringHealth.FATAL ->
            MonitoringHealthPresentation(ProductColors.Error)
        app.beyoureyes.monitor.MonitoringHealth.IDLE ->
            MonitoringHealthPresentation(ProductColors.TextMuted)
        app.beyoureyes.monitor.MonitoringHealth.WARMING ->
            MonitoringHealthPresentation(ProductColors.Cyan)
        app.beyoureyes.monitor.MonitoringHealth.OBSERVING ->
            MonitoringHealthPresentation(ProductColors.Green)
    }

private fun formatElapsed(startedAtEpochMillis: Long, nowEpochMillis: Long): String {
    val totalSeconds = ((nowEpochMillis - startedAtEpochMillis).coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

internal data class ObservationPresentation(
    val primary: UiText,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val foreground: Color,
    val tone: Color,
)

internal fun observationPresentation(
    kind: MonitorKind?,
    observation: Observation?,
    latestReading: LatestReading?,
    confirmedReadingFormat: ConfirmedReadingFormat?,
): ObservationPresentation = when (observation) {
    is Observation.Reading -> ObservationPresentation(
        primary = UiText.Verbatim(observation.text),
        icon = Icons.Outlined.Numbers,
        foreground = if (observation.stable) ProductColors.Green else ProductColors.Cyan,
        tone = if (observation.stable) ProductColors.GreenSoft else ProductColors.CyanSoft,
    )
    is Observation.State -> if (observation.stateId.endsWith(":present")) {
        ObservationPresentation(uiText(R.string.observation_target_found), Icons.Outlined.Visibility, ProductColors.Green, ProductColors.GreenSoft)
    } else {
        ObservationPresentation(uiText(R.string.observation_target_not_found), Icons.Outlined.ImageSearch, ProductColors.TextSecondary, ProductColors.SurfaceHighlight)
    }
    is Observation.Detections -> if (observation.items.isEmpty()) {
        ObservationPresentation(uiText(R.string.observation_target_not_found), Icons.Outlined.ImageSearch, ProductColors.TextSecondary, ProductColors.SurfaceHighlight)
    } else {
        ObservationPresentation(uiText(R.string.observation_target_found), Icons.Outlined.Visibility, ProductColors.Green, ProductColors.GreenSoft)
    }
    is Observation.Unavailable -> if (kind == MonitorKind.READING) {
        ObservationPresentation(
            uiText(R.string.observation_reading_unavailable),
            Icons.Outlined.Numbers,
            ProductColors.Amber,
            ProductColors.AmberSoft,
        )
    } else {
        ObservationPresentation(
            uiText(R.string.observation_frame_unavailable),
            Icons.Outlined.ErrorOutline,
            ProductColors.Amber,
            ProductColors.AmberSoft,
        )
    }
    null -> if (kind == MonitorKind.READING && latestReading?.valueDecimal != null) {
        ObservationPresentation(
            UiText.Verbatim(
                latestReadingDisplayText(latestReading, confirmedReadingFormat)
                    ?: requireNotNull(latestReading.valueDecimal),
            ),
            Icons.Outlined.Numbers,
            if (latestReading.status == ReadingStatus.STABLE) ProductColors.Green else ProductColors.Cyan,
            if (latestReading.status == ReadingStatus.STABLE) ProductColors.GreenSoft else ProductColors.CyanSoft,
        )
    } else {
        ObservationPresentation(
            uiText(R.string.observation_observing),
            if (kind == MonitorKind.READING) Icons.Outlined.Numbers else Icons.Outlined.Visibility,
            ProductColors.Cyan,
            ProductColors.CyanSoft,
        )
    }
}

internal fun liveObservationText(
    kind: MonitorKind?,
    observation: Observation?,
    latestReading: LatestReading?,
    confirmedReadingFormat: ConfirmedReadingFormat? = null,
): UiText = when (observation) {
    is Observation.Reading -> if (observation.stable) {
        uiText(R.string.reading_current_value, observation.text)
    } else {
        uiText(R.string.reading_confirming_value, observation.text)
    }
    is Observation.State -> when {
        observation.stateId.endsWith(":present") -> uiText(R.string.observation_target_found)
        observation.stateId.endsWith(":absent") -> uiText(R.string.observation_target_not_found)
        else -> uiText(R.string.observation_identifying_state)
    }
    is Observation.Detections -> if (observation.items.isEmpty()) {
        uiText(R.string.observation_target_not_found)
    } else {
        uiText(R.string.observation_target_found)
    }
    is Observation.Unavailable -> observation.retainedReading?.let { retained ->
        val ageSeconds = (observation.retainedReadingAgeMillis ?: 0L) / 100.0 / 10.0
        uiText(R.string.observation_retained_reading, retained.text, ageSeconds)
    } ?: uiText(R.string.observation_frame_currently_unavailable)
    null -> if (kind == MonitorKind.READING) {
        val displayText = latestReadingDisplayText(latestReading, confirmedReadingFormat).orEmpty()
        when (latestReading?.status) {
            ReadingStatus.STABLE -> uiText(R.string.reading_current_value, displayText)
            ReadingStatus.CANDIDATE -> uiText(R.string.reading_confirming_value, displayText)
            ReadingStatus.UNAVAILABLE -> uiText(R.string.reading_unavailable)
            null -> uiText(R.string.observation_waiting_first_stable_reading)
        }
    } else {
        uiText(R.string.observation_waiting_first_result)
    }
}

internal fun latestReadingDisplayText(
    latestReading: LatestReading?,
    confirmedReadingFormat: ConfirmedReadingFormat?,
): String? = latestReading?.valueDecimal?.let { canonicalValue ->
    confirmedReadingFormat?.displayValue(canonicalValue) ?: canonicalValue
}

@Composable
internal fun OtherMonitorRunningScreen(onOpenActive: () -> Unit, onBack: () -> Unit) {
    ProductStateScreen(
        icon = Icons.Outlined.PauseCircleOutline,
        title = stringResource(R.string.other_monitor_running_title),
        body = stringResource(R.string.other_monitor_running_body),
        primaryText = stringResource(R.string.action_view_active_monitor),
        onPrimary = onOpenActive,
        secondaryText = stringResource(R.string.action_back),
        onSecondary = onBack,
    )
}

@Composable
internal fun MonitoringFailureScreen(
    message: String,
    onRetry: () -> Unit,
    onDetails: () -> Unit,
    onBack: () -> Unit,
) {
    ProductStateScreen(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.monitoring_stopped_title),
        body = message,
        primaryText = stringResource(R.string.action_reopen_camera),
        onPrimary = onRetry,
        secondaryText = stringResource(R.string.action_view_monitor_settings),
        onSecondary = onDetails,
        tertiaryText = stringResource(R.string.action_back_home),
        onTertiary = onBack,
        tone = ProductTone.ERROR,
    )
}

@Composable
private fun CameraFailureScreen(message: String, retry: () -> Unit, onBack: () -> Unit) {
    ProductStateScreen(
        icon = Icons.Outlined.CameraAlt,
        title = stringResource(R.string.camera_not_open_title),
        body = message,
        primaryText = stringResource(R.string.action_reopen_camera),
        onPrimary = retry,
        primaryTag = CameraTags.CAMERA_RETRY,
        secondaryText = stringResource(R.string.action_back),
        onSecondary = onBack,
        tone = ProductTone.ERROR,
    )
}

@Composable
private fun ErrorScreen(
    state: MonitorCameraState.Error,
    retry: () -> Unit,
    replaceReference: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
) {
    ProductStateScreen(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(
            if (state.retryable) R.string.detection_not_ready_title
            else R.string.monitor_missing_title,
        ),
        body = state.message.resolve(),
        primaryText = when {
            !state.retryable -> stringResource(R.string.action_back_home)
            state.canOpenAccount -> stringResource(R.string.action_open_account)
            state.canReplaceReferenceImages -> stringResource(R.string.action_choose_images_again)
            else -> stringResource(R.string.action_retry)
        },
        onPrimary = when {
            !state.retryable -> onBack
            state.canOpenAccount -> onOpenAccount
            state.canReplaceReferenceImages -> replaceReference
            else -> retry
        },
        primaryEnabled = !state.removingMonitor,
        primaryTag = if (state.canOpenAccount) CameraTags.OPEN_ACCOUNT else CameraTags.RETRY,
        secondaryText = when {
            !state.retryable -> null
            state.canOpenAccount -> stringResource(R.string.action_try_again)
            state.canReplaceReferenceImages -> stringResource(R.string.action_try_again)
            else -> stringResource(R.string.action_back)
        },
        onSecondary = when {
            !state.retryable -> null
            state.canOpenAccount -> retry
            state.canReplaceReferenceImages -> retry
            else -> onBack
        },
        tone = ProductTone.ERROR,
    )
}

@Composable
private fun PermissionScreen(
    denied: Boolean,
    request: () -> Unit,
    openSettings: () -> Unit,
    onBack: () -> Unit,
) {
    ProductStateScreen(
        icon = if (denied) Icons.Outlined.LockOpen else Icons.Outlined.CameraAlt,
        title = stringResource(
            if (denied) R.string.camera_permission_required_title
            else R.string.camera_permission_request_title,
        ),
        body = if (denied) {
            stringResource(R.string.camera_permission_denied_body)
        } else {
            stringResource(R.string.camera_permission_request_body)
        },
        primaryText = stringResource(
            if (denied) R.string.action_allow_again else R.string.action_allow_camera,
        ),
        onPrimary = request,
        primaryTag = CameraTags.PERMISSION,
        secondaryText = stringResource(
            if (denied) R.string.action_open_system_settings else R.string.action_back,
        ),
        onSecondary = if (denied) openSettings else onBack,
    )
}

@Composable
private fun ProductStateScreen(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    tone: ProductTone = ProductTone.NEUTRAL,
    primaryEnabled: Boolean = true,
    primaryTag: String? = null,
    tertiaryText: String? = null,
    onTertiary: (() -> Unit)? = null,
) {
    Surface(Modifier.fillMaxSize(), color = ProductColors.Background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProductIconBadge(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = when (tone) {
                    ProductTone.ERROR -> ProductColors.Error
                    ProductTone.WAITING -> ProductColors.Amber
                    else -> ProductColors.Cyan
                },
                background = when (tone) {
                    ProductTone.ERROR -> ProductColors.ErrorSoft
                    ProductTone.WAITING -> ProductColors.AmberSoft
                    else -> ProductColors.CyanSoft
                },
            )
            Text(
                title,
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                modifier = Modifier.padding(top = 9.dp),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            ProductPrimaryButton(
                text = primaryText,
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp).height(54.dp)
                    .then(if (primaryTag != null) Modifier.testTag(primaryTag) else Modifier),
            )
            if (secondaryText != null && onSecondary != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp),
                ) { Text(secondaryText) }
            }
            if (tertiaryText != null && onTertiary != null) {
                androidx.compose.material3.TextButton(
                    onClick = onTertiary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text(tertiaryText) }
            }
        }
    }
}
