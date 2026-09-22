package app.beyoureyes.monitor.app.navigation

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.monitor.MonitoringObservationSnapshot
import app.beyoureyes.monitor.AppContainer
import app.beyoureyes.monitor.MonitoringStartResult
import app.beyoureyes.monitor.MonitoringStatus
import app.beyoureyes.monitor.RuntimeCameraConfig
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.activeMonitorId
import app.beyoureyes.monitor.feature.about.CommunityAboutScreen
import app.beyoureyes.monitor.feature.about.currentAppLanguageTag
import app.beyoureyes.monitor.feature.home.HomeScreen
import app.beyoureyes.monitor.feature.home.HomeViewModel
import app.beyoureyes.monitor.feature.history.EventHistoryScreen
import app.beyoureyes.monitor.feature.monitoring.ActiveMonitoringScreen
import app.beyoureyes.monitor.feature.monitoring.DirectStartScreen
import app.beyoureyes.monitor.feature.monitoring.MonitorCameraScreen
import app.beyoureyes.monitor.feature.monitoring.MonitorCameraViewModel
import app.beyoureyes.monitor.feature.monitoring.MonitorDetailScreen
import app.beyoureyes.monitor.feature.monitoring.MonitorDetailViewModel
import app.beyoureyes.monitor.feature.monitoring.MonitoringFailureScreen
import app.beyoureyes.monitor.feature.monitoring.OtherMonitorRunningScreen
import app.beyoureyes.monitor.feature.monitoring.ReferenceCameraScreen
import app.beyoureyes.monitor.feature.monitoring.TransientReadingCameraScreen
import app.beyoureyes.monitor.feature.monitoring.activeMonitoringSnapshot
import app.beyoureyes.monitor.feature.reference.ReferenceCreationScreen
import app.beyoureyes.monitor.feature.reference.ReferenceCreationViewModel
import app.beyoureyes.monitor.feature.reference.ReferenceDraft
import app.beyoureyes.monitor.feature.reference.ReferenceSetupViewModel
import app.beyoureyes.monitor.feature.reading.ReadingPreparation
import app.beyoureyes.monitor.feature.reading.ReadingTaskPersistence
import app.beyoureyes.monitor.feature.reading.TransientReadingSetupViewModel
import app.beyoureyes.core.data.MonitorStorageCodec
import app.beyoureyes.core.data.PersistedMonitor
import app.beyoureyes.core.data.StagedReferenceMonitor
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.monitor.feature.reference.ReferencePreparation
import app.beyoureyes.monitor.feature.reference.ReferenceTaskPersistence
import app.beyoureyes.monitor.feature.objectdetection.ObjectDetectionCreationScreen
import app.beyoureyes.monitor.feature.objectdetection.ObjectDetectionCreationViewModel
import app.beyoureyes.monitor.feature.objectdetection.ObjectDetectionModelBinding
import app.beyoureyes.monitor.feature.objectdetection.ObjectDetectionPreparation
import app.beyoureyes.monitor.feature.objectdetection.ObjectDetectionSetupViewModel
import app.beyoureyes.monitor.feature.objectdetection.ObjectDetectionTaskPersistence
import app.beyoureyes.monitor.feature.objectdetection.ObjectTargetCatalogProvider
import app.beyoureyes.monitor.design.localizedLabel

private const val HOME = "home"
private const val REFERENCE = "reference"
private const val REFERENCE_CAMERA = "reference-camera"
private const val REFERENCE_START = "reference-start"
private const val READING_SETUP = "reading-setup"
private const val OBJECT_DETECTION = "object-detection"
private const val OBJECT_DETECTION_CAMERA = "object-detection-camera"
private const val OBJECT_DETECTION_START = "object-detection-start"
private const val ABOUT = "about"
private const val HISTORY = "history"
private const val MONITOR_ID = "monitorId"
private const val CAMERA = "camera/{$MONITOR_ID}"
private const val DETAIL = "monitor/{$MONITOR_ID}"
private const val MONITOR_HISTORY = "history/monitor/{$MONITOR_ID}"
private val CAMERA_TRANSITION_ROUTES = setOf(
    CAMERA,
    READING_SETUP,
    REFERENCE_CAMERA,
    REFERENCE_START,
    OBJECT_DETECTION_CAMERA,
    OBJECT_DETECTION_START,
)

