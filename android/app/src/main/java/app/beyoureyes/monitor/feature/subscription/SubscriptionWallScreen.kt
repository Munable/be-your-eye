package app.beyoureyes.monitor.feature.subscription

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.monitor.BuildConfig
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.ProductTone
import kotlinx.coroutines.launch

internal object SubscriptionWallTags {
    const val WALL = "subscription_wall"
    const val ANNUAL = "subscription_plan_annual"
    const val MONTHLY = "subscription_plan_monthly"
    const val CTA = "subscription_continue"
    const val RESTORE = "subscription_restore"
    const val HISTORY = "subscription_history"
}

@Composable
internal fun SubscriptionWallScreen(
    accessState: ProductAccessState,
    accountState: CloudAccountState,
    playSubscription: PlaySubscriptionController,
    onOpenAccount: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    if (playSubscription.websiteBillingEnabled) {
        WebsiteAccessWallScreen(accountState, playSubscription, onOpenAccount, onOpenHistory)
        return
    }
    BackHandler(enabled = true) { }
    val context = LocalContext.current
    val plans by playSubscription.plans.collectAsState()
    val plansState by playSubscription.plansState.collectAsState()
    val subscriptionState by playSubscription.state.collectAsState()
    val scope = rememberCoroutineScope()
    val internalEvaluation = !playSubscription.playBillingEnabled
    var selectedBasePlanId by remember { mutableStateOf(ANNUAL_BASE_PLAN_ID) }
    val selectedPlan = plans.singleOrNull { it.basePlanId == selectedBasePlanId }
        ?: plans.firstOrNull()

    LaunchedEffect(Unit) {
        runCatching { playSubscription.refreshPlans() }
    }

    Surface(
        modifier = Modifier.fillMaxSize().testTag(SubscriptionWallTags.WALL),
        color = ProductColors.Background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductIconBadge(
                    icon = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = ProductColors.Cyan,
                    background = ProductColors.CyanSoft,
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Be Your Eye", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.subscription_tagline),
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Text(
                stringResource(
                    if (internalEvaluation) {
                        R.string.subscription_internal_headline
                    } else {
                        R.string.subscription_headline
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    if (internalEvaluation) {
                        R.string.subscription_internal_summary
                    } else {
                        R.string.subscription_summary
                    },
                ),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
            )

            listOf(
                stringResource(R.string.subscription_benefit_all_features),
                stringResource(R.string.subscription_benefit_unlimited_devices),
                stringResource(R.string.subscription_benefit_readonly_history),
            ).forEach { benefit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = ProductColors.Green)
                    Text(benefit, modifier = Modifier.padding(start = 10.dp))
                }
            }

            when {
                internalEvaluation -> {
                    ProductPanel(tone = ProductTone.WAITING) {
                        Text(stringResource(R.string.subscription_internal_test_access))
                    }
                }
                plans.isNotEmpty() -> plans.forEach { plan ->
                    SubscriptionPlanCard(
                        plan = plan,
                        selected = selectedPlan?.basePlanId == plan.basePlanId,
                        onSelect = { selectedBasePlanId = plan.basePlanId },
                    )
                }
                plansState is SubscriptionPlansState.Loading -> {
                    ProductPanel(tone = ProductTone.WAITING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 14.dp))
                            Text(stringResource(R.string.subscription_loading_prices))
                        }
                    }
                }
                plansState is SubscriptionPlansState.Unavailable -> {
                    ProductPanel(tone = ProductTone.WAITING) {
                        Text((plansState as SubscriptionPlansState.Unavailable).notice)
                    }
                    OutlinedButton(
                        onClick = { scope.launch { playSubscription.refreshPlans() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.subscription_retry_prices)) }
                }
            }

            selectedPlan?.let { plan ->
                Text(
                    subscriptionDisclosure(plan),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val signedIn = accountState as? CloudAccountState.SignedIn
            ProductPrimaryButton(
                text = if (internalEvaluation) {
                    stringResource(
                        if (signedIn == null) {
                            R.string.subscription_internal_sign_in
                        } else {
                            R.string.subscription_internal_refresh
                        },
                    )
                } else {
                    when {
                        signedIn == null -> stringResource(R.string.subscription_sign_in_continue)
                        selectedPlan?.trialPeriod == THREE_DAY_TRIAL_PERIOD ->
                            stringResource(R.string.subscription_start_trial)
                        else -> stringResource(R.string.subscription_continue)
                    }
                },
                enabled = internalEvaluation || selectedPlan != null || signedIn == null,
                onClick = {
                    if (signedIn == null) {
                        onOpenAccount()
                    } else if (internalEvaluation) {
                        scope.launch { playSubscription.refresh(signedIn.accountId, force = true) }
                    } else {
                        selectedPlan?.let { plan ->
                            context.findActivity()?.let { activity ->
                                playSubscription.launchPurchase(activity, plan.basePlanId)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag(SubscriptionWallTags.CTA),
                leadingIcon = Icons.Outlined.AutoAwesome,
            )

            if (!internalEvaluation) {
                OutlinedButton(
                    onClick = {
                        if (signedIn == null) onOpenAccount() else scope.launch {
                            playSubscription.refresh(signedIn.accountId, force = true)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag(SubscriptionWallTags.RESTORE),
                ) { Text(stringResource(R.string.subscription_restore)) }
            }

            subscriptionNotice(accessState, subscriptionState)?.let { notice ->
                ProductPanel(tone = ProductTone.WAITING) {
                    Text(notice, color = ProductColors.TextSecondary)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenAccount, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PersonOutline, contentDescription = null)
                    Text(stringResource(R.string.subscription_account), modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f).testTag(SubscriptionWallTags.HISTORY),
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null)
                    Text(
                        stringResource(R.string.subscription_local_history),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (!internalEvaluation) {
                TextButton(
                    onClick = { context.openUrl(PLAY_SUBSCRIPTION_MANAGEMENT_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.subscription_manage_play)) }
            }
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    enabled = BuildConfig.PRIVACY_POLICY_URL.isNotBlank(),
                    onClick = { context.openUrl(BuildConfig.PRIVACY_POLICY_URL) },
                ) { Text(stringResource(R.string.subscription_privacy)) }
                TextButton(onClick = { context.openUrl(SUBSCRIPTION_TERMS_URL) }) {
                    Text(stringResource(R.string.subscription_terms))
                }
            }
        }
    }
}

@Composable
private fun SubscriptionPlanCard(
    plan: SubscriptionPlan,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val period = stringResource(
        if (plan.billingPeriod == SubscriptionBillingPeriod.ANNUAL) {
            R.string.subscription_plan_annual
        } else {
            R.string.subscription_plan_monthly
        },
    )
    val billingPeriod = stringResource(
        if (plan.billingPeriod == SubscriptionBillingPeriod.ANNUAL) {
            R.string.subscription_period_annual
        } else {
            R.string.subscription_period_monthly
        },
    )
    Surface(
        modifier = Modifier.fillMaxWidth()
            .testTag(
                if (plan.billingPeriod == SubscriptionBillingPeriod.ANNUAL) {
                    SubscriptionWallTags.ANNUAL
                } else {
                    SubscriptionWallTags.MONTHLY
                },
            )
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) ProductColors.CyanSoft else ProductColors.Surface,
        tonalElevation = if (selected) 3.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(period, style = MaterialTheme.typography.titleLarge)
                if (plan.billingPeriod == SubscriptionBillingPeriod.ANNUAL) {
                    Text(
                        stringResource(R.string.subscription_default_plan),
                        color = ProductColors.Cyan,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (plan.trialPeriod == THREE_DAY_TRIAL_PERIOD) {
                    Text(
                        stringResource(R.string.subscription_trial_available),
                        color = ProductColors.TextSecondary,
                    )
                }
            }
            Text(
                stringResource(R.string.subscription_plan_price, plan.formattedPrice, billingPeriod),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun subscriptionDisclosure(plan: SubscriptionPlan): String {
    val period = stringResource(
        if (plan.billingPeriod == SubscriptionBillingPeriod.ANNUAL) {
            R.string.subscription_period_annual
        } else {
            R.string.subscription_period_monthly
        },
    )
    return if (plan.trialPeriod == THREE_DAY_TRIAL_PERIOD) {
        stringResource(R.string.subscription_trial_disclosure, plan.formattedPrice, period)
    } else {
        stringResource(R.string.subscription_immediate_disclosure, plan.formattedPrice, period)
    }
}

@Composable
private fun subscriptionNotice(
    accessState: ProductAccessState,
    subscriptionState: PlaySubscriptionState,
): String? = when {
    subscriptionState is PlaySubscriptionState.Ready && subscriptionState.pending ->
        stringResource(R.string.subscription_pending_notice)
    subscriptionState is PlaySubscriptionState.VerificationUnavailable -> subscriptionState.notice
    accessState is ProductAccessState.Locked -> when (accessState.reason) {
        ProductLockReason.PAYMENT_PAUSED -> stringResource(R.string.subscription_paused_notice)
        ProductLockReason.PAYMENT_ON_HOLD -> stringResource(R.string.subscription_hold_notice)
        ProductLockReason.EXPIRED, ProductLockReason.REVOKED ->
            stringResource(R.string.subscription_ended_notice)
        ProductLockReason.VERIFICATION_REQUIRED -> stringResource(R.string.subscription_refresh_notice)
        ProductLockReason.SERVICE_UNAVAILABLE -> stringResource(R.string.subscription_service_notice)
        ProductLockReason.PURCHASE_PENDING -> stringResource(R.string.subscription_processing_notice)
        ProductLockReason.SUBSCRIPTION_REQUIRED -> null
    }
    else -> null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openUrl(url: String) {
    if (url.isBlank()) return
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}

private const val SUBSCRIPTION_TERMS_URL = "https://beyoureye.com/terms/"
private const val PLAY_SUBSCRIPTION_MANAGEMENT_URL =
    "https://play.google.com/store/account/subscriptions?sku=be_your_eye_pro&package=app.beyoureyes.monitor"
