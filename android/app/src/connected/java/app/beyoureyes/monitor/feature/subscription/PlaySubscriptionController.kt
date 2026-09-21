package app.beyoureyes.monitor.feature.subscription

import android.app.Activity
import android.content.Context
import app.beyoureyes.monitor.R
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

private const val THREE_DAY_TRIAL_OFFER_ID = "trial-3d"

/** One account entitlement shared by every feature, with two Play-managed billing periods. */
internal class PlaySubscriptionController(
    context: Context,
    private val entitlementGateway: SubscriptionEntitlementGateway,
    private val leaseStore: SecureEntitlementLeaseStore = SecureEntitlementLeaseStore(context),
    val playBillingEnabled: Boolean = true,
    val websiteBillingEnabled: Boolean = false,
) : PurchasesUpdatedListener, AutoCloseable {
    private val appContext = context.applicationContext
    private val controllerJob = SupervisorJob()
    private val scope = CoroutineScope(controllerJob + Dispatchers.Main.immediate)
    private val refreshMutex = Mutex()
    private val billingClient = if (playBillingEnabled) {
        BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .enableAutoServiceReconnection()
            .build()
    } else {
        null
    }
    private val mutableState = MutableStateFlow<PlaySubscriptionState>(PlaySubscriptionState.SignedOut)
    val state: StateFlow<PlaySubscriptionState> = mutableState.asStateFlow()
    private val mutablePlans = MutableStateFlow<List<SubscriptionPlan>>(emptyList())
    val plans: StateFlow<List<SubscriptionPlan>> = mutablePlans.asStateFlow()
    private val mutablePlansState =
        MutableStateFlow<SubscriptionPlansState>(SubscriptionPlansState.Loading)
    val plansState: StateFlow<SubscriptionPlansState> = mutablePlansState.asStateFlow()

    @Volatile
    private var accountId: String? = null
    private var currentOffers: Map<String, ResolvedSubscriptionOffer> = emptyMap()
    private var scheduledEntitlementRefresh: Job? = null

    suspend fun refreshPlans() {
        refreshMutex.withLock {
            beginPlanLoad()
            if (!playBillingEnabled) {
                setPlansUnavailable(appContext.getString(if (websiteBillingEnabled) R.string.website_access_summary else R.string.subscription_internal_test_access))
                return@withLock
            }
            val connectionCode = connect()
            if (connectionCode != BillingClient.BillingResponseCode.OK) {
                setPlansUnavailable(billingNotice(appContext, connectionCode))
                return@withLock
            }
            loadOffersFromConnectedPlay()
        }
    }

    suspend fun refresh(signedInAccountId: String, force: Boolean = false) {
        require(signedInAccountId.isNotBlank())
        refreshMutex.withLock {
            val now = Instant.now()
            val current = mutableState.value
            if (!force && accountId == signedInAccountId &&
                current is PlaySubscriptionState.Ready && current.active &&
                current.expiresAt?.isAfter(now) == true &&
                current.refreshAfter?.isAfter(now) == true
            ) {
                return@withLock
            }

            if (accountId != signedInAccountId) cancelScheduledEntitlementRefresh()
            val cachedLease = leaseStore.load(signedInAccountId, now)
            if (accountId != signedInAccountId || cachedLease == null) {
                mutableState.value = PlaySubscriptionState.Loading
            } else {
                mutableState.value = cachedLease.toReady(
                    notice = appContext.getString(R.string.subscription_refreshing_online),
                )
            }
            accountId = signedInAccountId

            val serverResult = entitlementGateway.status()
            var entitlement = serverResult.completedOrNull()
            val purchasesResult = if (playBillingEnabled) {
                val connectionCode = connect()
                val playConnected = connectionCode == BillingClient.BillingResponseCode.OK
                if (playConnected) {
                    loadOffersFromConnectedPlay()
                    queryPurchases()
                } else {
                    setPlansUnavailable(billingNotice(appContext, connectionCode))
                    BillingQueryResult(connectionCode, emptyList())
                }
            } else {
                setPlansUnavailable(appContext.getString(if (websiteBillingEnabled) R.string.website_access_summary else R.string.subscription_internal_test_access))
                BillingQueryResult(BillingClient.BillingResponseCode.OK, emptyList())
            }
            val purchases = purchasesResult.value
            val playOperational = !playBillingEnabled ||
                purchasesResult.responseCode == BillingClient.BillingResponseCode.OK

            val purchased = purchases
                .filter { it.products.contains(PRO_SUBSCRIPTION_PRODUCT_ID) }
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .sortedByDescending(Purchase::getPurchaseTime)
            for (purchase in purchased) {
                val verified = entitlementGateway.verifyPurchase(purchase.purchaseToken).completedOrNull()
                if (verified != null) entitlement = verified
                if (verified?.active == true) break
            }
            val pending = purchases.any {
                it.products.contains(PRO_SUBSCRIPTION_PRODUCT_ID) &&
                    it.purchaseState == Purchase.PurchaseState.PENDING
            }
            if (accountId != signedInAccountId) return@withLock

            if (entitlement != null) {
                val ready = entitlement.toReady(
                    pending = pending,
                    notice = if (playOperational) {
                        null
                    } else {
                        billingNotice(appContext, purchasesResult.responseCode)
                    },
                )
                val lease = ready.toLeaseOrNull(signedInAccountId, now)
                if (lease != null) {
                    leaseStore.save(lease)
                    scheduleEntitlementRefresh(lease)
                } else {
                    leaseStore.clear()
                    cancelScheduledEntitlementRefresh()
                }
                mutableState.value = ready
            } else if (cachedLease != null) {
                mutableState.value = cachedLease.toReady(
                    pending = pending,
                    notice = appContext.getString(
                        R.string.subscription_offline_lease,
                        cachedLease.refreshAfter,
                    ),
                )
                scheduleEntitlementRefresh(cachedLease)
            } else {
                cancelScheduledEntitlementRefresh()
                mutableState.value = PlaySubscriptionState.VerificationUnavailable(
                    serverResult.unavailableNotice(appContext),
                )
            }
        }
    }

    fun signOut() {
        accountId = null
        cancelScheduledEntitlementRefresh()
        leaseStore.clear()
        mutableState.value = PlaySubscriptionState.SignedOut
    }

    /** Never lend a previously verified entitlement to a different signed-in account. */
    fun stateForAccount(signedInAccountId: String): PlaySubscriptionState =
        if (accountId == signedInAccountId) state.value else PlaySubscriptionState.Loading

    fun launchPurchase(activity: Activity, basePlanId: String) {
        if (!playBillingEnabled) return
        val account = accountId ?: return
        val offer = currentOffers[basePlanId] ?: return
        val product = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(offer.details)
            .setOfferToken(offer.plan.offerToken)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(product))
            .setObfuscatedAccountId(playAccountHash(account))
            .build()
        val result = checkNotNull(billingClient).launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            updateNotice(billingNotice(appContext, result.responseCode))
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val account = accountId ?: return
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
            -> scope.launch { refresh(account, force = true) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> updateNotice(billingNotice(appContext, result.responseCode))
        }
    }

    override fun close() {
        accountId = null
        cancelScheduledEntitlementRefresh()
        controllerJob.cancel()
        billingClient?.endConnection()
    }

    private fun scheduleEntitlementRefresh(lease: EntitlementLease) {
        cancelScheduledEntitlementRefresh()
        scheduledEntitlementRefresh = scope.launch {
            delay(
                entitlementRefreshDelayMillis(
                    refreshAfter = lease.refreshAfter,
                    expiresAt = lease.expiresAt,
                    now = Instant.now(),
                ),
            )
            if (accountId == lease.accountId) {
                scheduledEntitlementRefresh = null
                refresh(lease.accountId, force = true)
            }
        }
    }

    private fun cancelScheduledEntitlementRefresh() {
        scheduledEntitlementRefresh?.cancel()
        scheduledEntitlementRefresh = null
    }

    private fun updateNotice(notice: String) {
        mutableState.value = when (val current = mutableState.value) {
            is PlaySubscriptionState.Ready -> current.copy(notice = notice)
            else -> PlaySubscriptionState.VerificationUnavailable(notice)
        }
    }

    private fun updateOffers(offers: List<ResolvedSubscriptionOffer>) {
        currentOffers = offers.associateBy { it.plan.basePlanId }
        mutablePlans.value = offers.map(ResolvedSubscriptionOffer::plan)
            .sortedByDescending { it.billingPeriod == SubscriptionBillingPeriod.ANNUAL }
        mutablePlansState.value = SubscriptionPlansState.Ready
    }

    private fun beginPlanLoad() {
        currentOffers = emptyMap()
        mutablePlans.value = emptyList()
        mutablePlansState.value = SubscriptionPlansState.Loading
    }

    private fun setPlansUnavailable(notice: String) {
        currentOffers = emptyMap()
        mutablePlans.value = emptyList()
        mutablePlansState.value = SubscriptionPlansState.Unavailable(notice)
    }

    private suspend fun loadOffersFromConnectedPlay() {
        val result = queryOffers()
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            setPlansUnavailable(billingNotice(appContext, result.responseCode))
            return
        }
        if (!hasCompleteSubscriptionPlanSet(result.value.map { it.plan })) {
            setPlansUnavailable(appContext.getString(R.string.subscription_prices_unavailable))
            return
        }
        updateOffers(result.value)
    }

    private suspend fun connect(): Int {
        val client = billingClient ?: return BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
        if (client.isReady) return BillingClient.BillingResponseCode.OK
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) {
                        continuation.resume(result.responseCode)
                    }
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    private suspend fun queryOffers(): BillingQueryResult<List<ResolvedSubscriptionOffer>> =
        suspendCancellableCoroutine { continuation ->
            val client = checkNotNull(billingClient)
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_SUBSCRIPTION_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
            client.queryProductDetailsAsync(params) { result, detailsResult ->
                val details = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    detailsResult.productDetailsList.singleOrNull {
                        it.productId == PRO_SUBSCRIPTION_PRODUCT_ID &&
                            it.productType == BillingClient.ProductType.SUBS
                    }
                } else {
                    null
                }
                if (continuation.isActive) {
                    continuation.resume(
                        BillingQueryResult(result.responseCode, details?.resolvedOffers().orEmpty()),
                    )
                }
            }
        }

    private suspend fun queryPurchases(): BillingQueryResult<List<Purchase>> =
        suspendCancellableCoroutine { continuation ->
            val client = checkNotNull(billingClient)
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .includeSuspendedSubscriptions(true)
                .build()
            client.queryPurchasesAsync(params) { result, purchases ->
                if (continuation.isActive) {
                    continuation.resume(
                        BillingQueryResult(
                            result.responseCode,
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                                purchases
                            } else {
                                emptyList()
                            },
                        ),
                    )
                }
            }
        }
}

