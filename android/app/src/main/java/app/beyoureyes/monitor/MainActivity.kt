package app.beyoureyes.monitor

import app.beyoureyes.monitor.service.monitoring.MonitoringService

import app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics
import app.beyoureyes.monitor.diagnostics.MonitoringHeartbeatStore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.monitor.app.navigation.BeYourEyeApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var cameraPermissionGranted by mutableStateOf(false)
    private var cameraPermissionDenied by mutableStateOf(false)
    private var notificationEventId by mutableStateOf<String?>(null)
    private var notificationMonitorId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptPasswordRecoveryIntent(intent)
        acceptNotificationIntent(intent)
        cameraPermissionGranted = hasCameraPermission()
        NotificationChannels.ensureCreated(this)
        setContent {
            val monitoringStatus by MonitoringRuntimeState.status.collectAsState()
            val latestObservationSnapshot by MonitoringRuntimeState.latestObservationSnapshot.collectAsState()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                cameraPermissionGranted = granted
                cameraPermissionDenied = !granted
            }
            BeYourEyeTheme {
                BeYourEyeApp(
                    container = appContainer,
                    monitoringStatus = monitoringStatus,
                    latestObservationSnapshot = latestObservationSnapshot,
                    cameraPermissionGranted = cameraPermissionGranted,
                    cameraPermissionDenied = cameraPermissionDenied,
                    onRequestCameraPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenAppSettings = ::openAppSettings,
                    onStartMonitoring = ::startFromVisibleActivity,
                    onStopMonitoring = ::stopMonitoring,
                    notificationEventId = notificationEventId,
                    onNotificationNavigationConsumed = { notificationEventId = null },
                    notificationMonitorId = notificationMonitorId,
                    onMonitorNavigationConsumed = { notificationMonitorId = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cameraPermissionGranted = hasCameraPermission()
        LocalNotificationWorkScheduler.enqueue(this)
        CloudBootstrap.enqueueForegroundSyncIfSignedIn(this)
        val signedIn = appContainer.accountController.state.value as? CloudAccountState.SignedIn
        if (signedIn == null) {
            appContainer.subscription.signOut()
        } else {
            lifecycleScope.launch { appContainer.subscription.refresh(signedIn.accountId) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptPasswordRecoveryIntent(intent)
        acceptNotificationIntent(intent)
    }

    override fun onStop() {
        // Leaving this app ends camera monitoring. Configuration recreation is still the
        // same visible app; navigating between its pages never reaches this callback.
        if (!isChangingConfigurations && MonitoringRuntimeState.status.value.phase != MonitoringPhase.STOPPED) {
            startService(MonitoringService.stopIntent(this, appHidden = true))
        }
        super.onStop()
    }

    private fun acceptPasswordRecoveryIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val callback = intent.dataString ?: return
        if (CloudBootstrap.accountController(this).acceptPasswordRecoveryCallback(callback)) {
            intent.data = null
        }
    }

    private fun acceptNotificationIntent(intent: Intent) {
        notificationEventId = intent.getStringExtra(NotificationChannels.EXTRA_EVENT_ID)
            ?.takeIf(String::isNotBlank)
        intent.removeExtra(NotificationChannels.EXTRA_EVENT_ID)
        intent.getStringExtra(NotificationChannels.EXTRA_MONITOR_ID)
            ?.takeIf(String::isNotBlank)
            ?.let { notificationMonitorId = it }
        intent.removeExtra(NotificationChannels.EXTRA_MONITOR_ID)
    }

    private fun startFromVisibleActivity(config: RuntimeCameraConfig): MonitoringStartResult {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return MonitoringStartResult.Rejected(getString(R.string.error_page_not_visible))
        }
        appContainer.productAccessRejection()?.let {
            return it
        }
        val current = MonitoringRuntimeState.status.value
        if (current.phase in setOf(MonitoringPhase.STARTING, MonitoringPhase.RUNNING)) {
            return if (current.monitorId == config.taskId) {
                MonitoringStartResult.Accepted
            } else {
                MonitoringStartResult.Rejected(getString(R.string.error_other_monitor_running))
            }
        }
        return runCatching {
            RuntimeDiagnostics.record(this, "monitoring_start_accepted")
            MonitoringHeartbeatStore(filesDir).begin(config.taskId, System.currentTimeMillis())
            MonitoringRuntimeState.setManualReadingScanRegion(config.manualReadingScanRegion)
            MonitoringRuntimeState.update(
                MonitoringPhase.STARTING,
                getString(R.string.status_starting_monitoring),
                monitorId = config.taskId,
            )
            ContextCompat.startForegroundService(this, MonitoringService.startIntent(this, config))
            MonitoringStartResult.Accepted
        }.getOrElse {
            MonitoringHeartbeatStore(filesDir).clear(config.taskId)
            MonitoringRuntimeState.update(
                MonitoringPhase.STOPPED,
                getString(R.string.error_camera_service_not_started),
                MonitoringHealth.FATAL,
            )
            MonitoringStartResult.Rejected(getString(R.string.error_camera_service_retry))
        }
    }

    private fun stopMonitoring() {
        runCatching { startService(MonitoringService.stopIntent(this)) }
            .onFailure {
                MonitoringRuntimeState.update(
                    MonitoringPhase.STOPPED,
                    getString(R.string.error_stop_failed_close_app),
                    MonitoringHealth.FATAL,
                )
            }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

}
