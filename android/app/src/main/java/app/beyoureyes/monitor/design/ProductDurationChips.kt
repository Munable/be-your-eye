package app.beyoureyes.monitor.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import app.beyoureyes.core.domain.ALLOWED_TRIGGER_DURATIONS_SECONDS
import app.beyoureyes.monitor.R

internal val TRIGGER_DURATION_PRESETS_SECONDS = listOf(1, 3, 5, 10, 30, 60)

@Composable
internal fun durationSecondsLabel(seconds: Int): String =
    pluralStringResource(R.plurals.duration_seconds, seconds, seconds)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProductDurationChips(
    selectedSeconds: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTagPrefix: String? = null,
) {
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember(selectedSeconds) { mutableStateOf(selectedSeconds.toString()) }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TRIGGER_DURATION_PRESETS_SECONDS.forEach { seconds ->
            FilterChip(
                selected = selectedSeconds == seconds,
                onClick = { onSelected(seconds) },
                enabled = enabled,
                label = { Text(durationSecondsLabel(seconds)) },
                leadingIcon = if (selectedSeconds == seconds) {
                    {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else null,
                modifier = testTagPrefix?.let { Modifier.testTag("${it}_$seconds") } ?: Modifier,
            )
        }
        FilterChip(
            selected = selectedSeconds !in TRIGGER_DURATION_PRESETS_SECONDS,
            onClick = {
                customText = selectedSeconds.toString()
                customOpen = true
            },
            enabled = enabled,
            label = {
                Text(
                    if (selectedSeconds !in TRIGGER_DURATION_PRESETS_SECONDS) durationSecondsLabel(selectedSeconds)
                    else stringResource(R.string.duration_custom),
                )
            },
            modifier = Modifier.testTag("${testTagPrefix ?: "duration"}_custom"),
        )
    }
    if (customOpen) {
        val value = customText.toIntOrNull()
        val valid = value != null && value in ALLOWED_TRIGGER_DURATIONS_SECONDS
        AlertDialog(
            onDismissRequest = { customOpen = false },
            title = { Text(stringResource(R.string.duration_custom_title)) },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.duration_custom_range)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = customText.isNotBlank() && !valid,
                    modifier = Modifier.testTag("duration_custom_input"),
                )
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    onSelected(checkNotNull(value))
                    customOpen = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { customOpen = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
