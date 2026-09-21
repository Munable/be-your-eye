package app.beyoureyes.core.domain

import java.math.BigDecimal

enum class ReadingFormatKind(val wireValue: String) {
    DECIMAL("decimal"),
    PERCENT("percent"),
    SCIENTIFIC("scientific"),
    TIME("time"),
}

/** The complete display contract confirmed for one fixed reading target. */
data class ConfirmedReadingFormat(
    val kind: ReadingFormatKind,
    val fractionalDigits: Int = 0,
    val timeSegments: Int? = null,
    val unit: String? = null,
) {
    init {
        require(fractionalDigits in 0..MAX_FRACTIONAL_DIGITS)
        require((kind == ReadingFormatKind.TIME) == (timeSegments != null))
        require(timeSegments == null || timeSegments in 2..3)
        require(unit == null || unit.isNotBlank() && unit.length <= MAX_UNIT_LENGTH)
        require(kind != ReadingFormatKind.TIME || fractionalDigits == 0 && unit == null)
        require(kind != ReadingFormatKind.PERCENT || unit == "%")
    }

    /** Applies only the user-confirmed display format; it never substitutes a digit or sign. */
    fun apply(rawText: String): StructuredReadingValue? {
        val parsed = parseStructuredReading(rawText) ?: return null
        if (parsed.format.kind != kind || parsed.format.timeSegments != timeSegments ||
            parsed.format.unit != unit
        ) return null
        if (parsed.format.fractionalDigits == fractionalDigits) {
            return parsed.copy(format = this)
        }
        if (kind != ReadingFormatKind.DECIMAL && kind != ReadingFormatKind.PERCENT) return null
        if (fractionalDigits == 0) return null
        val scaledNumericText = if (parsed.format.fractionalDigits > 0) {
            // Preserve the numeric value when OCR only omitted or added trailing
            // decimal zeroes (for example 5.5 versus the confirmed 5.50).
            runCatching {
                BigDecimal(parsed.numericText)
                    .setScale(fractionalDigits, java.math.RoundingMode.UNNECESSARY)
                    .toPlainString()
            }.getOrNull() ?: return null
        } else {
            parsed.numericText.withFractionalDigits(fractionalDigits) ?: return null
        }
        val canonical = runCatching { BigDecimal(scaledNumericText).canonicalPlainString() }
            .getOrNull() ?: return null
        return StructuredReadingValue(
            text = scaledNumericText.decorateWith(unit),
            numericText = scaledNumericText,
            valueDecimal = canonical,
            format = this,
        )
    }

    /** Threshold inputs are human display values, not unscaled OCR digits. */
    fun parseThreshold(input: String): StructuredReadingValue? {
        val parsed = parseStructuredReadingInternal(input, requireWholeInput = true) ?: return null
        val compatible = when (kind) {
            ReadingFormatKind.TIME -> parsed.takeIf {
                it.format.kind == ReadingFormatKind.TIME && it.format.timeSegments == timeSegments
            }
            ReadingFormatKind.PERCENT -> parsed.takeIf {
                it.format.kind == ReadingFormatKind.PERCENT || it.format.kind == ReadingFormatKind.DECIMAL
            }
            ReadingFormatKind.DECIMAL -> parsed.takeIf {
                it.format.kind == ReadingFormatKind.DECIMAL || it.format.kind == ReadingFormatKind.PERCENT
            }
            ReadingFormatKind.SCIENTIFIC -> parsed.takeIf {
                it.format.kind == ReadingFormatKind.SCIENTIFIC || it.format.kind == ReadingFormatKind.DECIMAL
            }
        } ?: return null
        if (compatible.format.unit != null && compatible.format.unit != unit) return null
        return compatible.copy(format = this)
    }

    /** Converts a stored canonical comparison value back into the confirmed human display form. */
    fun displayValue(valueDecimal: String): String? {
        val canonical = runCatching { BigDecimal(valueDecimal).canonicalPlainString() }.getOrNull()
            ?: return null
        if (canonical != valueDecimal) return null
        if (kind != ReadingFormatKind.TIME) return valueDecimal
        val totalSeconds = runCatching { BigDecimal(valueDecimal).longValueExact() }.getOrNull()
            ?.takeIf { it >= 0L } ?: return null
        val seconds = totalSeconds % 60L
        return when (timeSegments) {
            2 -> {
                val minutes = totalSeconds / 60L
                if (minutes > MAX_FIRST_TIME_SEGMENT) null else "%02d:%02d".format(minutes, seconds)
            }
            3 -> {
                val hours = totalSeconds / 3_600L
                if (hours > MAX_FIRST_TIME_SEGMENT) null else "%02d:%02d:%02d".format(
                    hours,
                    totalSeconds / 60L % 60L,
                    seconds,
                )
            }
            else -> null
        }
    }

    /** Threshold fields use the same confirmed display form as live readings. */
    fun displayThreshold(valueDecimal: String): String? = displayValue(valueDecimal)

    companion object {
        const val PROFILE_ID = "confirmed_reading_format_v2"
        const val MAX_FRACTIONAL_DIGITS = 12
        const val MAX_UNIT_LENGTH = 8
        private const val MAX_FIRST_TIME_SEGMENT = 999_999L

        fun calibrate(rawText: String, confirmedText: String): ConfirmedReadingFormat? {
            val recognized = parseStructuredReading(rawText) ?: return null
            val confirmed = parseStructuredReadingInternal(
                confirmedText,
                requireWholeInput = true,
            ) ?: return null
            if (confirmed.format.kind != recognized.format.kind) return null
            val candidate = confirmed.format.copy(
                unit = confirmed.format.unit ?: recognized.format.unit,
            )
            val formattedRaw = candidate.apply(rawText) ?: return null
            // Confirmation may restore punctuation or ignore display-leading zeroes, but it must
            // never teach a one-sample digit substitution. This comparison is required even when
            // the recognized and confirmed formats already have the same fractional precision.
            if (formattedRaw.valueDecimal != confirmed.valueDecimal) return null
            return candidate
        }
    }
}

