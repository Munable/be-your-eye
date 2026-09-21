package app.beyoureyes.monitor

import app.beyoureyes.monitor.service.monitoring.MonitoringService

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationChannels {
    const val MONITORING_CHANNEL_ID = "monitoring_runtime_v1"
    const val LOCAL_EVENT_CHANNEL_ID = "local_monitoring_events_v2"
    const val REMOTE_EVENT_CHANNEL_ID = "remote_monitoring_events_v1"
    const val MONITORING_NOTIFICATION_ID = 10_001
    const val EVENT_NOTIFICATION_ID = 20_001
    const val READING_BASELINE_NOTIFICATION_ID = 30_001
    const val NO_HIT_REMINDER_NOTIFICATION_ID = 40_001
    const val EXTRA_EVENT_ID = "app.beyoureyes.monitor.extra.EVENT_ID"
    const val EXTRA_MONITOR_ID = "app.beyoureyes.monitor.extra.MONITOR_ID"

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    MONITORING_CHANNEL_ID,
                    context.getString(R.string.monitoring_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.monitoring_channel_description)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
                NotificationChannel(
                    LOCAL_EVENT_CHANNEL_ID,
                    context.getString(R.string.local_event_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.local_event_channel_description)
                },
                NotificationChannel(
                    REMOTE_EVENT_CHANNEL_ID,
                    context.getString(R.string.remote_event_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.remote_event_channel_description)
                },
            ),
        )
    }

    private fun cameraServiceNotification(
        context: Context,
        title: String,
        text: String,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            2,
            MonitoringService.stopIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, MONITORING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.notification_stop_action), stopIntent)
            .build()
    }

    fun startingCamera(context: Context): Notification = cameraServiceNotification(
        context = context,
        title = context.getString(R.string.notification_starting_title, context.getString(R.string.app_name)),
        text = context.getString(R.string.notification_starting_text),
    )

    fun cameraRunning(context: Context): Notification = cameraServiceNotification(
        context = context,
        title = context.getString(R.string.notification_running_title, context.getString(R.string.app_name)),
        text = context.getString(R.string.notification_running_text),
    )

    /** Must only be called after the event ID has been inserted idempotently into Room. */
    private fun confirmedEvent(
        context: Context,
        eventId: String,
        text: String,
        channelId: String,
        quiet: Boolean,
    ): Notification {
        val openTimelineIntent = PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            eventHistoryIntent(context, eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_event_title, context.getString(R.string.app_name)))
            .setContentText(text)
            .setContentIntent(openTimelineIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup("monitoring-events")
            .setSilent(quiet)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    fun localConfirmedEvent(context: Context, eventId: String, text: String): Notification =
        confirmedEvent(context, eventId, text, LOCAL_EVENT_CHANNEL_ID, quiet = false)

    fun remoteConfirmedEvent(context: Context, eventId: String, text: String): Notification =
        confirmedEvent(context, eventId, text, REMOTE_EVENT_CHANNEL_ID, quiet = false)

    internal fun eventHistoryIntent(context: Context, eventId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_EVENT_ID, eventId)
        }

    /**
     * One-shot local prompt for a pending-baseline reading task: the first stable value is in,
     * and tapping opens the monitor detail so the user can confirm it. Not an Event; never synced.
     */
    fun postReadingBaselinePrompt(
        context: Context,
        taskId: String,
        monitorName: String,
        readingText: String,
    ): Boolean {
        val appContext = context.applicationContext
        if (!AndroidEventNotificationAvailability.isAvailable(appContext)) return false
        ensureCreated(appContext)
        val openDetailIntent = PendingIntent.getActivity(
            appContext,
            taskId.hashCode(),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_MONITOR_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, LOCAL_EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(appContext.getString(R.string.reading_baseline_prompt_title))
            .setContentText(
                appContext.getString(
                    R.string.reading_baseline_prompt_body,
                    monitorName,
                    readingText,
                ),
            )
            .setContentIntent(openDetailIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(taskId, READING_BASELINE_NOTIFICATION_ID, notification)
        return true
    }

    /**
     * One-shot gentle local reminder for a task that has existed for a long time without any
     * hit: tapping opens the monitor detail, where the optional field test lives. Not an Event;
     * never synchronized; monitoring keeps running unchanged.
     */
    fun postNoHitReminder(
        context: Context,
        taskId: String,
        monitorName: String,
    ): Boolean {
        val appContext = context.applicationContext
        if (!AndroidEventNotificationAvailability.isAvailable(appContext)) return false
        ensureCreated(appContext)
        val openDetailIntent = PendingIntent.getActivity(
            appContext,
            taskId.hashCode(),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_MONITOR_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, MONITORING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(appContext.getString(R.string.no_hit_reminder_title))
            .setContentText(appContext.getString(R.string.no_hit_reminder_body, monitorName))
            .setContentIntent(openDetailIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(taskId, NO_HIT_REMINDER_NOTIFICATION_ID, notification)
        return true
    }
}
