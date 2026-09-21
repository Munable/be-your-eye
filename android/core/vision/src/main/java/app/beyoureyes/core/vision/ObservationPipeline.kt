package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason

sealed interface QualityGateResult {
    data object Accepted : QualityGateResult

    data class Rejected(
        val reason: UnavailableReason = UnavailableReason.LOW_QUALITY,
        val diagnosticCode: String,
    ) : QualityGateResult {
        init {
            require(diagnosticCode.isNotBlank())
        }
    }
}

fun interface QualityGate {
    fun evaluate(frame: CanonicalFrame): QualityGateResult
}

object AcceptAllQualityGate : QualityGate {
    override fun evaluate(frame: CanonicalFrame): QualityGateResult = QualityGateResult.Accepted
}

fun interface NanoClock {
    fun nanoTime(): Long
}

object SystemNanoClock : NanoClock {
    override fun nanoTime(): Long = System.nanoTime()
}

data class PipelineTimings(
    val normalizeNanos: Long,
    val qualityNanos: Long,
    val preprocessNanos: Long,
    val inferenceNanos: Long,
    val adapterNanos: Long,
    val readingStages: ReadingPipelineStageTimings? = null,
) {
    val totalNanos: Long
        get() = normalizeNanos + qualityNanos + preprocessNanos + inferenceNanos + adapterNanos
}

/** Optional diagnostic split for the two-stage structured-reading graph. */
data class ReadingPipelineStageTimings(
    val locatorPreprocessNanos: Long,
    val locatorInferenceNanos: Long,
    val locatorAdapterNanos: Long,
    val recognizerPreprocessNanos: Long,
    val recognizerInferenceNanos: Long,
    val recognizerAdapterNanos: Long,
)

data class PipelineResult(
    val sourceSequence: Long,
    val monotonicTimeMillis: Long,
    val capturedAtEpochMillis: Long?,
    val observation: Observation,
    val timings: PipelineTimings,
    /**
     * True only when normalization, quality acceptance, preprocessing, inference and the typed
     * adapter all returned normally. Activation smoke tests use this to distinguish a safe
     * content-level rejection from an early pipeline failure. It is not model-quality evidence.
     */
    val adapterCompleted: Boolean = false,
)

/**
 * One production path shared by CameraX and replay.
 *
 * A source/normalization/preprocess/backend failure becomes INFERENCE_ERROR. An adapter failure
 * becomes INCOMPATIBLE_OUTPUT. A quality rejection uses its explicit reason. No failure can become
 * an empty detections list, because that would falsely mean "the target is absent".
 */
fun interface VisionPipeline {
    fun process(frame: SourceFrame): PipelineResult
}

