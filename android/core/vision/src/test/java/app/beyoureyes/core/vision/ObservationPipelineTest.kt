package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Detection
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationPipelineTest {
    @Test
    fun `camera yuv and rgb replay share every stage after normalization`() {
        val normalizedBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val seenInputs = mutableListOf<ByteArray>()
        val normalizer = FrameNormalizer { frame ->
            CanonicalFrame(
                sourceSequence = frame.sourceSequence,
                monotonicTimeMillis = frame.monotonicTimeMillis,
                capturedAtEpochMillis = frame.capturedAtEpochMillis,
                width = 2,
                height = 1,
                rgb888 = normalizedBytes.copyOf(),
            )
        }
        val preprocessor = InputPreprocessor { frame ->
            PreparedInput(
                bytes = frame.rgb888.copyOf(),
                shape = listOf(1, 1, 2, 3),
                elementType = TensorElementType.UINT8,
                transform = identityTransform(frame),
            )
        }
        val backend = InferenceBackend { input ->
            seenInputs += input.bytes.copyOf()
            rawOutput()
        }
        val adapter = OutputAdapter { _, _, frame -> detections(frame.sourceSequence) }
        val pipeline = ObservationPipeline(
            normalizer = normalizer,
            qualityGate = AcceptAllQualityGate,
            preprocessor = preprocessor,
            backend = backend,
            adapter = adapter,
            clock = IncrementingNanoClock(),
        )

        val replay = pipeline.process(rgbFrame(sequence = 1))
        val camera = pipeline.process(yuvFrame(sequence = 2))

        assertEquals(detections(1), replay.observation)
        assertEquals(detections(2), camera.observation)
        assertEquals(2, seenInputs.size)
        assertArrayEquals(seenInputs[0], seenInputs[1])
        assertTrue(replay.timings.totalNanos > 0)
        assertTrue(replay.adapterCompleted)
    }

    @Test
    fun `quality rejection skips model and stays unavailable`() {
        var backendCalls = 0
        val pipeline = pipeline(
            qualityGate = QualityGate {
                QualityGateResult.Rejected(diagnosticCode = "blur_below_threshold")
            },
            backend = InferenceBackend {
                backendCalls += 1
                rawOutput()
            },
        )

        val result = pipeline.process(rgbFrame())

        val unavailable = result.observation as Observation.Unavailable
        assertEquals(UnavailableReason.LOW_QUALITY, unavailable.reason)
        assertEquals("blur_below_threshold", unavailable.diagnosticCode)
        assertEquals(0, backendCalls)
        assertFalse(result.adapterCompleted)
    }

    @Test
    fun `backend and adapter failures never become target absence`() {
        val backendResult = pipeline(backend = InferenceBackend { error("device delegate failed") })
            .process(rgbFrame(sequence = 10))
        val backendFailure = backendResult.observation as Observation.Unavailable
        assertEquals(UnavailableReason.INFERENCE_ERROR, backendFailure.reason)
        assertEquals("backend_failed", backendFailure.diagnosticCode)
        assertFalse(backendResult.adapterCompleted)

        val adapterResult = pipeline(adapter = OutputAdapter { _, _, _ -> error("bad tensor") })
            .process(rgbFrame(sequence = 11))
        val adapterFailure = adapterResult.observation as Observation.Unavailable
        assertEquals(UnavailableReason.INCOMPATIBLE_OUTPUT, adapterFailure.reason)
        assertEquals("adapter_failed", adapterFailure.diagnosticCode)
        assertFalse(adapterResult.adapterCompleted)
    }

    @Test
    fun `fatal errors are not disguised as retryable unavailable observations`() {
        val fatal = AssertionError("fatal runtime corruption")

        val observed = runCatching {
            pipeline(backend = InferenceBackend { throw fatal }).process(rgbFrame())
        }.exceptionOrNull()

        assertTrue(observed === fatal)
    }

    @Test
    fun `adapter cannot change source sequence`() {
        val pipeline = pipeline(
            adapter = OutputAdapter { _, _, _ -> Observation.Detections(emptyList(), 999) },
        )

        val result = pipeline.process(rgbFrame(sequence = 7)).observation as Observation.Unavailable

        assertEquals(7, result.sourceSequence)
        assertEquals(UnavailableReason.INCOMPATIBLE_OUTPUT, result.reason)
        assertEquals("source_sequence_mismatch", result.diagnosticCode)
    }

    @Test
    fun `rgb replay normalizer removes row padding without changing pixels`() {
        val frame = SourceFrame(
            sourceSequence = 1,
            monotonicTimeMillis = 0,
            capturedAtEpochMillis = 1,
            width = 2,
            height = 2,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, 2, 2),
            pixels = FramePixels.Rgb888(
                bytes = byteArrayOf(
                    1, 2, 3, 4, 5, 6, 99, 99,
                    7, 8, 9, 10, 11, 12, 88, 88,
                ),
                rowStride = 8,
            ),
        )

        val normalized = UprightRgbFrameNormalizer.normalize(frame)

        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            normalized.rgb888,
        )
    }

    @Test
    fun `two fps sampler uses monotonic boundaries and restarts fail closed on rollback`() {
        val sampler = MonotonicFrameSampler(FrameSamplingPolicy(500))

        assertTrue(sampler.shouldProcess(1_000))
        assertFalse(sampler.shouldProcess(1_499))
        assertTrue(sampler.shouldProcess(1_500))
        assertTrue(sampler.shouldProcess(1_400))
        assertFalse(sampler.shouldProcess(1_899))
        assertTrue(sampler.shouldProcess(1_900))
    }

    @Test
    fun `thirty fps sampler accepts nominal camera frame boundaries`() {
        val sampler = MonotonicFrameSampler(FrameSamplingPolicy(33))

        assertTrue(sampler.shouldProcess(1_000))
        assertFalse(sampler.shouldProcess(1_032))
        assertTrue(sampler.shouldProcess(1_033))
        assertTrue(sampler.shouldProcess(1_066))
    }

    private fun pipeline(
        qualityGate: QualityGate = AcceptAllQualityGate,
        backend: InferenceBackend = InferenceBackend { rawOutput() },
        adapter: OutputAdapter = OutputAdapter { _, _, frame -> detections(frame.sourceSequence) },
    ) = ObservationPipeline(
        normalizer = UprightRgbFrameNormalizer,
        qualityGate = qualityGate,
        preprocessor = InputPreprocessor { frame ->
            PreparedInput(
                bytes = frame.rgb888,
                shape = listOf(1, frame.height, frame.width, 3),
                elementType = TensorElementType.UINT8,
                transform = identityTransform(frame),
            )
        },
        backend = backend,
        adapter = adapter,
        clock = IncrementingNanoClock(),
    )

    private fun rgbFrame(sequence: Long = 1) = SourceFrame(
        sourceSequence = sequence,
        monotonicTimeMillis = sequence * 500,
        capturedAtEpochMillis = 1_700_000_000_000 + sequence * 500,
        width = 2,
        height = 1,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, 2, 1),
        pixels = FramePixels.Rgb888(byteArrayOf(1, 2, 3, 4, 5, 6), rowStride = 6),
    )

    private fun yuvFrame(sequence: Long) = SourceFrame(
        sourceSequence = sequence,
        monotonicTimeMillis = sequence * 500,
        capturedAtEpochMillis = 1_700_000_000_000 + sequence * 500,
        width = 2,
        height = 1,
        rotationDegrees = 0,
        cropRect = PixelRect(0, 0, 2, 1),
        pixels = FramePixels.Yuv420(
            yPlane = YuvPlane(byteArrayOf(1, 2), rowStride = 2, pixelStride = 1),
            uPlane = YuvPlane(byteArrayOf(3), rowStride = 1, pixelStride = 1),
            vPlane = YuvPlane(byteArrayOf(4), rowStride = 1, pixelStride = 1),
        ),
    )

    private fun detections(sequence: Long) = Observation.Detections(
        items = listOf(
            Detection(
                label = "person",
                score = 0.9f,
                box = NormalizedRect(0.1f, 0.1f, 0.8f, 0.9f),
            ),
        ),
        sourceSequence = sequence,
    )

    private fun rawOutput() = RawTensorOutput(
        listOf(
            RawTensor(
                name = "scores",
                bytes = byteArrayOf(1),
                shape = listOf(1),
                elementType = TensorElementType.UINT8,
            ),
        ),
    )

    private fun identityTransform(frame: CanonicalFrame) = LetterboxTransform(
        sourceWidth = frame.width,
        sourceHeight = frame.height,
        inputWidth = frame.width,
        inputHeight = frame.height,
        scaleX = 1f,
        scaleY = 1f,
        offsetX = 0f,
        offsetY = 0f,
    )

    private class IncrementingNanoClock : NanoClock {
        private var value = 0L
        override fun nanoTime(): Long = value.also { value += 10L }
    }
}
