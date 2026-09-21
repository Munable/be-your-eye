package app.beyoureyes.monitor

import app.beyoureyes.core.domain.RuntimeMonitorRule
import app.beyoureyes.core.domain.GenericObservationRuleEngine
import app.beyoureyes.core.data.EventSink
import app.beyoureyes.core.data.EventTaskSnapshot
import app.beyoureyes.core.data.EventWriteRequest
import app.beyoureyes.core.data.EventWriteResult
import app.beyoureyes.core.data.LocalNotificationStore
import app.beyoureyes.core.data.PendingLocalNotification
import app.beyoureyes.core.domain.RestrictedEventPayload
import app.beyoureyes.core.domain.Detection
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.MonotonicFrameSampler
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.core.vision.PipelineTimings
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericMonitoringRuntimeBridgeTest {
    @Test
    fun `visual duration state produces one durable notification per satisfied condition`() = runBlocking {
        val store = PendingStore()
        val published = mutableListOf<Pair<String, String>>()
        val coordinator = EventNotificationCoordinator(
            eventSink = store,
            notificationStore = store,
            contextResolver = EventNotificationContextResolver {
                EventNotificationContext(TASK_ID, EPOCH)
            },
            publisher = EventNotificationPublisher { eventId, _, text ->
                published += eventId to text
            },
            epochMillis = { EPOCH },
        )
        var present = true
        val runtime = MonitoringFrameRuntime { frame ->
            RuntimeFrameResult.Processed(
                PipelineResult(
                    frame.sourceSequence,
                    frame.monotonicTimeMillis,
                    frame.capturedAtEpochMillis,
                    Observation.State(
                        stateId = "task_target:${if (present) "present" else "absent"}",
                        confidence = 0.9f,
                        sourceSequence = frame.sourceSequence,
                        stableFrameCount = 1,
                    ),
                    PipelineTimings(0, 0, 0, 0, 0),
                ),
            )
        }
        val ids = ArrayDeque(
            listOf(
                EPISODE_ID,
                EVENT_ID,
                "01900000-0000-7000-8000-000000000003",
                "01900000-0000-7000-8000-000000000004",
            ),
        )
        val bridge = GenericMonitoringRuntimeBridge(
            runtime = runtime,
            taskSnapshot = EventTaskSnapshot(TASK_ID, 1, "{\"snapshot\":true}"),
            coordinator = coordinator,
            ruleEngine = GenericObservationRuleEngine(
                targetId = "task_target",
                displayName = "白色药盒",
                roi = NormalizedRect(0f, 0f, 1f, 1f),
                rule = RuntimeMonitorRule.Presence(1_000, 500),
            ),
            samplingPolicy = FrameSamplingPolicy(500),
            epochMillis = { EPOCH },
            uuidV7 = { ids.removeFirst() },
        )

        assertNull(bridge.acceptLazy(1, 0) { frame(1, 0) }.eventRequest)
        assertNull(bridge.acceptLazy(2, 500) { frame(2, 500) }.eventRequest)
        assertEquals(EVENT_ID, bridge.acceptLazy(3, 1_000) { frame(3, 1_000) }
            .eventWriteResult?.eventId)
        assertNull(bridge.acceptLazy(4, 1_500) { frame(4, 1_500) }.eventRequest)

        present = false
        assertNull(bridge.acceptLazy(5, 2_000) { frame(5, 2_000) }.eventRequest)
        assertNull(bridge.acceptLazy(6, 2_500) { frame(6, 2_500) }.eventRequest)
        assertNull(bridge.acceptLazy(7, 3_000) { frame(7, 3_000) }.eventRequest)
        present = true
        assertNull(bridge.acceptLazy(8, 3_500) { frame(8, 3_500) }.eventRequest)
        assertNull(bridge.acceptLazy(9, 4_000) { frame(9, 4_000) }.eventRequest)
        val second = bridge.acceptLazy(10, 4_500) { frame(10, 4_500) }

        assertEquals(2, store.requests.size)
        assertEquals(2, published.size)
        assertEquals(
            listOf(
                "白色药盒 remained visible for 1 s",
                "白色药盒 remained visible for 1 s",
            ),
            published.map { it.second },
        )
        assertEquals("01900000-0000-7000-8000-000000000004", second.eventWriteResult?.eventId)
        assertTrue(store.requests.all {
            (it.payload as RestrictedEventPayload.VisualConditionMet).condition ==
                app.beyoureyes.core.domain.VisualEventCondition.PRESENT_FOR_DURATION
        })
    }

    @Test
    fun `explicit stop durably closes an open reference episode without a local notification`() =
        runBlocking {
            val store = PendingStore()
            val published = mutableListOf<String>()
            val coordinator = EventNotificationCoordinator(
                eventSink = store,
                notificationStore = store,
                contextResolver = EventNotificationContextResolver {
                    EventNotificationContext(TASK_ID, EPOCH)
                },
                publisher = EventNotificationPublisher { eventId, _, _ -> published += eventId },
                epochMillis = { EPOCH + 600 },
            )
            val runtime = MonitoringFrameRuntime { frame ->
                RuntimeFrameResult.Processed(
                    PipelineResult(
                        frame.sourceSequence,
                        frame.monotonicTimeMillis,
                        frame.capturedAtEpochMillis,
                        Observation.State(
                            stateId = "task_target:present",
                            confidence = 0.9f,
                            sourceSequence = frame.sourceSequence,
                            stableFrameCount = 1,
                        ),
                        PipelineTimings(0, 0, 0, 0, 0),
                    ),
                )
            }
            val ids = ArrayDeque(
                listOf(
                    EPISODE_ID,
                    EVENT_ID,
                    "01900000-0000-7000-8000-000000000003",
                    "01900000-0000-7000-8000-000000000004",
                ),
            )
            val bridge = GenericMonitoringRuntimeBridge(
                runtime = runtime,
                taskSnapshot = EventTaskSnapshot(TASK_ID, 1, "{\"snapshot\":true}"),
                coordinator = coordinator,
                ruleEngine = GenericObservationRuleEngine(
                    targetId = "task_target",
                    displayName = "白色药盒",
                    roi = NormalizedRect(0f, 0f, 1f, 1f),
                    rule = RuntimeMonitorRule.PresenceEpisode(500, 5_000, 500),
                ),
                samplingPolicy = FrameSamplingPolicy(500),
                uuidV7 = { ids.removeFirst() },
            )

            assertNull(bridge.acceptLazy(1, 0) { frame(1, 0) }.eventRequest)
            assertEquals(
                EVENT_ID,
                bridge.acceptLazy(2, 500) { frame(2, 500) }.eventWriteResult?.eventId,
            )
            val stopped = bridge.closeOpenReferenceEpisodeForMonitoringStop(
                sourceSequence = 2,
                monotonicTimeMillis = 600,
                occurredAtEpochMillis = EPOCH + 600,
            )

            assertEquals("01900000-0000-7000-8000-000000000004", stopped?.eventId)
            assertEquals(listOf(EVENT_ID), published)
            assertEquals(2, store.requests.size)
            val payload = store.requests.last().payload as RestrictedEventPayload.StateTransition
            assertEquals("reference_episode_open", payload.fromState)
            assertEquals("monitoring_stopped", payload.toState)
            assertEquals(null, store.pending)
        }

    @Test
    fun `generic runtime persists before one notification and skips without copying a frame`() = runBlocking {
        val store = PendingStore()
        val published = mutableListOf<String>()
        val coordinator = EventNotificationCoordinator(
            eventSink = store,
            notificationStore = store,
            contextResolver = EventNotificationContextResolver {
                EventNotificationContext(TASK_ID, EPOCH)
            },
            publisher = EventNotificationPublisher { eventId, _, _ -> published += eventId },
            epochMillis = { EPOCH },
        )
        var runtimeCalls = 0
        val runtime = MonitoringFrameRuntime { frame ->
            runtimeCalls++
            RuntimeFrameResult.Processed(
                PipelineResult(
                    frame.sourceSequence,
                    frame.monotonicTimeMillis,
                    frame.capturedAtEpochMillis,
                    Observation.Detections(
                        listOf(Detection("task_target", 0.9f, NormalizedRect(0f, 0f, 1f, 1f))),
                        frame.sourceSequence,
                    ),
                    PipelineTimings(0, 0, 0, 0, 0),
                ),
            )
        }
        val ids = ArrayDeque(listOf(EPISODE_ID, EVENT_ID))
        val bridge = GenericMonitoringRuntimeBridge(
            runtime = runtime,
            taskSnapshot = EventTaskSnapshot(TASK_ID, 1, "{\"snapshot\":true}"),
            coordinator = coordinator,
            ruleEngine = GenericObservationRuleEngine(
                "task_target",
                "门口纸箱",
                NormalizedRect(0f, 0f, 1f, 1f),
                RuntimeMonitorRule.Presence(1_000, 500),
            ),
            samplingPolicy = FrameSamplingPolicy(500),
            epochMillis = { EPOCH },
            uuidV7 = { ids.removeFirst() },
        )

        assertNull(bridge.acceptLazy(1, 0) { frame(1, 0) }.eventRequest)
        var copied = false
        val skipped = bridge.acceptLazy(2, 100) {
            copied = true
            frame(2, 100)
        }
        assertTrue(skipped.runtimeResult is RuntimeFrameResult.Skipped)
        assertEquals(false, copied)
        assertNull(bridge.acceptLazy(3, 500) { frame(3, 500) }.eventRequest)
        val triggered = bridge.acceptLazy(4, 1_000) { frame(4, 1_000) }

        assertEquals(3, runtimeCalls)
        assertEquals(EVENT_ID, triggered.eventWriteResult?.eventId)
        assertEquals(1, store.requests.size)
        assertTrue(store.requests.single().payload is RestrictedEventPayload.VisualConditionMet)
        assertEquals(listOf(EVENT_ID), published)
        assertTrue(store.delivered)
    }

    @Test
    fun `failed event persistence rolls back its prepared side effect`() {
        val store = PendingStore { error("persist failed") }
        val preparedEventIds = mutableSetOf<String>()
        val ids = ArrayDeque(listOf(EPISODE_ID, EVENT_ID))
        val bridge = GenericMonitoringRuntimeBridge(
            runtime = alwaysPresentRuntime(),
            taskSnapshot = EventTaskSnapshot(TASK_ID, 1, "{\"snapshot\":true}"),
            coordinator = coordinator(store),
            ruleEngine = GenericObservationRuleEngine(
                "task_target",
                "门口纸箱",
                NormalizedRect(0f, 0f, 1f, 1f),
                RuntimeMonitorRule.Presence(1_000, 500),
            ),
            samplingPolicy = FrameSamplingPolicy(500),
            beforeEventPersist = { request, _ ->
                check(preparedEventIds.add(request.eventId))
                val rollback: () -> Unit = { preparedEventIds.remove(request.eventId) }
                rollback
            },
            uuidV7 = { ids.removeFirst() },
        )

        runBlocking {
            assertNull(bridge.acceptLazy(1, 0) { frame(1, 0) }.eventRequest)
            assertNull(bridge.acceptLazy(2, 500) { frame(2, 500) }.eventRequest)
        }
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { bridge.acceptLazy(3, 1_000) { frame(3, 1_000) } }
        }

        assertEquals("persist failed", failure.message)
        assertTrue(preparedEventIds.isEmpty())
        assertEquals(EVENT_ID, store.requests.single().eventId)
    }

    @Test
    fun `episode duplicate rolls back a snapshot prepared for the non durable request id`() = runBlocking {
        val store = PendingStore { EventWriteResult.Duplicate(EXISTING_EVENT_ID) }
        val preparedEventIds = mutableSetOf<String>()
        val ids = ArrayDeque(listOf(EPISODE_ID, EVENT_ID))
        val bridge = GenericMonitoringRuntimeBridge(
            runtime = alwaysPresentRuntime(),
            taskSnapshot = EventTaskSnapshot(TASK_ID, 1, "{\"snapshot\":true}"),
            coordinator = coordinator(store),
            ruleEngine = GenericObservationRuleEngine(
                "task_target",
                "门口纸箱",
                NormalizedRect(0f, 0f, 1f, 1f),
                RuntimeMonitorRule.Presence(1_000, 500),
            ),
            samplingPolicy = FrameSamplingPolicy(500),
            beforeEventPersist = { request, _ ->
                check(preparedEventIds.add(request.eventId))
                val rollback: () -> Unit = { preparedEventIds.remove(request.eventId) }
                rollback
            },
            uuidV7 = { ids.removeFirst() },
        )

        assertNull(bridge.acceptLazy(1, 0) { frame(1, 0) }.eventRequest)
        assertNull(bridge.acceptLazy(2, 500) { frame(2, 500) }.eventRequest)
        val result = bridge.acceptLazy(3, 1_000) { frame(3, 1_000) }

        assertEquals(EventWriteResult.Duplicate(EXISTING_EVENT_ID), result.eventWriteResult)
        assertTrue(preparedEventIds.isEmpty())
        assertEquals(EVENT_ID, store.requests.single().eventId)
    }

    @Test
    fun `reset clears both bridge and underlying runtime sampling gates`() = runBlocking {
        val store = PendingStore()
        val coordinator = EventNotificationCoordinator(
            eventSink = store,
            notificationStore = store,
            contextResolver = EventNotificationContextResolver {
                EventNotificationContext(TASK_ID, EPOCH)
            },
            publisher = EventNotificationPublisher { _, _, _ -> },
            epochMillis = { EPOCH },
        )
        val runtimeSampler = MonotonicFrameSampler(FrameSamplingPolicy(500))
        var resetCalls = 0
        val runtime = object : MonitoringFrameRuntime {
            override fun process(frame: SourceFrame): RuntimeFrameResult {
                if (!runtimeSampler.shouldProcess(frame.monotonicTimeMillis)) {
                    return RuntimeFrameResult.Skipped(frame.sourceSequence)
                }
                return RuntimeFrameResult.Processed(
                    PipelineResult(
                        frame.sourceSequence,
                        frame.monotonicTimeMillis,
                        frame.capturedAtEpochMillis,
                        Observation.Detections(emptyList(), frame.sourceSequence),
                        PipelineTimings(0, 0, 0, 0, 0),
                    ),
                )
            }

            override fun resetSampling() {
                resetCalls++
                runtimeSampler.reset()
            }
        }
        val bridge = GenericMonitoringRuntimeBridge(
            runtime = runtime,
            taskSnapshot = EventTaskSnapshot(TASK_ID, 1, "{\"snapshot\":true}"),
            coordinator = coordinator,
            ruleEngine = GenericObservationRuleEngine(
                "task_target",
                "门口纸箱",
                NormalizedRect(0f, 0f, 1f, 1f),
                RuntimeMonitorRule.Presence(1_000, 500),
            ),
            samplingPolicy = FrameSamplingPolicy(500),
        )

        assertTrue(bridge.acceptLazy(1, 1_000) { frame(1, 1_000) }.runtimeResult is RuntimeFrameResult.Processed)
        bridge.reset()
        val firstAfterReset = bridge.acceptLazy(2, 1_000) { frame(2, 1_000) }

        assertEquals(1, resetCalls)
        assertTrue(firstAfterReset.runtimeResult is RuntimeFrameResult.Processed)
    }

    @Test
    fun `bounded cadence update changes outer and runtime sampling gates together`() = runBlocking {
        val store = PendingStore()
        val coordinator = EventNotificationCoordinator(
            eventSink = store,
            notificationStore = store,
            contextResolver = EventNotificationContextResolver {
                EventNotificationContext(TASK_ID, EPOCH)
            },
            publisher = EventNotificationPublisher { _, _, _ -> },
        )
        val runtimeSampler = MonotonicFrameSampler(FrameSamplingPolicy(500))
        var runtimeCalls = 0
        var updateCalls = 0
        val runtime = object : MonitoringFrameRuntime {
            override fun process(frame: SourceFrame): RuntimeFrameResult {
                if (!runtimeSampler.shouldProcess(frame.monotonicTimeMillis)) {
                    return RuntimeFrameResult.Skipped(frame.sourceSequence)
                }
                runtimeCalls++
                return RuntimeFrameResult.Processed(
                    PipelineResult(
                        frame.sourceSequence,
                        frame.monotonicTimeMillis,
                        frame.capturedAtEpochMillis,
                        Observation.Detections(emptyList(), frame.sourceSequence),
                        PipelineTimings(0, 0, 0, 0, 0),
                    ),
                )
            }

            override fun updateSamplingPolicy(policy: FrameSamplingPolicy) {
                updateCalls++
                runtimeSampler.updatePolicy(policy)
            }
        }
        val bridge = GenericMonitoringRuntimeBridge(
            runtime = runtime,
            taskSnapshot = EventTaskSnapshot(TASK_ID, 1, "{\"snapshot\":true}"),
            coordinator = coordinator,
            ruleEngine = GenericObservationRuleEngine(
                "task_target",
                "门口纸箱",
                NormalizedRect(0f, 0f, 1f, 1f),
                RuntimeMonitorRule.Presence(1_000, 500),
            ),
            samplingPolicy = FrameSamplingPolicy(500),
        )

        assertTrue(bridge.acceptLazy(1, 0) { frame(1, 0) }.runtimeResult
            is RuntimeFrameResult.Processed)
        bridge.updateSamplingPolicy(FrameSamplingPolicy(1_000))
        var copied = false
        assertTrue(bridge.acceptLazy(2, 500) {
            copied = true
            frame(2, 500)
        }.runtimeResult is RuntimeFrameResult.Skipped)
        assertEquals(false, copied)
        assertTrue(bridge.acceptLazy(3, 1_000) { frame(3, 1_000) }.runtimeResult
            is RuntimeFrameResult.Processed)

        assertEquals(FrameSamplingPolicy(1_000), bridge.currentSamplingPolicy())
        assertEquals(1, updateCalls)
        assertEquals(2, runtimeCalls)
    }

    private fun frame(sequence: Long, monotonic: Long) = SourceFrame(
        sourceSequence = sequence,
        monotonicTimeMillis = monotonic,
        capturedAtEpochMillis = EPOCH + monotonic,
        width = 1,
        height = 1,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, 1, 1),
        pixels = FramePixels.Rgb888(byteArrayOf(0, 0, 0), 3),
    )

    private fun alwaysPresentRuntime() = MonitoringFrameRuntime { frame ->
        RuntimeFrameResult.Processed(
            PipelineResult(
                frame.sourceSequence,
                frame.monotonicTimeMillis,
                frame.capturedAtEpochMillis,
                Observation.State(
                    stateId = "task_target:present",
                    confidence = 0.9f,
                    sourceSequence = frame.sourceSequence,
                    stableFrameCount = 1,
                ),
                PipelineTimings(0, 0, 0, 0, 0),
            ),
        )
    }

    private fun coordinator(store: PendingStore) = EventNotificationCoordinator(
        eventSink = store,
        notificationStore = store,
        contextResolver = EventNotificationContextResolver {
            EventNotificationContext(TASK_ID, EPOCH)
        },
        publisher = EventNotificationPublisher { _, _, _ -> },
        epochMillis = { EPOCH },
    )

    private class PendingStore(
        private val persistResult: (EventWriteRequest) -> EventWriteResult = {
            EventWriteResult.Inserted(it.eventId)
        },
    ) : EventSink, LocalNotificationStore {
        val requests = mutableListOf<EventWriteRequest>()
        var pending: PendingLocalNotification? = null
        var delivered = false

        override suspend fun persist(request: EventWriteRequest): EventWriteResult {
            requests += request
            val result = persistResult(request)
            if (result is EventWriteResult.Inserted) {
                pending = PendingLocalNotification(request.eventId, request.notificationText, 0)
            }
            return result
        }

        override suspend fun findPending(eventId: String): PendingLocalNotification? =
            pending?.takeIf { it.eventId == eventId }

        override suspend fun listPending(limit: Int): List<PendingLocalNotification> =
            listOfNotNull(pending).take(limit)

        override suspend fun recordAttempt(eventId: String): Boolean = pending?.eventId == eventId

        override suspend fun markDelivered(eventId: String, deliveredAtEpochMillis: Long): Boolean {
            if (pending?.eventId != eventId) return false
            delivered = true
            pending = null
            return true
        }
    }

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        const val EPISODE_ID = "01900000-0000-7000-8000-000000000001"
        const val EVENT_ID = "01900000-0000-7000-8000-000000000002"
        const val EXISTING_EVENT_ID = "01900000-0000-7000-8000-000000000003"
        const val EPOCH = 1_700_000_000_000L
    }
}
