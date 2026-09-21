package app.beyoureyes.core.data

import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.domain.RestrictedEventPayload
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.data.reference.StoredReference
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.Monitor
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.ReadingFormatKind
import app.beyoureyes.core.domain.ReadingTargetConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMonitorStoreInstrumentedTest {
    private lateinit var database: MonitorDatabase
    private lateinit var store: RoomMonitorStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomMonitorStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun twentyFirstTaskIsRejectedAndExistingRowsRemain() = runBlocking {
        database.monitoringDao().upsertTask(
            TaskEntity(
                REMOTE_TASK_ID,
                2,
                remoteSnapshot(REMOTE_TASK_ID, 2, "其他手机监控"),
                false,
            ),
        )
        assertEquals(
            ACCOUNT_ID,
            store.remoteTasks.first { it.isNotEmpty() }.single().accountId,
        )

        val originalIds = (0 until 20).map { index ->
            taskId(index).also { id ->
                assertEquals(
                    LocalTaskInsertResult.INSERTED,
                    store.insert(task(id, "监控 $index"), maximumTasks = 20),
                )
            }
        }.toSet()

        assertEquals(
            LocalTaskInsertResult.CAPACITY_REACHED,
            store.insert(task(taskId(20), "第 21 条"), maximumTasks = 20),
        )

        val stored = store.tasks.first { it.size == 20 }
        assertEquals(originalIds, stored.map { it.taskId }.toSet())
        assertEquals(20, store.count())
        assertEquals(1, store.remoteTasks.first { it.isNotEmpty() }.size)
    }

    @Test
    fun malformedOrActiveRemoteSnapshotsStayOutOfTheDisplayOnlyFlow() = runBlocking {
        database.monitoringDao().upsertTask(
            TaskEntity(
                REMOTE_TASK_ID,
                2,
                remoteSnapshot(REMOTE_TASK_ID, 2, "多余字段").dropLast(1) + ",\"extra\":true}",
                false,
            ),
        )
        database.monitoringDao().upsertTask(
            TaskEntity(
                SECOND_REMOTE_TASK_ID,
                3,
                remoteSnapshot(SECOND_REMOTE_TASK_ID, 3, "错误活动状态"),
                true,
            ),
        )

        assertTrue(store.remoteTasks.first().isEmpty())
        assertEquals(0, store.count())
    }

    @Test
    fun deletingProductTaskCascadesRuntimeEventOutboxReadingAndSampling() = runBlocking {
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insert(task(TASK_ID, "门口有人"), maximumTasks = 20),
        )
        val sampling = sampling(
            ModelPackagePointer(
                ModelPackageIdentity("reference_matcher", "2.1.0"),
                "a".repeat(64),
            ),
            revision = 1,
        )
        assertTrue(store.bindRuntimePackage(sampling))
        val readings = RoomLatestTaskReadingStore(database)
        assertTrue(
            readings.record(
                taskId = TASK_ID,
                taskRevision = 1,
                observation = Observation.Reading("12", "12", true, 1, 0.9f),
                observedAtEpochMillis = 1_700_000_000_050,
                configuredUnit = null,
            ),
        )
        val events = RoomEventRepository(database)
        assertEquals(EventWriteResult.Inserted(EVENT_ID), events.persist(eventRequest()))
        assertEquals(EVENT_ID, store.events.first { it.isNotEmpty() }.single().eventId)
        assertEquals(sampling, store.findResolvedSamplingConfig(TASK_ID))
        assertEquals("12", readings.find(TASK_ID)?.valueDecimal)

        assertEquals(true, store.deleteTaskAndRuntime(TASK_ID))

        assertNull(database.monitoringDao().findProductTask(TASK_ID))
        assertNull(database.monitoringDao().findTask(TASK_ID))
        assertNull(database.monitoringDao().findResolvedSamplingConfig(TASK_ID))
        assertNull(database.monitoringDao().findLatestTaskReading(TASK_ID))
        assertEquals(0, database.monitoringDao().eventCount())
        assertEquals(0, database.monitoringDao().outboxCount())
        assertEquals(emptyList<StoredLocalTask>(), store.tasks.first())
        assertEquals(emptyList<TimelineEvent>(), store.events.first())
    }

    @Test
    fun exactRuntimePointerBindsOnlyTheCurrentTaskRevision() = runBlocking {
        val original = task(TASK_ID, "门口有人")
        assertEquals(LocalTaskInsertResult.INSERTED, store.insert(original, maximumTasks = 20))
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reference_matcher", "2.1.0"),
            "a".repeat(64),
        )

        assertEquals(false, store.bindRuntimePackage(sampling(pointer, revision = 2)))
        assertNull(store.find(TASK_ID)?.runtimePackagePointer)
        assertNull(store.findResolvedSamplingConfig(TASK_ID))
        val sampling = sampling(pointer, revision = 1)
        assertEquals(true, store.bindRuntimePackage(sampling))
        assertEquals(pointer, store.find(TASK_ID)?.runtimePackagePointer)
        assertEquals(sampling, store.findResolvedSamplingConfig(TASK_ID))
        assertEquals(pointer, store.tasks.first { it.single().runtimePackagePointer != null }
            .single().runtimePackagePointer)
    }

    @Test
    fun configuredReadingAndSamplingAreInsertedAsOneExactGeneration() = runBlocking {
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )
        val row = configuredReadingTask(TASK_ID, pointer)
        val sampling = sampling(pointer, revision = 1)

        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insertConfiguredReading(row, sampling, maximumTasks = 20),
        )

        val persistedRow = store.find(TASK_ID)
        val decoded = MonitorStorageCodec.decode(checkNotNull(persistedRow))
        assertEquals(1L, decoded.monitor.revision)
        assertTrue((decoded.monitor.rule as MonitorRule.ReadingThreshold).configured)
        assertEquals(
            ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
            (decoded.monitor.target as MonitorTarget.NumericReading).confirmedFormat,
        )
        assertEquals(pointer, persistedRow.runtimePackagePointer)
        assertEquals(sampling, store.findResolvedSamplingConfig(TASK_ID))
    }

    @Test
    fun manualConfiguredReadingUsesOneAtomicRowPair() = runBlocking {
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )
        val row = configuredReadingTask(TASK_ID, pointer)
        val sampling = sampling(pointer, revision = 1)

        assertEquals(0, store.count())
        assertNull(store.findResolvedSamplingConfig(TASK_ID))
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insertConfiguredReading(row, sampling, maximumTasks = 20),
        )

        assertEquals(1, store.count())
        val persistedRow = checkNotNull(store.find(TASK_ID))
        val decoded = MonitorStorageCodec.decode(persistedRow)
        assertTrue((decoded.monitor.rule as MonitorRule.ReadingThreshold).configured)
        assertEquals(pointer, persistedRow.runtimePackagePointer)
        assertEquals(sampling, store.findResolvedSamplingConfig(TASK_ID))
    }

    @Test
    fun configuredReferenceAndSamplingAreInsertedAsOneExactGeneration() = runBlocking {
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reference_matcher", "2.1.0"),
            "e".repeat(64),
        )
        val row = configuredReferenceTask(TASK_ID, pointer)
        val sampling = sampling(pointer, revision = 1)

        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insertConfiguredReference(row, sampling, maximumTasks = 20),
        )

        val persistedRow = checkNotNull(store.find(TASK_ID))
        val decoded = MonitorStorageCodec.decode(persistedRow)
        assertEquals(1L, decoded.monitor.revision)
        assertEquals(3, (decoded.monitor.target as MonitorTarget.ReferenceImages).imageCount)
        assertEquals(pointer, persistedRow.runtimePackagePointer)
        assertEquals(sampling, store.findResolvedSamplingConfig(TASK_ID))
    }

    @Test
    fun configuredObjectAndSamplingRoundTripWithStrictTargetJson() = runBlocking {
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("efficientdet_lite2_object_v1", "1.0.0"),
            "f".repeat(64),
        )
        val target = MonitorTarget.ObjectClass("cat", "猫", "cat")
        val row = configuredObjectTask(TASK_ID, pointer, target)
        val resolved = sampling(pointer, revision = 1)

        assertEquals(0, store.count())
        assertNull(store.findResolvedSamplingConfig(TASK_ID))
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insertConfiguredObject(row, resolved, maximumTasks = 20),
        )

        assertEquals(1, store.count())
        val persisted = checkNotNull(store.find(TASK_ID))
        val decoded = MonitorStorageCodec.decode(persisted)
        assertEquals(target, decoded.monitor.target)
        assertEquals(pointer, persisted.runtimePackagePointer)
        assertEquals(resolved, store.findResolvedSamplingConfig(TASK_ID))
    }

    @Test
    fun visualConditionAndDurationRoundTripWithoutInference() {
        val rules = listOf(
            MonitorRule.TargetPresence(PresenceRuleKind.APPEARS, 3),
            MonitorRule.TargetPresence(PresenceRuleKind.REMAINS, 3),
            MonitorRule.TargetPresence(PresenceRuleKind.DISAPPEARS, 30),
        )

        rules.forEach { rule ->
            assertEquals(rule, MonitorStorageCodec.decodeRule(MonitorStorageCodec.encodeRule(rule)))
        }
    }

    @Test
    fun readingConditionDurationRoundTripsWithoutDefaulting() {
        val rules = listOf(
            MonitorRule.ReadingThreshold.Single(
                comparison = ReadingComparison.GTE,
                thresholdDecimal = "12.5",
                configured = true,
                durationSeconds = 3,
            ),
            MonitorRule.ReadingThreshold.Outside(
                lowerThresholdDecimal = "10",
                upperThresholdDecimal = "20",
                configured = true,
                durationSeconds = 30,
            ),
        )

        rules.forEach { rule ->
            assertEquals(rule, MonitorStorageCodec.decodeRule(MonitorStorageCodec.encodeRule(rule)))
        }
        val unsupported = MonitorStorageCodec.encodeRule(rules.first())
            .replace("\"duration_ms\":3000", "\"duration_ms\":2000")
        assertThrows(IllegalArgumentException::class.java) {
            MonitorStorageCodec.decodeRule(unsupported)
        }
    }

    @Test
    fun samplingWriteFailureRollsBackConfiguredObjectInsert() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_object_sampling BEFORE INSERT ON resolved_sampling_configs " +
                "BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END",
        )
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("efficientdet_lite2_object_v1", "1.0.0"),
            "f".repeat(64),
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                store.insertConfiguredObject(
                    configuredObjectTask(
                        TASK_ID,
                        pointer,
                        MonitorTarget.ObjectClass("cat", "猫", "cat"),
                    ),
                    sampling(pointer, revision = 1),
                    maximumTasks = 20,
                )
            }
        }

        runBlocking {
            assertNull(store.find(TASK_ID))
            assertNull(store.findResolvedSamplingConfig(TASK_ID))
            assertEquals(0, store.count())
        }
    }

    @Test
    fun capacityFailureLeavesNeitherConfiguredReadingNorSampling() = runBlocking {
        repeat(20) { index ->
            assertEquals(
                LocalTaskInsertResult.INSERTED,
                store.insert(task(taskId(index), "监控 $index"), maximumTasks = 20),
            )
        }
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )

        assertEquals(
            LocalTaskInsertResult.CAPACITY_REACHED,
            store.insertConfiguredReading(
                configuredReadingTask(TASK_ID, pointer),
                sampling(pointer, revision = 1),
                maximumTasks = 20,
            ),
        )

        assertNull(store.find(TASK_ID))
        assertNull(store.findResolvedSamplingConfig(TASK_ID))
        assertEquals(20, store.count())
    }

    @Test
    fun existingIdentityIsNotReplacedByConfiguredReadingOrSampling() = runBlocking {
        val original = task(TASK_ID, "原监控")
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insert(original, maximumTasks = 20),
        )
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )

        assertEquals(
            LocalTaskInsertResult.ALREADY_EXISTS,
            store.insertConfiguredReading(
                configuredReadingTask(TASK_ID, pointer),
                sampling(pointer, revision = 1),
                maximumTasks = 20,
            ),
        )

        assertEquals(original, store.find(TASK_ID))
        assertNull(store.findResolvedSamplingConfig(TASK_ID))
        assertEquals(1, store.count())
    }

    @Test
    fun samplingWriteFailureRollsBackTheConfiguredReadingInsert() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_atomic_sampling BEFORE INSERT ON resolved_sampling_configs " +
                "BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END",
        )
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )
        val row = configuredReadingTask(
            TASK_ID,
            pointer,
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                store.insertConfiguredReading(
                    row,
                    sampling(pointer, revision = 1),
                    maximumTasks = 20,
                )
            }
        }

        runBlocking {
            assertNull(store.find(TASK_ID))
            assertNull(store.findResolvedSamplingConfig(TASK_ID))
            assertEquals(0, store.count())
        }
    }

    @Test
    fun monitorRuleRevisionCarriesExactRuntimeBindingAndRejectsStaleRetry() = runBlocking {
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )
        val originalSampling = sampling(pointer, revision = 1)
        val original = configuredReadingTask(TASK_ID, pointer)
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insertConfiguredReading(original, originalSampling, maximumTasks = 20),
        )
        val latestReadings = RoomLatestTaskReadingStore(database)
        assertTrue(
            latestReadings.record(
                taskId = TASK_ID,
                taskRevision = 1,
                observation = Observation.Reading("120", "120", true, 1, 0.99f),
                observedAtEpochMillis = 1_700_000_000_050,
                configuredUnit = null,
            ),
        )
        val replacement = "{\"type\":\"reading_threshold\",\"operator\":\"gt\"," +
            "\"threshold_decimal\":\"1306\",\"setup_complete\":true," +
            "\"duration_ms\":3000," +
            "\"hysteresis_decimal\":\"0\"," +
            "\"cooldown_ms\":0,\"source_kind\":\"digital_display\"}"

        val carried = store.reviseRuleAndCarryRuntime(originalSampling, replacement)

        assertEquals(2L, carried?.taskRevision)
        assertEquals(carried, store.findResolvedSamplingConfig(TASK_ID))
        assertEquals(2L, store.find(TASK_ID)?.revision)
        assertEquals(replacement, store.find(TASK_ID)?.ruleJson)
        assertEquals(pointer, store.find(TASK_ID)?.runtimePackagePointer)
        assertEquals("120", latestReadings.find(TASK_ID)?.valueDecimal)
        assertNull(store.reviseRuleAndCarryRuntime(originalSampling, replacement))
    }

    @Test
    fun rejectedFirstStartThenChangedReadingRuleAndRoiAdvanceOneExactDbGeneration() = runBlocking {
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )
        val originalSampling = sampling(pointer, revision = 1)
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insertConfiguredReading(
                configuredReadingTask(TASK_ID, pointer),
                originalSampling,
                maximumTasks = 20,
            ),
        )

        // A rejected MainActivity/FGS handoff does not mutate Room. The user remains on setup.
        assertEquals(1L, store.find(TASK_ID)?.revision)
        assertEquals(originalSampling, store.findResolvedSamplingConfig(TASK_ID))

        val changedTarget = ReadingTargetConfig(
            manualRoi = NormalizedRect(0.1f, 0.2f, 0.8f, 0.9f),
            confirmedFormat = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
        )
        val changedRule = MonitorRule.ReadingThreshold.Single(
            comparison = ReadingComparison.LT,
            thresholdDecimal = "30",
            configured = true,
        )
        val carried = store.reviseReadingTargetAndRuleAndCarryRuntime(
            expected = originalSampling,
            targetJson = MonitorStorageCodec.encodeReadingTarget(changedTarget),
            ruleJson = MonitorStorageCodec.encodeRule(changedRule),
        )

        val expectedSampling = originalSampling.copy(taskRevision = 2)
        assertEquals(expectedSampling, carried)
        assertEquals(expectedSampling, store.findResolvedSamplingConfig(TASK_ID))
        assertEquals(1, store.count())
        val persisted = checkNotNull(store.find(TASK_ID))
        val decoded = MonitorStorageCodec.decode(persisted)
        assertEquals(2L, persisted.revision)
        assertEquals(changedRule, decoded.monitor.rule)
        assertEquals(
            changedTarget,
            (decoded.monitor.target as MonitorTarget.NumericReading).config,
        )
        assertEquals(pointer, persisted.runtimePackagePointer)
        assertEquals(originalSampling.artifactIdentitySha256, expectedSampling.artifactIdentitySha256)
        assertEquals(originalSampling.deviceFingerprintSha256, expectedSampling.deviceFingerprintSha256)
        assertEquals(originalSampling.intervalMillis, expectedSampling.intervalMillis)
        assertEquals(
            originalSampling.manifestMinimumIntervalMillis,
            expectedSampling.manifestMinimumIntervalMillis,
        )
        assertEquals(
            originalSampling.manifestMaximumIntervalMillis,
            expectedSampling.manifestMaximumIntervalMillis,
        )
        assertNull(
            store.reviseReadingTargetAndRuleAndCarryRuntime(
                expected = originalSampling,
                targetJson = MonitorStorageCodec.encodeReadingTarget(changedTarget),
                ruleJson = MonitorStorageCodec.encodeRule(changedRule),
            ),
        )
    }

    @Test
    fun referenceMaterialRevisionCarriesRuntimeBindingAndRejectsStaleRetry() = runBlocking {
        val original = task(TASK_ID, "门口目标").copy(materialRefsJson = "[\"old\"]")
        assertEquals(LocalTaskInsertResult.INSERTED, store.insert(original, maximumTasks = 20))
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reference_matcher", "2.1.0"),
            "a".repeat(64),
        )
        val originalSampling = sampling(pointer, revision = 1)
        assertTrue(store.bindRuntimePackage(originalSampling))
        val replacement = "[\"new-a\",\"new-b\",\"new-c\"]"

        val carried = store.reviseReferenceMaterialsAndCarryRuntime(
            originalSampling,
            replacement,
        )

        assertEquals(2L, carried?.taskRevision)
        assertEquals(carried, store.findResolvedSamplingConfig(TASK_ID))
        assertEquals(2L, store.find(TASK_ID)?.revision)
        assertEquals(replacement, store.find(TASK_ID)?.materialRefsJson)
        assertEquals(pointer, store.find(TASK_ID)?.runtimePackagePointer)
        assertNull(
            store.reviseReferenceMaterialsAndCarryRuntime(originalSampling, replacement),
        )
    }

    @Test
    fun unpreparedReferenceMaterialRevisionRequiresNoRuntimeHalfBinding() = runBlocking {
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            store.insert(task(TASK_ID, "未准备监控"), maximumTasks = 20),
        )
        val replacement = "[\"new-a\",\"new-b\",\"new-c\"]"

        assertEquals(
            2L,
            store.reviseReferenceMaterialsWithoutRuntime(TASK_ID, 1, replacement),
        )
        assertEquals(2L, store.find(TASK_ID)?.revision)
        assertEquals(replacement, store.find(TASK_ID)?.materialRefsJson)
        assertNull(store.reviseReferenceMaterialsWithoutRuntime(TASK_ID, 1, replacement))
    }

    private fun task(taskId: String, title: String) = StoredLocalTask(
        taskId = taskId,
        revision = 1,
        title = title,
        targetMode = "reference_images",
        targetJson = "{\"type\":\"reference_images\"}",
        ruleJson = "{\"type\":\"presence_duration\",\"condition\":\"appears\",\"duration_ms\":1000}",
        materialRefsJson = "[]",
        createdAtEpochMillis = 1_700_000_000_000,
    )

    private fun sampling(pointer: ModelPackagePointer, revision: Long) = ResolvedSamplingConfig(
        taskId = TASK_ID,
        taskRevision = revision,
        catalogVersion = "2026.08.24.1",
        capabilityId = if (pointer.identity.packageId == "reader") {
            "structured_reading"
        } else {
            "visual_target"
        },
        modelProfileKey = if (pointer.identity.packageId == "reader") {
            "numeric_display_reading"
        } else {
            "reference_object_matching"
        },
        recipeId = if (pointer.identity.packageId == "reader") {
            "reading_pipeline_general_v1"
        } else {
            "reference_match_general_v1"
        },
        intentKey = if (pointer.identity.packageId == "reader") {
            "reading.numeric.display"
        } else {
            "visual.reference.user_target"
        },
        packagePointer = pointer,
        artifactIdentitySha256 = "b".repeat(64),
        deviceFingerprintSha256 = "c".repeat(64),
        intervalMillis = 750,
        manifestMinimumIntervalMillis = 250,
        manifestMaximumIntervalMillis = 2_000,
        adaptiveEnabled = false,
    )

    private fun configuredReadingTask(
        taskId: String,
        pointer: ModelPackagePointer,
    ): StoredLocalTask = MonitorStorageCodec.encode(
        monitor = Monitor(
            id = taskId,
            revision = 1,
            name = "数字监控 1",
            target = MonitorTarget.NumericReading(
                ReadingTargetConfig(
                    confirmedFormat = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
                ),
            ),
            rule = MonitorRule.ReadingThreshold.Single(
                thresholdDecimal = "120",
                configured = true,
                durationSeconds = 3,
            ),
            createdAtEpochMillis = 1_700_000_000_000,
        ),
        references = emptyList(),
    ).copy(runtimePackagePointer = pointer)

    private fun configuredReferenceTask(
        taskId: String,
        pointer: ModelPackagePointer,
    ): StoredLocalTask {
        val references = (1..3).map { index ->
            StoredReference(
                relativePath =
                    "reference-images/$taskId/generation-fixture/reference-$index.jpg",
                thumbnailRelativePath =
                    "reference-images/$taskId/generation-fixture/reference-$index.thumb.jpg",
                exactSha256 = index.toString().repeat(64),
                thumbnailSha256 = (index + 3).toString().repeat(64),
                differenceHash = index.toLong(),
                meanRed = 10 * index,
                meanGreen = 20 * index,
                meanBlue = 30 * index,
                width = 100,
                height = 100,
            )
        }
        return MonitorStorageCodec.encode(
            monitor = Monitor(
                id = taskId,
                revision = 1,
                name = "参考监控 1",
                target = MonitorTarget.ReferenceImages(references.size),
                rule = MonitorRule.TargetPresence(),
                createdAtEpochMillis = 1_700_000_000_000,
            ),
            references = references,
            ).copy(runtimePackagePointer = pointer)
    }

    private fun configuredObjectTask(
        taskId: String,
        pointer: ModelPackagePointer,
        target: MonitorTarget.ObjectClass,
    ): StoredLocalTask = MonitorStorageCodec.encode(
        monitor = Monitor(
            id = taskId,
            revision = 1,
            name = "文字找目标 1",
            target = target,
            rule = MonitorRule.TargetPresence(),
            createdAtEpochMillis = 1_700_000_000_000,
        ),
        references = emptyList(),
    ).copy(runtimePackagePointer = pointer)

    private fun eventRequest() = EventWriteRequest(
        eventId = EVENT_ID,
        task = EventTaskSnapshot(TASK_ID, 1, "{\"runtime\":true}"),
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
    )

    private fun taskId(index: Int): String = UuidV7.generate(1_700_000_000_000L + index)

    private fun remoteSnapshot(taskId: String, revision: Long, title: String) =
        "{\"schema_version\":\"remote_event_cache_v3\"," +
            "\"account_id\":\"$ACCOUNT_ID\"," +
            "\"task_id\":\"$taskId\",\"task_revision\":$revision," +
            "\"title\":\"$title\",\"capability_id\":\"visual_target\"," +
            "\"target_definition_mode\":\"reference_images\"," +
            "\"monitoring_device_id\":\"$MONITORING_DEVICE_ID\"}"

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        const val EVENT_ID = "01900000-0000-7000-8000-000000000001"
        const val EPISODE_ID = "01900000-0000-7000-8000-000000000002"
        const val REMOTE_TASK_ID = "01900000-0000-7000-8000-000000000010"
        const val SECOND_REMOTE_TASK_ID = "01900000-0000-7000-8000-000000000011"
        const val MONITORING_DEVICE_ID = "01900000-0000-7000-8000-000000000012"
        const val ACCOUNT_ID = "018f0870-7b8a-4abc-8abc-3123456789ab"
    }
}
