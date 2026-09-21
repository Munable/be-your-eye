package app.beyoureyes.monitor.feature.assistant

import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistantPresentationTest {
    @Test
    fun `proposal requires and exposes the exact signed model name`() {
        assertEquals("EfficientDet-Lite2", assistantModelDisplayName("EfficientDet-Lite2"))
        assertThrows(IllegalArgumentException::class.java) {
            assistantModelDisplayName("")
        }
    }

    @Test
    fun `assistant access gate distinguishes account action from entitlement refresh`() {
        assertEquals(
            R.string.action_open_account,
            assistantAccessPresentation(ProductAccessDecision.SIGN_IN_REQUIRED).actionRes,
        )
        assertEquals(
            R.string.action_open_account,
            assistantAccessPresentation(ProductAccessDecision.PRO_REQUIRED).actionRes,
        )
        assertEquals(
            R.string.action_check_subscription,
            assistantAccessPresentation(ProductAccessDecision.VERIFICATION_REQUIRED).actionRes,
        )
        assertEquals(
            R.string.assistant_issue_verification_title,
            assistantAccessPresentation(ProductAccessDecision.VERIFICATION_REQUIRED).titleRes,
        )
    }
}
