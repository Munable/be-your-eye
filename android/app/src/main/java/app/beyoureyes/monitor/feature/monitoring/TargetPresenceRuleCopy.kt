package app.beyoureyes.monitor.feature.monitoring

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.durationSecondsLabel

@Composable
internal fun targetPresenceRuleSummary(
    rule: MonitorRule.TargetPresence,
    targetLabel: String? = null,
): String {
    val target = targetLabel?.takeIf(String::isNotBlank) ?: stringResource(R.string.generic_target)
    val duration = durationSecondsLabel(rule.durationSeconds)
    return when (rule.kind) {
        PresenceRuleKind.APPEARS -> stringResource(
            R.string.presence_summary_appears,
            target,
            duration,
        )
        PresenceRuleKind.REMAINS -> stringResource(
            R.string.presence_summary_remains,
            target,
            duration,
        )
        PresenceRuleKind.DISAPPEARS -> stringResource(
            R.string.presence_summary_disappears,
            target,
            duration,
        )
    }
}

@Composable
internal fun targetPresenceSetupBody(
    rule: MonitorRule.TargetPresence,
    targetLabel: String? = null,
): String {
    val target = targetLabel?.takeIf(String::isNotBlank) ?: stringResource(R.string.generic_target)
    val duration = durationSecondsLabel(rule.durationSeconds)
    return when (rule.kind) {
        PresenceRuleKind.APPEARS -> stringResource(
            R.string.presence_setup_appears,
            target,
            duration,
        )
        PresenceRuleKind.REMAINS -> stringResource(
            R.string.presence_setup_remains,
            target,
            duration,
        )
        PresenceRuleKind.DISAPPEARS -> stringResource(
            R.string.presence_setup_disappears,
            target,
            duration,
        )
    }
}

@Composable
internal fun targetPresenceRuleTitle(
    rule: MonitorRule.TargetPresence,
    targetLabel: String? = null,
): String {
    val target = targetLabel?.takeIf(String::isNotBlank) ?: stringResource(R.string.generic_target)
    return when (rule.kind) {
        PresenceRuleKind.APPEARS -> stringResource(R.string.presence_title_appears, target)
        PresenceRuleKind.REMAINS -> stringResource(R.string.presence_title_remains, target)
        PresenceRuleKind.DISAPPEARS -> stringResource(R.string.presence_title_disappears, target)
    }
}

internal fun targetPresenceSummaryResource(kind: PresenceRuleKind): Int = when (kind) {
    PresenceRuleKind.APPEARS -> R.string.presence_summary_appears
    PresenceRuleKind.REMAINS -> R.string.presence_summary_remains
    PresenceRuleKind.DISAPPEARS -> R.string.presence_summary_disappears
}

internal data class TargetPresenceCopySemantics(
    val opensAndClosesEpisode: Boolean,
    val savesTriggerPhoto: Boolean,
)

internal fun targetPresenceCopySemantics(kind: PresenceRuleKind): TargetPresenceCopySemantics =
    TargetPresenceCopySemantics(
        opensAndClosesEpisode = kind == PresenceRuleKind.APPEARS,
        savesTriggerPhoto = kind == PresenceRuleKind.APPEARS,
    )
