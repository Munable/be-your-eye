package app.beyoureyes.monitor

import app.beyoureyes.core.data.EventSink
import app.beyoureyes.core.data.EventTaskSnapshot
import app.beyoureyes.core.data.EventWriteRequest
import app.beyoureyes.core.data.EventWriteResult
import app.beyoureyes.core.data.LocalNotificationStore
import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.data.PendingLocalNotification
import app.beyoureyes.core.domain.RestrictedEventPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventNotificationCoordinatorTest {
    @Test
    fun `insert publishes once and a delivered duplicate stays silent`() = runBlocking {
        val store = FakeNotificationStore(pending())
        val results = ArrayDeque(
            listOf(
                EventWriteResult.Inserted(EVENT_ID),
                EventWriteResult.Duplicate(EVENT_ID),
            ),
        )
        val published = mutableListOf<Pair<String, String>>()
        var retrySchedules = 0
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { results.removeFirst() },
            notificationStore = store,
            contextResolver = fixedContextResolver(),
            publisher = EventNotificationPublisher { eventId, _, text -> published += eventId to text },
            retryScheduler = PendingNotificationRetryScheduler { retrySchedules++ },
            epochMillis = { 1_700_000_000_100 },
        )

        assertEquals(EventWriteResult.Inserted(EVENT_ID), coordinator.persistThenNotify(request()))
        assertEquals(EventWriteResult.Duplicate(EVENT_ID), coordinator.persistThenNotify(request()))
        assertEquals(listOf(EVENT_ID to "门口有人"), published)
        assertEquals(listOf(EVENT_ID), store.attempted)
        assertEquals(listOf(EVENT_ID to 1_700_000_000_100), store.delivered)
        assertEquals(0, retrySchedules)
    }

    @Test
    fun `silent lifecycle event is durable and marked delivered without publishing`() = runBlocking {
        val store = FakeNotificationStore(pending())
        val published = mutableListOf<String>()
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { EventWriteResult.Inserted(EVENT_ID) },
            notificationStore = store,
            contextResolver = fixedContextResolver(),
            publisher = EventNotificationPublisher { eventId, _, _ -> published += eventId },
            epochMillis = { 1_700_000_000_200 },
        )

        assertEquals(EventWriteResult.Inserted(EVENT_ID), coordinator.persistSilently(request()))
        assertTrue(published.isEmpty())
        assertTrue(store.attempted.isEmpty())
        assertEquals(listOf(EVENT_ID to 1_700_000_000_200), store.delivered)
        assertEquals(null, store.findPending(EVENT_ID))
    }

    @Test
    fun `publisher failure stays pending without failing the monitoring frame`() = runBlocking {
        val store = FakeNotificationStore(pending())
        var publishCalls = 0
        val diagnostics = mutableListOf<Pair<String, String>>()
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { EventWriteResult.Duplicate(EVENT_ID) },
            notificationStore = store,
            contextResolver = fixedContextResolver(),
            publisher = EventNotificationPublisher { _, _, _ ->
                publishCalls++
                if (publishCalls == 1) error("notification manager unavailable")
            },
            diagnosticSink = NotificationDeliveryDiagnosticSink { eventId, failure ->
                diagnostics += eventId to checkNotNull(failure.message)
            },
            epochMillis = { 1_700_000_000_100 },
        )

        assertEquals(EventWriteResult.Duplicate(EVENT_ID), coordinator.persistThenNotify(request()))
        assertEquals(EVENT_ID, store.findPending(EVENT_ID)?.eventId)
        assertEquals(listOf(EVENT_ID to "notification manager unavailable"), diagnostics)

        assertEquals(EventWriteResult.Duplicate(EVENT_ID), coordinator.persistThenNotify(request()))
        assertEquals(2, publishCalls)
        assertEquals(null, store.findPending(EVENT_ID))
    }

    @Test
    fun `structured cancellation is never swallowed`() = runBlocking {
        val store = FakeNotificationStore(pending())
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { EventWriteResult.Inserted(EVENT_ID) },
            notificationStore = store,
            contextResolver = fixedContextResolver(),
            publisher = EventNotificationPublisher { _, _, _ ->
                throw CancellationException("service stopping")
            },
        )

        val failure = runCatching { coordinator.persistThenNotify(request()) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(EVENT_ID, store.findPending(EVENT_ID)?.eventId)
    }

    @Test
    fun `recovery scans durable pending rows without inserting another event`() = runBlocking {
        val otherId = "01900000-0000-7000-8000-000000000003"
        val otherTaskId = "01900000-0000-7000-8000-000000000004"
        val store = FakeNotificationStore(pending(), pending(otherId, "车位有车"))
        val published = mutableListOf<Pair<String, String>>()
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { error("recovery must not write an event") },
            notificationStore = store,
            contextResolver = EventNotificationContextResolver { eventId ->
                EventNotificationContext(
                    taskId = if (eventId == otherId) otherTaskId else TASK_ID,
                    occurredAtEpochMillis = 1_700_000_000_000,
                )
            },
            publisher = EventNotificationPublisher { eventId, context, _ ->
                published += eventId to context.taskId
            },
            epochMillis = { 1_700_000_000_100 },
        )

        assertEquals(2, coordinator.recoverPending())
        assertEquals(listOf(EVENT_ID to TASK_ID, otherId to otherTaskId), published)
        assertTrue(store.listPending(100).isEmpty())
    }

    @Test
    fun `persistence failure cannot publish`() = runBlocking {
        val published = mutableListOf<String>()
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { error("database unavailable") },
            notificationStore = FakeNotificationStore(),
            contextResolver = fixedContextResolver(),
            publisher = EventNotificationPublisher { eventId, _, _ -> published += eventId },
        )

        val failed = runCatching { coordinator.persistThenNotify(request()) }.exceptionOrNull()
        assertTrue(failed is IllegalStateException)
        assertEquals(emptyList<String>(), published)
    }

    @Test
    fun `permission denial keeps the durable event pending without failing persistence`() = runBlocking {
        val store = FakeNotificationStore(pending())
        var retrySchedules = 0
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { EventWriteResult.Inserted(EVENT_ID) },
            notificationStore = store,
            contextResolver = fixedContextResolver(),
            publisher = EventNotificationPublisher { _, _, _ ->
                throw NotificationDeliveryDeferredException("permission denied")
            },
            retryScheduler = PendingNotificationRetryScheduler { retrySchedules++ },
        )

        assertEquals(EventWriteResult.Inserted(EVENT_ID), coordinator.persistThenNotify(request()))
        assertEquals(EVENT_ID, store.findPending(EVENT_ID)?.eventId)
        assertEquals(listOf(EVENT_ID), store.attempted)
        assertTrue(store.delivered.isEmpty())
        assertEquals(1, retrySchedules)
    }

    @Test
    fun `recovery without durable task context stays pending and does not misroute`() = runBlocking {
        val store = FakeNotificationStore(pending())
        val published = mutableListOf<String>()
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { error("recovery must not write an event") },
            notificationStore = store,
            contextResolver = EventNotificationContextResolver { null },
            publisher = EventNotificationPublisher { eventId, _, _ -> published += eventId },
        )

        assertEquals(0, coordinator.recoverPending())
        assertEquals(EVENT_ID, store.findPending(EVENT_ID)?.eventId)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `retry scheduler defect cannot fail a durable event`() = runBlocking {
        val store = FakeNotificationStore(pending())
        val diagnostics = mutableListOf<String>()
        val coordinator = EventNotificationCoordinator(
            eventSink = EventSink { EventWriteResult.Inserted(EVENT_ID) },
            notificationStore = store,
            contextResolver = fixedContextResolver(),
            publisher = EventNotificationPublisher { _, _, _ ->
                throw NotificationDeliveryDeferredException("permission denied")
            },
            retryScheduler = PendingNotificationRetryScheduler {
                error("WorkManager unavailable")
            },
            diagnosticSink = NotificationDeliveryDiagnosticSink { _, failure ->
                diagnostics += checkNotNull(failure.message)
            },
        )

        assertEquals(EventWriteResult.Inserted(EVENT_ID), coordinator.persistThenNotify(request()))
        assertEquals(EVENT_ID, store.findPending(EVENT_ID)?.eventId)
        assertEquals(listOf("WorkManager unavailable"), diagnostics)
    }

    private class FakeNotificationStore(
        vararg initial: PendingLocalNotification,
    ) : LocalNotificationStore {
        private val pending = linkedMapOf(*initial.map { it.eventId to it }.toTypedArray())
        val attempted = mutableListOf<String>()
        val delivered = mutableListOf<Pair<String, Long>>()

        override suspend fun findPending(eventId: String): PendingLocalNotification? = pending[eventId]

        override suspend fun listPending(limit: Int): List<PendingLocalNotification> =
            pending.values.take(limit)

        override suspend fun recordAttempt(eventId: String): Boolean {
            val current = pending[eventId] ?: return false
            attempted += eventId
            pending[eventId] = current.copy(attemptCount = current.attemptCount + 1)
            return true
        }

        override suspend fun markDelivered(eventId: String, deliveredAtEpochMillis: Long): Boolean {
            if (pending.remove(eventId) == null) return false
            delivered += eventId to deliveredAtEpochMillis
            return true
        }
    }

    private fun pending(
        eventId: String = EVENT_ID,
        text: String = "门口有人",
    ) = PendingLocalNotification(eventId, text, attemptCount = 0)

    private fun fixedContextResolver() = EventNotificationContextResolver {
        EventNotificationContext(
            taskId = TASK_ID,
            occurredAtEpochMillis = 1_700_000_000_000,
        )
    }

    private fun request() = EventWriteRequest(
        eventId = EVENT_ID,
        task = EventTaskSnapshot(
            taskId = TASK_ID,
            revision = 1,
            runtimeSnapshotJson = "{}",
        ),
        episodeId = "01900000-0000-7000-8000-000000000002",
        sourceSequence = 7,
        occurredAtEpochMillis = 1_700_000_000_000,
        payload = RestrictedEventPayload.ObjectEpisode(
            targetId = "person",
            condition = ObjectEventCondition.APPEARED,
            durationMillis = 3_000,
            count = 1,
        ),
        notificationText = "门口有人",
    )

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        const val EVENT_ID = "01900000-0000-7000-8000-000000000001"
    }
}
