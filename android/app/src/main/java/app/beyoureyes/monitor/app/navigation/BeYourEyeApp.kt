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
import app.beyoureyes.monitor.feature.account.AccountScreen
import app.beyoureyes.monitor.feature.account.currentAppLanguageTag
import app.beyoureyes.monitor.feature.assistant.AssistantProposalKind
import app.beyoureyes.monitor.feature.assistant.AssistantProposalRecoveryScreen
import app.beyoureyes.monitor.feature.assistant.AssistantProposalRecoveryState
import app.beyoureyes.monitor.feature.assistant.AssistantProposalRecoveryViewModel
import app.beyoureyes.monitor.feature.assistant.AssistantProposalSavedState
import app.beyoureyes.monitor.feature.assistant.AssistantScreen
import app.beyoureyes.monitor.feature.assistant.AssistantViewModel
import app.beyoureyes.monitor.feature.assistant.MonitorConfigurationProposal
import app.beyoureyes.monitor.feature.assistant.objectClassDefinitions
import app.beyoureyes.monitor.feature.assistant.toReadingConditionDraft
import app.beyoureyes.monitor.feature.assistant.toMonitorRule
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
import app.beyoureyes.monitor.feature.subscription.ProductAccessState
import app.beyoureyes.monitor.feature.subscription.SubscriptionWallScreen
import app.beyoureyes.monitor.design.localizedLabel

private const val HOME = "home"
private const val ASSISTANT = "assistant"
private const val ASSISTANT_ACCOUNT = "assistant-account"
private const val SETUP_ACCOUNT = "setup-account"
private const val REFERENCE = "reference"
private const val REFERENCE_CAMERA = "reference-camera"
private const val REFERENCE_START = "reference-start"
private const val READING_SETUP = "reading-setup"
private const val OBJECT_DETECTION = "object-detection"
private const val OBJECT_DETECTION_CAMERA = "object-detection-camera"
private const val OBJECT_DETECTION_START = "object-detection-start"
private const val ACCOUNT = "account"
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
    SETUP_ACCOUNT,
)

