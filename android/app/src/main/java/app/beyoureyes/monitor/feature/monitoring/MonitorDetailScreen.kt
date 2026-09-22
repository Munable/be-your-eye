package app.beyoureyes.monitor.feature.monitoring

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.beyoureyes.core.domain.LatestReading
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.ReadingStatus
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.core.domain.materialSufficiency
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductDurationChips
import app.beyoureyes.monitor.design.ProductMetric
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.ProductSectionHeader
import app.beyoureyes.monitor.design.ProductSwitch
import app.beyoureyes.monitor.design.ProductTone
import app.beyoureyes.monitor.design.ProductTopBar
import app.beyoureyes.monitor.design.durationSecondsLabel
import app.beyoureyes.monitor.design.StatusPill
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.localizedLabel
import app.beyoureyes.monitor.design.pluralText
import app.beyoureyes.monitor.design.resolve
import app.beyoureyes.monitor.design.uiText
import app.beyoureyes.monitor.feature.reading.ReadingConditionMode
import app.beyoureyes.monitor.feature.reading.ReadingConditionInvalidReason
import app.beyoureyes.monitor.feature.reading.ReadingConditionValidation
import app.beyoureyes.monitor.feature.reading.validateReadingCondition
import app.beyoureyes.monitor.feature.reference.MaterialSufficiencyCard
import app.beyoureyes.monitor.feature.reference.ReferencePhotoGrid
import app.beyoureyes.monitor.feature.history.productPresentation
import app.beyoureyes.monitor.feature.history.summary
import app.beyoureyes.monitor.feature.history.userVisibleRecordCount
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale

internal object MonitorDetailTags {
    const val DELETE = "monitor_delete"
    const val DELETE_CONFIRM = "monitor_delete_confirm"
    const val PRESENCE_SAVE = "monitor_presence_rule_save"
    const val READING_SAVE = "monitor_reading_rule_save"
    const val REFERENCE_LIST = "monitor_reference_list"
    const val REFERENCE_EDIT = "monitor_reference_edit"
    const val REFERENCE_PICK = "monitor_reference_pick"
    const val REFERENCE_SAVE = "monitor_reference_save"
    const val REFERENCE_CANCEL = "monitor_reference_cancel"
    const val MODEL_RETRY = "monitor_model_retry"
    const val READING_BASELINE_CONFIRM = "monitor_reading_baseline_confirm"
    const val TEST_RECOGNITION = "monitor_test_recognition"
    const val OPEN_HISTORY = "monitor_open_history"
    const val READING_BASELINE_PENDING = "monitor_reading_baseline_pending"
}

