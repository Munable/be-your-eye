package app.beyoureyes.monitor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.data.RoomEventRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Durable local-notification recovery independent of the camera foreground service.
 *
 * An Event remains the source of truth. WorkManager only retries pending Room rows and the
 * publisher uses event_id as the stable notification tag, so app resume, permission changes and
 * process restarts cannot create a second Event or a second notification identity.
 */
internal object LocalNotificationWorkScheduler {
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocalEventNotificationWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_RECOVERY,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    internal const val MAX_ATTEMPTS = 5
    private const val UNIQUE_RECOVERY = "local-event-notification-recovery-v1"
}

internal class LocalEventNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val database = MonitorDatabaseFactory.open(applicationContext)
        val repository = RoomEventRepository(database)
        val coordinator = EventNotificationCoordinator(
            eventSink = repository,
            notificationStore = repository,
            contextResolver = EventNotificationContextResolver { eventId ->
                database.monitoringDao().findEvent(eventId)?.let { event ->
                    EventNotificationContext(
                        taskId = event.taskId,
                        occurredAtEpochMillis = event.occurredAtEpochMillis,
                    )
                }
            },
            publisher = AndroidEventNotificationPublisher(applicationContext),
            diagnosticSink = AndroidNotificationDeliveryDiagnosticSink(),
        )
        coordinator.recoverPending()
        val stillPending = repository.listPending(limit = 1).isNotEmpty()
        when {
            !stillPending -> Result.success()
            !AndroidEventNotificationAvailability.isAvailable(applicationContext) -> {
                // Enabled tasks stay pending until a permission/channel change. Disabled tasks
                // were consumed above by the publisher's explicit no-display policy.
                Result.success()
            }
            runAttemptCount + 1 < LocalNotificationWorkScheduler.MAX_ATTEMPTS -> Result.retry()
            else -> Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        if (runAttemptCount + 1 < LocalNotificationWorkScheduler.MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.failure()
        }
    }
}
