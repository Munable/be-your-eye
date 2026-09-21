package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectDetectionClassMapTest {
    @Test
    fun `verified Manifest class map resolves without a package-specific allowlist`() {
        val resolved = ObjectDetectionClassMap.provider.resolve(classMap())

        assertEquals("cat", resolved?.labelsByRawClassId?.get(16))
        assertEquals("狗", resolved?.targetsByRawClassId?.get(17)?.labelZhCn)
        assertEquals("苹果", resolved?.targetsByRawClassId?.get(52)?.labelZhCn)
    }

    @Test
    fun `class map rejects malformed unsigned structure while accepting signed identity changes`() {
        val spec = classMap()
        assertEquals(
            "new-signed-map",
            ObjectDetectionClassMap.provider.resolve(spec.copy(identity = "new-signed-map"))?.identity,
        )
        assertNull(ObjectDetectionClassMap.provider.resolve(spec.copy(sha256 = "invalid")))
        assertNull(ObjectDetectionClassMap.provider.resolve(spec.copy(classIdBase = 1)))
        assertNull(
            ObjectDetectionClassMap.provider.resolve(
                spec.copy(targets = spec.targets + spec.targets.first()),
            ),
        )
        val conflicting = spec.targets[1].copy(aliases = setOf("猫"))
        assertNull(ObjectDetectionClassMap.provider.resolve(spec.copy(targets = spec.targets + conflicting.copy(rawClassId = 99, targetId = "other"))))
    }

    @Test
    fun `detector adapter filters raw classes to the selected stable target`() {
        val manifest = objectManifest()
        val adapter = ManifestSsdDetectionAdapter(
            manifest = manifest,
            targetProfile = TargetProfile.ObjectClass("cat", "猫", "cat"),
            classMapProvider = ObjectDetectionClassMap.provider,
        )
        val observation = adapter.toObservation(
            output = detectionOutput(
                classes = floatArrayOf(16f, 17f),
                scores = floatArrayOf(0.95f, 0.96f),
            ),
            transform = LetterboxTransform(320, 320, 320, 320, 1f, 1f, 0f, 0f),
            frame = CanonicalFrame(
                sourceSequence = 1,
                monotonicTimeMillis = 1,
                capturedAtEpochMillis = null,
                width = 320,
                height = 320,
                rgb888 = ByteArray(320 * 320 * 3),
            ),
        )

        assertTrue(observation is Observation.Detections)
        assertEquals(listOf("cat"), (observation as Observation.Detections).items.map { it.label })
    }

    private fun detectionOutput(classes: FloatArray, scores: FloatArray): RawTensorOutput {
        fun floats(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .apply { values.forEach(::putFloat) }
            .array()
        return RawTensorOutput(
            listOf(
                RawTensor("boxes", floats(floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f)), listOf(1, 2, 4), TensorElementType.FLOAT32),
                RawTensor("classes", floats(classes), listOf(1, 2), TensorElementType.FLOAT32),
                RawTensor("scores", floats(scores), listOf(1, 2), TensorElementType.FLOAT32),
                RawTensor("count", floats(floatArrayOf(2f)), listOf(1), TensorElementType.FLOAT32),
            ),
        )
    }

    private fun objectManifest() = ModelPackageManifest(
        schemaVersion = "3.0",
        packageId = "object_fixture",
        packageVersion = "1.0.0",
        runtimeFamily = RecipeFamily.OBJECT_DETECTION_V1,
        supportedTasks = setOf(SupportedTask.VISUAL_TARGET),
        promptModes = setOf(TargetMode.OBJECT_CLASS),
        modelSource = source("model"),
        weightsSource = source("weights"),
        codeSource = source("code"),
        exportToolSource = source("export"),
        license = ModelPackageLicense("Apache-2.0", "d".repeat(64), LicenseReviewStatus.APPROVED, "2026-08-01T00:00:00Z", "review", true, true, false),
        artifacts = listOf(ArtifactComponent("primary", RuntimeKind.LITERT, ArtifactMediaType.LITERT, "https://example.com/model.tflite", "a".repeat(64), 1)),
        inputs = listOf(InputComponent(InputRole.IMAGE, "primary", 0, "image", TensorDataType.UINT8, listOf(1, 320, 320, 3), InputQuantization(QuantizationMode.PER_TENSOR, 1.0, 0), TensorLayout.NHWC, ColorSpace.RGB)),
        outputs = listOf(
            OutputComponent(OutputRole.DETECTION_BOXES, "primary", 0, "boxes", TensorDataType.FLOAT32, listOf(1, 2, 4)),
            OutputComponent(OutputRole.DETECTION_CLASSES, "primary", 1, "classes", TensorDataType.FLOAT32, listOf(1, 2)),
            OutputComponent(OutputRole.DETECTION_SCORES, "primary", 2, "scores", TensorDataType.FLOAT32, listOf(1, 2)),
            OutputComponent(OutputRole.DETECTION_COUNT, "primary", 3, "count", TensorDataType.FLOAT32, listOf(1)),
        ),
        bindings = emptyList(),
        adapterContract = AdapterContract(
            "object_detection_v1",
            classMap(),
            EmbeddedPostprocessSpec(2, 0.5, null),
        ),
        preprocessId = "object_rgb_uint8_v1",
        postprocessId = "object_detection_v1",
        parameterProfile = ParameterProfile(
            defaults = mapOf("max_detections" to 2.0),
            allowedBounds = mapOf("max_detections" to ParameterBound(1.0, 2.0)),
            samplingPolicy = SamplingPolicySpec(500, 250, 2000, false),
        ),
        deviceCompatibility = DeviceCompatibility(setOf("fixture"), 26, null, setOf("arm64-v8a"), 1024, emptySet()),
        signature = ManifestSignature("RFC8785", "Ed25519", "fixture", "AA=="),
    )

    private fun source(name: String) = SourceReference("https://example.com/$name", "1")

    private fun classMap() = ClassMapSpec(
        identity = "fixture-map",
        sha256 = "f".repeat(64),
        classIdBase = 0,
        targets = listOf(
            ClassMapTarget(16, "cat", "猫", "cat", setOf("小猫")),
            ClassMapTarget(17, "dog", "狗", "dog", setOf("小狗")),
            ClassMapTarget(52, "apple", "苹果", "apple"),
        ),
    )
}
