package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.NormalizedRect
import kotlin.math.ceil
import kotlin.math.floor

/** Maps an upright, ViewPort-normalized ROI back into an ImageProxy buffer crop. */
object RoiCoordinateMapper {
    fun normalizedToBufferRect(
        roi: NormalizedRect,
        imageCropRect: PixelRect,
        rotationDegrees: Int,
    ): PixelRect {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        val cropWidth = imageCropRect.right - imageCropRect.left
        val cropHeight = imageCropRect.bottom - imageCropRect.top
        require(cropWidth > 0 && cropHeight > 0)

        val uprightCorners = listOf(
            Point(roi.left, roi.top),
            Point(roi.right, roi.top),
            Point(roi.right, roi.bottom),
            Point(roi.left, roi.bottom),
        )
        val bufferCorners = uprightCorners.map { it.inverseRotate(rotationDegrees) }
        val minX = bufferCorners.minOf(Point::x)
        val minY = bufferCorners.minOf(Point::y)
        val maxX = bufferCorners.maxOf(Point::x)
        val maxY = bufferCorners.maxOf(Point::y)

        return PixelRect(
            left = floor(imageCropRect.left + minX * cropWidth).toInt()
                .coerceIn(imageCropRect.left, imageCropRect.right),
            top = floor(imageCropRect.top + minY * cropHeight).toInt()
                .coerceIn(imageCropRect.top, imageCropRect.bottom),
            right = ceil(imageCropRect.left + maxX * cropWidth).toInt()
                .coerceIn(imageCropRect.left, imageCropRect.right),
            bottom = ceil(imageCropRect.top + maxY * cropHeight).toInt()
                .coerceIn(imageCropRect.top, imageCropRect.bottom),
        )
    }

    private data class Point(val x: Float, val y: Float) {
        /** ImageProxy rotation is clockwise buffer-to-upright; this is the inverse transform. */
        fun inverseRotate(rotationDegrees: Int): Point = when (rotationDegrees) {
            0 -> this
            90 -> Point(y, 1f - x)
            180 -> Point(1f - x, 1f - y)
            270 -> Point(1f - y, x)
            else -> error("rotation validated by caller")
        }
    }
}

/** Per-frame coordinates consumed by the future inference adapter; it contains no image bytes. */
data class RuntimeFrameMetadata(
    val sourceSequence: Long,
    val captureTimestampNanos: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val imageCropRect: PixelRect,
    val uprightViewPortRoi: NormalizedRect,
    val bufferRoi: PixelRect,
) {
    init {
        require(sourceSequence >= 0)
        require(captureTimestampNanos >= 0)
        require(imageWidth > 0 && imageHeight > 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(imageCropRect.left >= 0 && imageCropRect.top >= 0)
        require(imageCropRect.right <= imageWidth && imageCropRect.bottom <= imageHeight)
        require(imageCropRect.left < imageCropRect.right && imageCropRect.top < imageCropRect.bottom)
        require(bufferRoi.left >= imageCropRect.left && bufferRoi.top >= imageCropRect.top)
        require(bufferRoi.right <= imageCropRect.right && bufferRoi.bottom <= imageCropRect.bottom)
        require(bufferRoi.left < bufferRoi.right && bufferRoi.top < bufferRoi.bottom)
    }

    companion object {
        /** Pure wiring boundary used by CameraX and unit tests for every analyzed frame. */
        fun resolve(
            sourceSequence: Long,
            captureTimestampNanos: Long,
            imageWidth: Int,
            imageHeight: Int,
            rotationDegrees: Int,
            imageCropRect: PixelRect,
            uprightViewPortRoi: NormalizedRect,
        ): RuntimeFrameMetadata {
            val bufferRoi = RoiCoordinateMapper.normalizedToBufferRect(
                roi = uprightViewPortRoi,
                imageCropRect = imageCropRect,
                rotationDegrees = rotationDegrees,
            )
            return RuntimeFrameMetadata(
                sourceSequence = sourceSequence,
                captureTimestampNanos = captureTimestampNanos,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                rotationDegrees = rotationDegrees,
                imageCropRect = imageCropRect,
                uprightViewPortRoi = uprightViewPortRoi,
                bufferRoi = bufferRoi,
            )
        }
    }
}
