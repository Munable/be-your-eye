package app.beyoureyes.monitor.feature.subscription

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductAccessDecisionTest {
    @Test
    fun `every model package follows the single product access state`() {
        val lease = EntitlementLease(
            accountId = "account",
            providerState = "SUBSCRIPTION_STATE_ACTIVE",
            expiresAt = Instant.parse("2026-09-30T00:00:00Z"),
            refreshAfter = Instant.parse("2026-09-03T00:00:00Z"),
        )
        assertEquals(
            ProductAccessDecision.GRANTED,
            productAccessDecision(ProductAccessState.Granted(lease)),
        )
        assertEquals(
            ProductAccessDecision.SIGN_IN_REQUIRED,
            productAccessDecision(ProductAccessState.SignedOut),
        )
        assertEquals(
            ProductAccessDecision.PRO_REQUIRED,
            productAccessDecision(
                ProductAccessState.Locked(ProductLockReason.SUBSCRIPTION_REQUIRED),
            ),
        )
        assertEquals(
            ProductAccessDecision.VERIFICATION_REQUIRED,
            productAccessDecision(
                ProductAccessState.Locked(ProductLockReason.VERIFICATION_REQUIRED),
            ),
        )
    }
}
