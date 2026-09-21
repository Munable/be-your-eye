package app.beyoureyes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NumericDisplayFormatTest {
    @Test
    fun `parses the complete structured reading family`() {
        assertEquals(
            StructuredReadingValue(
                text = "00:02:22",
                numericText = "142",
                valueDecimal = "142",
                format = ConfirmedReadingFormat(ReadingFormatKind.TIME, timeSegments = 3),
            ),
            parseStructuredReading("00:02:22"),
        )
        assertEquals("7.58", parseStructuredReading("¥7.58")?.valueDecimal)
        assertEquals("¥", parseStructuredReading("¥7.58")?.format?.unit)
        assertEquals("-1234.5", parseStructuredReading("-1,234.50")?.valueDecimal)
        assertEquals(2, parseStructuredReading("-1,234.50")?.format?.fractionalDigits)
        assertEquals(ReadingFormatKind.PERCENT, parseStructuredReading("12.5%")?.format?.kind)
        assertEquals("1200", parseStructuredReading("1.2e3")?.valueDecimal)
        assertEquals(ReadingFormatKind.SCIENTIFIC, parseStructuredReading("1.2e3")?.format?.kind)
        assertEquals("-7.58", parseStructuredReading("-¥7.58")?.valueDecimal)
        assertEquals("¥", parseStructuredReading("-¥7.58")?.format?.unit)
    }

    @Test
    fun `extracts the first structured reading from surrounding labels`() {
        val reading = parseStructuredReading("余额 ¥7.58，今日消费 3.20")

        assertEquals("¥7.58", reading?.text)
        assertEquals("7.58", reading?.valueDecimal)
        assertEquals("7.58", parseStructuredReading("余额 7.58，计时 00:02:22")?.valueDecimal)
        assertEquals("142", parseStructuredReading("计时 00:02:22，余额 7.58")?.valueDecimal)
    }

    @Test
    fun `confirmed format parses thresholds without requiring repeated units`() {
        val currency = ConfirmedReadingFormat(
            kind = ReadingFormatKind.DECIMAL,
            fractionalDigits = 2,
            unit = "¥",
        )
        val timer = ConfirmedReadingFormat(
            kind = ReadingFormatKind.TIME,
            timeSegments = 3,
        )

        assertEquals("8", currency.parseThreshold("8")?.valueDecimal)
        assertEquals("142", timer.parseThreshold("00:02:22")?.valueDecimal)
        assertEquals("00:02:22", timer.displayThreshold("142"))
        assertEquals("00:13:52", timer.displayValue("832"))
        assertNull(timer.parseThreshold("142"))
        assertNull(currency.parseThreshold("$8"))
    }

    @Test
    fun `confirmed display format accepts trailing zero precision changes`() {
        val format = ConfirmedReadingFormat(
            kind = ReadingFormatKind.DECIMAL,
            fractionalDigits = 2,
            unit = "¥",
        )

        assertEquals("8.10", format.apply("¥8.10")?.numericText)
        assertEquals("8.1", format.apply("¥8.10")?.valueDecimal)
        assertEquals("¥8.10", format.apply("¥8.1")?.text)
        assertNull(format.apply("$8.10"))
    }

    @Test
    fun `confirmed decimal precision restores omitted punctuation without changing digits or sign`() {
        val format = ConfirmedReadingFormat.calibrate("148", "14.8")

        assertEquals(ConfirmedReadingFormat(ReadingFormatKind.DECIMAL, fractionalDigits = 1), format)
        assertEquals("14.9", format?.apply("149")?.text)
        assertEquals("14.9", format?.apply("149")?.valueDecimal)
        assertEquals("-14.9", format?.apply("-149")?.text)
        assertEquals(
            ConfirmedReadingFormat(ReadingFormatKind.DECIMAL, fractionalDigits = 4),
            ConfirmedReadingFormat.calibrate("0082", "0.0082"),
        )
        assertEquals(
            ConfirmedReadingFormat(ReadingFormatKind.DECIMAL, fractionalDigits = 1),
            ConfirmedReadingFormat.calibrate("002.4", "2.4"),
        )
        assertNull(ConfirmedReadingFormat.calibrate("1.621", "1.62"))
        assertNull(ConfirmedReadingFormat.calibrate("173013", "71303"))
        assertNull(ConfirmedReadingFormat.calibrate("8.25", "8.75"))
        assertEquals(
            ConfirmedReadingFormat(ReadingFormatKind.DECIMAL, fractionalDigits = 2),
            ConfirmedReadingFormat.calibrate("5.5", "5.50"),
        )
        assertEquals("5.50", ConfirmedReadingFormat.calibrate("5.5", "5.50")?.apply("5.5")?.text)
    }

    @Test
    fun `invalid or out of contract text fails closed`() {
        assertNull(parseStructuredReading("没有读数"))
        assertNull(parseStructuredReading("00:61"))
        assertNull(parseStructuredReading("--1"))
        assertNull(parseStructuredReading("1,23"))
        assertNull(parseStructuredReading("1.2.3"))
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmedReadingFormat(ReadingFormatKind.DECIMAL, fractionalDigits = 13)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmedReadingFormat(ReadingFormatKind.TIME, fractionalDigits = 1, timeSegments = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmedReadingFormat(ReadingFormatKind.PERCENT, fractionalDigits = 1, unit = null)
        }
    }
}
