package app.beyoureyes.monitor

import android.content.Context
import androidx.core.content.ContextCompat
import app.beyoureyes.core.domain.MonitoringSessionTransition
import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.domain.RestrictedEventPayload
import app.beyoureyes.core.domain.RuntimeEventNotificationTextFormatter
import app.beyoureyes.core.domain.VisualEventCondition

internal class AndroidRuntimeEventNotificationTextFormatter(
    context: Context,
) : RuntimeEventNotificationTextFormatter {
    private val appContext = ContextCompat.getContextForLanguage(context)

    override fun format(displayName: String, payload: RestrictedEventPayload): String =
        when (payload) {
            is RestrictedEventPayload.ObjectEpisode -> when (payload.condition) {
                ObjectEventCondition.APPEARED -> text(
                    R.string.notification_event_target_appeared,
                    displayName,
                )
                ObjectEventCondition.DISAPPEARED -> text(
                    R.string.notification_event_target_left,
                    displayName,
                )
                ObjectEventCondition.COUNT_MATCHED -> text(
                    R.string.notification_event_target_count_met,
                    displayName,
                    payload.count,
                )
            }
            is RestrictedEventPayload.VisualConditionMet -> when (payload.condition) {
                VisualEventCondition.PRESENT_FOR_DURATION -> text(
                    R.string.notification_event_visible_duration,
                    displayName,
                    duration(payload.durationMillis),
                )
                VisualEventCondition.ABSENT_FOR_DURATION -> text(
                    R.string.notification_event_absent_duration,
                    displayName,
                    duration(payload.durationMillis),
                )
            }
            is RestrictedEventPayload.ReadingThresholdCrossed -> text(
                R.string.notification_event_reading_crossed,
                displayName,
                payload.displayText,
            )
            is RestrictedEventPayload.StateTransition -> if (
                payload.fromState == MonitoringSessionTransition.REFERENCE_EPISODE_OPEN &&
                payload.toState == MonitoringSessionTransition.MONITORING_STOPPED
            ) {
                text(R.string.notification_event_monitor_stopped, displayName)
            } else {
                text(
                    R.string.notification_event_state_changed,
                    displayName,
                    payload.fromState,
                    payload.toState,
                )
            }
        }

    private fun duration(durationMillis: Long): String = if (durationMillis % 1_000L == 0L) {
        text(R.string.notification_duration_seconds, durationMillis / 1_000L)
    } else {
        text(R.string.notification_duration_milliseconds, durationMillis)
    }

    private fun text(id: Int, vararg args: Any): String = appContext.getString(id, *args)
}