private data class BillingQueryResult<T>(
    val responseCode: Int,
    val value: T,
)

internal data class SubscriptionPricingPhaseCandidate(
    val billingPeriod: String,
    val formattedPrice: String,
    val priceMicros: Long,
    val billingCycleCount: Int,
    val recurrenceMode: Int,
)

internal data class SubscriptionOfferCandidate(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val phases: List<SubscriptionPricingPhaseCandidate>,
)

internal fun selectSubscriptionPlans(
    candidates: List<SubscriptionOfferCandidate>,
): List<SubscriptionPlan> = listOf(
    ANNUAL_BASE_PLAN_ID to SubscriptionBillingPeriod.ANNUAL,
    MONTHLY_BASE_PLAN_ID to SubscriptionBillingPeriod.MONTHLY,
).mapNotNull { (basePlanId, billingPeriod) ->
    val matching = candidates.filter { candidate ->
        candidate.basePlanId == basePlanId &&
            candidate.phases.lastOrNull()?.let { phase ->
                phase.billingPeriod == billingPeriod.iso8601 &&
                    phase.priceMicros > 0 && phase.formattedPrice.isNotBlank() &&
                    phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
            } == true
    }
    val trial = matching.singleOrNull { candidate ->
        candidate.offerId == THREE_DAY_TRIAL_OFFER_ID && candidate.phases.size == 2 &&
            candidate.phases.first().let { phase ->
                phase.billingPeriod == THREE_DAY_TRIAL_PERIOD && phase.priceMicros == 0L
                    && phase.billingCycleCount == 1 &&
                    phase.recurrenceMode == ProductDetails.RecurrenceMode.FINITE_RECURRING
            } == true
    }
    val base = matching.singleOrNull { candidate ->
        candidate.offerId == null && candidate.phases.size == 1
    }
    val selected = trial ?: base ?: return@mapNotNull null
    val paid = checkNotNull(selected.phases.lastOrNull())
    SubscriptionPlan(
        basePlanId = basePlanId,
        billingPeriod = billingPeriod,
        formattedPrice = paid.formattedPrice,
        priceMicros = paid.priceMicros,
        offerToken = selected.offerToken,
        trialPeriod = if (selected === trial) THREE_DAY_TRIAL_PERIOD else null,
    )
}

