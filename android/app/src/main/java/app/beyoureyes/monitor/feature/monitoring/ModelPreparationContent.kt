package app.beyoureyes.monitor.feature.monitoring

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.beyoureyes.monitor.ModelPreparationProgress
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.resolve

/** The existing camera-entry surface also owns first-download disclosure; no extra route. */
@Composable
internal fun LoadingScreen(state: MonitorCameraState.Loading, onBack: () -> Unit) {
    val context = LocalContext.current
    val request = (state.progress as? ModelPreparationProgress.AwaitingDownload)?.request
    val download = state.progress as? ModelPreparationProgress.Downloading
    val cached = state.progress as? ModelPreparationProgress.UsingDownloaded
    Surface(Modifier.fillMaxSize(), color = ProductColors.Background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (request != null || download != null) {
                Icon(Icons.Outlined.Download, null, Modifier.size(48.dp), tint = ProductColors.Cyan)
            } else {
                CircularProgressIndicator(color = ProductColors.Cyan)
            }
            Text(
                state.message.resolve(),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (request != null) {
                Text(request.purpose, color = ProductColors.TextSecondary, textAlign = TextAlign.Center)
                ProductPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.model_download_model_label), style = MaterialTheme.typography.labelMedium)
                        Text(request.modelName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.model_download_size, Formatter.formatShortFileSize(context, request.totalBytes)),
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.titleLarge,
                            color = ProductColors.Cyan,
                        )
                    }
                }
                Text(
                    stringResource(R.string.model_download_reuse),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ProductColors.TextSecondary,
                )
                Text(
                    stringResource(if (request.meteredNetwork) R.string.model_download_metered else R.string.model_download_network),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (request.meteredNetwork) ProductColors.Amber else ProductColors.TextMuted,
                )
                Button(onClick = request::confirm, modifier = Modifier.fillMaxWidth().testTag("model_download_confirm")) {
                    Text(stringResource(R.string.model_download_confirm))
                }
            } else if (download != null) {
                Text(download.modelName, color = ProductColors.TextSecondary, textAlign = TextAlign.Center)
                LinearProgressIndicator(
                    progress = { (download.downloadedBytes.toDouble() / download.totalBytes).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("model_download_progress"),
                    color = ProductColors.Cyan,
                )
                Text(
                    stringResource(
                        R.string.model_download_progress,
                        (download.downloadedBytes * 100 / download.totalBytes).toInt(),
                        Formatter.formatShortFileSize(context, download.downloadedBytes),
                        Formatter.formatShortFileSize(context, download.totalBytes),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.model_download_wait), color = ProductColors.TextMuted)
            } else {
                if (cached != null) Text(cached.modelName, color = ProductColors.TextSecondary)
                Text(
                    stringResource(if (cached != null) R.string.model_download_cached_body else R.string.model_preparation_body),
                    color = ProductColors.TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(when {
                    request != null -> R.string.model_download_later
                    download != null -> R.string.model_download_cancel
                    else -> R.string.action_back
                }))
            }
        }
    }
}
