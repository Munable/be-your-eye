package app.beyoureyes.monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.VerifiedModelPackage
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs one frame through any signed object_detection_v1 package using the shared runtime. */
@RunWith(AndroidJUnit4::class)
class SignedObjectPackageSanityInstrumentedTest {
    @Test
    fun signedPackageLoadsAndProcessesOneFrameThroughTheGenericRuntime() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runSignedObjectPackageSanity") == "true")
        val packageId = requireNotNull(arguments.getString("packageId"))
        val targetId = requireNotNull(arguments.getString("targetId"))
        val fixture = loadFixture()
        val now = JsonParser.parseString(fixture.catalog.decodeToString())
            .asJsonObject.get("issued_at").asString
            .let { java.time.Instant.parse(it).plusSeconds(1).toEpochMilli() }
        val catalog = SignedMetadataCodec.decodeAndVerifyCatalog(fixture.catalog, nowEpochMillis = now)
        val document = SignedMetadataCodec.decodeAndVerifyManifest(
            fixture.manifest,
            catalog,
            packageId,
            nowEpochMillis = now,
        )
        val manifest = document.manifest
        assertEquals("object_detection_v1", manifest.runtimeFamily.wireValue)
        assertEquals(
            manifest.artifacts.single { it.role == "primary" }.sha256,
            sha256(fixture.primary),
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "object-sanity-${System.nanoTime()}").apply { mkdirs() }
        try {
            val primary = File(root, "primary.tflite").apply { writeBytes(fixture.primary) }
            val created = ModelPackageRuntimeFactory(
                registry = ManifestRuntimeComponents.registry(),
                normalizer = UprightRgbFrameNormalizer,
                qualityGate = ManifestFrameQualityGates.forManifest(manifest),
                nowEpochMillis = { now },
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
                    artifactFile = primary,
                    artifactFilesByRole = mapOf("primary" to primary),
                    buildChannel = BuildChannel.INTERNAL_EVALUATION,
                    targetProfile = TargetProfile.ObjectClass(targetId, targetId, targetId),
                    samplingPolicyOverride = FrameSamplingPolicy(
                        requireNotNull(manifest.parameterProfile.samplingPolicy.minimumIntervalMillis),
                    ),
                ),
            ) as? RuntimeCreationResult.Ready
                ?: error("signed object package did not create the generic runtime")

            created.runtime.use { runtime ->
                val result = runtime.process(sourceFrame()) as? RuntimeFrameResult.Processed
                    ?: error("signed object package skipped its sanity frame")
                assertTrue(result.pipelineResult.adapterCompleted)
                assertTrue(result.pipelineResult.observation is Observation.Detections)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun loadFixture(): Fixture {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun required(name: String): ByteArray {
            val loaded = runCatching { assets.open("real-signed-object/$name").use { it.readBytes() } }
            assumeTrue(
                "Build the test APK with one signed object package fixture",
                loaded.isSuccess,
            )
            return loaded.getOrThrow()
        }
        return Fixture(required("catalog.json"), required("manifest.json"), required("primary.tflite"))
    }

    private fun sourceFrame(): SourceFrame {
        val size = 320
        val pixels = ByteArray(size * size * 3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val value = ((x * 255 / (size - 1)) xor (y * 127 / (size - 1))) and 0xff
                val offset = (y * size + x) * 3
                pixels[offset] = value.toByte()
                pixels[offset + 1] = ((value + 37) and 0xff).toByte()
                pixels[offset + 2] = (255 - value).toByte()
            }
        }
        return SourceFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 1_000,
            capturedAtEpochMillis = null,
            width = size,
            height = size,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, size, size),
            pixels = FramePixels.Rgb888(pixels, size * 3),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Fixture(
        val catalog: ByteArray,
        val manifest: ByteArray,
        val primary: ByteArray,
    )
}
