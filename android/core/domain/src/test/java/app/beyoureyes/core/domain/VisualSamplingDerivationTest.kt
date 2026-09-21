package app.beyoureyes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSamplingDerivationTest {
    @Test
    fun `duration semantics deterministically derive evidence count gap and symmetric rearm`() {
        val fast = RuntimeMonitorRule.Presence(3_000).resolveForSampling(750)
            as RuntimeMonitorRule.Presence
        val slower = RuntimeMonitorRule.Presence(3_000).resolveForSampling(2_000)
            as RuntimeMonitorRule.Presence

        assertEquals(5, fast.minimumPositiveCount)
        assertEquals(1_500, fast.maximumPositiveGapMillis)
        assertEquals(3_000, fast.rearmAbsenceMillis)
        assertEquals(3, slower.minimumPositiveCount)
        assertEquals(4_000, slower.maximumPositiveGapMillis)
        assertEquals(3_000, slower.rearmAbsenceMillis)
        assertEquals(fast, RuntimeMonitorRule.Presence(3_000).resolveForSampling(750))
    }

    @Test
    fun `duration and interval extremes still yield a formable candidate contract`() {
        val shortDuration = VisualSamplingDerivation.resolve(1_000, 10_000)
        val exactBoundary = VisualSamplingDerivation.resolve(60_000, 10_000)

        assertEquals(2, shortDuration.minimumObservationCount)
        assertEquals(20_000, shortDuration.maximumObservationGapMillis)
        assertEquals(7, exactBoundary.minimumObservationCount)
        assertTrue(exactBoundary.maximumObservationGapMillis >= 10_000)
    }

    @Test
    fun `count duration uses the same task bound evidence and gap derivation`() {
        val count = RuntimeMonitorRule.ObjectCount(
            CountOperator.GTE,
            count = 2,
            durationMillis = 3_000,
            cooldownMillis = 0,
        ).resolveForSampling(1_000) as RuntimeMonitorRule.ObjectCount

        assertEquals(4, count.minimumMatchCount)
        assertEquals(2_000, count.maximumObservationGapMillis)
        assertEquals(1_000L, count.samplingIntervalMillis)
    }

    @Test
    fun `appearance episode remains formable at a thermally adapted two second cadence`() {
        val episode = RuntimeMonitorRule.PresenceEpisode(
            appearanceConfirmMillis = 3_000,
        ).resolveForSampling(2_000) as RuntimeMonitorRule.PresenceEpisode

        assertEquals(6_000L, episode.maximumObservationGapMillis)
        assertEquals(2, episode.minimumObservationCount)
    }
}
