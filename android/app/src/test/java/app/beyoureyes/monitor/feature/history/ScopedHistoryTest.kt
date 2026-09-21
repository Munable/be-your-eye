package app.beyoureyes.monitor.feature.history

import app.beyoureyes.core.domain.MonitorEvent
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedHistoryTest {
    @Test
    fun `scoped history pairs only the selected monitor and excludes other local and remote tasks`() {
        val events = listOf(
            event("selected-open", "selected", 1_000),
            event("other-local", "selected-other", 2_000),
            event("other-remote", "remote", 3_000, remote = true),
            event("selected-close", "selected", 5_000, condition = MonitorObjectEventCondition.DISAPPEARED),
        )

        val entry = historyEntries(events, "selected").single() as DiaryEntry.ReferenceEpisode

        assertEquals("selected", entry.monitorId)
        assertEquals("selected-open", entry.triggerEventId)
        assertEquals("selected-close", entry.key)
        assertEquals(4_000L, entry.durationMillis)
    }

    @Test
    fun `remote task selection excludes local tasks and preserves the remote source`() {
        val entries = historyEntries(
            listOf(event("local", "local-task", 1_000), event("remote", "remote-task", 2_000, remote = true)),
            "remote-task",
        )

        assertEquals("remote", entries.single().key)
        assertTrue(entries.single().isRemote)
    }

    @Test
    fun `missing selected monitor stays empty while all history retains every task`() {
        val events = listOf(event("local", "local-task", 1_000), event("remote", "remote-task", 2_000, remote = true))

        assertTrue(historyEntries(events, "missing").isEmpty())
        assertEquals(observationDiary(events), historyEntries(events, null))
    }

    private fun event(
        id: String,
        monitorId: String,
        at: Long,
        remote: Boolean = false,
        condition: MonitorObjectEventCondition = MonitorObjectEventCondition.APPEARED,
    ) = MonitorEvent(
        id = id,
        monitorId = monitorId,
        text = id,
        occurredAtEpochMillis = at,
        isRemote = remote,
        fact = MonitorEventFact.ObjectEpisode("target", condition, 0, 1),
    )
}
