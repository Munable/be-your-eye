package app.beyoureyes.monitor.feature.subscription

import java.time.Instant

internal const val MONTHLY_BASE_PLAN_ID = "monthly-auto"
internal const val ANNUAL_BASE_PLAN_ID = "annual-auto"
internal const val THREE_DAY_TRIAL_PERIOD = "P3D"

internal enum class SubscriptionBillingPeriod(val iso8601: String) {
    MONTHLY("P1M"),
    ANNUAL("P1Y"),
}

internal data class SubscriptionPlan(
    val basePlanId: String,
    val billingPeriod: SubscriptionBillingPeriod,
    val formattedPrice: String,
    val priceMicros: Long,
    val offerToken: String,
    val trialPeriod: String?,
) {
    init {
        require(basePlanId == MONTHLY_BASE_PLAN_ID || basePlanId == ANNUAL_BASE_PLAN_ID)
        require(formattedPrice.isNotBlank() && priceMicros > 0 && offerToken.isNotBlank())
        require(trialPeriod == null || trialPeriod == THREE_DAY_TRIAL_PERIOD)
    }
}

internal sealed interface PlaySubscriptionState {
    data object SignedOut : PlaySubscriptionState
    data object Loading : PlaySubscriptionState
    data class VerificationUnavailable(val notice: String) : PlaySubscriptionState
    data class Ready(
        val active: Boolean,
        val providerState: String,
        val expiresAt: Instant?,
        val refreshAfter: Instant?,
        val pending: Boolean = false,
        val notice: String? = null,
    ) : PlaySubscriptionState
}

internal sealed interface SubscriptionPlansState {
    data object Loading : SubscriptionPlansState
    data object Ready : SubscriptionPlansState
    data class Unavailable(val notice: String) : SubscriptionPlansState
}
