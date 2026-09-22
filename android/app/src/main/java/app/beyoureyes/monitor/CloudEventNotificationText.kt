package app.beyoureyes.monitor

import android.content.Context
import androidx.core.content.ContextCompat
import app.beyoureyes.core.data.cloud.CloudEventRow
import app.beyoureyes.core.domain.MonitoringSessionTransition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolves a remote event at display time. The wire payload contains stable facts only; the
 * current application locale supplies the user-facing notification copy.
 */
internal fun cloudEventNotificationText(context: Context, event: CloudEventRow): String {
    val appContext = ContextCompat.getContextForLanguage(context)
    val payload = event.payload
    val fallbackTarget = appContext.getString(R.string.product_brand)
    val target = payload.stringOrNull("target_id") ?: fallbackTarget
    return when (payload.stringOrNull("type")) {
        "object_episode" -> when (payload.stringOrNull("condition")) {
            "disappeared" -> appContext.getString(
                R.string.notification_event_target_left,
                target,
            )
            "count_matched" -> appContext.getString(
                R.string.notification_event_target_count_met,
                target,
                payload.stringOrNull("count")?.toIntOrNull() ?: 0,
            )
            else -> appContext.getString(
                R.string.notification_event_target_appeared,
                target,
            )
        }
        "visual_condition_met" -> {
            val duration = payload.stringOrNull("duration_ms")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { durationText(appContext, it) }
            when (payload.stringOrNull("condition")) {
                "absent_for_duration" -> appContext.getString(
                    R.string.notification_event_absent_duration,
                    target,
                    duration ?: appContext.getString(R.string.notification_duration_unknown),
                )
                else -> appContext.getString(
                    R.string.notification_event_visible_duration,
                    target,
                    duration ?: appContext.getString(R.string.notification_duration_unknown),
                )
            }
        }
        "reading_threshold_crossed" -> {
            val reading = payload["reading"] as? JsonObject
            appContext.getString(
                R.string.notification_event_reading_crossed,
                target,
                reading?.stringOrNull("display_text")
                    ?: appContext.getString(R.string.notification_reading_unknown),
            )
        }
        "state_transition" -> {
            val from = payload.stringOrNull("from_state")
            val to = payload.stringOrNull("to_state") ?: fallbackTarget
            if (
                from == MonitoringSessionTransition.REFERENCE_EPISODE_OPEN &&
                to == MonitoringSessionTransition.MONITORING_STOPPED
            ) {
                appContext.getString(R.string.notification_event_monitor_stopped, target)
            } else {
                appContext.getString(
                    R.string.notification_event_state_changed,
                    target,
                    from ?: fallbackTarget,
                    to,
                )
            }
        }
        else -> appContext.getString(R.string.notification_event_title, fallbackTarget)
    }
}

private fun durationText(context: Context, durationMillis: Long): String = if (durationMillis % 1_000L == 0L) {
    context.getString(R.string.notification_duration_seconds, durationMillis / 1_000L)
} else {
    context.getString(R.string.notification_duration_milliseconds, durationMillis)
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
