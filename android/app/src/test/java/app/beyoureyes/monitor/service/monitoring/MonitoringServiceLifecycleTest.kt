package app.beyoureyes.monitor.service.monitoring

import app.beyoureyes.monitor.RuntimeThermalMode
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringServiceLifecycleTest {
    @Test
    fun `only a critical thermal pause safely stops active monitoring`() {
        assertFalse(shouldStopMonitoringForThermal(RuntimeThermalMode.NORMAL))
        assertFalse(shouldStopMonitoringForThermal(RuntimeThermalMode.LIMITED))
        assertTrue(shouldStopMonitoringForThermal(RuntimeThermalMode.PAUSED))
    }

    @Test
    fun `unexpected active service destruction reports a fatal stop`() {
        assertTrue(
            shouldReportUnexpectedMonitoringServiceDestroy(
                wasActive = true,
                preserveErrorOnDestroy = false,
                explicitStopRequested = false,
            ),
        )
    }

    @Test
    fun `explicit stop and preserved failure do not replace their final state`() {
        assertFalse(
            shouldReportUnexpectedMonitoringServiceDestroy(
                wasActive = true,
                preserveErrorOnDestroy = false,
                explicitStopRequested = true,
            ),
        )
        assertFalse(
            shouldReportUnexpectedMonitoringServiceDestroy(
                wasActive = true,
                preserveErrorOnDestroy = true,
                explicitStopRequested = false,
            ),
        )
    }

    @Test
    fun `destroying an idle service does not create a fatal monitor`() {
        assertFalse(
            shouldReportUnexpectedMonitoringServiceDestroy(
                wasActive = false,
                preserveErrorOnDestroy = false,
                explicitStopRequested = false,
            ),
        )
    }

}
