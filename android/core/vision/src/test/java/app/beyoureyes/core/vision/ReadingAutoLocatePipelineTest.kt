package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAutoLocatePipelineTest {
    @Test
    fun `automatic feedback scans every frame and keeps the first coordinate anchor`() {
        val fixture = fixture(
            listOf(probabilityMap(twoLines = true), probabilityMap(twoLines = true)),
            recognitions = listOf("11" to 0.95f, "22" to 0.80f),
        )

        val first = fixture.pipeline.process(frame(1)).observation as Observation.Reading
        val second = fixture.pipeline.process(frame(2)).observation as Observation.Reading

        assertEquals(first.anchorBox, second.anchorBox)
        assertEquals(2, fixture.detector.calls)
        assertEquals(2, fixture.recognizer.calls)
    }

    @Test
    fun `an anchored line disappearing never switches to a neighbouring number`() {
        val fixture = fixture(
            listOf(
                probabilityMap(twoLines = true),
                probabilityMap(twoLines = false),
            ),
            recognitions = listOf("11" to 0.95f, "22" to 0.80f),
        )

        val first = fixture.pipeline.process(frame(1)).observation as Observation.Reading
        val miss = fixture.pipeline.process(frame(2)).observation as Observation.Unavailable

        assertTrue(checkNotNull(first.anchorBox).centerY > 0.5f)
        assertEquals("structured_reading_anchor_missing", miss.diagnosticCode)
        assertEquals(2, fixture.detector.calls)
        assertEquals(1, fixture.recognizer.calls)
    }

    @Test
    fun `wide numeric rows never extend the anchor into a neighbouring row`() {
        val fixture = fixture(listOf(probabilityMap(wideRows = true), probabilityMap(topWideOnly = true)))

        val first = fixture.pipeline.process(frame(1)).observation as Observation.Reading
        val missing = fixture.pipeline.process(frame(2)).observation as Observation.Unavailable

        assertTrue(checkNotNull(first.anchorBox).centerY > 0.5f)
        assertEquals("structured_reading_anchor_missing", missing.diagnosticCode)
        assertEquals(1, fixture.recognizer.calls)
    }

    @Test
    fun `centre number wins over a larger higher confidence outer line`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(centreAndLargeOuter = true)),
            recognitions = listOf("22" to 0.85f, "999" to 0.99f),
        )

        val reading = fixture.pipeline.process(frame(1)).observation as Observation.Reading

        assertEquals("22", reading.text)
        val anchor = checkNotNull(reading.anchorBox)
        assertTrue(anchor.centerY in 0.45f..0.55f)
        assertEquals(1, fixture.recognizer.calls)
    }

    @Test
    fun `nearest candidate survives the candidate cap regardless of outer line size`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(manyCandidates = true)),
            recognitions = List(5) { "42" to 0.85f },
        )

        val reading = fixture.pipeline.process(frame(1)).observation as Observation.Reading

        assertTrue(checkNotNull(reading.anchorBox).centerY in 0.45f..0.55f)
        assertEquals(1, fixture.recognizer.calls)
    }

    @Test
    fun `multiple readable numbers do not require a manual region`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(twoLines = true)),
            recognitions = listOf("11" to 0.99f, "22" to 0.98f),
        )

        val reading = fixture.pipeline.process(frame(1)).observation as Observation.Reading

        assertTrue(checkNotNull(reading.anchorBox).centerY > 0.5f)
        assertEquals(1, fixture.recognizer.calls)
    }

    @Test
    fun `scan again discards the old coordinate anchor and changes tracking identity`() {
        val fixture = fixture(listOf(probabilityMap(twoLines = true), probabilityMap()))
        val first = fixture.pipeline.process(frame(1)).observation as Observation.Reading

        fixture.pipeline.resetAutomaticAnchor()
        val next = fixture.pipeline.process(frame(2)).observation as Observation.Reading

        assertTrue(checkNotNull(first.anchorBox).centerY > 0.5f)
        assertTrue(checkNotNull(next.anchorBox).centerY < 0.5f)
        assertTrue(first.targetTrackId != next.targetTrackId)
    }

    @Test
    fun `automatic scene sized component is unavailable instead of becoming a false reading`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(sceneSized = true)),
            recognitions = listOf("123" to 0.99f),
        )

        val observation = fixture.pipeline.process(frame(1)).observation as Observation.Unavailable

        assertEquals("numeric_target_shape_unavailable", observation.diagnosticCode)
        assertEquals(0, fixture.recognizer.calls)
    }

    @Test
    fun `unclip padding touching the frame keeps a valid detector core readable`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(edgeExpandedLine = true)),
            recognitions = listOf("1234" to 0.95f),
        )

        val observation = fixture.pipeline.process(frame(1)).observation as Observation.Reading

        assertEquals("1234", observation.text)
        val anchor = checkNotNull(observation.anchorBox)
        assertTrue(anchor.left == 0f || anchor.right == 1f)
        assertEquals(1, fixture.recognizer.calls)
    }

    @Test
    fun `edge clamping does not admit an expanded near full frame component`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(edgeClampedLargeCore = true)),
            recognitions = listOf("1234" to 0.95f),
        )

        val observation = fixture.pipeline.process(frame(1)).observation as Observation.Unavailable

        assertEquals("numeric_target_shape_unavailable", observation.diagnosticCode)
        assertEquals(0, fixture.recognizer.calls)
    }

    @Test
    fun `automatic vertical or tiny component is unavailable instead of becoming a false reading`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(verticalOrTiny = true)),
            recognitions = listOf("123" to 0.99f),
        )

        val observation = fixture.pipeline.process(frame(1)).observation as Observation.Unavailable

        assertEquals("numeric_target_shape_unavailable", observation.diagnosticCode)
        assertEquals(0, fixture.recognizer.calls)
    }

    @Test
    fun `automatic long thin line remains readable for timer-like strings`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(longThinLine = true)),
            recognitions = listOf("142" to 0.99f),
        )
        val observation = fixture.pipeline.process(frame(1)).observation as Observation.Reading

        assertEquals("142", observation.text)
        assertEquals("142", observation.valueDecimal)
        assertEquals(1, fixture.recognizer.calls)
    }

    @Test
    fun `equally sized lines choose the one closer to the centre`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(ambiguousLines = true)),
            recognitions = listOf("11" to 0.99f, "22" to 0.99f),
        )

        val observation = fixture.pipeline.process(frame(1)).observation as Observation.Reading

        assertEquals("11", observation.text)
        assertTrue(checkNotNull(observation.anchorBox).centerY > 0.5f)
        assertEquals(1, fixture.recognizer.calls)
    }

    @Test
    fun `non numeric high score line does not mask the only readable number`() {
        val fixture = fixture(
            maps = listOf(probabilityMap(ambiguousLines = true)),
            recognitions = listOf(null to 0f, "42" to 0.99f),
        )

        val observation = fixture.pipeline.process(frame(1)).observation as Observation.Reading

        assertEquals("42", observation.text)
        assertEquals(2, fixture.recognizer.calls)
    }

    @Test
    fun `manual region goes directly to recognition and can be cleared`() {
        val manual = NormalizedRect(0.1f, 0.1f, 0.9f, 0.5f)
        val fixture = fixture(
            maps = listOf(probabilityMap(twoLines = false), probabilityMap(twoLines = false)),
            initialManualScanRegion = manual,
        )

        val restricted = fixture.pipeline.process(frame(1)).observation as Observation.Reading
        assertEquals(manual, restricted.anchorBox)
        assertEquals(0, fixture.detector.calls)
        assertTrue(fixture.recognizer.lastInputBytes < FRAME_SIZE * FRAME_SIZE * 3)

        fixture.pipeline.updateManualScanRegion(null)
        val fullFrame = fixture.pipeline.process(frame(2)).observation as Observation.Reading
        assertTrue(checkNotNull(fullFrame.anchorBox).centerY < 0.5f)
        assertEquals(1, fixture.detector.calls)
        assertEquals(FRAME_SIZE * FRAME_SIZE * 3, fixture.detector.lastInputBytes)
    }

    private fun fixture(
        maps: List<RawTensor>,
        initialManualScanRegion: NormalizedRect? = null,
        recognitions: List<Pair<String?, Float>> = emptyList(),
    ): Fixture {
        val detectorOutputs = ArrayDeque(maps)
        val detector = CountingSession("locator") {
            mapOf("probability" to detectorOutputs.removeFirst())
        }
        val recognizer = CountingSession("primary") {
            mapOf(
                "logits" to RawTensor(
                    name = "logits",
                    bytes = byteArrayOf(1),
                    shape = listOf(1),
                    elementType = TensorElementType.UINT8,
                ),
            )
        }
        val preprocessor = InputPreprocessor { frame ->
            PreparedInput(
                bytes = frame.rgb888,
                shape = listOf(1, frame.height, frame.width, 3),
                elementType = TensorElementType.UINT8,
                transform = LetterboxTransform(
                    frame.width,
                    frame.height,
                    FRAME_SIZE,
                    FRAME_SIZE,
                    FRAME_SIZE.toFloat() / frame.width,
                    FRAME_SIZE.toFloat() / frame.height,
                    0f,
                    0f,
                ),
            )
        }
        val recognitionResults = ArrayDeque(recognitions)
        return Fixture(
            pipeline = ReadingScenePipeline(
                normalizer = UprightRgbFrameNormalizer,
                qualityGate = AcceptAllQualityGate,
                detectorInput = input("locator", "image"),
                detectorOutput = OutputComponent(
                    role = OutputRole.TEXT_PROBABILITY_MAP,
                    artifactRole = "locator",
                    tensorIndex = 0,
                    tensorName = "probability",
                    dataType = TensorDataType.FLOAT32,
                    runtimeShape = listOf(1, 1, FRAME_SIZE, FRAME_SIZE),
                ),
                detectorPreprocessor = preprocessor,
                detectorSession = detector,
                recognizerInput = input("primary", "image"),
                recognizerPreprocessor = preprocessor,
                recognizerSession = recognizer,
                recognizerAdapter = OutputAdapter { _, _, frame ->
                    val result = if (recognitionResults.isEmpty()) null else {
                        recognitionResults.removeFirst()
                    }
                    if (result == null) {
                        reading(frame.sourceSequence, "7.58")
                    } else if (result.first == null) {
                        Observation.Unavailable(
                            reason = UnavailableReason.LOW_QUALITY,
                            diagnosticCode = "recognizer_non_numeric",
                            sourceSequence = frame.sourceSequence,
                        )
                    } else {
                        reading(frame.sourceSequence, checkNotNull(result.first), result.second)
                    }
                },
                pixelThreshold = 0.2f,
                boxThreshold = 0.4f,
                unclipRatio = 1.4f,
                maximumCandidates = 20,
                initialManualScanRegion = initialManualScanRegion,
                clock = IncrementingNanoClock(),
            ),
            detector = detector,
            recognizer = recognizer,
        )
    }

    private fun input(role: String, name: String) = InputComponent(
        role = InputRole.IMAGE,
        artifactRole = role,
        tensorIndex = 0,
        tensorName = name,
        dataType = TensorDataType.UINT8,
        runtimeShape = listOf(1, FRAME_SIZE, FRAME_SIZE, 3),
        quantization = InputQuantization(QuantizationMode.NONE),
        layout = TensorLayout.NHWC,
        colorSpace = ColorSpace.RGB,
    )

    private fun probabilityMap(
        twoLines: Boolean = false,
        bottomOnly: Boolean = false,
        sceneSized: Boolean = false,
        verticalOrTiny: Boolean = false,
        ambiguousLines: Boolean = false,
        longThinLine: Boolean = false,
        manyCandidates: Boolean = false,
        centreAndLargeOuter: Boolean = false,
        wideRows: Boolean = false,
        topWideOnly: Boolean = false,
        edgeExpandedLine: Boolean = false,
        edgeClampedLargeCore: Boolean = false,
    ): RawTensor {
        val values = FloatArray(FRAME_SIZE * FRAME_SIZE)
        if (wideRows || topWideOnly) {
            fill(values, 8, 14, 48, 22, 0.95f)
            if (wideRows) fill(values, 8, 34, 48, 42, 0.95f)
        } else if (centreAndLargeOuter) {
            fill(values, 23, 29, 41, 34, 0.85f)
            fill(values, 5, 4, 32, 15, 0.99f)
        } else if (manyCandidates) {
            fill(values, 18, 1, 33, 3, 0.99f)
            fill(values, 18, 11, 33, 13, 0.98f)
            fill(values, 18, 21, 33, 23, 0.97f)
            fill(values, 18, 31, 33, 33, 0.96f)
            fill(values, 18, 41, 33, 43, 0.95f)
            fill(values, 14, 51, 45, 58, 0.81f)
        } else if (edgeExpandedLine) {
            fill(values, 5, 20, 58, 39, 0.95f)
        } else if (edgeClampedLargeCore) {
            // Dilation yields a plausible 93.75% x 59.4% detector core. The signed 1.4 unclip
            // clamps its width to the frame but also expands its height beyond the line guard.
            fill(values, 3, 13, 58, 48, 0.95f)
        } else if (ambiguousLines) {
            fill(values, 8, 10, 26, 20, 0.95f)
            fill(values, 8, 38, 26, 48, 0.94f)
        } else if (verticalOrTiny) {
            fill(values, 12, 12, 18, 45, 0.95f)
        } else if (sceneSized) {
            fill(values, 2, 8, FRAME_SIZE - 3, FRAME_SIZE - 8, 0.95f)
        } else if (longThinLine) {
            fill(values, 8, 25, 56, 28, 0.95f)
        } else {
            if (!bottomOnly) fill(values, 8, 10, 25, 20, 0.9f)
            if (twoLines || bottomOnly) fill(values, 36, 38, 56, 48, 0.85f)
        }
        val bytes = ByteArray(values.size * Float.SIZE_BYTES)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).also { buffer ->
            values.forEach(buffer::putFloat)
        }
        return RawTensor(
            name = "probability",
            bytes = bytes,
            shape = listOf(1, 1, FRAME_SIZE, FRAME_SIZE),
            elementType = TensorElementType.FLOAT32,
        )
    }

    private fun fill(
        values: FloatArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        value: Float,
    ) {
        for (y in top..bottom) for (x in left..right) values[y * FRAME_SIZE + x] = value
    }

    private fun reading(sequence: Long, value: String, confidence: Float = 0.95f) = Observation.Reading(
        text = value,
        valueDecimal = value,
        stable = true,
        sourceSequence = sequence,
        confidence = confidence,
    )

    private fun frame(sequence: Long) = SourceFrame(
        sourceSequence = sequence,
        monotonicTimeMillis = sequence * 100,
        capturedAtEpochMillis = 1_700_000_000_000L + sequence * 100,
        width = FRAME_SIZE,
        height = FRAME_SIZE,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, FRAME_SIZE, FRAME_SIZE),
        pixels = FramePixels.Rgb888(
            bytes = ByteArray(FRAME_SIZE * FRAME_SIZE * 3) { 127.toByte() },
            rowStride = FRAME_SIZE * 3,
        ),
    )

    private data class Fixture(
        val pipeline: ReadingScenePipeline,
        val detector: CountingSession,
        val recognizer: CountingSession,
    )

    private class CountingSession(
        override val artifactRole: String,
        private val output: () -> Map<String, RawTensor>,
    ) : ArtifactExecutionSession {
        var calls: Int = 0
            private set
        var lastInputBytes: Int = 0
            private set

        override fun run(inputsByTensorName: Map<String, RawTensor>): Map<String, RawTensor> {
            calls++
            lastInputBytes = inputsByTensorName.values.sumOf { it.bytes.size }
            return output()
        }

        override fun close() = Unit
    }

    private class IncrementingNanoClock : NanoClock {
        private var value = 0L
        override fun nanoTime(): Long = value.also { value += 10L }
    }

    private companion object {
        const val FRAME_SIZE = 64
    }
}
