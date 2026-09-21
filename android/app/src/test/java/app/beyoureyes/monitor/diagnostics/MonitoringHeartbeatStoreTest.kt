package app.beyoureyes.monitor.diagnostics

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MonitoringHeartbeatStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun activeRunSurvivesUntilTheExactRecoveredMarkerIsAcknowledged() {
        val root = temporaryFolder.newFolder("files")
        val store = MonitoringHeartbeatStore(root)

        assertTrue(store.begin("monitor-a", 100))
        assertTrue(store.heartbeat("monitor-a", 150))
        assertTrue(store.heartbeat("monitor-a", 125))
        assertFalse(store.heartbeat("monitor-b", 200))

        val interrupted = MonitoringHeartbeat("monitor-a", 100, 150)
        assertEquals(interrupted, store.interruptedRun())
        assertEquals(interrupted, store.interruptedRun())
        assertTrue(store.acknowledgeInterruptedRun(interrupted))
        assertNull(store.interruptedRun())
    }

    @Test
    fun acknowledgementNeverDeletesANewerMonitoringRun() {
        val store = MonitoringHeartbeatStore(temporaryFolder.newFolder("files"))
        assertTrue(store.begin("monitor-a", 100))
        val interrupted = checkNotNull(store.interruptedRun())

        assertTrue(store.begin("monitor-b", 200))
        assertFalse(store.acknowledgeInterruptedRun(interrupted))
        assertEquals(MonitoringHeartbeat("monitor-b", 200, 200), store.interruptedRun())
    }

    @Test
    fun cleanStopRemovesMarkerAndCorruptMarkerFailsClosed() {
        val root = temporaryFolder.newFolder("files")
        val store = MonitoringHeartbeatStore(root)
        assertTrue(store.begin("monitor-a", 100))
        assertTrue(store.clear("monitor-a"))
        assertNull(store.interruptedRun())

        val marker = root.resolve("monitoring-runtime/active-heartbeat.txt")
        checkNotNull(marker.parentFile).mkdirs()
        marker.writeText("corrupt monitor name and reading must not be recovered")
        assertNull(store.interruptedRun())
        assertFalse(Files.exists(marker.toPath()))
    }

    @Test
    fun sameMonitorKeepsOriginalStartWhileReplacementMonitorStartsNewRun() {
        val store = MonitoringHeartbeatStore(temporaryFolder.newFolder("files"))
        assertTrue(store.begin("monitor-a", 100))
        assertTrue(store.begin("monitor-a", 200))
        val firstRun = checkNotNull(store.interruptedRun())
        assertEquals(MonitoringHeartbeat("monitor-a", 100, 200), firstRun)
        assertTrue(store.acknowledgeInterruptedRun(firstRun))

        assertTrue(store.begin("monitor-b", 300))
        assertEquals(MonitoringHeartbeat("monitor-b", 300, 300), store.interruptedRun())
    }
}
