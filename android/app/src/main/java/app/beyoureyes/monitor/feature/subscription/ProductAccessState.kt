package app.beyoureyes.monitor.feature.subscription

import app.beyoureyes.core.data.cloud.CloudAccountState
import java.time.Instant

internal data class EntitlementLease(
    val accountId: String,
    val providerState: String,
    val expiresAt: Instant,
    val refreshAfter: Instant,
) {
    init {
        require(accountId.isNotBlank())
        require(providerState in GRANTED_PROVIDER_STATES)
        require(!refreshAfter.isAfter(expiresAt))
    }

    fun isValidFor(accountId: String, now: Instant): Boolean =
        this.accountId == accountId && expiresAt.isAfter(now)
}

internal enum class ProductLockReason {
    SUBSCRIPTION_REQUIRED,
    PURCHASE_PENDING,
    PAYMENT_PAUSED,
    PAYMENT_ON_HOLD,
    EXPIRED,
    REVOKED,
    VERIFICATION_REQUIRED,
    SERVICE_UNAVAILABLE,
}

internal sealed interface ProductAccessState {
    data object Initializing : ProductAccessState
    data object SignedOut : ProductAccessState
    data class Locked(val reason: ProductLockReason) : ProductAccessState
    data class Granted(val lease: EntitlementLease) : ProductAccessState
}

internal fun productAccessState(
    accountState: CloudAccountState,
    subscriptionState: PlaySubscriptionState,
    now: Instant = Instant.now(),
): ProductAccessState {
    val account = when (accountState) {
        is CloudAccountState.SignedIn -> accountState
        CloudAccountState.SignedOut -> return ProductAccessState.SignedOut
        is CloudAccountState.Disabled -> return ProductAccessState.Locked(
            ProductLockReason.SERVICE_UNAVAILABLE,
        )
    }
    return when (subscriptionState) {
        PlaySubscriptionState.SignedOut -> ProductAccessState.SignedOut
        PlaySubscriptionState.Loading -> ProductAccessState.Initializing
        is PlaySubscriptionState.VerificationUnavailable -> ProductAccessState.Locked(
            ProductLockReason.VERIFICATION_REQUIRED,
        )
        is PlaySubscriptionState.Ready -> {
            val expiresAt = subscriptionState.expiresAt
            val refreshAfter = subscriptionState.refreshAfter
            if (expiresAt != null && !expiresAt.isAfter(now) &&
                subscriptionState.providerState in GRANTED_PROVIDER_STATES
            ) {
                ProductAccessState.Locked(ProductLockReason.EXPIRED)
            } else if (subscriptionState.active &&
                subscriptionState.providerState in GRANTED_PROVIDER_STATES &&
                expiresAt != null && refreshAfter != null
            ) {
                val lease = runCatching {
                    EntitlementLease(
                        accountId = account.accountId,
                        providerState = subscriptionState.providerState,
                        expiresAt = expiresAt,
                        refreshAfter = refreshAfter,
                    )
                }.getOrNull()
                if (lease?.isValidFor(account.accountId, now) == true) {
                    ProductAccessState.Granted(lease)
                } else {
                    ProductAccessState.Locked(ProductLockReason.VERIFICATION_REQUIRED)
                }
            } else {
                ProductAccessState.Locked(subscriptionState.providerState.toLockReason())
            }
        }
    }
}

internal fun ProductAccessState.isGranted(now: Instant = Instant.now()): Boolean =
    this is ProductAccessState.Granted && lease.expiresAt.isAfter(now)

private fun String.toLockReason(): ProductLockReason = when (this) {
    "SUBSCRIPTION_STATE_PENDING" -> ProductLockReason.PURCHASE_PENDING
    "SUBSCRIPTION_STATE_PAUSED" -> ProductLockReason.PAYMENT_PAUSED
    "SUBSCRIPTION_STATE_ON_HOLD" -> ProductLockReason.PAYMENT_ON_HOLD
    "SUBSCRIPTION_STATE_EXPIRED", "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" ->
        ProductLockReason.EXPIRED
    "SUBSCRIPTION_STATE_REVOKED" -> ProductLockReason.REVOKED
    "none" -> ProductLockReason.SUBSCRIPTION_REQUIRED
    else -> ProductLockReason.VERIFICATION_REQUIRED
}

internal val GRANTED_PROVIDER_STATES = setOf(
    "WEBSITE_PASS_ACTIVE",
    "WEBSITE_TRIAL_ACTIVE",
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED",
)
