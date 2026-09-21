package app.beyoureyes.monitor.feature.monitoring

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.beyoureyes.monitor.FULL_FRAME_MONITOR_REGION
import app.beyoureyes.monitor.MonitoringStartResult
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.RuntimeCameraConfig
import app.beyoureyes.monitor.currentCameraTargetRotation
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.resolve
import kotlin.math.roundToInt

internal object DirectStartTags {
    const val SCREEN = "direct_start_screen"
    const val RETRY = "direct_start_retry"
    const val PERMISSION = "direct_start_permission"
}

/**
 * 「保存并开始监控」的承载页：复用与测试识别完全相同的模型包准备、持久化和启动路径，
 * 只是不打开相机预览。配置完整即可创建并启动，目标不需要在场。
 */
@Composable
internal fun DirectStartScreen(
    viewModel: FieldSetupController,
    cameraPermissionGranted: Boolean,
    cameraPermissionDenied: Boolean,
    onRequestCameraPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartMonitoring: (RuntimeCameraConfig) -> MonitoringStartResult,
    onCheckProductAccess: () -> MonitoringStartResult.Rejected?,
    onStarted: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsState()
    var startError by remember { mutableStateOf<String?>(null) }
    var startCanOpenAccount by remember { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    var attemptNonce by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val windowView = LocalView.current.rootView
    var notificationPermissionCompleted by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setNotificationsEnabled(granted)
        notificationPermissionCompleted = true
    }

    val saveFailedMessage = stringResource(R.string.error_monitor_save_failed)
    val ready = state as? MonitorCameraState.Ready
    LaunchedEffect(
        cameraPermissionGranted,
        ready?.persisted?.monitor?.id,
        ready?.persisted?.monitor?.revision,
        attemptNonce,
        notificationPermissionCompleted,
    ) {
        val current = ready ?: return@LaunchedEffect
        if (!cameraPermissionGranted || started) return@LaunchedEffect
        val rejection = onCheckProductAccess()
        if (rejection != null) {
            startError = rejection.message
            startCanOpenAccount = rejection.canOpenAccount
            return@LaunchedEffect
        }
        if (current.notificationsEnabled && !notificationPermissionCompleted &&
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return@LaunchedEffect
        }
        val persistedReady = viewModel.persist()
        if (persistedReady == null ||
            persistedReady.persisted.monitor.id != current.persisted.monitor.id
        ) {
            if (startError == null) startError = saveFailedMessage
            return@LaunchedEffect
        }
        val targetRotation = currentCameraTargetRotation(context)
        val config = RuntimeCameraConfig(
            taskId = persistedReady.persisted.monitor.id,
            taskRevision = persistedReady.persisted.monitor.revision,
            roi = FULL_FRAME_MONITOR_REGION,
            viewPortWidth = windowView.width,
            viewPortHeight = (windowView.height * CAMERA_VIEWPORT_FRACTION).roundToInt(),
            targetRotation = targetRotation,
            resolvedSamplingConfig = persistedReady.runtime.config,
        )
        when (val result = onStartMonitoring(config)) {
            is MonitoringStartResult.Accepted -> {
                started = true
                onStarted()
            }
            is MonitoringStartResult.Rejected -> {
                startError = result.message
                startCanOpenAccount = result.canOpenAccount
            }
        }
    }

    when {
        !cameraPermissionGranted -> DirectStartPermissionScreen(
            denied = cameraPermissionDenied,
            request = onRequestCameraPermission,
            openSettings = onOpenAppSettings,
            onBack = onBack,
        )
        startError != null -> DirectStartErrorScreen(
            message = startError.orEmpty(),
            canOpenAccount = startCanOpenAccount,
            onRetry = {
                startError = null
                startCanOpenAccount = false
                attemptNonce += 1
            },
            onOpenAccount = onOpenAccount,
            onBack = onBack,
        )
        state is MonitorCameraState.Error -> DirectStartErrorScreen(
            message = (state as MonitorCameraState.Error).message.resolve(),
            canOpenAccount = (state as MonitorCameraState.Error).canOpenAccount,
            onRetry = viewModel::retry,
            onOpenAccount = onOpenAccount,
            onBack = onBack,
        )
        state is MonitorCameraState.Loading -> LoadingScreen(
            state = state as MonitorCameraState.Loading,
            onBack = onBack,
        )
        else -> DirectStartProgressScreen(
            message = stringResource(R.string.direct_start_starting),
        )
    }
}

@Composable
private fun DirectStartProgressScreen(message: String) {
    Surface(
        Modifier.fillMaxSize().testTag(DirectStartTags.SCREEN),
        color = ProductColors.Background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), color = ProductColors.CyanSoft) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ProductColors.Cyan)
                }
            }
            Text(
                stringResource(R.string.direct_start_title),
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                message,
                modifier = Modifier.padding(top = 8.dp),
                color = ProductColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DirectStartErrorScreen(
    message: String,
    canOpenAccount: Boolean,
    onRetry: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = ProductColors.Background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), color = ProductColors.AmberSoft) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = ProductColors.Amber,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Text(
                stringResource(R.string.direct_start_failed_title),
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                message,
                modifier = Modifier.padding(top = 8.dp),
                color = ProductColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            ProductPrimaryButton(
                text = stringResource(
                    if (canOpenAccount) R.string.action_open_account else R.string.action_retry,
                ),
                onClick = if (canOpenAccount) onOpenAccount else onRetry,
                modifier = Modifier.padding(top = 24.dp).testTag(DirectStartTags.RETRY),
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}

@Composable
private fun DirectStartPermissionScreen(
    denied: Boolean,
    request: () -> Unit,
    openSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = ProductColors.Background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), color = ProductColors.CyanSoft) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        if (denied) Icons.Outlined.LockOpen else Icons.Outlined.CameraAlt,
                        contentDescription = null,
                        tint = ProductColors.Cyan,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Text(
                stringResource(
                    if (denied) R.string.camera_permission_required_title
                    else R.string.camera_permission_request_title,
                ),
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(
                    if (denied) R.string.camera_permission_denied_body
                    else R.string.camera_permission_request_body,
                ),
                modifier = Modifier.padding(top = 8.dp),
                color = ProductColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            ProductPrimaryButton(
                text = stringResource(
                    if (denied) R.string.action_allow_again else R.string.action_allow_camera,
                ),
                onClick = request,
                modifier = Modifier.padding(top = 24.dp).testTag(DirectStartTags.PERMISSION),
            )
            OutlinedButton(
                onClick = if (denied) openSettings else onBack,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    stringResource(
                        if (denied) R.string.action_open_system_settings else R.string.action_back,
                    ),
                )
            }
        }
    }
}
