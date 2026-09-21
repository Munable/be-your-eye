package app.beyoureyes.monitor.feature.history

import app.beyoureyes.core.domain.MonitorEvent
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import app.beyoureyes.core.domain.MonitorReadingOperator
import app.beyoureyes.core.domain.MonitorVisualEventCondition
import app.beyoureyes.core.domain.MonitoringSessionTransition
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.ReadingFormatKind
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationDiaryTest {
    @Test
    fun `duration condition is one moment and never reconstructed as an episode`() {
        val event = MonitorEvent(
            id = "remains",
            monitorId = "monitor-object",
            text = "fallback",
            occurredAtEpochMillis = 5_000,
            fact = MonitorEventFact.VisualConditionMet(
                targetId = "apple",
                condition = MonitorVisualEventCondition.PRESENT_FOR_DURATION,
                durationMillis = 3_000,
            ),
        )

        val entry = observationDiary(listOf(event)).single()

        assertEquals(DiaryEntryKind.REFERENCE, entry.kind)
        assertEquals("remains", (entry as DiaryEntry.Moment).key)
        assertEquals(1, userVisibleRecordCount(listOf(event)))
        assertEquals(uiText(R.string.event_visible_condition_met), event.productPresentation().headline)
        assertEquals(
            uiText(R.string.event_visible_duration, uiText(R.string.duration_seconds_short, 3L)),
            event.productPresentation().detail,
        )
    }

    @Test
    fun `absence duration fact is shown as one confirmed condition`() {
        val event = MonitorEvent(
            id = "disappears",
            monitorId = "monitor-object",
            text = "fallback",
            occurredAtEpochMillis = 8_000,
            fact = MonitorEventFact.VisualConditionMet(
                targetId = "apple",
                condition = MonitorVisualEventCondition.ABSENT_FOR_DURATION,
                durationMillis = 5_000,
            ),
        )

        assertEquals(DiaryEntry.Moment(event), observationDiary(listOf(event)).single())
        assertEquals(uiText(R.string.event_absent_condition_met), event.productPresentation().headline)
        assertEquals(
            uiText(R.string.event_absent_duration, uiText(R.string.duration_seconds_short, 5L)),
            event.productPresentation().detail,
        )
    }

    @Test
    fun `appearance and disappearance become one completed diary clue`() {
        val entries = observationDiary(
            listOf(
                reference("close", 5_000, MonitorObjectEventCondition.DISAPPEARED, 4_000),
                reference(
                    "open",
                    1_000,
                    MonitorObjectEventCondition.APPEARED,
                    0,
                    snapshotUri = "file:///private/trigger.jpg",
                ),
                reading("reading", 3_000),
            ),
        )

        assertEquals(2, entries.size)
        val episode = entries.first() as DiaryEntry.ReferenceEpisode
        assertEquals("close", episode.key)
        assertEquals(1_000L, episode.startedAtEpochMillis)
        assertEquals(5_000L, episode.endedAtEpochMillis)
        assertEquals(4_000L, episode.durationMillis)
        assertEquals(ReferenceEpisodeEndReason.TARGET_LEFT, episode.endReason)
        assertEquals("open", episode.triggerEventId)
        assertEquals("file:///private/trigger.jpg", episode.triggerSnapshotUri)
        assertEquals(DiaryEntryKind.READING, entries.last().kind)
    }

    @Test
    fun `appearance and disappearance count as one user-facing record`() {
        assertEquals(
            1,
            userVisibleRecordCount(
                listOf(
                    reference("open", 1_000, MonitorObjectEventCondition.APPEARED, 0),
                    reference("close", 5_000, MonitorObjectEventCondition.DISAPPEARED, 4_000),
                ),
            ),
        )
    }

    @Test
    fun `open episode stays visible and close without older page reconstructs its start`() {
        val entries = observationDiary(
            listOf(
                reference("orphan-close", 10_000, MonitorObjectEventCondition.DISAPPEARED, 2_500),
                reference("still-open", 12_000, MonitorObjectEventCondition.APPEARED, 0),
            ),
        )

        val open = entries.first() as DiaryEntry.ReferenceEpisode
        assertEquals("still-open", open.triggerEventId)
        assertEquals(12_000L, open.startedAtEpochMillis)
        assertNull(open.endedAtEpochMillis)
        val reconstructed = entries.last() as DiaryEntry.ReferenceEpisode
        assertNull(reconstructed.triggerEventId)
        assertEquals(7_500L, reconstructed.startedAtEpochMillis)
        assertEquals(10_000L, reconstructed.endedAtEpochMillis)
    }

    @Test
    fun `monitor stop closes an open clue without claiming the target left`() {
        val entries = observationDiary(
            listOf(
                reference(
                    "open",
                    1_000,
                    MonitorObjectEventCondition.APPEARED,
                    0,
                    snapshotUri = "file:///private/trigger.jpg",
                ),
                monitoringStopped("stop", 6_000),
            ),
        )

        val episode = entries.single() as DiaryEntry.ReferenceEpisode
        assertEquals(1_000L, episode.startedAtEpochMillis)
        assertEquals(6_000L, episode.endedAtEpochMillis)
        assertEquals(5_000L, episode.durationMillis)
        assertEquals(ReferenceEpisodeEndReason.MONITORING_STOPPED, episode.endReason)
        assertEquals("file:///private/trigger.jpg", episode.triggerSnapshotUri)
    }

    @Test
    fun `monitor stop outside the loaded event page remains a reference diary fact`() {
        val entry = observationDiary(listOf(monitoringStopped("stop", 6_000))).single()

        assertEquals(DiaryEntryKind.REFERENCE, entry.kind)
        assertEquals(
            uiText(R.string.event_monitor_stopped_episode_ended),
            (entry as DiaryEntry.Moment).event.productPresentation().headline,
        )
    }

    @Test
    fun `time event presentation formats stored thresholds for people`() {
        val presentation = MonitorEvent(
            id = "time-event",
            monitorId = "monitor-reading",
            text = "fallback",
            occurredAtEpochMillis = 1,
            fact = MonitorEventFact.ReadingThresholdCrossed(
                displayText = "02:09:15",
                valueDecimal = "7755",
                format = ConfirmedReadingFormat(ReadingFormatKind.TIME, timeSegments = 3),
                unit = null,
                operator = MonitorReadingOperator.GT,
                thresholdDecimal = "7754",
            ),
        ).productPresentation()

        assertEquals(uiText(R.string.event_reading_value, "02:09:15"), presentation.headline)
        assertEquals(uiText(R.string.event_reading_above, "02:09:14"), presentation.detail)
    }

    @Test
    fun `unknown event never exposes notification fallback as a product fact`() {
        val presentation = MonitorEvent(
            id = "unknown",
            monitorId = "monitor-reference",
            text = "unverified raw fallback",
            occurredAtEpochMillis = 1,
            fact = MonitorEventFact.Unknown(
                app.beyoureyes.core.domain.MonitorEventUnknownReason.UNSUPPORTED_TYPE,
            ),
        ).productPresentation()

        assertEquals(uiText(R.string.event_unrecognized_title), presentation.headline)
        assertEquals(uiText(R.string.event_unrecognized_body), presentation.detail)
    }

    private fun reference(
        id: String,
        at: Long,
        condition: MonitorObjectEventCondition,
        duration: Long,
        snapshotUri: String? = null,
    ) = MonitorEvent(
        id = id,
        monitorId = "monitor-reference",
        text = id,
        occurredAtEpochMillis = at,
        localTriggerSnapshotUri = snapshotUri,
        fact = MonitorEventFact.ObjectEpisode("target", condition, duration, 1),
    )

    private fun reading(id: String, at: Long) = MonitorEvent(
        id = id,
        monitorId = "monitor-reading",
        text = id,
        occurredAtEpochMillis = at,
        fact = MonitorEventFact.ReadingThresholdCrossed(
            displayText = "7.58",
            valueDecimal = "7.58",
            format = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL, fractionalDigits = 2),
            unit = null,
            operator = MonitorReadingOperator.GT,
            thresholdDecimal = "7",
        ),
    )

    private fun monitoringStopped(id: String, at: Long) = MonitorEvent(
        id = id,
        monitorId = "monitor-reference",
        text = id,
        occurredAtEpochMillis = at,
        fact = MonitorEventFact.StateTransition(
            MonitoringSessionTransition.REFERENCE_EPISODE_OPEN,
            MonitoringSessionTransition.MONITORING_STOPPED,
        ),
    )
}
