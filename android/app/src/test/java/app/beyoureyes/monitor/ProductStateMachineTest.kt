package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelDeliveryFailure
import app.beyoureyes.core.data.ModelPackageDeliveryResult
import app.beyoureyes.core.data.ModelPackageRejection
import app.beyoureyes.core.domain.LatestReading
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.ReadingFormatKind
import app.beyoureyes.core.domain.ReadingStatus
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.monitor.app.navigation.MonitorRouteState
import app.beyoureyes.monitor.app.navigation.monitorRouteState
import app.beyoureyes.monitor.feature.monitoring.liveObservationText
import app.beyoureyes.monitor.feature.monitoring.observationPresentation
import app.beyoureyes.monitor.design.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductStateMachineTest {
    @Test
    fun `running monitor reopens its live status without reacquiring the model`() {
        val status = MonitoringStatus(
            phase = MonitoringPhase.RUNNING,
            monitorId = "monitor-a",
        )

        assertEquals(MonitorRouteState.ACTIVE, monitorRouteState(status, "monitor-a"))
        assertEquals(
            MonitorRouteState.BLOCKED_BY_OTHER,
            monitorRouteState(status, "monitor-b"),
        )
    }

    @Test
    fun `stopped monitor enters setup and camera flow`() {
        assertEquals(
            MonitorRouteState.SETUP,
            monitorRouteState(MonitoringStatus(MonitoringPhase.STOPPED), "monitor-a"),
        )
    }

    @Test
    fun `fatal stop returns to the failed monitor instead of silently reopening setup`() {
        val status = MonitoringStatus(
            phase = MonitoringPhase.STOPPED,
            message = "相机超过 10 秒没有新画面，监控已停止。",
            health = MonitoringHealth.FATAL,
            monitorId = "monitor-a",
        )

        assertEquals(MonitorRouteState.FAILED, monitorRouteState(status, "monitor-a"))
        assertEquals(MonitorRouteState.SETUP, monitorRouteState(status, "monitor-b"))
    }

    @Test
    fun `active runtime lease is not described as a download failure`() {
        val active = ModelPackageDeliveryResult.Rejected(
            reason = ModelDeliveryFailure.STORE_REJECTED,
            storeReasons = setOf(ModelPackageRejection.MONITORING_ACTIVE),
        )
        val corrupt = ModelPackageDeliveryResult.Rejected(
            reason = ModelDeliveryFailure.STORE_REJECTED,
            storeReasons = setOf(ModelPackageRejection.STATE_CORRUPT),
        )

        assertTrue(active.isMonitoringActiveRejection())
        assertFalse(corrupt.isMonitoringActiveRejection())
    }

    @Test
    fun `live screen exposes the current product result instead of only a service clock`() {
        assertEquals(
            uiText(R.string.observation_target_found),
            liveObservationText(
                MonitorKind.REFERENCE,
                Observation.State("reference:present", 0.9f, 1L, 3),
                null,
            ),
        )
        assertEquals(
            uiText(R.string.reading_current_value, "333"),
            liveObservationText(
                MonitorKind.READING,
                Observation.Reading("333", "333", true, 2L, 0.99f),
                null,
            ),
        )
        assertEquals(
            uiText(R.string.reading_current_value, "334"),
            liveObservationText(
                MonitorKind.READING,
                null,
                LatestReading("monitor-a", 1L, ReadingStatus.STABLE, "334", 1L),
            ),
        )
        assertEquals(
            uiText(R.string.reading_current_value, "00:13:52"),
            liveObservationText(
                MonitorKind.READING,
                null,
                LatestReading("monitor-a", 1L, ReadingStatus.STABLE, "832", 1L),
                ConfirmedReadingFormat(ReadingFormatKind.TIME, timeSegments = 3),
            ),
        )
    }

    @Test
    fun `unavailable reading stays explicit while retained evidence remains secondary`() {
        val retained = Observation.Reading("34", "34", true, 7L, 0.99f)
        val unavailable = Observation.Unavailable(
            reason = UnavailableReason.LOW_QUALITY,
            sourceSequence = 8L,
            evidencePaused = true,
            retainedReading = retained,
            retainedReadingAgeMillis = 1_000L,
        )

        assertEquals(
            uiText(R.string.observation_reading_unavailable),
            observationPresentation(MonitorKind.READING, unavailable, null, null).primary,
        )
        assertEquals(
            uiText(R.string.observation_retained_reading, "34", 1.0),
            liveObservationText(MonitorKind.READING, unavailable, null),
        )
    }
}
