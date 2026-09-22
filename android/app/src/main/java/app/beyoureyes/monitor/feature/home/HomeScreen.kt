package app.beyoureyes.monitor.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.beyoureyes.core.domain.MonitorEvent
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.monitor.MonitoringHealth
import app.beyoureyes.monitor.MonitoringPhase
import app.beyoureyes.monitor.MonitoringStatus
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.activeMonitorId
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductSectionHeader
import app.beyoureyes.monitor.design.ProductTone
import app.beyoureyes.monitor.design.rememberProductPressScale
import app.beyoureyes.monitor.design.resolve
import app.beyoureyes.monitor.feature.history.productPresentation
import app.beyoureyes.monitor.feature.history.summary
import app.beyoureyes.monitor.feature.history.userVisibleRecordCount
import app.beyoureyes.monitor.feature.monitoring.liveObservationText

internal object HomeTags {
    const val SCREEN = "home"
    const val REFERENCE = "create_reference"
    const val READING = "create_reading"
    const val OBJECT = "create_object_detection"
    const val REFERENCE_ARTWORK = "create_reference_artwork"
    const val READING_ARTWORK = "create_reading_artwork"
    const val OBJECT_ARTWORK = "create_object_artwork"
    const val ACTIVE = "active_monitor"
    const val REFERENCE_DRAFT = "reference_draft_card"
    fun monitor(id: String): String = "monitor_$id"
}

@Composable
internal fun HomeScreen(
    viewModel: HomeViewModel,
    monitoringStatus: MonitoringStatus,
    latestObservation: Observation?,
    onReference: () -> Unit,
    onReading: () -> Unit,
    onObjectDetection: () -> Unit,
    onResumeReferenceDraft: (String) -> Unit,
    onOpenMonitor: (String) -> Unit,
) {
    val state by viewModel.monitors.collectAsState()
    val referenceDraft by viewModel.referenceDraft.collectAsState()
    // 每次回到首页都重新发现草稿；离开创建流程后仍可从草稿继续。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.currentStateFlow.collect { current ->
            if (current == androidx.lifecycle.Lifecycle.State.RESUMED) {
                viewModel.refreshReferenceDraft()
            }
        }
    }
    val monitoringActive = monitoringStatus.phase in setOf(
        MonitoringPhase.STARTING,
        MonitoringPhase.RUNNING,
    )
    val activeMonitorId = monitoringStatus.activeMonitorId
    val activeMonitor = state.local.singleOrNull { it.monitor.id == activeMonitorId }
    val statusMonitorId = monitoringStatus.monitorId.takeIf {
        monitoringActive || monitoringStatus.health == MonitoringHealth.FATAL
    }
    val savedMonitors = state.local.filterNot { it.monitor.id == statusMonitorId }
    val listState = rememberLazyListState()
    LaunchedEffect(monitoringActive, activeMonitorId) {
        if (monitoringActive) listState.scrollToItem(0)
    }
    Surface(
        modifier = Modifier.fillMaxSize().testTag(HomeTags.SCREEN),
        color = ProductColors.Background,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                HomeHeader(monitoringStatus, latestObservation)
            }
            item {
                Spacer(
                    Modifier.height(
                        if (monitoringActive || monitoringStatus.health == MonitoringHealth.FATAL) {
                            14.dp
                        } else {
                            2.dp
                        },
                    ),
                )
            }
            when {
                monitoringActive -> item {
                    RunningMonitorCard(
                        name = activeMonitor?.monitor?.name
                            ?: stringResource(R.string.home_monitor_running_fallback),
                        detail = monitoringStatus.message ?: liveObservationText(
                            activeMonitor?.monitor?.kind,
                            latestObservation,
                            activeMonitorId?.let(state.latestReadings::get),
                            (activeMonitor?.monitor?.target as? MonitorTarget.NumericReading)
                                ?.confirmedFormat,
                        ).resolve(),
                        starting = monitoringStatus.phase == MonitoringPhase.STARTING ||
                            monitoringStatus.health == MonitoringHealth.WARMING,
                        onClick = { activeMonitorId?.let(onOpenMonitor) },
                    )
                }
                monitoringStatus.health == MonitoringHealth.FATAL -> item {
                    FailedMonitorCard(
                        message = monitoringStatus.message
                            ?: stringResource(R.string.home_monitor_failure_fallback),
                        onClick = { monitoringStatus.monitorId?.let(onOpenMonitor) },
                    )
                }
                else -> Unit
            }

            if (!monitoringActive && monitoringStatus.health != MonitoringHealth.FATAL) {
                if (monitoringStatus.stoppedWhenHidden) item {
                    ProductPanel {
                        Text(stringResource(R.string.service_stopped_app_hidden), color = ProductColors.TextSecondary)
                    }
                }

            }

            referenceDraft?.let { draft ->
                if (!monitoringActive) {
                    item {
                        ReferenceDraftCard(
                            draft = draft,
                            modifier = Modifier.padding(top = 12.dp),
                            onClick = { onResumeReferenceDraft(draft.sessionId) },
                        )
                    }
                }
            }

            if (!monitoringActive) item {
                ProductSectionHeader(
                    if (savedMonitors.isEmpty()) {
                        stringResource(R.string.home_start_monitoring)
                    } else {
                        stringResource(R.string.home_create_monitor)
                    },
                    modifier = Modifier.padding(top = if (savedMonitors.isEmpty()) 12.dp else 18.dp),
                )
                Text(
                    stringResource(R.string.home_model_download_note),
                    modifier = Modifier.padding(top = 4.dp),
                    color = ProductColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                HomeDividedList(Modifier.padding(top = 8.dp)) {
                    CreationRow(
                        title = stringResource(R.string.home_reference_title),
                        detail = stringResource(R.string.home_reference_detail),
                        artworkRes = R.drawable.home_reference_scene,
                        artworkTag = HomeTags.REFERENCE_ARTWORK,
                        enabled = true,
                        tag = HomeTags.REFERENCE,
                        onClick = onReference,
                    )
                    HorizontalDivider(color = ProductColors.Border)
                    ObjectCreationRow(onClick = onObjectDetection)
                    HorizontalDivider(color = ProductColors.Border)
                    CreationRow(
                        title = stringResource(R.string.home_reading_title),
                        detail = stringResource(R.string.home_reading_detail),
                        artworkRes = R.drawable.home_reading_scene,
                        artworkTag = HomeTags.READING_ARTWORK,
                        enabled = true,
                        tag = HomeTags.READING,
                        onClick = onReading,
                    )
                }
            }

            if (savedMonitors.isNotEmpty()) {
                item {
                    SavedMonitorsSection(savedMonitors, state, onOpenMonitor)
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}



@Composable
private fun ReferenceDraftCard(
    draft: ReferenceDraftSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale by rememberProductPressScale(interactionSource)
    Surface(
        modifier = modifier.fillMaxWidth().testTag(HomeTags.REFERENCE_DRAFT)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics(mergeDescendants = true) { role = Role.Button },
        onClick = onClick,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.medium,
        color = ProductColors.Surface,
        border = BorderStroke(1.dp, ProductColors.Border.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProductIconBadge(
                icon = Icons.Outlined.ImageSearch,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = ProductColors.TextSecondary,
                background = ProductColors.SurfaceHighlight,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    stringResource(R.string.home_resume_draft_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        R.string.home_resume_draft_detail,
                        draft.name.ifBlank { stringResource(R.string.home_resume_draft_unnamed) },
                        draft.materialCount,
                    ),
                    modifier = Modifier.padding(top = 3.dp),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = ProductColors.TextMuted,
            )
        }
    }
}

@Composable
private fun HomeHeader(status: MonitoringStatus, observation: Observation?) {
    val (labelRes, tone) = homeHealthPresentation(status, observation)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)
        HomeHealthLabel(stringResource(labelRes), tone)
    }
}

