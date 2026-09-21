package app.beyoureyes.monitor.feature.subscription

import androidx.annotation.StringRes
import app.beyoureyes.monitor.R

internal enum class ProductAccessDecision {
    GRANTED,
    SIGN_IN_REQUIRED,
    PRO_REQUIRED,
    VERIFICATION_REQUIRED,
}

/** One fail-closed presentation decision shared by every gated product surface. */
internal fun productAccessDecision(
    accessState: ProductAccessState,
): ProductAccessDecision = when (accessState) {
    is ProductAccessState.Granted -> ProductAccessDecision.GRANTED
    ProductAccessState.SignedOut -> ProductAccessDecision.SIGN_IN_REQUIRED
    ProductAccessState.Initializing -> ProductAccessDecision.VERIFICATION_REQUIRED
    is ProductAccessState.Locked -> when (accessState.reason) {
        ProductLockReason.SUBSCRIPTION_REQUIRED,
        ProductLockReason.PURCHASE_PENDING,
        ProductLockReason.PAYMENT_PAUSED,
        ProductLockReason.PAYMENT_ON_HOLD,
        ProductLockReason.EXPIRED,
        ProductLockReason.REVOKED,
        -> ProductAccessDecision.PRO_REQUIRED
        ProductLockReason.VERIFICATION_REQUIRED,
        ProductLockReason.SERVICE_UNAVAILABLE,
        -> ProductAccessDecision.VERIFICATION_REQUIRED
    }
}

@StringRes
internal fun ProductAccessDecision.messageResource(): Int = when (this) {
    ProductAccessDecision.GRANTED -> error("granted access has no error message")
    ProductAccessDecision.SIGN_IN_REQUIRED ->
        R.string.access_sign_in_required
    ProductAccessDecision.PRO_REQUIRED ->
        R.string.access_subscription_required
    ProductAccessDecision.VERIFICATION_REQUIRED ->
        R.string.access_verification_required
}