data class StructuredReadingValue(
    val text: String,
    /** The matched number without currency, unit, grouping separators, or percent decoration. */
    val numericText: String,
    /** Canonical comparable value. TIME uses total seconds; PERCENT keeps displayed percent units. */
    val valueDecimal: String,
    val format: ConfirmedReadingFormat,
) {
    init {
        require(text.isNotBlank() && text.length <= MAX_READING_TEXT_LENGTH)
        require(numericText.isNotBlank())
        require(normalizeDecimal(valueDecimal) == valueDecimal)
    }

    val decimalValue: BigDecimal get() = BigDecimal(valueDecimal)

    companion object {
        const val MAX_READING_TEXT_LENGTH = 96
    }
}

/**
 * Extracts the first comparable structured reading from a recognized line. Labels around the
 * reading are deliberately allowed; detector line segmentation is not a product validity gate.
 */
fun parseStructuredReading(rawText: String): StructuredReadingValue? =
    parseStructuredReadingInternal(rawText, requireWholeInput = false)

private fun parseStructuredReadingInternal(
    rawText: String,
    requireWholeInput: Boolean,
): StructuredReadingValue? {
    val normalized = rawText.normalizeReadingCharacters().trim()
    if (normalized.isBlank() || normalized.length > StructuredReadingValue.MAX_READING_TEXT_LENGTH) {
        return null
    }
    val candidates = buildList {
        TIME_TOKEN.findAll(normalized).forEach { match ->
            parseTime(match)?.let { add(TokenCandidate(match.range, it)) }
        }
        NUMBER_TOKEN.findAll(normalized).forEach { match ->
            parseNumber(match)?.let { add(TokenCandidate(match.range, it)) }
        }
    }.filter { !requireWholeInput || it.range == normalized.indices }
    return candidates.minWithOrNull(
        compareBy<TokenCandidate> { it.range.first }.thenBy { it.range.last },
    )?.value
}

private fun parseTime(match: MatchResult): StructuredReadingValue? {
    val token = match.value.trim()
    val pieces = token.split(':')
    if (pieces.size !in 2..3) return null
    val values = pieces.map { it.toLongOrNull() ?: return null }
    if (values.drop(1).any { it !in 0..59 }) return null
    val totalSeconds = values.fold(0L) { total, value ->
        runCatching { Math.addExact(Math.multiplyExact(total, 60L), value) }.getOrNull()
            ?: return null
    }
    return StructuredReadingValue(
        text = token,
        numericText = totalSeconds.toString(),
        valueDecimal = totalSeconds.toString(),
        format = ConfirmedReadingFormat(
            kind = ReadingFormatKind.TIME,
            timeSegments = pieces.size,
        ),
    )
}