@Composable
internal fun MonitorDetailScreen(
    viewModel: MonitorDetailViewModel,
    activeMonitorId: String?,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onStartMonitoring: (app.beyoureyes.monitor.RuntimeCameraConfig) -> app.beyoureyes.monitor.MonitoringStartResult,
    onCheckProductAccess: () -> app.beyoureyes.monitor.MonitoringStartResult.Rejected?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onOpenCamera: (String) -> Unit,
    onConfirmReadingBaseline: (String) -> Unit,
    onOpenHistory: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val referencePreview by viewModel.referencePreview.collectAsState()
    val referenceEditor by viewModel.referenceEditor.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val modelDisclosure by viewModel.modelDisclosure.collectAsState()
    val row = state.local.singleOrNull { it.monitor.id == viewModel.monitorId }
    if (row == null) {
        Surface(Modifier.fillMaxSize(), color = ProductColors.Background) {
            Text(stringResource(R.string.monitor_missing_body), Modifier.safeDrawingPadding().padding(24.dp))
        }
        return
    }
    val monitor = row.monitor
    val thisMonitorActive = activeMonitorId == monitor.id
    val hasStarted = state.hasStarted(monitor.id)
    val anotherMonitorActive = activeMonitorId != null && !thisMonitorActive
    val deleteAllowed = monitorDetailDeleteAllowed(
        thisMonitorActive = thisMonitorActive,
        referenceEditorOpen = referenceEditor != null,
    )
    val cameraAllowed = monitorDetailCameraAllowed(
        anotherMonitorActive = anotherMonitorActive,
        referenceEditorOpen = referenceEditor != null,
        modelDisclosure = modelDisclosure,
    )
    val events = state.events.filter { !it.isRemote && it.monitorId == monitor.id }
    var name by remember(monitor.name) { mutableStateOf(monitor.name) }
    var deleteConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(referenceEditor) {
        if (referenceEditor != null) deleteConfirm = false
    }
    val context = LocalContext.current
    val windowView = androidx.compose.ui.platform.LocalView.current.rootView
    val scope = rememberCoroutineScope()
    var directStartError by remember { mutableStateOf<String?>(null) }
    var pendingDirectStart by rememberSaveable { mutableStateOf(false) }
    var directStartRunning by remember { mutableStateOf(false) }
    // 配置完整的监控直接开始，不强制经过相机；采样配置失效时回退到测试识别相机页重新准备。
    // pendingDirectStart 是 effect 的 key，只能在协程结束时复位，中途复位会取消协程自身。
    LaunchedEffect(pendingDirectStart, cameraPermissionGranted) {
        if (!pendingDirectStart || !cameraPermissionGranted) return@LaunchedEffect
        directStartRunning = true
        directStartError = null
        val rejection = onCheckProductAccess()
        if (rejection != null) {
            directStartError = rejection.message
            directStartRunning = false
            pendingDirectStart = false
            return@LaunchedEffect
        }
        val config = viewModel.buildDirectStartConfig(
            app.beyoureyes.monitor.currentCameraTargetRotation(context),
            viewPortWidth = windowView.width,
            viewPortHeight = (windowView.height * CAMERA_VIEWPORT_FRACTION).roundToInt(),
        )
        if (config == null) {
            directStartRunning = false
            pendingDirectStart = false
            onOpenCamera(monitor.id)
            return@LaunchedEffect
        }
        when (val result = onStartMonitoring(config)) {
            is app.beyoureyes.monitor.MonitoringStartResult.Accepted -> {
                pendingDirectStart = false
                onOpenCamera(monitor.id)
            }
            is app.beyoureyes.monitor.MonitoringStartResult.Rejected -> {
                directStartError = result.message
                directStartRunning = false
                pendingDirectStart = false
            }
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.setNotifications(true) }

    Surface(Modifier.fillMaxSize(), color = ProductColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ProductTopBar(
                title = stringResource(R.string.monitor_detail_title),
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                trailing = {
                    if (thisMonitorActive) StatusPill(stringResource(R.string.status_running), ProductTone.ACTIVE)
                },
            )
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                MonitorSummaryCard(
                    name = monitor.name,
                    kind = monitor.kind,
                    eventCount = userVisibleRecordCount(events),
                    lastEvent = events.firstOrNull()?.productPresentation()?.summary()?.resolve(),
                    active = thisMonitorActive,
                    hasStarted = hasStarted,
                    readingBaselinePending = (monitor.rule as? MonitorRule.ReadingThreshold)
                        ?.configured == false,
                    modelDisclosure = modelDisclosure,
                    onOpenHistory = onOpenHistory,
                )
                ProductPrimaryButton(
                    text = when {
                        directStartRunning -> stringResource(R.string.status_starting)
                        thisMonitorActive -> stringResource(R.string.action_open_monitor)
                        hasStarted -> stringResource(R.string.action_restart_monitoring)
                        else -> stringResource(R.string.action_start_monitoring)
                    },
                    onClick = {
                        if (thisMonitorActive) {
                            onOpenCamera(monitor.id)
                        } else if (!directStartRunning) {
                            pendingDirectStart = true
                            if (!cameraPermissionGranted) onRequestCameraPermission()
                        }
                    },
                    enabled = cameraAllowed && !directStartRunning,
                    leadingIcon = if (thisMonitorActive) Icons.Outlined.Visibility else Icons.Outlined.PlayArrow,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
                if (!thisMonitorActive) {
                    OutlinedButton(
                        onClick = { onOpenCamera(monitor.id) },
                        enabled = cameraAllowed && !directStartRunning,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                            .testTag(MonitorDetailTags.TEST_RECOGNITION),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        Text(
                            stringResource(R.string.action_test_recognition),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                }
                directStartError?.let {
                    Text(it, color = ProductColors.Error, style = MaterialTheme.typography.bodyMedium)
                }
                if (anotherMonitorActive) {
                    ProductPanel(tone = ProductTone.WAITING) {
                        Text(
                            stringResource(R.string.other_monitor_must_stop),
                            color = ProductColors.Amber,
                        )
                    }
                }

                if (monitor.kind == MonitorKind.REFERENCE) {
                    ProductSectionHeader(stringResource(R.string.reference_title))
                    ReferenceMaterialsCard(
                        expectedCount = (monitor.target as MonitorTarget.ReferenceImages).imageCount,
                        state = referencePreview,
                        editor = referenceEditor,
                        enabled = !thisMonitorActive,
                        onBeginEdit = { viewModel.beginReferenceEdit(monitor.revision) },
                        onAdd = viewModel::addReferenceImages,
                        onRemove = viewModel::removeReferenceMaterial,
                        onCancel = viewModel::cancelReferenceEdit,
                        onSave = viewModel::saveReferenceEdit,
                    )
                }

                ProductSectionHeader(stringResource(R.string.record_condition_section))
                when (monitor.kind) {
                    MonitorKind.REFERENCE -> TargetPresenceRuleEditor(
                        revision = monitor.revision,
                        rule = monitor.rule as MonitorRule.TargetPresence,
                        enabled = !thisMonitorActive && referenceEditor == null,
                        actionState = actionState,
                        onSave = { viewModel.setPresenceRule(monitor.revision, it) },
                    )
                    MonitorKind.OBJECT_DETECTION -> TargetPresenceRuleEditor(
                        revision = monitor.revision,
                        rule = monitor.rule as MonitorRule.TargetPresence,
                        targetLabel = (monitor.target as MonitorTarget.ObjectClass).localizedLabel(),
                        enabled = !thisMonitorActive,
                        actionState = actionState,
                        onSave = { viewModel.setPresenceRule(monitor.revision, it) },
                    )
                    MonitorKind.READING -> {
                        val readingRule = monitor.rule as MonitorRule.ReadingThreshold
                        if (!readingRule.configured) {
                            PendingReadingBaselinePanel(
                                latestReading = state.latestReadings[monitor.id],
                                confirmedFormat = (monitor.target as MonitorTarget.NumericReading)
                                    .confirmedFormat,
                                onConfirm = { onConfirmReadingBaseline(monitor.id) },
                            )
                        } else {
                            ReadingRuleEditor(
                                revision = monitor.revision,
                                rule = readingRule,
                                confirmedFormat = (monitor.target as MonitorTarget.NumericReading).confirmedFormat,
                                enabled = !thisMonitorActive,
                                actionState = actionState,
                                onSave = { viewModel.setReadingRule(monitor.revision, it) },
                            )
                        }
                    }
                }

                when (modelDisclosure) {
                    ModelDisclosureState.Loading -> if (!thisMonitorActive) {
                        ProductPanel(tone = ProductTone.WAITING) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.model_checking), color = ProductColors.TextSecondary)
                            }
                        }
                    }
                    ModelDisclosureState.Unavailable -> if (!thisMonitorActive) {
                        ProductPanel(tone = ProductTone.WAITING) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    stringResource(R.string.model_not_ready_prepare_on_camera),
                                    color = ProductColors.TextSecondary,
                                )
                                OutlinedButton(
                                    onClick = viewModel::retryModelDisclosure,
                                    modifier = Modifier.testTag(MonitorDetailTags.MODEL_RETRY),
                                ) { Text(stringResource(R.string.action_recheck)) }
                            }
                        }
                    }
                    is ModelDisclosureState.Ready -> Unit
                }

                MonitorNamePanel(
                    name = name,
                    onNameChange = { name = it.take(100) },
                    onSave = { viewModel.rename(name) },
                    saveEnabled = name.isNotBlank() && name.trim() != monitor.name,
                )
                LocalNotificationPanel(
                    checked = state.notificationsEnabled(monitor.id),
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setNotifications(enabled)
                        }
                    },
                )

                HorizontalDivider(color = ProductColors.Border)
                OutlinedButton(
                    onClick = { deleteConfirm = true },
                    enabled = deleteAllowed,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag(MonitorDetailTags.DELETE),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = ProductColors.Error)
                    Text(stringResource(R.string.action_delete_monitor), Modifier.padding(start = 8.dp), color = ProductColors.Error)
                }
                Text(
                    stringResource(
                        if (thisMonitorActive) R.string.monitor_active_edit_limit
                        else R.string.monitor_delete_data_note,
                    ),
                    color = ProductColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (deleteConfirm && deleteAllowed) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.monitor_delete_title, monitor.name)) },
            text = { Text(stringResource(R.string.monitor_delete_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteConfirm = false
                        if (viewModel.referenceEditor.value == null) {
                            viewModel.delete(onDeleted)
                        }
                    },
                    modifier = Modifier.testTag(MonitorDetailTags.DELETE_CONFIRM),
                ) { Text(stringResource(R.string.action_confirm_delete), color = ProductColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun MonitorNamePanel(
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
) {
    ProductPanel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.monitor_name), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.action_save_name)) }
        }
    }
}

