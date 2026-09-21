package app.beyoureyes.core.vision

/**
 * A frame at the only source-specific boundary in the vision runtime.
 *
 * CameraX supplies [FramePixels.Yuv420]. Static-image and decoded-video replay supplies
 * [FramePixels.Rgb888]. Both must pass through the same [FrameNormalizer] and every downstream
 * production stage; replay is not allowed to inject fabricated model results.
 */
data class SourceFrame(
    val sourceSequence: Long,
    val monotonicTimeMillis: Long,
    val capturedAtEpochMillis: Long?,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val cropRect: PixelRect,
    val pixels: FramePixels,
) {
    init {
        require(sourceSequence >= 0)
        require(monotonicTimeMillis >= 0)
        require(capturedAtEpochMillis == null || capturedAtEpochMillis >= 0)
        require(width > 0 && height > 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(cropRect.left >= 0 && cropRect.top >= 0)
        require(cropRect.right <= width && cropRect.bottom <= height)
        require(cropRect.left < cropRect.right && cropRect.top < cropRect.bottom)
        if (pixels is FramePixels.Rgb888) {
            require(pixels.rowStride >= width * RGB_CHANNEL_COUNT)
            require(pixels.bytes.size >= pixels.rowStride * height)
        }
    }

    private companion object {
        const val RGB_CHANNEL_COUNT = 3
    }
}

sealed interface FramePixels {
    /** Copied CameraX YUV_420_888 planes; row and pixel strides are preserved. */
    data class Yuv420(
        val yPlane: YuvPlane,
        val uPlane: YuvPlane,
        val vPlane: YuvPlane,
    ) : FramePixels

    /** Upright or source-oriented interleaved RGB bytes. Orientation remains on [SourceFrame]. */
    data class Rgb888(
        val bytes: ByteArray,
        val rowStride: Int,
    ) : FramePixels {
        init {
            require(bytes.isNotEmpty())
            require(rowStride > 0)
        }
    }
}

data class YuvPlane(
    val bytes: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
) {
    init {
        require(bytes.isNotEmpty())
        require(rowStride > 0)
        require(pixelStride > 0)
    }
}

data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left <= right)
        require(top <= bottom)
    }
}

/** Upright, crop-applied RGB shared by CameraX and replay after normalization. */
data class CanonicalFrame(
    val sourceSequence: Long,
    val monotonicTimeMillis: Long,
    val capturedAtEpochMillis: Long?,
    val width: Int,
    val height: Int,
    val rgb888: ByteArray,
) {
    init {
        require(sourceSequence >= 0)
        require(monotonicTimeMillis >= 0)
        require(capturedAtEpochMillis == null || capturedAtEpochMillis >= 0)
        require(width > 0 && height > 0)
        require(rgb888.size == width * height * 3) {
            "canonical RGB must be tightly packed width * height * 3"
        }
    }
}

fun interface FrameNormalizer {
    fun normalize(frame: SourceFrame): CanonicalFrame
}

/** Concrete replay normalizer for already-upright, tightly packed RGB frames. */
object UprightRgbFrameNormalizer : FrameNormalizer {
    override fun normalize(frame: SourceFrame): CanonicalFrame {
        require(frame.rotationDegrees == 0) { "RGB replay frame must already be upright" }
        require(frame.cropRect == PixelRect(0, 0, frame.width, frame.height)) {
            "RGB replay frame must already match the production ViewPort crop"
        }
        val pixels = frame.pixels as? FramePixels.Rgb888
            ?: error("UprightRgbFrameNormalizer only accepts RGB replay frames")
        val tightRowBytes = frame.width * 3
        val tight = if (pixels.rowStride == tightRowBytes) {
            pixels.bytes.copyOf(tightRowBytes * frame.height)
        } else {
            ByteArray(tightRowBytes * frame.height).also { output ->
                repeat(frame.height) { row ->
                    pixels.bytes.copyInto(
                        destination = output,
                        destinationOffset = row * tightRowBytes,
                        startIndex = row * pixels.rowStride,
                        endIndex = row * pixels.rowStride + tightRowBytes,
                    )
                }
            }
        }
        return CanonicalFrame(
            sourceSequence = frame.sourceSequence,
            monotonicTimeMillis = frame.monotonicTimeMillis,
            capturedAtEpochMillis = frame.capturedAtEpochMillis,
            width = frame.width,
            height = frame.height,
            rgb888 = tight,
        )
    }
}

enum class TensorElementType { UINT8, INT8, INT32, INT64, FLOAT16, FLOAT32 }

data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(inputWidth > 0 && inputHeight > 0)
        require(scaleX.isFinite() && scaleX > 0f)
        require(scaleY.isFinite() && scaleY > 0f)
        require(offsetX.isFinite() && offsetX >= 0f)
        require(offsetY.isFinite() && offsetY >= 0f)
    }
}

data class PreparedInput(
    val bytes: ByteArray,
    val shape: List<Int>,
    val elementType: TensorElementType,
    val transform: LetterboxTransform,
) {
    init {
        require(bytes.isNotEmpty())
        require(shape.isNotEmpty() && shape.all { it > 0 })
    }
}

data class RawTensor(
    val name: String,
    val bytes: ByteArray,
    val shape: List<Int>,
    val elementType: TensorElementType,
) {
    init {
        require(name.isNotBlank())
        require(bytes.isNotEmpty())
        require(shape.isNotEmpty() && shape.all { it > 0 })
    }
}

data class RawTensorOutput(val tensors: List<RawTensor>) {
    init {
        require(tensors.isNotEmpty())
        require(tensors.map { it.name }.distinct().size == tensors.size)
    }
}

fun interface InputPreprocessor {
    fun prepare(frame: CanonicalFrame): PreparedInput
}

/** Model execution only. Output interpretation is owned by the package adapter. */
fun interface InferenceBackend {
    fun infer(input: PreparedInput): RawTensorOutput
}

fun interface OutputAdapter {
    fun toObservation(
        output: RawTensorOutput,
        transform: LetterboxTransform,
        frame: CanonicalFrame,
    ): app.beyoureyes.core.domain.Observation
}
