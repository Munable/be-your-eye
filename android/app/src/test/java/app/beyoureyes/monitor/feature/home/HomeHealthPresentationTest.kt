package app.beyoureyes.monitor.feature.home

import app.beyoureyes.monitor.MonitoringHealth
import app.beyoureyes.monitor.MonitoringPhase
import app.beyoureyes.monitor.MonitoringStatus
import app.beyoureyes.monitor.R
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHealthPresentationTest {
    @Test
    fun `thermal slowdown is distinct from unavailable`() {
        val result = homeHealthPresentation(
            MonitoringStatus(
                phase = MonitoringPhase.RUNNING,
                health = MonitoringHealth.THERMALLY_LIMITED,
                monitorId = "monitor-1",
            ),
            observation = null,
        )

        assertEquals(R.string.home_health_thermal, result.first)
    }

    @Test
    fun `unavailable observation keeps unavailable label`() {
        val result = homeHealthPresentation(
            MonitoringStatus(
                phase = MonitoringPhase.RUNNING,
                health = MonitoringHealth.TEMPORARILY_UNAVAILABLE,
                monitorId = "monitor-1",
            ),
            observation = app.beyoureyes.core.domain.Observation.Unavailable(
                reason = app.beyoureyes.core.domain.UnavailableReason.LOW_QUALITY,
                diagnosticCode = "fixture_miss",
                sourceSequence = 1L,
            ),
        )

        assertEquals(R.string.home_health_unavailable, result.first)
    }
}
