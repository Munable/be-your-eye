package app.beyoureyes.core.vision

import java.nio.ByteBuffer

/** Stable failure categories for the CameraX-to-canonical frame boundary. */
enum class FrameNormalizationFailure {
    UNSUPPORTED_PIXEL_FORMAT,
    INVALID_DIMENSIONS,
    INVALID_CROP,
    INVALID_ROTATION,
    INVALID_PLANE_LAYOUT,
    BUFFER_TOO_SMALL,
}

/**
 * A controlled, non-data-bearing error. [ObservationPipeline] converts this into `unavailable`.
 *
 * The exception deliberately reports only the failed contract and plane name. It never includes
 * camera bytes or other frame contents.
 */
class FrameNormalizationException(
    val failure: FrameNormalizationFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * A read-only view over one CameraX `ImageProxy.PlaneProxy` buffer.
 *
 * The bytes between [buffer]'s current position and limit are plane byte zero onward. The mapper
 * duplicates the buffer and does not mutate its position. Callers must create the [SourceFrame]
 * before closing the `ImageProxy`; the returned frame owns copied bytes and remains valid after
 * the proxy is closed.
 */
data class CameraPlaneBufferView(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
)

/**
 * Thin, CameraX-free mapping boundary for an app analyzer.
 *
 * The app maps `planes[0]`, `planes[1]`, and `planes[2]` to Y, U, and V respectively and forwards
 * `width`, `height`, `cropRect`, and `imageInfo.rotationDegrees` unchanged. Only Android's
 * `YUV_420_888` three-plane layout is accepted.
 */
object CameraYuv420FrameMapper {
    fun copyToSourceFrame(
        sourceSequence: Long,
        monotonicTimeMillis: Long,
        capturedAtEpochMillis: Long?,
        width: Int,
        height: Int,
        cropRect: PixelRect,
        rotationDegrees: Int,
        yPlane: CameraPlaneBufferView,
        uPlane: CameraPlaneBufferView,
        vPlane: CameraPlaneBufferView,
    ): SourceFrame {
        validateMetadata(
            sourceSequence = sourceSequence,
            monotonicTimeMillis = monotonicTimeMillis,
            capturedAtEpochMillis = capturedAtEpochMillis,
            width = width,
            height = height,
            cropRect = cropRect,
            rotationDegrees = rotationDegrees,
        )

        return SourceFrame(
            sourceSequence = sourceSequence,
            monotonicTimeMillis = monotonicTimeMillis,
            capturedAtEpochMillis = capturedAtEpochMillis,
            width = width,
            height = height,
            rotationDegrees = rotationDegrees,
            cropRect = cropRect,
            pixels = FramePixels.Yuv420(
                yPlane = yPlane.copyOwned("Y"),
                uPlane = uPlane.copyOwned("U"),
                vPlane = vPlane.copyOwned("V"),
            ),
        )
    }

    private fun CameraPlaneBufferView.copyOwned(name: String): YuvPlane {
        if (rowStride <= 0 || pixelStride <= 0) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_PLANE_LAYOUT,
                "$name plane strides must be positive",
            )
        }
        val source = try {
            buffer.duplicate()
        } catch (error: RuntimeException) {
            throw FrameNormalizationException(
                FrameNormalizationFailure.INVALID_PLANE_LAYOUT,
                "$name plane buffer could not be duplicated",
                error,
            )
        }
        if (!source.hasRemaining()) {
            normalizationFailure(
                FrameNormalizationFailure.BUFFER_TOO_SMALL,
                "$name plane buffer is empty",
            )
        }
        val bytes = ByteArray(source.remaining())
        try {
            source.get(bytes)
        } catch (error: RuntimeException) {
            throw FrameNormalizationException(
                FrameNormalizationFailure.BUFFER_TOO_SMALL,
                "$name plane buffer could not be copied",
                error,
            )
        }
        return YuvPlane(bytes = bytes, rowStride = rowStride, pixelStride = pixelStride)
    }

    private fun validateMetadata(
        sourceSequence: Long,
        monotonicTimeMillis: Long,
        capturedAtEpochMillis: Long?,
        width: Int,
        height: Int,
        cropRect: PixelRect,
        rotationDegrees: Int,
    ) {
        if (sourceSequence < 0 || monotonicTimeMillis < 0 ||
            (capturedAtEpochMillis != null && capturedAtEpochMillis < 0)
        ) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_DIMENSIONS,
                "frame sequence and timestamps must be non-negative",
            )
        }
        if (width <= 0 || height <= 0) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_DIMENSIONS,
                "frame dimensions must be positive",
            )
        }
        if (rotationDegrees !in SUPPORTED_ROTATIONS) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_ROTATION,
                "rotation must be one of 0, 90, 180, or 270",
            )
        }
        if (cropRect.left < 0 || cropRect.top < 0 ||
            cropRect.right > width || cropRect.bottom > height ||
            cropRect.left >= cropRect.right || cropRect.top >= cropRect.bottom
        ) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_CROP,
                "crop must be a non-empty rectangle inside the source frame",
            )
        }
    }
}

