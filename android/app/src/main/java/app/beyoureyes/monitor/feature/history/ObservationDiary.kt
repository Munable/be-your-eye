package app.beyoureyes.monitor.feature.history

import app.beyoureyes.core.domain.MonitorEvent
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import app.beyoureyes.core.domain.MonitoringSessionTransition

internal enum class DiaryEntryKind {
    REFERENCE,
    READING,
    OTHER,
}

internal enum class ReferenceEpisodeEndReason {
    TARGET_LEFT,
    MONITORING_STOPPED,
}

internal sealed interface DiaryEntry {
    val key: String
    val monitorId: String
    val sortAtEpochMillis: Long
    val isRemote: Boolean
    val kind: DiaryEntryKind

    data class ReferenceEpisode(
        override val key: String,
        override val monitorId: String,
        val startedAtEpochMillis: Long,
        val endedAtEpochMillis: Long?,
        val durationMillis: Long?,
        val endReason: ReferenceEpisodeEndReason?,
        val triggerEventId: String?,
        val triggerSnapshotUri: String?,
        override val isRemote: Boolean,
    ) : DiaryEntry {
        override val sortAtEpochMillis: Long = endedAtEpochMillis ?: startedAtEpochMillis
        override val kind = DiaryEntryKind.REFERENCE

        init {
            require(startedAtEpochMillis >= 0)
            require(endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis)
            require(durationMillis == null || durationMillis >= 0)
            require((endedAtEpochMillis == null) == (endReason == null))
        }
    }

    data class Moment(val event: MonitorEvent) : DiaryEntry {
        override val key: String = event.id
        override val monitorId: String = event.monitorId
        override val sortAtEpochMillis: Long = event.occurredAtEpochMillis
        override val isRemote: Boolean = event.isRemote
        override val kind: DiaryEntryKind = when (event.fact) {
            is MonitorEventFact.ReadingThresholdCrossed -> DiaryEntryKind.READING
            is MonitorEventFact.ObjectEpisode -> DiaryEntryKind.REFERENCE
            is MonitorEventFact.VisualConditionMet -> DiaryEntryKind.REFERENCE
            is MonitorEventFact.StateTransition -> if (event.isReferenceMonitoringStop()) {
                DiaryEntryKind.REFERENCE
            } else {
                DiaryEntryKind.OTHER
            }
            is MonitorEventFact.Unknown -> DiaryEntryKind.OTHER
        }
    }
}

/**
 * Projects the append-only event stream into user-facing clues. Reference APPEARED/DISAPPEARED
 * facts are sequential for one fixed target, so a close pairs with the latest open fact from the
 * same monitor and source. A pulled close without its older open reconstructs the start from the
 * signed duration instead of losing the diary entry.
 */
internal fun observationDiary(events: List<MonitorEvent>): List<DiaryEntry> {
    data class StreamKey(val monitorId: String, val remote: Boolean)

    val entries = mutableListOf<DiaryEntry>()
    val open = mutableMapOf<StreamKey, MonitorEvent>()
    events.sortedWith(compareBy<MonitorEvent> { it.occurredAtEpochMillis }.thenBy { it.id })
        .forEach { event ->
            val stream = StreamKey(event.monitorId, event.isRemote)
            val stopped = event.isReferenceMonitoringStop()
            if (stopped) {
                val appeared = open.remove(stream)
                if (appeared == null) {
                    entries += DiaryEntry.Moment(event)
                } else {
                    val endedAt = event.occurredAtEpochMillis
                    val startedAt = appeared.occurredAtEpochMillis.coerceAtMost(endedAt)
                    entries += DiaryEntry.ReferenceEpisode(
                        key = event.id,
                        monitorId = event.monitorId,
                        startedAtEpochMillis = startedAt,
                        endedAtEpochMillis = endedAt,
                        durationMillis = endedAt - startedAt,
                        endReason = ReferenceEpisodeEndReason.MONITORING_STOPPED,
                        triggerEventId = appeared.id,
                        triggerSnapshotUri = appeared.localTriggerSnapshotUri,
                        isRemote = event.isRemote,
                    )
                }
                return@forEach
            }
            val fact = event.fact as? MonitorEventFact.ObjectEpisode
            if (fact == null || fact.condition == MonitorObjectEventCondition.COUNT_MATCHED) {
                entries += DiaryEntry.Moment(event)
                return@forEach
            }
            when (fact.condition) {
                MonitorObjectEventCondition.APPEARED -> {
                    open.put(stream, event)?.let { previous ->
                        entries += previous.toOpenReferenceEpisode()
                    }
                }
                MonitorObjectEventCondition.DISAPPEARED -> {
                    val appeared = open.remove(stream)
                    val endedAt = event.occurredAtEpochMillis
                    val fallbackDuration = fact.durationMillis.coerceAtMost(endedAt)
                    val startedAt = appeared?.occurredAtEpochMillis
                        ?: (endedAt - fallbackDuration).coerceAtLeast(0)
                    entries += DiaryEntry.ReferenceEpisode(
                        key = event.id,
                        monitorId = event.monitorId,
                        startedAtEpochMillis = startedAt,
                        endedAtEpochMillis = endedAt,
                        durationMillis = fact.durationMillis.takeIf { it > 0 }
                            ?: (endedAt - startedAt),
                        endReason = ReferenceEpisodeEndReason.TARGET_LEFT,
                        triggerEventId = appeared?.id,
                        triggerSnapshotUri = appeared?.localTriggerSnapshotUri,
                        isRemote = event.isRemote,
                    )
                }
                MonitorObjectEventCondition.COUNT_MATCHED -> Unit
            }
        }
    open.values.forEach { entries += it.toOpenReferenceEpisode() }
    return entries.sortedWith(
        compareByDescending<DiaryEntry> { it.sortAtEpochMillis }.thenByDescending { it.key },
    )
}

/** Number of user-facing records, not the append-only open/close facts behind them. */
internal fun userVisibleRecordCount(events: List<MonitorEvent>): Int =
    observationDiary(events).count { it.kind != DiaryEntryKind.OTHER }

private fun MonitorEvent.toOpenReferenceEpisode() = DiaryEntry.ReferenceEpisode(
    key = id,
    monitorId = monitorId,
    startedAtEpochMillis = occurredAtEpochMillis,
    endedAtEpochMillis = null,
    durationMillis = null,
    endReason = null,
    triggerEventId = id,
    triggerSnapshotUri = localTriggerSnapshotUri,
    isRemote = isRemote,
)

private fun MonitorEvent.isReferenceMonitoringStop(): Boolean =
    (fact as? MonitorEventFact.StateTransition)?.let {
        it.fromState == MonitoringSessionTransition.REFERENCE_EPISODE_OPEN &&
            it.toState == MonitoringSessionTransition.MONITORING_STOPPED
    } == true
