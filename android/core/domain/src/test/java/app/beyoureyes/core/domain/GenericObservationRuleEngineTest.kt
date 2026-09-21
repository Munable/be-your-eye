package app.beyoureyes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericObservationRuleEngineTest {
    @Test
    fun `reference episode opens on confirmed appearance and closes on confirmed absence`() {
        val engine = engine(RuntimeMonitorRule.PresenceEpisode(500, 750, 100))

        assertNull(engine.evaluate(result(state(1, "task_target:absent"), 0)))
        assertNull(engine.evaluate(result(state(2, "task_target:present"), 100)))
        assertNull(engine.evaluate(result(state(3, "task_target:present"), 350)))
        assertNull(engine.evaluate(result(unavailable(4), 450)))
        assertNull(engine.evaluate(result(state(5, "task_target:present"), 600)))
        val appeared = engine.evaluate(result(state(6, "task_target:present"), 850))

        val appearedPayload = appeared?.payload as RestrictedEventPayload.ObjectEpisode
        assertEquals(ObjectEventCondition.APPEARED, appearedPayload.condition)
        assertEquals(0, appearedPayload.durationMillis)
        assertEquals("目标 appeared; recording started", appeared.notificationText)
        assertNull(engine.evaluate(result(state(7, "task_target:present"), 1_000)))

        assertNull(engine.evaluate(result(state(8, "task_target:absent"), 1_100)))
        assertNull(engine.evaluate(result(unavailable(9), 1_300)))
        assertNull(engine.evaluate(result(state(10, "task_target:absent"), 1_500)))
        val disappeared = engine.evaluate(result(state(11, "task_target:absent"), 2_250))

        val disappearedPayload = disappeared?.payload as RestrictedEventPayload.ObjectEpisode
        assertEquals(ObjectEventCondition.DISAPPEARED, disappearedPayload.condition)
        assertEquals(1_400, disappearedPayload.durationMillis)
        assertEquals("目标 left", disappeared.notificationText)
        assertEquals(1L, appeared.episode)
        assertEquals(2L, disappeared.episode)
    }

    @Test
    fun `reference appearance still opens after thermal cadence reaches two seconds`() {
        val engine = engine(
            RuntimeMonitorRule.PresenceEpisode(
                appearanceConfirmMillis = 3_000,
                disappearanceConfirmMillis = 5_000,
                samplingIntervalMillis = 2_000,
            ),
        )

        assertNull(engine.evaluate(result(state(1, "task_target:present"), 0)))
        assertNull(engine.evaluate(result(state(2, "task_target:present"), 2_000)))
        val appeared = engine.evaluate(result(state(3, "task_target:present"), 4_000))

        assertEquals(
            ObjectEventCondition.APPEARED,
            (appeared?.payload as RestrictedEventPayload.ObjectEpisode).condition,
        )
    }

    @Test
    fun `stopping monitoring closes only a confirmed reference episode without inventing absence`() {
        val engine = engine(RuntimeMonitorRule.PresenceEpisode(500, 5_000, 100))

        assertNull(engine.closeOpenReferenceEpisodeForMonitoringStop(0, 0))
        assertNull(engine.evaluate(result(state(1, "task_target:present"), 100)))
        assertNull(engine.closeOpenReferenceEpisodeForMonitoringStop(1, 200))

        assertNull(engine.evaluate(result(state(2, "task_target:present"), 300)))
        val appeared = engine.evaluate(result(state(3, "task_target:present"), 800)
        )
        assertEquals(ObjectEventCondition.APPEARED,
            (appeared?.payload as RestrictedEventPayload.ObjectEpisode).condition)

        val stopped = engine.closeOpenReferenceEpisodeForMonitoringStop(3, 900)
        val payload = stopped?.payload as RestrictedEventPayload.StateTransition
        assertEquals(MonitoringSessionTransition.REFERENCE_EPISODE_OPEN, payload.fromState)
        assertEquals(MonitoringSessionTransition.MONITORING_STOPPED, payload.toState)
        assertEquals("目标 monitoring stopped; episode ended", stopped.notificationText)
        assertNull(engine.closeOpenReferenceEpisodeForMonitoringStop(3, 1_000))
    }

    @Test
    fun `reference unavailable never fabricates disappearance and long gaps reset partial evidence`() {
        val engine = engine(RuntimeMonitorRule.PresenceEpisode(500, 750, 100))

        assertNull(engine.evaluate(result(state(1, "task_target:present"), 0)))
        assertNull(engine.evaluate(result(state(2, "task_target:present"), 250)))
        // max gap is 750 ms; the old partial appearance cannot complete after this gap.
        assertNull(engine.evaluate(result(state(3, "task_target:present"), 1_001)))
        assertNull(engine.evaluate(result(state(4, "task_target:present"), 1_251)))
        assertTrue(engine.evaluate(result(state(5, "task_target:present"), 1_501)) != null)

        assertNull(engine.evaluate(result(unavailable(6), 2_000)))
        assertNull(engine.evaluate(result(unavailable(7), 4_000)))
        assertNull(engine.evaluate(result(state(8, "task_target:present"), 4_100)))
        assertNull(engine.evaluate(result(state(9, "task_target:absent"), 4_200)))
        assertNull(engine.evaluate(result(state(10, "task_target:present"), 4_500)))
        assertNull(engine.evaluate(result(state(11, "task_target:absent"), 5_000)))
        assertNull(engine.evaluate(result(state(12, "task_target:absent"), 5_500)))
        assertTrue(engine.evaluate(result(state(13, "task_target:absent"), 5_750)) != null)
    }

    @Test
    fun `brief unavailable frames preserve bounded reference evidence segments`() {
        val engine = engine(RuntimeMonitorRule.PresenceEpisode(500, 5_000, 100))

        assertNull(engine.evaluate(result(state(1, "task_target:present"), 0)))
        assertNull(engine.evaluate(result(unavailable(2), 200)))
        assertNull(engine.evaluate(result(state(3, "task_target:present"), 300)))
        assertNull(engine.evaluate(result(unavailable(4), 500)))
        assertNull(engine.evaluate(result(state(5, "task_target:present"), 600)))
        assertNull(engine.evaluate(result(unavailable(6), 800)))
        val appeared = engine.evaluate(result(state(7, "task_target:present"), 900))

        assertEquals(
            ObjectEventCondition.APPEARED,
            (appeared?.payload as RestrictedEventPayload.ObjectEpisode).condition,
        )
    }

    @Test
    fun `brief reference loss stays in one episode and only continuous absence closes it`() {
        val engine = engine(RuntimeMonitorRule.PresenceEpisode(500, 5_000, 100))

        assertNull(engine.evaluate(result(state(1, "task_target:present"), 0)))
        val appeared = engine.evaluate(result(state(2, "task_target:present"), 500))
        assertEquals(ObjectEventCondition.APPEARED,
            (appeared?.payload as RestrictedEventPayload.ObjectEpisode).condition)

        assertNull(engine.evaluate(result(state(3, "task_target:absent"), 700)))
        assertNull(engine.evaluate(result(state(4, "task_target:absent"), 1_700)))
        assertNull(engine.evaluate(result(state(5, "task_target:present"), 1_800)))
        assertNull(engine.evaluate(result(state(6, "task_target:absent"), 2_000)))
        (7L..15L).forEach { sequence ->
            val at = 2_000L + (sequence - 6L) * 500L
            assertNull(engine.evaluate(result(state(sequence, "task_target:absent"), at)))
        }
        val disappeared = engine.evaluate(result(state(16, "task_target:absent"), 7_000))

        assertEquals(ObjectEventCondition.DISAPPEARED,
            (disappeared?.payload as RestrictedEventPayload.ObjectEpisode).condition)
        assertEquals(2L, disappeared.episode)
    }

    @Test
    fun `detections obey ROI duration and unavailable restarts the candidate`() {
        val engine = engine(RuntimeMonitorRule.Presence(2_000, 1_000))

        assertNull(engine.evaluate(result(detections(1), 0)))
        assertNull(engine.evaluate(result(detections(2), 1_000)))
        assertNull(engine.evaluate(result(unavailable(3), 1_500)))
        assertNull(engine.evaluate(result(detections(4), 2_000)))
        assertNull(engine.evaluate(result(detections(5), 3_000)))
        val event = engine.evaluate(result(detections(6), 4_000))

        assertEquals(6L, event?.sourceSequence)
        val payload = event?.payload as RestrictedEventPayload.VisualConditionMet
        assertEquals(VisualEventCondition.PRESENT_FOR_DURATION, payload.condition)
        assertEquals(2_000, payload.durationMillis)
        assertEquals("目标 remained visible for 2 s", event.notificationText)
        assertNull(engine.evaluate(result(detections(7), 5_000)))
        assertNull(engine.evaluate(result(emptyDetections(8), 5_500)))
        assertNull(engine.evaluate(result(emptyDetections(9), 6_500)))
        assertNull(engine.evaluate(result(detections(10), 7_000)))
    }

    @Test
    fun `absence requires observed present and rearms only through present`() {
        val engine = engine(RuntimeMonitorRule.Absence(1_000, 1_000))

        assertNull(engine.evaluate(result(state(1, "task_target:absent"), 0)))
        assertNull(engine.evaluate(result(state(2, "task_target:absent"), 1_000)))
        assertNull(engine.evaluate(result(unavailable(3), 1_500)))
        assertNull(engine.evaluate(result(state(4, "task_target:absent"), 2_000)))

        assertNull(engine.evaluate(result(state(5, "task_target:present"), 3_000)))
        assertNull(engine.evaluate(result(state(6, "task_target:absent"), 4_000)))
        assertNull(engine.evaluate(result(unavailable(7), 4_500)))
        assertNull(engine.evaluate(result(state(8, "task_target:absent"), 5_000)))
        val firstEvent = engine.evaluate(result(state(9, "task_target:absent"), 6_000))
        val payload = firstEvent?.payload as RestrictedEventPayload.VisualConditionMet
        assertEquals(VisualEventCondition.ABSENT_FOR_DURATION, payload.condition)
        assertEquals(1_000, payload.durationMillis)
        assertEquals("目标 remained absent for 1 s", firstEvent.notificationText)
        assertNull(engine.evaluate(result(state(10, "task_target:absent"), 7_000)))

        assertNull(engine.evaluate(result(state(11, "task_target:present"), 8_000)))
        assertNull(engine.evaluate(result(state(12, "task_target:present"), 9_000)))
        assertNull(engine.evaluate(result(state(13, "task_target:absent"), 10_000)))
        val secondEvent = engine.evaluate(result(state(14, "task_target:absent"), 11_000))
        assertEquals(1L, firstEvent.episode)
        assertEquals(2L, secondEvent?.episode)
    }

    @Test
    fun `absence accepts object detections and triggers after the target disappears`() {
        val engine = engine(RuntimeMonitorRule.Absence(1_000, 1_000))

        assertNull(engine.evaluate(result(detections(1), 0)))
        assertNull(engine.evaluate(result(emptyDetections(2), 1_000)))
        val event = engine.evaluate(result(emptyDetections(3), 2_000))

        val payload = event?.payload as RestrictedEventPayload.VisualConditionMet
        assertEquals(VisualEventCondition.ABSENT_FOR_DURATION, payload.condition)
        assertEquals(1_000, payload.durationMillis)
        assertEquals(3L, event.sourceSequence)
    }

    @Test
    fun `two matching condition observations spanning 120 milliseconds trigger`() {
        val engine = engine(
            RuntimeMonitorRule.ReadingThreshold.Single(
                operator = ReadingOperator.GTE,
                thresholdDecimal = "10",
                durationMillis = 120,
                hysteresisDecimal = "1",
                cooldownMillis = 0,
            ),
        )

        assertNull(engine.evaluate(result(reading(1, "12", stable = false), 0)))
        assertNull(engine.evaluate(result(unavailable(2), 500)))
        assertNull(engine.evaluate(result(reading(3, "12"), 1_000, 11_000)))
        val firstEvent = engine.evaluate(result(reading(4, "13"), 1_120, 11_120))

        val payload = firstEvent?.payload as RestrictedEventPayload.ReadingThresholdCrossed
        assertEquals("13", payload.valueDecimal)
        assertEquals(11_120, payload.observedAtEpochMillis)
        assertEquals(ReadingOperator.GTE, payload.operator)
        assertNull(engine.evaluate(result(reading(5, "12"), 2_000)))

        assertNull(engine.evaluate(result(reading(6, "8"), 2_500)))
        assertNull(engine.evaluate(result(reading(7, "12"), 3_000)))
        val secondEvent = engine.evaluate(result(reading(8, "13"), 3_120))

        assertEquals(1L, firstEvent.episode)
        assertEquals(2L, secondEvent?.episode)
    }

    @Test
    fun `reading duration requires one continuous match window and unavailable clears it`() {
        val engine = engine(
            RuntimeMonitorRule.ReadingThreshold.Single(
                operator = ReadingOperator.GT,
                thresholdDecimal = "10",
                durationMillis = 3_000,
            ),
        )

        assertNull(engine.evaluate(result(reading(1, "11"), 0)))
        assertNull(engine.evaluate(result(reading(2, "12"), 1_000)))
        assertNull(
            engine.evaluate(
                result(
                    Observation.Unavailable(
                        UnavailableReason.LOW_QUALITY,
                        "retained_ui_only",
                        sourceSequence = 3,
                        evidencePaused = true,
                    ),
                    2_000,
                ),
            ),
        )
        assertNull(engine.evaluate(result(reading(4, "11"), 3_000)))
        assertNull(engine.evaluate(result(reading(5, "12"), 4_000)))
        assertNull(engine.evaluate(result(reading(6, "10"), 5_000)))

        assertNull(engine.evaluate(result(reading(7, "11"), 6_000)))
        assertNull(engine.evaluate(result(reading(8, "12"), 7_000)))
        assertNull(engine.evaluate(result(reading(9, "13"), 8_000)))
        val first = engine.evaluate(result(reading(10, "14"), 9_000))
        assertEquals(1L, first?.episode)
        assertNull(engine.evaluate(result(reading(11, "15"), 10_000)))

        assertNull(engine.evaluate(result(reading(12, "10"), 11_000)))
        assertNull(engine.evaluate(result(reading(13, "11"), 12_000)))
        val second = engine.evaluate(result(reading(14, "12"), 15_000))
        assertEquals(2L, second?.episode)
    }

    @Test
    fun `pending baseline reading rule never produces a condition event`() {
        val engine = engine(
            RuntimeMonitorRule.ReadingThreshold.Single(
                operator = ReadingOperator.GT,
                thresholdDecimal = "10",
                durationMillis = 120,
                configured = false,
            ),
        )

        // Stable values far beyond the placeholder threshold still produce no candidate event.
        assertNull(engine.evaluate(result(reading(1, "999"), 0)))
        assertNull(engine.evaluate(result(reading(2, "999"), 500)))
        assertNull(engine.evaluate(result(reading(3, "999"), 1_000)))
        assertNull(engine.evaluate(result(reading(4, "999"), 2_000)))
        assertNull(engine.evaluate(result(unavailable(5), 3_000)))
        assertNull(engine.evaluate(result(reading(6, "0"), 4_000)))
        assertNull(engine.evaluate(result(reading(7, "999"), 5_000)))
    }

    @Test
    fun `count condition remains continuous while a gte count changes within its match range`() {
        val engine = engine(
            RuntimeMonitorRule.ObjectCount(CountOperator.GTE, 2, 1_000, 0, 500),
        )

        assertNull(engine.evaluate(result(insideDetections(1, 2), 0)))
        assertNull(engine.evaluate(result(insideDetections(2, 3), 500)))
        val event = engine.evaluate(result(insideDetections(3, 2), 1_000))

        val payload = event?.payload as RestrictedEventPayload.ObjectEpisode
        assertEquals(2, payload.count)
    }

    @Test
    fun `every product count operator uses ROI detections and unavailable restarts duration`() {
        listOf(
            CountOperator.EQ to 2,
            CountOperator.GTE to 3,
            CountOperator.LTE to 1,
        ).forEachIndexed { index, (operator, observedCount) ->
            val engine = engine(RuntimeMonitorRule.ObjectCount(operator, 2, 1_000, 0, 500))
            val base = index.toLong() * 10

            assertNull(engine.evaluate(result(countDetections(base + 1, observedCount), 0)))
            assertNull(engine.evaluate(result(unavailable(base + 2), 500)))
            assertNull(engine.evaluate(result(countDetections(base + 3, observedCount), 1_000)))
            assertNull(engine.evaluate(result(countDetections(base + 4, observedCount), 1_500)))
            val event = engine.evaluate(result(countDetections(base + 5, observedCount), 2_000))

            val payload = event?.payload as RestrictedEventPayload.ObjectEpisode
            assertEquals(observedCount, payload.count)
            assertNull(engine.evaluate(result(countDetections(base + 6, observedCount), 3_000)))
        }
    }

    @Test
    fun `count duration restarts when the no-frame gap exceeds signed sampling allowance`() {
        val engine = engine(
            RuntimeMonitorRule.ObjectCount(CountOperator.GTE, 2, 1_000, 0, 500),
        )

        assertNull(engine.evaluate(result(insideDetections(1, 2), 0)))
        assertNull(engine.evaluate(result(insideDetections(2, 2), 500)))
        assertNull(engine.evaluate(result(insideDetections(3, 2), 1_501)))
        assertNull(engine.evaluate(result(insideDetections(4, 2), 2_001)))
        assertTrue(engine.evaluate(result(insideDetections(5, 2), 2_501)) != null)
    }

    @Test
    fun `strict reading operators rearm at equality while inclusive operators do not`() {
        val greaterThan = readingEngine(ReadingOperator.GT)
        assertNull(greaterThan.evaluate(result(reading(1, "11"), 0)))
        assertTrue(greaterThan.evaluate(result(reading(2, "12"), 120)) != null)
        assertNull(greaterThan.evaluate(result(reading(3, "10"), 240)))
        assertNull(greaterThan.evaluate(result(reading(4, "11"), 360)))
        assertTrue(greaterThan.evaluate(result(reading(5, "12"), 480)) != null)

        val greaterThanOrEqual = readingEngine(ReadingOperator.GTE)
        assertNull(greaterThanOrEqual.evaluate(result(reading(1, "10"), 0)))
        assertTrue(greaterThanOrEqual.evaluate(result(reading(2, "11"), 120)) != null)
        assertNull(greaterThanOrEqual.evaluate(result(reading(3, "10"), 240)))
        assertNull(greaterThanOrEqual.evaluate(result(reading(4, "11"), 360)))
        assertNull(greaterThanOrEqual.evaluate(result(reading(5, "9"), 480)))
        assertNull(greaterThanOrEqual.evaluate(result(reading(6, "10"), 600)))
        assertTrue(greaterThanOrEqual.evaluate(result(reading(7, "11"), 720)) != null)

        val lessThan = readingEngine(ReadingOperator.LT)
        assertNull(lessThan.evaluate(result(reading(1, "9"), 0)))
        assertTrue(lessThan.evaluate(result(reading(2, "8"), 120)) != null)
        assertNull(lessThan.evaluate(result(reading(3, "10"), 240)))
        assertNull(lessThan.evaluate(result(reading(4, "9"), 360)))
        assertTrue(lessThan.evaluate(result(reading(5, "8"), 480)) != null)

        val lessThanOrEqual = readingEngine(ReadingOperator.LTE)
        assertNull(lessThanOrEqual.evaluate(result(reading(1, "10"), 0)))
        assertTrue(lessThanOrEqual.evaluate(result(reading(2, "9"), 120)) != null)
        assertNull(lessThanOrEqual.evaluate(result(reading(3, "10"), 240)))
        assertNull(lessThanOrEqual.evaluate(result(reading(4, "9"), 360)))
        assertNull(lessThanOrEqual.evaluate(result(reading(5, "11"), 480)))
        assertNull(lessThanOrEqual.evaluate(result(reading(6, "10"), 600)))
        assertTrue(lessThanOrEqual.evaluate(result(reading(7, "9"), 720)) != null)
    }

    @Test
    fun `outside range stays latched through unavailable and rearms only inside the range`() {
        val engine = engine(
            RuntimeMonitorRule.ReadingThreshold.Outside(
                lowerThresholdDecimal = "10",
                upperThresholdDecimal = "20",
                durationMillis = 120,
                hysteresisDecimal = "0",
                cooldownMillis = 0,
            ),
        )

        assertNull(engine.evaluate(result(reading(1, "9"), 0)))
        val first = engine.evaluate(result(reading(2, "8"), 120))
        assertEquals(1L, first?.episode)
        val firstPayload = first?.payload as RestrictedEventPayload.ReadingThresholdCrossed
        assertEquals(ReadingOperator.OUTSIDE, firstPayload.operator)
        assertEquals("10", firstPayload.lowerThresholdDecimal)
        assertEquals("20", firstPayload.upperThresholdDecimal)

        assertNull(engine.evaluate(result(unavailable(3), 240)))
        assertNull(engine.evaluate(result(reading(4, "8"), 360)))
        assertNull(engine.evaluate(result(reading(5, "15"), 480)))

        assertNull(engine.evaluate(result(reading(6, "21"), 600)))
        val second = engine.evaluate(result(reading(7, "22"), 720))
        assertEquals(2L, second?.episode)
    }

    @Test
    fun `State transition requires from state and restarts stable frame count after unavailable`() {
        val engine = engine(RuntimeMonitorRule.StateTransition("off", "on", 2, 0))

        assertNull(engine.evaluate(result(state(1, "off"), 0)))
        assertNull(engine.evaluate(result(state(2, "on"), 500)))
        assertNull(engine.evaluate(result(unavailable(3), 600)))
        assertNull(engine.evaluate(result(state(4, "on"), 1_000)))
        val event = engine.evaluate(result(state(5, "on"), 1_500))

        assertEquals(
            RestrictedEventPayload.StateTransition("off", "on"),
            event?.payload,
        )
    }

    private fun engine(rule: RuntimeMonitorRule) = GenericObservationRuleEngine(
        targetId = "task_target",
        displayName = "目标",
        roi = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f),
        rule = rule,
    )

    private fun readingEngine(operator: ReadingOperator) = GenericObservationRuleEngine(
        targetId = "reading_target",
        displayName = "读数",
        roi = NormalizedRect(0f, 0f, 1f, 1f),
        rule = RuntimeMonitorRule.ReadingThreshold.Single(
            operator = operator,
            thresholdDecimal = "10",
            durationMillis = 120,
            hysteresisDecimal = "0",
            cooldownMillis = 0,
        ),
    )

    private fun detections(sequence: Long) = Observation.Detections(
        listOf(
            Detection(
                "task_target",
                0.9f,
                NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f),
            ),
            Detection(
                "task_target",
                0.8f,
                NormalizedRect(0.8f, 0.8f, 0.9f, 0.9f),
            ),
        ),
        sequence,
    )

    private fun emptyDetections(sequence: Long) = Observation.Detections(emptyList(), sequence)

    private fun insideDetections(sequence: Long, count: Int) = Observation.Detections(
        List(count) {
            Detection(
                "task_target",
                0.9f,
                NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f),
            )
        },
        sequence,
    )

    private fun countDetections(sequence: Long, insideCount: Int) = Observation.Detections(
        items = insideDetections(sequence, insideCount).items + Detection(
            "task_target",
            0.99f,
            NormalizedRect(0.8f, 0.8f, 0.9f, 0.9f),
        ),
        sourceSequence = sequence,
    )

    private fun unavailable(sequence: Long) = Observation.Unavailable(
        UnavailableReason.LOW_QUALITY,
        "test",
        sequence,
    )

    private fun state(sequence: Long, id: String) = Observation.State(id, 0.9f, sequence, 1)

    private fun reading(sequence: Long, value: String, stable: Boolean = true) = Observation.Reading(
        text = value,
        valueDecimal = value,
        stable = stable,
        sourceSequence = sequence,
        confidence = 0.95f,
    )

    private data class RuleInput(
        val observation: Observation,
        val monotonicMillis: Long,
        val capturedAtEpochMillis: Long?,
    )

    private fun GenericObservationRuleEngine.evaluate(input: RuleInput): RuntimeEventCandidate? =
        evaluate(input.observation, input.monotonicMillis, input.capturedAtEpochMillis)

    private fun result(
        observation: Observation,
        monotonicMillis: Long,
        capturedAtEpochMillis: Long = monotonicMillis,
    ) = RuleInput(observation, monotonicMillis, capturedAtEpochMillis)

}
