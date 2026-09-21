package app.beyoureyes.monitor.feature.subscription

import app.beyoureyes.core.data.cloud.CloudAccountState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAccessStateTest {
    private val now = Instant.parse("2026-08-31T00:00:00Z")
    private val account = CloudAccountState.SignedIn(ACCOUNT_ID, "person@example.com")

    @Test
    fun `active grace and canceled before expiry are granted`() {
        GRANTED_PROVIDER_STATES.forEach { providerState ->
            val state = productAccessState(account, ready(providerState), now)
            assertTrue(providerState, state is ProductAccessState.Granted)
        }
    }

    @Test
    fun `pending paused hold expired and revoked are locked`() {
        val expected = mapOf(
            "SUBSCRIPTION_STATE_PENDING" to ProductLockReason.PURCHASE_PENDING,
            "SUBSCRIPTION_STATE_PAUSED" to ProductLockReason.PAYMENT_PAUSED,
            "SUBSCRIPTION_STATE_ON_HOLD" to ProductLockReason.PAYMENT_ON_HOLD,
            "SUBSCRIPTION_STATE_EXPIRED" to ProductLockReason.EXPIRED,
            "SUBSCRIPTION_STATE_REVOKED" to ProductLockReason.REVOKED,
        )
        expected.forEach { (providerState, reason) ->
            assertEquals(
                ProductAccessState.Locked(reason),
                productAccessState(account, ready(providerState, active = false), now),
            )
        }
    }

    @Test
    fun `refresh boundary stays granted until the signed lease expires`() {
        assertTrue(
            productAccessState(account, ready(refreshAfter = now), now) is
                ProductAccessState.Granted,
        )
        assertTrue(
            productAccessState(
                account,
                ready(refreshAfter = now.minusSeconds(60)),
                now,
            ) is ProductAccessState.Granted,
        )
    }

    @Test
    fun `granted provider states become expired at their exact expiry`() {
        GRANTED_PROVIDER_STATES.forEach { providerState ->
            assertEquals(
                providerState,
                ProductAccessState.Locked(ProductLockReason.EXPIRED),
                productAccessState(
                    account,
                    ready(
                        providerState = providerState,
                        active = false,
                        expiresAt = now,
                        refreshAfter = null,
                    ),
                    now,
                ),
            )
        }
    }

    @Test
    fun `invalid lease shape and account boundary fail closed`() {
        assertEquals(
            ProductAccessState.Locked(ProductLockReason.EXPIRED),
            productAccessState(account, ready(expiresAt = now, refreshAfter = now), now),
        )
        assertEquals(
            ProductAccessState.Locked(ProductLockReason.VERIFICATION_REQUIRED),
            productAccessState(
                account,
                ready(expiresAt = now.plusSeconds(60), refreshAfter = now.plusSeconds(61)),
                now,
            ),
        )
        assertEquals(
            ProductAccessState.SignedOut,
            productAccessState(CloudAccountState.SignedOut, ready(), now),
        )
    }

    private fun ready(
        providerState: String = "SUBSCRIPTION_STATE_ACTIVE",
        active: Boolean = true,
        expiresAt: Instant = now.plusSeconds(7_200),
        refreshAfter: Instant? = now.plusSeconds(3_600),
    ) = PlaySubscriptionState.Ready(
        active = active,
        providerState = providerState,
        expiresAt = expiresAt,
        refreshAfter = refreshAfter,
    )

    private companion object {
        const val ACCOUNT_ID = "018f3f72-6e5c-7b4e-9a8f-1234567890ab"
    }
}
