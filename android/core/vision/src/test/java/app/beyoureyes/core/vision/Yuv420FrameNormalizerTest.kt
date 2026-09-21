package app.beyoureyes.core.vision

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class Yuv420FrameNormalizerTest {
    @Test
    fun `golden crop stride and all clockwise rotations produce upright RGB`() {
        val sourceLuma = arrayOf(
            intArrayOf(16, 16, 16, 16),
            intArrayOf(16, 54, 91, 128),
            intArrayOf(16, 165, 202, 235),
        )
        val expectedByRotation = mapOf(
            0 to GoldenRgb(
                width = 3,
                height = 2,
                grayscale = intArrayOf(44, 87, 130, 173, 217, 255),
            ),
            90 to GoldenRgb(
                width = 2,
                height = 3,
                grayscale = intArrayOf(173, 44, 217, 87, 255, 130),
            ),
            180 to GoldenRgb(
                width = 3,
                height = 2,
                grayscale = intArrayOf(255, 217, 173, 130, 87, 44),
            ),
            270 to GoldenRgb(
                width = 2,
                height = 3,
                grayscale = intArrayOf(130, 255, 87, 217, 44, 173),
            ),
        )

        expectedByRotation.forEach { (rotation, expected) ->
            val canonical = Yuv420FrameNormalizer.normalize(
                sourceFrame(
                    rotationDegrees = rotation,
                    cropRect = PixelRect(1, 1, 4, 3),
                    yPlane = stridedPlane(
                        samples = sourceLuma,
                        rowStride = 10,
                        pixelStride = 2,
                    ),
                    uPlane = neutralChromaPlane(),
                    vPlane = neutralChromaPlane(),
                ),
            )

            assertEquals("rotation=$rotation width", expected.width, canonical.width)
            assertEquals("rotation=$rotation height", expected.height, canonical.height)
            assertArrayEquals(
                "rotation=$rotation pixels",
                expected.asRgbBytes(),
                canonical.rgb888,
            )
            assertEquals(7L, canonical.sourceSequence)
            assertEquals(1_234L, canonical.monotonicTimeMillis)
            assertEquals(5_678L, canonical.capturedAtEpochMillis)
        }
    }

    @Test
    fun `BT601 limited conversion clamps red sample to RGB bounds`() {
        val canonical = Yuv420FrameNormalizer.normalize(
            sourceFrame(
                width = 2,
                height = 2,
                cropRect = PixelRect(0, 0, 2, 2),
                yPlane = stridedPlane(
                    samples = arrayOf(intArrayOf(81, 81), intArrayOf(81, 81)),
                    rowStride = 2,
                    pixelStride = 1,
                ),
                uPlane = YuvPlane(byteArrayOf(90.toByte()), rowStride = 1, pixelStride = 1),
                vPlane = YuvPlane(byteArrayOf(240.toByte()), rowStride = 1, pixelStride = 1),
            ),
        )

        assertArrayEquals(
            byteArrayOf(
                255.toByte(), 0, 0,
                255.toByte(), 0, 0,
                255.toByte(), 0, 0,
                255.toByte(), 0, 0,
            ),
            canonical.rgb888,
        )
    }

    @Test
    fun `camera mapper copies current buffer slices without changing positions`() {
        val yBuffer = ByteBuffer.wrap(byteArrayOf(99, 16, 16, 16, 16, 88)).apply {
            position(1)
            limit(5)
        }
        val uBuffer = ByteBuffer.wrap(byteArrayOf(77, 128.toByte(), 66)).apply {
            position(1)
            limit(2)
        }
        val vBuffer = ByteBuffer.wrap(byteArrayOf(55, 128.toByte(), 44)).apply {
            position(1)
            limit(2)
        }

        val frame = CameraYuv420FrameMapper.copyToSourceFrame(
            sourceSequence = 9,
            monotonicTimeMillis = 10,
            capturedAtEpochMillis = 11,
            width = 2,
            height = 2,
            cropRect = PixelRect(0, 0, 2, 2),
            rotationDegrees = 0,
            yPlane = CameraPlaneBufferView(yBuffer, rowStride = 2, pixelStride = 1),
            uPlane = CameraPlaneBufferView(uBuffer, rowStride = 1, pixelStride = 1),
            vPlane = CameraPlaneBufferView(vBuffer, rowStride = 1, pixelStride = 1),
        )

        assertEquals(1, yBuffer.position())
        assertEquals(1, uBuffer.position())
        assertEquals(1, vBuffer.position())
        val owned = frame.pixels as FramePixels.Yuv420
        assertArrayEquals(byteArrayOf(16, 16, 16, 16), owned.yPlane.bytes)
        assertArrayEquals(byteArrayOf(128.toByte()), owned.uPlane.bytes)
        assertArrayEquals(byteArrayOf(128.toByte()), owned.vPlane.bytes)

        yBuffer.put(1, 235.toByte())
        assertArrayEquals(byteArrayOf(16, 16, 16, 16), owned.yPlane.bytes)
    }

    @Test
    fun `undersized plane fails closed with controlled buffer error`() {
        val error = expectNormalizationFailure {
            Yuv420FrameNormalizer.normalize(
                sourceFrame(
                    yPlane = YuvPlane(
                        bytes = ByteArray(26),
                        rowStride = 10,
                        pixelStride = 2,
                    ),
                    uPlane = neutralChromaPlane(),
                    vPlane = neutralChromaPlane(),
                ),
            )
        }

        assertEquals(FrameNormalizationFailure.BUFFER_TOO_SMALL, error.failure)
    }

    @Test
    fun `row stride that cannot address logical pixels fails closed`() {
        val error = expectNormalizationFailure {
            Yuv420FrameNormalizer.normalize(
                sourceFrame(
                    yPlane = YuvPlane(
                        bytes = ByteArray(30),
                        rowStride = 6,
                        pixelStride = 2,
                    ),
                    uPlane = neutralChromaPlane(),
                    vPlane = neutralChromaPlane(),
                ),
            )
        }

        assertEquals(FrameNormalizationFailure.INVALID_PLANE_LAYOUT, error.failure)
    }

    @Test
    fun `wrong pixel format fails closed with controlled type error`() {
        val frame = SourceFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 2,
            capturedAtEpochMillis = null,
            width = 2,
            height = 2,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, 2, 2),
            pixels = FramePixels.Rgb888(ByteArray(12), rowStride = 6),
        )

        val error = expectNormalizationFailure { Yuv420FrameNormalizer.normalize(frame) }

        assertEquals(FrameNormalizationFailure.UNSUPPORTED_PIXEL_FORMAT, error.failure)
    }

    @Test
    fun `camera mapper rejects invalid rotation before SourceFrame construction`() {
        val plane = CameraPlaneBufferView(ByteBuffer.wrap(byteArrayOf(16)), 1, 1)

        val error = expectNormalizationFailure {
            CameraYuv420FrameMapper.copyToSourceFrame(
                sourceSequence = 0,
                monotonicTimeMillis = 0,
                capturedAtEpochMillis = null,
                width = 1,
                height = 1,
                cropRect = PixelRect(0, 0, 1, 1),
                rotationDegrees = 45,
                yPlane = plane,
                uPlane = plane,
                vPlane = plane,
            )
        }

        assertEquals(FrameNormalizationFailure.INVALID_ROTATION, error.failure)
    }

    private fun sourceFrame(
        width: Int = 4,
        height: Int = 3,
        rotationDegrees: Int = 0,
        cropRect: PixelRect = PixelRect(0, 0, width, height),
        yPlane: YuvPlane = stridedPlane(
            samples = arrayOf(
                intArrayOf(16, 16, 16, 16),
                intArrayOf(16, 54, 91, 128),
                intArrayOf(16, 165, 202, 235),
            ),
            rowStride = 10,
            pixelStride = 2,
        ),
        uPlane: YuvPlane,
        vPlane: YuvPlane,
    ): SourceFrame = SourceFrame(
        sourceSequence = 7,
        monotonicTimeMillis = 1_234,
        capturedAtEpochMillis = 5_678,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        cropRect = cropRect,
        pixels = FramePixels.Yuv420(yPlane = yPlane, uPlane = uPlane, vPlane = vPlane),
    )

    private fun neutralChromaPlane(): YuvPlane = stridedPlane(
        samples = arrayOf(intArrayOf(128, 128), intArrayOf(128, 128)),
        rowStride = 5,
        pixelStride = 2,
    )

    private fun stridedPlane(
        samples: Array<IntArray>,
        rowStride: Int,
        pixelStride: Int,
    ): YuvPlane {
        val height = samples.size
        val width = samples.first().size
        require(samples.all { it.size == width })
        val requiredBytes = (height - 1) * rowStride + (width - 1) * pixelStride + 1
        return YuvPlane(
            bytes = ByteArray(requiredBytes).also { bytes ->
                samples.forEachIndexed { y, row ->
                    row.forEachIndexed { x, sample ->
                        bytes[y * rowStride + x * pixelStride] = sample.toByte()
                    }
                }
            },
            rowStride = rowStride,
            pixelStride = pixelStride,
        )
    }

    private fun expectNormalizationFailure(block: () -> Unit): FrameNormalizationException {
        return try {
            block()
            fail("expected FrameNormalizationException")
            error("unreachable")
        } catch (error: FrameNormalizationException) {
            error
        }
    }

    private data class GoldenRgb(
        val width: Int,
        val height: Int,
        val grayscale: IntArray,
    ) {
        fun asRgbBytes(): ByteArray = ByteArray(grayscale.size * 3).also { output ->
            grayscale.forEachIndexed { index, value ->
                output[index * 3] = value.toByte()
                output[index * 3 + 1] = value.toByte()
                output[index * 3 + 2] = value.toByte()
            }
        }
    }
}
