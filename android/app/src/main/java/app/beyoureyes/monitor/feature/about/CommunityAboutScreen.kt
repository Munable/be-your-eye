package app.beyoureyes.monitor.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.beyoureyes.monitor.BuildConfig
import app.beyoureyes.monitor.R

@Composable
internal fun CommunityAboutScreen(onClose: () -> Unit, showBackButton: Boolean, onPairedAlerts: () -> Unit = {}) {
    val context = LocalContext.current
    var showNotices by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showBackButton) TextButton(onClick = onClose) { Text(stringResource(R.string.community_back)) }
            Text(stringResource(R.string.community_brand), style = MaterialTheme.typography.headlineSmall)
            Text(BuildConfig.VERSION_NAME)
            Text(stringResource(R.string.community_about))
            LanguageSettingsCard()
            TextButton(onClick = onPairedAlerts) { Text(stringResource(R.string.peer_title)) }
            TextButton(onClick = { showNotices = !showNotices }) { Text(stringResource(R.string.community_notices)) }
            if (showNotices) Text(remember {
                context.resources.openRawResource(R.raw.third_party_licenses).bufferedReader().use { it.readText() }
            })
        }
    }
}
