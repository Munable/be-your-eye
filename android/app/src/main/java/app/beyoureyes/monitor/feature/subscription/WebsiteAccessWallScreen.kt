package app.beyoureyes.monitor.feature.subscription

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductPrimaryButton
import kotlinx.coroutines.launch

internal const val WEBSITE_ACCOUNT_URL = "https://beyoureye.com/account/"

@Composable
internal fun WebsiteAccessWallScreen(
    accountState: CloudAccountState,
    subscription: PlaySubscriptionController,
    onOpenAccount: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    BackHandler(enabled = true) { }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by subscription.state.collectAsState()
    val account = accountState as? CloudAccountState.SignedIn
    Surface(color = ProductColors.Background, modifier = Modifier.fillMaxSize().testTag(SubscriptionWallTags.WALL)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Be Your Eye · 帮你盯", style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.website_access_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.website_access_summary), color = ProductColors.TextSecondary)
            account?.email?.let { Text(stringResource(R.string.website_same_account, it)) }
            ProductPrimaryButton(
                text = stringResource(if (account == null) R.string.account_email_sign_in_or_register else R.string.website_open_account),
                onClick = {
                    if (account == null) onOpenAccount()
                    else context.startActivity(Intent(Intent.ACTION_VIEW, WEBSITE_ACCOUNT_URL.toUri()))
                },
                modifier = Modifier.fillMaxWidth().testTag(SubscriptionWallTags.CTA),
            )
            OutlinedButton(
                enabled = account != null && state !is PlaySubscriptionState.Loading,
                onClick = { account?.let { scope.launch { subscription.refresh(it.accountId, force = true) } } },
                modifier = Modifier.fillMaxWidth().testTag(SubscriptionWallTags.RESTORE),
            ) { Text(stringResource(R.string.website_refresh_access)) }
            if (state is PlaySubscriptionState.VerificationUnavailable) {
                Text((state as PlaySubscriptionState.VerificationUnavailable).notice, color = ProductColors.TextSecondary)
            }
            TextButton(onClick = onOpenAccount) { Text(stringResource(R.string.subscription_account)) }
            TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.subscription_local_history)) }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://beyoureye.com/terms/".toUri())) }) {
                Text(stringResource(R.string.subscription_terms))
            }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://beyoureye.com/privacy/".toUri())) }) {
                Text(stringResource(R.string.subscription_privacy))
            }
        }
    }
}