private fun parseNumber(match: MatchResult): StructuredReadingValue? {
    val signBefore = match.groups["signBefore"]?.value.orEmpty()
    val signAfter = match.groups["signAfter"]?.value.orEmpty()
    if (signBefore.isNotEmpty() && signAfter.isNotEmpty()) return null
    val sign = signBefore.ifEmpty { signAfter }
    val currency = match.groups["currency"]?.value?.takeIf(String::isNotBlank)
    val number = match.groups["number"]?.value ?: return null
    val percent = match.groups["percent"]?.value?.takeIf(String::isNotBlank)
    val suffix = match.groups["unit"]?.value?.trim()?.takeIf(String::isNotBlank)
    val numericText = sign + number.replace(",", "")
    val canonical = runCatching { BigDecimal(numericText).canonicalPlainString() }.getOrNull()
        ?: return null
    val kind = when {
        percent != null -> ReadingFormatKind.PERCENT
        numericText.contains('e', ignoreCase = true) -> ReadingFormatKind.SCIENTIFIC
        else -> ReadingFormatKind.DECIMAL
    }
    val unit = when {
        percent != null -> "%"
        currency != null -> currency
        suffix != null -> suffix
        else -> null
    }
    val fractionalDigits = numericText.substringBefore('e', missingDelimiterValue = numericText)
        .substringBefore('E', missingDelimiterValue = numericText)
        .substringAfter('.', missingDelimiterValue = "")
        .length
    val display = match.value.trim()
    return StructuredReadingValue(
        text = display,
        numericText = numericText,
        valueDecimal = canonical,
        format = ConfirmedReadingFormat(
            kind = kind,
            fractionalDigits = fractionalDigits.coerceAtMost(ConfirmedReadingFormat.MAX_FRACTIONAL_DIGITS),
            unit = unit,
        ),
    )
}

private data class TokenCandidate(
    val range: IntRange,
    val value: StructuredReadingValue,
)

private fun String.withFractionalDigits(fractionalDigits: Int): String? {
    if (contains('e', ignoreCase = true)) return null
    val sign = firstOrNull()?.takeIf { it == '-' || it == '+' }?.toString().orEmpty()
    val unsigned = removePrefix(sign)
    if (unsigned.isBlank() || unsigned.any { it != '.' && it !in '0'..'9' }) return null
    val digits = unsigned.replace(".", "")
    if (digits.isBlank()) return null
    val padded = if (fractionalDigits == 0) digits else {
        digits.padStart(fractionalDigits + 1, '0')
    }
    val scaled = if (fractionalDigits == 0) {
        padded
    } else {
        val split = padded.length - fractionalDigits
        padded.substring(0, split) + "." + padded.substring(split)
    }
    return sign + scaled
}

private fun String.decorateWith(unit: String?): String = when (unit) {
    null -> this
    "¥", "$", "€", "£" -> unit + this
    else -> this + unit
}

private fun String.normalizeReadingCharacters(): String = buildString(length) {
    this@normalizeReadingCharacters.forEachIndexed { index, character ->
        append(
            when (character) {
                '\u3000' -> ' '
                '\u2212' -> '-'
                '\uffe5' -> '\u00a5'
                in '\uff10'..'\uff19',
                in '\uff21'..'\uff3a',
                in '\uff41'..'\uff5a',
                '\uff04',
                '\uff05',
                '\uff0b',
                '\uff0d',
                '\uff0e',
                '\uff0f',
                '\uff1a',
                -> (character.code - 0xfee0).toChar()
                // A full-width comma is also normal Chinese punctuation. Treat it as a numeric
                // grouping mark only when it is actually between digits; otherwise keep it as a
                // token boundary instead of turning `7.58，余额` into malformed `7.58,余额`.
                '\uff0c' -> if (
                    getOrNull(index - 1)?.isReadingDigit() == true &&
                    getOrNull(index + 1)?.isReadingDigit() == true
                ) ',' else character
                else -> character
            },
        )
    }
}

private fun Char.isReadingDigit(): Boolean = this in '0'..'9' || this in '\uff10'..'\uff19'

private fun BigDecimal.canonicalPlainString(): String {
    if (compareTo(BigDecimal.ZERO) == 0) return "0"
    return stripTrailingZeros().toPlainString()
}

private val TIME_TOKEN = Regex("(?<![0-9])(?:[0-9]{1,6}:)?[0-9]{1,6}:[0-9]{2}(?![0-9:])")
private val NUMBER_TOKEN = Regex(
    """(?<![0-9:.,+-])(?<signBefore>[+-]?)\s*(?<currency>[¥$€£]?)\s*(?<signAfter>[+-]?)\s*(?<number>(?:(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)\s*(?<percent>%?)(?<unit>(?:kWh|mWh|MHz|GHz|kHz|km/h|m/s|mmHg|rpm|USD|CNY|RMB|kg|mg|km|cm|mm|mL|ms|Hz|kW|mW|Wh|Ah|mAh|V|A|W|Pa|bar|psi|dB|°C|°F|℃|℉|元|秒|分钟|小时|天|克|千克|米|厘米|毫米|升|毫升)?)(?![0-9:.,])""",
    RegexOption.IGNORE_CASE,
)
