package app.beyoureyes.monitor

import android.graphics.BitmapFactory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import app.beyoureyes.core.vision.ModelPackageRuntime
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.FixedHttpsRequest
import app.beyoureyes.core.data.FixedHttpsResponse
import app.beyoureyes.core.data.FixedHttpsTransport
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ModelPackageSlot
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.SupportedTask
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.VerifiedModelPackage
import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real signed package test for the generic reading family. The package identity is test data;
 * production preparation and runtime code contain no PP-OCR or vendor branch.
 */
@RunWith(AndroidJUnit4::class)
class RealSignedReadingPackageInstrumentedTest {
    @Test
    fun signedPackageDownloadsSelfTestsActivatesRestartsAndProducesTypedReading() = runBlocking {
        val fixture = loadFixture()
        val fixtureNow = fixtureNowEpochMillis(fixture)
        val catalog = SignedMetadataCodec.decodeAndVerifyCatalog(
            fixture.catalog,
            nowEpochMillis = fixtureNow,
        )
        val document = SignedMetadataCodec.decodeAndVerifyManifest(
            fixture.manifest,
            catalog,
            PACKAGE_ID,
            nowEpochMillis = fixtureNow,
        )
        assertEquals(catalog.documentSha256, sha256(fixture.catalog))
        assertEquals(document.documentSha256, sha256(fixture.manifest))
        val descriptors = document.manifest.artifacts.associateBy { it.role }
        assertEquals(descriptors.getValue("primary").sha256, sha256(fixture.primary))
        assertEquals(descriptors.getValue("locator").sha256, sha256(fixture.locator))
        assertEquals(descriptors.getValue("vocabulary").sha256, sha256(fixture.vocabulary))
        assertEquals(RecipeFamily.READING_PIPELINE_V1, document.manifest.runtimeFamily)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesRoot = File(context.cacheDir, "real-signed-reading-${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            var boundPointer: ModelPackagePointer? = null
            val coordinator = ModelPreparationCoordinator(
                appContext = context,
                filesDir = filesRoot,
                // This is a fixture transport URL, not the product BuildConfig
                // Catalog. It must coexist with the signed reference fixture.
                catalogUrl = CATALOG_URL,
                buildChannel = checkNotNull(BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL))
                    .also { assertEquals(BuildChannel.INTERNAL_EVALUATION, it) },
                device = ModelPreparationDevice(
                    androidApi = 36,
                    abi = "arm64-v8a",
                    marketedMemoryMb = 8_192,
                ),
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
                taskBinder = ModelPreparationTaskBinder { config ->
                    assertEquals(TASK_ID, config.taskId)
                    assertEquals(1L, config.taskRevision)
                    boundPointer = config.packagePointer
                    true
                },
                transport = transport(fixture, document),
                nowEpochMillis = { fixtureNow },
                productAccess = { ProductAccessDecision.GRANTED },
            )

            val ready = coordinator.prepareTask(TASK_ID) { progress ->
                (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
            } as ModelPreparationResult.Ready
            assertEquals(PACKAGE_ID, ready.packageId)
            assertEquals(READING_SLOT, ready.slot)
            assertEquals(ready.pointer, boundPointer)

            val store = ModelPackageStores.open(filesRoot)
            assertEquals(PACKAGE_ID, store.activationState(READING_SLOT).current?.identity?.packageId)
            val leaseResult = ModelPackageStores.open(filesRoot).acquireRuntimeLease(
                ready.pointer,
                fixtureNow,
            ) as ModelPackageRuntimeLeaseResult.Acquired
            leaseResult.lease.use { lease ->
                assertTrue(lease.gateReport.selfTestPassed)
                assertEquals(document.documentSha256, lease.canonicalManifest.sha256)
                val manifest = SignedMetadataCodec.decodeStoredManifestDocument(
                    lease.canonicalManifest.copyBytes(),
                    lease.descriptor.identity,
                    lease.descriptor.canonicalManifestSha256,
                )
                val created = ModelPackageRuntimeFactory(
                    registry = ManifestRuntimeComponents.registry(),
                    normalizer = UprightRgbFrameNormalizer,
                    qualityGate = ManifestFrameQualityGates.forManifest(manifest),
                    nowEpochMillis = { fixtureNow },
                ).create(
                    ModelRuntimeRequest(
                        verifiedPackage = VerifiedModelPackage(
                            manifest = manifest,
                            catalogEntryActive = true,
                            catalogSignatureValid = true,
                            catalogManifestSha256Matches = true,
                            manifestSignatureValid = true,
                            artifactSha256Valid = true,
                            licenseTextSha256Valid = true,
                        ),
                        artifactFile = lease.artifactFilesByRole.getValue("primary"),
                        artifactFilesByRole = lease.artifactFilesByRole,
                        buildChannel = BuildChannel.INTERNAL_EVALUATION,
                        targetProfile = null,
                        // The signed known-answer input is already the recognizer crop. Keep
                        // this direct runtime assertion aligned with ManifestKnownAnswerSelfTest
                        // instead of asking the production full-scene locator to rediscover it.
                        enforceAutomaticShapeGuard = false,
                    ),
                ) as RuntimeCreationResult.Ready
                created.runtime.use { runtime ->
                    val result = runtime.process(manifestSelfTestFrame(manifest))
                        as RuntimeFrameResult.Processed
                    val observation = result.pipelineResult.observation
                    assertTrue("expected a typed reading, got $observation", observation is Observation.Reading)
                    val reading = observation as Observation.Reading
                    assertEquals("-12.3", reading.text)
                    assertEquals("-12.3", reading.valueDecimal)
                    reportSuppliedReadingCrops(runtime)
                }
            }

            val previewIdentity = ReadingPreviewIdentity(
                taskId = TASK_ID,
                taskRevision = 1,
                packagePointer = ready.pointer,
                roi = NormalizedRect(0f, 0f, 1f, 1f),
            )
            ReadingPreviewCoordinator.openAppPrivate(
                filesDir = filesRoot,
                identity = previewIdentity,
                nowEpochMillis = { fixtureNow },
            ).use { preview ->
                // A user-drawn full-frame ROI is the product path for an already-cropped
                // display. It intentionally bypasses automatic line localization.
                preview.updateManualScanRegion(previewIdentity, previewIdentity.roi)
                repeat(3) { index ->
                    val sourceFrame = manifestSelfTestFrame(
                        document.manifest,
                        sourceSequence = index.toLong() + 1,
                    )
                    preview.accept(
                        previewIdentity,
                        NormalizedRoiSourceFrame(previewIdentity.roi, sourceFrame),
                    )
                }
                val waiting = preview.state as ReadingPreviewStatus.AwaitingConfirmation
                assertEquals("-12.3", waiting.value.text)
                assertEquals("-12.3", waiting.value.valueDecimal)
                assertTrue(preview.confirm(previewIdentity) is ReadingPreviewStatus.Confirmed)
            }
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    // Optional, private feedback fixtures. No user pixels or recognized text go into logs/APKs.
    private fun reportSuppliedReadingCrops(runtime: ModelPackageRuntime) {
        if (InstrumentationRegistry.getArguments().getString("runReadingFeedbackProbe") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "reading-feedback")
        val output = JsonArray()
        input.listFiles { file -> file.extension == "png" }.orEmpty().sortedBy { it.name }.forEachIndexed { index, file ->
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rgb = ByteArray(pixels.size * 3)
            pixels.forEachIndexed { i, pixel ->
                rgb[i * 3] = (pixel shr 16).toByte()
                rgb[i * 3 + 1] = (pixel shr 8).toByte()
                rgb[i * 3 + 2] = pixel.toByte()
            }
            val frame = SourceFrame(
                index.toLong() + 10, (index.toLong() + 10) * 500, null,
                bitmap.width, bitmap.height, 0, PixelRect(0, 0, bitmap.width, bitmap.height),
                FramePixels.Rgb888(rgb, bitmap.width * 3),
            )
            bitmap.recycle()
            runtime.resetSampling()
            runtime.updateManualReadingScanRegion(NormalizedRect(0f, 0f, 1f, 1f))
            val result = runtime.process(frame) as RuntimeFrameResult.Processed
            output.add(JsonObject().apply {
                addProperty("case", file.nameWithoutExtension)
                addProperty("available", result.pipelineResult.observation is Observation.Reading)
                when (val observation = result.pipelineResult.observation) {
                    is Observation.Reading -> {
                        addProperty("confidence", observation.confidence)
                        addProperty("static_value_matches", if (file.name.startsWith("210005")) observation.valueDecimal == "700.805" else null)
                    }
                    is Observation.Unavailable -> addProperty("diagnostic", observation.diagnosticCode)
                    else -> error("unexpected observation")
                }
            })
        }
        assertTrue("feedback crops were not supplied", output.size() > 0)
        File(context.cacheDir, "reading-feedback-result.json").writeText(output.toString())
    }

    private fun transport(
        fixture: Fixture,
        document: app.beyoureyes.core.data.VerifiedManifestDocument,
    ) = FixedHttpsTransport { request: FixedHttpsRequest ->
        assertEquals(null, request.rangeStartBytes)
        val artifactUrls = document.manifest.artifacts.associate { it.role to it.url }
        val responses = mapOf(
            CATALOG_URL to fixture.catalog,
            document.catalogEntry.manifestUrl to fixture.manifest,
            artifactUrls.getValue("primary") to fixture.primary,
            artifactUrls.getValue("locator") to fixture.locator,
            artifactUrls.getValue("vocabulary") to fixture.vocabulary,
        )
        val bytes = responses[request.url] ?: error("unexpected fixture URL: ${request.url}")
        FixedHttpsResponse(200, request.url, bytes.size.toLong(), null, ByteArrayInputStream(bytes))
    }

    private fun manifestSelfTestFrame(
        manifest: app.beyoureyes.core.vision.ModelPackageManifest,
        sourceSequence: Long = 0,
    ): SourceFrame {
        val spec = checkNotNull(manifest.selfTest)
        val bytes = Base64.getDecoder().decode(spec.input.rgb888Base64)
        return SourceFrame(
            sourceSequence = sourceSequence,
            monotonicTimeMillis = sourceSequence * 500,
            capturedAtEpochMillis = null,
            width = spec.input.width,
            height = spec.input.height,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, spec.input.width, spec.input.height),
            pixels = FramePixels.Rgb888(bytes, spec.input.width * 3),
        )
    }

    private fun placeholderFrame(): SourceFrame {
        val bytes = ByteArray(3) { 127 }
        return SourceFrame(0, 0, null, 1, 1, 0, PixelRect(0, 0, 1, 1), FramePixels.Rgb888(bytes, 3))
    }

    private fun loadFixture(): Fixture {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun required(name: String): ByteArray {
            val loaded = runCatching { assets.open("real-signed-reading/$name").use { it.readBytes() } }
            assumeTrue(
                "Run with -PinternalReadingReleaseDir=<signed output directory>",
                loaded.isSuccess,
            )
            return loaded.getOrThrow()
        }
        return Fixture(
            catalog = required("catalog.json"),
            manifest = required("manifest.json"),
            primary = required("primary.onnx"),
            locator = required("locator.onnx"),
            vocabulary = required("vocabulary.json"),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun fixtureNowEpochMillis(fixture: Fixture): Long =
        JsonParser.parseString(fixture.catalog.decodeToString())
            .asJsonObject.get("issued_at").asString
            .let { Instant.parse(it).plusSeconds(1).toEpochMilli() }

    private data class Fixture(
        val catalog: ByteArray,
        val manifest: ByteArray,
        val primary: ByteArray,
        val locator: ByteArray,
        val vocabulary: ByteArray,
    )

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000080"
        val READING_SLOT = ModelPackageSlot("structured_reading_none")
        const val CATALOG_URL = "https://catalog.beyoureyes.test/internal-reading/catalog.json"
        const val PACKAGE_ID = "numeric_reader_ppocrv6_medium_v1"
    }
}