enum class MonitorRouteState { ACTIVE, BLOCKED_BY_OTHER, FAILED, SETUP }

internal fun monitorRouteState(status: MonitoringStatus, monitorId: String): MonitorRouteState {
    val active = status.phase in setOf(
        app.beyoureyes.monitor.MonitoringPhase.STARTING,
        app.beyoureyes.monitor.MonitoringPhase.RUNNING,
    )
    return when {
        active && status.monitorId == monitorId -> MonitorRouteState.ACTIVE
        active -> MonitorRouteState.BLOCKED_BY_OTHER
        status.health == app.beyoureyes.monitor.MonitoringHealth.FATAL && status.monitorId == monitorId ->
            MonitorRouteState.FAILED
        else -> MonitorRouteState.SETUP
    }
}

@Composable
internal fun BeYourEyeApp(
    container: AppContainer,
    monitoringStatus: MonitoringStatus,
    latestObservationSnapshot: MonitoringObservationSnapshot?,
    cameraPermissionGranted: Boolean,
    cameraPermissionDenied: Boolean,
    onRequestCameraPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartMonitoring: (RuntimeCameraConfig) -> MonitoringStartResult,
    onStopMonitoring: () -> Unit,
    openPeerAlerts: Boolean = false,
    onPeerNavigationConsumed: () -> Unit = {},
    notificationEventId: String? = null,
    onNotificationNavigationConsumed: () -> Unit = {},
    notificationMonitorId: String? = null,
    onMonitorNavigationConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val repositoryState by container.monitors.state.collectAsState()
    val objectTargetCatalogProvider = remember(container.objectCatalog) { container.objectCatalog }
    val monitoringActive = monitoringStatus.phase in setOf(
        app.beyoureyes.monitor.MonitoringPhase.STARTING,
        app.beyoureyes.monitor.MonitoringPhase.RUNNING,
    )

    fun openMainTab(tab: MainTab) {
        val route = when (tab) {
            MainTab.MONITORS -> HOME
            MainTab.HISTORY -> HISTORY
            MainTab.ABOUT -> ABOUT
        }
        nav.navigate(route) {
            popUpTo(HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun returnHome() {
        // Navigation/Lifecycle 只允许主线程访问；监控启动回调可能从挂起点直接
        // 恢复到后台线程（仪器测试环境已观测到崩溃），这里统一把导航收敛回主线程。
        val navigateHome = Runnable {
            if (!nav.popBackStack(HOME, inclusive = false)) {
                nav.navigate(HOME) { launchSingleTop = true }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            navigateHome.run()
        } else {
            Handler(Looper.getMainLooper()).post(navigateHome)
        }
    }

    LaunchedEffect(monitoringStatus.stoppedWhenHidden) {
        if (monitoringStatus.stoppedWhenHidden) returnHome()
    }



    LaunchedEffect(openPeerAlerts) {
        if (openPeerAlerts) { nav.navigate("paired-alerts"); onPeerNavigationConsumed() }
    }

    LaunchedEffect(notificationEventId) {
        if (notificationEventId != null) {
            openMainTab(MainTab.HISTORY)
            onNotificationNavigationConsumed()
        }
    }

    LaunchedEffect(notificationMonitorId) {
        if (notificationMonitorId != null) {
            nav.navigate("monitor/$notificationMonitorId") { launchSingleTop = true }
            onMonitorNavigationConsumed()
        }
    }







    NavHost(
        navController = nav,
        startDestination = HOME,
        enterTransition = {
            if (initialState.destination.route in CAMERA_TRANSITION_ROUTES ||
                targetState.destination.route in CAMERA_TRANSITION_ROUTES
            ) EnterTransition.None else {
                fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 24 }
            }
        },
        exitTransition = {
            if (initialState.destination.route in CAMERA_TRANSITION_ROUTES ||
                targetState.destination.route in CAMERA_TRANSITION_ROUTES
            ) ExitTransition.None else {
                fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 32 }
            }
        },
        popEnterTransition = {
            if (initialState.destination.route in CAMERA_TRANSITION_ROUTES ||
                targetState.destination.route in CAMERA_TRANSITION_ROUTES
            ) EnterTransition.None else {
                fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -it / 24 }
            }
        },
        popExitTransition = {
            if (initialState.destination.route in CAMERA_TRANSITION_ROUTES ||
                targetState.destination.route in CAMERA_TRANSITION_ROUTES
            ) ExitTransition.None else {
                fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 32 }
            }
        },
    ) {
        composable("paired-alerts") { app.beyoureyes.monitor.feature.peers.PeerScreen(onBack = { nav.popBackStack() }) }
        composable(HOME) {
            val vm: HomeViewModel = viewModel(factory = viewModelFactory {
                HomeViewModel(repository = container.monitors)
            })
            MainTabScaffold(MainTab.MONITORS, ::openMainTab) {
                HomeScreen(
                    viewModel = vm,
                    monitoringStatus = monitoringStatus,
                    latestObservation = latestObservationSnapshot?.takeIf {
                        it.monitorId == monitoringStatus.monitorId &&
                            it.monitorRevision == monitoringStatus.monitorRevision
                    }?.observation,
                    onReference = {
                        if (monitoringActive && monitoringStatus.monitorId != null) {
                            nav.navigate("camera/${monitoringStatus.monitorId}")
                        } else nav.navigate(REFERENCE)
                    },
                    onReading = { nav.navigate(READING_SETUP) },
                    onObjectDetection = { nav.navigate(OBJECT_DETECTION) },
                    onResumeReferenceDraft = { draftId ->
                        nav.navigate("$REFERENCE?draft=$draftId")
                    },
                    onOpenMonitor = { monitorId ->
                        if (monitorRouteState(monitoringStatus, monitorId) in setOf(
                                MonitorRouteState.ACTIVE,
                                MonitorRouteState.FAILED,
                            )
                        ) nav.navigate("camera/$monitorId") else nav.navigate("monitor/$monitorId")
                    },
                )
            }
        }


        composable(READING_SETUP) { entry ->

            val defaultReadingMonitorName = stringResource(R.string.default_reading_monitor_name)
            val vm: TransientReadingSetupViewModel = viewModel(factory = viewModelFactory {
                TransientReadingSetupViewModel(
                    initialNotificationsEnabled = false,
                    monitorName = defaultReadingMonitorName,
                    preparation = ReadingPreparation { taskId, onProgress ->
                        container.modelPreparation.prepareTransientReading(
                            taskId = taskId,
                            onProgress = onProgress,
                            requiredModelProfileKey = null,
                            requiredPackageId = null,
                            requiredIntentKey = null,
                        )
                    },
                    persistence = object : ReadingTaskPersistence {
                        override suspend fun persist(
                            taskId: String,
                            rule: MonitorRule.ReadingThreshold,
                            targetConfig: app.beyoureyes.core.domain.ReadingTargetConfig,
                            resolvedSamplingConfig: app.beyoureyes.core.data.ResolvedSamplingConfig,
                            nowEpochMillis: Long,
                        ): PersistedMonitor {
                            return container.monitors.createConfiguredReadingMonitor(
                                taskId = taskId,
                                requestedName = defaultReadingMonitorName,
                                rule = rule,
                                targetConfig = targetConfig,
                                resolvedSamplingConfig = resolvedSamplingConfig,
                                nowEpochMillis = nowEpochMillis,
                            )
                        }

                        override suspend fun persistPending(
                            taskId: String,
                            targetConfig: app.beyoureyes.core.domain.ReadingTargetConfig,
                            resolvedSamplingConfig: app.beyoureyes.core.data.ResolvedSamplingConfig,
                            nowEpochMillis: Long,
                        ): PersistedMonitor {
                            return container.monitors.createPendingReadingMonitor(
                                taskId = taskId,
                                requestedName = defaultReadingMonitorName,
                                targetConfig = targetConfig,
                                resolvedSamplingConfig = resolvedSamplingConfig,
                                nowEpochMillis = nowEpochMillis,
                            )
                        }

                        override suspend fun revise(
                            taskId: String,
                            expectedRevision: Long,
                            rule: MonitorRule.ReadingThreshold,
                            targetConfig: app.beyoureyes.core.domain.ReadingTargetConfig,
                        ) = container.monitors.configureReading(taskId, expectedRevision, rule, targetConfig)

                        override suspend fun setNotificationsEnabled(taskId: String, enabled: Boolean) {
                            container.monitors.setNotificationsEnabled(taskId, enabled)
                        }
                    },
                    diagnosticEvent = container.diagnosticEvent,
                    diagnosticFailure = container.diagnosticFailure,
                )
            })
            TransientReadingCameraScreen(
                viewModel = vm,
                initialCondition = null,
                repositoryState = repositoryState,
                monitoringStatus = monitoringStatus,
                latestObservationSnapshot = latestObservationSnapshot,
                cameraPermissionGranted = cameraPermissionGranted,
                cameraPermissionDenied = cameraPermissionDenied,
                onRequestCameraPermission = onRequestCameraPermission,
                onOpenAppSettings = onOpenAppSettings,
                onStartMonitoring = onStartMonitoring,
                onStopMonitoring = onStopMonitoring,
                onBack = { vm.leave(::returnHome) },
                onMonitoringStarted = ::returnHome,
            )
        }
        composable(OBJECT_DETECTION) { entry ->
            val languageTag = currentAppLanguageTag(LocalContext.current)

            val vm: ObjectDetectionCreationViewModel = viewModel(factory = viewModelFactory {
                ObjectDetectionCreationViewModel(
                    catalogProvider = objectTargetCatalogProvider,
                    initialTargetId = null,
                    initialTargetQuery = null,
                    languageTag = languageTag,
                    initialModelBinding = null,
                    initialRule = MonitorRule.TargetPresence(),
                    initialNotificationsEnabled = false,
                )
            })
            ObjectDetectionCreationScreen(
                viewModel = vm,
                onBack = nav::popBackStack,
                onOpenCamera = { nav.navigate(OBJECT_DETECTION_CAMERA) },
                onStartDirectly = { nav.navigate(OBJECT_DETECTION_START) },
            )
        }
        composable(OBJECT_DETECTION_CAMERA) { cameraEntry ->
            val creationEntry = remember(cameraEntry) { nav.getBackStackEntry(OBJECT_DETECTION) }
            ObjectDetectionSetupContent(
                ownerEntry = cameraEntry,
                creationEntry = creationEntry,
                container = container,
                objectTargetCatalogProvider = objectTargetCatalogProvider,
                onRecoveryExit = ::returnHome,
                onMissingTarget = { nav.popBackStack(OBJECT_DETECTION, false) },
            ) { vm, creationVm ->
                ReferenceCameraScreen(
                    viewModel = vm,
                    repositoryState = repositoryState,
                    monitoringStatus = monitoringStatus,
                    latestObservationSnapshot = latestObservationSnapshot,
                    cameraPermissionGranted = cameraPermissionGranted,
                    cameraPermissionDenied = cameraPermissionDenied,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onStartMonitoring = onStartMonitoring,
                    onStopMonitoring = onStopMonitoring,
                    onBack = {
                        creationVm.resetCameraRequest()
                        vm.leave { nav.popBackStack(OBJECT_DETECTION, false) }
                    },
                    onReferencePersisted = {},
                    onMonitoringStarted = ::returnHome,
                    onNotificationPreferenceChanged = creationVm::setNotificationsEnabled,
                )
            }
        }
        composable(OBJECT_DETECTION_START) { startEntry ->
            val creationEntry = remember(startEntry) { nav.getBackStackEntry(OBJECT_DETECTION) }
            ObjectDetectionSetupContent(
                ownerEntry = startEntry,
                creationEntry = creationEntry,
                container = container,
                objectTargetCatalogProvider = objectTargetCatalogProvider,
                onRecoveryExit = ::returnHome,
                onMissingTarget = { nav.popBackStack(OBJECT_DETECTION, false) },
            ) { vm, creationVm ->
                DirectStartScreen(
                    viewModel = vm,
                    cameraPermissionGranted = cameraPermissionGranted,
                    cameraPermissionDenied = cameraPermissionDenied,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onStartMonitoring = onStartMonitoring,
                    onStarted = ::returnHome,
                    onBack = {
                        creationVm.resetCameraRequest()
                        vm.leave { nav.popBackStack(OBJECT_DETECTION, false) }
                    },
                )
            }
        }
        composable(
            route = "$REFERENCE?draft={draft}",
            arguments = listOf(navArgument("draft") { defaultValue = "" }),
        ) { entry ->
            // 首页草稿卡片恢复：把发现的草稿会话播种给创建页，避免覆盖进行中的会话。
            entry.savedStateHandle.get<String>("draft")?.takeIf(String::isNotBlank)?.let { draftId ->
                if (entry.savedStateHandle.get<String>(
                        ReferenceCreationViewModel.DRAFT_SESSION_KEY,
                    ) == null
                ) {
                    entry.savedStateHandle[ReferenceCreationViewModel.DRAFT_SESSION_KEY] = draftId
                }
            }

            val vm: ReferenceCreationViewModel = viewModel(factory = viewModelFactory {
                ReferenceCreationViewModel(
                    repository = container.monitors,
                    savedStateHandle = entry.savedStateHandle,
                    initialName = "",
                    initialRule = MonitorRule.TargetPresence(),
                    initialNotificationsEnabled = false,
                    requiredModelProfileKey = null,
                    requiredPackageId = null,
                    requiredIntentKey = null,
                )
            })
            val requested by vm.cameraRequested.collectAsState()
            LaunchedEffect(requested) {
                if (requested) {
                    nav.navigate(REFERENCE_CAMERA)
                    vm.acknowledgeCameraRequest()
                }
            }
            val startRequested by vm.startRequested.collectAsState()
            LaunchedEffect(startRequested) {
                if (startRequested) {
                    nav.navigate(REFERENCE_START)
                    vm.acknowledgeStartRequest()
                }
            }
            ReferenceCreationScreen(vm) { nav.popBackStack() }
        }
        composable(REFERENCE_CAMERA) { cameraEntry ->
            val materialsEntry = remember(cameraEntry) { nav.getBackStackEntry(REFERENCE) }
            ReferenceSetupContent(
                ownerEntry = cameraEntry,
                materialsEntry = materialsEntry,
                container = container,
                onRecoveryExit = ::returnHome,
                onMissingDraft = { nav.popBackStack(REFERENCE, false) },
            ) { vm, materialsVm ->
                ReferenceCameraScreen(
                    viewModel = vm,
                    repositoryState = repositoryState,
                    monitoringStatus = monitoringStatus,
                    latestObservationSnapshot = latestObservationSnapshot,
                    cameraPermissionGranted = cameraPermissionGranted,
                    cameraPermissionDenied = cameraPermissionDenied,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onStartMonitoring = onStartMonitoring,
                    onStopMonitoring = onStopMonitoring,
                    onBack = {
                        vm.leave {
                            materialsVm.resumeAfterCamera()
                            nav.popBackStack()
                        }
                    },
                    onReferencePersisted = materialsVm::completeCameraCreation,
                    onMonitoringStarted = ::returnHome,
                    onNotificationPreferenceChanged = materialsVm::setNotificationsEnabled,
                )
            }
        }
        composable(REFERENCE_START) { startEntry ->
            val materialsEntry = remember(startEntry) { nav.getBackStackEntry(REFERENCE) }
            ReferenceSetupContent(
                ownerEntry = startEntry,
                materialsEntry = materialsEntry,
                container = container,
                onRecoveryExit = ::returnHome,
                onMissingDraft = { nav.popBackStack(REFERENCE, false) },
            ) { vm, materialsVm ->
                DirectStartScreen(
                    viewModel = vm,
                    cameraPermissionGranted = cameraPermissionGranted,
                    cameraPermissionDenied = cameraPermissionDenied,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onStartMonitoring = onStartMonitoring,
                    onStarted = {
                        materialsVm.completeCameraCreation()
                        returnHome()
                    },
                    onBack = {
                        vm.leave {
                            materialsVm.resumeAfterCamera()
                            nav.popBackStack()
                        }
                    },
                )
            }
        }
        composable(ABOUT) {
            MainTabScaffold(MainTab.ABOUT, ::openMainTab) {
                CommunityAboutScreen(
                    onPairedAlerts = { nav.navigate("paired-alerts") },
                    showBackButton = false,
                    onClose = { openMainTab(MainTab.MONITORS) },
                )
            }
        }
        composable(HISTORY) {
            MainTabScaffold(MainTab.HISTORY, ::openMainTab) {
                EventHistoryScreen(
                    state = repositoryState,
                    onCreateMonitor = { openMainTab(MainTab.MONITORS) },
                )
            }
        }
        composable(MONITOR_HISTORY, arguments = listOf(navArgument(MONITOR_ID) { type = NavType.StringType })) { entry ->
            val id = checkNotNull(entry.arguments?.getString(MONITOR_ID))
            EventHistoryScreen(
                state = repositoryState,
                showBackButton = true,
                onBack = nav::popBackStack,
                monitorId = id,
            )
        }
        composable(DETAIL, arguments = listOf(navArgument(MONITOR_ID) { type = NavType.StringType })) { entry ->
            val id = checkNotNull(entry.arguments?.getString(MONITOR_ID))
            val vm: MonitorDetailViewModel = viewModel(key = "detail-$id", factory = viewModelFactory {
                MonitorDetailViewModel(id, container.monitors, container.samplingResolver)
            })
            MonitorDetailScreen(
                viewModel = vm,
                activeMonitorId = monitoringStatus.activeMonitorId,
                cameraPermissionGranted = cameraPermissionGranted,
                onRequestCameraPermission = onRequestCameraPermission,
                onStartMonitoring = onStartMonitoring,
                onBack = nav::popBackStack,
                onOpenHistory = { nav.navigate("history/monitor/$id") { launchSingleTop = true } },
                onDeleted = {
                    container.clearMonitoringHeartbeat(id)
                    app.beyoureyes.monitor.MonitoringRuntimeState.clearFatalForDeletedMonitor(id)
                    returnHome()
                },
                onOpenCamera = {
                    app.beyoureyes.monitor.MonitoringRuntimeState.acknowledgeHiddenStop()
                    nav.navigate("camera/$it")
                },
                onConfirmReadingBaseline = { monitorId ->
                    if (monitoringStatus.activeMonitorId == monitorId) onStopMonitoring()
                    // The camera route waits for STOPPED before mounting setup, so the
                    // service releases CameraX before baseline confirmation takes ownership.
                    app.beyoureyes.monitor.MonitoringRuntimeState.acknowledgeHiddenStop()
                    nav.navigate("camera/$monitorId")
                },
            )
        }
        composable(CAMERA, arguments = listOf(navArgument(MONITOR_ID) { type = NavType.StringType })) { entry ->
            val id = checkNotNull(entry.arguments?.getString(MONITOR_ID))
            when (monitorRouteState(monitoringStatus, id)) {
                MonitorRouteState.ACTIVE -> {
                    val monitor = repositoryState.local.singleOrNull { it.monitor.id == id }?.monitor
                    val active = activeMonitoringSnapshot(repositoryState, id, monitor?.revision, monitoringStatus, latestObservationSnapshot)
                    ActiveMonitoringScreen(
                        name = monitor?.name ?: stringResource(R.string.monitor_running_fallback),
                        kind = monitor?.kind,
                        rule = monitor?.rule,
                        targetLabel = (monitor?.target as? MonitorTarget.ObjectClass)?.localizedLabel(),
                        confirmedReadingFormat = (monitor?.target as? MonitorTarget.NumericReading)?.confirmedFormat,
                        latestReading = active.latestReading,
                        latestObservation = active.latestObservation,
                        observationSnapshot = active.observationSnapshot,
                        eventCount = active.eventCount,
                        lastEventText = active.lastEventText,
                        status = monitoringStatus,
                        onDetails = { nav.navigate("monitor/$id") },
                        onOpenHistory = { nav.navigate("history/monitor/$id") { launchSingleTop = true } },
                        onStop = { returnHome(); onStopMonitoring() },
                        onBack = ::returnHome,
                    )
                }
                MonitorRouteState.BLOCKED_BY_OTHER -> OtherMonitorRunningScreen(
                    onOpenActive = {
                        nav.navigate("camera/${monitoringStatus.monitorId}") {
                            popUpTo("camera/$id") { inclusive = true }
                        }
                    },
                    onBack = nav::popBackStack,
                )
                MonitorRouteState.FAILED -> MonitoringFailureScreen(
                    message = monitoringStatus.message ?: stringResource(R.string.monitoring_stopped_body),
                    onRetry = { app.beyoureyes.monitor.MonitoringRuntimeState.update(app.beyoureyes.monitor.MonitoringPhase.STOPPED) },
                    onDetails = { nav.navigate("monitor/$id") },
                    onBack = {
                        app.beyoureyes.monitor.MonitoringRuntimeState.update(app.beyoureyes.monitor.MonitoringPhase.STOPPED)
                        returnHome()
                    },
                )
                MonitorRouteState.SETUP -> if (!monitoringStatus.stoppedWhenHidden) {
                    val vm: MonitorCameraViewModel = viewModel(key = "camera-$id", factory = viewModelFactory {
                        MonitorCameraViewModel(
                            id,
                            container.monitors,
                            container.modelPreparation,
                            container.samplingResolver,
                            container.diagnosticEvent,
                            container.diagnosticFailure,
                            container::clearMonitoringHeartbeat,
                        )
                    })
                    MonitorCameraScreen(
                        viewModel = vm,
                        repositoryState = repositoryState,
                        monitoringStatus = monitoringStatus,
                        latestObservationSnapshot = latestObservationSnapshot,
                        cameraPermissionGranted = cameraPermissionGranted,
                        cameraPermissionDenied = cameraPermissionDenied,
                        onRequestCameraPermission = onRequestCameraPermission,
                        onOpenAppSettings = onOpenAppSettings,
                        onStartMonitoring = onStartMonitoring,
                        onStopMonitoring = onStopMonitoring,
                        onReplaceReference = {
                            nav.navigate(REFERENCE) { popUpTo("camera/$id") { inclusive = true } }
                        },
                        onBack = { vm.leave(::returnHome) },
                    )
                }
            }
        }
    }
}

