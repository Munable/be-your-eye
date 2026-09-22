package app.beyoureyes.monitor

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.FixedHttpsRequest
import app.beyoureyes.core.data.FixedHttpsResponse
import app.beyoureyes.core.data.FixedHttpsTransport
import app.beyoureyes.core.data.ModelPackageSlot
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.data.SignedMetadataHttpClient
import app.beyoureyes.core.data.VerifiedCatalogCache
import app.beyoureyes.core.data.VerifiedManifestCache
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.ReferenceImageAsset
import app.beyoureyes.core.vision.ReferenceImageMetadata
import app.beyoureyes.core.vision.ReferenceImageProvider
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.SupportedTask
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.VerifiedModelPackage
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Installs all three runtime slots from the exact signed three-package Catalog fixture. */
@RunWith(AndroidJUnit4::class)
class OfflineProductPackageSeedInstrumentedTest {
    @Test
    fun seedExactSignedPackagesAndCatalogCache() = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("seedOfflinePackages") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val fixture = loadFixture()
        val catalogUrl = BuildConfig.MODEL_CATALOG_URL
        require(catalogUrl.startsWith("https://") && catalogUrl.endsWith("/catalog.json"))
        targetContext.filesDir.resolve("signed-manifest-cache-v1").deleteRecursively()
        val transport = fixtureTransport(fixture, catalogUrl)
        val buildChannel = checkNotNull(BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL))
        assertEquals(BuildChannel.COMMUNITY, buildChannel)
        val device = currentModelPreparationDevice(targetContext)
        val now = System.currentTimeMillis()

        val references = referenceAssets()
        val provider = ReferenceImageProvider { metadata ->
            references.singleOrNull { it.localAssetId == metadata.localAssetId }
        }
        val referenceProfile = TargetProfile.ReferenceImages(
            targetId = "offline_fixture_target",
            images = references.mapIndexed { index, asset ->
                ReferenceImageMetadata(
                    referenceId = "offline-ref-${index + 1}",
                    localAssetId = asset.localAssetId,
                    contentSha256 = asset.contentSha256,
                    width = asset.width,
                    height = asset.height,
                )
            },
        )
        val referenceReady = ModelPreparationCoordinator(
            appContext = targetContext,
            filesDir = targetContext.filesDir,
            catalogUrl = catalogUrl,
            buildChannel = buildChannel,
            device = device,
            targetResolver = ModelPreparationTargetResolver {
                ModelPreparationTarget(
                    taskRevision = 1,
                    capabilityId = "visual_target",
                    requiredTask = SupportedTask.VISUAL_TARGET,
                    requiredRuleType = "presence_duration",
                    requiredRuntimeFamily = RecipeFamily.SIMILARITY_MATCH_V1,
                    targetProfile = referenceProfile,
                    referenceImageProvider = provider,
                    selfTestFrame = sourceFrame(references.first()),
                )
            },
            taskBinder = ModelPreparationTaskBinder { true },
            transport = transport,
            nowEpochMillis = { now },
        ).prepareTask(REFERENCE_TASK_ID) { progress ->
            (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
        }
        assertTrue("reference fixture preparation failed: $referenceReady", referenceReady is ModelPreparationResult.Ready)

        val readingReady = ModelPreparationCoordinator(
            appContext = targetContext,
            filesDir = targetContext.filesDir,
            catalogUrl = catalogUrl,
            buildChannel = buildChannel,
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
            transport = transport,
            nowEpochMillis = { now },
        ).prepareTask(READING_TASK_ID) { progress ->
            (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
        }
        assertTrue("reading fixture preparation failed: $readingReady", readingReady is ModelPreparationResult.Ready)

        val objectReady = ModelPreparationCoordinator(
            appContext = targetContext,
            filesDir = targetContext.filesDir,
            catalogUrl = catalogUrl,
            buildChannel = buildChannel,
            device = device,
            targetResolver = ModelPreparationTargetResolver { objectTarget() },
            taskBinder = ModelPreparationTaskBinder { true },
            transport = transport,
            nowEpochMillis = { now },
        ).prepareTask(OBJECT_TASK_ID) { progress ->
            (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
        }
        assertTrue("object fixture preparation failed: $objectReady", objectReady is ModelPreparationResult.Ready)

        val store = ModelPackageStores.open(targetContext.filesDir)
        assertEquals(
            REFERENCE_PACKAGE_ID,
            store.activationState(ModelPackageSlot("visual_target_reference_images"))
                .current?.identity?.packageId,
        )
        assertEquals(
            READING_PACKAGE_ID,
            store.activationState(ModelPackageSlot("structured_reading_none"))
                .current?.identity?.packageId,
        )
        assertEquals(
            OBJECT_PACKAGE_ID,
            store.activationState(ModelPackageSlot("visual_target_object_class"))
                .current?.identity?.packageId,
        )

        val verifiedCatalog = SignedMetadataCodec.decodeAndVerifyCatalog(
            documentBytes = fixture.catalog,
            nowEpochMillis = now,
        )
        val commonEntry = checkNotNull(verifiedCatalog.activePackage(OBJECT_PACKAGE_ID))
        assertArrayEquals(
            fixture.objectManifest,
            VerifiedManifestCache(targetContext.filesDir, commonEntry).readBytesOrNull(),
        )
        val objectPointer = (objectReady as ModelPreparationResult.Ready).pointer
        val acquired = store.acquireRuntimeLease(objectPointer, now)
            as? ModelPackageRuntimeLeaseResult.Acquired
            ?: error("Lite2 runtime lease failed: $objectPointer")
        val inferenceNanos = acquired.lease.use { lease ->
            val verified = SignedMetadataCodec.decodeAndVerifyManifest(
                documentBytes = lease.canonicalManifest.copyBytes(),
                catalog = verifiedCatalog,
                packageId = OBJECT_PACKAGE_ID,
                nowEpochMillis = now,
            )
            val created = ModelPackageRuntimeFactory(
                registry = ManifestRuntimeComponents.registry(),
                normalizer = UprightRgbFrameNormalizer,
                qualityGate = ManifestFrameQualityGates.forManifest(verified.manifest),
                nowEpochMillis = { now },
            ).create(
                ModelRuntimeRequest(
                    verifiedPackage = VerifiedModelPackage(
                        manifest = verified.manifest,
                        catalogEntryActive = true,
                        catalogSignatureValid = true,
                        catalogManifestSha256Matches = true,
                        manifestSignatureValid = true,
                        artifactSha256Valid = true,
                        licenseTextSha256Valid = true,
                    ),
                    artifactFile = lease.artifactFilesByRole.getValue("primary"),
                    artifactFilesByRole = lease.artifactFilesByRole,
                    buildChannel = buildChannel,
                    targetProfile = checkNotNull(objectTarget().targetProfile),
                ),
            )
            val runtime = (created as? RuntimeCreationResult.Ready)?.runtime
                ?: error("Lite2 runtime creation failed: $created")
            runtime.use {
                repeat(LITE2_WARMUP_COUNT) { index ->
                    runtime.resetSampling()
                    check(runtime.process(objectSelfTestFrame(index.toLong())) is RuntimeFrameResult.Processed)
                }
                List(LITE2_SAMPLE_COUNT) { index ->
                    runtime.resetSampling()
                    val processed = runtime.process(
                        objectSelfTestFrame((index + LITE2_WARMUP_COUNT).toLong()),
                    ) as? RuntimeFrameResult.Processed ?: error("Lite2 frame was skipped")
                    check(processed.pipelineResult.adapterCompleted)
                    processed.pipelineResult.timings.inferenceNanos
                }
            }
        }
        val lite2P95Nanos = inferenceNanos.sorted()[(inferenceNanos.size * 95 - 1) / 100]
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("lite2_p95_inference_ms", "%.3f".format(lite2P95Nanos / 1_000_000.0))
                putString("lite2_inference_samples_ms", inferenceNanos.joinToString(",") {
                    "%.3f".format(it / 1_000_000.0)
                })
            },
        )
        assertTrue(
            "Lite2 p95 inference ${lite2P95Nanos / 1_000_000.0} ms exceeds 500 ms",
            lite2P95Nanos <= LITE2_P95_LIMIT_NANOS,
        )
        targetContext.filesDir.resolve("signed-manifest-cache-v1").deleteRecursively()
        val offlineTransport = FixedHttpsTransport { throw IOException("offline fixture") }
        val storedObjectReady = ModelPreparationCoordinator(
            appContext = targetContext,
            filesDir = targetContext.filesDir,
            catalogUrl = catalogUrl,
            buildChannel = buildChannel,
            device = device,
            targetResolver = ModelPreparationTargetResolver { objectTarget() },
            taskBinder = ModelPreparationTaskBinder { true },
            transport = offlineTransport,
            nowEpochMillis = { now },
            networkAvailable = { false },
        ).prepareTask(STORED_OBJECT_TASK_ID) { progress ->
            (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
        }
        assertTrue(
            "stored object fixture preparation failed: $storedObjectReady",
            storedObjectReady is ModelPreparationResult.Ready,
        )
        assertArrayEquals(
            fixture.objectManifest,
            VerifiedManifestCache(targetContext.filesDir, commonEntry).readBytesOrNull(),
        )

        val offlineDefinitions = SignedObjectCatalogProvider(
            catalogUrl = catalogUrl,
            expectedBuildChannel = buildChannel,
            metadataClient = SignedMetadataHttpClient(offlineTransport),
            cache = VerifiedCatalogCache(targetContext.filesDir, catalogUrl),
            manifestCacheFactory = { entry ->
                VerifiedManifestCache(targetContext.filesDir, entry)
            },
            nowEpochMillis = { now },
        ).load()
        assertTrue(offlineDefinitions.any { it.targetId == "cat" })
        assertEquals(80, offlineDefinitions.size)
    }

    private fun loadFixture(): Fixture {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun required(path: String): ByteArray = assets.open(path).use { it.readBytes() }
        val referenceCatalog = required("real-signed-reference/catalog.json")
        val readingCatalog = required("real-signed-reading/catalog.json")
        val objectCatalog = required("real-signed-object/catalog.json")
        require(referenceCatalog.contentEquals(readingCatalog))
        require(referenceCatalog.contentEquals(objectCatalog))
        return Fixture(
            catalog = referenceCatalog,
            referenceManifest = required("real-signed-reference/manifest.json"),
            referencePrimary = required("real-signed-reference/primary.tflite"),
            referenceObjectCrop = required("real-signed-reference/object-crop.json"),
            referenceEmbedder = required("real-signed-reference/embedder.tflite"),
            referenceHead = required("real-signed-reference/similarity-head.json"),
            readingManifest = required("real-signed-reading/manifest.json"),
            readingPrimary = required("real-signed-reading/primary.onnx"),
            readingLocator = required("real-signed-reading/locator.onnx"),
            readingVocabulary = required("real-signed-reading/vocabulary.json"),
            objectManifest = required("real-signed-object/manifest.json"),
            objectPrimary = required("real-signed-object/primary.tflite"),
        )
    }

    private fun fixtureTransport(fixture: Fixture, catalogUrl: String): FixedHttpsTransport {
        val catalog = JsonParser.parseString(fixture.catalog.decodeToString()).asJsonObject
        val manifestUrls = catalog.getAsJsonArray("packages").associate { element ->
            val entry = element.asJsonObject
            entry.get("package_id").asString to entry.get("manifest_url").asString
        }
        val referenceManifest = JsonParser.parseString(fixture.referenceManifest.decodeToString()).asJsonObject
        val readingManifest = JsonParser.parseString(fixture.readingManifest.decodeToString()).asJsonObject
        val objectManifest = JsonParser.parseString(fixture.objectManifest.decodeToString()).asJsonObject
        fun artifactUrls(root: com.google.gson.JsonObject): Map<String, String> =
            root.getAsJsonArray("artifacts").associate { element ->
                val artifact = element.asJsonObject
                artifact.get("role").asString to artifact.get("url").asString
            }
        val referenceArtifacts = artifactUrls(referenceManifest)
        val readingArtifacts = artifactUrls(readingManifest)
        val objectArtifacts = artifactUrls(objectManifest)
        val responses = mapOf(
            catalogUrl to fixture.catalog,
            manifestUrls.getValue(REFERENCE_PACKAGE_ID) to fixture.referenceManifest,
            manifestUrls.getValue(READING_PACKAGE_ID) to fixture.readingManifest,
            referenceArtifacts.getValue("primary") to fixture.referencePrimary,
            referenceArtifacts.getValue("object_crop") to fixture.referenceObjectCrop,
            referenceArtifacts.getValue("embedder") to fixture.referenceEmbedder,
            referenceArtifacts.getValue("similarity_head") to fixture.referenceHead,
            readingArtifacts.getValue("primary") to fixture.readingPrimary,
            readingArtifacts.getValue("locator") to fixture.readingLocator,
            readingArtifacts.getValue("vocabulary") to fixture.readingVocabulary,
            manifestUrls.getValue(OBJECT_PACKAGE_ID) to fixture.objectManifest,
            objectArtifacts.getValue("primary") to fixture.objectPrimary,
        )
        return FixedHttpsTransport { request: FixedHttpsRequest ->
            val bytes = responses[request.url] ?: error("unexpected fixture URL: ${request.url}")
            val offset = request.rangeStartBytes ?: 0L
            require(offset in 0 until bytes.size.toLong())
            val body = bytes.copyOfRange(offset.toInt(), bytes.size)
            FixedHttpsResponse(
                statusCode = if (offset == 0L) 200 else 206,
                finalUrl = request.url,
                contentLengthBytes = body.size.toLong(),
                contentRange = if (offset == 0L) null else {
                    "bytes $offset-${bytes.lastIndex}/${bytes.size}"
                },
                body = ByteArrayInputStream(body),
            )
        }
    }

    private fun referenceAssets(): List<ReferenceImageAsset> = listOf(
        patternAsset("offline-asset-1", 0, 0, 245),
        patternAsset("offline-asset-2", 16, 0, 225),
        patternAsset("offline-asset-3", 0, 16, 205),
    )

    private fun patternAsset(
        id: String,
        shiftX: Int,
        shiftY: Int,
        foreground: Int,
    ): ReferenceImageAsset {
        val size = 224
        val pixels = ByteArray(size * size * 3) { 24 }
        for (y in 48 + shiftY until 176 + shiftY) {
            for (x in 48 + shiftX until 176 + shiftX) {
                if (x !in 0 until size || y !in 0 until size) continue
                val offset = (y * size + x) * 3
                pixels[offset] = foreground.toByte()
                pixels[offset + 1] = (foreground - 7).toByte()
                pixels[offset + 2] = (foreground - 14).toByte()
            }
        }
        return ReferenceImageAsset(id, sha256(pixels), size, size, pixels)
    }

    private fun sourceFrame(asset: ReferenceImageAsset): SourceFrame = SourceFrame(
        sourceSequence = 0,
        monotonicTimeMillis = 0,
        capturedAtEpochMillis = null,
        width = asset.width,
        height = asset.height,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, asset.width, asset.height),
        pixels = FramePixels.Rgb888(asset.rgb888, asset.width * 3),
    )

    private fun placeholderFrame(): SourceFrame = SourceFrame(
        sourceSequence = 0,
        monotonicTimeMillis = 0,
        capturedAtEpochMillis = null,
        width = 1,
        height = 1,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, 1, 1),
        pixels = FramePixels.Rgb888(ByteArray(3) { 127 }, 3),
    )

    private fun objectSelfTestFrame(sourceSequence: Long = 0): SourceFrame {
        val size = 320
        val rgb = ByteArray(size * size * 3)
        repeat(size) { y ->
            repeat(size) { x ->
                val value = ((x * 255 / (size - 1)) xor (y * 127 / (size - 1))).coerceIn(0, 255)
                val offset = (y * size + x) * 3
                rgb[offset] = value.toByte()
                rgb[offset + 1] = ((value + 37) and 0xff).toByte()
                rgb[offset + 2] = ((255 - value) and 0xff).toByte()
            }
        }
        return SourceFrame(
            sourceSequence = sourceSequence,
            monotonicTimeMillis = sourceSequence * 500,
            capturedAtEpochMillis = null,
            width = size,
            height = size,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, size, size),
            pixels = FramePixels.Rgb888(rgb, size * 3),
        )
    }

    private fun objectTarget() = ModelPreparationTarget(
        taskRevision = 1,
        capabilityId = "visual_target",
        requiredTask = SupportedTask.VISUAL_TARGET,
        requiredRuleType = "presence_duration",
        requiredRuntimeFamily = RecipeFamily.OBJECT_DETECTION_V1,
        targetProfile = TargetProfile.ObjectClass("cat", "猫", "cat"),
        referenceImageProvider = null,
        selfTestFrame = objectSelfTestFrame(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Fixture(
        val catalog: ByteArray,
        val referenceManifest: ByteArray,
        val referencePrimary: ByteArray,
        val referenceObjectCrop: ByteArray,
        val referenceEmbedder: ByteArray,
        val referenceHead: ByteArray,
        val readingManifest: ByteArray,
        val readingPrimary: ByteArray,
        val readingLocator: ByteArray,
        val readingVocabulary: ByteArray,
        val objectManifest: ByteArray,
        val objectPrimary: ByteArray,
    )

    private companion object {
        const val REFERENCE_PACKAGE_ID = "similarity_mediapipe_mobilenet_v3_large_v1"
        const val READING_PACKAGE_ID = "numeric_reader_ppocrv6_medium_v1"
        const val OBJECT_PACKAGE_ID = "efficientdet_lite2_object_v1"
        const val REFERENCE_TASK_ID = "01900000-0000-7000-8000-000000000091"
        const val READING_TASK_ID = "01900000-0000-7000-8000-000000000092"
        const val OBJECT_TASK_ID = "01900000-0000-7000-8000-000000000093"
        const val STORED_OBJECT_TASK_ID = "01900000-0000-7000-8000-000000000094"
        const val LITE2_WARMUP_COUNT = 3
        const val LITE2_SAMPLE_COUNT = 10
        const val LITE2_P95_LIMIT_NANOS = 500_000_000L
    }
}
