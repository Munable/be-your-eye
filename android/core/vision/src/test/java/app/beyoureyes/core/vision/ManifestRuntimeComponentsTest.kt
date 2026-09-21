package app.beyoureyes.core.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestRuntimeComponentsTest {
    @Test
    fun `default production registry contains only reusable v3 families`() {
        val registry = ManifestRuntimeComponents.registry()

        assertNull(registry.preprocessor("rgb_uint8_letterbox_black_nearest_v1"))
        assertNull(registry.adapter("efficientdet_lite_coco_sparse_ids_v1"))
        assertNotNull(registry.preprocessor(ManifestRuntimeComponents.PREPROCESS_OBJECT_UINT8))
        assertEquals(
            setOf(RecipeFamily.READING_PIPELINE_V1),
            checkNotNull(registry.preprocessor(ManifestRuntimeComponents.PREPROCESS_PPOCR_AUTO_LOCATE))
                .recipeFamilies,
        )
        assertEquals(
            setOf(
                RecipeFamily.OBJECT_DETECTION_V1,
                RecipeFamily.SIMILARITY_MATCH_V1,
                RecipeFamily.READING_PIPELINE_V1,
            ),
            checkNotNull(registry.backend(RuntimeKind.LITERT)).recipeFamilies,
        )
    }

    @Test
    fun `reference provider and cache install one generic neural family without package IDs`() {
        val provider = ReferenceImageProvider { null }
        val cache = object : ReferenceEmbeddingCache {
            override fun load(key: ReferenceEmbeddingCacheKey, expectedDimension: Int) = null
            override fun store(key: ReferenceEmbeddingCacheKey, normalizedEmbedding: FloatArray) = Unit
            override fun invalidateReferences(referenceSha256: Set<String>) = Unit
        }

        val registry = ManifestRuntimeComponents.registry(
            referenceImageProvider = provider,
            referenceEmbeddingCache = cache,
        )

        assertNotNull(registry.preprocessor(NeuralReferenceRuntimeComponents.PREPROCESS_ID))
        assertNull(registry.preprocessor("rgb_direct_resize_0_1_v1"))
        assertNull(registry.preprocessor("classic_reference_rgb_v1"))
        assertNotNull(registry.artifactSession(RuntimeKind.CLASSIC_VISION))
        assertNull(registry.preprocessor("mediapipe_mobilenet_v3_large_reference_v1"))
    }

    @Test
    fun `reused letterbox tensor clears padding between aspect ratios`() {
        val preprocessor = ManifestRgbLetterboxPreprocessor(
            inputSpec = InputSpec(
                tensorIndex = 0,
                tensorName = "image",
                width = 4,
                height = 4,
                channels = 3,
                dataType = TensorDataType.UINT8,
                layout = TensorLayout.NHWC,
                colorSpace = ColorSpace.RGB,
                runtimeShape = listOf(1, 4, 4, 3),
                quantization = InputQuantization(QuantizationMode.NONE),
            ),
            mode = ManifestRgbLetterboxPreprocessor.PixelEncodingMode.UINT8_RAW,
        )

        val wide = preprocessor.prepare(canonicalFrame(width = 4, height = 2, value = 255))
        val wideBytes = wide.bytes.copyOf()
        val tall = preprocessor.prepare(canonicalFrame(width = 2, height = 4, value = 7))

        assertSame(wide.bytes, tall.bytes)
        assertTrue(wideBytes.take(4 * 3).all { it == 0.toByte() })
        assertTrue(wideBytes.takeLast(4 * 3).all { it == 0.toByte() })
        for (row in 0 until 4) {
            assertEquals(0, tall.bytes[(row * 4) * 3].toInt())
            assertEquals(0, tall.bytes[(row * 4 + 3) * 3].toInt())
        }
    }

    @Test
    fun `object detector stretches the full frame with half-pixel bilinear sampling`() {
        val preprocessor = ManifestObjectRgbDirectResizePreprocessor(
            inputSpec = InputSpec(
                tensorIndex = 0,
                tensorName = "image",
                width = 3,
                height = 3,
                channels = 3,
                dataType = TensorDataType.UINT8,
                layout = TensorLayout.NHWC,
                colorSpace = ColorSpace.RGB,
                runtimeShape = listOf(1, 3, 3, 3),
                quantization = InputQuantization(QuantizationMode.NONE),
            ),
        )
        val frame = CanonicalFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 1,
            capturedAtEpochMillis = null,
            width = 2,
            height = 1,
            rgb888 = byteArrayOf(0, 10, 20, 90, 110, 130.toByte()),
        )

        val prepared = preprocessor.prepare(frame)

        val expectedRow = byteArrayOf(0, 10, 20, 45, 60, 75, 90, 110, 130.toByte())
        assertArrayEquals(expectedRow + expectedRow + expectedRow, prepared.bytes)
        assertEquals(2, prepared.transform.sourceWidth)
        assertEquals(1, prepared.transform.sourceHeight)
        assertEquals(3, prepared.transform.inputWidth)
        assertEquals(3, prepared.transform.inputHeight)
        assertEquals(1.5f, prepared.transform.scaleX)
        assertEquals(3f, prepared.transform.scaleY)
        assertEquals(0f, prepared.transform.offsetX)
        assertEquals(0f, prepared.transform.offsetY)
        assertSame(prepared.bytes, preprocessor.prepare(frame).bytes)
    }

    @Test
    fun `object detector downsamples both axes and rebuilds mappings when source size changes`() {
        val preprocessor = ManifestObjectRgbDirectResizePreprocessor(
            inputSpec = InputSpec(
                tensorIndex = 0,
                tensorName = "image",
                width = 2,
                height = 2,
                channels = 3,
                dataType = TensorDataType.UINT8,
                layout = TensorLayout.NHWC,
                colorSpace = ColorSpace.RGB,
                runtimeShape = listOf(1, 2, 2, 3),
                quantization = InputQuantization(QuantizationMode.NONE),
            ),
        )
        val downsampled = preprocessor.prepare(
            canonicalFrame(
                width = 4,
                height = 4,
                rgb888 = grayscaleRgb((0..15).map { it * 10 }),
            ),
        )
        assertArrayEquals(grayscaleRgb(listOf(25, 45, 105, 125)), downsampled.bytes)

        val sameSize = preprocessor.prepare(
            canonicalFrame(
                width = 2,
                height = 2,
                rgb888 = grayscaleRgb(listOf(1, 2, 3, 4)),
            ),
        )
        assertArrayEquals(grayscaleRgb(listOf(1, 2, 3, 4)), sameSize.bytes)
    }

    private fun canonicalFrame(width: Int, height: Int, value: Int): CanonicalFrame =
        CanonicalFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 1,
            capturedAtEpochMillis = null,
            width = width,
            height = height,
            rgb888 = ByteArray(width * height * 3) { value.toByte() },
        )

    private fun canonicalFrame(width: Int, height: Int, rgb888: ByteArray): CanonicalFrame =
        CanonicalFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 1,
            capturedAtEpochMillis = null,
            width = width,
            height = height,
            rgb888 = rgb888,
        )

    private fun grayscaleRgb(values: List<Int>): ByteArray =
        values.flatMap { value -> List(3) { value.toByte() } }.toByteArray()
}