// 文字描述（测试识别相机页与直接启动页）共用的装配：恢复助手提案、解析创建状态、
// 准备运行模型并构造持久化。两处入口只差最终画面，接线只保留一份。
@Composable
private fun ObjectDetectionSetupContent(
    ownerEntry: NavBackStackEntry,
    creationEntry: NavBackStackEntry,
    container: AppContainer,
    objectTargetCatalogProvider: ObjectTargetCatalogProvider,
    onRecoveryExit: () -> Unit,
    onMissingTarget: () -> Unit,
    content: @Composable (ObjectDetectionSetupViewModel, ObjectDetectionCreationViewModel) -> Unit,
) {
    val languageTag = currentAppLanguageTag(LocalContext.current)

    val creationVm: ObjectDetectionCreationViewModel = viewModel(
        viewModelStoreOwner = creationEntry,
        factory = viewModelFactory {
            ObjectDetectionCreationViewModel(
                catalogProvider = objectTargetCatalogProvider,
                initialTargetId = null,
                initialTargetQuery = null,
                languageTag = languageTag,
                initialModelBinding = null,
                initialRule = MonitorRule.TargetPresence(),
            )
        },
    )
    val target = creationVm.target() ?: run {
        LaunchedEffect(Unit) { onMissingTarget() }
        return
    }
    val targetLabel = target.localizedLabel(currentAppLanguageTag(LocalContext.current))
    val defaultObjectMonitorName = stringResource(
        R.string.default_object_monitor_name,
        targetLabel,
    )
    val profile = app.beyoureyes.core.vision.TargetProfile.ObjectClass(
        targetId = target.targetId,
        labelZhCn = target.labelZhCn,
        labelEn = target.labelEn,
        labels = target.labels,
    )
    val vm: ObjectDetectionSetupViewModel = viewModel(
        viewModelStoreOwner = ownerEntry,
        factory = viewModelFactory {
            ObjectDetectionSetupViewModel(
                target = target,
                monitorName = defaultObjectMonitorName,
                rule = creationVm.presenceRule(),
                initialNotificationsEnabled = creationVm.notificationsEnabled(),
                preparation = ObjectDetectionPreparation { taskId, _, onProgress ->
                    val binding = creationVm.modelBinding()
                    if (binding == null) {
                        container.modelPreparation.prepareTransientObject(
                            taskId,
                            profile,
                            onProgress,
                        )
                    } else {
                        container.modelPreparation.prepareTransientObject(
                            taskId = taskId,
                            targetProfile = profile,
                            onProgress = onProgress,
                            requiredModelProfileKey = binding.modelProfileKey,
                            requiredPackageId = binding.packageId,
                            requiredIntentKey = binding.intentKey,
                        )
                    }
                },
                persistence = object : ObjectDetectionTaskPersistence {
                    override suspend fun persist(
                        taskId: String,
                        target: MonitorTarget.ObjectClass,
                        rule: MonitorRule.TargetPresence,
                        resolvedSamplingConfig: app.beyoureyes.core.data.ResolvedSamplingConfig,
                        nowEpochMillis: Long,
                    ): PersistedMonitor {
                        return container.monitors.createConfiguredObjectMonitor(
                            taskId = taskId,
                            requestedName = defaultObjectMonitorName,
                            target = target,
                            rule = rule,
                            resolvedSamplingConfig = resolvedSamplingConfig,
                            nowEpochMillis = nowEpochMillis,
                        )
                    }

                    override suspend fun setNotificationsEnabled(taskId: String, enabled: Boolean) {
                        container.monitors.setNotificationsEnabled(taskId, enabled)
                    }
                },
                diagnosticEvent = container.diagnosticEvent,
                diagnosticFailure = container.diagnosticFailure,
            )
        },
    )
    content(vm, creationVm)
}

