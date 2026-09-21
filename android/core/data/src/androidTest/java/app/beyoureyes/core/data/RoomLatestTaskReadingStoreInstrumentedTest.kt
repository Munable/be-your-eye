package app.beyoureyes.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLatestTaskReadingStoreInstrumentedTest {
    private lateinit var database: MonitorDatabase
    private lateinit var productStore: RoomMonitorStore
    private lateinit var readingStore: RoomLatestTaskReadingStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        productStore = RoomMonitorStore(database)
        readingStore = RoomLatestTaskReadingStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun candidateStableAndUnavailableAreTypedAndOnlyAdvanceBySequence() = runBlocking {
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )
        val sampling = sampling(pointer)
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            productStore.insertConfiguredReading(
                task().copy(runtimePackagePointer = pointer),
                sampling,
                maximumTasks = 20,
            ),
        )
        assertTrue(
            readingStore.record(
                TASK_ID,
                1,
                Observation.Reading("12.0", "12", false, 1, 0.8f),
                100,
                configuredUnit = "kPa",
            ),
        )
        assertEquals(LatestReadingStatus.CANDIDATE, readingStore.find(TASK_ID)?.status)
        assertFalse(
            readingStore.record(
                TASK_ID,
                1,
                Observation.Reading("13", "13", true, 1, 0.9f),
                101,
                configuredUnit = "kPa",
            ),
        )
        assertTrue(
            readingStore.record(
                TASK_ID,
                1,
                Observation.Reading("13", "13", true, 2, 0.9f),
                102,
                configuredUnit = "kPa",
            ),
        )
        assertEquals(LatestReadingStatus.STABLE, readingStore.find(TASK_ID)?.status)
        assertEquals("13", readingStore.find(TASK_ID)?.valueDecimal)
        assertTrue(
            readingStore.record(
                TASK_ID,
                1,
                Observation.Unavailable(UnavailableReason.LOW_QUALITY, "blur", 3),
                103,
                configuredUnit = "kPa",
            ),
        )
        val unavailable = readingStore.find(TASK_ID)
        assertEquals(LatestReadingStatus.UNAVAILABLE, unavailable?.status)
        assertNull(unavailable?.valueDecimal)
        assertEquals("kPa", unavailable?.unit)
        assertFalse(
            readingStore.record(
                TASK_ID,
                2,
                Observation.Reading("14", "14", true, 4, 0.9f),
                104,
                configuredUnit = "kPa",
            ),
        )
        assertEquals(1, readingStore.readings.first { it.isNotEmpty() }.size)
    }

    @Test
    fun deletingTaskCascadesLatestReading() = runBlocking {
        val pointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )
        assertEquals(
            LocalTaskInsertResult.INSERTED,
            productStore.insertConfiguredReading(
                task().copy(runtimePackagePointer = pointer),
                sampling(pointer),
                maximumTasks = 20,
            ),
        )
        readingStore.record(
            TASK_ID,
            1,
            Observation.Reading("12", "12", true, 1, 0.9f),
            100,
            configuredUnit = null,
        )

        assertTrue(productStore.deleteTaskAndRuntime(TASK_ID))
        assertNull(readingStore.find(TASK_ID))
    }

    private fun task() = StoredLocalTask(
        taskId = TASK_ID,
        revision = 1,
        title = "压力",
        targetMode = "structured_reading",
        targetJson = "{\"type\":\"structured_reading\",\"manual_roi\":null," +
            "\"confirmed_format\":{\"profile_id\":\"confirmed_reading_format_v2\"," +
            "\"kind\":\"decimal\",\"fractional_digits\":0," +
            "\"time_segments\":null,\"unit\":null},\"route_binding\":null}",
        ruleJson = "{\"type\":\"reading_threshold\"}",
        materialRefsJson = "[]",
        createdAtEpochMillis = 1,
    )

    private fun sampling(pointer: ModelPackagePointer) = ResolvedSamplingConfig(
        taskId = TASK_ID,
        taskRevision = 1,
        catalogVersion = "2026.08.24.1",
        capabilityId = "structured_reading",
        modelProfileKey = "numeric_display_reading",
        recipeId = "reading_pipeline_general_v1",
        intentKey = "reading.numeric.display",
        packagePointer = pointer,
        artifactIdentitySha256 = "b".repeat(64),
        deviceFingerprintSha256 = "c".repeat(64),
        intervalMillis = 750,
        manifestMinimumIntervalMillis = 250,
        manifestMaximumIntervalMillis = 2_000,
        adaptiveEnabled = false,
    )

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
    }
}
