package app.beyoureyes.core.data

import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.core.domain.ReadingComparison
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorStorageCodecTest {
    @Test
    fun `pending single reading rule survives a storage round trip`() {
        val pending = MonitorRule.ReadingThreshold.Single(
            comparison = ReadingComparison.GT,
            thresholdDecimal = "0",
            configured = false,
            durationSeconds = 10,
        )

        val decoded = MonitorStorageCodec.decodeRule(MonitorStorageCodec.encodeRule(pending))

        assertEquals(pending, decoded)
    }

    @Test
    fun `configured single reading rule survives a storage round trip`() {
        val configured = MonitorRule.ReadingThreshold.Single(
            comparison = ReadingComparison.LTE,
            thresholdDecimal = "36.5",
            configured = true,
            durationSeconds = 30,
        )

        val decoded = MonitorStorageCodec.decodeRule(MonitorStorageCodec.encodeRule(configured))

        assertEquals(configured, decoded)
    }

    @Test
    fun `outside reading rule survives a storage round trip`() {
        val outside = MonitorRule.ReadingThreshold.Outside(
            lowerThresholdDecimal = "1.5",
            upperThresholdDecimal = "9.75",
            configured = true,
            durationSeconds = 60,
        )

        val decoded = MonitorStorageCodec.decodeRule(MonitorStorageCodec.encodeRule(outside))

        assertEquals(outside, decoded)
    }

    @Test
    fun `presence rules survive a storage round trip`() {
        PresenceRuleKind.entries.forEach { kind ->
            val rule = MonitorRule.TargetPresence(kind = kind, durationSeconds = 60)

            val decoded = MonitorStorageCodec.decodeRule(MonitorStorageCodec.encodeRule(rule))

            assertEquals(rule, decoded)
        }
    }
}
