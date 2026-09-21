package app.beyoureyes.core.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/** PP-OCR DB detector preprocessing: aspect-preserving RGB-to-BGR resize and ImageNet normalize. */
class PpOcrDetectionPreprocessor(
    private val input: InputComponent,
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
        require(input.runtimeShape[0] == 1 && input.runtimeShape[1] == CHANNELS)
        require(inputHeight in MIN_SIDE..MAX_SIDE && inputWidth in MIN_SIDE..MAX_SIDE)
        require(inputHeight % 32 == 0 && inputWidth % 32 == 0)
    }

    override fun prepare(frame: CanonicalFrame): PreparedInput {
        val scale = min(inputWidth.toFloat() / frame.width, inputHeight.toFloat() / frame.height)
        val resizedWidth = (frame.width * scale).toInt().coerceIn(1, inputWidth)
        val resizedHeight = (frame.height * scale).toInt().coerceIn(1, inputHeight)
        val offsetX = (inputWidth - resizedWidth) / 2
        val offsetY = (inputHeight - resizedHeight) / 2
        val bytes = ByteArray(input.runtimeShape.checkedElementCount() * Float.SIZE_BYTES)
        val values = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val planeSize = inputHeight * inputWidth

        repeat(resizedHeight) { destinationY ->
            val sourceY = halfPixelSource(destinationY, resizedHeight, frame.height)
            val y0 = floor(sourceY).toInt().coerceIn(0, frame.height - 1)
            val y1 = (y0 + 1).coerceAtMost(frame.height - 1)
            val yWeight = (sourceY - y0).toFloat().coerceIn(0f, 1f)
            repeat(resizedWidth) { destinationX ->
                val sourceX = halfPixelSource(destinationX, resizedWidth, frame.width)
                val x0 = floor(sourceX).toInt().coerceIn(0, frame.width - 1)
                val x1 = (x0 + 1).coerceAtMost(frame.width - 1)
                val xWeight = (sourceX - x0).toFloat().coerceIn(0f, 1f)
                repeat(CHANNELS) { bgrChannel ->
                    val rgbChannel = CHANNELS - 1 - bgrChannel
                    val top = lerp(
                        frame.pixel(x0, y0, rgbChannel),
                        frame.pixel(x1, y0, rgbChannel),
                        xWeight,
                    )
                    val bottom = lerp(
                        frame.pixel(x0, y1, rgbChannel),
                        frame.pixel(x1, y1, rgbChannel),
                        xWeight,
                    )
                    val pixel = lerp(top, bottom, yWeight) / 255f
                    val normalized = (pixel - MEAN[bgrChannel]) / STANDARD_DEVIATION[bgrChannel]
                    val x = offsetX + destinationX
                    val y = offsetY + destinationY
                    val element = bgrChannel * planeSize + y * inputWidth + x
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
                scaleY = resizedHeight.toFloat() / frame.height,
                offsetX = offsetX.toFloat(),
                offsetY = offsetY.toFloat(),
            ),
        )
    }

    private fun CanonicalFrame.pixel(x: Int, y: Int, channel: Int): Float =
        (rgb888[(y * width + x) * CHANNELS + channel].toInt() and 0xff).toFloat()

    private fun halfPixelSource(destination: Int, destinationSize: Int, sourceSize: Int): Double =
        ((destination + 0.5) * sourceSize / destinationSize - 0.5)
            .coerceIn(0.0, (sourceSize - 1).toDouble())

    private fun lerp(start: Float, end: Float, weight: Float): Float =
        start + (end - start) * weight

    companion object {
        const val ID = "ppocr_det_rec_bgr_nchw_float32_v1"
        private const val CHANNELS = 3
        private const val MIN_SIDE = 32
        private const val MAX_SIDE = 1_280
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STANDARD_DEVIATION = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
