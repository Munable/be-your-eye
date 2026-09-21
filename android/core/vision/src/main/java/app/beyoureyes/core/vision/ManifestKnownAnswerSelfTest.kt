package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Observation
import java.io.File
import java.security.MessageDigest
import java.util.Base64

sealed interface ManifestKnownAnswerSelfTestResult {
    data object Passed : ManifestKnownAnswerSelfTestResult

    data class Failed(
        val errors: Set<RuntimeActivationError>,
    ) : ManifestKnownAnswerSelfTestResult {
        init {
            require(errors.isNotEmpty())
        }
    }
}

/**
 * Artifact-bound known-answer execution shared by package preparation and every reading runtime
 * start. Package IDs and model vendors never participate: the signed Manifest selects the finite
 * preprocess/runtime/adapter family.
 *
 * Known-answer pixels are already upright RGB and intentionally bypass field-image quality
 * rejection. Live camera frames continue to use [ManifestFrameQualityGates].
 */
object ManifestKnownAnswerSelfTestRunner {
    fun runReading(
        verifiedPackage: VerifiedModelPackage,
        artifactFilesByRole: Map<String, File>,
        buildChannel: BuildChannel,
        registry: RuntimeComponentRegistry,
        numberOfThreads: Int = ModelRuntimeRequest.DEFAULT_THREAD_COUNT,
        clock: NanoClock = SystemNanoClock,
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ): ManifestKnownAnswerSelfTestResult {
        val manifest = verifiedPackage.manifest
        if (manifest.runtimeFamily != RecipeFamily.READING_PIPELINE_V1) {
            return failed(RuntimeActivationError.MANIFEST_INVALID)
        }
        val primary = artifactFilesByRole["primary"]
            ?: return failed(RuntimeActivationError.ARTIFACT_ROLE_SET_MISMATCH)
        val creation = ModelPackageRuntimeFactory(
            registry = registry,
            normalizer = UprightRgbFrameNormalizer,
            qualityGate = AcceptAllQualityGate,
            clock = clock,
            nowEpochMillis = nowEpochMillis,
        ).create(
            ModelRuntimeRequest(
                verifiedPackage = verifiedPackage,
                artifactFile = primary,
                artifactFilesByRole = artifactFilesByRole,
                buildChannel = buildChannel,
                targetProfile = null,
                enforceAutomaticShapeGuard = false,
                numberOfThreads = numberOfThreads,
            ),
        )
        val runtime = when (creation) {
            is RuntimeCreationResult.Ready -> creation.runtime
            is RuntimeCreationResult.Unavailable -> return ManifestKnownAnswerSelfTestResult.Failed(
                creation.errors,
            )
        }
        return runtime.use {
            val spec = manifest.selfTest
                ?: return@use failed(RuntimeActivationError.MANIFEST_INVALID)
            val frame = spec.toSourceFrame(manifest.artifacts)
                ?: return@use failed(RuntimeActivationError.MANIFEST_INVALID)
            val processed = runtime.process(frame) as? RuntimeFrameResult.Processed
                ?: return@use failed(RuntimeActivationError.KNOWN_ANSWER_SELF_TEST_FAILED)
            if (spec.matches(processed.pipelineResult)) {
                ManifestKnownAnswerSelfTestResult.Passed
            } else {
                failed(RuntimeActivationError.KNOWN_ANSWER_SELF_TEST_FAILED)
            }
        }
    }

    private fun ReadingKnownAnswerSelfTest.toSourceFrame(
        artifacts: List<ArtifactComponent>,
    ): SourceFrame? {
        val boundArtifact = artifacts.singleOrNull { it.role == artifactRole } ?: return null
        if (boundArtifact.sha256 != artifactSha256) return null
        val bytes = runCatching { Base64.getDecoder().decode(input.rgb888Base64) }.getOrNull()
            ?: return null
        val expectedSize = input.width.toLong() * input.height.toLong() * RGB_CHANNELS.toLong()
        if (bytes.size.toLong() != expectedSize) return null
        if (bytes.sha256() != input.sha256) return null
        return SourceFrame(
            sourceSequence = 0,
            monotonicTimeMillis = 0,
            capturedAtEpochMillis = null,
            width = input.width,
            height = input.height,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, input.width, input.height),
            pixels = FramePixels.Rgb888(bytes, input.width * RGB_CHANNELS),
        )
    }

    private fun ReadingKnownAnswerSelfTest.matches(result: PipelineResult): Boolean {
        if (!result.adapterCompleted) return false
        val reading = result.observation as? Observation.Reading ?: return false
        return reading.text == expected.text &&
            reading.valueDecimal == expected.valueDecimal &&
            reading.unit == expected.unit &&
            reading.confidence >= expected.minimumConfidence
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun failed(
        error: RuntimeActivationError,
    ) = ManifestKnownAnswerSelfTestResult.Failed(setOf(error))

    private const val RGB_CHANNELS = 3
}
