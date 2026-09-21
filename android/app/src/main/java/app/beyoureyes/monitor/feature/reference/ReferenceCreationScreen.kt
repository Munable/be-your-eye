package app.beyoureyes.monitor.feature.reference

import androidx.core.net.toUri
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.beyoureyes.core.domain.MaterialSufficiencyStage
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.ProductTone
import app.beyoureyes.monitor.design.ProductTopBar
import app.beyoureyes.monitor.design.SetupInstructions
import app.beyoureyes.monitor.feature.monitoring.TargetPresenceRuleControls
import app.beyoureyes.monitor.feature.monitoring.LocalNotificationSetupPanel
import app.beyoureyes.monitor.feature.monitoring.targetPresenceRuleSummary
import kotlinx.coroutines.launch

internal object ReferenceTags {
    const val SCREEN = "reference_create"
    const val NAME = "reference_name"
    const val PICK = "reference_pick"
    const val START = "reference_start"
    const val TEST_RECOGNITION = "reference_test_recognition"
    const val SCENE_ARTWORK = "reference_scene_artwork"
}

@Composable
internal fun ReferenceCreationScreen(
    viewModel: ReferenceCreationViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val guardedOnBack = referenceCreationBackNavigation(state.saving, onBack)
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20),
        viewModel::addImages,
    )
    Surface(
        modifier = Modifier.fillMaxSize().testTag(ReferenceTags.SCREEN),
        color = ProductColors.Background,
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ProductTopBar(
                title = stringResource(R.string.reference_title),
                onBack = guardedOnBack,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column {
                        Text(
                            stringResource(R.string.reference_heading),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.reference_intro),
                            modifier = Modifier.padding(top = 5.dp),
                            color = ProductColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (state.materials.isNotEmpty()) {
                    item {
                        ReferencePhotoGrid(
                            materials = state.materials,
                            onRemove = viewModel::remove,
                            importing = state.importing,
                            canAdd = state.materials.size < 20,
                            onAdd = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                    }
                } else {
                    item {
                        EmptyPhotoAction(
                            importing = state.importing,
                            onClick = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                    }
                }
                item {
                    SetupInstructions(reference = true, testTag = ReferenceTags.SCENE_ARTWORK)
                }
                item {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = viewModel::setName,
                        label = { Text(stringResource(R.string.monitor_name_optional)) },
                        placeholder = { Text(stringResource(R.string.monitor_name_auto_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(ReferenceTags.NAME),
                    )
                }
                item {
                    MaterialSufficiencyCard(
                        count = state.materials.size,
                        stage = state.sufficiency.stage,
                    )
                }
                state.message?.let { message ->
                    item {
                        ProductPanel(tone = ProductTone.WAITING) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Lightbulb,
                                    contentDescription = null,
                                    tint = ProductColors.Amber,
                                )
                                Text(
                                    referenceMessageText(message),
                                    modifier = Modifier.padding(start = 10.dp),
                                    color = ProductColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
                item {
                    ProductPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProductIconBadge(Icons.Outlined.Tune, null)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    stringResource(R.string.reference_match_method),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.reference_model_auto),
                                    modifier = Modifier.padding(top = 2.dp),
                                    color = ProductColors.TextMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                stringResource(
                                    if (state.materials.size >= 3) R.string.status_ready_to_continue
                                    else R.string.reference_waiting_for_photos,
                                ),
                                color = if (state.materials.size >= 3) ProductColors.Green else ProductColors.TextMuted,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                if (state.sufficiency.canContinue) {
                    item {
                        ProductPanel {
                            TargetPresenceRuleControls(
                                rule = state.rule,
                                onRuleChange = viewModel::setPresenceRule,
                            )
                        }
                    }
                    item {
                        LocalNotificationSetupPanel(
                            checked = state.notificationsEnabled,
                            onCheckedChange = viewModel::setNotificationsEnabled,
                        )
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = ProductColors.TextMuted,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            stringResource(R.string.reference_local_only),
                            modifier = Modifier.padding(start = 7.dp),
                            color = ProductColors.TextMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
            Surface(color = ProductColors.Background) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    if (state.sufficiency.canContinue) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(
                                    R.string.record_condition_value,
                                    targetPresenceRuleSummary(state.rule),
                                ),
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                color = ProductColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = {
                                val ruleIndex = 5 + if (state.message == null) 0 else 1
                                scope.launch { listState.animateScrollToItem(ruleIndex) }
                            }) {
                                Text(stringResource(R.string.action_edit))
                            }
                        }
                    }
                    ProductPrimaryButton(
                        text = stringResource(
                            if (state.saving) R.string.status_opening_camera
                            else R.string.action_save_start_monitoring,
                        ),
                        onClick = viewModel::createAndStart,
                        enabled = state.sufficiency.canContinue && !state.saving,
                        leadingIcon = if (state.saving) null else Icons.Outlined.Visibility,
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag(ReferenceTags.START),
                    )
                    OutlinedButton(
                        onClick = viewModel::createAndOpen,
                        enabled = state.sufficiency.canContinue && !state.saving,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                            .testTag(ReferenceTags.TEST_RECOGNITION),
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
internal fun referenceCreationBackNavigation(
    saving: Boolean,
    onBack: () -> Unit,
): (() -> Unit)? {
    val allowed = if (saving) null else onBack
    BackHandler { allowed?.invoke() }
    return allowed
}

@Composable
internal fun MaterialSufficiencyCard(
    count: Int,
    stage: MaterialSufficiencyStage,
) {
    val label = when (stage) {
        MaterialSufficiencyStage.NEED_MORE -> if (count == 0) {
            stringResource(R.string.reference_no_photos)
        } else {
            pluralStringResource(R.plurals.reference_more_photos_needed, 3 - count, 3 - count)
        }
        MaterialSufficiencyStage.READY -> stringResource(R.string.reference_material_ready)
        MaterialSufficiencyStage.SUFFICIENT -> stringResource(R.string.reference_material_sufficient)
        MaterialSufficiencyStage.MORE_STABLE -> stringResource(R.string.reference_material_very_sufficient)
    }
    ProductPanel(tone = if (count >= 3) ProductTone.ACTIVE else ProductTone.NEUTRAL) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(R.string.reference_material_sufficiency), style = MaterialTheme.typography.titleMedium)
                    Text(
                        label,
                        modifier = Modifier.padding(top = 2.dp),
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    pluralStringResource(R.plurals.photo_count, count, count),
                    color = if (count >= 3) ProductColors.Green else ProductColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { (count / 3f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (count >= 3) ProductColors.Green else ProductColors.Cyan,
                trackColor = ProductColors.SurfaceHighlight,
                drawStopIndicator = {},
            )
            Text(
                if (count >= 3) {
                    stringResource(R.string.reference_sufficiency_ready_body)
                } else {
                    stringResource(R.string.reference_sufficiency_need_body)
                },
                modifier = Modifier.padding(top = 10.dp),
                color = ProductColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun ReferencePhotoGrid(
    materials: List<ReferenceMaterial>,
    onRemove: (ReferenceMaterial) -> Unit,
    importing: Boolean = false,
    canAdd: Boolean = false,
    onAdd: (() -> Unit)? = null,
) {
    val cells: List<ReferenceMaterial?> = buildList {
        addAll(materials)
        if (canAdd && onAdd != null) add(null)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(3).forEachIndexed { rowIndex, rowMaterials ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowMaterials.forEachIndexed { columnIndex, material ->
                    if (material != null) {
                        ReferencePhoto(
                            material = material,
                            position = rowIndex * 3 + columnIndex + 1,
                            onRemove = onRemove,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        AddPhotoTile(
                            importing = importing,
                            onClick = requireNotNull(onAdd),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(3 - rowMaterials.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun EmptyPhotoAction(importing: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(116.dp).testTag(ReferenceTags.PICK)
            .semantics { role = Role.Button }
            .then(if (!importing) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = ProductColors.Surface,
        border = BorderStroke(1.dp, ProductColors.Border),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (importing) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.reference_importing), Modifier.padding(top = 8.dp))
            } else {
                Icon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = ProductColors.Cyan,
                    modifier = Modifier.size(27.dp),
                )
                Text(
                    stringResource(R.string.reference_pick_photos),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.reference_pick_multiple),
                    modifier = Modifier.padding(top = 2.dp),
                    color = ProductColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AddPhotoTile(
    importing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.aspectRatio(1f).testTag(ReferenceTags.PICK)
            .semantics { role = Role.Button }
            .then(if (!importing) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = ProductColors.SurfaceRaised,
        border = BorderStroke(1.dp, ProductColors.Border),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (importing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = ProductColors.Cyan,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    stringResource(R.string.reference_add_more),
                    modifier = Modifier.padding(top = 6.dp),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ReferencePhoto(
    material: ReferenceMaterial,
    position: Int,
    onRemove: (ReferenceMaterial) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photoDescription = stringResource(R.string.reference_photo_description, position)
    val removeDescription = stringResource(R.string.reference_remove_photo, position)
    Box(modifier.aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(ProductColors.Surface)) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = photoDescription
                }
            },
            update = { image -> image.setImageURI((material.thumbnailUri ?: material.sourceUri).toUri()) },
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
            color = Color(0xCC080C0F),
            shape = CircleShape,
        ) {
            IconButton(onClick = { onRemove(material) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = removeDescription,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun referenceMessageText(message: ReferenceCreationMessage): String = when (message) {
    ReferenceCreationMessage.DraftSaveFailed -> stringResource(R.string.reference_draft_save_failed)
    ReferenceCreationMessage.ImageReadFailed -> stringResource(R.string.reference_image_read_failed)
    ReferenceCreationMessage.PhotoRemoveFailed -> stringResource(R.string.reference_photo_remove_failed)
    ReferenceCreationMessage.PhotoDraftSaveFailed -> stringResource(R.string.reference_photo_draft_save_failed)
    is ReferenceCreationMessage.ImportSummary -> buildList {
        if (message.duplicateCount > 0) {
            add(
                pluralStringResource(
                    R.plurals.reference_duplicates_skipped,
                    message.duplicateCount,
                    message.duplicateCount,
                ),
            )
        }
        if (message.unreadableCount > 0) {
            add(
                pluralStringResource(
                    R.plurals.reference_unreadable_photos,
                    message.unreadableCount,
                    message.unreadableCount,
                ),
            )
        }
    }.joinToString(stringResource(R.string.list_separator))
}
