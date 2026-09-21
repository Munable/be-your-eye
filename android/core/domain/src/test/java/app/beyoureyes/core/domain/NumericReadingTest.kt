package app.beyoureyes.core.domain

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NumericReadingTest {
    @Test
    fun `runtime reading keeps canonical decimal text as its only numeric truth`() {
        val reading = Observation.Reading(
            text = " -12.5 ",
            valueDecimal = "-12.5",
            stable = true,
            sourceSequence = 42,
            confidence = 0.98f,
            unit = "°C",
        )

        assertEquals(BigDecimal("-12.5"), reading.decimalValue)
        assertEquals("-12.5", reading.valueDecimal)
        assertEquals("°C", reading.unit)
        assertEquals(42L, reading.sourceSequence)
    }

    @Test
    fun `reading rejects units embedded in normalized numeric text`() {
        assertThrows(IllegalArgumentException::class.java) {
            Observation.Reading(
                text = "12.5V",
                valueDecimal = "12.5V",
                stable = false,
                sourceSequence = 1,
                confidence = 0.9f,
            )
        }
    }

    @Test
    fun `reading rejects noncanonical negative zero and leading zeros`() {
        listOf("-0", "-0.00", "01.2").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                Observation.Reading(value, value, false, 1, 0.9f)
            }
        }
    }

    @Test
    fun `state carries the same required stable frame count as JSON`() {
        val state = Observation.State("door:open", 0.95f, 7, 3)
        assertEquals(3, state.stableFrameCount)
        assertThrows(IllegalArgumentException::class.java) {
            Observation.State("door:open", 0.95f, 8, 0)
        }
    }

    @Test
    fun `canonical frame rejects incomplete buffers`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalReadingFrame(1, width = 2, height = 2, grayscaleBytes = byteArrayOf(1, 2))
        }
    }
}