internal fun homeHealthPresentation(
    status: MonitoringStatus,
    observation: Observation?,
): Pair<Int, ProductTone> = when {
    status.health == MonitoringHealth.FATAL -> R.string.home_health_stopped to ProductTone.ERROR
    status.phase == MonitoringPhase.STARTING || status.health == MonitoringHealth.WARMING -> {
        R.string.home_health_starting to ProductTone.WAITING
    }
    status.health == MonitoringHealth.THERMALLY_LIMITED -> {
        R.string.home_health_thermal to ProductTone.WAITING
    }
    status.health == MonitoringHealth.TEMPORARILY_UNAVAILABLE ||
        observation is Observation.Unavailable -> {
        R.string.home_health_unavailable to ProductTone.WAITING
    }
    status.phase == MonitoringPhase.RUNNING -> R.string.home_health_normal to ProductTone.ACTIVE
    else -> R.string.home_health_idle to ProductTone.NEUTRAL
}

@Composable
private fun HomeHealthLabel(label: String, tone: ProductTone) {
    val color = when (tone) {
        ProductTone.ACTIVE -> ProductColors.Green
        ProductTone.WAITING -> ProductColors.Amber
        ProductTone.ERROR -> ProductColors.Error
        ProductTone.NEUTRAL -> ProductColors.TextMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            label,
            modifier = Modifier.padding(start = 8.dp),
            color = color,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun RunningMonitorCard(
    name: String,
    detail: String,
    starting: Boolean,
    onClick: () -> Unit,
) {
    val tone = if (starting) ProductTone.WAITING else ProductTone.ACTIVE
    ProductPanel(
        modifier = Modifier.testTag(HomeTags.ACTIVE),
        tone = tone,
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeHealthLabel(
                    stringResource(
                        if (starting) R.string.home_health_starting else R.string.home_monitoring_now,
                    ),
                    tone,
                )
                ProductIconBadge(
                    Icons.Outlined.ChevronRight,
                    null,
                    tint = if (starting) ProductColors.Amber else ProductColors.Green,
                    background = if (starting) ProductColors.AmberSoft else ProductColors.GreenSoft,
                )
            }
            Text(name, style = MaterialTheme.typography.headlineMedium)
            Text(detail, color = ProductColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_open_monitor),
                    color = ProductColors.Cyan,
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = ProductColors.Cyan,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun FailedMonitorCard(message: String, onClick: () -> Unit) {
    ProductPanel(tone = ProductTone.ERROR, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductIconBadge(
                Icons.Outlined.MonitorHeart,
                null,
                tint = ProductColors.Error,
                background = ProductColors.ErrorSoft,
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(R.string.home_monitor_stopped), style = MaterialTheme.typography.titleMedium)
                Text(
                    message,
                    modifier = Modifier.padding(top = 3.dp),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = ProductColors.Error)
        }
    }
}

@Composable
private fun HomeDividedList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = ProductColors.Surface,
        border = BorderStroke(1.dp, ProductColors.Border),
    ) {
        Column(content = content)
    }
}

