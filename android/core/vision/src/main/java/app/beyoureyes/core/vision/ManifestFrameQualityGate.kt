package app.beyoureyes.core.vision

import kotlin.math.max

/**
 * A small, package-agnostic set of frame quality contracts understood by the app.
 *
 * The current signed Manifest schema does not expose a dedicated quality profile identifier, so
 * the profile is selected only by [RecipeFamily]. A package ID, vendor name, or model filename is
 * never consulted. Adding another compatible package therefore does not require Android code.
 */
object ManifestFrameQualityGates {
    fun forManifest(manifest: ModelPackageManifest): QualityGate = forFamily(manifest.runtimeFamily)

    internal fun forFamily(family: RecipeFamily): QualityGate = when (family) {
        RecipeFamily.OBJECT_DETECTION_V1,
        RecipeFamily.SIMILARITY_MATCH_V1,
        -> ConservativeRgbFrameQualityGate(FrameQualityProfile.GENERAL_VISUAL_V1)

        RecipeFamily.READING_PIPELINE_V1 ->
            ConservativeRgbFrameQualityGate(FrameQualityProfile.STRUCTURED_READING_V1)
    }

    /** Enrollment uses the same conservative visual contract before a reference counts. */
    val referenceEnrollment: QualityGate =
        ConservativeRgbFrameQualityGate(FrameQualityProfile.GENERAL_VISUAL_V1)
}

/** Finite app-owned profiles. Values are deliberately conservative and must be replay-tuned. */
enum class FrameQualityProfile(
    internal val minimumMeanLuma: Double,
    internal val maximumMeanLuma: Double,
    internal val maximumDarkClippedRatio: Double,
    internal val maximumBrightClippedRatio: Double,
    internal val minimumLaplacianVariance: Double,
) {
    GENERAL_VISUAL_V1(
        minimumMeanLuma = 10.0,
        maximumMeanLuma = 245.0,
        maximumDarkClippedRatio = 0.985,
        maximumBrightClippedRatio = 0.985,
        minimumLaplacianVariance = 8.0,
    ),
    STRUCTURED_READING_V1(
        // Numeric displays legitimately use almost-white or almost-black backgrounds. Keep the
        // flatness gate strict, but do not reject a sharp high-contrast display before OCR.
        minimumMeanLuma = 2.0,
        maximumMeanLuma = 253.0,
        maximumDarkClippedRatio = 0.995,
        maximumBrightClippedRatio = 0.995,
        minimumLaplacianVariance = 12.0,
    ),
}

data class FrameQualityMetrics(
    val meanLuma: Double,
    val darkClippedRatio: Double,
    val brightClippedRatio: Double,
    val laplacianVariance: Double,
    val sampledWidth: Int,
    val sampledHeight: Int,
)

class ConservativeRgbFrameQualityGate(
    private val profile: FrameQualityProfile,
) : QualityGate {
    override fun evaluate(frame: CanonicalFrame): QualityGateResult {
        val metrics = RgbFrameQualityAnalyzer.measure(frame)
        return when {
            metrics.meanLuma < profile.minimumMeanLuma ||
                metrics.darkClippedRatio > profile.maximumDarkClippedRatio ->
                QualityGateResult.Rejected(diagnosticCode = "frame_underexposed")

            metrics.meanLuma > profile.maximumMeanLuma ||
                metrics.brightClippedRatio > profile.maximumBrightClippedRatio ->
                QualityGateResult.Rejected(diagnosticCode = "frame_overexposed")

            metrics.sampledWidth < MIN_SHARPNESS_EDGE ||
                metrics.sampledHeight < MIN_SHARPNESS_EDGE ||
                metrics.laplacianVariance < profile.minimumLaplacianVariance ->
                QualityGateResult.Rejected(diagnosticCode = "frame_too_blurry_or_flat")

            else -> QualityGateResult.Accepted
        }
    }

    private companion object {
        const val MIN_SHARPNESS_EDGE = 3
    }
}

/** Bounded RGB measurement shared by live/replay frames and reference enrollment. */
object RgbFrameQualityAnalyzer {
    fun measure(frame: CanonicalFrame): FrameQualityMetrics {
        val stepX = max(1, (frame.width + MAX_SAMPLED_EDGE - 1) / MAX_SAMPLED_EDGE)
        val stepY = max(1, (frame.height + MAX_SAMPLED_EDGE - 1) / MAX_SAMPLED_EDGE)
        val sampledWidth = ((frame.width - 1) / stepX) + 1
        val sampledHeight = ((frame.height - 1) / stepY) + 1
        val luma = IntArray(sampledWidth * sampledHeight)
        var lumaTotal = 0L
        var darkClipped = 0
        var brightClipped = 0
        var outputIndex = 0
        var y = 0
        while (y < frame.height) {
            var x = 0
            while (x < frame.width) {
                val source = (y * frame.width + x) * RGB_CHANNELS
                val red = frame.rgb888[source].toInt() and 0xff
                val green = frame.rgb888[source + 1].toInt() and 0xff
                val blue = frame.rgb888[source + 2].toInt() and 0xff
                val value = (red * 77 + green * 150 + blue * 29 + 128) ushr 8
                luma[outputIndex++] = value
                lumaTotal += value
                if (value <= DARK_CLIP_LUMA) darkClipped++
                if (value >= BRIGHT_CLIP_LUMA) brightClipped++
                x += stepX
            }
            y += stepY
        }
        check(outputIndex == luma.size)

        var laplacianTotal = 0.0
        var laplacianSquaredTotal = 0.0
        var laplacianCount = 0
        if (sampledWidth >= 3 && sampledHeight >= 3) {
            for (sampleY in 1 until sampledHeight - 1) {
                for (sampleX in 1 until sampledWidth - 1) {
                    val index = sampleY * sampledWidth + sampleX
                    val laplacian = 4 * luma[index] -
                        luma[index - 1] - luma[index + 1] -
                        luma[index - sampledWidth] - luma[index + sampledWidth]
                    laplacianTotal += laplacian
                    laplacianSquaredTotal += laplacian.toDouble() * laplacian
                    laplacianCount++
                }
            }
        }
        val laplacianVariance = if (laplacianCount == 0) {
            0.0
        } else {
            val mean = laplacianTotal / laplacianCount
            (laplacianSquaredTotal / laplacianCount - mean * mean).coerceAtLeast(0.0)
        }
        val sampleCount = luma.size.toDouble()
        return FrameQualityMetrics(
            meanLuma = lumaTotal / sampleCount,
            darkClippedRatio = darkClipped / sampleCount,
            brightClippedRatio = brightClipped / sampleCount,
            laplacianVariance = laplacianVariance,
            sampledWidth = sampledWidth,
            sampledHeight = sampledHeight,
        )
    }

    private const val RGB_CHANNELS = 3
    private const val MAX_SAMPLED_EDGE = 256
    private const val DARK_CLIP_LUMA = 4
    private const val BRIGHT_CLIP_LUMA = 251
}
