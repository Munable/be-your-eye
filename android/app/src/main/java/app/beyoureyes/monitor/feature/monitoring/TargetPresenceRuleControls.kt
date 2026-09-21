package app.beyoureyes.monitor.feature.monitoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductDurationChips

internal object TargetPresenceRuleTags {
    const val KIND_PREFIX = "target_presence_kind"
    const val DURATION_PREFIX = "target_presence_duration"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TargetPresenceRuleControls(
    rule: MonitorRule.TargetPresence,
    onRuleChange: (MonitorRule.TargetPresence) -> Unit,
    modifier: Modifier = Modifier,
    targetLabel: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.reading_when_record), style = MaterialTheme.typography.titleMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PresenceRuleKind.entries.forEach { kind ->
                FilterChip(
                    selected = rule.kind == kind,
                    onClick = { onRuleChange(rule.copy(kind = kind)) },
                    enabled = enabled,
                    label = { Text(kind.userLabel()) },
                    leadingIcon = if (rule.kind == kind) {
                        {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                    modifier = Modifier.testTag("${TargetPresenceRuleTags.KIND_PREFIX}_${kind.wireValue}"),
                )
            }
        }
        Text(stringResource(R.string.presence_duration_question), style = MaterialTheme.typography.titleMedium)
        ProductDurationChips(
            selectedSeconds = rule.durationSeconds,
            onSelected = { onRuleChange(rule.copy(durationSeconds = it)) },
            enabled = enabled,
            testTagPrefix = TargetPresenceRuleTags.DURATION_PREFIX,
        )
        Text(
            targetPresenceSetupBody(rule, targetLabel),
            color = ProductColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PresenceRuleKind.userLabel(): String = when (this) {
    PresenceRuleKind.APPEARS -> stringResource(R.string.presence_kind_appears)
    PresenceRuleKind.REMAINS -> stringResource(R.string.presence_kind_remains)
    PresenceRuleKind.DISAPPEARS -> stringResource(R.string.presence_kind_disappears)
}