class ObservationPipeline(
    private val normalizer: FrameNormalizer,
    private val qualityGate: QualityGate,
    private val preprocessor: InputPreprocessor,
    private val backend: InferenceBackend,
    private val adapter: OutputAdapter,
    private val clock: NanoClock = SystemNanoClock,
) : VisionPipeline {
    override fun process(frame: SourceFrame): PipelineResult {
        var normalizeNanos = 0L
        var qualityNanos = 0L
        var preprocessNanos = 0L
        var inferenceNanos = 0L
        var adapterNanos = 0L

        fun result(
            observation: Observation,
            adapterCompleted: Boolean = false,
        ) = PipelineResult(
            sourceSequence = frame.sourceSequence,
            monotonicTimeMillis = frame.monotonicTimeMillis,
            capturedAtEpochMillis = frame.capturedAtEpochMillis,
            observation = observation,
            timings = PipelineTimings(
                normalizeNanos = normalizeNanos,
                qualityNanos = qualityNanos,
                preprocessNanos = preprocessNanos,
                inferenceNanos = inferenceNanos,
                adapterNanos = adapterNanos,
            ),
            adapterCompleted = adapterCompleted,
        )

        val canonical = try {
            measured({ normalizeNanos = it }) { normalizer.normalize(frame) }
        } catch (error: Exception) {
            return result(unavailable(frame, UnavailableReason.INFERENCE_ERROR, "normalize_failed"))
        }

        val quality = try {
            measured({ qualityNanos = it }) { qualityGate.evaluate(canonical) }
        } catch (error: Exception) {
            return result(unavailable(frame, UnavailableReason.INFERENCE_ERROR, "quality_gate_failed"))
        }
        if (quality is QualityGateResult.Rejected) {
            return result(unavailable(frame, quality.reason, quality.diagnosticCode))
        }

        val input = try {
            measured({ preprocessNanos = it }) { preprocessor.prepare(canonical) }
        } catch (error: Exception) {
            return result(unavailable(frame, UnavailableReason.INFERENCE_ERROR, "preprocess_failed"))
        }
        val output = try {
            measured({ inferenceNanos = it }) { backend.infer(input) }
        } catch (error: Exception) {
            return result(unavailable(frame, UnavailableReason.INFERENCE_ERROR, "backend_failed"))
        }
        val observation = try {
            measured({ adapterNanos = it }) { adapter.toObservation(output, input.transform, canonical) }
        } catch (error: Exception) {
            return result(
                unavailable(frame, UnavailableReason.INCOMPATIBLE_OUTPUT, "adapter_failed"),
            )
        }
        if (observation.sourceSequence != frame.sourceSequence) {
            return result(
                unavailable(frame, UnavailableReason.INCOMPATIBLE_OUTPUT, "source_sequence_mismatch"),
            )
        }
        return result(observation, adapterCompleted = true)
    }

    private inline fun <T> measured(setNanos: (Long) -> Unit, block: () -> T): T {
        val started = clock.nanoTime()
        return try {
            block()
        } finally {
            setNanos((clock.nanoTime() - started).coerceAtLeast(0L))
        }
    }

    private fun unavailable(
        frame: SourceFrame,
        reason: UnavailableReason,
        code: String,
    ): Observation.Unavailable = Observation.Unavailable(
        reason = reason,
        diagnosticCode = code,
        sourceSequence = frame.sourceSequence,
    )
}

/**
 * Runtime-selected analysis cadence. The policy stores an interval rather than a nominal FPS so
 * callers can compare it directly with monotonic frame timestamps without rounding.
 *
 * The accepted range deliberately covers high-rate replay through low-rate, power-sensitive
 * monitoring. A concrete package/runtime snapshot must provide the value; the generic runtime
 * never embeds a product-wide FPS decision.
 */
data class FrameSamplingPolicy(
    val analysisIntervalMillis: Long,
) {
    init {
        require(analysisIntervalMillis in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) {
            "analysisIntervalMillis must be in $MIN_INTERVAL_MILLIS..$MAX_INTERVAL_MILLIS"
        }
    }

    companion object {
        /** 33 ms accepts every frame from a nominal 30 fps CameraX stream. */
        const val MIN_INTERVAL_MILLIS = 33L
        const val MAX_INTERVAL_MILLIS = 10_000L
        const val MIN_FRAMES_PER_SECOND = 0.1f
        const val MAX_FRAMES_PER_SECOND = 30f
    }
}

/** Camera watchdogs must still observe every CameraX frame before this sampling gate. */
class MonotonicFrameSampler(policy: FrameSamplingPolicy) {
    private var policy: FrameSamplingPolicy = policy
    private var lastAcceptedMillis: Long? = null

    @Synchronized
    fun shouldProcess(monotonicTimeMillis: Long): Boolean {
        require(monotonicTimeMillis >= 0)
        val previous = lastAcceptedMillis
        if (previous == null || monotonicTimeMillis < previous ||
            monotonicTimeMillis - previous >= policy.analysisIntervalMillis
        ) {
            lastAcceptedMillis = monotonicTimeMillis
            return true
        }
        return false
    }

    /** Keeps the monotonic anchor; callers separately interrupt cadence-dependent rule evidence. */
    @Synchronized
    fun updatePolicy(policy: FrameSamplingPolicy) {
        this.policy = policy
    }

    @Synchronized
    fun currentPolicy(): FrameSamplingPolicy = policy

    @Synchronized
    fun reset() {
        lastAcceptedMillis = null
    }
}
