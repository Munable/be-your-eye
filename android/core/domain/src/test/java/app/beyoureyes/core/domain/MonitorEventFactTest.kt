package app.beyoureyes.core.domain

import org.junit.Assert.assertThrows
import org.junit.Test

class MonitorEventFactTest {
    @Test
    fun `outside reading fact requires two ordered thresholds`() {
        assertThrows(IllegalArgumentException::class.java) {
            MonitorEventFact.ReadingThresholdCrossed(
                displayText = "25",
                valueDecimal = "25",
                format = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
                unit = null,
                operator = MonitorReadingOperator.OUTSIDE,
                lowerThresholdDecimal = "20",
                upperThresholdDecimal = "10",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonitorEventFact.ReadingThresholdCrossed(
                displayText = "25",
                valueDecimal = "25",
                format = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
                unit = null,
                operator = MonitorReadingOperator.OUTSIDE,
                lowerThresholdDecimal = "10",
            )
        }
    }

    @Test
    fun `single reading fact rejects missing or mixed threshold shapes`() {
        assertThrows(IllegalArgumentException::class.java) {
            MonitorEventFact.ReadingThresholdCrossed(
                displayText = "25",
                valueDecimal = "25",
                format = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
                unit = null,
                operator = MonitorReadingOperator.GT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonitorEventFact.ReadingThresholdCrossed(
                displayText = "25",
                valueDecimal = "25",
                format = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
                unit = null,
                operator = MonitorReadingOperator.GT,
                thresholdDecimal = "20",
                lowerThresholdDecimal = "10",
            )
        }
    }
}