@Composable
private fun LocalNotificationPanel(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ProductPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                ProductIconBadge(Icons.Outlined.NotificationsNone, null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.local_notification_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.local_notification_off_still_records),
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            ProductSwitch(
                checked = checked,
                accessibilityLabel = stringResource(R.string.local_notification_title),
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

internal fun monitorDetailDeleteAllowed(
    thisMonitorActive: Boolean,
    referenceEditorOpen: Boolean,
): Boolean = !thisMonitorActive && !referenceEditorOpen

@Composable
private fun MonitorSummaryCard(
    name: String,
    kind: MonitorKind,
    eventCount: Int,
    lastEvent: String?,
    active: Boolean,
    hasStarted: Boolean,
    readingBaselinePending: Boolean,
    modelDisclosure: ModelDisclosureState,
    onOpenHistory: (() -> Unit)?,
) {
    ProductPanel(tone = if (active) ProductTone.ACTIVE else ProductTone.NEUTRAL) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (active) ProductColors.Green else ProductColors.TextMuted,
                ) {}
                Text(
                    when {
                        active -> stringResource(R.string.status_monitoring)
                        hasStarted -> stringResource(R.string.status_stopped)
                        else -> stringResource(R.string.status_ready)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                    color = if (active) ProductColors.Green else ProductColors.TextMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(name, style = MaterialTheme.typography.headlineSmall)
            if (readingBaselinePending) {
                StatusPill(
                    stringResource(R.string.reading_pending_baseline_title),
                    ProductTone.WAITING,
                    Modifier.testTag(MonitorDetailTags.READING_BASELINE_PENDING),
                )
            }
            Text(
                when {
                    readingBaselinePending -> stringResource(R.string.reading_pending_baseline_summary)
                    active -> stringResource(R.string.monitor_running_on_device)
                    hasStarted -> stringResource(R.string.monitor_stopped_data_kept)
                    else -> stringResource(R.string.monitor_saved_not_started)
                },
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                ProductMetric(eventCount.toString(), stringResource(R.string.metric_existing_records), Modifier.weight(1f))
                ProductMetric(
                    monitorKindDisplayName(kind).resolve(),
                    stringResource(R.string.metric_monitor_type),
                    Modifier.weight(1f),
                )
            }
            lastEvent?.let {
                Text(stringResource(R.string.latest_record_value, it), color = ProductColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            if (eventCount > 0 && onOpenHistory != null) {
                TextButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                        .testTag(MonitorDetailTags.OPEN_HISTORY),
                ) {
                    Text(stringResource(R.string.action_view_records))
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp).size(20.dp),
                    )
                }
            }
            Text(
                text = monitorModelDisclosureSummary(active, modelDisclosure).resolve(),
                color = ProductColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun monitorModelDisclosureSummary(
    active: Boolean,
    modelDisclosure: ModelDisclosureState,
): UiText = when {
    modelDisclosure is ModelDisclosureState.Ready ->
        uiText(R.string.model_disclosure_named, modelDisclosure.displayName)
    active -> uiText(R.string.model_disclosure_running)
    modelDisclosure == ModelDisclosureState.Loading -> uiText(R.string.model_disclosure_checking)
    else -> uiText(R.string.model_disclosure_prepare_on_camera)
}

internal fun monitorDetailCameraAllowed(
    anotherMonitorActive: Boolean,
    referenceEditorOpen: Boolean,
    modelDisclosure: ModelDisclosureState,
): Boolean = !anotherMonitorActive && !referenceEditorOpen &&
    modelDisclosure != ModelDisclosureState.Loading

@Composable
private fun TargetPresenceRuleEditor(
    revision: Long,
    rule: MonitorRule.TargetPresence,
    targetLabel: String? = null,
    enabled: Boolean,
    actionState: MonitorDetailActionState,
    onSave: (MonitorRule.TargetPresence) -> Unit,
) {
    var draft by remember(revision) { mutableStateOf(rule) }
    ProductPanel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                targetPresenceRuleTitle(draft, targetLabel),
                style = MaterialTheme.typography.titleMedium,
            )
            TargetPresenceRuleControls(
                rule = draft,
                onRuleChange = { draft = it },
                targetLabel = targetLabel,
                enabled = enabled && !actionState.savingPresenceRule,
            )
            Text(
                stringResource(
                    if (enabled) R.string.presence_editor_enabled_help
                    else R.string.condition_edit_requires_stop,
                ),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            actionState.presenceRuleError?.let {
                Text(it.resolve(), color = ProductColors.Error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { onSave(draft) },
                enabled = enabled && draft != rule && !actionState.savingPresenceRule,
                modifier = Modifier.testTag(MonitorDetailTags.PRESENCE_SAVE),
            ) {
                Text(
                    stringResource(
                        if (actionState.savingPresenceRule) R.string.status_saving
                        else R.string.action_save_record_condition,
                    ),
                )
            }
        }
    }
}

internal fun monitorKindDisplayName(kind: MonitorKind): UiText = when (kind) {
    MonitorKind.REFERENCE -> uiText(R.string.route_reference_images)
    MonitorKind.READING -> uiText(R.string.route_numeric_reading)
    MonitorKind.OBJECT_DETECTION -> uiText(R.string.object_creation_title)
}

@Composable
private fun ReferenceMaterialsCard(
    expectedCount: Int,
    state: ReferencePreviewState,
    editor: ReferenceEditorState?,
    enabled: Boolean,
    onBeginEdit: () -> Unit,
    onAdd: (List<Uri>) -> Unit,
    onRemove: (ReferenceMaterial) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20),
        onAdd,
    )
    ProductPanel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editor != null) {
                val sufficiency = materialSufficiency(editor.materials.size)
                MaterialSufficiencyCard(
                    count = editor.materials.size,
                    stage = sufficiency.stage,
                )
                if (editor.materials.isNotEmpty()) {
                    ReferencePhotoGrid(
                        materials = editor.materials,
                        onRemove = onRemove,
                    )
                }
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !editor.importing && !editor.saving && editor.materials.size < 20,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                        .testTag(MonitorDetailTags.REFERENCE_PICK),
                ) {
                    if (editor.importing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.reference_importing), Modifier.padding(start = 10.dp))
                    } else {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                        Text(stringResource(R.string.reference_continue_adding), Modifier.padding(start = 8.dp))
                    }
                }
                editor.message?.let { message ->
                    Text(message.resolve(), color = ProductColors.Amber, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !editor.saving,
                        modifier = Modifier.weight(1f).height(50.dp)
                            .testTag(MonitorDetailTags.REFERENCE_CANCEL),
                    ) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = onSave,
                        enabled = enabled && editor.canSave,
                        modifier = Modifier.weight(1f).height(50.dp)
                            .testTag(MonitorDetailTags.REFERENCE_SAVE),
                    ) {
                        Text(
                            stringResource(
                                if (editor.saving) R.string.status_saving
                                else R.string.action_save_images,
                            ),
                        )
                    }
                }
                Text(
                    stringResource(R.string.reference_keep_at_least_three),
                    color = ProductColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    style = MaterialTheme.typography.titleMedium,
                    text = pluralStringResource(
                        R.plurals.reference_image_count,
                        expectedCount,
                        expectedCount,
                    ),
                )
                Text(
                    stringResource(R.string.saved_on_this_phone),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when (state) {
                ReferencePreviewState.Loading -> Text(
                    stringResource(R.string.reference_loading_images),
                    color = ProductColors.TextSecondary,
                )
                is ReferencePreviewState.Failed -> ProductPanel(tone = ProductTone.ERROR) {
                    Text(state.message.resolve(), color = ProductColors.Error)
                }
                is ReferencePreviewState.Ready -> {
                    if (state.materials.size != expectedCount) {
                        ProductPanel(tone = ProductTone.ERROR) {
                            Text(stringResource(R.string.reference_images_unavailable_recreate), color = ProductColors.Error)
                        }
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag(MonitorDetailTags.REFERENCE_LIST),
                        ) {
                            itemsIndexed(
                                items = state.materials,
                                key = { _, material -> material.exactSha256 },
                            ) { index, material ->
                                ReferenceThumbnail(material, index + 1)
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(
                    if (enabled) R.string.reference_images_editable_help
                    else R.string.reference_images_require_stop,
                ),
                color = ProductColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (state is ReferencePreviewState.Ready && state.materials.size == expectedCount) {
                OutlinedButton(
                    onClick = onBeginEdit,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                        .testTag(MonitorDetailTags.REFERENCE_EDIT),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                    Text(stringResource(R.string.action_replace_reference_images), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ReferenceThumbnail(material: ReferenceMaterial, position: Int) {
    val image by produceState<ImageBitmap?>(initialValue = null, material.thumbnailUri, material.sourceUri) {
        value = withContext(Dispatchers.IO) {
            decodePrivateThumbnail(material.thumbnailUri ?: material.sourceUri)
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val imageDescription = stringResource(R.string.reference_photo_description, position)
    if (image != null) {
        Image(
            bitmap = checkNotNull(image),
            contentDescription = imageDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(76.dp).clip(shape)
                .border(1.dp, ProductColors.Border, shape),
        )
    } else {
        Column(
            modifier = Modifier.size(76.dp).clip(shape).background(ProductColors.SurfaceRaised)
                .border(1.dp, ProductColors.Border, shape),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.ImageSearch, contentDescription = null, tint = ProductColors.TextMuted)
        }
    }
}

private fun decodePrivateThumbnail(uri: String): ImageBitmap? = runCatching {
    val parsed = uri.toUri()
    require(parsed.scheme == "file")
    val file = File(requireNotNull(parsed.path))
    require(file.isFile)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 256) sampleSize *= 2
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )?.asImageBitmap()
}.getOrNull()

@Composable
private fun PendingReadingBaselinePanel(
    latestReading: LatestReading?,
    confirmedFormat: app.beyoureyes.core.domain.ConfirmedReadingFormat?,
    onConfirm: () -> Unit,
) {
    ProductPanel(tone = ProductTone.WAITING) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductIconBadge(
                    Icons.Outlined.Numbers,
                    null,
                    tint = ProductColors.Cyan,
                    background = ProductColors.CyanSoft,
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        stringResource(R.string.reading_pending_baseline_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (latestReading?.status == ReadingStatus.STABLE) {
                        Text(
                            stringResource(
                                R.string.reading_last_stable_value,
                                latestReadingDisplayText(latestReading, confirmedFormat)
                                    ?: latestReading.valueDecimal.orEmpty(),
                            ),
                            color = ProductColors.Green,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.reading_pending_baseline_body),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            ProductPrimaryButton(
                text = stringResource(R.string.reading_pending_confirm_action),
                onClick = onConfirm,
                leadingIcon = Icons.Outlined.Numbers,
                modifier = Modifier.fillMaxWidth().height(52.dp)
                    .testTag(MonitorDetailTags.READING_BASELINE_CONFIRM),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingRuleEditor(
    revision: Long,
    rule: MonitorRule.ReadingThreshold,
    confirmedFormat: app.beyoureyes.core.domain.ConfirmedReadingFormat?,
    enabled: Boolean,
    actionState: MonitorDetailActionState,
    onSave: (MonitorRule.ReadingThreshold) -> Unit,
) {
    val appLocale = LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
    val initialMode = when (rule) {
        is MonitorRule.ReadingThreshold.Single -> when (rule.comparison) {
            ReadingComparison.GT, ReadingComparison.GTE -> ReadingConditionMode.ABOVE
            ReadingComparison.LT, ReadingComparison.LTE -> ReadingConditionMode.BELOW
        }
        is MonitorRule.ReadingThreshold.Outside -> ReadingConditionMode.OUTSIDE
    }
    var mode by remember(revision) { mutableStateOf(initialMode) }
    var threshold by remember(revision) {
        mutableStateOf(
            when (rule) {
                is MonitorRule.ReadingThreshold.Single ->
                    confirmedFormat?.displayThreshold(rule.thresholdDecimal, appLocale) ?: rule.thresholdDecimal
                is MonitorRule.ReadingThreshold.Outside ->
                    confirmedFormat?.displayThreshold(rule.upperThresholdDecimal, appLocale)
                        ?: rule.upperThresholdDecimal
            },
        )
    }
    var lower by remember(revision) {
        val persisted = (rule as? MonitorRule.ReadingThreshold.Outside)?.lowerThresholdDecimal
        mutableStateOf(persisted?.let { confirmedFormat?.displayThreshold(it, appLocale) ?: it }.orEmpty())
    }
    var upper by remember(revision) {
        val persisted = (rule as? MonitorRule.ReadingThreshold.Outside)?.upperThresholdDecimal
        mutableStateOf(persisted?.let { confirmedFormat?.displayThreshold(it, appLocale) ?: it }.orEmpty())
    }
    var durationSeconds by remember(revision) { mutableIntStateOf(rule.durationSeconds) }
    val validation = confirmedFormat?.let { profile ->
        validateReadingCondition(
            mode = mode,
            thresholdInput = threshold,
            lowerInput = lower,
            upperInput = upper,
            confirmedFormat = profile,
            durationSeconds = durationSeconds,
            locale = appLocale,
        )
    }
    val validRule = (validation as? ReadingConditionValidation.Valid)?.rule

    ProductPanel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductIconBadge(Icons.Outlined.Numbers, null)
                Column(Modifier.padding(start = 13.dp)) {
                    Text(
                        readingRuleSummary(rule, confirmedFormat, appLocale).resolve(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when {
                            !rule.configured -> stringResource(R.string.reading_rule_setup_with_camera)
                            enabled -> stringResource(R.string.reading_rule_crosses_condition)
                            else -> stringResource(R.string.condition_edit_requires_stop)
                        },
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (rule.configured) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        ReadingConditionMode.ABOVE to R.string.condition_above,
                        ReadingConditionMode.BELOW to R.string.condition_below,
                        ReadingConditionMode.OUTSIDE to R.string.condition_outside_short,
                    ).forEach { (candidate, labelRes) ->
                        FilterChip(
                            selected = mode == candidate,
                            onClick = {
                                if (candidate == ReadingConditionMode.OUTSIDE && upper.isBlank()) {
                                    upper = threshold
                                } else if (candidate != ReadingConditionMode.OUTSIDE && threshold.isBlank()) {
                                    threshold = upper
                                }
                                mode = candidate
                            },
                            label = { Text(stringResource(labelRes)) },
                            leadingIcon = if (mode == candidate) {
                                {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else null,
                            enabled = enabled && !actionState.savingReadingRule,
                        )
                    }
                }
                if (mode == ReadingConditionMode.OUTSIDE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DecimalRuleField(
                            value = lower,
                            onValueChange = { lower = it },
                            label = stringResource(R.string.reading_lower_limit),
                            enabled = enabled && !actionState.savingReadingRule,
                            modifier = Modifier.weight(1f),
                        )
                        DecimalRuleField(
                            value = upper,
                            onValueChange = { upper = it },
                            label = stringResource(R.string.reading_upper_limit),
                            enabled = enabled && !actionState.savingReadingRule,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    DecimalRuleField(
                        value = threshold,
                        onValueChange = { threshold = it },
                        label = stringResource(
                            if (mode == ReadingConditionMode.ABOVE) R.string.reading_above_value
                            else R.string.reading_below_value,
                        ),
                        enabled = enabled && !actionState.savingReadingRule,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (validation is ReadingConditionValidation.Invalid && enabled) {
                    Text(
                        stringResource(
                            when (validation.reason) {
                                ReadingConditionInvalidReason.VALUE_FORMAT ->
                                    R.string.reading_validation_value_format
                                ReadingConditionInvalidReason.LOWER_FORMAT ->
                                    R.string.reading_validation_lower_format
                                ReadingConditionInvalidReason.UPPER_FORMAT ->
                                    R.string.reading_validation_upper_format
                                ReadingConditionInvalidReason.ORDER ->
                                    R.string.reading_validation_order
                            },
                        ),
                        color = ProductColors.Error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(R.string.trigger_duration_question), style = MaterialTheme.typography.titleMedium)
                ProductDurationChips(
                    selectedSeconds = durationSeconds,
                    onSelected = { durationSeconds = it },
                    enabled = enabled && !actionState.savingReadingRule,
                )
                Text(
                    stringResource(R.string.condition_duration_once, durationSecondsLabel(durationSeconds)),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                actionState.readingRuleError?.let {
                    Text(it.resolve(), color = ProductColors.Error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { validRule?.let(onSave) },
                    enabled = enabled && validRule != null && validRule != rule &&
                        !actionState.savingReadingRule,
                    modifier = Modifier.testTag(MonitorDetailTags.READING_SAVE),
                ) {
                    Text(
                        stringResource(
                            if (actionState.savingReadingRule) R.string.status_saving
                            else R.string.action_save_record_condition,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DecimalRuleField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(64)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = modifier,
    )
}

internal fun readingRuleSummary(
    rule: MonitorRule.ReadingThreshold,
    confirmedFormat: app.beyoureyes.core.domain.ConfirmedReadingFormat? = null,
    locale: Locale = Locale.ROOT,
): UiText {
    if (!rule.configured) return uiText(R.string.reading_rule_not_configured)
    fun display(value: String): String = confirmedFormat?.displayThreshold(value, locale) ?: value
    return when (rule) {
        is MonitorRule.ReadingThreshold.Single -> when (rule.comparison) {
            ReadingComparison.GT -> uiText(
                R.string.reading_rule_gt_summary,
                display(rule.thresholdDecimal),
                pluralText(R.plurals.duration_seconds, rule.durationSeconds, rule.durationSeconds),
            )
            ReadingComparison.GTE -> uiText(
                R.string.reading_rule_gte_summary,
                display(rule.thresholdDecimal),
                pluralText(R.plurals.duration_seconds, rule.durationSeconds, rule.durationSeconds),
            )
            ReadingComparison.LT -> uiText(
                R.string.reading_rule_lt_summary,
                display(rule.thresholdDecimal),
                pluralText(R.plurals.duration_seconds, rule.durationSeconds, rule.durationSeconds),
            )
            ReadingComparison.LTE -> uiText(
                R.string.reading_rule_lte_summary,
                display(rule.thresholdDecimal),
                pluralText(R.plurals.duration_seconds, rule.durationSeconds, rule.durationSeconds),
            )
        }
        is MonitorRule.ReadingThreshold.Outside -> uiText(
            R.string.reading_rule_outside_summary,
            display(rule.lowerThresholdDecimal),
            display(rule.upperThresholdDecimal),
            pluralText(R.plurals.duration_seconds, rule.durationSeconds, rule.durationSeconds),
        )
    }
}
