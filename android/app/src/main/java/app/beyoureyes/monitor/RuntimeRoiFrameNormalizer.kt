package app.beyoureyes.monitor

import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.vision.CanonicalFrame
import app.beyoureyes.core.vision.FrameNormalizer
import app.beyoureyes.core.vision.SourceFrame
import kotlin.math.ceil
import kotlin.math.floor

/** Applies the product-defined runtime region before state/reading recipes; detector coordinates stay global. */
internal class RuntimeRoiFrameNormalizer(
    private val delegate: FrameNormalizer,
    private val roi: NormalizedRect,
    private val cropToRoi: Boolean,
) : FrameNormalizer {
    override fun normalize(frame: SourceFrame): CanonicalFrame {
        val canonical = delegate.normalize(frame)
        if (!cropToRoi || roi == FULL_FRAME) return canonical
        val left = floor(roi.left * canonical.width).toInt().coerceIn(0, canonical.width - 1)
        val top = floor(roi.top * canonical.height).toInt().coerceIn(0, canonical.height - 1)
        val right = ceil(roi.right * canonical.width).toInt().coerceIn(left + 1, canonical.width)
        val bottom = ceil(roi.bottom * canonical.height).toInt().coerceIn(top + 1, canonical.height)
        val width = right - left
        val height = bottom - top
        val output = ByteArray(width * height * RGB_CHANNELS)
        repeat(height) { row ->
            val sourceOffset = ((top + row) * canonical.width + left) * RGB_CHANNELS
            canonical.rgb888.copyInto(
                output,
                destinationOffset = row * width * RGB_CHANNELS,
                startIndex = sourceOffset,
                endIndex = sourceOffset + width * RGB_CHANNELS,
            )
        }
        return canonical.copy(width = width, height = height, rgb888 = output)
    }

    private companion object {
        const val RGB_CHANNELS = 3
        val FULL_FRAME = NormalizedRect(0f, 0f, 1f, 1f)
    }
}
