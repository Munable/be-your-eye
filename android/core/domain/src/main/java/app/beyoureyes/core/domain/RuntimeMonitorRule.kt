package app.beyoureyes.core.domain

import java.math.BigDecimal

/** Runtime-only rule union. Product creation currently exposes presence and numeric thresholds. */
sealed interface RuntimeMonitorRule {
    /** One appearance opens an episode; five seconds of continuous absence closes it. */
    data class PresenceEpisode(
        val appearanceConfirmMillis: Long = PRODUCT_APPEARANCE_CONFIRM_MILLIS,
        val disappearanceConfirmMillis: Long = PRODUCT_DISAPPEARANCE_CONFIRM_MILLIS,
        val samplingIntervalMillis: Long? = null,
    ) : RuntimeMonitorRule {
        init {
            require(appearanceConfirmMillis > 0)
            require(disappearanceConfirmMillis > 0)
            require(samplingIntervalMillis == null || samplingIntervalMillis > 0)
        }

        val minimumObservationCount: Int = PRODUCT_MINIMUM_OBSERVATION_COUNT

        val maximumObservationGapMillis: Long
            get() = checkNotNull(samplingIntervalMillis) {
                "presence episode sampling is unresolved"
            }.let { interval ->
                maxOf(MINIMUM_OBSERVATION_GAP_MILLIS, Math.multiplyExact(interval, 3L))
                    .coerceAtMost(PRODUCT_MAXIMUM_OBSERVATION_GAP_MILLIS)
            }

        companion object {
            const val PRODUCT_APPEARANCE_CONFIRM_MILLIS = 500L
            const val PRODUCT_MINIMUM_OBSERVATION_COUNT = 2
            // The generic sampler permits up to 10 seconds. A rule must remain formable at every
            // signed cadence instead of silently resetting forever when thermal adaptation slows it.
            const val PRODUCT_MAXIMUM_OBSERVATION_GAP_MILLIS = 30_000L
            const val PRODUCT_DISAPPEARANCE_CONFIRM_MILLIS = 5_000L
            private const val MINIMUM_OBSERVATION_GAP_MILLIS = 750L
        }
    }

    data class Presence(
        val durationMillis: Long,
        val samplingIntervalMillis: Long? = null,
    ) : RuntimeMonitorRule {
        init {
            require(durationMillis > 0)
            require(samplingIntervalMillis == null || samplingIntervalMillis > 0)
        }

        val minimumPositiveCount: Int get() = sampling().minimumObservationCount
        val maximumPositiveGapMillis: Long get() = sampling().maximumObservationGapMillis
        val rearmAbsenceMillis: Long get() = durationMillis

        private fun sampling() = VisualSamplingDerivation.resolve(
            durationMillis,
            checkNotNull(samplingIntervalMillis) { "presence rule sampling is unresolved" },
        )
    }

    data class Absence(
        val durationMillis: Long,
        val samplingIntervalMillis: Long? = null,
    ) : RuntimeMonitorRule {
        init {
            require(durationMillis > 0)
            require(samplingIntervalMillis == null || samplingIntervalMillis > 0)
        }

        val minimumNegativeCount: Int get() = sampling().minimumObservationCount
        val maximumObservationGapMillis: Long get() = sampling().maximumObservationGapMillis
        val rearmPresenceMillis: Long get() = durationMillis

        private fun sampling() = VisualSamplingDerivation.resolve(
            durationMillis,
            checkNotNull(samplingIntervalMillis) { "absence rule sampling is unresolved" },
        )
    }

    /** Package-neutral rule support; no first-release creation surface emits it. */
    data class ObjectCount(
        val operator: CountOperator,
        val count: Int,
        val durationMillis: Long,
        val cooldownMillis: Long,
        val samplingIntervalMillis: Long? = null,
    ) : RuntimeMonitorRule {
        init {
            require(count >= 0 && durationMillis > 0 && cooldownMillis >= 0)
            require(samplingIntervalMillis == null || samplingIntervalMillis > 0)
        }

        val minimumMatchCount: Int get() = sampling().minimumObservationCount
        val maximumObservationGapMillis: Long get() = sampling().maximumObservationGapMillis

        private fun sampling() = VisualSamplingDerivation.resolve(
            durationMillis,
            checkNotNull(samplingIntervalMillis) { "object count rule sampling is unresolved" },
        )
    }

    sealed interface ReadingThreshold : RuntimeMonitorRule {
        val operator: ReadingOperator
        val thresholdDecimal: String?
        val lowerThresholdDecimal: String?
        val upperThresholdDecimal: String?
        val durationMillis: Long
        val hysteresisDecimal: String
        val cooldownMillis: Long
        val sourceKind: ReadingSourceKind