// 参考图片（测试识别相机页与直接启动页）共用的装配：恢复助手提案、解析草稿会话、
// 准备运行模型并构造暂存/提交持久化。两处入口只差最终画面，接线只保留一份。
@Composable
private fun ReferenceSetupContent(
    ownerEntry: NavBackStackEntry,
    materialsEntry: NavBackStackEntry,
    container: AppContainer,
    onRecoveryExit: () -> Unit,
    onMissingDraft: () -> Unit,
    content: @Composable (ReferenceSetupViewModel, ReferenceCreationViewModel) -> Unit,
) {

    val materialsVm: ReferenceCreationViewModel = viewModel(
        viewModelStoreOwner = materialsEntry,
        factory = viewModelFactory {
            ReferenceCreationViewModel(
                repository = container.monitors,
                savedStateHandle = materialsEntry.savedStateHandle,
                initialName = "",
                initialRule = MonitorRule.TargetPresence(),
                initialNotificationsEnabled = false,
                requiredModelProfileKey = null,
                requiredPackageId = null,
                requiredIntentKey = null,
            )
        },
    )
    val draft = materialsVm.draft() ?: run {
        LaunchedEffect(Unit) { onMissingDraft() }
        return
    }
    val defaultReferenceMonitorName = stringResource(
        R.string.default_reference_monitor_name,
    )
    val vm: ReferenceSetupViewModel = viewModel(
        viewModelStoreOwner = ownerEntry,
        factory = viewModelFactory {
            ReferenceSetupViewModel(
                draft = draft,
                preparation = ReferencePreparation { stagedTask, onProgress ->
                    container.modelPreparation.prepareTransientReference(
                        stagedTask = stagedTask,
                        onProgress = onProgress,
                        requiredModelProfileKey = draft.requiredModelProfileKey,
                        requiredPackageId = draft.requiredPackageId,
                        requiredIntentKey = draft.requiredIntentKey,
                    )
                },
                persistence = object : ReferenceTaskPersistence {
                    override suspend fun stage(
                        taskId: String,
                        requestedName: String,
                        materials: List<app.beyoureyes.core.domain.ReferenceMaterial>,
                        rule: MonitorRule.TargetPresence,
                        nowEpochMillis: Long,
                    ) = run {
                        val staged = container.monitors.stageReferenceMonitor(
                            taskId = taskId,
                            requestedName = requestedName.ifBlank {
                                defaultReferenceMonitorName
                            },
                            materials = materials,
                            rule = rule,
                            nowEpochMillis = nowEpochMillis,
                        )
                        val decoded = MonitorStorageCodec.decode(staged.task)
                        app.beyoureyes.monitor.feature.reference.ReferenceStage(
                            task = staged.task,
                            preview = PersistedMonitor(decoded.monitor, null),
                            repositoryHandle = staged,
                        )
                    }

                    override suspend fun commit(
                        stage: app.beyoureyes.monitor.feature.reference.ReferenceStage,
                        resolvedSamplingConfig: app.beyoureyes.core.data.ResolvedSamplingConfig,
                    ): PersistedMonitor {
                        return container.monitors.createConfiguredReferenceMonitor(
                            stage.repositoryHandle as StagedReferenceMonitor,
                            resolvedSamplingConfig,
                        )
                    }

                    override suspend fun discard(stage: app.beyoureyes.monitor.feature.reference.ReferenceStage) {
                        container.monitors.discardStagedReferenceMonitor(
                            stage.repositoryHandle as StagedReferenceMonitor,
                        )
                    }

                    override suspend fun setNotificationsEnabled(taskId: String, enabled: Boolean) {
                        container.monitors.setNotificationsEnabled(taskId, enabled)
                    }
                },
                diagnosticEvent = container.diagnosticEvent,
                diagnosticFailure = container.diagnosticFailure,
            )
        },
    )
    content(vm, materialsVm)
}
