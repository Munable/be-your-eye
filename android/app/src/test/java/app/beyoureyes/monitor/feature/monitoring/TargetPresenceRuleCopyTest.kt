package app.beyoureyes.monitor.feature.monitoring

import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.monitor.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetPresenceRuleCopyTest {
    @Test
    fun `only appears describes an open and close episode`() {
        assertEquals(
            R.string.presence_summary_appears,
            targetPresenceSummaryResource(PresenceRuleKind.APPEARS),
        )
        assertEquals(
            TargetPresenceCopySemantics(true, true),
            targetPresenceCopySemantics(PresenceRuleKind.APPEARS),
        )
        assertEquals(
            TargetPresenceCopySemantics(false, false),
            targetPresenceCopySemantics(PresenceRuleKind.REMAINS),
        )
        assertEquals(
            TargetPresenceCopySemantics(false, false),
            targetPresenceCopySemantics(PresenceRuleKind.DISAPPEARS),
        )
    }
}
