package app.beyoureyes.monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.SupportedTask
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live public-R2 download plus exact installed-package offline reuse on the connected device. */
@RunWith(AndroidJUnit4::class)
class LiveR2ReadingPackageInstrumentedTest {
    @Test
    fun publicCatalogDownloadsActivatesAndThenReusesExactPackageOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString(RUN_ARGUMENT) == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "live-r2-reading-${System.nanoTime()}").apply { mkdirs() }
        try {
            val device = currentModelPreparationDevice(context)
            val onlineTaskId = UuidV7.generate()
            val onlineStarted = System.nanoTime()
            val onlineResult = coordinator(root, device, networkAvailable = true)
                .prepareTask(onlineTaskId) { progress ->
                    (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
                }
            assertTrue("online preparation result=$onlineResult", onlineResult is ModelPreparationResult.Ready)
            val online = onlineResult as ModelPreparationResult.Ready
            val onlineMillis = (System.nanoTime() - onlineStarted) / 1_000_000L
            assertEquals(PACKAGE_ID, online.packageId)
            assertEquals(PACKAGE_VERSION, online.packageVersion)
            assertTrue(online.pointer.canonicalManifestSha256.matches(Regex("^[0-9a-f]{64}$")))

            val store = ModelPackageStores.open(root)
            val acquired = store.acquireRuntimeLease(online.pointer, System.currentTimeMillis())
                as ModelPackageRuntimeLeaseResult.Acquired
            acquired.lease.use { lease ->
                assertTrue(lease.gateReport.selfTestPassed)
                assertEquals(3, lease.artifactFilesByRole.size)
                assertEquals(
                    LOCATOR_SHA256,
                    lease.descriptor.artifacts.single { it.role == "locator" }.sha256,
                )
            }

            val offlineStarted = System.nanoTime()
            val offlineTaskId = UuidV7.generate()
            val offlineResult = coordinator(root, device, networkAvailable = false)
                .prepareTask(offlineTaskId) { progress ->
                    (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
                }
            assertTrue("offline preparation result=$offlineResult", offlineResult is ModelPreparationResult.Ready)
            val offline = offlineResult as ModelPreparationResult.Ready
            val offlineMillis = (System.nanoTime() - offlineStarted) / 1_000_000L
            assertEquals(online.pointer, offline.pointer)
            assertEquals(online.slot, offline.slot)

            val report = JSONObject()
                .put("catalog_url", BuildConfig.MODEL_CATALOG_URL)
                .put("package_id", online.packageId)
                .put("package_version", online.packageVersion)
                .put("manifest_sha256", online.pointer.canonicalManifestSha256)
                .put("locator_sha256", LOCATOR_SHA256)
                .put("online_download_activation_ms", onlineMillis)
                .put("offline_exact_reuse_ms", offlineMillis)
                .put("device_model", android.os.Build.MODEL)
                .put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull())
                .toString()
            File(context.filesDir, RESULT_FILE).writeText(report)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                android.os.Bundle().apply { putString("live_r2_reading", report) },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun coordinator(
        root: File,
        device: ModelPreparationDevice,
        networkAvailable: Boolean,
    ) = ModelPreparationCoordinator(
        appContext = InstrumentationRegistry.getInstrumentation().targetContext,
        filesDir = root,
        catalogUrl = BuildConfig.MODEL_CATALOG_URL,
        buildChannel = checkNotNull(BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)),
        device = device,
        targetResolver = ModelPreparationTargetResolver {
            ModelPreparationTarget(
                taskRevision = 1,
                capabilityId = "structured_reading",
                requiredTask = SupportedTask.STRUCTURED_READING,
                requiredRuleType = "reading_threshold",
                requiredRuntimeFamily = RecipeFamily.READING_PIPELINE_V1,
                targetProfile = null,
                referenceImageProvider = null,
                selfTestFrame = placeholderFrame(),
            )
        },
        taskBinder = ModelPreparationTaskBinder { true },
        networkAvailable = { networkAvailable },
    )

    private fun placeholderFrame(): SourceFrame {
        val bytes = byteArrayOf(127, 127, 127)
        return SourceFrame(
            sourceSequence = 0,
            monotonicTimeMillis = 0,
            capturedAtEpochMillis = null,
            width = 1,
            height = 1,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, 1, 1),
            pixels = FramePixels.Rgb888(bytes, 3),
        )
    }

    private companion object {
        const val RUN_ARGUMENT = "runLiveR2ReadingAcceptance"
        const val PACKAGE_ID = "numeric_reader_ppocrv6_medium_v1"
        const val PACKAGE_VERSION = "0.1.0-internal.14"
        const val LOCATOR_SHA256 =
            "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8"
        const val RESULT_FILE = "live-r2-reading-result.json"
    }
}
