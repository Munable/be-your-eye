package app.beyoureyes.monitor.feature.subscription

import com.android.billingclient.api.ProductDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaySubscriptionControllerTest {
    @Test
    fun `entitlement refresh uses the soft refresh boundary`() {
        val now = java.time.Instant.parse("2026-08-31T00:00:00Z")
        assertEquals(
            72L * 60L * 60L * 1_000L,
            entitlementRefreshDelayMillis(
                refreshAfter = now.plusSeconds(72L * 60L * 60L),
                expiresAt = now.plusSeconds(72L * 60L * 60L),
                now = now,
            ),
        )
    }

    @Test
    fun `failed soft refresh retries without invalidating or overrunning the lease`() {
        val now = java.time.Instant.parse("2026-08-31T00:00:00Z")
        assertEquals(
            15L * 60L * 1_000L,
            entitlementRefreshDelayMillis(
                refreshAfter = now.minusMillis(1),
                expiresAt = now.plusSeconds(7_200),
                now = now,
            ),
        )
        assertEquals(
            30_000L,
            entitlementRefreshDelayMillis(
                refreshAfter = now.minusMillis(1),
                expiresAt = now.plusSeconds(30),
                now = now,
            ),
        )
        assertEquals(
            0L,
            entitlementRefreshDelayMillis(
                refreshAfter = now.minusMillis(1),
                expiresAt = now,
                now = now,
            ),
        )
    }

    @Test
    fun `Play account binding is stable SHA-256 without account text`() {
        val accountId = "018f3f72-6e5c-7b4e-9a8f-1234567890ab"
        val hash = playAccountHash(accountId)
        assertEquals(64, hash.length)
        assertEquals("920ff54b82fc9766c78ec8061140fd9443c5a8945e0e0b7124711cf774e04432", hash)
        assertFalse(hash.contains(accountId))
        assertEquals(hash, playAccountHash(accountId))
    }

    @Test
    fun `monthly and annual plans prefer the exact eligible three day trial`() {
        val plans = selectSubscriptionPlans(
            listOf(
                offer(MONTHLY_BASE_PLAN_ID, null, "monthly-base", "P1M", "$5.99", 5_990_000),
                trial(MONTHLY_BASE_PLAN_ID, "monthly-trial", "P1M", "$5.99", 5_990_000),
                offer(ANNUAL_BASE_PLAN_ID, null, "annual-base", "P1Y", "$49.99", 49_990_000),
                trial(ANNUAL_BASE_PLAN_ID, "annual-trial", "P1Y", "$49.99", 49_990_000),
            ),
        )

        assertEquals(listOf(ANNUAL_BASE_PLAN_ID, MONTHLY_BASE_PLAN_ID), plans.map { it.basePlanId })
        assertEquals(listOf("annual-trial", "monthly-trial"), plans.map { it.offerToken })
        assertEquals(listOf(THREE_DAY_TRIAL_PERIOD, THREE_DAY_TRIAL_PERIOD), plans.map { it.trialPeriod })
        assertEquals(true, hasCompleteSubscriptionPlanSet(plans))
    }

    @Test
    fun `returning subscriber sees base plan without trial wording`() {
        val plan = selectSubscriptionPlans(
            listOf(offer(ANNUAL_BASE_PLAN_ID, null, "annual-base", "P1Y", "€49.99", 49_990_000)),
        ).single()

        assertEquals("€49.99", plan.formattedPrice)
        assertNull(plan.trialPeriod)
    }

    @Test
    fun `wrong periods and unknown offers are not exposed`() {
        assertEquals(
            emptyList<SubscriptionPlan>(),
            selectSubscriptionPlans(
                listOf(
                    offer(MONTHLY_BASE_PLAN_ID, null, "wrong-period", "P1Y", "$5.99", 5_990_000),
                    offer("legacy", null, "legacy", "P1M", "$1.00", 1_000_000),
                ),
            ),
        )
    }

    @Test
    fun `trial must be exactly one finite P3D phase followed by normal recurring price`() {
        val extraDiscount = SubscriptionOfferCandidate(
            basePlanId = ANNUAL_BASE_PLAN_ID,
            offerId = "trial-3d",
            offerToken = "unexpected-three-phase-offer",
            phases = listOf(
                freeTrialPhase(),
                SubscriptionPricingPhaseCandidate(
                    billingPeriod = "P1M",
                    formattedPrice = "$1.99",
                    priceMicros = 1_990_000,
                    billingCycleCount = 2,
                    recurrenceMode = ProductDetails.RecurrenceMode.FINITE_RECURRING,
                ),
                paidPhase("P1Y", "$49.99", 49_990_000),
            ),
        )
        val repeatingFreePhase = trial(
            basePlanId = MONTHLY_BASE_PLAN_ID,
            token = "repeating-free-phase",
            period = "P1M",
            price = "$5.99",
            micros = 5_990_000,
            freeCycleCount = 2,
        )

        assertEquals(emptyList<SubscriptionPlan>(), selectSubscriptionPlans(listOf(extraDiscount)))
        assertEquals(emptyList<SubscriptionPlan>(), selectSubscriptionPlans(listOf(repeatingFreePhase)))
    }

    @Test
    fun `a partial Play setup never becomes a purchasable plan set`() {
        val annualOnly = selectSubscriptionPlans(
            listOf(offer(ANNUAL_BASE_PLAN_ID, null, "annual-base", "P1Y", "$49.99", 49_990_000)),
        )

        assertEquals(false, hasCompleteSubscriptionPlanSet(annualOnly))
        assertEquals(false, hasCompleteSubscriptionPlanSet(emptyList()))
    }

    private fun offer(
        basePlanId: String,
        offerId: String?,
        token: String,
        period: String,
        price: String,
        micros: Long,
    ) = SubscriptionOfferCandidate(
        basePlanId = basePlanId,
        offerId = offerId,
        offerToken = token,
        phases = listOf(paidPhase(period, price, micros)),
    )

    private fun trial(
        basePlanId: String,
        token: String,
        period: String,
        price: String,
        micros: Long,
        freeCycleCount: Int = 1,
    ) = SubscriptionOfferCandidate(
        basePlanId = basePlanId,
        offerId = "trial-3d",
        offerToken = token,
        phases = listOf(
            freeTrialPhase(freeCycleCount),
            paidPhase(period, price, micros),
        ),
    )

    private fun freeTrialPhase(cycleCount: Int = 1) = SubscriptionPricingPhaseCandidate(
        billingPeriod = THREE_DAY_TRIAL_PERIOD,
        formattedPrice = "$0",
        priceMicros = 0,
        billingCycleCount = cycleCount,
        recurrenceMode = ProductDetails.RecurrenceMode.FINITE_RECURRING,
    )

    private fun paidPhase(period: String, price: String, micros: Long) =
        SubscriptionPricingPhaseCandidate(
            billingPeriod = period,
            formattedPrice = price,
            priceMicros = micros,
            billingCycleCount = 0,
            recurrenceMode = ProductDetails.RecurrenceMode.INFINITE_RECURRING,
        )
}
