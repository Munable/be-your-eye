package app.beyoureyes.monitor.feature.history

import app.beyoureyes.core.domain.MonitorEvent
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import app.beyoureyes.core.domain.MonitorReadingOperator
import app.beyoureyes.core.domain.MonitorVisualEventCondition
import app.beyoureyes.core.domain.MonitoringSessionTransition
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.uiText

internal data class EventPresentation(
    val headline: UiText,
    val detail: UiText? = null,
)

internal fun EventPresentation.summary(): UiText =
    detail?.let { uiText(R.string.text_pair, headline, it) } ?: headline

internal fun MonitorEvent.productPresentation(): EventPresentation = when (val value = fact) {
    is MonitorEventFact.ObjectEpisode -> EventPresentation(
        headline = when (value.condition) {
            MonitorObjectEventCondition.APPEARED -> uiText(R.string.event_target_appeared_episode_open)
            MonitorObjectEventCondition.DISAPPEARED -> uiText(R.string.event_target_left_episode_closed)
            MonitorObjectEventCondition.COUNT_MATCHED -> uiText(R.string.event_target_count_met)
        },
        detail = value.durationMillis.takeIf { it > 0 }?.let {
            uiText(R.string.event_stayed_duration, formatDiaryDuration(it))
        },
    )
    is MonitorEventFact.VisualConditionMet -> EventPresentation(
        headline = when (value.condition) {
            MonitorVisualEventCondition.PRESENT_FOR_DURATION -> uiText(R.string.event_visible_condition_met)
            MonitorVisualEventCondition.ABSENT_FOR_DURATION -> uiText(R.string.event_absent_condition_met)
        },
        detail = when (value.condition) {
            MonitorVisualEventCondition.PRESENT_FOR_DURATION ->
                uiText(R.string.event_visible_duration, formatDiaryDuration(value.durationMillis))
            MonitorVisualEventCondition.ABSENT_FOR_DURATION ->
                uiText(R.string.event_absent_duration, formatDiaryDuration(value.durationMillis))
        },
    )
    is MonitorEventFact.ReadingThresholdCrossed -> EventPresentation(
        headline = uiText(R.string.event_reading_value, value.displayText),
        detail = when (value.operator) {
            MonitorReadingOperator.GT -> uiText(
                R.string.event_reading_above,
                value.displayThreshold(value.thresholdDecimal),
            )
            MonitorReadingOperator.GTE ->
                uiText(R.string.event_reading_at_or_above, value.displayThreshold(value.thresholdDecimal))
            MonitorReadingOperator.LT -> uiText(
                R.string.event_reading_below,
                value.displayThreshold(value.thresholdDecimal),
            )
            MonitorReadingOperator.LTE ->
                uiText(R.string.event_reading_at_or_below, value.displayThreshold(value.thresholdDecimal))
            MonitorReadingOperator.EQ -> uiText(
                R.string.event_reading_equals,
                value.displayThreshold(value.thresholdDecimal),
            )
            MonitorReadingOperator.OUTSIDE ->
                uiText(
                    R.string.event_reading_outside,
                    value.displayThreshold(value.lowerThresholdDecimal),
                    value.displayThreshold(value.upperThresholdDecimal),
                )
        },
    )
    is MonitorEventFact.StateTransition -> if (
        value.fromState == MonitoringSessionTransition.REFERENCE_EPISODE_OPEN &&
        value.toState == MonitoringSessionTransition.MONITORING_STOPPED
    ) {
        EventPresentation(headline = uiText(R.string.event_monitor_stopped_episode_ended))
    } else {
        EventPresentation(
            headline = uiText(R.string.event_state_changed, value.toState),
            detail = uiText(R.string.event_previous_state, value.fromState),
        )
    }
    is MonitorEventFact.Unknown -> EventPresentation(
        headline = uiText(R.string.event_unrecognized_title),
        detail = uiText(R.string.event_unrecognized_body),
    )
}

private fun MonitorEventFact.ReadingThresholdCrossed.displayThreshold(value: String?): String =
    value?.let(format::displayThreshold) ?: value.orEmpty()
