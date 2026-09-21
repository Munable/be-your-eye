package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Observation
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayBenchmarkRunnerTest {
    @Test
    fun `benchmark performs required warmup and measured runs`() {
        var backendCalls = 0
        val clock = IncrementingNanoClock()
        val pipeline = ObservationPipeline(
            normalizer = UprightRgbFrameNormalizer,
            qualityGate = AcceptAllQualityGate,
            preprocessor = InputPreprocessor { frame ->
                PreparedInput(
                    bytes = frame.rgb888,
                    shape = listOf(1, 1, 1, 3),
                    elementType = TensorElementType.UINT8,
                    transform = LetterboxTransform(1, 1, 1, 1, 1f, 1f, 0f, 0f),
                )
            },
            backend = InferenceBackend {
                backendCalls += 1
                RawTensorOutput(
                    listOf(RawTensor("output", byteArrayOf(1), listOf(1), TensorElementType.UINT8)),
                )
            },
            adapter = OutputAdapter { _, _, frame ->
                Observation.Detections(emptyList(), frame.sourceSequence)
            },
            clock = clock,
        )

        val report = ReplayBenchmarkRunner(pipeline).run(listOf(frame()))

        assertEquals(120, backendCalls)
        assertEquals(20, report.warmupRuns)
        assertEquals(100, report.measuredFrames)
        assertEquals(50, report.p50LatencyNanos)
        assertEquals(50, report.p95LatencyNanos)
        assertEquals(50, report.maxLatencyNanos)
        assertEquals(0, report.unavailableFrames)
    }

    private fun frame() = SourceFrame(
        sourceSequence = 1,
        monotonicTimeMillis = 0,
        capturedAtEpochMillis = null,
        width = 1,
        height = 1,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, 1, 1),
        pixels = FramePixels.Rgb888(byteArrayOf(1, 2, 3), rowStride = 3),
    )

    private class IncrementingNanoClock : NanoClock {
        private var value = 0L
        override fun nanoTime(): Long = value.also { value += 10L }
    }
}
