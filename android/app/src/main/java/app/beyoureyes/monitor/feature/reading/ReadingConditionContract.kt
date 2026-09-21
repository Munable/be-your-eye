package app.beyoureyes.monitor.feature.reading

import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.ALLOWED_TRIGGER_DURATIONS_SECONDS
import app.beyoureyes.core.domain.DEFAULT_TRIGGER_DURATION_SECONDS
import java.math.BigDecimal

internal enum class ReadingConditionMode {
    ABOVE,
    BELOW,
    OUTSIDE,
}

internal sealed interface ReadingConditionValidation {
    data class Valid(val rule: MonitorRule.ReadingThreshold) : ReadingConditionValidation
    data class Invalid(val reason: ReadingConditionInvalidReason) : ReadingConditionValidation
}

internal enum class ReadingConditionInvalidReason {
    VALUE_FORMAT,
    LOWER_FORMAT,
    UPPER_FORMAT,
    ORDER,
}

internal data class ReadingConditionDraft(
    val mode: ReadingConditionMode,
    val threshold: String = "",
    val lowerThreshold: String = "",
    val upperThreshold: String = "",
    val durationSeconds: Int = DEFAULT_TRIGGER_DURATION_SECONDS,
) {
    init {
        require(durationSeconds in ALLOWED_TRIGGER_DURATIONS_SECONDS)
    }
}

internal data class ReadingConditionSubmission(
    val rule: MonitorRule.ReadingThreshold,
    val requiresPersistence: Boolean,
)

/**
 * Restores the controls from the saved rule. A confirmed camera value is only a
 * default for a monitor that has never had a condition configured.
 */
internal fun readingConditionDraft(
    persistedRule: MonitorRule.ReadingThreshold,
    confirmedBaseline: String? = null,
    confirmedFormat: ConfirmedReadingFormat? = null,
): ReadingConditionDraft {
    if (!persistedRule.configured) {
        return ReadingConditionDraft(
            mode = ReadingConditionMode.ABOVE,
            threshold = confirmedBaseline.orEmpty(),
        )
    }
    return when (persistedRule) {
        is MonitorRule.ReadingThreshold.Single -> ReadingConditionDraft(
            mode = when (persistedRule.comparison) {
                ReadingComparison.GT,
                ReadingComparison.GTE,
                -> ReadingConditionMode.ABOVE
                ReadingComparison.LT,
                ReadingComparison.LTE,
                -> ReadingConditionMode.BELOW
            },
            threshold = confirmedFormat?.displayThreshold(persistedRule.thresholdDecimal)
                ?: persistedRule.thresholdDecimal,
            durationSeconds = persistedRule.durationSeconds,
        )
        is MonitorRule.ReadingThreshold.Outside -> ReadingConditionDraft(
            mode = ReadingConditionMode.OUTSIDE,
            lowerThreshold = confirmedFormat?.displayThreshold(persistedRule.lowerThresholdDecimal)
                ?: persistedRule.lowerThresholdDecimal,
            upperThreshold = confirmedFormat?.displayThreshold(persistedRule.upperThresholdDecimal)
                ?: persistedRule.upperThresholdDecimal,
            durationSeconds = persistedRule.durationSeconds,
        )
    }
}

/** Keeps the exact persisted rule, including comparison semantics, when the user made no change. */
internal fun readingConditionSubmission(
    persistedRule: MonitorRule.ReadingThreshold,
    draft: ReadingConditionDraft,
    confirmedReadingFormat: ConfirmedReadingFormat,
): ReadingConditionSubmission? {
    if (persistedRule.configured && draft == readingConditionDraft(
            persistedRule,
            confirmedFormat = confirmedReadingFormat,
        )
    ) {
        return ReadingConditionSubmission(persistedRule, requiresPersistence = false)
    }
    val candidate = readingRuleFromInputs(
        mode = draft.mode,
        threshold = draft.threshold,
        lowerThreshold = draft.lowerThreshold,
        upperThreshold = draft.upperThreshold,
        confirmedFormat = confirmedReadingFormat,
        durationSeconds = draft.durationSeconds,
    ) ?: return null
    return ReadingConditionSubmission(
        rule = candidate,
        requiresPersistence = candidate != persistedRule,
    )
}

internal fun validateReadingCondition(
    mode: ReadingConditionMode,
    thresholdInput: String,
    lowerInput: String,
    upperInput: String,
    confirmedFormat: ConfirmedReadingFormat,
    durationSeconds: Int = DEFAULT_TRIGGER_DURATION_SECONDS,
): ReadingConditionValidation = when (mode) {
    ReadingConditionMode.ABOVE,
    ReadingConditionMode.BELOW,
    -> {
        val canonical = confirmedFormat.parseThreshold(thresholdInput)?.valueDecimal
            ?: return ReadingConditionValidation.Invalid(ReadingConditionInvalidReason.VALUE_FORMAT)
        ReadingConditionValidation.Valid(
            MonitorRule.ReadingThreshold.Single(
                comparison = if (mode == ReadingConditionMode.ABOVE) {
                    ReadingComparison.GT
                } else {
                    ReadingComparison.LT
                },
                thresholdDecimal = canonical,
                configured = true,
                durationSeconds = durationSeconds,
            ),
        )
    }
    ReadingConditionMode.OUTSIDE -> {
        val lower = confirmedFormat.parseThreshold(lowerInput)?.valueDecimal
            ?: return ReadingConditionValidation.Invalid(ReadingConditionInvalidReason.LOWER_FORMAT)
        val upper = confirmedFormat.parseThreshold(upperInput)?.valueDecimal
            ?: return ReadingConditionValidation.Invalid(ReadingConditionInvalidReason.UPPER_FORMAT)
        if (BigDecimal(lower) >= BigDecimal(upper)) {
            ReadingConditionValidation.Invalid(ReadingConditionInvalidReason.ORDER)
        } else {
            ReadingConditionValidation.Valid(
                MonitorRule.ReadingThreshold.Outside(
                    lower,
                    upper,
                    configured = true,
                    durationSeconds = durationSeconds,
                ),
            )
        }
    }
}

internal fun readingRuleFromInputs(
    mode: ReadingConditionMode,
    threshold: String,
    lowerThreshold: String,
    upperThreshold: String,
    confirmedFormat: ConfirmedReadingFormat,
    durationSeconds: Int = DEFAULT_TRIGGER_DURATION_SECONDS,
): MonitorRule.ReadingThreshold? = (
    validateReadingCondition(
        mode,
        threshold,
        lowerThreshold,
        upperThreshold,
        confirmedFormat,
        durationSeconds,
    )
        as? ReadingConditionValidation.Valid
    )?.rule
