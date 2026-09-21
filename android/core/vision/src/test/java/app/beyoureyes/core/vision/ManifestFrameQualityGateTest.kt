package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestFrameQualityGateTest {
    @Test
    fun `sharp normally exposed frame passes conservative visual profile`() {
        val frame = checkerFrame()

        val metrics = RgbFrameQualityAnalyzer.measure(frame)

        assertTrue(metrics.meanLuma in 10.0..245.0)
        assertTrue(metrics.laplacianVariance >= 8.0)
        assertEquals(
            QualityGateResult.Accepted,
            ConservativeRgbFrameQualityGate(FrameQualityProfile.GENERAL_VISUAL_V1)
                .evaluate(frame),
        )
    }

    @Test
    fun `extreme exposure and flat frames fail with stable diagnostics`() {
        val gate = ConservativeRgbFrameQualityGate(FrameQualityProfile.GENERAL_VISUAL_V1)

        assertEquals(
            QualityGateResult.Rejected(diagnosticCode = "frame_underexposed"),
            gate.evaluate(solidFrame(2)),
        )
        assertEquals(
            QualityGateResult.Rejected(diagnosticCode = "frame_overexposed"),
            gate.evaluate(solidFrame(253)),
        )
        assertEquals(
            QualityGateResult.Rejected(diagnosticCode = "frame_too_blurry_or_flat"),
            gate.evaluate(solidFrame(120)),
        )
    }

    @Test
    fun `pipeline turns a quality rejection into typed unavailable before inference`() {
        var inferenceCalls = 0
        val pipeline = ObservationPipeline(
            normalizer = UprightRgbFrameNormalizer,
            qualityGate = ConservativeRgbFrameQualityGate(FrameQualityProfile.GENERAL_VISUAL_V1),
            preprocessor = InputPreprocessor { error("preprocess must not run") },
            backend = InferenceBackend {
                inferenceCalls++
                error("backend must not run")
            },
            adapter = OutputAdapter { _, _, _ -> error("adapter must not run") },
        )

        val result = pipeline.process(sourceFrame(solidFrame(120)))

        val unavailable = result.observation as Observation.Unavailable
        assertEquals(UnavailableReason.LOW_QUALITY, unavailable.reason)
        assertEquals("frame_too_blurry_or_flat", unavailable.diagnosticCode)
        assertEquals(0, inferenceCalls)
    }

    @Test
    fun `every runtime family maps to a finite non accepting quality contract`() {
        RecipeFamily.entries.forEach { family ->
            val result = ManifestFrameQualityGates.forFamily(family).evaluate(solidFrame(120))
            assertTrue("$family unexpectedly accepted a flat frame", result is QualityGateResult.Rejected)
        }
    }

    @Test
    fun `structured reading accepts sharp dark digits on a mostly white display`() {
        val frame = highContrastDisplayFrame(background = 255, foreground = 0)
        val metrics = RgbFrameQualityAnalyzer.measure(frame)

        assertTrue(metrics.meanLuma > FrameQualityProfile.GENERAL_VISUAL_V1.maximumMeanLuma)
        assertTrue(metrics.laplacianVariance >= FrameQualityProfile.STRUCTURED_READING_V1.minimumLaplacianVariance)
        assertEquals(
            QualityGateResult.Accepted,
            ConservativeRgbFrameQualityGate(FrameQualityProfile.STRUCTURED_READING_V1).evaluate(frame),
        )
    }

    @Test
    fun `structured reading accepts sharp light digits on a mostly black display`() {
        val frame = highContrastDisplayFrame(background = 0, foreground = 255)
        val metrics = RgbFrameQualityAnalyzer.measure(frame)

        assertTrue(metrics.meanLuma < FrameQualityProfile.GENERAL_VISUAL_V1.minimumMeanLuma)
        assertTrue(metrics.laplacianVariance >= FrameQualityProfile.STRUCTURED_READING_V1.minimumLaplacianVariance)
        assertEquals(
            QualityGateResult.Accepted,
            ConservativeRgbFrameQualityGate(FrameQualityProfile.STRUCTURED_READING_V1).evaluate(frame),
        )
    }

    @Test
    fun `structured reading still rejects blank black white and gray frames`() {
        val gate = ConservativeRgbFrameQualityGate(FrameQualityProfile.STRUCTURED_READING_V1)

        listOf(0, 120, 255).forEach { value ->
            assertTrue("blank $value frame unexpectedly accepted", gate.evaluate(solidFrame(value)) is QualityGateResult.Rejected)
        }
    }

    @Test
    fun `deterministic textured RGB fixtures pass the conservative live gate`() {
        // Raw VM-004 replay frames are evidence inputs, intentionally ignored, and never a CI dependency.
        val frames = mapOf(
            "landscape" to texturedFrame(width = 96, height = 64, seed = 11),
            "portrait" to texturedFrame(width = 64, height = 96, seed = 29),
            "wide" to texturedFrame(width = 112, height = 56, seed = 47),
        )
        val gate = ConservativeRgbFrameQualityGate(FrameQualityProfile.GENERAL_VISUAL_V1)

        frames.forEach { (name, frame) ->
            assertEquals(name, QualityGateResult.Accepted, gate.evaluate(frame))
        }
    }

    private fun checkerFrame(size: Int = 96): CanonicalFrame {
        val rgb = ByteArray(size * size * 3)
        repeat(size) { y ->
            repeat(size) { x ->
                val value = if (((x / 8) + (y / 8)) % 2 == 0) 35 else 220
                val offset = (y * size + x) * 3
                rgb[offset] = value.toByte()
                rgb[offset + 1] = value.toByte()
                rgb[offset + 2] = value.toByte()
            }
        }
        return CanonicalFrame(1, 0, null, size, size, rgb)
    }

    private fun texturedFrame(width: Int, height: Int, seed: Int): CanonicalFrame {
        val rgb = ByteArray(width * height * 3)
        repeat(height) { y ->
            repeat(width) { x ->
                val offset = (y * width + x) * 3
                rgb[offset] = (20 + ((x * 37 + y * 17 + seed) % 216)).toByte()
                rgb[offset + 1] = (20 + ((x * 13 + y * 43 + seed * 3) % 216)).toByte()
                rgb[offset + 2] = (20 + ((x * 29 + y * 23 + seed * 5) % 216)).toByte()
            }
        }
        return CanonicalFrame(1, 0, null, width, height, rgb)
    }

    private fun highContrastDisplayFrame(background: Int, foreground: Int): CanonicalFrame {
        val width = 320
        val height = 48
        val rgb = ByteArray(width * height * 3) { background.toByte() }

        // Three small seven-segment-style glyphs occupy less than four percent of the display.
        // This reproduces the exposure distribution of a monitor showing a short value such as
        // "123" without depending on fonts or Android rendering in a JVM test.
        val segments = listOf(
            PixelRect(130, 12, 132, 36),
            PixelRect(142, 10, 154, 12),
            PixelRect(152, 12, 154, 22),
            PixelRect(142, 21, 154, 23),
            PixelRect(142, 34, 154, 36),
            PixelRect(164, 10, 176, 12),
            PixelRect(174, 12, 176, 36),
            PixelRect(164, 21, 176, 23),
            PixelRect(164, 34, 176, 36),
        )
        segments.forEach { rect ->
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    val offset = (y * width + x) * 3
                    rgb[offset] = foreground.toByte()
                    rgb[offset + 1] = foreground.toByte()
                    rgb[offset + 2] = foreground.toByte()
                }
            }
        }
        return CanonicalFrame(1, 0, null, width, height, rgb)
    }

    private fun solidFrame(value: Int, size: Int = 64) = CanonicalFrame(
        sourceSequence = 1,
        monotonicTimeMillis = 0,
        capturedAtEpochMillis = null,
        width = size,
        height = size,
        rgb888 = ByteArray(size * size * 3) { value.toByte() },
    )

    private fun sourceFrame(frame: CanonicalFrame) = SourceFrame(
        sourceSequence = frame.sourceSequence,
        monotonicTimeMillis = frame.monotonicTimeMillis,
        capturedAtEpochMillis = frame.capturedAtEpochMillis,
        width = frame.width,
        height = frame.height,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, frame.width, frame.height),
        pixels = FramePixels.Rgb888(frame.rgb888, frame.width * 3),
    )
}
