package app.beyoureyes.core.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpOcrDbTextLineLocatorTest {
    @Test
    fun `nearby glyphs become one numeric line while distant rows remain separate`() {
        val width = 128
        val height = 64
        val values = FloatArray(width * height)
        fill(values, width, 18, 12, 23, 25, 0.9f)
        fill(values, width, 27, 12, 32, 25, 0.85f)
        fill(values, width, 80, 42, 92, 50, 0.95f)
        val locator = PpOcrDbTextLineLocator(0.2f, 0.4f, 1.4f, 20)

        val result = locator.locate(
            tensor(values, width, height),
            LetterboxTransform(width, height, width, height, 1f, 1f, 0f, 0f),
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.box.centerX < 0.4f && it.box.centerY < 0.5f })
        assertTrue(result.any { it.box.centerX > 0.6f && it.box.centerY > 0.5f })
    }

    @Test
    fun `letterbox padding is clipped out of source coordinates`() {
        val width = 64
        val height = 64
        val values = FloatArray(width * height)
        fill(values, width, 16, 24, 47, 38, 0.9f)
        val locator = PpOcrDbTextLineLocator(0.2f, 0.4f, 1.4f, 20)

        val result = locator.locate(
            tensor(values, width, height),
            LetterboxTransform(
                sourceWidth = 64,
                sourceHeight = 32,
                inputWidth = 64,
                inputHeight = 64,
                scaleX = 1f,
                scaleY = 1f,
                offsetX = 0f,
                offsetY = 16f,
            ),
        )

        assertEquals(1, result.size)
        assertTrue(result.single().box.top in 0f..0.5f)
        assertTrue(result.single().box.bottom in 0.5f..1f)
    }

    private fun fill(
        values: FloatArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        value: Float,
    ) {
        for (y in top..bottom) for (x in left..right) values[y * width + x] = value
    }

    private fun tensor(values: FloatArray, width: Int, height: Int): RawTensor {
        val bytes = ByteArray(values.size * Float.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        values.forEach(buffer::putFloat)
        return RawTensor(
            name = "fetch_name_0",
            bytes = bytes,
            shape = listOf(1, 1, height, width),
            elementType = TensorElementType.FLOAT32,
        )
    }
}
