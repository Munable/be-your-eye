package app.beyoureyes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveVisualRuleEngineTest {
    @Test
    fun `gap unavailable and clock rollback cannot complete a visual candidate`() {
        val engine = engine(durationMillis = 2_000, intervalMillis = 500)

        assertNull(engine.evaluate(present(1, 0)))
        assertNull(engine.evaluate(present(2, 500)))
        // Derived maximum gap is 1_000 ms; an absent frame interval restarts the candidate.
        assertNull(engine.evaluate(present(3, 1_501)))
        assertNull(engine.evaluate(present(4, 2_001)))
        assertNull(engine.evaluate(unavailable(5, 2_500)))
        assertNull(engine.evaluate(present(6, 3_000)))
        assertNull(engine.evaluate(present(7, 3_500)))
        // Defensive monotonic rollback is fail-closed.
        assertNull(engine.evaluate(present(8, 3_400)))
        assertNull(engine.evaluate(present(9, 4_000)))
        assertNull(engine.evaluate(present(10, 4_500)))
        assertNull(engine.evaluate(present(11, 5_000)))
        assertNull(engine.evaluate(present(12, 5_500)))
        val event = engine.evaluate(present(13, 6_000))

        assertEquals(13L, event?.sourceSequence)
        assertEquals(
            2_000,
            (event?.payload as RestrictedEventPayload.VisualConditionMet).durationMillis,
        )
    }

    @Test
    fun `cadence transition interrupts partial evidence and uses newly derived minimum count`() {
        val engine = engine(durationMillis = 3_000, intervalMillis = 500)
        assertNull(engine.evaluate(present(1, 0)))
        assertNull(engine.evaluate(present(2, 500)))

        engine.updateSamplingInterval(2_000)

        assertNull(engine.evaluate(present(3, 2_000)))
        assertNull(engine.evaluate(present(4, 4_000)))
        // ceil(3000/2000)+1 = 3 observations; the event forms only from post-change evidence.
        val event = engine.evaluate(present(5, 6_000))
        assertEquals(5L, event?.sourceSequence)
    }

    private fun engine(durationMillis: Long, intervalMillis: Long) =
        GenericObservationRuleEngine(
            targetId = TARGET_ID,
            displayName = "门口目标",
            roi = NormalizedRect(0f, 0f, 1f, 1f),
            rule = RuntimeMonitorRule.Presence(durationMillis, intervalMillis),
        )

    private fun present(sequence: Long, millis: Long) = result(
        Observation.Detections(
            listOf(Detection(TARGET_ID, 0.9f, NormalizedRect(0f, 0f, 1f, 1f))),
            sequence,
        ),
        millis,
    )

    private fun unavailable(sequence: Long, millis: Long) = result(
        Observation.Unavailable(UnavailableReason.LOW_QUALITY, "test", sequence),
        millis,
    )

    private data class RuleInput(val observation: Observation, val monotonicMillis: Long)

    private fun GenericObservationRuleEngine.evaluate(input: RuleInput): RuntimeEventCandidate? =
        evaluate(input.observation, input.monotonicMillis)

    private fun result(observation: Observation, millis: Long) = RuleInput(observation, millis)

    private companion object {
        const val TARGET_ID = "task_target"
    }
}
