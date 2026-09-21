package app.beyoureyes.core.data

import app.beyoureyes.core.domain.ReadingOperator
import app.beyoureyes.core.domain.ReadingSourceKind
import app.beyoureyes.core.domain.RestrictedEventPayload
import app.beyoureyes.core.domain.VisualEventCondition
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.ReadingFormatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RestrictedEventPayloadJsonTest {
    @Test
    fun `visual duration condition is distinct from an object episode`() {
        val encoded = RestrictedEventPayloadJson.encode(
            RestrictedEventPayload.VisualConditionMet(
                targetId = "apple",
                condition = VisualEventCondition.PRESENT_FOR_DURATION,
                durationMillis = 3_000,
            ),
        )

        assertEquals(
            "{\"type\":\"visual_condition_met\",\"target_id\":\"apple\"," +
                "\"condition\":\"present_for_duration\",\"duration_ms\":3000}",
            encoded,
        )
    }

    @Test
    fun `reading threshold event matches canonical nested reading JSON`() {
        val encoded = RestrictedEventPayloadJson.encode(
            RestrictedEventPayload.ReadingThresholdCrossed(
                displayText = "123.40kPa",
                valueDecimal = "123.40",
                format = decimalFormat(2, "kPa"),
                confidence = 0.98f,
                sourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
                observedAtEpochMillis = 1_786_147_323_000,
                operator = ReadingOperator.GTE,
                thresholdDecimal = "120",
                unit = "kPa",
            ),
        )

        assertEquals(
            "{\"type\":\"reading_threshold_crossed\",\"reading\":{\"type\":\"structured_reading\",\"status\":\"stable\",\"display_text\":\"123.40kPa\",\"value_decimal\":\"123.40\",\"format\":{\"profile_id\":\"confirmed_reading_format_v2\",\"kind\":\"decimal\",\"fractional_digits\":2,\"time_segments\":null,\"unit\":\"kPa\"},\"unit\":\"kPa\",\"confidence\":0.98,\"source_kind\":\"digital_display\",\"observed_at\":\"2026-08-08T00:02:03Z\"},\"operator\":\"gte\",\"threshold_decimal\":\"120\"}",
            encoded,
        )
    }

    @Test
    fun `reading threshold payload rejects exponent and noncanonical leading zeros`() {
        listOf("1e3", "01.2", "+1").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                RestrictedEventPayload.ReadingThresholdCrossed(
                    displayText = value,
                    valueDecimal = value,
                    format = decimalFormat(value.substringAfter('.', "").length),
                    confidence = 0.9f,
                    sourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
                    observedAtEpochMillis = 0,
                    operator = ReadingOperator.GTE,
                    thresholdDecimal = "1",
                )
            }
        }
    }

    @Test
    fun `outside payload uses two ordered thresholds and never serializes a fake midpoint`() {
        val encoded = RestrictedEventPayloadJson.encode(
            RestrictedEventPayload.ReadingThresholdCrossed(
                displayText = "25",
                valueDecimal = "25",
                format = decimalFormat(0),
                confidence = 0.9f,
                sourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
                observedAtEpochMillis = 1_786_147_323_000,
                operator = ReadingOperator.OUTSIDE,
                lowerThresholdDecimal = "10",
                upperThresholdDecimal = "20",
            ),
        )

        assertEquals(true, encoded.contains("\"lower_threshold_decimal\":\"10\""))
        assertEquals(true, encoded.contains("\"upper_threshold_decimal\":\"20\""))
        assertEquals(false, encoded.contains("\"threshold_decimal\""))
        assertThrows(IllegalArgumentException::class.java) {
            RestrictedEventPayload.ReadingThresholdCrossed(
                displayText = "25",
                valueDecimal = "25",
                format = decimalFormat(0),
                confidence = 0.9f,
                sourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
                observedAtEpochMillis = 0,
                operator = ReadingOperator.OUTSIDE,
                lowerThresholdDecimal = "20",
                upperThresholdDecimal = "10",
            )
        }
    }

    private fun decimalFormat(
        fractionalDigits: Int,
        unit: String? = null,
    ) = ConfirmedReadingFormat(
        kind = ReadingFormatKind.DECIMAL,
        fractionalDigits = fractionalDigits,
        unit = unit,
    )
}
