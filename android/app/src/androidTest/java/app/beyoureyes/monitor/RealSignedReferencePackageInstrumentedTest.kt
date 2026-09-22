package app.beyoureyes.monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.FixedHttpsRequest
import app.beyoureyes.core.data.FixedHttpsResponse
import app.beyoureyes.core.data.FixedHttpsTransport
import app.beyoureyes.core.data.MetadataVerificationException
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ModelPackageSlot
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntime
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import app.beyoureyes.core.vision.ReferenceImageAsset
import app.beyoureyes.core.vision.ReferenceImageMetadata
import app.beyoureyes.core.vision.ReferenceImageProvider
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.SupportedTask
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.VerifiedModelPackage
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealSignedReferencePackageInstrumentedTest {
    @Test
    fun currentReleaseKeysDriveProductPreparationFullGraphRestartAndCacheLifecycle() = runBlocking {
        val fixture = loadFixture()
        val fixtureNow = fixtureNowEpochMillis(fixture)
        val verifiedCatalog = SignedMetadataCodec.decodeAndVerifyCatalog(
            fixture.catalog,
            nowEpochMillis = fixtureNow,
        )
        val verifiedManifest = SignedMetadataCodec.decodeAndVerifyManifest(
            fixture.manifest,
            verifiedCatalog,
            PACKAGE_ID,
            nowEpochMillis = fixtureNow,
        )
        assertEquals(
            setOf("primary", "object_crop", "embedder", "similarity_head"),
            verifiedManifest.manifest.artifacts.map { it.role }.toSet(),
        )
        verifiedManifest.manifest.artifacts.forEach { descriptor ->
            assertEquals(descriptor.sha256, sha256(fixture.artifactsByRole.getValue(descriptor.role)))
        }

        val references = referenceAssets()
        val target = target(references)
        val provider = ReferenceImageProvider { metadata ->
            references.singleOrNull { it.localAssetId == metadata.localAssetId }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesRoot = File(context.cacheDir, "real-signed-e2e-${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            var boundPointer: ModelPackagePointer? = null
            val coordinator = coordinator(
                filesRoot,
                fixture,
                target,
                provider,
                ModelPreparationTaskBinder { config ->
                    assertEquals(REFERENCE_TASK_ID, config.taskId)
                    assertEquals(1L, config.taskRevision)
                    boundPointer = config.packagePointer
                    true
                },
            )

            val result = coordinator.prepareTask(REFERENCE_TASK_ID) { progress ->
                (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
            }

            val ready = result as ModelPreparationResult.Ready
            assertEquals(PACKAGE_ID, ready.packageId)
            assertEquals(ready.pointer, boundPointer)
            val store = ModelPackageStores.open(filesRoot)
            assertEquals(PACKAGE_ID, store.activationState(ready.slot).current?.identity?.packageId)
            val cacheDirectory = File(filesRoot, ReferenceEmbeddingCaches.DIRECTORY_NAME)
            val cacheFiles = cacheDirectory.listFiles().orEmpty().filter { it.name.endsWith(".refembed") }
            val prototypeCount = verifiedManifest.manifest.inputs
                .single { it.role == app.beyoureyes.core.vision.InputRole.REFERENCE_FEATURES }
                .runtimeShape.first()
            assertEquals(references.size * prototypeCount, cacheFiles.size)
            val cacheTimestamps = cacheFiles.associate { it.name to it.lastModified() }

            val firstLease = store.acquireRuntimeLease(
                ready.pointer,
                fixtureNow,
            ) as ModelPackageRuntimeLeaseResult.Acquired
            firstLease.lease.use { lease ->
                assertEquals(sha256(fixture.manifest), lease.canonicalManifest.sha256)
                verifiedManifest.manifest.artifacts.forEach { descriptor ->
                    assertEquals(
                        descriptor.sha256,
                        sha256(lease.artifactFilesByRole.getValue(descriptor.role).readBytes()),
                    )
                }
                assertTrue(lease.gateReport.selfTestPassed)
            }

            // Reopen the app-private store as a new Service process would after restart.
            val reopened = ModelPackageStores.open(filesRoot)
            val reopenedLease = reopened.acquireRuntimeLease(
                ready.pointer,
                fixtureNow,
            ) as ModelPackageRuntimeLeaseResult.Acquired
            reopenedLease.lease.use { lease ->
                val runtime = createRuntime(lease, target, provider, filesRoot, fixtureNow)
                runtime.use {
                    val processed = runtime.process(sourceFrame(references.first())) as RuntimeFrameResult.Processed
                    assertTrue(processed.pipelineResult.observation is Observation.State)
                }
            }
            assertEquals(
                cacheTimestamps,
                cacheDirectory.listFiles().orEmpty()
                    .filter { it.name.endsWith(".refembed") }
                    .associate { it.name to it.lastModified() },
            )

            val referenceHashes = references.mapTo(linkedSetOf()) { it.contentSha256 }
            ReferenceEmbeddingCaches.openAppPrivate(filesRoot).invalidateReferences(referenceHashes)
            assertFalse(
                cacheDirectory.listFiles().orEmpty().any { file ->
                    referenceHashes.any { hash -> file.name.startsWith("$hash--") }
                },
            )
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun currentKeySignatureAndArtifactHashFailuresRemainFailClosed() = runBlocking {
        val fixture = loadFixture()
        val root = JsonParser.parseString(fixture.catalog.toString(Charsets.UTF_8)).asJsonObject
        val signature = root.getAsJsonObject("signature")
        val originalValue = signature.get("value").asString
        signature.addProperty(
            "value",
            (if (originalValue.first() == 'A') "B" else "A") + originalValue.drop(1),
        )
        val error = assertThrows(MetadataVerificationException::class.java) {
            SignedMetadataCodec.decodeAndVerifyCatalog(
                root.toString().toByteArray(),
                nowEpochMillis = fixtureNowEpochMillis(fixture),
            )
        }
        assertEquals("invalid_signature", error.code)

        val references = referenceAssets()
        val target = target(references)
        val provider = ReferenceImageProvider { metadata ->
            references.singleOrNull { it.localAssetId == metadata.localAssetId }
        }
        val corruptedPrimary = fixture.artifactsByRole.getValue("primary").copyOf().also { bytes ->
            bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 1).toByte()
        }
        val corrupted = fixture.copy(
            artifactsByRole = fixture.artifactsByRole + ("primary" to corruptedPrimary),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesRoot = File(context.cacheDir, "real-signed-bad-hash-${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            val result = coordinator(filesRoot, corrupted, target, provider)
                .prepareTask(BAD_ARTIFACT_TASK_ID) { progress ->
                    (progress as? ModelPreparationProgress.AwaitingDownload)?.request?.confirm()
                }

            val unavailable = result as ModelPreparationResult.Unavailable
            assertEquals(ModelPreparationFailure.PACKAGE_DOWNLOAD_FAILED, unavailable.failure)
            assertEquals(
                null,
                ModelPackageStores.open(filesRoot).activationState(REFERENCE_SLOT).current,
            )
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    private fun coordinator(
        filesRoot: File,
        fixture: Fixture,
        target: TargetProfile.ReferenceImages,
        provider: ReferenceImageProvider,
        taskBinder: ModelPreparationTaskBinder = ModelPreparationTaskBinder { true },
    ) = ModelPreparationCoordinator(
        appContext = InstrumentationRegistry.getInstrumentation().targetContext,
        filesDir = filesRoot,
        // The signed reference and reading fixtures intentionally use separate
        // in-memory Catalog URLs. Keeping the URL local to each fixture allows
        // both generic package families to run in one instrumentation APK.
        catalogUrl = CATALOG_URL,
        buildChannel = checkNotNull(BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)).also {
            assertEquals(BuildChannel.INTERNAL_EVALUATION, it)
        },
        device = ModelPreparationDevice(
            androidApi = 36,
            abi = "arm64-v8a",
            marketedMemoryMb = 8_192,
        ),
        targetResolver = ModelPreparationTargetResolver {
            ModelPreparationTarget(
                taskRevision = 1,
                capabilityId = "visual_target",
                requiredTask = SupportedTask.VISUAL_TARGET,
                targetProfile = target,
                referenceImageProvider = provider,
                selfTestFrame = sourceFrame(referenceAssets().first()),
            )
        },
        taskBinder = taskBinder,
        transport = transport(fixture),
        nowEpochMillis = { fixtureNowEpochMillis(fixture) },
    )

    private fun transport(fixture: Fixture): FixedHttpsTransport {
        val catalog = JsonParser.parseString(fixture.catalog.decodeToString()).asJsonObject
        val manifestUrl = catalog.getAsJsonArray("packages")
            .map { it.asJsonObject }
            .single { it.get("package_id").asString == PACKAGE_ID }
            .get("manifest_url").asString
        val manifest = JsonParser.parseString(fixture.manifest.decodeToString()).asJsonObject
        val artifactUrlsByRole = manifest.getAsJsonArray("artifacts").associate { element ->
            val artifact = element.asJsonObject
            artifact.get("role").asString to artifact.get("url").asString
        }
        val responses = buildMap {
            put(CATALOG_URL, fixture.catalog)
            put(manifestUrl, fixture.manifest)
            artifactUrlsByRole.forEach { (role, url) ->
                put(url, fixture.artifactsByRole.getValue(role))
            }
        }
        return FixedHttpsTransport { request: FixedHttpsRequest ->
            assertEquals(null, request.rangeStartBytes)
            val bytes = responses[request.url] ?: error("unexpected fixture URL: ${request.url}")
            FixedHttpsResponse(
                statusCode = 200,
                finalUrl = request.url,
                contentLengthBytes = bytes.size.toLong(),
                contentRange = null,
                body = ByteArrayInputStream(bytes),
            )
        }
    }

    private fun createRuntime(
        lease: app.beyoureyes.core.data.ModelPackageRuntimeLease,
        target: TargetProfile.ReferenceImages,
        provider: ReferenceImageProvider,
        filesRoot: File,
        nowEpochMillis: Long,
    ): ModelPackageRuntime {
        val manifest = SignedMetadataCodec.decodeStoredManifestDocument(
            documentBytes = lease.canonicalManifest.copyBytes(),
            expectedIdentity = lease.descriptor.identity,
            expectedSha256 = lease.descriptor.canonicalManifestSha256,
        )
        val created = ModelPackageRuntimeFactory(
            registry = ManifestRuntimeComponents.registry(
                referenceImageProvider = provider,
                referenceEmbeddingCache = ReferenceEmbeddingCaches.openAppPrivate(filesRoot),
            ),
            normalizer = UprightRgbFrameNormalizer,
            qualityGate = ManifestFrameQualityGates.forManifest(manifest),
            nowEpochMillis = { nowEpochMillis },
        ).create(
            ModelRuntimeRequest(
                verifiedPackage = VerifiedModelPackage(
                    manifest = manifest,
                    catalogEntryActive = lease.gateReport.catalogEntryActive,
                    catalogSignatureValid = lease.gateReport.catalogSignatureValid,
                    catalogManifestSha256Matches = lease.gateReport.catalogManifestHashValid,
                    manifestSignatureValid = lease.gateReport.manifestSignatureValid,
                    artifactSha256Valid = true,
                    licenseTextSha256Valid = lease.gateReport.licenseGatePassed,
                ),
                artifactFile = lease.artifactFilesByRole.getValue("primary"),
                artifactFilesByRole = lease.artifactFilesByRole,
                buildChannel = BuildChannel.INTERNAL_EVALUATION,
                targetProfile = target,
            ),
        )
        assertTrue(created.toString(), created is RuntimeCreationResult.Ready)
        return (created as RuntimeCreationResult.Ready).runtime
    }

    private fun loadFixture(): Fixture {
        // The immutable signed fixture is generated into the androidTest APK only;
        // read it from the instrumentation context rather than the product APK.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun required(name: String): ByteArray {
            val loaded = runCatching { assets.open("real-signed-reference/$name").use { it.readBytes() } }
            assumeTrue(
                "Run with -PinternalReferenceReleaseDir=<signed output directory>",
                loaded.isSuccess,
            )
            return loaded.getOrThrow()
        }
        return Fixture(
            catalog = required("catalog.json"),
            manifest = required("manifest.json"),
            artifactsByRole = mapOf(
                "primary" to required("primary.tflite"),
                "object_crop" to required("object-crop.json"),
                "embedder" to required("embedder.tflite"),
                "similarity_head" to required("similarity-head.json"),
            ),
        )
    }

    private fun fixtureNowEpochMillis(fixture: Fixture): Long {
        val issuedAt = JsonParser.parseString(fixture.catalog.decodeToString())
            .asJsonObject.get("issued_at").asString
        return Instant.parse(issuedAt).plusSeconds(1).toEpochMilli()
    }

    private fun referenceAssets() = listOf(
        patternAsset("asset-1", 224, 224, 0, 0, 245),
        patternAsset("asset-2", 320, 180, 16, 0, 225),
        patternAsset("asset-3", 180, 320, 0, 16, 205),
        patternAsset("asset-4", 512, 256, -12, 8, 235),
        patternAsset("asset-5", 256, 512, 12, -8, 215),
        patternAsset("asset-6", 420, 280, -8, -12, 195),
        patternAsset("asset-7", 280, 420, 8, 12, 175),
    )

    private fun target(references: List<ReferenceImageAsset>) = TargetProfile.ReferenceImages(
        targetId = "fixture_target",
        images = references.mapIndexed { index, asset ->
            ReferenceImageMetadata(
                referenceId = "ref-${index + 1}",
                localAssetId = asset.localAssetId,
                contentSha256 = asset.contentSha256,
                width = asset.width,
                height = asset.height,
            )
        },
    )

    private fun patternAsset(
        id: String,
        width: Int,
        height: Int,
        shiftX: Int,
        shiftY: Int,
        foreground: Int,
    ): ReferenceImageAsset {
        val pixels = ByteArray(width * height * 3) { 24 }
        val boxWidth = (width * 4 / 7).coerceAtLeast(1)
        val boxHeight = (height * 4 / 7).coerceAtLeast(1)
        val left = ((width - boxWidth) / 2 + shiftX).coerceIn(0, width - boxWidth)
        val top = ((height - boxHeight) / 2 + shiftY).coerceIn(0, height - boxHeight)
        for (y in top until top + boxHeight) {
            for (x in left until left + boxWidth) {
                val offset = (y * width + x) * 3
                pixels[offset] = foreground.toByte()
                pixels[offset + 1] = (foreground - 7).toByte()
                pixels[offset + 2] = (foreground - 14).toByte()
            }
        }
        return ReferenceImageAsset(id, sha256(pixels), width, height, pixels)
    }

    private fun sourceFrame(asset: ReferenceImageAsset) = SourceFrame(
        sourceSequence = 0,
        monotonicTimeMillis = 0,
        capturedAtEpochMillis = null,
        width = asset.width,
        height = asset.height,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, asset.width, asset.height),
        pixels = FramePixels.Rgb888(asset.rgb888, asset.width * 3),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Fixture(
        val catalog: ByteArray,
        val manifest: ByteArray,
        val artifactsByRole: Map<String, ByteArray>,
    )

    private companion object {
        const val REFERENCE_TASK_ID = "01900000-0000-7000-8000-000000000070"
        const val BAD_ARTIFACT_TASK_ID = "01900000-0000-7000-8000-000000000071"
        val REFERENCE_SLOT = ModelPackageSlot("visual_target_reference_images")
        const val CATALOG_URL = "https://catalog.beyoureyes.test/internal-reference/catalog.json"
        const val PACKAGE_ID = "similarity_mediapipe_mobilenet_v3_large_v1"
    }
}
