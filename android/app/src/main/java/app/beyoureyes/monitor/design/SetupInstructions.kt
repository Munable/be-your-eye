package app.beyoureyes.monitor.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R

/** Teach the actual input and camera flow; never show an unrelated target picture. */
@Composable
internal fun SetupInstructions(reference: Boolean, testTag: String) {
    ProductPanel(modifier = Modifier.testTag(testTag)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val steps = listOf(
                (if (reference) Icons.Outlined.AddPhotoAlternate else Icons.Outlined.Search) to
                    (if (reference) R.string.setup_reference_step else R.string.setup_generic_step),
                Icons.Outlined.CameraAlt to R.string.setup_camera_step,
                Icons.Outlined.NotificationsNone to R.string.setup_notify_step,
            )
            steps.forEach { (icon, text) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductIconBadge(icon, null)
                    Text(stringResource(text), modifier = Modifier.padding(start = 12.dp),
                        color = ProductColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