private data class ResolvedSubscriptionOffer(
    val details: ProductDetails,
    val plan: SubscriptionPlan,
)

internal fun hasCompleteSubscriptionPlanSet(plans: List<SubscriptionPlan>): Boolean =
    plans.map(SubscriptionPlan::basePlanId).toSet() ==
        setOf(MONTHLY_BASE_PLAN_ID, ANNUAL_BASE_PLAN_ID) && plans.size == 2

private fun ProductDetails.resolvedOffers(): List<ResolvedSubscriptionOffer> {
    val offers = subscriptionOfferDetails.orEmpty()
    val candidates = offers.map { offer ->
        SubscriptionOfferCandidate(
            basePlanId = offer.basePlanId,
            offerId = offer.offerId,
            offerToken = offer.offerToken,
            phases = offer.pricingPhases.pricingPhaseList.map { phase ->
                SubscriptionPricingPhaseCandidate(
                    billingPeriod = phase.billingPeriod,
                    formattedPrice = phase.formattedPrice,
                    priceMicros = phase.priceAmountMicros,
                    billingCycleCount = phase.billingCycleCount,
                    recurrenceMode = phase.recurrenceMode,
                )
            },
        )
    }
    val selected = selectSubscriptionPlans(candidates)
    return selected.mapNotNull { plan ->
        offers.singleOrNull { it.offerToken == plan.offerToken }?.let {
            ResolvedSubscriptionOffer(this, plan)
        }
    }
}

