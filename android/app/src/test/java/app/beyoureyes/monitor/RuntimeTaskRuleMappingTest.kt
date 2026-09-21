package app.beyoureyes.monitor

import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.RuntimeMonitorRule
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeTaskRuleMappingTest {
    @Test
    fun visualConditionsAndDurationsReachTheirRuntimeRules() {
        assertEquals(
            RuntimeMonitorRule.PresenceEpisode(
                appearanceConfirmMillis = 3_000,
                disappearanceConfirmMillis = 5_000,
            ),
            MonitorRule.TargetPresence(PresenceRuleKind.APPEARS, 3).toRuntimeRule(),
        )
        assertEquals(
            RuntimeMonitorRule.Presence(durationMillis = 10_000),
            MonitorRule.TargetPresence(PresenceRuleKind.REMAINS, 10).toRuntimeRule(),
        )
        assertEquals(
            RuntimeMonitorRule.Absence(durationMillis = 30_000),
            MonitorRule.TargetPresence(PresenceRuleKind.DISAPPEARS, 30).toRuntimeRule(),
        )
    }

    @Test
    fun `pending reading rule keeps its unconfigured marker in the runtime rule`() {
        val runtime = MonitorRule.ReadingThreshold.Single(
            comparison = ReadingComparison.GT,
            thresholdDecimal = "0",
            configured = false,
        ).toRuntimeRule() as RuntimeMonitorRule.ReadingThreshold.Single

        assertEquals(false, runtime.configured)
        assertEquals("0", runtime.thresholdDecimal)
    }

    @Test
    fun readingDurationReachesBothRuntimeThresholdVariants() {
        assertEquals(
            1_000L,
            MonitorRule.ReadingThreshold.Single(
                thresholdDecimal = "10",
                configured = true,
            ).toRuntimeRule().let { (it as RuntimeMonitorRule.ReadingThreshold).durationMillis },
        )
        assertEquals(
            RuntimeMonitorRule.ReadingThreshold.Single(
                operator = app.beyoureyes.core.domain.ReadingOperator.GT,
                thresholdDecimal = "10",
                durationMillis = 3_000,
            ),
            MonitorRule.ReadingThreshold.Single(
                comparison = ReadingComparison.GT,
                thresholdDecimal = "10",
                configured = true,
                durationSeconds = 3,
            ).toRuntimeRule(),
        )
        assertEquals(
            RuntimeMonitorRule.ReadingThreshold.Outside(
                lowerThresholdDecimal = "10",
                upperThresholdDecimal = "20",
                durationMillis = 30_000,
            ),
            MonitorRule.ReadingThreshold.Outside(
                lowerThresholdDecimal = "10",
                upperThresholdDecimal = "20",
                configured = true,
                durationSeconds = 30,
            ).toRuntimeRule(),
        )
    }
}