        /**
         * A pending-baseline task keeps monitoring and recording latest readings, but its rule is
         * not user-confirmed yet and must never produce a condition event.
         */
        val configured: Boolean

        data class Single(
            override val operator: ReadingOperator,
            override val thresholdDecimal: String,
            override val durationMillis: Long,
            override val hysteresisDecimal: String = "0",
            override val cooldownMillis: Long = 0,
            override val sourceKind: ReadingSourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
            override val configured: Boolean = true,
        ) : ReadingThreshold {
            override val lowerThresholdDecimal: String? = null
            override val upperThresholdDecimal: String? = null

            init {
                require(
                    operator in setOf(
                        ReadingOperator.GT,
                        ReadingOperator.GTE,
                        ReadingOperator.LT,
                        ReadingOperator.LTE,
                    ),
                )
                require(thresholdDecimal.isRuntimeDecimal())
                requireReadingRuntimeParameters(durationMillis, hysteresisDecimal, cooldownMillis)
            }
        }

        data class Outside(
            override val lowerThresholdDecimal: String,
            override val upperThresholdDecimal: String,
            override val durationMillis: Long,
            override val hysteresisDecimal: String = "0",
            override val cooldownMillis: Long = 0,
            override val sourceKind: ReadingSourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
        ) : ReadingThreshold {
            override val operator: ReadingOperator = ReadingOperator.OUTSIDE
            override val thresholdDecimal: String? = null

            /** A range rule only exists after user confirmation, so it is always configured. */
            override val configured: Boolean get() = true

            init {
                require(lowerThresholdDecimal.isRuntimeDecimal() && upperThresholdDecimal.isRuntimeDecimal())
                require(BigDecimal(lowerThresholdDecimal) < BigDecimal(upperThresholdDecimal)) {
                    "outside thresholds require lower < upper"
                }
                requireReadingRuntimeParameters(durationMillis, hysteresisDecimal, cooldownMillis)
            }
        }
    }

    /** Package-neutral rule support; no first-release creation surface emits it. */
    data class StateTransition(
        val fromState: String,
        val toState: String,
        val stableFrames: Int,
        val cooldownMillis: Long,
    ) : RuntimeMonitorRule {
        init {
            require(fromState.isNotBlank() && toState.isNotBlank() && fromState != toState)
            require(stableFrames > 0 && cooldownMillis >= 0)
        }
    }
}

data class VisualSamplingDerivation(
    val minimumObservationCount: Int,
    val maximumObservationGapMillis: Long,
) {
    companion object {
        fun resolve(durationMillis: Long, samplingIntervalMillis: Long): VisualSamplingDerivation {
            require(durationMillis > 0 && samplingIntervalMillis > 0)
            val intervals = durationMillis / samplingIntervalMillis +
                if (durationMillis % samplingIntervalMillis == 0L) 0L else 1L
            val count = Math.addExact(intervals, 1L).coerceAtLeast(2L)
            require(count <= Int.MAX_VALUE)
            return VisualSamplingDerivation(
                minimumObservationCount = count.toInt(),
                maximumObservationGapMillis = Math.multiplyExact(samplingIntervalMillis, 2L),
            )
        }
    }
}

fun RuntimeMonitorRule.resolveForSampling(intervalMillis: Long): RuntimeMonitorRule = when (this) {
    is RuntimeMonitorRule.PresenceEpisode -> copy(samplingIntervalMillis = intervalMillis)
    is RuntimeMonitorRule.Presence -> copy(samplingIntervalMillis = intervalMillis)
    is RuntimeMonitorRule.Absence -> copy(samplingIntervalMillis = intervalMillis)
    is RuntimeMonitorRule.ObjectCount -> copy(samplingIntervalMillis = intervalMillis)
    is RuntimeMonitorRule.ReadingThreshold,
    is RuntimeMonitorRule.StateTransition,
    -> this
}

enum class CountOperator { EQ, GTE, LTE }

private val RUNTIME_DECIMAL = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?$")
private fun String.isRuntimeDecimal(): Boolean = length in 1..64 && RUNTIME_DECIMAL.matches(this)

private fun requireReadingRuntimeParameters(
    durationMillis: Long,
    hysteresisDecimal: String,
    cooldownMillis: Long,
) {
    require(durationMillis > 0)
    require(cooldownMillis >= 0)
    require(hysteresisDecimal.isRuntimeDecimal())
    require(BigDecimal(hysteresisDecimal).signum() >= 0) {
        "reading hysteresis must be non-negative"
    }
}
