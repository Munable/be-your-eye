package app.beyoureyes.core.domain

import java.math.BigDecimal

/**
 * A closed runtime result. An error is always [Unavailable], never an empty result that could
 * accidentally be interpreted as "nothing is present".
 */
sealed interface Observation {
    val sourceSequence: Long

    data class Detections(
        val items: List<Detection>,
        override val sourceSequence: Long,
    ) : Observation

    data class Reading(
        val text: String,
        /** Canonical base-10 storage/wire truth; binary floating point is never persisted. */
        val valueDecimal: String,
        val stable: Boolean,
        override val sourceSequence: Long,
        val confidence: Float,
        val unit: String? = null,
        val format: ConfirmedReadingFormat = ConfirmedReadingFormat(
            kind = ReadingFormatKind.DECIMAL,
            fractionalDigits = valueDecimal.substringAfter('.', "").length,
            unit = unit,
        ),
        /** Current coordinate-matched reading feedback. It is never an inference ROI. */
        val anchorBox: NormalizedRect? = null,
        /** Runtime-local coordinate anchor generation; explicit rescan starts new evidence. */
        val targetTrackId: Long = 0,
    ) : Observation {
        init {
            require(text.isNotBlank()) { "text must not be blank" }
            requireCanonicalDecimal(valueDecimal, "valueDecimal")
            require(confidence in 0f..1f) { "confidence must be in [0, 1]" }
            require(unit == null || unit.isNotBlank()) { "unit must be null or non-blank" }
            require(unit == format.unit) { "reading unit and format unit must match" }
            require(targetTrackId >= 0)
        }

        val decimalValue: BigDecimal get() = BigDecimal(valueDecimal)
    }

    data class State(
        val stateId: String,
        val confidence: Float,
        override val sourceSequence: Long,
        val stableFrameCount: Int,
    ) : Observation {
        init {
            require(stateId.isNotBlank()) { "stateId must not be blank" }
            require(confidence in 0f..1f) { "confidence must be in [0, 1]" }
            require(stableFrameCount >= 1) { "stableFrameCount must be positive" }
        }
    }

    data class Unavailable(
        val reason: UnavailableReason,
        val diagnosticCode: String? = null,
        override val sourceSequence: Long,
        /** True while the reading tracker retains UI feedback; product rule evidence still resets. */
        val evidencePaused: Boolean = false,
        /** Last in-memory candidate for grey UI feedback; rules still see this as unavailable. */
        val retainedReading: Reading? = null,
        val retainedReadingAgeMillis: Long? = null,
    ) : Observation {
        init {
            require((retainedReading == null) == (retainedReadingAgeMillis == null))
            require(retainedReadingAgeMillis == null || retainedReadingAgeMillis >= 0)
        }
    }
}

enum class UnavailableReason {
    NO_FRAME,
    LOW_QUALITY,
    INFERENCE_ERROR,
    INCOMPATIBLE_OUTPUT,
    THERMAL_PAUSE,
}

data class Detection(
    val label: String,
    val score: Float,
    val box: NormalizedRect,
) {
    init {
        require(label.isNotBlank()) { "label must not be blank" }
        require(score in 0f..1f) { "score must be in [0, 1]" }
    }
}

/** Coordinates in the upright CameraX ViewPort after target rotation, normalized to [0, 1]. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "all coordinates must be in [0, 1]"
        }
        require(left < right) { "left must be < right; zero-width rectangles are invalid" }
        require(top < bottom) { "top must be < bottom; zero-height rectangles are invalid" }
    }

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Boundary points count as inside the original ROI. */
    fun containsCenterOf(other: NormalizedRect): Boolean =
        other.centerX in left..right && other.centerY in top..bottom
}
