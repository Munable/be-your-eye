package app.beyoureyes.monitor.feature.reading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.beyoureyes.core.domain.parseStructuredReading
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.ReadingPreviewLiveState
import app.beyoureyes.monitor.ReadingPreviewStatus
import app.beyoureyes.monitor.readingPreviewSuggestsManualRegion
import app.beyoureyes.monitor.readingLiveIsStable
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductDurationChips
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.ProductTone
import app.beyoureyes.monitor.design.durationSecondsLabel
import app.beyoureyes.monitor.feature.monitoring.CameraTags
import app.beyoureyes.monitor.feature.monitoring.LocalNotificationSetupPanel

/** Reading-only confirmation and threshold controls; camera ownership stays in monitoring. */
@Composable
internal fun ReadingConditionControls(
    status: ReadingPreviewStatus,
    mode: ReadingConditionMode,
    threshold: String,
    lowerThreshold: String,
    upperThreshold: String,
    durationSeconds: Int,
    saving: Boolean,
    error: String?,
    notificationsEnabled: Boolean,
    onConfirm: (ReadingPreviewStatus.AwaitingConfirmation) -> Unit,
    onBeginFormatCorrection: (ReadingPreviewStatus.AwaitingConfirmation) -> Unit,
    onCancelFormatCorrection: () -> Unit,
    onModeChange: (ReadingConditionMode) -> Unit,
    onThresholdChange: (String) -> Unit,
    onLowerThresholdChange: (String) -> Unit,
    onUpperThresholdChange: (String) -> Unit,
    onDurationSecondsChange: (Int) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onCreatePending: (() -> Unit)? = null,
    pendingActionLabelRes: Int = R.string.reading_create_pending_action,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (status !is ReadingPreviewStatus.Confirmed &&
            (status as? ReadingPreviewStatus.AwaitingConfirmation)?.editingFormat != true
        ) ReadingLiveInstrument(status)

        when (status) {
            ReadingPreviewStatus.Idle,
            is ReadingPreviewStatus.Opening,
            -> ReadingScanningHelp(status)

            is ReadingPreviewStatus.Scanning -> ReadingScanningHelp(status)

            is ReadingPreviewStatus.AwaitingConfirmation -> ReadingConfirmationControls(
                status = status,
                onConfirm = onConfirm,
                onBeginFormatCorrection = onBeginFormatCorrection,
                onCancelFormatCorrection = onCancelFormatCorrection,
            )

            is ReadingPreviewStatus.Confirmed -> ReadingThresholdControls(
                status = status,
                mode = mode,
                threshold = threshold,
                lowerThreshold = lowerThreshold,
                upperThreshold = upperThreshold,
                durationSeconds = durationSeconds,
                saving = saving,
                error = error,
                notificationsEnabled = notificationsEnabled,
                onModeChange = onModeChange,
                onThresholdChange = onThresholdChange,
                onLowerThresholdChange = onLowerThresholdChange,
                onUpperThresholdChange = onUpperThresholdChange,
                onDurationSecondsChange = onDurationSecondsChange,
                onStart = onStart,
                onNotificationsChange = onNotificationsChange,
            )

            is ReadingPreviewStatus.Failed,
            is ReadingPreviewStatus.Invalidated,
            -> ReadingRetryControls(onRetry)
        }

        if (onCreatePending != null) {
            OutlinedButton(
                onClick = onCreatePending,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(50.dp)
                    .testTag(CameraTags.READING_CREATE_PENDING),
            ) {
                Text(stringResource(pendingActionLabelRes))
            }
            Text(
                stringResource(R.string.reading_create_pending_body),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReadingLiveInstrument(status: ReadingPreviewStatus) {
    val live = (status as? ReadingPreviewStatus.Checking)?.live
        ?: ReadingPreviewLiveState.Waiting
    val value = when (live) {
        ReadingPreviewLiveState.Waiting,
        is ReadingPreviewLiveState.Unavailable,
        -> "—"
        is ReadingPreviewLiveState.Candidate -> live.value.text
        is ReadingPreviewLiveState.Stable -> live.value.text
    }
    val description = when (live) {
        ReadingPreviewLiveState.Waiting -> stringResource(R.string.reading_waiting_for_number)
        is ReadingPreviewLiveState.Candidate ->
            stringResource(R.string.reading_confirming_value, live.value.text)
        is ReadingPreviewLiveState.Stable ->
            stringResource(R.string.reading_current_value, live.value.text)
        is ReadingPreviewLiveState.Unavailable -> stringResource(R.string.reading_unavailable)
    }
    val tone = when (live) {
        is ReadingPreviewLiveState.Stable -> ProductTone.ACTIVE
        is ReadingPreviewLiveState.Unavailable -> ProductTone.WAITING
        else -> ProductTone.NEUTRAL
    }

    ProductPanel(tone = tone) {
        Column(
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.reading_live_label),
                color = ProductColors.TextMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 36.sp),
                color = ProductColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            if (live !is ReadingPreviewLiveState.Stable) {
                Text(
                    description,
                    color = if (live is ReadingPreviewLiveState.Unavailable) {
                        ProductColors.Amber
                    } else ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ReadingScanningHelp(status: ReadingPreviewStatus) {
    val live = (status as? ReadingPreviewStatus.Scanning)?.live
    val suggestsManualRegion = readingPreviewSuggestsManualRegion(status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductIconBadge(
            Icons.Outlined.Numbers,
            null,
            tint = if (live is ReadingPreviewLiveState.Unavailable) {
                ProductColors.Amber
            } else {
                ProductColors.Cyan
            },
            background = if (live is ReadingPreviewLiveState.Unavailable) {
                ProductColors.AmberSoft
            } else {
                ProductColors.CyanSoft
            },
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                when (live) {
                    is ReadingPreviewLiveState.Candidate -> stringResource(R.string.reading_confirming_stable)
                    is ReadingPreviewLiveState.Unavailable -> if (suggestsManualRegion) {
                        stringResource(R.string.reading_incomplete_value)
                    } else {
                        stringResource(R.string.reading_not_clear)
                    }
                    else -> stringResource(R.string.reading_scanning)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when (status) {
                    is ReadingPreviewStatus.Scanning -> when (live) {
                        is ReadingPreviewLiveState.Candidate -> stringResource(R.string.reading_auto_confirming)
                        is ReadingPreviewLiveState.Unavailable -> if (suggestsManualRegion) {
                            stringResource(R.string.reading_draw_box_for_number)
                        } else {
                            stringResource(R.string.reading_keep_clear)
                        }
                        else -> stringResource(R.string.reading_keep_clear)
                    }
                    else -> stringResource(R.string.reading_keep_clear)
                },
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ReadingConfirmationControls(
    status: ReadingPreviewStatus.AwaitingConfirmation,
    onConfirm: (ReadingPreviewStatus.AwaitingConfirmation) -> Unit,
    onBeginFormatCorrection: (ReadingPreviewStatus.AwaitingConfirmation) -> Unit,
    onCancelFormatCorrection: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var correctedValue by rememberSaveable(
        status.expectedIdentity.taskId,
        status.expectedIdentity.taskRevision,
        status.editingFormat,
    ) {
        mutableStateOf(status.value.text)
    }
    var correctionErrorRes by rememberSaveable(
        status.expectedIdentity.taskId,
        status.expectedIdentity.taskRevision,
        status.editingFormat,
    ) {
        mutableStateOf<Int?>(null)
    }

    if (!status.editingFormat) {
        ProductPrimaryButton(
            text = stringResource(R.string.reading_confirm_value),
            onClick = { onConfirm(status) },
            enabled = readingConfirmationEnabled(status),
            leadingIcon = Icons.Outlined.CheckCircle,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag(CameraTags.READING_CONFIRM),
        )
        OutlinedButton(
            onClick = {
                onBeginFormatCorrection(status)
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
                .testTag(CameraTags.READING_CORRECTION_TOGGLE),
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = null)
            Text(stringResource(R.string.reading_correct_decimal), Modifier.padding(start = 8.dp))
        }
    } else {
        Text(stringResource(R.string.reading_correct_format_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.reading_correct_format_sample, status.live.value.text),
            color = ProductColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = correctedValue,
            onValueChange = {
                correctedValue = it.take(64)
                correctionErrorRes = null
            },
            singleLine = true,
            label = { Text(stringResource(R.string.reading_correct_format_label)) },
            supportingText = {
                Text(
                    correctionErrorRes?.let { stringResource(it) }
                        ?: stringResource(R.string.reading_correct_format_help),
                )
            },
            isError = correctionErrorRes != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().testTag(CameraTags.READING_CORRECTION),
        )
        ProductPrimaryButton(
            text = stringResource(R.string.reading_confirm_format),
            onClick = {
                if (parseStructuredReading(correctedValue) == null) {
                    correctionErrorRes = R.string.reading_error_invalid_structured
                } else if (!status.correctBaseline(correctedValue)) {
                    correctionErrorRes = R.string.reading_error_mismatch
                } else {
                    correctedValue = status.value.text
                    onConfirm(status)
                }
            },
            enabled = correctedValue.isNotBlank(),
            leadingIcon = Icons.Outlined.CheckCircle,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag(CameraTags.READING_CONFIRM),
        )
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                onCancelFormatCorrection()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(stringResource(R.string.reading_back_to_live))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingThresholdControls(
    status: ReadingPreviewStatus.Confirmed,
    mode: ReadingConditionMode,
    threshold: String,
    lowerThreshold: String,
    upperThreshold: String,
    durationSeconds: Int,
    saving: Boolean,
    error: String?,
    notificationsEnabled: Boolean,
    onModeChange: (ReadingConditionMode) -> Unit,
    onThresholdChange: (String) -> Unit,
    onLowerThresholdChange: (String) -> Unit,
    onUpperThresholdChange: (String) -> Unit,
    onDurationSecondsChange: (Int) -> Unit,
    onStart: () -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductIconBadge(
            Icons.Outlined.CheckCircle,
            null,
            tint = ProductColors.Green,
            background = ProductColors.GreenSoft,
        )
        Column(Modifier.padding(start = 14.dp)) {
            Text(stringResource(R.string.reading_baseline_confirmed), color = ProductColors.Green, style = MaterialTheme.typography.labelLarge)
            Text(status.value.text, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.reading_paused_during_setup),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Text(stringResource(R.string.reading_when_record), style = MaterialTheme.typography.titleMedium)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReadingConditionMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = { Text(readingModeLabel(option)) },
                leadingIcon = if (mode == option) {
                    {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else null,
            )
        }
    }
    if (mode == ReadingConditionMode.OUTSIDE) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = lowerThreshold,
                onValueChange = { onLowerThresholdChange(it.take(64)) },
                singleLine = true,
                label = { Text(stringResource(R.string.reading_lower_limit)) },
                supportingText = { Text(stringResource(R.string.reading_below_value)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f).testTag(CameraTags.LOWER_THRESHOLD),
            )
            OutlinedTextField(
                value = upperThreshold,
                onValueChange = { onUpperThresholdChange(it.take(64)) },
                singleLine = true,
                label = { Text(stringResource(R.string.reading_upper_limit)) },
                supportingText = { Text(stringResource(R.string.reading_above_value)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f).testTag(CameraTags.UPPER_THRESHOLD),
            )
        }
        if (readingRuleFromInputs(
                mode,
                threshold,
                lowerThreshold,
                upperThreshold,
                status.confirmedFormat,
                durationSeconds,
            ) == null
        ) {
            Text(stringResource(R.string.reading_error_invalid_range), color = ProductColors.Error)
        }
    } else {
        OutlinedTextField(
            value = threshold,
            onValueChange = { onThresholdChange(it.take(64)) },
            singleLine = true,
            label = {
                Text(
                    stringResource(
                        if (mode == ReadingConditionMode.ABOVE) R.string.reading_upper_limit
                        else R.string.reading_lower_limit,
                    ),
                )
            },
            supportingText = {
                Text(
                    stringResource(
                        if (mode == ReadingConditionMode.ABOVE) R.string.reading_record_above
                        else R.string.reading_record_below,
                    ),
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().testTag(CameraTags.THRESHOLD),
        )
    }
    Text(stringResource(R.string.trigger_duration_question), style = MaterialTheme.typography.titleMedium)
    ProductDurationChips(
        selectedSeconds = durationSeconds,
        onSelected = onDurationSecondsChange,
    )
    Text(
        stringResource(R.string.reading_duration_explanation, durationSecondsLabel(durationSeconds)),
        color = ProductColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (status.live is ReadingPreviewLiveState.Unavailable) {
        Text(
            stringResource(R.string.reading_current_frame_unavailable),
            color = ProductColors.Amber,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    LocalNotificationSetupPanel(
        checked = notificationsEnabled,
        onCheckedChange = onNotificationsChange,
    )
    ProductPrimaryButton(
        text = stringResource(
            if (saving) R.string.status_starting else R.string.action_start_monitoring,
        ),
        onClick = onStart,
        enabled = readingConditionStartEnabled(
            status,
            saving,
            readingRuleFromInputs(
                mode,
                threshold,
                lowerThreshold,
                upperThreshold,
                status.confirmedFormat,
                durationSeconds,
            ) != null,
        ),
        leadingIcon = if (saving) null else Icons.Outlined.Visibility,
        modifier = Modifier.fillMaxWidth().height(54.dp).testTag(CameraTags.START),
    )
    error?.let { Text(it, color = ProductColors.Error) }
}

@Composable
private fun ReadingRetryControls(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductIconBadge(
            Icons.Outlined.Refresh,
            null,
            tint = ProductColors.Amber,
            background = ProductColors.AmberSoft,
        )
        Column(Modifier.padding(start = 14.dp)) {
            Text(stringResource(R.string.reading_connection_interrupted), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.reading_reconnect_body),
                color = ProductColors.TextSecondary,
            )
        }
    }
    ProductPrimaryButton(
        text = stringResource(R.string.action_reconnect),
        onClick = onRetry,
        leadingIcon = Icons.Outlined.Refresh,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag(CameraTags.RETRY),
    )
}

@Composable
private fun readingModeLabel(mode: ReadingConditionMode): String = stringResource(
    when (mode) {
        ReadingConditionMode.ABOVE -> R.string.condition_above
        ReadingConditionMode.BELOW -> R.string.condition_below
        ReadingConditionMode.OUTSIDE -> R.string.condition_outside
    },
)

internal fun readingConfirmationEnabled(status: ReadingPreviewStatus): Boolean =
    status is ReadingPreviewStatus.AwaitingConfirmation

internal fun readingConditionStartEnabled(
    status: ReadingPreviewStatus,
    saving: Boolean,
    conditionValid: Boolean = true,
): Boolean = status is ReadingPreviewStatus.Confirmed &&
    readingLiveIsStable(status) &&
    conditionValid &&
    !saving