/**
 * Converts copied CameraX `YUV_420_888` planes into tightly packed, upright RGB888.
 *
 * Plane layout is validated against the full declared source frame, including row/pixel strides
 * and odd dimensions. The source [SourceFrame.cropRect] is applied before the clockwise CameraX
 * buffer-to-upright rotation. Conversion uses Android camera's conventional BT.601 limited-range
 * integer matrix and clamps every output channel to 0..255.
 */
object Yuv420FrameNormalizer : FrameNormalizer {
    override fun normalize(frame: SourceFrame): CanonicalFrame {
        val pixels = frame.pixels as? FramePixels.Yuv420
            ?: normalizationFailure(
                FrameNormalizationFailure.UNSUPPORTED_PIXEL_FORMAT,
                "Yuv420FrameNormalizer requires YUV_420_888 planes",
            )

        validatePlane(
            name = "Y",
            plane = pixels.yPlane,
            logicalWidth = frame.width,
            logicalHeight = frame.height,
        )
        val chromaWidth = ceilHalf(frame.width)
        val chromaHeight = ceilHalf(frame.height)
        validatePlane("U", pixels.uPlane, chromaWidth, chromaHeight)
        validatePlane("V", pixels.vPlane, chromaWidth, chromaHeight)

        val cropWidth = frame.cropRect.right - frame.cropRect.left
        val cropHeight = frame.cropRect.bottom - frame.cropRect.top
        val outputWidth = if (frame.rotationDegrees == 90 || frame.rotationDegrees == 270) {
            cropHeight
        } else {
            cropWidth
        }
        val outputHeight = if (frame.rotationDegrees == 90 || frame.rotationDegrees == 270) {
            cropWidth
        } else {
            cropHeight
        }
        val outputSize = checkedRgbSize(outputWidth, outputHeight)
        val output = ByteArray(outputSize)

        for (sourceY in frame.cropRect.top until frame.cropRect.bottom) {
            for (sourceX in frame.cropRect.left until frame.cropRect.right) {
                val localX = sourceX - frame.cropRect.left
                val localY = sourceY - frame.cropRect.top
                val destinationPixel = rotatedPixelIndex(
                    x = localX,
                    y = localY,
                    width = cropWidth,
                    height = cropHeight,
                    outputWidth = outputWidth,
                    rotationDegrees = frame.rotationDegrees,
                )

                val y = pixels.yPlane.sample(sourceX, sourceY)
                val chromaX = sourceX / 2
                val chromaY = sourceY / 2
                val u = pixels.uPlane.sample(chromaX, chromaY)
                val v = pixels.vPlane.sample(chromaX, chromaY)
                val destination = destinationPixel * RGB_CHANNEL_COUNT
                writeBt601LimitedRgb(output, destination, y, u, v)
            }
        }

        return CanonicalFrame(
            sourceSequence = frame.sourceSequence,
            monotonicTimeMillis = frame.monotonicTimeMillis,
            capturedAtEpochMillis = frame.capturedAtEpochMillis,
            width = outputWidth,
            height = outputHeight,
            rgb888 = output,
        )
    }

