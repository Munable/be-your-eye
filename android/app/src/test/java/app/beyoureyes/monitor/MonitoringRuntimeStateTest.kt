package app.beyoureyes.monitor

import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringRuntimeStateTest {
    @org.junit.Before
    fun resetRuntime() = MonitoringRuntimeState.resetForProcessStart()

    private fun sample(
        observation: Observation = Observation.State("reference:present", 0.9f, 7L, 3),
        monitorId: String = "monitor-a",
        revision: Long = 1,
    ) = MonitoringObservationSnapshot(monitorId, revision, observation, 100, 110, 120, 125, 5, 10)

    @Test
    fun `hidden stop clears live readings and needs a deliberate camera reopen`() {
        MonitoringRuntimeState.update(MonitoringPhase.RUNNING, monitorId = "monitor-a", monitorRevision = 1)
        MonitoringRuntimeState.recordObservation(sample())
        MonitoringRuntimeState.update(MonitoringPhase.STOPPED, stoppedWhenHidden = true)
        assertTrue(MonitoringRuntimeState.status.value.stoppedWhenHidden)
        assertNull(MonitoringRuntimeState.latestObservationSnapshot.value)
        assertNull(MonitoringRuntimeState.status.value.activeMonitorId)
        MonitoringRuntimeState.acknowledgeHiddenStop()
        assertFalse(MonitoringRuntimeState.status.value.stoppedWhenHidden)
        assertEquals(MonitoringPhase.STOPPED, MonitoringRuntimeState.status.value.phase)
    }

    @Test
    fun `selected reading scene box survives unavailable observations and clears only on stop`() {
        val box = NormalizedRect(0.1f, 0.2f, 0.5f, 0.4f)
        MonitoringRuntimeState.setManualReadingScanRegion(box)
        MonitoringRuntimeState.update(
            MonitoringPhase.RUNNING,
            monitorId = "monitor-a",
            monitorRevision = 1,
        )
        MonitoringRuntimeState.recordObservation(
            sample(Observation.Unavailable(
                app.beyoureyes.core.domain.UnavailableReason.LOW_QUALITY,
                "fixture_miss",
                1,
            )),
        )

        assertEquals(box, MonitoringRuntimeState.manualReadingScanRegion.value)

        MonitoringRuntimeState.update(MonitoringPhase.STOPPED)
        assertNull(MonitoringRuntimeState.manualReadingScanRegion.value)
    }

    @Test
    fun `fresh process always starts stopped instead of restoring a persisted activity marker`() {
        MonitoringRuntimeState.update(
            MonitoringPhase.RUNNING,
            "stale process state",
            monitorId = "monitor-a",
            monitorRevision = 1,
        )

        MonitoringRuntimeState.resetForProcessStart()

        assertEquals(MonitoringPhase.STOPPED, MonitoringRuntimeState.status.value.phase)
        assertEquals(MonitoringHealth.IDLE, MonitoringRuntimeState.status.value.health)
        assertNull(MonitoringRuntimeState.status.value.message)
        assertNull(MonitoringRuntimeState.status.value.monitorId)
        assertNull(MonitoringRuntimeState.frameMetadata.value)
        assertNull(MonitoringRuntimeState.latestObservationSnapshot.value)
    }

    @Test
    fun `interrupted process is recovered as an explicit stopped monitor`() {
        MonitoringRuntimeState.resetForProcessStart("monitor-a", "interrupted process")

        val status = MonitoringRuntimeState.status.value
        assertEquals(MonitoringPhase.STOPPED, status.phase)
        assertEquals(MonitoringHealth.FATAL, status.health)
        assertEquals("monitor-a", status.monitorId)
        assertEquals("interrupted process", status.message)
        assertNull(status.activeMonitorId)
    }

    @Test
    fun `camera connected is not presented as healthy inference`() {
        MonitoringRuntimeState.update(
            MonitoringPhase.RUNNING,
            "相机已连接",
            MonitoringHealth.WARMING,
            monitorId = "monitor-a",
            monitorRevision = 1,
        )

        assertEquals(MonitoringPhase.RUNNING, MonitoringRuntimeState.status.value.phase)
        assertEquals(MonitoringHealth.WARMING, MonitoringRuntimeState.status.value.health)
    }

    @Test
    fun `temporarily unavailable and fatal are distinct typed states`() {
        MonitoringRuntimeState.update(
            MonitoringPhase.RUNNING,
            "本次识别暂时不可用",
            MonitoringHealth.TEMPORARILY_UNAVAILABLE,
            monitorId = "monitor-a",
            monitorRevision = 1,
        )
        assertEquals(
            MonitoringHealth.TEMPORARILY_UNAVAILABLE,
            MonitoringRuntimeState.status.value.health,
        )

        MonitoringRuntimeState.update(
            MonitoringPhase.STOPPED,
            "运行已停止",
            MonitoringHealth.FATAL,
        )
        assertEquals(MonitoringHealth.FATAL, MonitoringRuntimeState.status.value.health)
        assertEquals("monitor-a", MonitoringRuntimeState.status.value.monitorId)
        assertNull(MonitoringRuntimeState.status.value.activeMonitorId)
    }

    @Test
    fun `active monitor identity survives health updates and clears on stop`() {
        MonitoringRuntimeState.update(
            MonitoringPhase.STARTING,
            "正在启动",
            monitorId = "monitor-a",
            monitorRevision = 1,
        )
        MonitoringRuntimeState.update(
            MonitoringPhase.RUNNING,
            "正在识别",
            MonitoringHealth.OBSERVING,
        )

        assertEquals("monitor-a", MonitoringRuntimeState.status.value.monitorId)

        MonitoringRuntimeState.update(MonitoringPhase.STOPPED)
        assertNull(MonitoringRuntimeState.status.value.monitorId)
    }

    @Test
    fun `latest observation is in process only and clears with the monitoring session`() {
        MonitoringRuntimeState.update(MonitoringPhase.RUNNING, monitorId = "monitor-a", monitorRevision = 1)
        MonitoringRuntimeState.recordObservation(sample())

        assertEquals(
            "reference:present",
            (MonitoringRuntimeState.latestObservationSnapshot.value?.observation as Observation.State).stateId,
        )

        MonitoringRuntimeState.clearObservation()
        assertNull(MonitoringRuntimeState.latestObservationSnapshot.value)
    }

    @Test
    fun `snapshots reject foreign revisions and stop accepting when stopped`() {
        MonitoringRuntimeState.update(MonitoringPhase.STARTING, monitorId = "monitor-a", monitorRevision = 2)
        assertFalse(MonitoringRuntimeState.recordObservation(sample()))
        assertFalse(MonitoringRuntimeState.recordObservation(sample(monitorId = "monitor-b", revision = 2)))
        assertTrue(MonitoringRuntimeState.recordObservation(sample(revision = 2)))
        assertFalse(MonitoringRuntimeState.recordObservation(sample(revision = 2)))
        MonitoringRuntimeState.update(MonitoringPhase.RUNNING)
        assertEquals(2L, MonitoringRuntimeState.status.value.monitorRevision)
        assertEquals(7L, MonitoringRuntimeState.latestObservationSnapshot.value?.sourceSequence)
        MonitoringRuntimeState.update(MonitoringPhase.STOPPED)
        assertNull(MonitoringRuntimeState.latestObservationSnapshot.value)
        assertFalse(MonitoringRuntimeState.recordObservation(sample(revision = 2)))
    }

    @Test
    fun `restart clears same revision results and accepts the new frame sequence`() {
        MonitoringRuntimeState.update(MonitoringPhase.RUNNING, monitorId = "monitor-a", monitorRevision = 1)
        assertTrue(MonitoringRuntimeState.recordObservation(sample()))
        MonitoringRuntimeState.update(MonitoringPhase.STARTING)
        assertNull(MonitoringRuntimeState.latestObservationSnapshot.value)
        assertTrue(MonitoringRuntimeState.recordObservation(sample()))
    }

    @Test
    fun `frame diagnostics omit recognized contents and monitor identity`() {
        val snapshot = sample(Observation.Reading("987.6", "987.6", true, 7, 0.9f))
        val metadata = snapshot.diagnosticMetadata()
        assertEquals(8, metadata.size)
        assertEquals("7", metadata["source_sequence"])
        assertEquals("125", metadata["published_elapsed_ms"])
        assertFalse(metadata.values.any { it.contains("987.6") || it.contains("monitor-a") })
    }

    @Test
    fun `deleting the matching fatal monitor clears retained runtime identity`() {
        MonitoringRuntimeState.update(
            MonitoringPhase.STOPPED,
            "相机失败",
            MonitoringHealth.FATAL,
            monitorId = "monitor-a",
            monitorRevision = 1,
        )

        assertFalse(MonitoringRuntimeState.clearFatalForDeletedMonitor("monitor-b"))
        assertEquals(MonitoringHealth.FATAL, MonitoringRuntimeState.status.value.health)

        assertTrue(MonitoringRuntimeState.clearFatalForDeletedMonitor("monitor-a"))
        assertEquals(MonitoringPhase.STOPPED, MonitoringRuntimeState.status.value.phase)
        assertEquals(MonitoringHealth.IDLE, MonitoringRuntimeState.status.value.health)
        assertNull(MonitoringRuntimeState.status.value.monitorId)
    }
}
