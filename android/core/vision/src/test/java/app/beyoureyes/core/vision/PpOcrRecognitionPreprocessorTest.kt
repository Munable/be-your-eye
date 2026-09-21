package app.beyoureyes.core.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PpOcrRecognitionPreprocessorTest {
    @Test
    fun convertsRgbToBgrNormalizesAndRightPads() {
        val subject = PpOcrRecognitionPreprocessor(input(width = 120))
        val frame = solidFrame(width = 2, height = 1, red = 255, green = 0, blue = 128)

        val prepared = subject.prepare(frame)

        assertEquals(listOf(1, 3, 48, 120), prepared.shape)
        assertEquals(96f / 2f, prepared.transform.scaleX)
        assertEquals(48f, prepared.transform.scaleY)
        assertEquals(0f, prepared.transform.offsetX)
        assertEquals(0f, prepared.transform.offsetY)
        val values = prepared.floats()
        val plane = 48 * 120
        assertEquals(128f / 127.5f - 1f, values[0], 1e-6f)
        assertEquals(-1f, values[plane], 1e-6f)
        assertEquals(1f, values[plane * 2], 1e-6f)
        assertEquals(0f, values[119], 0f)
        assertEquals(0f, values[plane + 119], 0f)
        assertEquals(0f, values[plane * 2 + 119], 0f)
    }

    @Test
    fun usesFloorWidthAndSignedMaximumWithoutChangingWeights() {
        val wide = PpOcrRecognitionPreprocessor(input(width = 640)).prepare(
            solidFrame(width = 615, height = 68, red = 1, green = 2, blue = 3),
        )
        val clamped = PpOcrRecognitionPreprocessor(input(width = 320)).prepare(
            solidFrame(width = 615, height = 68, red = 1, green = 2, blue = 3),
        )

        assertEquals(434f / 615f, wide.transform.scaleX)
        assertEquals(320f / 615f, clamped.transform.scaleX)
        assertTrue(wide.floats().slice(434 until 640).all { it == 0f })
        assertTrue(clamped.floats().take(320).all { it != 0f })
    }

    @Test
    fun quantizesBilinearPixelsBeforeUpstreamNormalization() {
        val frame = CanonicalFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 1,
            capturedAtEpochMillis = 1,
            width = 2,
            height = 1,
            rgb888 = byteArrayOf(
                0, 0, 0,
                255.toByte(), 0, 0,
            ),
        )

        val values = PpOcrRecognitionPreprocessor(input(width = 120)).prepare(frame).floats()
        val redPlaneOffset = 2 * 48 * 120

        assertEquals(130f / 127.5f - 1f, values[redPlaneOffset + 48], 1e-6f)
    }

    @Test
    fun grayscaleMinMaxUsesDeterministicBt601AndEqualBgrPlanes() {
        val frame = CanonicalFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 1,
            capturedAtEpochMillis = 1,
            width = 2,
            height = 1,
            rgb888 = byteArrayOf(
                255.toByte(), 0, 0,
                0, 0, 255.toByte(),
            ),
        )
        val prepared = PpOcrRecognitionPreprocessor(
            input(width = 120),
            PpOcrRecognitionPreprocessor.ColorMode.GRAYSCALE_MINMAX,
        ).prepare(frame)
        val values = prepared.floats()
        val plane = 48 * 120

        repeat(3) { channel ->
            assertEquals(1f, values[channel * plane], 1e-6f)
            assertEquals(-1f, values[channel * plane + 95], 1e-6f)
            assertEquals(0f, values[channel * plane + 119], 0f)
        }
    }

    @Test
    fun rejectsContractsOutsideTheFiniteFamily() {
        assertThrows(IllegalArgumentException::class.java) {
            PpOcrRecognitionPreprocessor(input(width = 320).copy(layout = TensorLayout.NHWC))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PpOcrRecognitionPreprocessor(input(width = 320).copy(colorSpace = ColorSpace.RGB))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PpOcrRecognitionPreprocessor(input(width = 320).copy(dataType = TensorDataType.UINT8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PpOcrRecognitionPreprocessor(input(width = 320).copy(runtimeShape = listOf(1, 3, 32, 320)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PpOcrRecognitionPreprocessor(input(width = 320).copy(runtimeShape = listOf(1, 3, 48)))
        }
    }

    private fun input(width: Int) = InputComponent(
        role = InputRole.IMAGE,
        artifactRole = "primary",
        tensorIndex = 0,
        tensorName = "x",
        dataType = TensorDataType.FLOAT32,
        runtimeShape = listOf(1, 3, 48, width),
        quantization = InputQuantization(QuantizationMode.NONE),
        layout = TensorLayout.NCHW,
        colorSpace = ColorSpace.BGR,
    )

    private fun solidFrame(
        width: Int,
        height: Int,
        red: Int,
        green: Int,
        blue: Int,
    ) = CanonicalFrame(
        sourceSequence = 1,
        monotonicTimeMillis = 1,
        capturedAtEpochMillis = 1,
        width = width,
        height = height,
        rgb888 = ByteArray(width * height * 3).also { bytes ->
            repeat(width * height) { pixel ->
                bytes[pixel * 3] = red.toByte()
                bytes[pixel * 3 + 1] = green.toByte()
                bytes[pixel * 3 + 2] = blue.toByte()
            }
        },
    )

    private fun PreparedInput.floats(): List<Float> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        return List(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }
}