@Composable
private fun ObjectCreationRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp).testTag(HomeTags.OBJECT)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Visibility,
            contentDescription = null,
            tint = ProductColors.Cyan,
            modifier = Modifier.size(58.dp)
                .background(ProductColors.Surface, RoundedCornerShape(13.dp))
                .padding(14.dp)
                .testTag(HomeTags.OBJECT_ARTWORK),
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(stringResource(R.string.home_object_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.home_object_detail),
                modifier = Modifier.padding(top = 3.dp),
                color = ProductColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = ProductColors.Cyan)
    }
}

@Composable
private fun CreationRow(
    title: String,
    detail: String,
    artworkRes: Int,
    artworkTag: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp).testTag(tag)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
            }.then(
                if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
            ).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(artworkRes),
            contentDescription = null,
            modifier = Modifier.size(58.dp)
                .clip(RoundedCornerShape(13.dp))
                .testTag(artworkTag),
            contentScale = ContentScale.Crop,
            alpha = if (enabled) 1f else 0.5f,
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) ProductColors.TextPrimary else ProductColors.TextMuted,
            )
            Text(
                detail,
                modifier = Modifier.padding(top = 3.dp),
                color = ProductColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = if (enabled) ProductColors.Cyan else ProductColors.TextMuted,
        )
    }
}

@Composable
internal fun SavedMonitorRow(
    id: String,
    name: String,
    kind: MonitorKind,
    eventCount: Int,
    hasStarted: Boolean,
    latestEvent: MonitorEvent?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp).testTag(HomeTags.monitor(id))
            .semantics(mergeDescendants = true) { role = Role.Button }.clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductIconBadge(
            icon = when (kind) {
                MonitorKind.REFERENCE -> Icons.Outlined.ImageSearch
                MonitorKind.READING -> Icons.Outlined.Numbers
                MonitorKind.OBJECT_DETECTION -> Icons.Outlined.Visibility
            },
            contentDescription = null,
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (hasStarted) {
                    pluralStringResource(R.plurals.home_saved_stopped_records, eventCount, eventCount)
                } else {
                    stringResource(R.string.home_saved_never_run)
                },
                modifier = Modifier.padding(top = 3.dp),
                color = ProductColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            latestEvent?.let {
                val presentation = it.productPresentation()
                Text(
                    presentation.summary().resolve(),
                    modifier = Modifier.padding(top = 3.dp),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = ProductColors.TextMuted)
    }
}

@Composable
private fun SavedMonitorsSection(
    rows: List<app.beyoureyes.core.data.PersistedMonitor>,
    state: app.beyoureyes.core.data.MonitorRepositoryState,
    onOpenMonitor: (String) -> Unit,
) {
    ProductSectionHeader(
        title = if (rows.any { !state.hasStarted(it.monitor.id) }) {
            stringResource(R.string.home_continue_setup)
        } else {
            stringResource(R.string.home_continue_monitoring)
        },
        modifier = Modifier.padding(top = 18.dp),
    )
    HomeDividedList(Modifier.padding(top = 8.dp)) {
        rows.forEachIndexed { index, row ->
            val events = state.events.filter { !it.isRemote && it.monitorId == row.monitor.id }
            SavedMonitorRow(
                id = row.monitor.id,
                name = row.monitor.name,
                kind = row.monitor.kind,
                eventCount = userVisibleRecordCount(events),
                hasStarted = state.hasStarted(row.monitor.id),
                latestEvent = events.firstOrNull(),
                onClick = { onOpenMonitor(row.monitor.id) },
            )
            if (index != rows.lastIndex) HorizontalDivider(color = ProductColors.Border)
        }
    }
}
