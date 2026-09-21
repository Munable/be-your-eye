package app.beyoureyes.core.data.cloud

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.data.EventTaskSnapshot
import app.beyoureyes.core.data.EventEntity
import app.beyoureyes.core.data.EventWriteRequest
import app.beyoureyes.core.data.EventWriteResult
import app.beyoureyes.core.data.LocalTaskEntity
import app.beyoureyes.core.data.MonitorDatabase
import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.data.OutboxEntity
import app.beyoureyes.core.data.RemoteEventSnapshotCache
import app.beyoureyes.core.data.ResolvedSamplingConfigEntity
import app.beyoureyes.core.domain.RestrictedEventPayload
import app.beyoureyes.core.data.RoomEventRepository
import app.beyoureyes.core.data.RoomMonitorStore
import app.beyoureyes.core.data.TaskEntity
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCloudBridgeInstrumentedTest {
    private lateinit var database: MonitorDatabase
    private lateinit var bridge: RoomCloudBridge

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bridge = RoomCloudBridge(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun localTaskAndOutboxBecomePrivacyBoundedCloudWrites() = runBlocking {
        database.monitoringDao().insertProductTask(
            LocalTaskEntity(
                TASK_ID,
                2,
                "门口的人",
                "reference_images",
                "{\"type\":\"reference_images\"}",
                "{\"type\":\"presence_duration\",\"condition\":\"appears\",\"duration_ms\":1000}",
                "[]",
                1_700_000_000_000,
                PACKAGE_ID,
                PACKAGE_VERSION,
                MANIFEST_SHA256,
            ),
        )
        insertSampling(revision = 2, intervalMillis = 500)
        val eventRepository = RoomEventRepository(database)
        assertEquals(
            EventWriteResult.Inserted(EVENT_ID),
            eventRepository.persist(
                EventWriteRequest(
                    eventId = EVENT_ID,
                    task = EventTaskSnapshot(TASK_ID, 2, "{\"runtime\":true}"),
                    episodeId = EPISODE_ID,
                    sourceSequence = 7,
                    occurredAtEpochMillis = 1_700_000_000_100,
                    payload = RestrictedEventPayload.ObjectEpisode(
                        targetId = "person",
                        condition = ObjectEventCondition.APPEARED,
                        durationMillis = 3_000,
                        count = 1,
                    ),
                    notificationText = "门口有人",
                ),
            ),
        )

        val task = bridge.taskWrites(DEVICE_ID).single()
        val event = bridge.readyEvents(1_700_000_000_100, 100).single()

        assertEquals(TASK_ID, task.taskId)
        assertEquals(2, task.revision)
        assertEquals("visual_target", task.capabilityId)
        assertEquals(DEVICE_ID, task.monitoringDeviceId)
        assertEquals("reference_images", task.config.getValue("target_definition").jsonObject["mode"]?.jsonPrimitive?.content)
        assertPresenceEpisodeRule(
            task.config.getValue("rule").jsonObject,
            expectedDurationMillis = 1_000,
            expectedMaximumGapMillis = 1_500,
        )
        val visualRoi = task.config.getValue("roi").jsonObject
        assertEquals(0.0, visualRoi.getValue("left").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1.0, visualRoi.getValue("bottom").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(EVENT_ID, event.eventId)
        assertFalse(event.payload.containsKey("image"))

        bridge.markEventsUploaded(setOf(EVENT_ID))
        assertNull(database.monitoringDao().findOutbox(EVENT_ID))
        assertNotNull(database.monitoringDao().findEvent(EVENT_ID))
    }

    @Test
    fun objectTaskProjectsTaskBoundPresenceEpisodeContract() = runBlocking {
        insertObjectTask()
        insertSampling(revision = 1, intervalMillis = 400)

        val task = bridge.taskWrites(DEVICE_ID).single()
        val target = task.config.getValue("target_definition").jsonObject

        assertEquals("visual_target", task.capabilityId)
        assertEquals("2026.08.24.1", task.catalogVersion)
        assertEquals("找猫", task.title)
        assertEquals(
            setOf("mode", "target_id", "label_zh_cn", "label_en"),
            target.keys,
        )
        assertEquals("object_detection", target.getValue("mode").jsonPrimitive.content)
        assertEquals("cat", target.getValue("target_id").jsonPrimitive.content)
        assertEquals(
            "common_objects_tensorflow_efficientdet_lite2",
            task.config.getValue("route_binding").jsonObject
                .getValue("model_profile_key").jsonPrimitive.content,
        )
        assertEquals(
            PACKAGE_ID,
            task.config.getValue("package_binding").jsonObject
                .getValue("package_id").jsonPrimitive.content,
        )
        assertPresenceEpisodeRule(
            task.config.getValue("rule").jsonObject,
            expectedDurationMillis = 1_000,
            expectedMaximumGapMillis = 1_200,
        )
    }

    @Test
    fun absenceRuleProjectsExactDurationAndEvidenceWithoutSyncFailure() = runBlocking {
        insertObjectTask(
            ruleType = "absence_duration",
            condition = "disappears",
            durationMillis = 3_000,
        )
        insertSampling(revision = 1, intervalMillis = 500)

        val rule = bridge.taskWrites(DEVICE_ID).single().config.getValue("rule").jsonObject

        assertEquals("absence_duration", rule.getValue("type").jsonPrimitive.content)
        assertEquals("disappears", rule.getValue("condition").jsonPrimitive.content)
        assertEquals(3_000L, rule.getValue("duration_ms").jsonPrimitive.content.toLong())
        assertEquals(7L, rule.getValue("min_negative_count").jsonPrimitive.content.toLong())
        assertEquals(1_000L, rule.getValue("max_observation_gap_ms").jsonPrimitive.content.toLong())
        assertEquals(3_000L, rule.getValue("rearm_presence_ms").jsonPrimitive.content.toLong())
    }

    @Test
    fun remainsRuleProjectsExactDurationAndEvidenceWithoutSyncFailure() = runBlocking {
        insertObjectTask(condition = "remains", durationMillis = 5_000)
        insertSampling(revision = 1, intervalMillis = 500)

        val rule = bridge.taskWrites(DEVICE_ID).single().config.getValue("rule").jsonObject

        assertEquals("presence_duration", rule.getValue("type").jsonPrimitive.content)
        assertEquals("remains", rule.getValue("condition").jsonPrimitive.content)
        assertEquals(5_000L, rule.getValue("duration_ms").jsonPrimitive.content.toLong())
        assertEquals(11L, rule.getValue("min_positive_count").jsonPrimitive.content.toLong())
        assertEquals(1_000L, rule.getValue("max_positive_gap_ms").jsonPrimitive.content.toLong())
        assertEquals(5_000L, rule.getValue("rearm_absence_ms").jsonPrimitive.content.toLong())
    }

    @Test
    fun sameRevisionSamplingAdjustmentRefreshesDerivedCloudEvidence() = runBlocking {
        insertObjectTask()
        insertSampling(revision = 1, intervalMillis = 400)
        val store = RoomMonitorStore(database)
        val before = bridge.taskWrites(DEVICE_ID).single()
        val expected = checkNotNull(store.findResolvedSamplingConfig(TASK_ID))

        assertTrue(
            store.updateResolvedSamplingConfig(
                expected = expected,
                replacement = expected.copy(intervalMillis = 500),
            ),
        )

        val after = bridge.taskWrites(DEVICE_ID).single()
        assertEquals(before.copy(config = after.config), after)
        assertPresenceEpisodeRule(
            before.config.getValue("rule").jsonObject,
            expectedDurationMillis = 1_000,
            expectedMaximumGapMillis = 1_200,
        )
        assertPresenceEpisodeRule(
            after.config.getValue("rule").jsonObject,
            expectedDurationMillis = 1_000,
            expectedMaximumGapMillis = 1_500,
        )
        assertEquals(500L, store.findResolvedSamplingConfig(TASK_ID)?.intervalMillis)
    }

    @Test
    fun missingOrMismatchedSamplingFailsCloudProjectionClosed() {
        insertObjectTask()

        val missing = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { bridge.taskWrites(DEVICE_ID) }
        }
        assertTrue(missing.message.orEmpty().contains("valid persisted sampling"))

        insertSampling(
            revision = 1,
            intervalMillis = 500,
            packageId = "mismatched_runtime",
        )
        val mismatched = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { bridge.taskWrites(DEVICE_ID) }
        }
        assertTrue(mismatched.message.orEmpty().contains("projected task package"))
    }

    @Test
    fun peerEventIsCachedOnceWithoutCreatingAnUploadLoop() = runBlocking {
        assertTrue(
            bridge.cacheRemoteTaskSummary(
                ACCOUNT_ID,
                CloudTaskSummary(
                    schemaVersion = "4.0",
                    taskId = REMOTE_TASK_ID,
                    revision = 2,
                    capabilityId = "visual_target",
                    title = "门口的人",
                    targetDefinition = buildJsonObject { put("mode", "object_detection") },
                    monitoringDeviceId = DEVICE_ID,
                ),
            ),
        )
        val remote = CloudEventRow(
            eventId = REMOTE_EVENT_ID,
            taskId = REMOTE_TASK_ID,
            taskRevision = 2,
            episodeId = REMOTE_EPISODE_ID,
            sourceSequence = 9,
            occurredAt = "2026-08-03T00:00:00Z",
            monitoringDeviceId = DEVICE_ID,
            payload = buildJsonObject {
                put("type", "object_episode")
                put("target_id", "person")
                put("condition", "appeared")
                put("duration_ms", 3_000)
                put("count", 1)
            },
        )

        assertTrue(bridge.cacheRemoteEvent(ACCOUNT_ID, remote))
        assertFalse(bridge.cacheRemoteEvent(ACCOUNT_ID, remote))

        assertEquals(1, database.monitoringDao().eventCount())
        assertEquals(0, database.monitoringDao().outboxCount())
        assertEquals("person 已出现", database.monitoringDao().findEvent(REMOTE_EVENT_ID).notificationText)
        val cachedTask = database.monitoringDao().findTask(REMOTE_TASK_ID)
        assertNotNull(cachedTask)
        assertFalse(cachedTask.isActive)
        assertTrue(cachedTask.runtimeSnapshotJson.contains("\"title\":\"门口的人\""))
        assertTrue(cachedTask.runtimeSnapshotJson.contains("\"capability_id\":\"visual_target\""))
        val productStore = RoomMonitorStore(database)
        assertTrue(productStore.tasks.first().isEmpty())
        val remoteTask = productStore.remoteTasks.first { it.isNotEmpty() }.single()
        assertEquals(REMOTE_TASK_ID, remoteTask.taskId)
        assertEquals(2, remoteTask.revision)
        assertEquals("门口的人", remoteTask.title)
        assertEquals("visual_target", remoteTask.capabilityId)
        assertEquals(ACCOUNT_ID, remoteTask.accountId)
        val timelineEvent = productStore.events.first { it.isNotEmpty() }.single()
        assertTrue(timelineEvent.isRemoteTask)
        assertEquals("门口的人", timelineEvent.remoteTaskTitle)
        assertEquals("visual_target", timelineEvent.remoteCapabilityId)
        assertEquals(ACCOUNT_ID, timelineEvent.remoteAccountId)
        assertTrue(bridge.isRemoteNotificationPending(ACCOUNT_ID, REMOTE_EVENT_ID))
        assertFalse(bridge.isRemoteNotificationPending(OTHER_ACCOUNT_ID, REMOTE_EVENT_ID))
        assertEquals(listOf(remote), bridge.pendingRemoteNotifications(ACCOUNT_ID, 100))
        assertTrue(bridge.pendingRemoteNotifications(OTHER_ACCOUNT_ID, 100).isEmpty())
        assertEquals(0, database.monitoringDao().pendingLocalNotifications(100).size)
        assertFalse(
            bridge.markRemoteNotificationHandled(
                OTHER_ACCOUNT_ID,
                REMOTE_EVENT_ID,
                1_700_000_000_001,
            ),
        )
        assertTrue(
            bridge.markRemoteNotificationHandled(
                ACCOUNT_ID,
                REMOTE_EVENT_ID,
                1_700_000_000_001,
            ),
        )
        assertFalse(bridge.isRemoteNotificationPending(ACCOUNT_ID, REMOTE_EVENT_ID))
        assertTrue(bridge.pendingRemoteNotifications(ACCOUNT_ID, 100).isEmpty())

        assertFalse(bridge.removeCachedRemoteTask(OTHER_ACCOUNT_ID, REMOTE_TASK_ID))
        assertTrue(bridge.removeCachedRemoteTask(ACCOUNT_ID, REMOTE_TASK_ID))
        assertEquals(0, database.monitoringDao().eventCount())
        assertNull(database.monitoringDao().findTask(REMOTE_TASK_ID))
        assertTrue(productStore.remoteTasks.first().isEmpty())
    }

    @Test
    fun peerEventTombstoneDeletesOnlyTheMatchingAccountsCachedEvent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(context.cacheDir, "remote-tombstone-${System.nanoTime()}")
        val cache = RemoteEventSnapshotCache.openAppPrivate(isolatedFiles)
        val cacheAwareBridge = RoomCloudBridge(database, remoteSnapshotCache = cache)
        val remote = CloudEventRow(
            eventId = REMOTE_EVENT_ID,
            taskId = REMOTE_TASK_ID,
            taskRevision = 2,
            episodeId = REMOTE_EPISODE_ID,
            sourceSequence = 9,
            occurredAt = "2026-08-03T00:00:00Z",
            monitoringDeviceId = DEVICE_ID,
            payload = buildJsonObject {
                put("type", "object_episode")
                put("target_id", "person")
                put("condition", "appeared")
                put("duration_ms", 3_000)
                put("count", 1)
            },
        )
        assertTrue(cacheAwareBridge.cacheRemoteEvent(ACCOUNT_ID, remote))
        val jpeg = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output))
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        val cachedFile = File(requireNotNull(
            Uri.parse(cache.store(ACCOUNT_ID, DEVICE_ID, REMOTE_EVENT_ID, jpeg)).path,
        ))
        assertTrue(cachedFile.isFile)

        assertFalse(cacheAwareBridge.removeCachedRemoteEvent(OTHER_ACCOUNT_ID, REMOTE_EVENT_ID))
        assertNotNull(database.monitoringDao().findEvent(REMOTE_EVENT_ID))
        assertTrue(cacheAwareBridge.removeCachedRemoteEvent(ACCOUNT_ID, REMOTE_EVENT_ID))

        assertNull(database.monitoringDao().findEvent(REMOTE_EVENT_ID))
        assertNotNull(database.monitoringDao().findTask(REMOTE_TASK_ID))
        assertEquals(0, database.monitoringDao().outboxCount())
        assertFalse(cachedFile.exists())
        assertTrue(isolatedFiles.deleteRecursively())
    }

    @Test
    fun peerEventTombstoneNeverDeletesALocalProductEvent() = runBlocking {
        val dao = database.monitoringDao()
        dao.insertProductTask(
            LocalTaskEntity(
                TASK_ID,
                1,
                "本机监控",
                "reference_images",
                "{\"type\":\"reference_images\"}",
                "{\"type\":\"presence_duration\",\"condition\":\"appears\",\"duration_ms\":1000}",
                "[]",
                1_700_000_000_000,
                null,
                null,
                null,
            ),
        )
        dao.upsertTask(TaskEntity(TASK_ID, 1, "{\"runtime\":true}", false))
        dao.insertEvent(
            EventEntity(
                EVENT_ID,
                TASK_ID,
                1,
                EPISODE_ID,
                1,
                1_700_000_000_100,
                "{\"type\":\"object_episode\"}",
                "本机事件",
                0,
                null,
            ),
        )

        assertFalse(bridge.removeCachedRemoteEvent(ACCOUNT_ID, EVENT_ID))
        assertNotNull(dao.findEvent(EVENT_ID))
    }

    @Test
    fun peerSummaryRejectsStaleRevisionAndLocalTaskWithSameIdentityWins() = runBlocking {
        assertTrue(bridge.cacheRemoteTaskSummary(
            ACCOUNT_ID,
            CloudTaskSummary(
                schemaVersion = "4.0",
                taskId = REMOTE_TASK_ID,
                revision = 3,
                capabilityId = "visible_state",
                title = "远端门状态",
                targetDefinition = buildJsonObject { put("mode", "reference_images") },
                monitoringDeviceId = DEVICE_ID,
            ),
        ))
        assertFalse(bridge.cacheRemoteTaskSummary(
            ACCOUNT_ID,
            CloudTaskSummary(
                schemaVersion = "4.0",
                taskId = REMOTE_TASK_ID,
                revision = 2,
                capabilityId = "visual_target",
                title = "旧摘要",
                targetDefinition = buildJsonObject { put("mode", "reference_images") },
                monitoringDeviceId = DEVICE_ID,
            ),
        ))
        val productStore = RoomMonitorStore(database)
        val peer = productStore.remoteTasks.first { it.isNotEmpty() }.single()
        assertEquals(3, peer.revision)
        assertEquals("远端门状态", peer.title)
        assertEquals("visible_state", peer.capabilityId)

        database.monitoringDao().insertProductTask(
            LocalTaskEntity(
                REMOTE_TASK_ID,
                1,
                "本机同标识监控",
                "reference_images",
                "{\"type\":\"reference_images\"}",
                "{\"type\":\"presence_duration\",\"condition\":\"appears\",\"duration_ms\":1000}",
                "[]",
                1_700_000_000_000,
                null,
                null,
                null,
            ),
        )

        assertTrue(productStore.remoteTasks.first().isEmpty())
        assertEquals("本机同标识监控", productStore.tasks.first { it.isNotEmpty() }.single().title)
        assertFalse(bridge.cacheRemoteTaskSummary(
            ACCOUNT_ID,
            CloudTaskSummary(
                schemaVersion = "4.0",
                taskId = REMOTE_TASK_ID,
                revision = 4,
                capabilityId = "visible_state",
                title = "不应覆盖本机",
                targetDefinition = buildJsonObject { put("mode", "reference_images") },
                monitoringDeviceId = DEVICE_ID,
            ),
        ))
        assertFalse(bridge.removeCachedRemoteTask(ACCOUNT_ID, REMOTE_TASK_ID))
        assertNotNull(database.monitoringDao().findTask(REMOTE_TASK_ID))
    }

    @Test
    fun accountExitClearsOnlyRemoteCacheAndPreservesLocalTaskEventAndOutbox() = runBlocking {
        val dao = database.monitoringDao()
        dao.insertProductTask(
            LocalTaskEntity(
                TASK_ID,
                1,
                "本机监控",
                "reference_images",
                "{\"type\":\"reference_images\"}",
                "{\"type\":\"presence_duration\",\"condition\":\"appears\",\"duration_ms\":1000}",
                "[]",
                1_700_000_000_000,
                null,
                null,
                null,
            ),
        )
        dao.upsertTask(TaskEntity(TASK_ID, 1, "{\"runtime\":true}", false))
        assertTrue(
            dao.insertEventAndOutbox(
                EventEntity(
                    EVENT_ID,
                    TASK_ID,
                    1,
                    EPISODE_ID,
                    1,
                    1_700_000_000_100,
                    "{\"type\":\"object_episode\"}",
                    "本机事件",
                    0,
                    null,
                ),
                OutboxEntity(EVENT_ID, 0, 1_700_000_000_100),
            ),
        )
        assertTrue(
            bridge.cacheRemoteTaskSummary(
                ACCOUNT_ID,
                CloudTaskSummary(
                    "4.0",
                    REMOTE_TASK_ID,
                    2,
                    "visible_state",
                    "其他手机监控",
                    buildJsonObject { put("mode", "reference_images") },
                    DEVICE_ID,
                ),
            ),
        )
        assertTrue(
            bridge.cacheRemoteEvent(
                ACCOUNT_ID,
                CloudEventRow(
                    REMOTE_EVENT_ID,
                    REMOTE_TASK_ID,
                    2,
                    REMOTE_EPISODE_ID,
                    2,
                    "2026-08-03T00:00:00Z",
                    DEVICE_ID,
                    buildJsonObject { put("type", "state_transition") },
                ),
            ),
        )

        assertEquals(1, bridge.clearRemoteCache())

        assertNotNull(dao.findProductTask(TASK_ID))
        assertNotNull(dao.findTask(TASK_ID))
        assertNotNull(dao.findEvent(EVENT_ID))
        assertNotNull(dao.findOutbox(EVENT_ID))
        assertNull(dao.findTask(REMOTE_TASK_ID))
        assertNull(dao.findEvent(REMOTE_EVENT_ID))
        assertEquals(1, dao.eventCount())
        assertEquals(1, dao.outboxCount())
    }

    @Test
    fun readingTaskProjectsItsManualRoiAndConfirmedFormatWithoutLocalOnlyRuleMetadata() = runBlocking {
        database.monitoringDao().insertProductTask(
            LocalTaskEntity(
                TASK_ID,
                1,
                "压力",
                "structured_reading",
                "{\"type\":\"structured_reading\",\"manual_roi\":{" +
                    "\"left\":0.08,\"top\":0.12,\"right\":0.92,\"bottom\":0.67}," +
                    "\"confirmed_format\":{\"profile_id\":\"confirmed_reading_format_v2\"," +
                    "\"kind\":\"decimal\",\"fractional_digits\":1," +
                    "\"time_segments\":null,\"unit\":null}}",
                "{\"type\":\"reading_threshold\",\"operator\":\"gte\"," +
                    "\"setup_complete\":true," +
                    "\"threshold_decimal\":\"10\"," +
                    "\"duration_ms\":3000," +
                    "\"hysteresis_decimal\":\"0\",\"cooldown_ms\":0," +
                    "\"source_kind\":\"digital_display\"}",
                "[]",
                1_700_000_000_000,
                PACKAGE_ID,
                PACKAGE_VERSION,
                MANIFEST_SHA256,
            ),
        )
        insertSampling(revision = 1, intervalMillis = 500)

        val task = bridge.taskWrites(DEVICE_ID).single()
        val roi = task.config.getValue("roi").jsonObject
        val rule = task.config.getValue("rule").jsonObject
        val targetDefinition = task.config.getValue("target_definition").jsonObject

        assertEquals("structured_reading", task.capabilityId)
        assertEquals(setOf("mode", "confirmed_format"), targetDefinition.keys)
        assertEquals("none", targetDefinition.getValue("mode").jsonPrimitive.content)
        assertEquals(
            "confirmed_reading_format_v2",
            targetDefinition.getValue("confirmed_format").jsonObject
                .getValue("profile_id").jsonPrimitive.content,
        )
        assertEquals(0.08, roi.getValue("left").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(0.67, roi.getValue("bottom").jsonPrimitive.content.toDouble(), 0.0)
        assertFalse(rule.containsKey("source_kind"))
        assertFalse(rule.containsKey("setup_complete"))
        assertEquals(3000, rule.getValue("duration_ms").jsonPrimitive.content.toInt())
    }

    @Test
    fun unfinishedReadingSetupStaysLocalUntilTheNotificationRuleIsSaved() = runBlocking {
        database.monitoringDao().insertProductTask(
            LocalTaskEntity(
                TASK_ID,
                1,
                "待确认压力",
                "structured_reading",
                "{\"type\":\"structured_reading\",\"manual_roi\":null," +
                    "\"confirmed_format\":null}",
                "{\"type\":\"reading_threshold\",\"operator\":\"gte\"," +
                    "\"setup_complete\":false,\"threshold_decimal\":\"0\"," +
                    "\"duration_ms\":1000," +
                    "\"hysteresis_decimal\":\"0\"," +
                    "\"cooldown_ms\":0,\"source_kind\":\"digital_display\"}",
                "[]",
                1_700_000_000_000,
                null,
                null,
                null,
            ),
        )

        assertTrue(bridge.taskWrites(DEVICE_ID).isEmpty())
    }

    private fun insertObjectTask(
        ruleType: String = "presence_duration",
        condition: String = "appears",
        durationMillis: Long = 1_000,
    ) {
        database.monitoringDao().insertProductTask(
            LocalTaskEntity(
                TASK_ID,
                1,
                "找猫",
                "object_detection",
                "{\"type\":\"object_detection\",\"target_id\":\"cat\"," +
                    "\"label_zh_cn\":\"猫\",\"label_en\":\"cat\"}",
                "{\"type\":\"$ruleType\",\"condition\":\"$condition\"," +
                    "\"duration_ms\":$durationMillis}",
                "[]",
                1_700_000_000_000,
                PACKAGE_ID,
                PACKAGE_VERSION,
                MANIFEST_SHA256,
            ),
        )
    }

    private fun insertSampling(
        revision: Long,
        intervalMillis: Long,
        packageId: String = PACKAGE_ID,
    ) {
        val targetMode = checkNotNull(database.monitoringDao().findProductTask(TASK_ID)).targetMode
        val isReading = targetMode == "structured_reading"
        val isReference = targetMode == "reference_images"
        database.monitoringDao().upsertResolvedSamplingConfig(
            ResolvedSamplingConfigEntity(
                TASK_ID,
                revision,
                "2026.08.24.1",
                if (isReading) "structured_reading" else "visual_target",
                when {
                    isReading -> "numeric_display_reading"
                    isReference -> "reference_object_matching"
                    else -> "common_objects_tensorflow_efficientdet_lite2"
                },
                when {
                    isReading -> "reading_pipeline_general_v1"
                    isReference -> "reference_match_general_v1"
                    else -> "object_detection_general_v1"
                },
                when {
                    isReading -> "reading.numeric.display"
                    isReference -> "visual.reference.user_target"
                    else -> "object.common.cat"
                },
                packageId,
                PACKAGE_VERSION,
                MANIFEST_SHA256,
                "a".repeat(64),
                "b".repeat(64),
                intervalMillis,
                250,
                2_000,
                true,
            ),
        )
    }

    private fun assertPresenceEpisodeRule(
        rule: JsonObject,
        expectedDurationMillis: Long,
        expectedMaximumGapMillis: Long,
    ) {
        assertEquals(
            setOf(
                "type",
                "condition",
                "duration_ms",
                "min_positive_count",
                "max_positive_gap_ms",
                "rearm_absence_ms",
            ),
            rule.keys,
        )
        assertEquals("presence_duration", rule.getValue("type").jsonPrimitive.content)
        assertEquals("appears", rule.getValue("condition").jsonPrimitive.content)
        assertEquals(expectedDurationMillis, rule.getValue("duration_ms").jsonPrimitive.content.toLong())
        assertEquals(2L, rule.getValue("min_positive_count").jsonPrimitive.content.toLong())
        assertEquals(
            expectedMaximumGapMillis,
            rule.getValue("max_positive_gap_ms").jsonPrimitive.content.toLong(),
        )
        assertEquals(5_000L, rule.getValue("rearm_absence_ms").jsonPrimitive.content.toLong())
    }

    private companion object {
        const val DEVICE_ID = "01900000-0000-7000-8000-000000000010"
        const val TASK_ID = "01900000-0000-7000-8000-000000000020"
        const val EVENT_ID = "01900000-0000-7000-8000-000000000021"
        const val EPISODE_ID = "01900000-0000-7000-8000-000000000022"
        const val REMOTE_TASK_ID = "01900000-0000-7000-8000-000000000030"
        const val REMOTE_EVENT_ID = "01900000-0000-7000-8000-000000000031"
        const val REMOTE_EPISODE_ID = "01900000-0000-7000-8000-000000000032"
        const val ACCOUNT_ID = "018f0870-7b8a-4abc-8abc-3123456789ab"
        const val OTHER_ACCOUNT_ID = "018f0870-7b8a-4abc-8abc-4123456789ab"
        const val PACKAGE_ID = "object_detection_v1"
        const val PACKAGE_VERSION = "1.0.0"
        val MANIFEST_SHA256 = "c".repeat(64)
    }
}
