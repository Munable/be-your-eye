package app.beyoureyes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MonitorRuleTest {
    @Test
    fun `single and outside reading rules are distinct validated variants`() {
        val single = MonitorRule.ReadingThreshold.Single(
            comparison = ReadingComparison.LT,
            thresholdDecimal = "10.5",
            configured = true,
        )
        val outside = MonitorRule.ReadingThreshold.Outside(
            lowerThresholdDecimal = "5",
            upperThresholdDecimal = "20",
            configured = true,
        )

        assertEquals("10.5", single.thresholdDecimal)
        assertEquals(1, single.durationSeconds)
        assertEquals("5", outside.lowerThresholdDecimal)
        assertEquals("20", outside.upperThresholdDecimal)
    }

    @Test
    fun `reading confirmation duration accepts whole seconds in the product range`() {
        assertEquals(
            2,
            MonitorRule.ReadingThreshold.Single(
                thresholdDecimal = "10",
                configured = true,
                durationSeconds = 2,
            ).durationSeconds,
        )
        assertThrows(IllegalArgumentException::class.java) {
            MonitorRule.ReadingThreshold.Single(
                thresholdDecimal = "10",
                configured = true,
                durationSeconds = 61,
            )
        }
    }

    @Test
    fun `configured reading monitor requires a confirmed target format`() {
        assertThrows(IllegalArgumentException::class.java) {
            Monitor(
                id = "monitor",
                revision = 1,
                name = "计时器",
                target = MonitorTarget.NumericReading(),
                rule = MonitorRule.ReadingThreshold.Single(
                    thresholdDecimal = "10",
                    configured = true,
                ),
                createdAtEpochMillis = 0,
            )
        }
    }

    @Test
    fun `outside reading rule requires an ordered canonical range`() {
        listOf("20" to "20", "20" to "5", "05" to "20").forEach { (lower, upper) ->
            assertThrows(IllegalArgumentException::class.java) {
                MonitorRule.ReadingThreshold.Outside(lower, upper, configured = true)
            }
        }
    }
}
