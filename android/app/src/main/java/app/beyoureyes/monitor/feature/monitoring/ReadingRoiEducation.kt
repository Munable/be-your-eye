package app.beyoureyes.monitor.feature.monitoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
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
import app.beyoureyes.monitor.design.ProductPanel

internal object ReadingRoiEducationTags {
    const val CARD = "reading_roi_education"
}

/** Setup guidance; the camera screen removes it on the first drag. */
@Composable
internal fun ReadingRoiEducationCard(modifier: Modifier = Modifier) {
    ProductPanel(
        modifier = modifier.testTag(ReadingRoiEducationTags.CARD),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CropFree,
                    contentDescription = null,
                    tint = ProductColors.Cyan,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        stringResource(R.string.reading_roi_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.reading_roi_body),
                        modifier = Modifier.padding(top = 3.dp),
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = ProductColors.Cyan,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.reading_roi_fixed_phone),
                    modifier = Modifier.padding(start = 8.dp),
                    color = ProductColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
