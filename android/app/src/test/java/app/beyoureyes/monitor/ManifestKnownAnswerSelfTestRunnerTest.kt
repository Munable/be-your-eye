package app.beyoureyes.monitor

import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.vision.AdapterRegistration
import app.beyoureyes.core.vision.ArtifactExecutionSession
import app.beyoureyes.core.vision.ArtifactSessionRegistration
import app.beyoureyes.core.vision.BackendRegistration
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.CanonicalFrame
import app.beyoureyes.core.vision.InferenceBackend
import app.beyoureyes.core.vision.InputPreprocessor
import app.beyoureyes.core.vision.LetterboxTransform
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestResult
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestRunner
import app.beyoureyes.core.vision.ManifestOutputAdapterFactory
import app.beyoureyes.core.vision.ManifestPreprocessorFactory
import app.beyoureyes.core.vision.ManifestBackendFactory
import app.beyoureyes.core.vision.OutputAdapter
import app.beyoureyes.core.vision.OutputRole
import app.beyoureyes.core.vision.PreparedInput
import app.beyoureyes.core.vision.PreprocessorRegistration
import app.beyoureyes.core.vision.QualityGateResult
import app.beyoureyes.core.vision.RawTensor
import app.beyoureyes.core.vision.RawTensorOutput
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.RuntimeActivationError
import app.beyoureyes.core.vision.RuntimeComponentRegistry
import app.beyoureyes.core.vision.RuntimeKind
import app.beyoureyes.core.vision.TargetMode
import app.beyoureyes.core.vision.TensorElementType
import app.beyoureyes.core.vision.VerifiedModelPackage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManifestKnownAnswerSelfTestRunnerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `known answer uses upright RGB and bypasses only the live quality gate`() {
        val fixture = fixture()
        val stages = mutableListOf<String>()
        val result = run(fixture, registry("12.3", stages))

        assertEquals(ManifestKnownAnswerSelfTestResult.Passed, result)
        requireMonitoringKnownAnswerSelfTest(result)
        assertEquals(listOf("preprocess", "runtime", "adapter"), stages)
        val input = checkNotNull(fixture.manifest.selfTest).input
        val canonical = CanonicalFrame(
            sourceSequence = 0,
            monotonicTimeMillis = 0,
            capturedAtEpochMillis = null,
            width = input.width,
            height = input.height,
            rgb888 = java.util.Base64.getDecoder().decode(input.rgb888Base64),
        )
        assertTrue(
            ManifestFrameQualityGates.forManifest(fixture.manifest).evaluate(canonical) is
                QualityGateResult.Rejected,
        )
    }

    @Test
    fun `adapter drift fails a fresh monitoring start as runtime incompatible`() {
        val result = run(fixture(), registry("12.4", mutableListOf()))
        val failed = result as ManifestKnownAnswerSelfTestResult.Failed

        assertEquals(setOf(RuntimeActivationError.KNOWN_ANSWER_SELF_TEST_FAILED), failed.errors)
        val exception = runCatching { requireMonitoringKnownAnswerSelfTest(failed) }
            .exceptionOrNull() as MonitoringRuntimeInitializationException
        assertEquals(MonitoringRuntimeInitializationError.RUNTIME_INCOMPATIBLE, exception.error)
        assertEquals(failed.errors, exception.activationErrors)
    }

    @Test
    fun `fresh known answer execution rehashes every leased artifact`() {
        val fixture = fixture()
        fixture.artifacts.getValue("primary").appendBytes(byteArrayOf(9))
        val result = run(fixture, registry("12.3", mutableListOf()))
            as ManifestKnownAnswerSelfTestResult.Failed

        assertTrue(RuntimeActivationError.ARTIFACT_SIZE_MISMATCH in result.errors)
        assertTrue(RuntimeActivationError.ARTIFACT_SHA256_MISMATCH in result.errors)
    }

    private fun run(
        fixture: ReadingFixture,
        registry: RuntimeComponentRegistry,
    ): ManifestKnownAnswerSelfTestResult = ManifestKnownAnswerSelfTestRunner.runReading(
        verifiedPackage = VerifiedModelPackage(
            manifest = fixture.manifest,
            catalogEntryActive = true,
            catalogSignatureValid = true,
            catalogManifestSha256Matches = true,
            manifestSignatureValid = true,
            artifactSha256Valid = true,
            licenseTextSha256Valid = true,
        ),
        artifactFilesByRole = fixture.artifacts,
        // These tests isolate the artifact-bound known-answer path. The current signed fixture is
        // intentionally Internal-only and must not be promoted merely to exercise this runner.
        buildChannel = BuildChannel.INTERNAL_EVALUATION,
        registry = registry,
        nowEpochMillis = { 1_786_000_000_000L },
    )

    private fun registry(
        readingText: String,
        stages: MutableList<String>,
    ) = RuntimeComponentRegistry(
        preprocessors = listOf(
            PreprocessorRegistration(
                preprocessId = "ppocr_det_rec_bgr_nchw_float32_v1",
                recipeFamilies = setOf(RecipeFamily.READING_PIPELINE_V1),
                factory = ManifestPreprocessorFactory {
                    InputPreprocessor { frame ->
                        stages += "preprocess"
                        assertArrayEquals(
                            byteArrayOf(0, 0, 0, -1, -1, -1),
                            frame.rgb888,
                        )
                        PreparedInput(
                            bytes = byteArrayOf(1),
                            shape = listOf(1),
                            elementType = TensorElementType.UINT8,
                            transform = LetterboxTransform(2, 1, 2, 1, 1f, 1f, 0f, 0f),
                        )
                    }
                },
            ),
        ),
        adapters = listOf(
            AdapterRegistration(
                adapterId = "structured_reading_ctc_v2",
                recipeFamilies = setOf(RecipeFamily.READING_PIPELINE_V1),
                outputSchemaIds = setOf("structured_reading_v2"),
                factory = ManifestOutputAdapterFactory {
                    OutputAdapter { _, _, frame ->
                        stages += "adapter"
                        Observation.Reading(
                            text = readingText,
                            valueDecimal = readingText,
                            stable = false,
                            sourceSequence = frame.sourceSequence,
                            confidence = 0.8f,
                        )
                    }
                },
            ),
        ),
        backends = listOf(
            BackendRegistration(
                runtimeKind = RuntimeKind.LITERT,
                recipeFamilies = setOf(RecipeFamily.READING_PIPELINE_V1),
                targetModes = setOf(TargetMode.NONE),
                factory = ManifestBackendFactory {
                    InferenceBackend {
                        stages += "runtime"
                        RawTensorOutput(
                            listOf(
                                RawTensor(
                                    name = "ctc_logits",
                                    bytes = byteArrayOf(1),
                                    shape = listOf(1),
                                    elementType = TensorElementType.FLOAT32,
                                ),
                            ),
                        )
                    }
                },
            ),
        ),
        artifactSessions = listOf(
            ArtifactSessionRegistration(RuntimeKind.LITERT) { context ->
                object : ArtifactExecutionSession {
                    override val artifactRole = context.artifact.role

                    override fun run(inputsByTensorName: Map<String, RawTensor>): Map<String, RawTensor> {
                        stages += "runtime"
                        return context.outputSpecs.associate { output ->
                            output.tensorName to RawTensor(
                                name = output.tensorName,
                                bytes = byteArrayOf(1),
                                shape = output.runtimeShape,
                                elementType = TensorElementType.FLOAT32,
                            )
                        }
                    }

                    override fun close() = Unit
                }
            },
            ArtifactSessionRegistration(RuntimeKind.ONNX) { context ->
                object : ArtifactExecutionSession {
                    override val artifactRole = context.artifact.role

                    override fun run(inputsByTensorName: Map<String, RawTensor>): Map<String, RawTensor> =
                        context.outputSpecs.associate { output ->
                            val values = output.runtimeShape.fold(1, Int::times)
                            val tensorValues = FloatArray(values) { index ->
                                if (output.role == OutputRole.TEXT_PROBABILITY_MAP) {
                                    val width = output.runtimeShape.last()
                                    val height = output.runtimeShape[output.runtimeShape.size - 2]
                                    val x = index % width
                                    val y = (index / width) % height
                                    if (x in (width / 5)..(width * 4 / 5) &&
                                        y in (height / 2 - 1)..(height / 2 + 1)
                                    ) 1f else 0f
                                } else {
                                    1f
                                }
                            }
                            val bytes = ByteBuffer.allocate(values * Float.SIZE_BYTES)
                                .order(ByteOrder.nativeOrder())
                                .apply { tensorValues.forEach(::putFloat) }
                                .array()
                            output.tensorName to RawTensor(
                                name = output.tensorName,
                                bytes = bytes,
                                shape = output.runtimeShape,
                                elementType = TensorElementType.FLOAT32,
                            )
                        }

                    override fun close() = Unit
                }
            },
        ),
    )

    private fun fixture(): ReadingFixture {
        val signature = java.util.Base64.getEncoder().encodeToString(ByteArray(64))
        val manifestBytes = repoFile("test-vectors/valid/model-manifest-reading.json")
            .readText()
            .replace("\"value\": \"AA==\"", "\"value\": \"$signature\"")
            .toByteArray()
        val decoded = SignedMetadataCodec.decodeStoredManifestDocument(manifestBytes)
        val bytesByRole = mapOf(
            "primary" to "package-neutral-reading-runtime".toByteArray(),
            "locator" to "package-neutral-reading-locator".toByteArray(),
            "vocabulary" to repoFile("test-vectors/valid/ctc-vocabulary-numeric-13.json").readBytes(),
        )
        val files = bytesByRole.mapValues { (role, bytes) ->
            temporaryFolder.newFile("$role.bin").apply { writeBytes(bytes) }
        }
        val artifacts = decoded.artifacts.map { artifact ->
            val bytes = bytesByRole.getValue(artifact.role)
            artifact.copy(sha256 = bytes.sha256(), sizeBytes = bytes.size.toLong())
        }
        val primarySha = artifacts.single { it.role == "primary" }.sha256
        val manifest = decoded.copy(
            artifacts = artifacts,
            selfTest = checkNotNull(decoded.selfTest).copy(artifactSha256 = primarySha),
        )
        assertTrue(manifest.structuralErrors().isEmpty())
        return ReadingFixture(manifest, files)
    }

    private fun repoFile(path: String): File {
        var directory = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(directory, "AGENTS.md").isFile || !File(directory, "test-vectors").isDirectory) {
            directory = checkNotNull(directory.parentFile) { "repository root not found" }
        }
        return File(directory, path)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private data class ReadingFixture(
        val manifest: app.beyoureyes.core.vision.ModelPackageManifest,
        val artifacts: Map<String, File>,
    )
}
