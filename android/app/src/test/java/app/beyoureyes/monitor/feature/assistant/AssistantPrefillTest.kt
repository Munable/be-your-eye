package app.beyoureyes.monitor.feature.assistant

import app.beyoureyes.monitor.feature.reading.ReadingConditionMode
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantPrefillTest {
    @Test
    fun readingProposalBecomesTheRealInitialConditionDraft() {
        val proposal = MonitorConfigurationProposal.StructuredReading(
            title = "读数低于 12.5",
            catalogBinding = binding(),
            modelProfileKey = "numeric_display_reading",
            packageId = "numeric_reader_ppocrv6_medium_v1",
            intentKey = "reading.numeric.threshold",
            rule = AssistantReadingRule.Single(
                condition = AssistantReadingCondition.BELOW,
                thresholdDecimal = "12.5",
                durationSeconds = 3,
            ),
        )

        val draft = proposal.toReadingConditionDraft()

        assertEquals(ReadingConditionMode.BELOW, draft.mode)
        assertEquals("12.5", draft.threshold)
        assertEquals(3, draft.durationSeconds)
    }

    @Test
    fun visualConditionAndDurationBecomeThePersistedDomainRule() {
        assertEquals(
            MonitorRule.TargetPresence(PresenceRuleKind.DISAPPEARS, 30),
            AssistantPresenceRule(
                condition = AssistantPresenceCondition.DISAPPEARS,
                durationSeconds = 30,
            ).toMonitorRule(),
        )
    }

    private fun binding() = AssistantCatalogBinding(
        catalogId = "be-your-eye-internal",
        catalogVersion = "2026.08.24.1",
        catalogSignedPayloadSha256 = "a".repeat(64),
    )
}
