package app.beyoureyes.monitor.feature.subscription

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** No billing SDK, network, account lease or fake purchase exists in this distribution. */
@Suppress("UNUSED_PARAMETER")
internal class PlaySubscriptionController(
    context: Context,
    entitlementGateway: SubscriptionEntitlementGateway,
    val playBillingEnabled: Boolean = false,
    val websiteBillingEnabled: Boolean = false,
) : AutoCloseable {
    val state: StateFlow<PlaySubscriptionState> = MutableStateFlow(PlaySubscriptionState.SignedOut)
    val plans: StateFlow<List<SubscriptionPlan>> = MutableStateFlow(emptyList())
    val plansState: StateFlow<SubscriptionPlansState> = MutableStateFlow(SubscriptionPlansState.Unavailable("Community"))
    suspend fun refreshPlans() = Unit
    suspend fun refresh(signedInAccountId: String, force: Boolean = false) = Unit
    fun signOut() = Unit
    fun stateForAccount(signedInAccountId: String): PlaySubscriptionState = PlaySubscriptionState.SignedOut
    fun launchPurchase(activity: Activity, basePlanId: String) = Unit
    override fun close() = Unit
}
