package app.beyoureyes.core.domain

import kotlin.math.ceil

/**
 * One task-scoped time evidence gate for numeric readings.
 *
 * The model adapter describes one frame. This tracker owns every cross-frame decision used by
 * both setup and monitoring, so a rule never adds a second confirmation window. Missing frames
 * pause evidence until [maximumEvidenceGapMillis]; changing values remain one stream when their
 * fixed position and display format still match. If only decimal punctuation changes, the same
 * digit/sign evidence is retained so confirmation can restore the user's visible precision.
 */
class ReadingTemporalEvidenceTracker(
    val minimumStableSpanMillis: Long = MINIMUM_STABLE_SPAN_MILLIS,
    val minimumValidObservations: Int = MINIMUM_VALID_OBSERVATIONS,
    private val latencyWindowSize: Int = LATENCY_WINDOW_SIZE,
) {
    private data class Candidate(
        val format: ConfirmedReadingFormat,
        /** Digits/sign used only when decimal precision changes between adjacent frames. */
        val digitSignature: String,
        val targetTrackId: Long,
        val firstAtMillis: Long,
        val lastAtMillis: Long,
        val count: Int,
    )

    private var candidate: Candidate? = null
    private var lastSourceSequence: Long? = null
    private var lastMonotonicMillis: Long? = null
    private var lastReading: Observation.Reading? = null
    private var lastReadingAtMillis: Long? = null
    private val recentProcessingLatencies = ArrayDeque<Long>()

    init {
        require(minimumStableSpanMillis >= 0)
        require(minimumValidObservations >= 2)
        require(latencyWindowSize in 1..MAX_LATENCY_WINDOW_SIZE)
    }

    val processingLatencyP95Millis: Long
        get() {
            if (recentProcessingLatencies.isEmpty()) return 0
            val sorted = recentProcessingLatencies.sorted()
            val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
            return sorted[index]
        }

    val maximumEvidenceGapMillis: Long
        get() = maxOf(
            MINIMUM_EVIDENCE_GAP_MILLIS,
            processingLatencyP95Millis * PROCESSING_LATENCY_GAP_MULTIPLIER,
        )
            .coerceAtMost(MAXIMUM_EVIDENCE_GAP_MILLIS)

    fun accept(
        observation: Observation,
        monotonicMillis: Long,
        processingLatencyMillis: Long?,
    ): Observation {
        require(monotonicMillis >= 0)
        require(processingLatencyMillis == null || processingLatencyMillis >= 0)
        processingLatencyMillis?.let(::recordLatency)

        val previousSequence = lastSourceSequence
        val previousTime = lastMonotonicMillis
        lastSourceSequence = observation.sourceSequence
        lastMonotonicMillis = monotonicMillis
        if (previousSequence != null && observation.sourceSequence <= previousSequence) {
            resetCandidate()
            return unavailable(
                observation.sourceSequence,
                UnavailableReason.INCOMPATIBLE_OUTPUT,
                "reading_sequence_not_increasing",
            )
        }
        if (previousTime != null && monotonicMillis < previousTime) {
            resetCandidate()
            return unavailable(
                observation.sourceSequence,
                UnavailableReason.INCOMPATIBLE_OUTPUT,
                "reading_clock_rollback",
            )
        }

        if (observation is Observation.Unavailable) {
            expireCandidateIfNeeded(monotonicMillis)
            return observation.withRetainedEvidence(monotonicMillis)
        }
        val reading = observation as? Observation.Reading ?: run {
            resetCandidate()
            return unavailable(
                observation.sourceSequence,
                UnavailableReason.INCOMPATIBLE_OUTPUT,
                "reading_evidence_input_mismatch",
            )
        }
        lastReading = reading.copy(stable = false)
        lastReadingAtMillis = monotonicMillis
        val current = candidate
        val currentDigitSignature = digitSignature(reading)
        val sameEvidence = current != null &&
            (reading.format == current.format ||
                (compatibleFormat(reading.format, current.format) &&
                    currentDigitSignature == current.digitSignature)) &&
            reading.targetTrackId == current.targetTrackId &&
            monotonicMillis - current.lastAtMillis <= maximumEvidenceGapMillis
        val updated = if (sameEvidence) {
            checkNotNull(current).copy(
                lastAtMillis = monotonicMillis,
                count = current.count + 1,
            )
        } else {
            Candidate(
                format = reading.format,
                digitSignature = currentDigitSignature,
                targetTrackId = reading.targetTrackId,
                firstAtMillis = monotonicMillis,
                lastAtMillis = monotonicMillis,
                count = 1,
            )
        }
        candidate = updated
        val stable = updated.count >= minimumValidObservations &&
            updated.lastAtMillis - updated.firstAtMillis >= minimumStableSpanMillis
        return reading.copy(stable = stable)
    }

    fun reset() {
        resetCandidate()
        lastSourceSequence = null
        lastMonotonicMillis = null
        lastReading = null
        lastReadingAtMillis = null
        recentProcessingLatencies.clear()
    }

    private fun recordLatency(value: Long) {
        if (recentProcessingLatencies.size == latencyWindowSize) {
            recentProcessingLatencies.removeFirst()
        }
        recentProcessingLatencies.addLast(value)
    }

    private fun expireCandidateIfNeeded(now: Long) {
        val lastValid = candidate?.lastAtMillis ?: return
        if (now - lastValid > maximumEvidenceGapMillis) resetCandidate()
    }

    private fun Observation.Unavailable.withRetainedEvidence(now: Long): Observation.Unavailable {
        val reading = lastReading ?: return copy(
            evidencePaused = false,
            retainedReading = null,
            retainedReadingAgeMillis = null,
        )
        val age = now - checkNotNull(lastReadingAtMillis)
        return copy(
            evidencePaused = candidate != null && age <= maximumEvidenceGapMillis,
            retainedReading = reading,
            retainedReadingAgeMillis = age,
        )
    }

    private fun resetCandidate() {
        candidate = null
    }

    private fun compatibleFormat(
        left: ConfirmedReadingFormat,
        right: ConfirmedReadingFormat,
    ): Boolean = left.kind == right.kind &&
        left.timeSegments == right.timeSegments &&
        left.unit == right.unit

    private fun digitSignature(reading: Observation.Reading): String {
        val canonical = reading.valueDecimal
        val sign = if (canonical.startsWith('-')) "-" else "+"
        val digits = canonical.removePrefix("-")
            .replace(".", "")
            .trimStart('0')
            .ifEmpty { "0" }
        return "$sign$digits"
    }

    private fun unavailable(
        sourceSequence: Long,
        reason: UnavailableReason,
        diagnosticCode: String,
    ) = Observation.Unavailable(
        reason = reason,
        diagnosticCode = diagnosticCode,
        sourceSequence = sourceSequence,
    )

    companion object {
        const val MINIMUM_STABLE_SPAN_MILLIS = 120L
        const val MINIMUM_VALID_OBSERVATIONS = 2
        const val MINIMUM_EVIDENCE_GAP_MILLIS = 750L
        /**
         * Runtime sampling deliberately leaves several processing windows idle to control heat.
         * Evidence must span that cadence plus CameraX scheduling jitter, rather than resetting
         * merely because a faster model is sampled less often.
         */
        const val MAXIMUM_EVIDENCE_GAP_MILLIS = 5_000L
        private const val PROCESSING_LATENCY_GAP_MULTIPLIER = 16L
        const val LATENCY_WINDOW_SIZE = 20
        private const val MAX_LATENCY_WINDOW_SIZE = 120
    }
}