private fun SubscriptionEntitlementResult.completedOrNull(): SubscriptionEntitlement? =
    (this as? SubscriptionEntitlementResult.Completed)?.entitlement

private fun SubscriptionEntitlementResult.unavailableNotice(context: Context): String = when (this) {
    is SubscriptionEntitlementResult.Completed ->
        context.getString(R.string.subscription_verification_incomplete)
    SubscriptionEntitlementResult.SignInRequired ->
        context.getString(R.string.subscription_sign_in_refresh)
    SubscriptionEntitlementResult.Unavailable ->
        context.getString(R.string.subscription_verification_unavailable)
}

private fun SubscriptionEntitlement.toReady(
    pending: Boolean = false,
    notice: String? = null,
): PlaySubscriptionState.Ready = PlaySubscriptionState.Ready(
    active = active,
    providerState = state,
    expiresAt = expiresAt,
    refreshAfter = refreshAfter,
    pending = pending,
    notice = notice,
)

private fun EntitlementLease.toReady(
    pending: Boolean = false,
    notice: String? = null,
): PlaySubscriptionState.Ready = PlaySubscriptionState.Ready(
    active = true,
    providerState = providerState,
    expiresAt = expiresAt,
    refreshAfter = refreshAfter,
    pending = pending,
    notice = notice,
)

private fun PlaySubscriptionState.Ready.toLeaseOrNull(
    accountId: String,
    now: Instant,
): EntitlementLease? {
    val expiry = expiresAt ?: return null
    val refresh = refreshAfter ?: return null
    if (!active || providerState !in GRANTED_PROVIDER_STATES) return null
    return runCatching { EntitlementLease(accountId, providerState, expiry, refresh) }
        .getOrNull()?.takeIf { it.isValidFor(accountId, now) }
}

internal fun playAccountHash(accountId: String): String = MessageDigest.getInstance("SHA-256")
    .digest(accountId.encodeToByteArray())
    .joinToString(separator = "") { "%02x".format(it) }

internal fun entitlementRefreshDelayMillis(
    refreshAfter: Instant,
    expiresAt: Instant,
    now: Instant,
): Long {
    val untilExpiry = (expiresAt.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0L)
    if (untilExpiry == 0L) return 0L
    val untilRefresh = refreshAfter.toEpochMilli() - now.toEpochMilli()
    return if (untilRefresh > 0L) {
        minOf(untilRefresh, untilExpiry)
    } else {
        minOf(ENTITLEMENT_REFRESH_RETRY_MILLIS, untilExpiry)
    }
}

private const val ENTITLEMENT_REFRESH_RETRY_MILLIS = 15L * 60L * 1_000L

private fun billingNotice(context: Context, responseCode: Int): String = when (responseCode) {
    BillingClient.BillingResponseCode.NETWORK_ERROR,
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
    -> context.getString(R.string.subscription_play_connection_lost)
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
        context.getString(R.string.subscription_billing_unavailable)
    else -> context.getString(R.string.subscription_action_incomplete)
}
