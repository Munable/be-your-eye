package app.beyoureyes.core.data

import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitoringSessionTransition
import app.beyoureyes.core.domain.RestrictedEventPayload
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomEventRepositoryInstrumentedTest {
    private lateinit var database: MonitorDatabase
    private lateinit var dao: MonitoringDao
    private lateinit var repository: RoomEventRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.monitoringDao()
        repository = RoomEventRepository(database)
        seedProductTask()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun eventAndOutboxAreAtomicAndIdempotentByEventId() = runBlocking {
        val request = request(eventId = EVENT_1, episodeId = EPISODE_1)

        val inserted = repository.persist(request)
        val duplicate = repository.persist(request)

        assertEquals(EventWriteResult.Inserted(EVENT_1), inserted)
        assertEquals(EventWriteResult.Duplicate(EVENT_1), duplicate)

        assertEquals(1, dao.eventCount())
        assertEquals(1, dao.outboxCount())
        assertNotNull(dao.findTask(TASK_ID))
        assertNotNull(dao.findEvent(EVENT_1))
        assertNotNull(dao.findOutbox(EVENT_1))
    }

    @Test
    fun runtimeSnapshotIsDurableBeforeTheFirstEventAndConflictsFailClosed() = runBlocking {
        val snapshot = EventTaskSnapshot(TASK_ID, 1, "{\"runtime\":\"v3\"}")

        repository.persistTaskSnapshot(snapshot)

        assertEquals("{\"runtime\":\"v3\"}", dao.findTask(TASK_ID).runtimeSnapshotJson)
        assertEquals(0, dao.eventCount())
        assertEquals(0, dao.outboxCount())
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.persistTaskSnapshot(snapshot.copy(runtimeSnapshotJson = "{\"other\":true}"))
            }
        }
        Unit
    }

    @Test
    fun sameTaskRevisionAndEpisodeIsDuplicateEvenWithDifferentEventId() = runBlocking {
        assertEquals(
            EventWriteResult.Inserted(EVENT_1),
            repository.persist(request(eventId = EVENT_1, episodeId = EPISODE_7)),
        )
        assertEquals(
            EventWriteResult.Duplicate(EVENT_1),
            repository.persist(request(eventId = EVENT_2, episodeId = EPISODE_7)),
        )

        assertEquals(1, dao.eventCount())
        assertEquals(1, dao.outboxCount())
        assertNull(dao.findEvent(EVENT_2))
        assertNull(dao.findOutbox(EVENT_2))
    }

    @Test
    fun notificationPendingStateSurvivesDuplicateAndMarksDeliveredExplicitly() = runBlocking {
        val request = request(eventId = EVENT_1, episodeId = EPISODE_1)
        assertEquals(EventWriteResult.Inserted(EVENT_1), repository.persist(request))

        val pending = repository.findPending(EVENT_1)
        assertEquals(EVENT_1, pending?.eventId)
        assertEquals("门口有人", pending?.text)
        assertEquals(0, pending?.attemptCount)
        assertTrue(repository.recordAttempt(EVENT_1))
        assertEquals(1, repository.findPending(EVENT_1)?.attemptCount)

        assertEquals(EventWriteResult.Duplicate(EVENT_1), repository.persist(request))
        assertTrue(repository.markDelivered(EVENT_1, 1_700_000_000_100))
        assertNull(repository.findPending(EVENT_1))
        assertTrue(repository.listPending(100).isEmpty())
    }

    @Test
    fun deletingTaskCascadesToEventsAndOutbox() = runBlocking {
        repository.persist(request(eventId = EVENT_1, episodeId = EPISODE_1))
        repository.persist(request(eventId = EVENT_2, episodeId = EPISODE_2))

        assertEquals(1, dao.deleteTask(TASK_ID))

        assertNull(dao.findTask(TASK_ID))
        assertEquals(0, dao.eventCount())
        assertEquals(0, dao.outboxCount())
    }

    @Test
    fun delayedEventWriteAfterProductDeletionCannotRecreateTaskOrOutbox() = runBlocking {
        val delayed = request(eventId = EVENT_1, episodeId = EPISODE_1)
        repository.persistTaskSnapshot(delayed.task)

        assertTrue(dao.deleteProductTaskAndRuntime(TASK_ID))
        assertThrows(EventTaskNoLongerRunnableException::class.java) {
            runBlocking { repository.persist(delayed) }
        }

        assertNull(dao.findProductTask(TASK_ID))
        assertNull(dao.findTask(TASK_ID))
        assertEquals(0, dao.eventCount())
        assertEquals(0, dao.outboxCount())
    }

    @Test
    fun interruptedOpenAppearanceIsClosedExactlyOnceAtTheLastKnownActiveBoundary() = runBlocking {
        val appearedAt = 1_700_000_000_200
        repository.persist(
            request(
                eventId = EVENT_1,
                episodeId = EPISODE_1,
                occurredAtEpochMillis = appearedAt,
                payload = RestrictedEventPayload.ObjectEpisode(
                    targetId = "reference_target",
                    condition = ObjectEventCondition.APPEARED,
                    durationMillis = 3_000,
                    count = 1,
                ),
            ),
        )

        assertEquals(
            InterruptedMonitoringRecoveryResult.CLOSED_OPEN_EPISODE,
            repository.recoverInterruptedMonitoringStop(
                taskId = TASK_ID,
                lastKnownActiveAtEpochMillis = 1_700_000_000_100,
                eventsCreatedBeforeOrAtEpochMillis = 1_700_000_000_300,
                eventId = EVENT_2,
                episodeId = EPISODE_2,
            ),
        )

        val close = checkNotNull(dao.findEvent(EVENT_2))
        assertEquals(appearedAt, close.occurredAtEpochMillis)
        assertEquals(43, close.sourceSequence)
        assertEquals(close.occurredAtEpochMillis, close.notificationDeliveredAtEpochMillis)
        assertEquals(
            MonitorEventFact.StateTransition(
                MonitoringSessionTransition.REFERENCE_EPISODE_OPEN,
                MonitoringSessionTransition.MONITORING_STOPPED,
            ),
            MonitorEventFactJson.decode(close.payloadJson),
        )
        assertEquals(
            InterruptedMonitoringRecoveryResult.NO_OPEN_EPISODE,
            repository.recoverInterruptedMonitoringStop(
                taskId = TASK_ID,
                lastKnownActiveAtEpochMillis = 1_700_000_000_100,
                eventsCreatedBeforeOrAtEpochMillis = 1_700_000_000_300,
                eventId = EVENT_3,
                episodeId = EPISODE_3,
            ),
        )
        assertEquals(2, dao.eventCount())
        assertEquals(2, dao.outboxCount())
    }

    @Test
    fun interruptedRecoveryIsNoOpForClosedEpisodeAndDeletedTask() = runBlocking {
        repository.persist(
            request(
                eventId = EVENT_1,
                episodeId = EPISODE_1,
                payload = RestrictedEventPayload.ObjectEpisode(
                    targetId = "reference_target",
                    condition = ObjectEventCondition.APPEARED,
                    durationMillis = 3_000,
                    count = 1,
                ),
            ),
        )
        repository.persist(
            request(
                eventId = EVENT_2,
                episodeId = EPISODE_2,
                occurredAtEpochMillis = 1_700_000_000_100,
                payload = RestrictedEventPayload.ObjectEpisode(
                    targetId = "reference_target",
                    condition = ObjectEventCondition.DISAPPEARED,
                    durationMillis = 1_000,
                    count = 0,
                ),
            ),
        )

        assertEquals(
            InterruptedMonitoringRecoveryResult.NO_OPEN_EPISODE,
            repository.recoverInterruptedMonitoringStop(
                taskId = TASK_ID,
                lastKnownActiveAtEpochMillis = 1_700_000_000_100,
                eventsCreatedBeforeOrAtEpochMillis = 1_700_000_000_200,
                eventId = EVENT_3,
                episodeId = EPISODE_3,
            ),
        )
        assertEquals(2, dao.eventCount())

        assertTrue(dao.deleteProductTaskAndRuntime(TASK_ID))
        assertEquals(
            InterruptedMonitoringRecoveryResult.TASK_DELETED,
            repository.recoverInterruptedMonitoringStop(
                taskId = TASK_ID,
                lastKnownActiveAtEpochMillis = 1_700_000_000_100,
                eventsCreatedBeforeOrAtEpochMillis = 1_700_000_000_200,
                eventId = EVENT_4,
                episodeId = EPISODE_4,
            ),
        )
        assertNull(dao.findTask(TASK_ID))
        assertEquals(0, dao.eventCount())
        assertEquals(0, dao.outboxCount())
    }

    @Test
    fun snapshotReconcileDeletesOrphanAndKeepsMatchedLocalEvent() = runBlocking {
        repository.persist(request(eventId = EVENT_1, episodeId = EPISODE_1))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filesDir = File(context.cacheDir, "snapshot-reconcile-${System.nanoTime()}")
        try {
            val snapshots = ReferenceEventSnapshotStore.openAppPrivate(filesDir)
            val taskDirectory = filesDir.resolve("reference-event-snapshots-v1/$TASK_ID")
            assertTrue(taskDirectory.mkdirs())
            val matched = taskDirectory.resolve("$EVENT_1.jpg").apply { writeBytes(byteArrayOf(1)) }
            val orphan = taskDirectory.resolve("$EVENT_2.jpg").apply { writeBytes(byteArrayOf(2)) }

            val result = repository.reconcileReferenceEventSnapshots(
                store = snapshots,
                createdBeforeOrAtEpochMillis = System.currentTimeMillis() + 1_000,
                maximumFiles = 10,
            )

            assertEquals(2, result.inspectedFiles)
            assertEquals(1, result.deletedFiles)
            assertFalse(result.reachedLimit)
            assertTrue(matched.isFile)
            assertFalse(orphan.exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun eventPayloadIsRestrictedToContractFieldsAndEscaped() = runBlocking {
        repository.persist(
            request(
                eventId = EVENT_1,
                episodeId = EPISODE_1,
                payload = RestrictedEventPayload.ObjectEpisode(
                    targetId = "person\"\nmedia",
                    condition = ObjectEventCondition.APPEARED,
                    durationMillis = 3_000,
                    count = 1,
                ),
            ),
        )

        val payload = JSONObject(dao.findEvent(EVENT_1).payloadJson)
        assertEquals(
            setOf("type", "target_id", "condition", "duration_ms", "count"),
            payload.keys().asSequence().toSet(),
        )
        assertEquals("person\"\nmedia", payload.getString("target_id"))
        assertFalse(payload.has("image"))
        assertFalse(payload.has("video"))
        assertFalse(payload.has("media"))
    }

    private fun request(
        eventId: String,
        episodeId: String,
        occurredAtEpochMillis: Long = 1_700_000_000_000,
        payload: RestrictedEventPayload = RestrictedEventPayload.ObjectEpisode(
            targetId = "person",
            condition = ObjectEventCondition.APPEARED,
            durationMillis = 3_000,
            count = 1,
        ),
    ) = EventWriteRequest(
        eventId = eventId,
        task = EventTaskSnapshot(
            taskId = TASK_ID,
            revision = 1,
            runtimeSnapshotJson = "{\"schema_version\":\"1.0\"}",
        ),
        episodeId = episodeId,
        sourceSequence = 42,
        occurredAtEpochMillis = occurredAtEpochMillis,
        payload = payload,
        notificationText = "门口有人",
    )

    private fun seedProductTask() {
        assertTrue(
            dao.insertProductTask(
                LocalTaskEntity(
                    TASK_ID,
                    1,
                    "门口监控",
                    "reference_images",
                    "{}",
                    "{}",
                    "[]",
                    1_700_000_000_000,
                    null,
                    null,
                    null,
                ),
            ) != -1L,
        )
    }

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        const val EVENT_1 = "01900000-0000-7000-8000-000000000001"
        const val EVENT_2 = "01900000-0000-7000-8000-000000000002"
        const val EVENT_3 = "01900000-0000-7000-8000-000000000003"
        const val EVENT_4 = "01900000-0000-7000-8000-000000000004"
        const val EPISODE_1 = "01900000-0000-7000-8000-000000000101"
        const val EPISODE_2 = "01900000-0000-7000-8000-000000000102"
        const val EPISODE_3 = "01900000-0000-7000-8000-000000000103"
        const val EPISODE_4 = "01900000-0000-7000-8000-000000000104"
        const val EPISODE_7 = "01900000-0000-7000-8000-000000000107"
    }
}