enum class MonitorRouteState { ACTIVE, BLOCKED_BY_OTHER, FAILED, SETUP }
private enum class LockedSurface { PAYWALL, ACCOUNT, HISTORY }

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
    notificationEventId: String? = null,
    onNotificationNavigationConsumed: () -> Unit = {},
    notificationMonitorId: String? = null,
    onMonitorNavigationConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val repositoryState by container.monitors.state.collectAsState()
    val remoteSnapshotStates by container.remoteSnapshots.states.collectAsState()
    val accountState by container.accountController.state.collectAsState()
    val productAccessState by container.productAccess.collectAsState()
    val localUseGranted = app.beyoureyes.monitor.BuildConfig.COMMUNITY_BUILD ||
        productAccessState is ProductAccessState.Granted
    val passwordRecoveryPending by container.accountController.passwordRecoveryPending.collectAsState()
    var lockedSurface by rememberSaveable { mutableStateOf(LockedSurface.PAYWALL) }
    val objectTargetCatalogProvider = remember(container.assistantCatalog) {
        ObjectTargetCatalogProvider {
            container.assistantCatalog.load().objectClassDefinitions()
        }
    }
    val monitoringActive = monitoringStatus.phase in setOf(
        app.beyoureyes.monitor.MonitoringPhase.STARTING,
        app.beyoureyes.monitor.MonitoringPhase.RUNNING,
    )

    fun openMainTab(tab: MainTab) {
        val route = when (tab) {
            MainTab.MONITORS -> HOME
            MainTab.HISTORY -> HISTORY
            MainTab.ACCOUNT -> ACCOUNT
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

    LaunchedEffect(passwordRecoveryPending, productAccessState) {
        if (passwordRecoveryPending) {
            if (localUseGranted) {
                nav.navigate(ACCOUNT) { launchSingleTop = true }
            } else {
                lockedSurface = LockedSurface.ACCOUNT
            }
        }
    }

    LaunchedEffect(notificationEventId, productAccessState) {
        if (notificationEventId != null) {
            if (localUseGranted) {
                openMainTab(MainTab.HISTORY)
            } else {
                lockedSurface = LockedSurface.HISTORY
            }
            onNotificationNavigationConsumed()
        }
    }

    LaunchedEffect(notificationMonitorId, productAccessState) {
        if (notificationMonitorId != null) {
            if (localUseGranted) {
                nav.navigate("monitor/$notificationMonitorId") { launchSingleTop = true }
            }
            onMonitorNavigationConsumed()
        }
    }

    LaunchedEffect(accountState) {
        val signedIn = accountState as? app.beyoureyes.core.data.cloud.CloudAccountState.SignedIn
        if (signedIn == null) {
            container.subscription.signOut()
        } else {
            container.subscription.refresh(signedIn.accountId)
        }
    }

    LaunchedEffect(productAccessState) {
        if (!localUseGranted && (productAccessState is ProductAccessState.SignedOut ||
            productAccessState is ProductAccessState.Locked)
        ) {
            if (monitoringActive) onStopMonitoring()
        }
    }

    if (!localUseGranted) {
        when (lockedSurface) {
            LockedSurface.PAYWALL -> SubscriptionWallScreen(
                accessState = productAccessState,
                accountState = accountState,
                playSubscription = container.subscription,
                onOpenAccount = { lockedSurface = LockedSurface.ACCOUNT },
                onOpenHistory = { lockedSurface = LockedSurface.HISTORY },
            )
            LockedSurface.ACCOUNT -> AccountScreen(
                showBackButton = true,
                playSubscription = container.subscription,
                onClose = { lockedSurface = LockedSurface.PAYWALL },
            )
            LockedSurface.HISTORY -> EventHistoryScreen(
                state = repositoryState,
                remoteSnapshotStates = emptyMap(),
                onRequestRemoteSnapshot = { _, _ -> },
                onCreateMonitor = { lockedSurface = LockedSurface.PAYWALL },
                showBackButton = true,
                onBack = { lockedSurface = LockedSurface.PAYWALL },
            )
        }
        return
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
                    onAssistant = { nav.navigate(ASSISTANT) },
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
        composable(ASSISTANT) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val subscriptionState by container.subscription.state.collectAsState()
            val assistantAccessDecision = remember(accountState, subscriptionState) {
                container.currentCloudAccessDecision()
            }
            val vm: AssistantViewModel = viewModel(factory = viewModelFactory {
                AssistantViewModel(
                    gateway = container.monitorAssistant,
                    catalogProvider = container.assistantCatalog,
                    voiceGateway = container.voiceTranscription,
                    accessDecision = {
                        container.currentCloudAccessDecision()
                    },
                    languageTag = { currentAppLanguageTag(context) },
                )
            })
            AssistantScreen(
                viewModel = vm,
                accessDecision = assistantAccessDecision,
                onBack = nav::popBackStack,
                onOpenAccount = { nav.navigate(ASSISTANT_ACCOUNT) },
                onConfirmProposal = { proposal ->
                    val destination = when (proposal) {
                        is MonitorConfigurationProposal.ReferenceImages -> REFERENCE
                        is MonitorConfigurationProposal.VisualDescription -> OBJECT_DETECTION
                        is MonitorConfigurationProposal.StructuredReading -> READING_SETUP
                    }
                    nav.navigate(destination) {
                        popUpTo(ASSISTANT) { inclusive = true }
                    }
                    AssistantProposalSavedState.write(
                        nav.getBackStackEntry(destination).savedStateHandle,
                        proposal,
                    )
                },
            )
        }
        composable(ASSISTANT_ACCOUNT) {
            AccountScreen(
                showBackButton = true,
                playSubscription = container.subscription,
                onClose = nav::popBackStack,
            )
        }
        composable(SETUP_ACCOUNT) {
            AccountScreen(
                showBackButton = true,
                playSubscription = container.subscription,
                onClose = nav::popBackStack,
            )
        }
        composable(READING_SETUP) { entry ->
            val assistantRecovery: AssistantProposalRecoveryViewModel = viewModel(
                key = "assistant-proposal-recovery",
                viewModelStoreOwner = entry,
                factory = viewModelFactory {
                    AssistantProposalRecoveryViewModel(
                        savedStateHandle = entry.savedStateHandle,
                        expectedKind = AssistantProposalKind.STRUCTURED_READING,
                        catalogProvider = container.assistantCatalog,
                    )
                },
            )
            val assistantRecoveryState by assistantRecovery.state.collectAsState()
            val assistantProposal: MonitorConfigurationProposal.StructuredReading? =
                when (val state = assistantRecoveryState) {
                    AssistantProposalRecoveryState.ManualRoute -> null
                    is AssistantProposalRecoveryState.Ready ->
                        state.proposal as MonitorConfigurationProposal.StructuredReading
                    AssistantProposalRecoveryState.Validating,
                    is AssistantProposalRecoveryState.Rejected,
                    -> {
                        AssistantProposalRecoveryScreen(
                            state = state,
                            onRetry = assistantRecovery::retry,
                            onBack = nav::popBackStack,
                        )
                        return@composable
                    }
                }
            val defaultReadingMonitorName = stringResource(R.string.default_reading_monitor_name)
            val vm: TransientReadingSetupViewModel = viewModel(factory = viewModelFactory {
                TransientReadingSetupViewModel(
                    initialNotificationsEnabled = assistantProposal != null,
                    monitorName = defaultReadingMonitorName,
                    preparation = ReadingPreparation { taskId, onProgress ->
                        container.modelPreparation.prepareTransientReading(
                            taskId = taskId,
                            onProgress = onProgress,
                            requiredModelProfileKey = assistantProposal?.modelProfileKey,
                            requiredPackageId = assistantProposal?.packageId,
                            requiredIntentKey = assistantProposal?.intentKey,
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
                            container.requireProductAccess()
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
                            container.requireProductAccess()
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
                initialCondition = assistantProposal?.toReadingConditionDraft(),
                repositoryState = repositoryState,
                monitoringStatus = monitoringStatus,
                latestObservationSnapshot = latestObservationSnapshot,
                cameraPermissionGranted = cameraPermissionGranted,
                cameraPermissionDenied = cameraPermissionDenied,
                onRequestCameraPermission = onRequestCameraPermission,
                onOpenAppSettings = onOpenAppSettings,
                onStartMonitoring = onStartMonitoring,
                onCheckProductAccess = container::productAccessRejection,
                onStopMonitoring = onStopMonitoring,
                onOpenAccount = { nav.navigate(SETUP_ACCOUNT) { launchSingleTop = true } },
                onBack = { vm.leave(::returnHome) },
                onMonitoringStarted = ::returnHome,
            )
        }
        composable(OBJECT_DETECTION) { entry ->
            val languageTag = currentAppLanguageTag(LocalContext.current)
            val assistantRecovery: AssistantProposalRecoveryViewModel = viewModel(
                key = "assistant-proposal-recovery",
                viewModelStoreOwner = entry,
                factory = viewModelFactory {
                    AssistantProposalRecoveryViewModel(
                        savedStateHandle = entry.savedStateHandle,
                        expectedKind = AssistantProposalKind.VISUAL_DESCRIPTION,
                        catalogProvider = container.assistantCatalog,
                    )
                },
            )
            val assistantRecoveryState by assistantRecovery.state.collectAsState()
            val assistantProposal: MonitorConfigurationProposal.VisualDescription? =
                when (val state = assistantRecoveryState) {
                    AssistantProposalRecoveryState.ManualRoute -> null
                    is AssistantProposalRecoveryState.Ready ->
                        state.proposal as MonitorConfigurationProposal.VisualDescription
                    AssistantProposalRecoveryState.Validating,
                    is AssistantProposalRecoveryState.Rejected,
                    -> {
                        AssistantProposalRecoveryScreen(
                            state = state,
                            onRetry = assistantRecovery::retry,
                            onBack = nav::popBackStack,
                        )
                        return@composable
                    }
                }
            val vm: ObjectDetectionCreationViewModel = viewModel(factory = viewModelFactory {
                ObjectDetectionCreationViewModel(
                    catalogProvider = objectTargetCatalogProvider,
                    initialTargetId = assistantProposal?.targetId,
                    initialTargetQuery = assistantProposal?.displayText,
                    languageTag = languageTag,
                    initialModelBinding = assistantProposal?.let {
                        ObjectDetectionModelBinding(
                            modelProfileKey = it.modelProfileKey,
                            packageId = it.packageId,
                            intentKey = it.intentKey,
                        )
                    },
                    initialRule = assistantProposal?.rule?.toMonitorRule()
                        ?: MonitorRule.TargetPresence(),
                    initialNotificationsEnabled = assistantProposal != null,
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
                    onCheckProductAccess = container::productAccessRejection,
                    onStopMonitoring = onStopMonitoring,
                    onOpenAccount = { nav.navigate(SETUP_ACCOUNT) { launchSingleTop = true } },
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
                    onCheckProductAccess = container::productAccessRejection,
                    onStarted = ::returnHome,
                    onOpenAccount = { nav.navigate(SETUP_ACCOUNT) { launchSingleTop = true } },
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
            val assistantRecovery: AssistantProposalRecoveryViewModel = viewModel(
                key = "assistant-proposal-recovery",
                viewModelStoreOwner = entry,
                factory = viewModelFactory {
                    AssistantProposalRecoveryViewModel(
                        savedStateHandle = entry.savedStateHandle,
                        expectedKind = AssistantProposalKind.REFERENCE_IMAGES,
                        catalogProvider = container.assistantCatalog,
                    )
                },
            )
            val assistantRecoveryState by assistantRecovery.state.collectAsState()
            val assistantProposal: MonitorConfigurationProposal.ReferenceImages? =
                when (val state = assistantRecoveryState) {
                    AssistantProposalRecoveryState.ManualRoute -> null
                    is AssistantProposalRecoveryState.Ready ->
                        state.proposal as MonitorConfigurationProposal.ReferenceImages
                    AssistantProposalRecoveryState.Validating,
                    is AssistantProposalRecoveryState.Rejected,
                    -> {
                        AssistantProposalRecoveryScreen(
                            state = state,
                            onRetry = assistantRecovery::retry,
                            onBack = nav::popBackStack,
                        )
                        return@composable
                    }
                }
            val vm: ReferenceCreationViewModel = viewModel(factory = viewModelFactory {
                ReferenceCreationViewModel(
                    repository = container.monitors,
                    savedStateHandle = entry.savedStateHandle,
                    initialName = assistantProposal?.title.orEmpty(),
                    initialRule = assistantProposal?.rule?.toMonitorRule()
                        ?: MonitorRule.TargetPresence(),
                    initialNotificationsEnabled = assistantProposal != null,
                    requiredModelProfileKey = assistantProposal?.modelProfileKey,
                    requiredPackageId = assistantProposal?.packageId,
                    requiredIntentKey = assistantProposal?.intentKey,
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
                    onCheckProductAccess = container::productAccessRejection,
                    onStopMonitoring = onStopMonitoring,
                    onOpenAccount = { nav.navigate(SETUP_ACCOUNT) { launchSingleTop = true } },
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
                    onCheckProductAccess = container::productAccessRejection,
                    onStarted = {
                        materialsVm.completeCameraCreation()
                        returnHome()
                    },
                    onOpenAccount = { nav.navigate(SETUP_ACCOUNT) { launchSingleTop = true } },
                    onBack = {
                        vm.leave {
                            materialsVm.resumeAfterCamera()
                            nav.popBackStack()
                        }
                    },
                )
            }
        }
        composable(ACCOUNT) {
            MainTabScaffold(MainTab.ACCOUNT, ::openMainTab) {
                AccountScreen(
                    showBackButton = false,
                    playSubscription = container.subscription,
                    onClose = { openMainTab(MainTab.MONITORS) },
                )
            }
        }
        composable(HISTORY) {
            MainTabScaffold(MainTab.HISTORY, ::openMainTab) {
                EventHistoryScreen(
                    state = repositoryState,
                    remoteSnapshotStates = remoteSnapshotStates,
                    onRequestRemoteSnapshot = container.remoteSnapshots::request,
                    onCreateMonitor = { openMainTab(MainTab.MONITORS) },
                )
            }
        }
        composable(MONITOR_HISTORY, arguments = listOf(navArgument(MONITOR_ID) { type = NavType.StringType })) { entry ->
            val id = checkNotNull(entry.arguments?.getString(MONITOR_ID))
            EventHistoryScreen(
                state = repositoryState,
                remoteSnapshotStates = remoteSnapshotStates,
                onRequestRemoteSnapshot = container.remoteSnapshots::request,
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
                onCheckProductAccess = container::productAccessRejection,
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
                        onCheckProductAccess = container::productAccessRejection,
                        onStopMonitoring = onStopMonitoring,
                        onOpenAccount = { nav.navigate(SETUP_ACCOUNT) { launchSingleTop = true } },
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
    val assistantRecovery: AssistantProposalRecoveryViewModel = viewModel(
        key = "assistant-proposal-recovery",
        viewModelStoreOwner = creationEntry,
        factory = viewModelFactory {
            AssistantProposalRecoveryViewModel(
                savedStateHandle = creationEntry.savedStateHandle,
                expectedKind = AssistantProposalKind.VISUAL_DESCRIPTION,
                catalogProvider = container.assistantCatalog,
            )
        },
    )
    val assistantRecoveryState by assistantRecovery.state.collectAsState()
    val assistantProposal: MonitorConfigurationProposal.VisualDescription? =
        when (val state = assistantRecoveryState) {
            AssistantProposalRecoveryState.ManualRoute -> null
            is AssistantProposalRecoveryState.Ready ->
                state.proposal as MonitorConfigurationProposal.VisualDescription
            AssistantProposalRecoveryState.Validating,
            is AssistantProposalRecoveryState.Rejected,
            -> {
                AssistantProposalRecoveryScreen(
                    state = state,
                    onRetry = assistantRecovery::retry,
                    onBack = onRecoveryExit,
                )
                return
            }
        }
    val creationVm: ObjectDetectionCreationViewModel = viewModel(
        viewModelStoreOwner = creationEntry,
        factory = viewModelFactory {
            ObjectDetectionCreationViewModel(
                catalogProvider = objectTargetCatalogProvider,
                initialTargetId = assistantProposal?.targetId,
                initialTargetQuery = assistantProposal?.displayText,
                languageTag = languageTag,
                initialModelBinding = assistantProposal?.let {
                    ObjectDetectionModelBinding(
                        modelProfileKey = it.modelProfileKey,
                        packageId = it.packageId,
                        intentKey = it.intentKey,
                    )
                },
                initialRule = assistantProposal?.rule?.toMonitorRule()
                    ?: MonitorRule.TargetPresence(),
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
                        container.requireProductAccess()
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
    val assistantRecovery: AssistantProposalRecoveryViewModel = viewModel(
        key = "assistant-proposal-recovery",
        viewModelStoreOwner = materialsEntry,
        factory = viewModelFactory {
            AssistantProposalRecoveryViewModel(
                savedStateHandle = materialsEntry.savedStateHandle,
                expectedKind = AssistantProposalKind.REFERENCE_IMAGES,
                catalogProvider = container.assistantCatalog,
            )
        },
    )
    val assistantRecoveryState by assistantRecovery.state.collectAsState()
    val assistantProposal: MonitorConfigurationProposal.ReferenceImages? =
        when (val state = assistantRecoveryState) {
            AssistantProposalRecoveryState.ManualRoute -> null
            is AssistantProposalRecoveryState.Ready ->
                state.proposal as MonitorConfigurationProposal.ReferenceImages
            AssistantProposalRecoveryState.Validating,
            is AssistantProposalRecoveryState.Rejected,
            -> {
                AssistantProposalRecoveryScreen(
                    state = state,
                    onRetry = assistantRecovery::retry,
                    onBack = onRecoveryExit,
                )
                return
            }
        }
    val materialsVm: ReferenceCreationViewModel = viewModel(
        viewModelStoreOwner = materialsEntry,
        factory = viewModelFactory {
            ReferenceCreationViewModel(
                repository = container.monitors,
                savedStateHandle = materialsEntry.savedStateHandle,
                initialName = assistantProposal?.title.orEmpty(),
                initialRule = assistantProposal?.rule?.toMonitorRule()
                    ?: MonitorRule.TargetPresence(),
                initialNotificationsEnabled = assistantProposal != null,
                requiredModelProfileKey = assistantProposal?.modelProfileKey,
                requiredPackageId = assistantProposal?.packageId,
                requiredIntentKey = assistantProposal?.intentKey,
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
                        container.requireProductAccess()
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