    private fun validatePlane(
        name: String,
        plane: YuvPlane,
        logicalWidth: Int,
        logicalHeight: Int,
    ) {
        val lastRowWidth = checkedAdd(
            checkedMultiply((logicalWidth - 1).toLong(), plane.pixelStride.toLong(), name),
            1L,
            name,
        )
        if (plane.rowStride.toLong() < lastRowWidth) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_PLANE_LAYOUT,
                "$name plane rowStride cannot address its logical row",
            )
        }
        val requiredBytes = checkedAdd(
            checkedMultiply((logicalHeight - 1).toLong(), plane.rowStride.toLong(), name),
            lastRowWidth,
            name,
        )
        if (requiredBytes > plane.bytes.size.toLong()) {
            normalizationFailure(
                FrameNormalizationFailure.BUFFER_TOO_SMALL,
                "$name plane buffer is shorter than its declared layout",
            )
        }
    }

    private fun checkedMultiply(left: Long, right: Long, planeName: String): Long {
        if (left != 0L && right > Long.MAX_VALUE / left) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_PLANE_LAYOUT,
                "$planeName plane layout overflows",
            )
        }
        return left * right
    }

    private fun checkedAdd(left: Long, right: Long, planeName: String): Long {
        if (right > Long.MAX_VALUE - left) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_PLANE_LAYOUT,
                "$planeName plane layout overflows",
            )
        }
        return left + right
    }

    private fun YuvPlane.sample(x: Int, y: Int): Int {
        val index = y * rowStride + x * pixelStride
        return bytes[index].toInt() and UNSIGNED_BYTE_MASK
    }

    /** Returns the tightly packed upright pixel index without allocating per-pixel coordinates. */
    private fun rotatedPixelIndex(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        outputWidth: Int,
        rotationDegrees: Int,
    ): Int = when (rotationDegrees) {
        0 -> y * outputWidth + x
        90 -> x * outputWidth + (height - 1 - y)
        180 -> (height - 1 - y) * outputWidth + (width - 1 - x)
        270 -> (width - 1 - x) * outputWidth + y
        else -> normalizationFailure(
            FrameNormalizationFailure.INVALID_ROTATION,
            "rotation must be one of 0, 90, 180, or 270",
        )
    }

    private fun writeBt601LimitedRgb(
        output: ByteArray,
        offset: Int,
        y: Int,
        u: Int,
        v: Int,
    ) {
        val luminance = y - Y_OFFSET
        val blueDifference = u - CHROMA_OFFSET
        val redDifference = v - CHROMA_OFFSET
        val scaledY = Y_SCALE * luminance
        val red = (scaledY + RED_V_SCALE * redDifference + ROUNDING) shr FRACTION_BITS
        val green = (
            scaledY - GREEN_U_SCALE * blueDifference - GREEN_V_SCALE * redDifference + ROUNDING
            ) shr FRACTION_BITS
        val blue = (scaledY + BLUE_U_SCALE * blueDifference + ROUNDING) shr FRACTION_BITS

        output[offset] = red.coerceIn(0, 255).toByte()
        output[offset + 1] = green.coerceIn(0, 255).toByte()
        output[offset + 2] = blue.coerceIn(0, 255).toByte()
    }

    private fun checkedRgbSize(width: Int, height: Int): Int {
        val size = width.toLong() * height.toLong() * RGB_CHANNEL_COUNT
        if (size > Int.MAX_VALUE) {
            normalizationFailure(
                FrameNormalizationFailure.INVALID_DIMENSIONS,
                "canonical RGB frame is too large",
            )
        }
        return size.toInt()
    }

    private fun ceilHalf(value: Int): Int = value / 2 + value % 2

    private const val RGB_CHANNEL_COUNT = 3
    private const val UNSIGNED_BYTE_MASK = 0xff
    private const val Y_OFFSET = 16
    private const val CHROMA_OFFSET = 128
    private const val Y_SCALE = 298
    private const val RED_V_SCALE = 409
    private const val GREEN_U_SCALE = 100
    private const val GREEN_V_SCALE = 208
    private const val BLUE_U_SCALE = 516
    private const val ROUNDING = 128
    private const val FRACTION_BITS = 8
}

private val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)

private fun normalizationFailure(
    failure: FrameNormalizationFailure,
    message: String,
): Nothing = throw FrameNormalizationException(failure, message)
