package app.beyoureyes.monitor.feature.account

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.core.data.cloud.CloudDeviceRow
import app.beyoureyes.monitor.CloudBootstrap
import app.beyoureyes.monitor.BuildConfig
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.ProductSectionHeader
import app.beyoureyes.monitor.design.ProductSwitch
import app.beyoureyes.monitor.design.ProductTone
import app.beyoureyes.monitor.design.ProductTopBar
import app.beyoureyes.monitor.feature.subscription.PlaySubscriptionController
import app.beyoureyes.monitor.feature.subscription.PlaySubscriptionState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

internal object AccountPanelTags {
    const val PANEL = "account_panel"
    const val EMAIL = "account_email"
    const val PASSWORD = "account_password"
    const val SIGN_IN = "account_sign_in"
    const val SIGN_UP = "account_sign_up"
    const val CANCEL = "account_cancel"
    const val FORGOT_PASSWORD = "account_forgot_password"
    const val RECOVERY_EMAIL = "account_recovery_email"
    const val RECOVERY_SEND = "account_recovery_send"
    const val RECOVERY_PASSWORD = "account_recovery_password"
    const val RECOVERY_CONFIRM = "account_recovery_confirm"
    const val SUBSCRIPTION = "account_subscription"
    const val SIGN_OUT = "account_sign_out"
    const val DELETE = "account_delete"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val ACCOUNT_EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private enum class AccountAuthMode { CREDENTIALS, REQUEST_RECOVERY, COMPLETE_RECOVERY }

/** Mirrors the finite email/password contract enforced by the Supabase data layer. */
internal data class AccountCredentialDraft(
    val email: String = "",
    val password: String = "",
) {
    val emailValid: Boolean
        get() = email.length in 3..320 && ACCOUNT_EMAIL.matches(email.trim())
    val passwordValid: Boolean
        get() = password.length in 8..256
    val canSubmit: Boolean
        get() = emailValid && passwordValid

    fun cleared() = AccountCredentialDraft()
}

/** Account, subscription recovery, device revocation, and destructive account controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    playSubscription: PlaySubscriptionController,
    onClose: () -> Unit,
) {
    if (BuildConfig.COMMUNITY_BUILD) {
        CommunityAboutScreen(onClose = onClose, showBackButton = showBackButton)
        return
    }
    val context = LocalContext.current
    val resources = LocalResources.current
    val focusManager = LocalFocusManager.current
    val controller = remember(context) { CloudBootstrap.accountController(context) }
    val accountState by controller.state.collectAsState()
    val subscriptionState by playSubscription.state.collectAsState()
    val subscriptionPlans by playSubscription.plans.collectAsState()
    val passwordRecoveryPending by controller.passwordRecoveryPending.collectAsState()
    val notificationsEnabled by CloudBootstrap.notificationsEnabled(context)
        .collectAsState(initial = false)
    val peers = remember { mutableStateListOf<CloudDeviceRow>() }
    val scope = rememberCoroutineScope()
    var credentials by remember { mutableStateOf(AccountCredentialDraft()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showThirdPartyLicenses by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf<CloudDeviceRow?>(null) }
    var currentDeviceId by remember { mutableStateOf<String?>(null) }
    var authSheetOpen by remember { mutableStateOf(false) }
    var authMode by remember { mutableStateOf(AccountAuthMode.CREDENTIALS) }
    var recoveryEmail by remember { mutableStateOf("") }
    var recoveryPassword by remember { mutableStateOf("") }
    var recoveryPasswordConfirmation by remember { mutableStateOf("") }
    val privacyPolicyUrl = BuildConfig.PRIVACY_POLICY_URL.trim()
    val thirdPartyLicenses = remember(resources) {
        resources.openRawResource(R.raw.third_party_licenses)
            .bufferedReader()
            .use { it.readText() }
    }

    fun clearCredentials() {
        credentials = credentials.cleared()
        recoveryEmail = ""
        recoveryPassword = ""
        recoveryPasswordConfirmation = ""
    }

    fun closeAccount() {
        if (busy && authMode == AccountAuthMode.COMPLETE_RECOVERY) return
        if (passwordRecoveryPending) controller.cancelPasswordRecovery()
        clearCredentials()
        authSheetOpen = false
        onClose()
    }

    BackHandler { closeAccount() }

    fun runAccountAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        messageIsError = false
        focusManager.clearFocus()
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageIsError = true
                message = context.getString(R.string.account_action_failed)
            } finally {
                busy = false
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch { CloudBootstrap.setNotificationsEnabled(context, true) }
        } else {
            message = context.getString(R.string.account_notification_permission_needed)
        }
    }

    LaunchedEffect(accountState) {
        if (accountState is CloudAccountState.SignedIn) {
            val signedIn = accountState as CloudAccountState.SignedIn
            clearCredentials()
            currentDeviceId = runCatching { controller.currentDeviceId() }.getOrNull()
            runCatching { controller.peerDevices() }
                .onSuccess { values -> peers.apply { clear(); addAll(values) } }
            runCatching { playSubscription.refresh(signedIn.accountId) }
        } else {
            peers.clear()
            currentDeviceId = null
            playSubscription.signOut()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { playSubscription.refreshPlans() }
    }

    LaunchedEffect(passwordRecoveryPending) {
        if (passwordRecoveryPending) {
            authMode = AccountAuthMode.COMPLETE_RECOVERY
            authSheetOpen = true
        }
    }

    Surface(
        modifier = modifier.fillMaxSize().testTag(AccountPanelTags.PANEL),
        color = ProductColors.Background,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ProductTopBar(
                title = stringResource(
                    if (showBackButton) R.string.account_title_sync else R.string.account_title_devices,
                ),
                onBack = if (showBackButton) ({ closeAccount() }) else null,
                eyebrow = if (showBackButton) null else stringResource(R.string.account_eyebrow),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            androidx.compose.material3.HorizontalDivider(color = ProductColors.Border)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProductPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val signedIn = accountState as? CloudAccountState.SignedIn
                        ProductIconBadge(
                            if (signedIn != null) Icons.Outlined.CloudDone else Icons.Outlined.Lock,
                            null,
                            tint = ProductColors.Green,
                            background = ProductColors.GreenSoft,
                        )
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                stringResource(
                                    if (signedIn != null) {
                                        R.string.account_signed_in
                                    } else {
                                        R.string.account_sign_in_continue
                                    },
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (signedIn != null) {
                                    stringResource(
                                        R.string.account_signed_in_summary,
                                        signedIn.email ?: stringResource(R.string.account_connected_fallback),
                                    )
                                } else {
                                    stringResource(
                                        if (playSubscription.websiteBillingEnabled) {
                                            R.string.website_access_summary
                                        } else if (playSubscription.playBillingEnabled) {
                                            R.string.account_signed_out_summary
                                        } else {
                                            R.string.account_internal_signed_out_summary
                                        },
                                    )
                                },
                                color = ProductColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                when (val state = accountState) {
                    is CloudAccountState.Disabled -> {
                        ProductPanel(tone = ProductTone.WAITING) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProductIconBadge(
                                    Icons.Outlined.CloudOff,
                                    null,
                                    tint = ProductColors.Amber,
                                    background = ProductColors.AmberSoft,
                                )
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text(
                                        stringResource(R.string.account_cloud_unavailable_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(R.string.account_cloud_unavailable_detail),
                                        color = ProductColors.TextSecondary,
                                    )
                                }
                            }
                        }
                    }

                    CloudAccountState.SignedOut -> {
                        ProductPrimaryButton(
                            text = stringResource(R.string.account_email_sign_in_or_register),
                            onClick = {
                                message = null
                                authMode = AccountAuthMode.CREDENTIALS
                                authSheetOpen = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = Icons.Outlined.PersonOutline,
                        )
                    }

                    is CloudAccountState.SignedIn -> {
                        ProductSectionHeader(
                            stringResource(
                                if (playSubscription.websiteBillingEnabled) {
                                    R.string.website_access_section
                                } else if (playSubscription.playBillingEnabled) {
                                    R.string.account_subscription_section
                                } else {
                                    R.string.account_internal_access_section
                                },
                            ),
                        )
                        when (val subscription = subscriptionState) {
                            PlaySubscriptionState.SignedOut,
                            PlaySubscriptionState.Loading,
                            -> ProductPanel(tone = ProductTone.WAITING) {
                                Text(
                                    stringResource(R.string.account_subscription_checking),
                                    color = ProductColors.TextSecondary,
                                )
                            }
                            is PlaySubscriptionState.VerificationUnavailable -> {
                                ProductPanel(tone = ProductTone.WAITING) {
                                    Text(
                                        subscription.notice,
                                        color = ProductColors.TextSecondary,
                                    )
                                }
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            playSubscription.refresh(state.accountId, force = true)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.account_subscription_retry)) }
                            }
                            is PlaySubscriptionState.Ready -> {
                                ProductPanel(
                                    tone = if (subscription.active) ProductTone.ACTIVE else ProductTone.WAITING,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ProductIconBadge(
                                            Icons.Outlined.AutoAwesome,
                                            null,
                                            tint = if (subscription.active) ProductColors.Green else ProductColors.Amber,
                                            background = if (subscription.active) {
                                                ProductColors.GreenSoft
                                            } else {
                                                ProductColors.AmberSoft
                                            },
                                        )
                                        Column(Modifier.padding(start = 14.dp)) {
                                            Text(
                                                stringResource(
                                                    if (subscription.active) {
                                                        if (playSubscription.websiteBillingEnabled) {
                                                            R.string.website_access_enabled
                                                        } else if (playSubscription.playBillingEnabled) {
                                                            R.string.account_subscription_enabled
                                                        } else {
                                                            R.string.account_internal_access_enabled
                                                        }
                                                    } else {
                                                        if (playSubscription.websiteBillingEnabled) {
                                                            R.string.website_access_required
                                                        } else if (playSubscription.playBillingEnabled) {
                                                            R.string.account_subscription_required
                                                        } else {
                                                            R.string.account_internal_access_required
                                                        }
                                                    },
                                                ),
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text(
                                                when {
                                                    subscription.active ->
                                                        stringResource(
                                                            if (playSubscription.websiteBillingEnabled) {
                                                                R.string.website_access_enabled_detail
                                                            } else if (playSubscription.playBillingEnabled) {
                                                                R.string.account_subscription_enabled_detail
                                                            } else {
                                                                R.string.account_internal_access_enabled_detail
                                                            },
                                                        )
                                                    subscription.pending ->
                                                        stringResource(R.string.account_subscription_pending_detail)
                                                    else ->
                                                        stringResource(
                                                            if (playSubscription.websiteBillingEnabled) {
                                                                R.string.website_access_summary
                                                            } else if (playSubscription.playBillingEnabled) {
                                                                R.string.account_subscription_required_detail
                                                            } else {
                                                                R.string.account_internal_access_required_detail
                                                            },
                                                        )
                                                },
                                                color = ProductColors.TextSecondary,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                                if (!subscription.active) {
                                    val annualLabel = stringResource(R.string.account_plan_annual)
                                    val monthlyLabel = stringResource(R.string.account_plan_monthly)
                                    val trialSuffix = stringResource(R.string.account_plan_trial_suffix)
                                    subscriptionPlans.forEachIndexed { index, plan ->
                                        ProductPrimaryButton(
                                            text = buildString {
                                                append(
                                                    if (plan.billingPeriod == app.beyoureyes.monitor.feature.subscription.SubscriptionBillingPeriod.ANNUAL) {
                                                        annualLabel
                                                    } else {
                                                        monthlyLabel
                                                    },
                                                )
                                                append(" · ${plan.formattedPrice}")
                                                if (plan.trialPeriod == app.beyoureyes.monitor.feature.subscription.THREE_DAY_TRIAL_PERIOD) {
                                                    append(trialSuffix)
                                                }
                                            },
                                            onClick = {
                                                context.findActivity()?.let { activity ->
                                                    playSubscription.launchPurchase(activity, plan.basePlanId)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().then(
                                                if (index == 0) Modifier.testTag(AccountPanelTags.SUBSCRIPTION) else Modifier,
                                            ),
                                            leadingIcon = Icons.Outlined.AutoAwesome,
                                        )
                                    }
                                    if (subscriptionPlans.isEmpty()) {
                                        Text(
                                            stringResource(
                                                if (playSubscription.websiteBillingEnabled) {
                                                    R.string.website_access_summary
                                                } else if (playSubscription.playBillingEnabled) {
                                                    R.string.account_prices_unavailable
                                                } else {
                                                    R.string.account_internal_access_missing
                                                },
                                            ),
                                            color = ProductColors.TextSecondary,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            playSubscription.refresh(state.accountId, force = true)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        stringResource(
                                            if (playSubscription.websiteBillingEnabled) {
                                                R.string.website_refresh_access
                                            } else if (playSubscription.playBillingEnabled) {
                                                R.string.account_restore_subscription
                                            } else {
                                                R.string.subscription_internal_refresh
                                            },
                                        ),
                                    )
                                }
                                subscription.notice?.let { notice ->
                                    Text(
                                        notice,
                                        color = ProductColors.TextMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }

                        ProductSectionHeader(stringResource(R.string.account_this_phone))
                        ProductPanel {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    ProductIconBadge(Icons.Outlined.NotificationsNone, null)
                                    Column(Modifier.padding(start = 14.dp)) {
                                        Text(
                                            stringResource(R.string.account_receive_peer_alerts),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            stringResource(R.string.account_peer_alerts_off_detail),
                                            color = ProductColors.TextSecondary,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                ProductSwitch(
                                    checked = notificationsEnabled,
                                    accessibilityLabel = stringResource(R.string.account_receive_peer_alerts),
                                    onCheckedChange = { enabled ->
                                        if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            scope.launch {
                                                CloudBootstrap.setNotificationsEnabled(context, enabled)
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        ProductSectionHeader(
                            stringResource(R.string.account_peer_devices),
                            count = peers.count { it.revokedAt == null },
                        )
                        peers.filter { it.revokedAt == null }.forEach { device ->
                            ProductPanel {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        ProductIconBadge(Icons.Outlined.Devices, null)
                                        Column(Modifier.padding(start = 14.dp)) {
                                            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                stringResource(
                                                    if (device.deviceId == currentDeviceId) {
                                                        R.string.account_current_device
                                                    } else {
                                                        R.string.account_connected_device
                                                    },
                                                ),
                                                color = ProductColors.TextSecondary,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                    if (device.deviceId != currentDeviceId) {
                                        OutlinedButton(
                                            enabled = !busy,
                                            onClick = { confirmRevoke = device },
                                        ) { Text(stringResource(R.string.account_disable_device)) }
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    runAccountAction {
                                        controller.syncNow()
                                        peers.apply { clear(); addAll(controller.peerDevices()) }
                                        message = context.getString(R.string.account_sync_complete)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Sync, contentDescription = null)
                                Text(stringResource(R.string.account_sync_now), Modifier.padding(start = 7.dp))
                            }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    runAccountAction {
                                        controller.signOut()
                                        clearCredentials()
                                        message = context.getString(R.string.account_signed_out_message)
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag(AccountPanelTags.SIGN_OUT),
                            ) { Text(stringResource(R.string.account_sign_out)) }
                        }

                        if (controller.accountDeletionAvailable) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { confirmDelete = true },
                                modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.DELETE),
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = ProductColors.Error)
                                Text(
                                    stringResource(R.string.account_delete),
                                    Modifier.padding(start = 8.dp),
                                    color = ProductColors.Error,
                                )
                            }
                            Text(
                                stringResource(
                                    if (playSubscription.websiteBillingEnabled) {
                                        R.string.website_delete_summary
                                    } else {
                                        R.string.account_delete_summary
                                    },
                                ),
                                color = ProductColors.TextMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (!authSheetOpen) message?.let {
                    ProductPanel(tone = if (messageIsError) ProductTone.ERROR else ProductTone.ACTIVE) {
                        Text(it, color = if (messageIsError) ProductColors.Error else ProductColors.Green)
                    }
                }

                ProductSectionHeader(stringResource(R.string.account_local_settings))
                ProductPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProductIconBadge(Icons.Outlined.NotificationsNone, null)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(stringResource(R.string.account_notifications), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.account_notifications_detail),
                                    color = ProductColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(color = ProductColors.Border)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProductIconBadge(Icons.Outlined.PrivacyTip, null)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(stringResource(R.string.account_privacy_data), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.account_privacy_detail),
                                    color = ProductColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    enabled = privacyPolicyUrl.isNotEmpty(),
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, privacyPolicyUrl.toUri()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.account_view_privacy)) }

                ProductSectionHeader(stringResource(R.string.account_about_section))
                ProductPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.account_about_product),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.account_version, BuildConfig.VERSION_NAME),
                            color = ProductColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.account_about_summary),
                            color = ProductColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (playSubscription.websiteBillingEnabled) {
                    OutlinedButton(
                        onClick = { context.openExternalUrl("https://beyoureye.com/account/") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.website_open_account)) }
                }
                if (playSubscription.playBillingEnabled) {
                    OutlinedButton(
                        onClick = { context.openExternalUrl(PLAY_SUBSCRIPTION_MANAGEMENT_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.account_manage_subscription)) }
                }
                OutlinedButton(
                    onClick = { context.openExternalUrl(SUBSCRIPTION_TERMS_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.account_view_subscription_terms)) }
                OutlinedButton(
                    onClick = { context.openEmail(SUPPORT_EMAIL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.account_contact_support)) }
                OutlinedButton(
                    onClick = { context.openEmail(SECURITY_EMAIL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.account_report_security_issue)) }
                OutlinedButton(
                    onClick = { showThirdPartyLicenses = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.account_third_party_licenses)) }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (authSheetOpen &&
        (accountState is CloudAccountState.SignedOut || passwordRecoveryPending)
    ) {
        ModalBottomSheet(
            onDismissRequest = {
                if (!busy || authMode != AccountAuthMode.COMPLETE_RECOVERY) {
                    if (authMode == AccountAuthMode.COMPLETE_RECOVERY) {
                        controller.cancelPasswordRecovery()
                    }
                    clearCredentials()
                    authSheetOpen = false
                }
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ProductColors.SurfaceRaised,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                message?.let {
                    ProductPanel(tone = if (messageIsError) ProductTone.ERROR else ProductTone.ACTIVE) {
                        Text(
                            it,
                            color = if (messageIsError) ProductColors.Error else ProductColors.Green,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                when (authMode) {
                    AccountAuthMode.CREDENTIALS -> AccountCredentialsContent(
                        credentials = credentials,
                        busy = busy,
                        recoveryAvailable = controller.passwordRecoveryAvailable,
                        onCredentialsChange = { credentials = it },
                        onSignIn = {
                            val submitted = credentials
                            runAccountAction {
                                controller.signIn(submitted.email, submitted.password)
                                clearCredentials()
                                authSheetOpen = false
                                message = context.getString(R.string.account_signed_in_syncing)
                            }
                        },
                        onSignUp = {
                            val submitted = credentials
                            runAccountAction {
                                val result = controller.signUp(submitted.email, submitted.password)
                                clearCredentials()
                                authSheetOpen = false
                                message = if (result is CloudAccountState.SignedOut) {
                                    context.getString(R.string.account_created_confirm_email)
                                } else {
                                    context.getString(R.string.account_created_signed_in)
                                }
                            }
                        },
                        onForgotPassword = {
                            message = null
                            recoveryEmail = credentials.email
                            credentials = credentials.cleared()
                            authMode = AccountAuthMode.REQUEST_RECOVERY
                        },
                        onCancel = {
                            clearCredentials()
                            authSheetOpen = false
                        },
                    )
                    AccountAuthMode.REQUEST_RECOVERY -> AccountRecoveryRequestContent(
                        email = recoveryEmail,
                        busy = busy,
                        onEmailChange = { recoveryEmail = it.take(320) },
                        onSubmit = {
                            val submitted = recoveryEmail
                            runAccountAction {
                                controller.requestPasswordReset(submitted)
                                clearCredentials()
                                authSheetOpen = false
                                authMode = AccountAuthMode.CREDENTIALS
                                message = context.getString(R.string.account_reset_email_sent)
                            }
                        },
                        onBack = {
                            message = null
                            authMode = AccountAuthMode.CREDENTIALS
                        },
                    )
                    AccountAuthMode.COMPLETE_RECOVERY -> AccountRecoveryCompleteContent(
                        password = recoveryPassword,
                        confirmation = recoveryPasswordConfirmation,
                        busy = busy,
                        onPasswordChange = { recoveryPassword = it.take(256) },
                        onConfirmationChange = { recoveryPasswordConfirmation = it.take(256) },
                        onSubmit = {
                            val submitted = recoveryPassword
                            runAccountAction {
                                controller.completePasswordRecovery(submitted)
                                clearCredentials()
                                authSheetOpen = false
                                authMode = AccountAuthMode.CREDENTIALS
                                message = context.getString(R.string.account_password_updated)
                            }
                        },
                        onCancel = {
                            controller.cancelPasswordRecovery()
                            clearCredentials()
                            authSheetOpen = false
                            authMode = AccountAuthMode.CREDENTIALS
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.account_delete_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        if (playSubscription.websiteBillingEnabled) {
                            R.string.website_delete_dialog_body
                        } else {
                            R.string.account_delete_dialog_body
                        },
                    ),
                )
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }, enabled = !busy) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        confirmDelete = false
                        runAccountAction {
                            controller.deleteAccount()
                            clearCredentials()
                            peers.clear()
                            message = context.getString(R.string.account_deleted_message)
                        }
                    },
                ) { Text(stringResource(R.string.account_confirm_delete)) }
            },
        )
    }

    if (showThirdPartyLicenses) {
        AlertDialog(
            onDismissRequest = { showThirdPartyLicenses = false },
            title = { Text(stringResource(R.string.account_third_party_licenses)) },
            text = {
                Text(
                    thirdPartyLicenses,
                    modifier = Modifier.height(420.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showThirdPartyLicenses = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    confirmRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirmRevoke = null },
            title = { Text(stringResource(R.string.account_disable_dialog_title)) },
            text = { Text(stringResource(R.string.account_disable_dialog_body, device.displayName)) },
            dismissButton = {
                OutlinedButton(onClick = { confirmRevoke = null }, enabled = !busy) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        confirmRevoke = null
                        runAccountAction {
                            check(controller.revokePeerDevice(device.deviceId)) {
                                "device is already revoked"
                            }
                            peers.apply { clear(); addAll(controller.peerDevices()) }
                            message = context.getString(
                                R.string.account_disabled_message,
                                device.displayName,
                            )
                        }
                    },
                ) { Text(stringResource(R.string.account_confirm_disable)) }
            },
        )
    }
}

private fun Context.openExternalUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

private fun Context.openEmail(address: String) {
    runCatching { startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$address".toUri())) }
}

private const val SUBSCRIPTION_TERMS_URL = "https://beyoureye.com/terms/"
private const val PLAY_SUBSCRIPTION_MANAGEMENT_URL =
    "https://play.google.com/store/account/subscriptions?sku=be_your_eye_pro&package=app.beyoureyes.monitor"
private const val SUPPORT_EMAIL = "support@beyoureye.com"
private const val SECURITY_EMAIL = "security@beyoureye.com"

@Composable
private fun AccountCredentialsContent(
    credentials: AccountCredentialDraft,
    busy: Boolean,
    recoveryAvailable: Boolean,
    onCredentialsChange: (AccountCredentialDraft) -> Unit,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Text(stringResource(R.string.account_email_account), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.account_email_account_detail),
        color = ProductColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = credentials.email,
        onValueChange = { onCredentialsChange(credentials.copy(email = it.take(320))) },
        label = { Text(stringResource(R.string.account_email_label)) },
        singleLine = true,
        isError = credentials.email.isNotEmpty() && !credentials.emailValid,
        supportingText = if (credentials.email.isNotEmpty() && !credentials.emailValid) {
            { Text(stringResource(R.string.account_email_invalid)) }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
        ),
        modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.EMAIL),
    )
    OutlinedTextField(
        value = credentials.password,
        onValueChange = { onCredentialsChange(credentials.copy(password = it.take(256))) },
        label = { Text(stringResource(R.string.account_password_label)) },
        singleLine = true,
        isError = credentials.password.isNotEmpty() && !credentials.passwordValid,
        supportingText = if (credentials.password.isNotEmpty() && !credentials.passwordValid) {
            { Text(stringResource(R.string.account_password_invalid)) }
        } else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                if (credentials.canSubmit && !busy) onSignIn() else focusManager.clearFocus()
            },
        ),
        modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.PASSWORD),
    )
    ProductPrimaryButton(
        text = stringResource(if (busy) R.string.account_signing_in else R.string.account_sign_in),
        onClick = onSignIn,
        enabled = !busy && credentials.canSubmit,
        modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.SIGN_IN),
        leadingIcon = Icons.Outlined.PersonOutline,
    )
    OutlinedButton(
        enabled = !busy && credentials.canSubmit,
        onClick = onSignUp,
        modifier = Modifier.fillMaxWidth().height(50.dp).testTag(AccountPanelTags.SIGN_UP),
    ) { Text(stringResource(R.string.account_create)) }
    if (!credentials.canSubmit) {
        Text(
            stringResource(R.string.account_credentials_required),
            color = ProductColors.TextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (recoveryAvailable) {
        TextButton(
            enabled = !busy,
            onClick = onForgotPassword,
            modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.FORGOT_PASSWORD),
        ) { Text(stringResource(R.string.account_forgot_password)) }
    }
    TextButton(
        enabled = !busy,
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.CANCEL),
    ) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun AccountRecoveryRequestContent(
    email: String,
    busy: Boolean,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val valid = email.length in 3..320 && ACCOUNT_EMAIL.matches(email.trim())
    Text(stringResource(R.string.account_reset_password), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.account_reset_privacy),
        color = ProductColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text(stringResource(R.string.account_email_label)) },
        singleLine = true,
        isError = email.isNotEmpty() && !valid,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (valid && !busy) onSubmit() else focusManager.clearFocus() },
        ),
        modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.RECOVERY_EMAIL),
    )
    ProductPrimaryButton(
        text = stringResource(if (busy) R.string.account_sending else R.string.account_send_reset),
        onClick = onSubmit,
        enabled = !busy && valid,
        modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.RECOVERY_SEND),
    )
    TextButton(
        enabled = !busy,
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.account_back_to_sign_in)) }
}

@Composable
private fun AccountRecoveryCompleteContent(
    password: String,
    confirmation: String,
    busy: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val valid = password.length in 8..256 && password == confirmation
    Text(stringResource(R.string.account_set_new_password), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.account_recovery_verified),
        color = ProductColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.account_new_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
        ),
        modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.RECOVERY_PASSWORD),
    )
    OutlinedTextField(
        value = confirmation,
        onValueChange = onConfirmationChange,
        label = { Text(stringResource(R.string.account_repeat_new_password)) },
        singleLine = true,
        isError = confirmation.isNotEmpty() && password != confirmation,
        supportingText = if (confirmation.isNotEmpty() && password != confirmation) {
            { Text(stringResource(R.string.account_password_mismatch)) }
        } else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (valid && !busy) onSubmit() else focusManager.clearFocus() },
        ),
        modifier = Modifier.fillMaxWidth().testTag(AccountPanelTags.RECOVERY_CONFIRM),
    )
    ProductPrimaryButton(
        text = stringResource(if (busy) R.string.account_updating else R.string.account_update_password),
        onClick = onSubmit,
        enabled = !busy && valid,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(
        enabled = !busy,
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.action_cancel)) }
}
