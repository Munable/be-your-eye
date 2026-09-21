package app.beyoureyes.monitor

import android.app.Application
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.data.ReferenceEventSnapshotStore
import app.beyoureyes.core.data.RoomEventRepository
import app.beyoureyes.monitor.diagnostics.MonitoringHeartbeatStore
import app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class BeYourEyeApplication : Application() {
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val processStartedAtEpochMillis = System.currentTimeMillis()
        val heartbeatStore = MonitoringHeartbeatStore(filesDir)
        val interrupted = heartbeatStore.interruptedRun()
        MonitoringRuntimeState.resetForProcessStart(
            interruptedMonitorId = interrupted?.monitorId,
            interruptedMessage = interrupted?.let {
                getString(R.string.monitoring_interrupted_process)
            },
        )
        if (interrupted != null) {
            val recovered = runCatching {
                runBlocking(Dispatchers.IO) {
                    RoomEventRepository(MonitorDatabaseFactory.open(this@BeYourEyeApplication))
                        .recoverInterruptedMonitoringStop(
                            taskId = interrupted.monitorId,
                            lastKnownActiveAtEpochMillis =
                                interrupted.lastHeartbeatAtEpochMillis,
                            eventsCreatedBeforeOrAtEpochMillis = maxOf(
                                processStartedAtEpochMillis,
                                interrupted.lastHeartbeatAtEpochMillis,
                            ),
                        )
                }
            }
            if (recovered.isSuccess) {
                heartbeatStore.acknowledgeInterruptedRun(interrupted)
                RuntimeDiagnostics.record(this, "interrupted_monitoring_reconciled")
            } else {
                RuntimeDiagnostics.record(this, "interrupted_monitoring_reconcile_deferred")
            }
        }
        NotificationChannels.ensureCreated(this)
        LocalNotificationWorkScheduler.enqueue(this)
        CloudBootstrap.initialize(this)
        container = AppContainer(this)
        recoveryScope.launch {
            runCatching {
                RoomEventRepository(MonitorDatabaseFactory.open(this@BeYourEyeApplication))
                    .reconcileReferenceEventSnapshots(
                        store = ReferenceEventSnapshotStore.openAppPrivate(filesDir),
                        createdBeforeOrAtEpochMillis = processStartedAtEpochMillis,
                    )
            }.onFailure {
                RuntimeDiagnostics.record(
                    this@BeYourEyeApplication,
                    "event_snapshot_reconcile_deferred",
                )
            }
        }
    }
}

internal val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as BeYourEyeApplication).container
