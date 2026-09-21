package app.beyoureyes.monitor.feature.monitoring

import app.beyoureyes.core.data.MonitorRepositoryState
import app.beyoureyes.core.domain.LatestReading
import app.beyoureyes.core.domain.MonitorEvent
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.ReadingStatus
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.uiText
import app.beyoureyes.monitor.MonitoringPhase
import app.beyoureyes.monitor.MonitoringObservationSnapshot
import app.beyoureyes.monitor.MonitoringStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ActiveMonitoringSnapshotTest {
    @Test
    fun `setup live projection updates from the active monitor repository state`() {
        val observation = Observation.Reading(
            text = "123",
            valueDecimal = "123",
            stable = true,
            sourceSequence = 7,
            confidence = 0.99f,
        )
        val status = MonitoringStatus(
            phase = MonitoringPhase.RUNNING,
            monitorId = MONITOR_ID,
        )

        val before = activeMonitoringSnapshot(
            repositoryState = MonitorRepositoryState(),
            monitorId = MONITOR_ID,
            expectedRevision = 1,
            monitoringStatus = status,
            latestObservationSnapshot = sample(observation),
        )

        assertEquals(0, before.eventCount)
        assertNull(before.latestReading)
        assertNull(before.lastEventText)

        val reading = LatestReading(
            monitorId = MONITOR_ID,
            monitorRevision = 1,
            status = ReadingStatus.STABLE,
            valueDecimal = "123",
            updatedAtEpochMillis = 2,
        )
        val updated = activeMonitoringSnapshot(
            repositoryState = MonitorRepositoryState(
                events = listOf(
                    event(id = "current", monitorId = MONITOR_ID),
                    event(id = "other", monitorId = "other-monitor"),
                    event(id = "remote", monitorId = MONITOR_ID, isRemote = true),
                ),
                latestReadings = mapOf(MONITOR_ID to reading),
            ),
            monitorId = MONITOR_ID,
            expectedRevision = 1,
            monitoringStatus = status,
            latestObservationSnapshot = sample(observation),
        )

        assertEquals(1, updated.eventCount)
        assertSame(reading, updated.latestReading)
        assertSame(observation, updated.latestObservation)
        assertEquals(uiText(R.string.event_target_appeared_episode_open), updated.lastEventText)
    }

    @Test
    fun `projection rejects a reading from a stale monitor revision`() {
        val staleReading = LatestReading(
            monitorId = MONITOR_ID,
            monitorRevision = 1,
            status = ReadingStatus.STABLE,
            valueDecimal = "123",
            updatedAtEpochMillis = 2,
        )

        val snapshot = activeMonitoringSnapshot(
            repositoryState = MonitorRepositoryState(
                latestReadings = mapOf(MONITOR_ID to staleReading),
            ),
            monitorId = MONITOR_ID,
            expectedRevision = 2,
            monitoringStatus = MonitoringStatus(
                phase = MonitoringPhase.RUNNING,
                monitorId = MONITOR_ID,
            ),
            latestObservationSnapshot = null,
        )

        assertNull(snapshot.latestReading)
    }

    @Test
    fun `projection never attributes another monitor runtime observation`() {
        val observation = Observation.Reading(
            text = "123",
            valueDecimal = "123",
            stable = true,
            sourceSequence = 7,
            confidence = 0.99f,
        )
        val reading = LatestReading(
            monitorId = MONITOR_ID,
            monitorRevision = 1,
            status = ReadingStatus.STABLE,
            valueDecimal = "123",
            updatedAtEpochMillis = 2,
        )

        val snapshot = activeMonitoringSnapshot(
            repositoryState = MonitorRepositoryState(
                latestReadings = mapOf(MONITOR_ID to reading),
            ),
            monitorId = MONITOR_ID,
            expectedRevision = 1,
            monitoringStatus = MonitoringStatus(
                phase = MonitoringPhase.RUNNING,
                monitorId = "other-monitor",
            ),
            latestObservationSnapshot = sample(observation),
        )

        assertNull(snapshot.latestReading)
        assertNull(snapshot.latestObservation)
    }

    @Test
    fun `starting again does not label a persisted reading as current before the first result`() {
        val reading = LatestReading(MONITOR_ID, 1, ReadingStatus.STABLE, "123", 2)
        val status = MonitoringStatus(MonitoringPhase.STARTING, monitorId = MONITOR_ID)
        val active = activeMonitoringSnapshot(
            MonitorRepositoryState(latestReadings = mapOf(MONITOR_ID to reading)),
            MONITOR_ID, 1, status, null,
        )
        assertNull(active.latestReading)
        assertNull(active.latestObservation)
        assertNull(active.observationSnapshot)
    }

    @Test
    fun `projection rejects observation and timestamp from an earlier revision`() {
        val observation = Observation.Reading("123", "123", true, 7, 0.9f)
        val active = activeMonitoringSnapshot(
            MonitorRepositoryState(), MONITOR_ID, 2,
            MonitoringStatus(MonitoringPhase.RUNNING, monitorId = MONITOR_ID), sample(observation),
        )
        assertNull(active.latestObservation)
        assertNull(active.observationSnapshot)
    }

    private fun sample(observation: Observation) = MonitoringObservationSnapshot(
        MONITOR_ID, 1, observation, 100, 110, 120, 125, 5, 10,
    )

    private fun event(
        id: String,
        monitorId: String,
        isRemote: Boolean = false,
    ) = MonitorEvent(
        id = id,
        monitorId = monitorId,
        text = "fallback",
        occurredAtEpochMillis = 1,
        remoteMonitorName = if (isRemote) "remote" else null,
        isRemote = isRemote,
        fact = MonitorEventFact.ObjectEpisode(
            targetId = "target",
            condition = MonitorObjectEventCondition.APPEARED,
            durationMillis = 0,
            count = 1,
        ),
    )

    private companion object {
        const val MONITOR_ID = "monitor-reading"
    }
}
