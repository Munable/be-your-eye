package app.beyoureyes.core.data.cloud

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.data.EventEntity
import app.beyoureyes.core.data.LocalTaskEntity
import app.beyoureyes.core.data.MonitorDatabase
import app.beyoureyes.core.data.ReferenceEventSnapshotStore
import app.beyoureyes.core.data.TaskEntity
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.SourceFrame
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSnapshotTransferControllerInstrumentedTest {
    @Test
    fun sourceEncryptsOneLocalTriggerAndViewerCachesItWithoutCloudMedia() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceFiles = File(context.cacheDir, "snapshot-source-${System.nanoTime()}")
        val viewerFiles = File(context.cacheDir, "snapshot-viewer-${System.nanoTime()}")
        val sourceDatabase = database(context, "source")
        val viewerDatabase = database(context, "viewer")
        val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = RelayBus()
        try {
            seedSource(sourceDatabase, sourceFiles)
            val sourceController = controller(
                deviceId = SOURCE_DEVICE_ID,
                database = sourceDatabase,
                files = sourceFiles,
                relay = bus.endpoint(),
                scope = sourceScope,
            )
            val viewerController = controller(
                deviceId = VIEWER_DEVICE_ID,
                database = viewerDatabase,
                files = viewerFiles,
                relay = bus.endpoint(),
                scope = viewerScope,
            )
            withTimeout(5_000) { while (bus.connectedCount() != 2) delay(20) }

            viewerController.request(
                RemoteSnapshotRequestSpec(EVENT_ID, TASK_ID, SOURCE_DEVICE_ID),
            )
            val ready = withTimeout(10_000) {
                while (true) {
                    val state = viewerController.states.value[EVENT_ID]
                    if (state is RemoteSnapshotViewState.Ready) return@withTimeout state
                    delay(25)
                }
                error("unreachable")
            }

            val cached = File(requireNotNull(Uri.parse(ready.privateUri).path))
            val decoded = BitmapFactory.decodeFile(cached.absolutePath)
            assertTrue(cached.isFile)
            assertNotNull(decoded)
            decoded.recycle()
            assertTrue(requireNotNull(bus.lastResponse).status == RemoteSnapshotResponse.STATUS_OK)
            assertTrue(requireNotNull(bus.lastResponse?.ciphertextBase64).length < 200_000)

            viewerController.clearCurrentAccountCache()
            assertTrue(!cached.exists())
            sourceController.reconcileCache()
        } finally {
            sourceScope.cancel()
            viewerScope.cancel()
            sourceDatabase.close()
            viewerDatabase.close()
            sourceFiles.deleteRecursively()
            viewerFiles.deleteRecursively()
        }
    }

    @Test
    fun clearingViewerCacheCancelsAnInFlightEncryptedResponseWithoutWaitingForTheNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceFiles = File(context.cacheDir, "snapshot-source-race-${System.nanoTime()}")
        val viewerFiles = File(context.cacheDir, "snapshot-viewer-race-${System.nanoTime()}")
        val sourceDatabase = database(context, "source-race")
        val viewerDatabase = database(context, "viewer-race")
        val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val responseGate = CompletableDeferred<Unit>()
        val bus = RelayBus(responseGate)
        try {
            seedSource(sourceDatabase, sourceFiles)
            controller(
                deviceId = SOURCE_DEVICE_ID,
                database = sourceDatabase,
                files = sourceFiles,
                relay = bus.endpoint(),
                scope = sourceScope,
            )
            val viewerController = controller(
                deviceId = VIEWER_DEVICE_ID,
                database = viewerDatabase,
                files = viewerFiles,
                relay = bus.endpoint(),
                scope = viewerScope,
            )
            withTimeout(5_000) { while (bus.connectedCount() != 2) delay(20) }

            viewerController.request(
                RemoteSnapshotRequestSpec(EVENT_ID, TASK_ID, SOURCE_DEVICE_ID),
            )
            withTimeout(5_000) { bus.requestSent.await() }
            val clear = async { viewerController.clearCurrentAccountCache() }
            withTimeout(1_000) { clear.await() }
            assertTrue(viewerController.states.value.isEmpty())
            assertTrue(viewerFiles.walk().none { it.isFile })

            responseGate.complete(Unit)
            delay(100)
            assertTrue(viewerController.states.value.isEmpty())
            assertTrue(viewerFiles.walk().none { it.isFile })
        } finally {
            sourceScope.cancel()
            viewerScope.cancel()
            sourceDatabase.close()
            viewerDatabase.close()
            sourceFiles.deleteRecursively()
            viewerFiles.deleteRecursively()
        }
    }

    private fun controller(
        deviceId: String,
        database: MonitorDatabase,
        files: File,
        relay: RemoteSnapshotRelay,
        scope: CoroutineScope,
    ) = RemoteSnapshotTransferController(
        accountState = MutableStateFlow(CloudAccountState.SignedIn(ACCOUNT_ID, "test@example.com")),
        accessGranted = MutableStateFlow(true),
        dataPlane = FakeDataPlane(),
        relay = relay,
        stateStore = FakeStateStore(deviceId),
        database = database,
        filesDir = files,
        scope = scope,
    )

    private fun database(context: Context, name: String): MonitorDatabase =
        Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "snapshot-test-$name")
            })
            .build()

    private fun seedSource(database: MonitorDatabase, files: File) {
        val dao = database.monitoringDao()
        dao.insertProductTask(
            LocalTaskEntity(
                TASK_ID,
                1,
                "门口",
                "reference_images",
                "{\"type\":\"reference_images\",\"route_binding\":null}",
                "{\"type\":\"presence_duration\",\"condition\":\"appears\",\"duration_ms\":500}",
                "[]",
                1,
                null,
                null,
                null,
            ),
        )
        dao.upsertTask(TaskEntity(TASK_ID, 1, "local-source-snapshot", true))
        dao.insertEvent(
            EventEntity(
                EVENT_ID,
                TASK_ID,
                1,
                EPISODE_ID,
                1,
                1,
                "{\"type\":\"object_episode\",\"target_id\":\"door\"," +
                    "\"condition\":\"appeared\",\"duration_ms\":0,\"count\":1}",
                "发现目标",
                0,
                null,
            ),
        )
        ReferenceEventSnapshotStore.openAppPrivate(files).capture(
            TASK_ID,
            EVENT_ID,
            SourceFrame(
                sourceSequence = 1,
                monotonicTimeMillis = 1,
                capturedAtEpochMillis = 1,
                width = 2,
                height = 2,
                rotationDegrees = 0,
                cropRect = PixelRect(0, 0, 2, 2),
                pixels = FramePixels.Rgb888(
                    byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1),
                    6,
                ),
            ),
        )
    }

    private class RelayBus(
        private val responseGate: CompletableDeferred<Unit>? = null,
    ) {
        private val endpoints = mutableListOf<Endpoint>()
        val requestSent = CompletableDeferred<Unit>()
        var lastResponse: RemoteSnapshotResponse? = null
            private set

        fun endpoint(): RemoteSnapshotRelay = synchronized(endpoints) {
            Endpoint().also(endpoints::add)
        }

        fun connectedCount(): Int = synchronized(endpoints) { endpoints.count { it.connected } }

        private inner class Endpoint : RemoteSnapshotRelay {
            private val mutableRequests = MutableSharedFlow<RemoteSnapshotRequest>(extraBufferCapacity = 8)
            private val mutableResponses = MutableSharedFlow<RemoteSnapshotResponse>(extraBufferCapacity = 8)
            @Volatile var connected = false
            override val configured = true
            override val requests: Flow<RemoteSnapshotRequest> = mutableRequests
            override val responses: Flow<RemoteSnapshotResponse> = mutableResponses
            override suspend fun connect(accountId: String) {
                requireAccountUuid(accountId)
                connected = true
            }
            override suspend fun disconnect() { connected = false }
            override suspend fun send(request: RemoteSnapshotRequest) {
                requestSent.complete(Unit)
                synchronized(endpoints) { endpoints.filter { it !== this && it.connected } }
                    .forEach { it.mutableRequests.emit(request) }
            }
            override suspend fun send(response: RemoteSnapshotResponse) {
                responseGate?.await()
                lastResponse = response
                synchronized(endpoints) { endpoints.filter { it !== this && it.connected } }
                    .forEach { it.mutableResponses.emit(response) }
            }
        }
    }

    private class FakeStateStore(private val id: String) : CloudLocalStateStore {
        override suspend fun deviceId(accountId: String) = id
        override suspend fun cursor(accountId: String) = 0L
        override suspend fun setCursor(accountId: String, cursor: Long) = Unit
        override suspend fun pushToken(): String? = null
        override suspend fun setPushToken(token: String?) = Unit
        override suspend fun syncedTaskIds(accountId: String): Set<String> = emptySet()
        override suspend fun setSyncedTaskIds(accountId: String, taskIds: Set<String>) = Unit
        override suspend fun resetAccountSessionState(accountId: String) = Unit
        override suspend fun clearAccountState(accountId: String) = Unit
        override suspend fun syncFingerprint(accountId: String, resourceKey: String): String? = null
        override suspend fun setSyncFingerprint(accountId: String, resourceKey: String, value: String?) = Unit
    }

    private class FakeDataPlane : CloudDataPlane {
        override val configured = true
        override suspend fun currentAccountId() = ACCOUNT_ID
        override suspend fun peerDevices() = listOf(
            CloudDeviceRow(SOURCE_DEVICE_ID, "source", false, 1, "2026-08-13T00:00:00Z"),
            CloudDeviceRow(VIEWER_DEVICE_ID, "viewer", false, 1, "2026-08-13T00:00:00Z"),
        )
        override suspend fun upsertDevice(device: CloudDeviceWrite) = Unit
        override suspend fun revokeDevice(deviceId: String) = false
        override suspend fun upsertTasks(tasks: List<CloudTaskWrite>) = Unit
        override suspend fun deleteTasks(taskIds: List<String>) = Unit
        override suspend fun uploadEvents(events: List<CloudEventWrite>) =
            CloudEventBatchResult(emptyList(), emptyList())
        override suspend fun changesAfter(cursor: Long, limit: Int) = emptyList<CloudSyncChange>()
        override suspend fun event(eventId: String): CloudEventRow? = null
        override suspend fun addReceipt(eventId: String, deviceId: String, type: ReceiptType, receivedAt: String) = Unit
        override suspend fun registerPushToken(deviceId: String, token: String) = Unit
        override suspend fun unregisterPushToken(deviceId: String) = Unit
    }

    private companion object {
        const val ACCOUNT_ID = "018f0870-7b8a-4abc-8abc-3123456789ab"
        const val SOURCE_DEVICE_ID = "01900000-0000-7000-8000-000000000010"
        const val VIEWER_DEVICE_ID = "01900000-0000-7000-8000-000000000011"
        const val TASK_ID = "01900000-0000-7000-8000-000000000020"
        const val EVENT_ID = "01900000-0000-7000-8000-000000000021"
        const val EPISODE_ID = "01900000-0000-7000-8000-000000000022"
    }
}
