package app.beyoureyes.monitor.feature.history

import androidx.core.net.toUri
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.beyoureyes.core.data.MonitorRepositoryState
import app.beyoureyes.core.domain.MonitorEvent
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductTone
import app.beyoureyes.monitor.design.ProductTopBar
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.resolve
import app.beyoureyes.monitor.design.uiText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun EventHistoryScreen(
    state: MonitorRepositoryState,
    onCreateMonitor: () -> Unit = {},
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
    monitorId: String? = null,
) {
    BackHandler(enabled = showBackButton, onBack = onBack)
    var filter by remember(monitorId) { mutableStateOf(LogFilter.ALL) }
    val entries = remember(state.events, monitorId) { historyEntries(state.events, monitorId) }
    val visible = entries.filter { filter.accepts(it.kind) }
    val zone = ZoneId.systemDefault()
    val grouped = visible.groupByTo(linkedMapOf()) {
        Instant.ofEpochMilli(it.sortAtEpochMillis).atZone(zone).toLocalDate()
    }
    val today = LocalDate.now(zone)
    val monitorNames = remember(state.local, state.remote) {
        buildMap {
            state.local.forEach { put(it.monitor.id, it.monitor.name) }
            state.remote.forEach { put(it.id, it.name) }
        }
    }
    val referenceThumbnails = remember(state.local) {
        state.local.mapNotNull { persisted ->
            persisted.referenceThumbnailUri?.let { persisted.monitor.id to it }
        }.toMap()
    }


    Surface(Modifier.fillMaxSize(), color = ProductColors.Background) {
        Column(
            Modifier.fillMaxSize().then(
                if (monitorId != null) Modifier.safeDrawingPadding() else Modifier,
            ),
        ) {
            ProductTopBar(
                title = if (monitorId == null) stringResource(R.string.history_title) else {
                    monitorNames[monitorId] ?: stringResource(R.string.deleted_monitor)
                },
                eyebrow = if (monitorId != null) stringResource(R.string.history_title) else null,
                onBack = if (showBackButton) onBack else null,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                trailing = {
                    if (entries.isNotEmpty()) {
                        LogFilterMenu(filter = filter, onFilter = { filter = it })
                    }
                },
            )
            HorizontalDivider(color = ProductColors.Border)
            when {
                entries.isEmpty() -> CompactLogEmptyState(
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body),
                    actionLabel = if (monitorId == null) stringResource(R.string.action_create_monitor) else null,
                    onAction = if (monitorId == null) onCreateMonitor else null,
                )
                visible.isEmpty() -> CompactLogEmptyState(
                    title = stringResource(R.string.history_filter_empty_title),
                    body = stringResource(R.string.history_filter_empty_body),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
                        .testTag("history_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    grouped.forEach { (date, dayEntries) ->
                        item(key = "day-$date") {
                            LogDayHeader(
                                label = dayLabel(date, today),
                            )
                        }
                        items(dayEntries, key = DiaryEntry::key) { entry ->
                            LogTimelineRow(
                                entry = entry,
                                monitorName = monitorNames[entry.monitorId]
                                    ?: stringResource(R.string.deleted_monitor),
                                referenceThumbnailUri = referenceThumbnails[entry.monitorId],
                            )
                        }
                    }
                    item { Spacer(Modifier.height(22.dp)) }
                }
            }
        }
    }
}

internal fun historyEntries(events: List<MonitorEvent>, monitorId: String?): List<DiaryEntry> =
    observationDiary(if (monitorId == null) events else events.filter { it.monitorId == monitorId })

@Composable
private fun LogFilterMenu(filter: LogFilter, onFilter: (LogFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = 48.dp).testTag("history_filter_menu"),
        ) {
            Icon(
                Icons.Outlined.FilterList,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(stringResource(filter.labelRes), modifier = Modifier.padding(start = 6.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LogFilter.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(item.labelRes),
                            color = if (item == filter) {
                                ProductColors.Cyan
                            } else {
                                ProductColors.TextPrimary
                            },
                        )
                    },
                    onClick = {
                        onFilter(item)
                        expanded = false
                    },
                    modifier = Modifier.testTag("history_filter_${item.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun CompactLogEmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            modifier = Modifier.padding(top = 6.dp),
            color = ProductColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun LogDayHeader(label: String) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun LogTimelineRow(
    entry: DiaryEntry,
    monitorName: String,
    referenceThumbnailUri: String?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            formatEventTime(entry.sortAtEpochMillis),
            modifier = Modifier.width(58.dp).padding(top = 15.dp),
            color = ProductColors.Cyan,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(Modifier.weight(1f)) {
            when (entry) {
                is DiaryEntry.ReferenceEpisode -> ReferenceLogCard(
                    entry = entry,
                    monitorName = monitorName,
                    referenceThumbnailUri = referenceThumbnailUri,
                )
                is DiaryEntry.Moment -> MomentLogCard(entry, monitorName)
            }
        }
    }
}

@Composable
private fun ReferenceLogCard(
    entry: DiaryEntry.ReferenceEpisode,
    monitorName: String,
    referenceThumbnailUri: String?,
) {
    var expandedSnapshotUri by remember { mutableStateOf<String?>(null) }
    val completed = entry.endedAtEpochMillis != null
    val displayedSnapshotUri = entry.triggerSnapshotUri
    val thumbnailDescription = stringResource(R.string.reference_target_thumbnail)
    val triggerImageDescription = stringResource(R.string.trigger_image_description)
    ProductPanel(
        modifier = Modifier.fillMaxWidth(),
        tone = if (completed) ProductTone.NEUTRAL else ProductTone.WAITING,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (referenceThumbnailUri == null) {
                    ProductIconBadge(
                        icon = Icons.Outlined.ImageSearch,
                        contentDescription = null,
                        tint = if (completed) ProductColors.Cyan else ProductColors.Amber,
                        background = if (completed) ProductColors.CyanSoft else ProductColors.AmberSoft,
                    )
                } else {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                contentDescription = thumbnailDescription
                            }
                        },
                        update = { image -> image.setImageURI(referenceThumbnailUri.toUri()) },
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        monitorName,
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (completed) {
                            stringResource(
                                R.string.history_target_stayed,
                                formatDiaryDuration(entry.durationMillis ?: 0).resolve(),
                            )
                        } else {
                            stringResource(R.string.history_target_appeared)
                        },
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        referenceLogDetail(entry),
                        modifier = Modifier.padding(top = 3.dp),
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SourceBadge(entry.isRemote)
                }
            }
            displayedSnapshotUri?.let { snapshotUri ->
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = triggerImageDescription
                        }
                    },
                    update = { image -> image.setImageURI(snapshotUri.toUri()) },
                    modifier = Modifier.fillMaxWidth().height(132.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expandedSnapshotUri = snapshotUri }
                        .testTag("trigger_snapshot"),
                )
                Text(
                    if (entry.isRemote) {
                        stringResource(R.string.history_remote_trigger_tap)
                    } else {
                        stringResource(R.string.history_local_trigger_tap)
                    },
                    color = ProductColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

        }
    }
    expandedSnapshotUri?.let { snapshotUri ->
        FullScreenSnapshotDialog(
            snapshotUri = snapshotUri,
            onDismiss = { expandedSnapshotUri = null },
        )
    }
}

