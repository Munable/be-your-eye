package app.beyoureyes.core.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Finite, package-agnostic PP-OCR recognition preprocessing family.
 *
 * The signed Manifest chooses a concrete NCHW width. The image keeps its aspect ratio until that
 * width is exhausted, is resized to height 48 with deterministic half-pixel bilinear sampling,
 * quantized back to unsigned-pixel values like the upstream uint8 resize, converted from canonical
 * RGB to BGR, normalized to [-1, 1], and right-padded with normalized zeroes. A different compatible
 * reader changes only the Manifest shape/artifacts/evidence.
 */
class PpOcrRecognitionPreprocessor(
    private val input: InputComponent,
    private val colorMode: ColorMode = ColorMode.BGR,
) : InputPreprocessor {
    private val inputHeight = input.runtimeShape.getOrNull(2) ?: -1
    private val inputWidth = input.runtimeShape.getOrNull(3) ?: -1

    init {
        require(input.role == InputRole.IMAGE)
        require(input.dataType == TensorDataType.FLOAT32)
        require(input.layout == TensorLayout.NCHW)
        require(input.colorSpace == ColorSpace.BGR)
        require(input.quantization.mode == QuantizationMode.NONE)
        require(input.runtimeShape.size == 4)
        require(input.runtimeShape[0] == 1 && input.runtimeShape[1] == CHANNEL_COUNT)
        require(inputHeight == FIXED_HEIGHT)
        require(inputWidth in MIN_WIDTH..MAX_WIDTH)
    }

    override fun prepare(frame: CanonicalFrame): PreparedInput {
        val grayscale = if (colorMode == ColorMode.GRAYSCALE_MINMAX) {
            frame.grayscaleMinMax()
        } else {
            null
        }
        val aspectWidth = floor(inputHeight.toDouble() * frame.width / frame.height)
            .toInt()
            .coerceAtLeast(1)
        val resizedWidth = minOf(inputWidth, aspectWidth)
        val bytes = ByteArray(input.runtimeShape.checkedElementCount() * Float.SIZE_BYTES)
        val values = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val planeSize = inputHeight * inputWidth

        repeat(inputHeight) { destinationY ->
            val sourceY = halfPixelSource(destinationY, inputHeight, frame.height)
            val y0 = floor(sourceY).toInt().coerceIn(0, frame.height - 1)
            val y1 = (y0 + 1).coerceAtMost(frame.height - 1)
            val yWeight = (sourceY - y0).toFloat().coerceIn(0f, 1f)
            repeat(resizedWidth) { destinationX ->
                val sourceX = halfPixelSource(destinationX, resizedWidth, frame.width)
                val x0 = floor(sourceX).toInt().coerceIn(0, frame.width - 1)
                val x1 = (x0 + 1).coerceAtMost(frame.width - 1)
                val xWeight = (sourceX - x0).toFloat().coerceIn(0f, 1f)
                repeat(CHANNEL_COUNT) { bgrChannel ->
                    val rgbChannel = CHANNEL_COUNT - 1 - bgrChannel
                    val top = lerp(
                        frame.pixel(x0, y0, rgbChannel, grayscale),
                        frame.pixel(x1, y0, rgbChannel, grayscale),
                        xWeight,
                    )
                    val bottom = lerp(
                        frame.pixel(x0, y1, rgbChannel, grayscale),
                        frame.pixel(x1, y1, rgbChannel, grayscale),
                        xWeight,
                    )
                    val pixel = lerp(top, bottom, yWeight)
                        .roundToInt()
                        .coerceIn(0, MAX_PIXEL_VALUE)
                    val normalized = pixel / HALF_PIXEL_RANGE - 1f
                    val element = bgrChannel * planeSize + destinationY * inputWidth + destinationX
                    values.putFloat(element * Float.SIZE_BYTES, normalized)
                }
            }
        }

        return PreparedInput(
            bytes = bytes,
            shape = input.runtimeShape,
            elementType = TensorElementType.FLOAT32,
            transform = LetterboxTransform(
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                scaleX = resizedWidth.toFloat() / frame.width,
                scaleY = inputHeight.toFloat() / frame.height,
                offsetX = 0f,
                offsetY = 0f,
            ),
        )
    }

    private fun halfPixelSource(destination: Int, destinationSize: Int, sourceSize: Int): Double =
        ((destination + 0.5) * sourceSize / destinationSize - 0.5)
            .coerceIn(0.0, (sourceSize - 1).toDouble())

    private fun CanonicalFrame.pixel(
        x: Int,
        y: Int,
        channel: Int,
        grayscale: ByteArray?,
    ): Float = if (grayscale == null) {
        (rgb888[(y * width + x) * CHANNEL_COUNT + channel].toInt() and 0xff).toFloat()
    } else {
        (grayscale[y * width + x].toInt() and 0xff).toFloat()
    }

    private fun CanonicalFrame.grayscaleMinMax(): ByteArray {
        val pixels = ByteArray(width * height)
        var minimum = MAX_PIXEL_VALUE
        var maximum = 0
        repeat(width * height) { pixel ->
            val offset = pixel * CHANNEL_COUNT
            val red = rgb888[offset].toInt() and 0xff
            val green = rgb888[offset + 1].toInt() and 0xff
            val blue = rgb888[offset + 2].toInt() and 0xff
            val value = (77 * red + 150 * green + 29 * blue + 128) shr 8
            pixels[pixel] = value.toByte()
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
        }
        val range = maximum - minimum
        if (range > 0) {
            repeat(pixels.size) { index ->
                val value = pixels[index].toInt() and 0xff
                pixels[index] = (((value - minimum) * MAX_PIXEL_VALUE + range / 2) / range)
                    .toByte()
            }
        }
        return pixels
    }

    private fun lerp(start: Float, end: Float, weight: Float): Float =
        start + (end - start) * weight

    companion object {
        const val ID = "ppocr_rec_bgr_nchw_float32_minus1_1_v1"
        const val GRAYSCALE_MINMAX_ID = "ppocr_rec_gray_minmax_nchw_float32_minus1_1_v1"
        const val FIXED_HEIGHT = 48
        const val MIN_WIDTH = 32
        const val MAX_WIDTH = 3200
        private const val CHANNEL_COUNT = 3
        private const val MAX_PIXEL_VALUE = 255
        private const val HALF_PIXEL_RANGE = 127.5f
    }

    enum class ColorMode { BGR, GRAYSCALE_MINMAX }
}
