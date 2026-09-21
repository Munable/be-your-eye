package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Observation
import kotlin.math.ceil

data class ReplayBenchmarkConfig(
    val warmupRuns: Int = 20,
    val measuredFrames: Int = 100,
) {
    init {
        require(warmupRuns >= 20)
        require(measuredFrames >= 100)
    }
}

data class ReplayBenchmarkReport(
    val warmupRuns: Int,
    val measuredFrames: Int,
    val p50LatencyNanos: Long,
    val p95LatencyNanos: Long,
    val maxLatencyNanos: Long,
    val unavailableFrames: Int,
) {
    init {
        require(warmupRuns >= 20)
        require(measuredFrames >= 100)
        require(p50LatencyNanos >= 0 && p95LatencyNanos >= p50LatencyNanos)
        require(maxLatencyNanos >= p95LatencyNanos)
        require(unavailableFrames in 0..measuredFrames)
    }
}

/**
 * Runs already-decoded frames as fast as possible. Decode/I/O, energy, memory, and thermal evidence
 * are deliberately outside this report and must be collected by the Android device harness.
 */
class ReplayBenchmarkRunner(
    private val pipeline: ObservationPipeline,
) {
    fun run(
        frames: List<SourceFrame>,
        config: ReplayBenchmarkConfig = ReplayBenchmarkConfig(),
    ): ReplayBenchmarkReport {
        require(frames.isNotEmpty())
        repeat(config.warmupRuns) { index -> pipeline.process(frames[index % frames.size]) }

        var unavailableFrames = 0
        val samples = LongArray(config.measuredFrames) { index ->
            val result = pipeline.process(frames[index % frames.size])
            if (result.observation is Observation.Unavailable) unavailableFrames += 1
            result.timings.totalNanos
        }.sortedArray()

        return ReplayBenchmarkReport(
            warmupRuns = config.warmupRuns,
            measuredFrames = config.measuredFrames,
            p50LatencyNanos = samples.percentile(0.50),
            p95LatencyNanos = samples.percentile(0.95),
            maxLatencyNanos = samples.last(),
            unavailableFrames = unavailableFrames,
        )
    }

    private fun LongArray.percentile(fraction: Double): Long {
        val rank = ceil(size * fraction).toInt().coerceIn(1, size)
        return this[rank - 1]
    }
}
