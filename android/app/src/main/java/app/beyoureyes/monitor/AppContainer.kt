package app.beyoureyes.monitor

import android.content.Context
import android.app.NotificationManager
import app.beyoureyes.core.data.MonitorRepository
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.monitor.diagnostics.MonitoringHeartbeatStore
import app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics
import app.beyoureyes.monitor.feature.assistant.DisabledMonitorAssistantGateway
import app.beyoureyes.monitor.feature.assistant.DisabledVoiceTranscriptionGateway
import app.beyoureyes.monitor.feature.assistant.SignedAssistantCatalogSnapshotProvider
import app.beyoureyes.monitor.feature.assistant.SupabaseMonitorAssistantGateway
import app.beyoureyes.monitor.feature.assistant.SupabaseVoiceTranscriptionGateway
import app.beyoureyes.monitor.feature.subscription.PlaySubscriptionController
import app.beyoureyes.monitor.feature.subscription.EntitlementLease
import app.beyoureyes.monitor.feature.subscription.ProductAccessState
import app.beyoureyes.monitor.feature.subscription.SupabaseSubscriptionEntitlementGateway
import app.beyoureyes.monitor.feature.subscription.productAccessDecision
import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import app.beyoureyes.monitor.feature.subscription.productAccessState
import app.beyoureyes.monitor.feature.subscription.messageResource
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Small process graph. Dependencies remain explicit; no reflection or service locator framework. */
internal class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val platformServicesDisabled = BuildConfig.COMMUNITY_BUILD || BuildConfig.BUILD_IDENTITY == "functional-test"
    private val functionalTest = BuildConfig.BUILD_IDENTITY == "functional-test"

    val accountController = CloudBootstrap.accountController(appContext)
    val subscription = PlaySubscriptionController(
        appContext,
        SupabaseSubscriptionEntitlementGateway.create(
            appContext,
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            websiteBillingEnabled = BuildConfig.WEBSITE_BILLING_ENABLED,
        ),
        playBillingEnabled = BuildConfig.PLAY_BILLING_ENABLED,
        websiteBillingEnabled = BuildConfig.WEBSITE_BILLING_ENABLED,
    )
    private val accessScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val productAccess: StateFlow<ProductAccessState> = if (functionalTest) {
        MutableStateFlow(functionalTestProductAccess())
    } else {
        combine(accountController.state, subscription.state) { account, subscriptionState ->
            productAccessState(account, subscriptionState)
        }.stateIn(accessScope, SharingStarted.Eagerly, ProductAccessState.Initializing)
    }
    init {
        accessScope.launch {
            productAccess.collectLatest { access ->
                CloudBootstrap.updateProductAccess(access is ProductAccessState.Granted)
            }
        }
    }
    val monitorAssistant = if (platformServicesDisabled) {
        DisabledMonitorAssistantGateway
    } else {
        SupabaseMonitorAssistantGateway.create(
            appContext,
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
    }
    val voiceTranscription = if (platformServicesDisabled) {
        DisabledVoiceTranscriptionGateway
    } else {
        SupabaseVoiceTranscriptionGateway.create(
            appContext,
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
    }
    val assistantCatalog = SignedAssistantCatalogSnapshotProvider.create(
        appContext,
        BuildConfig.MODEL_CATALOG_URL,
    )
    val remoteSnapshots = CloudBootstrap.snapshotTransfers(appContext)
    val monitors = MonitorRepository(
        appContext,
        accountController.state,
        deleteEventNotification = { eventId ->
            appContext.getSystemService(NotificationManager::class.java)
                ?.cancel(eventId, NotificationChannels.EVENT_NOTIFICATION_ID)
        },
        deleteReadingBaselineNotification = { monitorId ->
            appContext.getSystemService(NotificationManager::class.java)
                ?.cancel(monitorId, NotificationChannels.READING_BASELINE_NOTIFICATION_ID)
        },
        onLocalTaskCreated = { taskId ->
            val signedIn = accountController.state.value as? CloudAccountState.SignedIn
            if (signedIn != null) {
                CloudBootstrap.claimLocalTask(appContext, signedIn.accountId, taskId)
            }
        },
    )
    val modelPreparation = ModelPreparationCoordinator.createApp(
        appContext,
        productAccess = ::currentAccessDecision,
    )
    val samplingResolver = TaskBoundSamplingConfigResolver.createApp(appContext)
    val diagnosticEvent: (String) -> Unit = { event ->
        RuntimeDiagnostics.record(appContext, event)
    }
    val diagnosticFailure: (String, String) -> Unit = { event, code ->
        RuntimeDiagnostics.record(appContext, event, mapOf("code" to code))
    }

    fun clearMonitoringHeartbeat(monitorId: String) {
        MonitoringHeartbeatStore(appContext.filesDir).clear(monitorId)
    }

    fun currentAccessDecision(): ProductAccessDecision =
        app.beyoureyes.monitor.feature.subscription.localUseAccessDecision(
            app.beyoureyes.core.vision.BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)
                ?: app.beyoureyes.core.vision.BuildChannel.DEVELOPMENT_NO_MODEL,
            currentProductAccess(),
        )

    fun currentCloudAccessDecision(): ProductAccessDecision =
        productAccessDecision(currentProductAccess())

    fun currentProductAccess(now: Instant = Instant.now()): ProductAccessState =
        if (functionalTest) {
            functionalTestProductAccess()
        } else {
            productAccessState(accountController.state.value, subscription.state.value, now)
        }

    fun productAccessRejection(): MonitoringStartResult.Rejected? =
        currentAccessDecision().takeUnless {
            it == ProductAccessDecision.GRANTED
        }?.let { decision ->
            MonitoringStartResult.Rejected(
                message = appContext.getString(decision.messageResource()),
                canOpenAccount = true,
            )
        }

    fun requireProductAccess() {
        val rejection = productAccessRejection() ?: return
        throw IllegalStateException(rejection.message)
    }

    private fun functionalTestProductAccess(): ProductAccessState = ProductAccessState.Granted(
        EntitlementLease(
            accountId = "functional-test",
            providerState = "SUBSCRIPTION_STATE_ACTIVE",
            expiresAt = Instant.parse("9999-12-31T23:59:59Z"),
            refreshAfter = Instant.parse("9999-12-31T23:59:59Z"),
        ),
    )
}
