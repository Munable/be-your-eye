package app.beyoureyes.core.domain

enum class ObjectEventCondition(val wireValue: String) {
    APPEARED("appeared"),
    DISAPPEARED("disappeared"),
    COUNT_MATCHED("count_matched"),
}

/** A one-shot visual duration condition, deliberately separate from an open/close episode. */
enum class VisualEventCondition(val wireValue: String) {
    PRESENT_FOR_DURATION("present_for_duration"),
    ABSENT_FOR_DURATION("absent_for_duration"),
}

enum class ReadingOperator(val wireValue: String) {
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    EQ("eq"),
    OUTSIDE("outside"),
}

enum class ReadingSourceKind(val wireValue: String) {
    DIGITAL_DISPLAY("digital_display"),
    COUNTER("counter"),
    ANALOG_DIAL("analog_dial"),
}

/** Existing state-transition payload values used to close an open reference clue on stop. */
object MonitoringSessionTransition {
    const val REFERENCE_EPISODE_OPEN = "reference_episode_open"
    const val MONITORING_STOPPED = "monitoring_stopped"
}

/**
 * Closed event payload union shared by rule evaluation and durable storage.
 *
 * There is intentionally no byte array, URI, file path, image, audio, or video field. Runtime
 * code cannot put media into an Event through this product-domain API.
 */
sealed interface RestrictedEventPayload {
    data class ObjectEpisode(
        val targetId: String,
        val condition: ObjectEventCondition,
        val durationMillis: Long,
        val count: Int,
    ) : RestrictedEventPayload {
        init {
            require(targetId.isNotBlank()) { "targetId must not be blank" }
            require(durationMillis >= 0) { "durationMillis must not be negative" }
            require(count >= 0) { "count must not be negative" }
        }
    }

    data class VisualConditionMet(
        val targetId: String,
        val condition: VisualEventCondition,
        val durationMillis: Long,
    ) : RestrictedEventPayload {
        init {
            require(targetId.isNotBlank()) { "targetId must not be blank" }
            require(durationMillis > 0) { "durationMillis must be positive" }
        }
    }

    data class ReadingThresholdCrossed(
        val displayText: String,
        val valueDecimal: String,
        val format: ConfirmedReadingFormat,
        val confidence: Float,
        val sourceKind: ReadingSourceKind,
        val observedAtEpochMillis: Long,
        val operator: ReadingOperator,
        val thresholdDecimal: String? = null,
        val lowerThresholdDecimal: String? = null,
        val upperThresholdDecimal: String? = null,
        val unit: String? = null,
    ) : RestrictedEventPayload {
        init {
            require(
                displayText.isNotBlank() &&
                    displayText.length <= StructuredReadingValue.MAX_READING_TEXT_LENGTH,
            )
            requireCanonicalDecimal(valueDecimal, "valueDecimal")
            require(confidence in 0f..1f) { "confidence must be in [0, 1]" }
            require(observedAtEpochMillis >= 0) { "observedAtEpochMillis must not be negative" }
            require(unit == null || unit.isNotBlank()) { "unit must be null or non-blank" }
            require(unit == format.unit) { "event unit and format unit must match" }
            if (operator == ReadingOperator.OUTSIDE) {
                require(thresholdDecimal == null)
                requireNotNull(lowerThresholdDecimal).also {
                    requireCanonicalDecimal(it, "lowerThresholdDecimal")
                }
                requireNotNull(upperThresholdDecimal).also {
                    requireCanonicalDecimal(it, "upperThresholdDecimal")
                }
                require(
                    java.math.BigDecimal(lowerThresholdDecimal) <
                        java.math.BigDecimal(upperThresholdDecimal),
                ) { "outside thresholds require lower < upper" }
            } else {
                requireNotNull(thresholdDecimal).also {
                    requireCanonicalDecimal(it, "thresholdDecimal")
                }
                require(lowerThresholdDecimal == null && upperThresholdDecimal == null)
            }
        }
    }

    data class StateTransition(
        val fromState: String,
        val toState: String,
    ) : RestrictedEventPayload {
        init {
            require(fromState.isNotBlank()) { "fromState must not be blank" }
            require(toState.isNotBlank()) { "toState must not be blank" }
        }
    }
}