@Composable
private fun FullScreenSnapshotDialog(
    snapshotUri: String,
    onDismiss: () -> Unit,
) {
    val imageDescription = stringResource(R.string.full_trigger_image)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = imageDescription
                    }
                },
                update = { image -> image.setImageURI(snapshotUri.toUri()) },
                modifier = Modifier.fillMaxSize().testTag("full_trigger_snapshot"),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding()
                    .padding(12.dp).background(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(24.dp),
                    ),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.close_full_image),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun referenceLogDetail(entry: DiaryEntry.ReferenceEpisode): String {
    val endedAt = entry.endedAtEpochMillis
    if (endedAt == null) return stringResource(
        R.string.history_episode_still_visible,
        formatEventTime(entry.startedAtEpochMillis),
    )
    val reason = when (entry.endReason) {
        ReferenceEpisodeEndReason.TARGET_LEFT -> stringResource(R.string.history_end_target_left)
        ReferenceEpisodeEndReason.MONITORING_STOPPED -> stringResource(R.string.history_end_monitor_stopped)
        null -> error("completed reference episode requires an end reason")
    }
    return stringResource(R.string.history_episode_ended, formatEventTime(endedAt), reason)
}



@Composable
private fun MomentLogCard(entry: DiaryEntry.Moment, monitorName: String) {
    val event = entry.event
    val presentation = event.productPresentation()
    val reading = event.fact is MonitorEventFact.ReadingThresholdCrossed
    ProductPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            ProductIconBadge(
                icon = if (reading) Icons.Outlined.Numbers else Icons.Outlined.History,
                contentDescription = null,
                tint = if (event.isRemote) ProductColors.Amber else ProductColors.Cyan,
                background = if (event.isRemote) ProductColors.AmberSoft else ProductColors.CyanSoft,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    monitorName,
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    presentation.headline.resolve(),
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                presentation.detail?.let {
                    Text(
                        it.resolve(),
                        modifier = Modifier.padding(top = 3.dp),
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SourceBadge(event.isRemote)
            }
        }
    }
}

@Composable
private fun SourceBadge(remote: Boolean) {
    if (!remote) return
    Surface(
        modifier = Modifier.padding(top = 7.dp),
        shape = MaterialTheme.shapes.small,
        color = ProductColors.AmberSoft,
    ) {
        Text(
            stringResource(R.string.other_phone),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = ProductColors.Amber,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private enum class LogFilter(val labelRes: Int) {
    ALL(R.string.filter_all),
    REFERENCE(R.string.filter_visual_target),
    READING(R.string.filter_numeric),
    ;

    fun accepts(kind: DiaryEntryKind): Boolean = when (this) {
        ALL -> true
        REFERENCE -> kind == DiaryEntryKind.REFERENCE
        READING -> kind == DiaryEntryKind.READING
    }
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.date_today)
    today.minusDays(1) -> stringResource(R.string.date_yesterday)
    else -> date.format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(LocalConfiguration.current.locales[0] ?: Locale.getDefault()),
    )
}

@Composable
private fun formatEventTime(epochMillis: Long): String = DateTimeFormatter
    .ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(LocalConfiguration.current.locales[0] ?: Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))

internal fun formatDiaryDuration(durationMillis: Long): UiText {
    val seconds = (durationMillis.coerceAtLeast(0) / 1_000L).coerceAtLeast(1)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return when {
        hours > 0 && minutes > 0 -> uiText(R.string.duration_hours_minutes, hours, minutes)
        hours > 0 -> uiText(R.string.duration_hours, hours)
        minutes > 0 && remainingSeconds > 0 ->
            uiText(R.string.duration_minutes_seconds, minutes, remainingSeconds)
        minutes > 0 -> uiText(R.string.duration_minutes, minutes)
        else -> uiText(R.string.duration_seconds_short, remainingSeconds)
    }
}
