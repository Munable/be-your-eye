package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Observation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestSsdDetectionAdapterTest {
    @Test
    fun `signed maximum five ignores a selected target on the sixth sorted row`() {
        val classes = IntArray(OUTPUT_MAXIMUM) { DOG_CLASS_ID }.also {
            it[5] = CAT_CLASS_ID
        }

        val observation = adapter(configuredMaximum = 5).toObservation(
            output = detectionOutput(classes = classes, count = 6),
            transform = transform,
            frame = frame,
        )

        assertTrue(observation is Observation.Detections)
        assertTrue((observation as Observation.Detections).items.isEmpty())
    }

    @Test
    fun `signed maximum 25 accepts the last output row and rejects count beyond the contract`() {
        val classes = IntArray(OUTPUT_MAXIMUM) { DOG_CLASS_ID }.also {
            it[OUTPUT_MAXIMUM - 1] = CAT_CLASS_ID
        }
        val adapter = adapter(configuredMaximum = OUTPUT_MAXIMUM)

        val observation = adapter.toObservation(
            output = detectionOutput(classes = classes, count = OUTPUT_MAXIMUM),
            transform = transform,
            frame = frame,
        )

        assertEquals(
            listOf(CAT_TARGET_ID),
            (observation as Observation.Detections).items.map { it.label },
        )
        assertThrows(IllegalArgumentException::class.java) {
            adapter.toObservation(
                output = detectionOutput(classes = classes, count = OUTPUT_MAXIMUM + 1),
                transform = transform,
                frame = frame,
            )
        }
    }

    @Test
    fun `direct resize maps normalized detector coordinates without letterbox correction`() {
        val directResizeTransform = LetterboxTransform(
            sourceWidth = 4,
            sourceHeight = 2,
            inputWidth = 3,
            inputHeight = 3,
            scaleX = 0.75f,
            scaleY = 1.5f,
            offsetX = 0f,
            offsetY = 0f,
        )

        val observation = adapter(configuredMaximum = 1).toObservation(
            output = detectionOutput(
                classes = IntArray(OUTPUT_MAXIMUM) { CAT_CLASS_ID },
                count = 1,
                box = floatArrayOf(0.25f, 0.125f, 0.75f, 0.875f),
            ),
            transform = directResizeTransform,
            frame = frame,
        ) as Observation.Detections

        val mapped = observation.items.single().box
        assertEquals(0.125f, mapped.left)
        assertEquals(0.25f, mapped.top)
        assertEquals(0.875f, mapped.right)
        assertEquals(0.75f, mapped.bottom)
    }

    private fun adapter(configuredMaximum: Int) = ManifestSsdDetectionAdapter(
        manifest = manifest(configuredMaximum),
        targetProfile = TargetProfile.ObjectClass(CAT_TARGET_ID, "猫", "cat"),
        classMapProvider = ObjectDetectionClassMap.provider,
    )

    private fun detectionOutput(
        classes: IntArray,
        count: Int,
        box: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    ): RawTensorOutput {
        require(classes.size == OUTPUT_MAXIMUM)
        require(box.size == BOX_COORDINATES)
        val boxes = FloatArray(OUTPUT_MAXIMUM * BOX_COORDINATES) { index ->
            box[index % BOX_COORDINATES]
        }
        val scores = FloatArray(OUTPUT_MAXIMUM) { 0.9f }
        return RawTensorOutput(
            listOf(
                floatTensor("boxes", boxes, listOf(1, OUTPUT_MAXIMUM, BOX_COORDINATES)),
                floatTensor(
                    "classes",
                    FloatArray(classes.size) { classes[it].toFloat() },
                    listOf(1, OUTPUT_MAXIMUM),
                ),
                floatTensor("scores", scores, listOf(1, OUTPUT_MAXIMUM)),
                floatTensor("count", floatArrayOf(count.toFloat()), listOf(1)),
            ),
        )
    }

    private fun floatTensor(name: String, values: FloatArray, shape: List<Int>): RawTensor =
        RawTensor(
            name = name,
            bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .apply { values.forEach(::putFloat) }
                .array(),
            shape = shape,
            elementType = TensorElementType.FLOAT32,
        )

    private fun manifest(configuredMaximum: Int) = ModelPackageManifest(
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
        license = ModelPackageLicense(
            licenseId = "Apache-2.0",
            licenseTextSha256 = "d".repeat(64),
            reviewStatus = LicenseReviewStatus.APPROVED,
            reviewedAt = "2026-08-01T00:00:00Z",
            reviewEvidenceRef = "review",
            commercialUseAllowed = true,
            redistributionAllowed = true,
            sourceDisclosureRequired = false,
        ),
        artifacts = listOf(
            ArtifactComponent(
                role = "primary",
                runtime = RuntimeKind.LITERT,
                mediaType = ArtifactMediaType.LITERT,
                url = "https://example.com/model.tflite",
                sha256 = "a".repeat(64),
                sizeBytes = 1,
            ),
        ),
        inputs = listOf(
            InputComponent(
                role = InputRole.IMAGE,
                artifactRole = "primary",
                tensorIndex = 0,
                tensorName = "image",
                dataType = TensorDataType.UINT8,
                runtimeShape = listOf(1, 320, 320, 3),
                quantization = InputQuantization(QuantizationMode.PER_TENSOR, 1.0, 0),
                layout = TensorLayout.NHWC,
                colorSpace = ColorSpace.RGB,
            ),
        ),
        outputs = listOf(
            output(OutputRole.DETECTION_BOXES, 0, "boxes", listOf(1, OUTPUT_MAXIMUM, 4)),
            output(OutputRole.DETECTION_CLASSES, 1, "classes", listOf(1, OUTPUT_MAXIMUM)),
            output(OutputRole.DETECTION_SCORES, 2, "scores", listOf(1, OUTPUT_MAXIMUM)),
            output(OutputRole.DETECTION_COUNT, 3, "count", listOf(1)),
        ),
        bindings = emptyList(),
        adapterContract = AdapterContract(
            schemaId = "object_detection_v1",
            classMap = classMap,
            embeddedPostprocess = EmbeddedPostprocessSpec(
                maxDetections = OUTPUT_MAXIMUM,
                scoreThreshold = 0.5,
                nmsIouThreshold = 0.5,
            ),
        ),
        preprocessId = "object_rgb_uint8_v1",
        postprocessId = "object_detection_v1",
        parameterProfile = ParameterProfile(
            defaults = mapOf("max_detections" to configuredMaximum.toDouble()),
            allowedBounds = mapOf(
                "max_detections" to ParameterBound(1.0, OUTPUT_MAXIMUM.toDouble()),
            ),
            samplingPolicy = SamplingPolicySpec(500, 250, 2_000, false),
        ),
        deviceCompatibility = DeviceCompatibility(
            setOf("fixture"),
            26,
            null,
            setOf("arm64-v8a"),
            1_024,
            emptySet(),
        ),
        signature = ManifestSignature("RFC8785", "Ed25519", "fixture", "AA=="),
    )

    private fun output(
        role: OutputRole,
        index: Int,
        name: String,
        shape: List<Int>,
    ) = OutputComponent(role, "primary", index, name, TensorDataType.FLOAT32, shape)

    private fun source(name: String) = SourceReference("https://example.com/$name", "1")

    private companion object {
        const val OUTPUT_MAXIMUM = 25
        const val BOX_COORDINATES = 4
        const val CAT_CLASS_ID = 0
        const val DOG_CLASS_ID = 1
        const val CAT_TARGET_ID = "cat"

        val transform = LetterboxTransform(320, 320, 320, 320, 1f, 1f, 0f, 0f)
        val frame = CanonicalFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 1,
            capturedAtEpochMillis = null,
            width = 320,
            height = 320,
            rgb888 = ByteArray(320 * 320 * 3),
        )
        val classMap = ClassMapSpec(
            identity = "fixture-map",
            sha256 = "f".repeat(64),
            classIdBase = 0,
            targets = listOf(
                ClassMapTarget(CAT_CLASS_ID, CAT_TARGET_ID, "猫", "cat"),
                ClassMapTarget(DOG_CLASS_ID, "dog", "狗", "dog"),
            ),
        )
    }
}
