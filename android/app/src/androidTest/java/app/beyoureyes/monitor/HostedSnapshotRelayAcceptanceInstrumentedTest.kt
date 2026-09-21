package app.beyoureyes.monitor

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.EventEntity
import app.beyoureyes.core.data.LocalTaskEntity
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.data.OutboxEntity
import app.beyoureyes.core.data.ReferenceEventSnapshotStore
import app.beyoureyes.core.data.ResolvedSamplingConfigEntity
import app.beyoureyes.core.data.TaskEntity
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.core.data.cloud.CloudConfiguration
import app.beyoureyes.core.data.cloud.CloudDeviceWrite
import app.beyoureyes.core.data.cloud.RemoteSnapshotRequestSpec
import app.beyoureyes.core.data.cloud.RemoteSnapshotViewState
import app.beyoureyes.core.data.cloud.SupabaseCloudClientFactory
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.SourceFrame
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit two-device acceptance. Run the source role on a physical phone and the viewer role on
 * a separate API 36 Android device with one disposable account. The relay carries one HPKE ciphertext only;
 * neither the Event nor any Supabase table/Storage row contains media.
 */
@RunWith(AndroidJUnit4::class)
class HostedSnapshotRelayAcceptanceInstrumentedTest {
    @Test
    fun physicalSourceAndApi36ViewerExchangeOneEncryptedTriggerPhoto() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Run explicitly with -e runHostedSnapshotRelayAcceptance true",
            arguments.getString("runHostedSnapshotRelayAcceptance") == "true",
        )
        val email = requireNotNull(arguments.getString("snapshotEmail")).trim()
        val password = requireNotNull(arguments.getString("snapshotPassword"))
        val completionMarker = requireNotNull(arguments.getString("snapshotCompletionMarker"))
        require(completionMarker.matches(Regex("snapshot-complete-[a-z0-9-]{8,80}")))
        when (requireNotNull(arguments.getString("snapshotRole"))) {
            "source" -> runSource(email, password, completionMarker)
            "viewer" -> runViewer(email, password, completionMarker)
            else -> error("snapshotRole must be source or viewer")
        }
    }

    private suspend fun runSource(email: String, password: String, completionMarker: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = CloudBootstrap.accountController(context)
        if (controller.state.value is CloudAccountState.SignedIn) controller.signOut()
        var sourceActivity: MainActivity? = null
        var deleted = false
        try {
            val signedIn = controller.signIn(email, password)
            assertTrue(signedIn is CloudAccountState.SignedIn)
            clearSourceFixture(context)
            seedSourceEvent(context)
            val sync = CloudBootstrap.coordinator(context).sync()
            assertTrue(sync.uploadedEvents in 0..1)
            assertEquals(0, MonitorDatabaseFactory.open(context).monitoringDao().outboxCount())
            assertTrue(ReferenceEventSnapshotStore.openAppPrivate(context.filesDir)
                .uriFor(TASK_ID, EVENT_ID) != null)

            // A real source has an active camera foreground service. Keep this deterministic
            // fixture foreground instead, so vendor background freezing cannot invalidate the
            // phone-to-emulator relay result while the human-visible viewer route is exercised.
            val foregroundActivity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            sourceActivity = foregroundActivity
            instrumentation.runOnMainSync {
                foregroundActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // The shell harness uses this private marker only to avoid racing viewer sign-in with
            // source task upload/channel initialization. It contains no credential or media.
            delay(1_000)
            File(context.cacheDir, SOURCE_READY_FILE).writeText("ready")

            withTimeout(SOURCE_WAIT_MILLIS) {
                while (controller.peerDevices().none { it.displayName == completionMarker }) {
                    delay(250)
                }
            }
            controller.deleteAccount()
            deleted = true
            assertTrue(controller.state.value is CloudAccountState.SignedOut)
        } finally {
            File(context.cacheDir, SOURCE_READY_FILE).delete()
            instrumentation.runOnMainSync { sourceActivity?.finish() }
            if (!deleted && controller.state.value is CloudAccountState.SignedIn) {
                runCatching { controller.deleteAccount() }
            }
            if (controller.state.value is CloudAccountState.SignedIn) {
                runCatching { controller.signOut() }
            }
            runCatching { clearSourceFixture(context) }
        }
    }

    private suspend fun runViewer(email: String, password: String, completionMarker: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = context.appContainer
        val controller = container.accountController
        if (controller.state.value is CloudAccountState.SignedIn) controller.signOut()
        try {
            withTimeout(VIEWER_SIGN_IN_MILLIS) {
                while (true) {
                    val state = runCatching { controller.signIn(email, password) }.getOrNull()
                    if (state is CloudAccountState.SignedIn) break
                    delay(500)
                }
            }
            val pair = withTimeout(VIEWER_SYNC_MILLIS) {
                while (true) {
                    CloudBootstrap.coordinator(context).sync()
                    val state = container.monitors.state.value
                    val event = state.events.firstOrNull { candidate ->
                        candidate.isRemote &&
                            (candidate.fact as? MonitorEventFact.ObjectEpisode)?.condition ==
                            MonitorObjectEventCondition.APPEARED
                    }
                    val remote = event?.let { found ->
                        state.remote.firstOrNull { it.id == found.monitorId }
                    }
                    if (event != null && remote != null) return@withTimeout Pair(event, remote)
                    delay(250)
                }
                error("unreachable")
            }

            val event = pair.first
            val remote = pair.second
            val spec = RemoteSnapshotRequestSpec(event.id, event.monitorId, remote.monitoringDeviceId)
            val ready = withTimeout(VIEWER_TRANSFER_MILLIS) {
                var attempt = 0
                while (true) {
                    container.remoteSnapshots.request(spec, force = attempt > 0)
                    val result = withTimeoutOrNull(12_000) {
                        while (true) {
                            val current = container.remoteSnapshots.states.value[event.id]
                            if (current is RemoteSnapshotViewState.Ready) {
                                return@withTimeoutOrNull current
                            }
                            delay(50)
                        }
                        error("unreachable")
                    }
                    if (result != null) return@withTimeout result
                    attempt++
                }
                error("unreachable")
            }
            val cached = File(requireNotNull(Uri.parse(ready.privateUri).path))
            val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cached.absolutePath, bounds)
            assertTrue(cached.isFile && cached.length() in 1..120_000)
            assertTrue(bounds.outWidth in 1..720 && bounds.outHeight in 1..720)

            markViewerComplete(context, email, password, controller.currentDeviceId(), completionMarker)
            controller.signOut()
            assertTrue(!cached.exists())
            assertTrue(controller.state.value is CloudAccountState.SignedOut)
        } finally {
            if (controller.state.value is CloudAccountState.SignedIn) {
                runCatching { controller.signOut() }
            }
        }
    }

    private suspend fun markViewerComplete(
        context: Context,
        email: String,
        password: String,
        deviceId: String,
        completionMarker: String,
    ) {
        val configuration = CloudConfiguration.from(
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
        assertTrue(configuration is CloudConfiguration.Enabled)
        val client = SupabaseCloudClientFactory.create(context, configuration)
        client.account.awaitInitialization()
        if (client.account.state.value !is CloudAccountState.SignedIn) {
            assertTrue(client.account.signIn(email, password) is CloudAccountState.SignedIn)
        }
        client.dataPlane.upsertDevice(
            CloudDeviceWrite(
                deviceId = deviceId,
                displayName = completionMarker,
                androidApi = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a",
                memoryMb = 8_192,
                gmsAvailable = true,
                notificationsEnabled = false,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
    }

    private fun seedSourceEvent(context: Context) {
        val now = System.currentTimeMillis()
        val dao = MonitorDatabaseFactory.open(context).monitoringDao()
        assertTrue(
            dao.insertProductTask(
                LocalTaskEntity(
                    TASK_ID,
                    1,
                    "加密触发照片验收",
                    "reference_images",
                    "{\"type\":\"reference_images\",\"route_binding\":null}",
                    "{\"type\":\"presence_duration\",\"condition\":\"appears\"," +
                        "\"duration_ms\":1000}",
                    "[]",
                    now,
                    REFERENCE_PACKAGE_ID,
                    REFERENCE_PACKAGE_VERSION,
                    REFERENCE_MANIFEST_SHA256,
                ),
            ) != -1L,
        )
        dao.upsertResolvedSamplingConfig(
            ResolvedSamplingConfigEntity(
                TASK_ID,
                1,
                "2026.08.31.1",
                "visual_target",
                "reference_object_matching",
                "neural_reference_target_v1",
                "visual.reference.user_target",
                REFERENCE_PACKAGE_ID,
                REFERENCE_PACKAGE_VERSION,
                REFERENCE_MANIFEST_SHA256,
                "a".repeat(64),
                "b".repeat(64),
                100,
                100,
                2_000,
                true,
            ),
        )
        dao.upsertTask(TaskEntity(TASK_ID, 1, "hosted-snapshot-source", true))
        assertTrue(
            dao.insertEventAndOutbox(
                EventEntity(
                    EVENT_ID,
                    TASK_ID,
                    1,
                    EPISODE_ID,
                    1,
                    now,
                    "{\"type\":\"object_episode\",\"target_id\":\"relay-target\"," +
                        "\"condition\":\"appeared\",\"duration_ms\":0,\"count\":1}",
                    "发现目标",
                    0,
                    null,
                ),
                OutboxEntity(EVENT_ID, 0, now),
            ),
        )
        val snapshotUri =
            ReferenceEventSnapshotStore.openAppPrivate(context.filesDir).capture(
                TASK_ID,
                EVENT_ID,
                SourceFrame(
                    sourceSequence = 1,
                    monotonicTimeMillis = 1,
                    capturedAtEpochMillis = now,
                    width = 64,
                    height = 64,
                    rotationDegrees = 0,
                    cropRect = PixelRect(0, 0, 64, 64),
                    pixels = FramePixels.Rgb888(checkerboard(), 64 * 3),
                ),
            )
        assertTrue(snapshotUri.startsWith("file:"))
    }

    private fun clearSourceFixture(context: Context) {
        MonitorDatabaseFactory.open(context).monitoringDao().deleteProductTaskAndRuntime(TASK_ID)
        ReferenceEventSnapshotStore.openAppPrivate(context.filesDir).deleteTask(TASK_ID)
    }

    private fun checkerboard(): ByteArray = ByteArray(64 * 64 * 3).also { pixels ->
        for (y in 0 until 64) for (x in 0 until 64) {
            val offset = (y * 64 + x) * 3
            val light = ((x / 8 + y / 8) % 2 == 0)
            pixels[offset] = if (light) 0xE8.toByte() else 0x18.toByte()
            pixels[offset + 1] = if (light) 0x42.toByte() else 0xB8.toByte()
            pixels[offset + 2] = if (light) 0x24.toByte() else 0xD8.toByte()
        }
    }

    private companion object {
        const val SOURCE_READY_FILE = "hosted-snapshot-source-ready"
        const val TASK_ID = "01900000-0000-7000-8000-000000000071"
        const val EVENT_ID = "01900000-0000-7000-8000-000000000072"
        const val EPISODE_ID = "01900000-0000-7000-8000-000000000073"
        const val REFERENCE_PACKAGE_ID = "similarity_mediapipe_mobilenet_v3_large_v1"
        const val REFERENCE_PACKAGE_VERSION = "0.1.0-internal.15"
        const val REFERENCE_MANIFEST_SHA256 =
            "2319c397c685d3d9a9d9d57c6d4703a49f516d3e9f2e258548d23a5c87d91306"
        const val SOURCE_WAIT_MILLIS = 300_000L
        const val VIEWER_SIGN_IN_MILLIS = 60_000L
        const val VIEWER_SYNC_MILLIS = 60_000L
        const val VIEWER_TRANSFER_MILLIS = 60_000L
    }
}
