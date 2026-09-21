package app.beyoureyes.monitor.feature.objectdetection

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.beyoureyes.core.domain.ObjectClassDefinition
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.ProductTopBar
import app.beyoureyes.monitor.design.SetupInstructions
import app.beyoureyes.monitor.feature.monitoring.TargetPresenceRuleControls
import app.beyoureyes.monitor.feature.monitoring.LocalNotificationSetupPanel

internal object ObjectCreationTags {
    const val SCREEN = "object_detection_creation"
    const val LIST = "object_detection_list"
    const val INPUT = "object_detection_input"
    const val SUGGESTION = "object_detection_suggestion"
    const val CONTINUE = "object_detection_continue"
    const val RETRY = "object_detection_retry"
    const val TEST_RECOGNITION = "object_detection_test_recognition"
    const val SCENE_ARTWORK = "object_detection_scene_artwork"
    const val EXAMPLE = "object_detection_example"
}

@Composable
internal fun ObjectDetectionCreationScreen(
    viewModel: ObjectDetectionCreationViewModel,
    onBack: () -> Unit,
    onOpenCamera: () -> Unit,
    onStartDirectly: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val rule by viewModel.rule.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val focusManager = LocalFocusManager.current
    Surface(
        modifier = Modifier.fillMaxSize().testTag(ObjectCreationTags.SCREEN),
        color = ProductColors.Background,
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ProductTopBar(
                title = stringResource(R.string.object_creation_title),
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
                    .testTag(ObjectCreationTags.LIST),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "query") {
                    ProductPanel(tone = app.beyoureyes.monitor.design.ProductTone.NEUTRAL) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProductIconBadge(
                                    Icons.Outlined.Visibility,
                                    null,
                                    tint = ProductColors.Green,
                                    background = ProductColors.GreenSoft,
                                )
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(
                                        stringResource(
                                            if (state.loading) R.string.object_catalog_checking
                                            else R.string.object_choose_target,
                                        ),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        stringResource(R.string.object_creation_intro),
                                        color = ProductColors.TextMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = viewModel::onQueryChanged,
                                modifier = Modifier.fillMaxWidth().testTag(ObjectCreationTags.INPUT),
                                label = { Text(stringResource(R.string.object_query_label)) },
                                placeholder = { Text(stringResource(R.string.object_query_example)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            )
                            if (state.query.isBlank() && state.suggestions.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.object_search_hint),
                                    color = ProductColors.TextMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.suggestions.forEach { definition ->
                                        AssistChip(
                                            onClick = {
                                                focusManager.clearFocus()
                                                viewModel.select(definition)
                                            },
                                            label = { Text(definition.localizedLabel()) },
                                            modifier = Modifier.testTag(
                                                "${ObjectCreationTags.SUGGESTION}_${definition.targetId}",
                                            ),
                                        )
                                    }
                                }
                            }
                            state.error?.let { message ->
                                Text(
                                    stringResource(
                                        when (message) {
                                            ObjectCreationError.CATALOG_UNAVAILABLE ->
                                                R.string.object_catalog_unavailable
                                            ObjectCreationError.NO_MATCH -> R.string.object_no_match
                                        },
                                    ),
                                    color = ProductColors.Error,
                                )
                                if (state.catalogUnavailable) {
                                    TextButton(
                                        onClick = viewModel::retryCatalogLoad,
                                        modifier = Modifier.testTag(ObjectCreationTags.RETRY),
                                    ) { Text(stringResource(R.string.action_reload)) }
                                }
                            }
                        }
                    }
                }
                if (state.query.isBlank()) item(key = "scene") {
                    SetupInstructions(reference = false, testTag = ObjectCreationTags.SCENE_ARTWORK)
                }
                if (state.query.isNotBlank()) item(key = "results_heading") {
                    Text(
                        when {
                            state.loading -> stringResource(R.string.status_loading)
                            else -> stringResource(R.string.object_available_targets)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(
                    if (state.query.isBlank()) emptyList() else state.suggestions,
                    key = ObjectClassDefinition::targetId,
                ) { definition ->
                    ObjectSuggestionRow(
                        definition = definition,
                        selected = state.selected?.targetId == definition.targetId,
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.select(definition)
                        },
                    )
                }
                if (state.selected != null) {
                    item(key = "example") {
                        ProductPanel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProductIconBadge(
                                    Icons.Outlined.Visibility,
                                    null,
                                    modifier = Modifier.testTag(ObjectCreationTags.EXAMPLE),
                                )
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text(
                                        stringResource(R.string.object_example_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.object_example_body,
                                            state.selected?.localizedLabel().orEmpty(),
                                        ),
                                        modifier = Modifier.padding(top = 3.dp),
                                        color = ProductColors.TextMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "rule") {
                        ProductPanel {
                            TargetPresenceRuleControls(
                                rule = rule,
                                onRuleChange = viewModel::setPresenceRule,
                                targetLabel = state.selected?.localizedLabel(),
                            )
                        }
                    }
                    item(key = "notifications") {
                        LocalNotificationSetupPanel(
                            checked = notificationsEnabled,
                            onCheckedChange = viewModel::setNotificationsEnabled,
                        )
                    }
                }
                item(key = "actions") {
                    ProductPrimaryButton(
                        text = stringResource(R.string.action_save_start_monitoring),
                        enabled = !state.loading && state.selected != null,
                        onClick = { if (viewModel.continueToStart()) onStartDirectly() },
                        modifier = Modifier.fillMaxWidth().testTag(ObjectCreationTags.CONTINUE),
                    )
                    OutlinedButton(
                        onClick = { if (viewModel.continueToCamera()) onOpenCamera() },
                        enabled = !state.loading && state.selected != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                            .testTag(ObjectCreationTags.TEST_RECOGNITION),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        Text(
                            stringResource(R.string.action_test_recognition_optional),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObjectSuggestionRow(
    definition: ObjectClassDefinition,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("${ObjectCreationTags.SUGGESTION}_${definition.targetId}")
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(definition.localizedLabel(), style = MaterialTheme.typography.titleMedium)
            Text(definition.secondaryLabel(), color = ProductColors.TextMuted)
        }
        if (selected) Text(stringResource(R.string.status_selected), color = ProductColors.Green)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = ProductColors.Cyan)
    }
}

@Composable
private fun ObjectClassDefinition.localizedLabel(): String =
    if (LocalConfiguration.current.locales[0].language == "zh") labelZhCn else labelEn

@Composable
private fun ObjectClassDefinition.secondaryLabel(): String =
    if (LocalConfiguration.current.locales[0].language == "zh") labelEn else labelZhCn
