package app.beyoureyes.monitor.feature.monitoring

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
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
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductSwitch
import app.beyoureyes.monitor.design.ProductTone

internal const val LOCAL_NOTIFICATION_SETUP_TAG = "local_notification_setup"

@Composable
internal fun LocalNotificationSetupPanel(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProductPanel(
        modifier = modifier.fillMaxWidth().testTag(LOCAL_NOTIFICATION_SETUP_TAG),
        tone = if (checked) ProductTone.NEUTRAL else ProductTone.WAITING,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProductIconBadge(Icons.Outlined.NotificationsNone, null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.local_notification_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (checked) {
                        stringResource(R.string.local_notification_on)
                    } else {
                        stringResource(R.string.local_notification_off)
                    },
                    color = if (checked) ProductColors.TextMuted else ProductColors.Amber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ProductSwitch(
                checked = checked,
                accessibilityLabel = stringResource(R.string.local_notification_title),
                onCheckedChange = onCheckedChange,
            )
        }
    }
}
