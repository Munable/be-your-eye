package app.beyoureyes.monitor

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.data.FixedHttpsResponse
import app.beyoureyes.core.data.FixedHttpsTransport
import app.beyoureyes.core.data.UrlConnectionFixedHttpsTransport
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.monitor.feature.monitoring.LoadingScreen
import app.beyoureyes.monitor.feature.monitoring.MonitorCameraState
import app.beyoureyes.monitor.feature.monitoring.modelPreparationMessage
import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import java.io.IOException
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated files + real signed public package. Never touches the owner's installed models/tasks. */
@RunWith(AndroidJUnit4::class)
class ModelDownloadFlowInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun liveDownloadRequiresConsentCancelsResumesAndReusesOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveModelDownload") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "model-download-flow-${System.nanoTime()}").apply { mkdirs() }
        val artifacts = AtomicInteger()
        val resumes = AtomicInteger()
        val failNextArtifact = AtomicBoolean(false)
        val network = UrlConnectionFixedHttpsTransport()
        val transport = FixedHttpsTransport { request ->
            val isArtifact = request.url != BuildConfig.MODEL_CATALOG_URL && !request.url.contains("/manifests/")
            if (isArtifact) {
                artifacts.incrementAndGet()
                if (failNextArtifact.compareAndSet(true, false)) throw IOException("Injected test network interruption")
                if (request.rangeStartBytes != null) resumes.incrementAndGet()
            }
            val response = network.execute(request)
            FixedHttpsResponse(response.statusCode, response.finalUrl, response.contentLengthBytes, response.contentRange,
                object : FilterInputStream(response.body) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        // Slow only this test's stream enough to inspect and cancel a live download.
                        if (isArtifact) Thread.sleep(3)
                        return super.read(bytes, offset, length)
                    }
                })
        }
        fun coordinator(online: Boolean) = ModelPreparationCoordinator(
            appContext = context, filesDir = root, catalogUrl = BuildConfig.MODEL_CATALOG_URL,
            buildChannel = BuildChannel.INTERNAL_EVALUATION, device = currentModelPreparationDevice(context),
            targetResolver = ModelPreparationTargetResolver { null },
            taskBinder = ModelPreparationTaskBinder { true }, transport = transport,
            networkAvailable = { online }, productAccess = { ProductAccessDecision.GRANTED },
        )
        val state = mutableStateOf(MonitorCameraState.Loading())
        val fontScale = mutableStateOf(1f)
        fun progress(progress: ModelPreparationProgress) {
            state.value = MonitorCameraState.Loading(modelPreparationMessage(progress), progress)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                BeYourEyeTheme { LoadingScreen(state.value, onBack = {}) }
            }
        }
        fun capture(name: String) {
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.filesDir, "download-ux-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        try {
            val first = async(Dispatchers.Default) {
                coordinator(true).prepareTransientReading(UuidV7.generate(), ::progress)
            }
            compose.waitUntil(30_000) { state.value.progress is ModelPreparationProgress.AwaitingDownload }
            val request = (state.value.progress as ModelPreparationProgress.AwaitingDownload).request
            assertTrue(request.totalBytes > 0)
            assertEquals(0, artifacts.get())
            capture("consent")
            fontScale.value = 1.5f
            compose.onNodeWithTag("model_download_confirm").performScrollTo()
            capture("large-text")
            fontScale.value = 1f
            compose.onNodeWithTag("model_download_confirm").performScrollTo().performClick()
            compose.waitUntil(30_000) {
                (state.value.progress as? ModelPreparationProgress.Downloading)?.downloadedBytes?.let { it > 131_072 } == true
            }
            capture("progress")
            first.cancelAndJoin()
            val stoppedRequests = artifacts.get()
            assertTrue(stoppedRequests > 0)

            failNextArtifact.set(true)
            val interrupted = async(Dispatchers.Default) {
                coordinator(true).prepareTransientReading(UuidV7.generate(), ::progress)
            }
            compose.waitUntil(30_000) { state.value.progress is ModelPreparationProgress.AwaitingDownload }
            compose.onNodeWithTag("model_download_confirm").performScrollTo().performClick()
            val failed = interrupted.await()
            assertTrue(failed is TransientReadingPreparationResult.Unavailable)
            assertEquals(context.getString(R.string.model_not_ready_retry),
                (failed as TransientReadingPreparationResult.Unavailable).userMessage)
            val requestsAfterFailure = artifacts.get()

            val retry = async(Dispatchers.Default) {
                coordinator(true).prepareTransientReading(UuidV7.generate(), ::progress)
            }
            compose.waitUntil(30_000) { state.value.progress is ModelPreparationProgress.AwaitingDownload }
            assertEquals(requestsAfterFailure, artifacts.get())
            compose.onNodeWithTag("model_download_confirm").performScrollTo().performClick()
            val ready = retry.await()
            assertTrue("retry must activate the verified package: $ready", ready is TransientReadingPreparationResult.Ready)
            assertTrue("retry must reuse the partial file via Range", resumes.get() > 0)
            val completedRequests = artifacts.get()
            var reuseSeen = false
            val reused = coordinator(false).prepareTransientReading(UuidV7.generate(), onProgress = {
                assertTrue("installed model must not request another download", it !is ModelPreparationProgress.AwaitingDownload)
                if (it is ModelPreparationProgress.UsingDownloaded) reuseSeen = true
                progress(it)
            })
            assertTrue(reused is TransientReadingPreparationResult.Ready)
            assertTrue(reuseSeen)
            assertEquals(completedRequests, artifacts.get())
            progress(ModelPreparationProgress.UsingDownloaded(request.modelName))
            capture("cached")
            File(context.filesDir, "download-ux-result.json").writeText(
                """{"total_bytes":${request.totalBytes},"artifact_requests":$completedRequests,"range_resume_requests":${resumes.get()},"consent_before_download":true,"cancelled":true,"injected_network_failure_retried":true,"offline_reuse":true}""",
            )
        } finally {
            coroutineContext.cancelChildren()
            root.deleteRecursively()
        }
    }
}
