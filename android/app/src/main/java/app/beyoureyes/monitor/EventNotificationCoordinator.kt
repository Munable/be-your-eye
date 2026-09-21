package app.beyoureyes.monitor

import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics
import app.beyoureyes.core.data.EventSink
import app.beyoureyes.core.data.EventWriteRequest
import app.beyoureyes.core.data.EventWriteResult
import app.beyoureyes.core.data.LocalNotificationStore
import app.beyoureyes.core.data.monitorPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EventNotificationContext(
    val taskId: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(taskId.isNotBlank())
        require(occurredAtEpochMillis >= 0)
    }
}

fun interface EventNotificationContextResolver {
    suspend fun resolve(eventId: String): EventNotificationContext?
}

fun interface EventNotificationPublisher {
    fun publish(
        eventId: String,
        context: EventNotificationContext,
        text: String,
    )
}

fun interface NotificationDeliveryDiagnosticSink {
    fun record(eventId: String, failure: Exception)
}

fun interface PendingNotificationRetryScheduler {
    fun schedule()
}

/**
 * The Event is durable, but Android cannot display its notification yet.
 *
 * This is deliberately distinct from a publisher defect: permission/channel denial must not
 * fail the monitoring frame or mark the Event notification delivered. A later recovery scan can
 * retry the same Room event_id after the user enables notifications.
 */
class NotificationDeliveryDeferredException(
    reason: String,
) : IllegalStateException(reason)

/**
 * VM-007 durable ordering boundary: Room wins first and owns pending notification state.
 *
 * A process exit or publisher failure between Event commit and notify leaves the row pending.
 * Recovery retries the same event tag/ID, then marks delivery only after the publisher returns.
 */
class EventNotificationCoordinator(
    private val eventSink: EventSink,
    private val notificationStore: LocalNotificationStore,
    private val contextResolver: EventNotificationContextResolver,
    private val publisher: EventNotificationPublisher,
    private val diagnosticSink: NotificationDeliveryDiagnosticSink =
        NotificationDeliveryDiagnosticSink { _, _ -> },
    private val retryScheduler: PendingNotificationRetryScheduler =
        PendingNotificationRetryScheduler { },
    private val epochMillis: () -> Long = System::currentTimeMillis,
) {
    private val deliveryMutex = Mutex()

    suspend fun persistThenNotify(request: EventWriteRequest): EventWriteResult {
        val result = eventSink.persist(request)
        val delivered = attemptPendingDelivery(result.eventId)
        val stillPending = if (delivered) {
            false
        } else {
            try {
                notificationStore.findPending(result.eventId) != null
            } catch (failure: Exception) {
                try {
                    diagnosticSink.record(result.eventId, failure)
                } catch (_: Exception) {
                    // The Event already exists; diagnostics cannot change durable truth.
                }
                true
            }
        }
        if (stillPending) {
            try {
                retryScheduler.schedule()
            } catch (failure: Exception) {
                try {
                    diagnosticSink.record(result.eventId, failure)
                } catch (_: Exception) {
                    // Scheduling diagnostics are best effort; the durable Room row remains pending.
                }
            }
        }
        return result
    }

    /** Persists a lifecycle boundary without notifying the user who just requested that stop. */
    suspend fun persistSilently(request: EventWriteRequest): EventWriteResult {
        val result = eventSink.persist(request)
        val pending = notificationStore.findPending(result.eventId)
        if (pending != null) {
            check(notificationStore.markDelivered(result.eventId, epochMillis())) {
                "silent event could not be marked delivered"
            }
        }
        return result
    }

    suspend fun recoverPending(limit: Int = 100): Int {
        require(limit in 1..1_000)
        var delivered = 0
        notificationStore.listPending(limit).forEach { pending ->
            if (attemptPendingDelivery(pending.eventId)) delivered++
        }
        return delivered
    }

    /** Once Room owns the Event, a notification defect must never terminate visual monitoring. */
    private suspend fun attemptPendingDelivery(eventId: String): Boolean = try {
        deliverPending(eventId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        try {
            diagnosticSink.record(eventId, failure)
        } catch (_: Exception) {
            // Diagnostics are best effort and may not turn a durable Event into a runtime failure.
        }
        false
    }

    private suspend fun deliverPending(eventId: String): Boolean = deliveryMutex.withLock {
        val pending = notificationStore.findPending(eventId) ?: return@withLock false
        val context = contextResolver.resolve(eventId) ?: return@withLock false
        check(notificationStore.recordAttempt(eventId)) {
            "pending notification disappeared before attempt"
        }
        try {
            publisher.publish(pending.eventId, context, pending.text)
        } catch (_: NotificationDeliveryDeferredException) {
            return@withLock false
        }
        check(notificationStore.markDelivered(pending.eventId, epochMillis())) {
            "published notification could not be marked delivered"
        }
        true
    }
}

class AndroidEventNotificationPublisher(
    context: Context,
    private val taskNotificationsEnabled: (String) -> Boolean = { taskId ->
        runBlocking {
            monitorPreferences(context.applicationContext)
                .eventNotificationTaskIds.first()
                .contains(taskId)
        }
    },
    private val notificationsAvailable: () -> Boolean = {
        AndroidEventNotificationAvailability.isAvailable(context.applicationContext)
    },
) : EventNotificationPublisher {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    override fun publish(
        eventId: String,
        context: EventNotificationContext,
        text: String,
    ) {
        require(eventId.isNotBlank())
        if (!taskNotificationsEnabled(context.taskId)) {
            RuntimeDiagnostics.record(appContext, "notification_skipped_disabled")
            return
        }
        if (!notificationsAvailable()) {
            RuntimeDiagnostics.record(appContext, "notification_deferred")
            throw NotificationDeliveryDeferredException("event_notification_permission_denied")
        }
        NotificationChannels.ensureCreated(appContext)
        if (manager.getNotificationChannel(NotificationChannels.LOCAL_EVENT_CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE
        ) {
            throw NotificationDeliveryDeferredException("event_notification_channel_disabled")
        }
        manager.notify(
            eventId,
            NotificationChannels.EVENT_NOTIFICATION_ID,
            NotificationChannels.localConfirmedEvent(appContext, eventId, text),
        )
        RuntimeDiagnostics.record(appContext, "notification_delivered")
    }
}

internal object AndroidEventNotificationAvailability {
    fun isAvailable(context: Context): Boolean {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted || !manager.areNotificationsEnabled()) return false
        NotificationChannels.ensureCreated(appContext)
        return manager.getNotificationChannel(NotificationChannels.LOCAL_EVENT_CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE
    }
}

class AndroidNotificationDeliveryDiagnosticSink : NotificationDeliveryDiagnosticSink {
    override fun record(@Suppress("UNUSED_PARAMETER") eventId: String, failure: Exception) {
        Log.w(
            TAG,
            "notification_pending reason=${failure.javaClass.simpleName}",
        )
    }

    private companion object {
        const val TAG = "BeYourEyeEventNotify"
    }
}
