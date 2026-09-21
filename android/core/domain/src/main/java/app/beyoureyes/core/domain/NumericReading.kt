package app.beyoureyes.core.domain

import java.math.BigDecimal

private val CANONICAL_DECIMAL = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")

/** Validates the exact decimal text used by JSON contracts without converting it to Double. */
internal fun requireCanonicalDecimal(value: String, fieldName: String): BigDecimal {
    require(CANONICAL_DECIMAL.matches(value)) {
        "$fieldName must be a canonical base-10 decimal string"
    }
    val decimal = runCatching { BigDecimal(value) }.getOrElse {
        throw IllegalArgumentException("$fieldName is not a valid decimal", it)
    }
    require(!(decimal.signum() == 0 && value.startsWith('-'))) {
        "$fieldName must not encode negative zero"
    }
    return decimal
}

/**
 * Generic detect -> read -> typed data boundary.
 *
 * JSON serialization belongs in core:data/backend. UI consumes this typed object rather than
 * reparsing model-specific JSON.
 */
internal fun interface NumericReadingPipeline {
    fun read(frame: CanonicalReadingFrame): Observation
}

internal data class CanonicalReadingFrame(
    val sourceSequence: Long,
    val width: Int,
    val height: Int,
    val grayscaleBytes: ByteArray,
) {
    init {
        require(sourceSequence >= 0)
        require(width > 0 && height > 0)
        require(grayscaleBytes.size == width * height) {
            "grayscaleBytes must contain exactly width * height pixels"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CanonicalReadingFrame &&
            sourceSequence == other.sourceSequence &&
            width == other.width &&
            height == other.height &&
            grayscaleBytes.contentEquals(other.grayscaleBytes)

    override fun hashCode(): Int {
        var result = sourceSequence.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + grayscaleBytes.contentHashCode()
        return result
    }
}
