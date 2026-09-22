package app.beyoureyes.monitor

import android.content.Context
import android.app.NotificationManager
import app.beyoureyes.core.data.MonitorRepository
import app.beyoureyes.monitor.diagnostics.MonitoringHeartbeatStore
import app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics

/** Process-scoped local dependencies. No accounts, hosted backend or provider credentials. */
internal class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val monitors = MonitorRepository(appContext,
        deleteEventNotification = { eventId ->
            appContext.getSystemService(NotificationManager::class.java)
                ?.cancel(eventId, NotificationChannels.EVENT_NOTIFICATION_ID)
        },
        deleteReadingBaselineNotification = { monitorId ->
            appContext.getSystemService(NotificationManager::class.java)
                ?.cancel(monitorId, NotificationChannels.READING_BASELINE_NOTIFICATION_ID)
        },
    )
    val peerAlerts = app.beyoureyes.monitor.feature.peers.PeerAlertStore(appContext)
    val objectCatalog = SignedObjectCatalogProvider.create(appContext, BuildConfig.MODEL_CATALOG_URL)
    val modelPreparation = ModelPreparationCoordinator.createApp(appContext)
    val samplingResolver = TaskBoundSamplingConfigResolver.createApp(appContext)
    val diagnosticEvent: (String) -> Unit = { RuntimeDiagnostics.record(appContext, it) }
    val diagnosticFailure: (String, String) -> Unit = { event, code ->
        RuntimeDiagnostics.record(appContext, event, mapOf("code" to code))
    }
    fun clearMonitoringHeartbeat(monitorId: String) {
        MonitoringHeartbeatStore(appContext.filesDir).clear(monitorId)
    }
}
