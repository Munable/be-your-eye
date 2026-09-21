package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.vision.RuntimeFrameResult

/**
 * Immutable identity of one live field-check attempt.
 *
 * The exact task revision, signed package pointer, and ROI are part of the result. A successful
 * result must never be reused after any of them changes. [targetId] also binds typed State output to
 * the task that requested the check.
 */
internal data class SimilarityFieldValidationIdentity(
    val taskId: String,
    val taskRevision: Long,
    val packagePointer: ModelPackagePointer,
    val roi: NormalizedRect,
    val targetId: String,
) {
    init {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(taskRevision >= 1) { "taskRevision must be at least one" }
        require(targetId.isNotBlank()) { "targetId must not be blank" }
    }
}

internal enum class SimilarityFieldValidationIdentityChange {
    TASK_ID,
    TASK_REVISION,
    PACKAGE_POINTER,
    ROI,
    TARGET_ID,
}

internal enum class SimilarityFieldValidationFailure {
    INCOMPATIBLE_OBSERVATION,
    SOURCE_SEQUENCE_INVALID,
}

/** Latest processed live signal; contains no score, label, image, or other media-derived detail. */
internal enum class SimilarityFieldValidationSignal {
    PRESENT,
    ABSENT,
    UNAVAILABLE,
}

/** Bounded, media-free diagnostic summary for UI routing and evidence. */
internal data class SimilarityFieldValidationSummary(
    val processedFrames: Int,
    val presentFrames: Int,
    val absentFrames: Int,
    val unavailableFrames: Int,
    val unavailableReasons: Map<UnavailableReason, Int>,
    val unavailableDiagnosticCodes: Map<String, Int>,
    val latestSignal: SimilarityFieldValidationSignal?,
) {
    init {
        require(processedFrames >= 0)
        require(presentFrames >= 0 && absentFrames >= 0 && unavailableFrames >= 0)
        require(presentFrames + absentFrames + unavailableFrames <= processedFrames)
        require(unavailableReasons.values.all { it > 0 })
        require(unavailableReasons.values.sum() == unavailableFrames)
        require(unavailableDiagnosticCodes.keys.all(String::isNotBlank))
        require(unavailableDiagnosticCodes.values.all { it > 0 })
        require(unavailableDiagnosticCodes.values.sum() <= unavailableFrames)
    }
}

internal sealed interface SimilarityFieldValidationState {
    val expectedIdentity: SimilarityFieldValidationIdentity
    val summary: SimilarityFieldValidationSummary

    data class Collecting(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
    ) : SimilarityFieldValidationState

    data class Passed(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
        /** Source sequence that first completed this uninterrupted stable-present episode. */
        val stableEpisodeId: Long,
    ) : SimilarityFieldValidationState {
        init {
            require(stableEpisodeId > 0)
        }
    }

    /** A complete absence interval did not find the target; the same live session keeps observing. */
    data class NotFound(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
    ) : SimilarityFieldValidationState

    data class Failed(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
        val failure: SimilarityFieldValidationFailure,
    ) : SimilarityFieldValidationState

    /** Terminal: callers must construct a new reducer for the new identity. */
    data class Invalidated(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
        val currentIdentity: SimilarityFieldValidationIdentity,
        val changes: Set<SimilarityFieldValidationIdentityChange>,
    ) : SimilarityFieldValidationState {
        init {
            require(changes.isNotEmpty())
        }
    }
}

/**
 * Package-agnostic live output check for `similarity_match_v1`.
 *
 * It never reads a package ID, vendor, raw model score, or image. The production runtime has
 * already applied the signed Manifest graph, match threshold, rejection margin, ROI normalizer,
 * and quality gate before producing a typed [Observation]. The live setup check passes only when
 * `present` remains valid for a short elapsed-time interval. The decision is intentionally based
 * on monotonic time rather than a fixed number of processed frames, so a faster runtime improves
 * evidence density without changing the user-visible wait. `unavailable` pauses the current
 * candidate and never becomes absence; a long gap discards only the partial candidate. Once the
 * target has passed, unavailable output pauses evidence without revoking the user's confirmed
 * setup. The first later absence revokes the pass and starts a fresh confirmation window. Skipped
 * sampling results never advance evidence.
 */
internal class SimilarityFieldValidationReducer(
    private val identity: SimilarityFieldValidationIdentity,
) {
    private val samples = ArrayDeque<ValidationSample>(MAX_DIAGNOSTIC_SAMPLES)
    private var lastProcessedSourceSequence: Long? = null
    private var lastProcessedMonotonicTimeMillis: Long? = null
    private var evidence: TimedEvidence? = null

    var state: SimilarityFieldValidationState = SimilarityFieldValidationState.Collecting(
        expectedIdentity = identity,
        summary = summary(),
    )
        private set

    /**
     * Invalidates a result as soon as task, package, ROI, or target identity changes. An invalidated
     * attempt cannot become valid again even if a later caller passes the old identity.
     */
    fun ensureIdentity(
        currentIdentity: SimilarityFieldValidationIdentity,
    ): SimilarityFieldValidationState {
        if (state is SimilarityFieldValidationState.Invalidated) return state
        val changes = identity.changesFrom(currentIdentity)
        if (changes.isNotEmpty()) {
            state = SimilarityFieldValidationState.Invalidated(
                expectedIdentity = identity,
                currentIdentity = currentIdentity,
                changes = changes,
                summary = summary(),
            )
        }
        return state
    }

    fun accept(
        currentIdentity: SimilarityFieldValidationIdentity,
        result: RuntimeFrameResult,
    ): SimilarityFieldValidationState {
        ensureIdentity(currentIdentity)
        if (state !is SimilarityFieldValidationState.Collecting &&
            state !is SimilarityFieldValidationState.NotFound &&
            state !is SimilarityFieldValidationState.Passed
        ) return state
        if (result is RuntimeFrameResult.Skipped) return state

        result as RuntimeFrameResult.Processed
        val sourceSequence = result.sourceSequence
        val monotonicTimeMillis = result.pipelineResult.monotonicTimeMillis
        val observation = result.pipelineResult.observation
        if (observation.sourceSequence != sourceSequence ||
            lastProcessedSourceSequence?.let { sourceSequence <= it } == true ||
            lastProcessedMonotonicTimeMillis?.let { monotonicTimeMillis < it } == true
        ) {
            return fail(SimilarityFieldValidationFailure.SOURCE_SEQUENCE_INVALID)
        }
        lastProcessedSourceSequence = sourceSequence
        lastProcessedMonotonicTimeMillis = monotonicTimeMillis
        val sample = when (observation) {
            is Observation.State -> when (observation.stateId) {
                "${identity.targetId}:present" -> Signal.Present
                "${identity.targetId}:absent" -> Signal.Absent
                else -> return fail(SimilarityFieldValidationFailure.INCOMPATIBLE_OBSERVATION)
            }
            is Observation.Detections -> if (observation.items.any { it.label == identity.targetId }) {
                Signal.Present
            } else {
                Signal.Absent
            }
            is Observation.Unavailable -> UnavailableSample(
                reason = observation.reason,
                diagnosticCode = observation.diagnosticCode?.takeIf(String::isNotBlank),
            )
            is Observation.Reading,
            -> return fail(SimilarityFieldValidationFailure.INCOMPATIBLE_OBSERVATION)
        }
        addDiagnosticSample(sample)

        if (sample is UnavailableSample) {
            evidence = evidence?.pause(monotonicTimeMillis, MAX_EVIDENCE_GAP_MILLIS)
            state = state.withSummary(summary())
            return state
        }

        sample as Signal
        val currentStableEpisodeId = (state as? SimilarityFieldValidationState.Passed)
            ?.stableEpisodeId
        val current = evidence
        val next = if (current == null || current.signal != sample) {
            TimedEvidence.start(sample, monotonicTimeMillis)
        } else {
            current.advance(monotonicTimeMillis, MAX_EVIDENCE_GAP_MILLIS)
        }
        evidence = next
        val summary = summary()
        state = when {
            currentStableEpisodeId != null && sample == Signal.Present ->
                SimilarityFieldValidationState.Passed(
                    expectedIdentity = identity,
                    summary = summary,
                    stableEpisodeId = currentStableEpisodeId,
                )
            next.confirmed && sample == Signal.Present -> SimilarityFieldValidationState.Passed(
                expectedIdentity = identity,
                summary = summary,
                stableEpisodeId = currentStableEpisodeId ?: sourceSequence,
            )
            next.confirmed && sample == Signal.Absent -> SimilarityFieldValidationState.NotFound(
                expectedIdentity = identity,
                summary = summary,
            )
            else -> SimilarityFieldValidationState.Collecting(
                expectedIdentity = identity,
                summary = summary,
            )
        }
        return state
    }

    private fun fail(
        failure: SimilarityFieldValidationFailure,
    ): SimilarityFieldValidationState = SimilarityFieldValidationState.Failed(
        expectedIdentity = identity,
        summary = summary(),
        failure = failure,
    ).also { state = it }

    private fun summary(): SimilarityFieldValidationSummary {
        val unavailable = samples.filterIsInstance<UnavailableSample>()
        return SimilarityFieldValidationSummary(
            processedFrames = samples.size,
            presentFrames = samples.count { it == Signal.Present },
            absentFrames = samples.count { it == Signal.Absent },
            unavailableFrames = unavailable.size,
            unavailableReasons = unavailable.groupingBy(UnavailableSample::reason).eachCount(),
            unavailableDiagnosticCodes = unavailable.mapNotNull(UnavailableSample::diagnosticCode)
                .groupingBy { it }
                .eachCount(),
            latestSignal = when (samples.lastOrNull()) {
                Signal.Present -> SimilarityFieldValidationSignal.PRESENT
                Signal.Absent -> SimilarityFieldValidationSignal.ABSENT
                is UnavailableSample -> SimilarityFieldValidationSignal.UNAVAILABLE
                null -> null
            },
        )
    }

    private fun addDiagnosticSample(sample: ValidationSample) {
        if (samples.size == MAX_DIAGNOSTIC_SAMPLES) samples.removeFirst()
        samples.addLast(sample)
    }

    private fun SimilarityFieldValidationIdentity.changesFrom(
        current: SimilarityFieldValidationIdentity,
    ): Set<SimilarityFieldValidationIdentityChange> = buildSet {
        if (taskId != current.taskId) add(SimilarityFieldValidationIdentityChange.TASK_ID)
        if (taskRevision != current.taskRevision) {
            add(SimilarityFieldValidationIdentityChange.TASK_REVISION)
        }
        if (packagePointer != current.packagePointer) {
            add(SimilarityFieldValidationIdentityChange.PACKAGE_POINTER)
        }
        if (roi != current.roi) add(SimilarityFieldValidationIdentityChange.ROI)
        if (targetId != current.targetId) add(SimilarityFieldValidationIdentityChange.TARGET_ID)
    }

    private fun SimilarityFieldValidationState.withSummary(
        nextSummary: SimilarityFieldValidationSummary,
    ): SimilarityFieldValidationState = when (this) {
        is SimilarityFieldValidationState.Collecting -> copy(summary = nextSummary)
        is SimilarityFieldValidationState.NotFound -> copy(summary = nextSummary)
        is SimilarityFieldValidationState.Passed -> copy(summary = nextSummary)
        else -> this
    }.also { state = it }

    private sealed interface ValidationSample

    private enum class Signal : ValidationSample { Present, Absent }

    private data class UnavailableSample(
        val reason: UnavailableReason,
        val diagnosticCode: String?,
    ) : ValidationSample

    private data class TimedEvidence(
        val signal: Signal,
        val accumulatedMillis: Long,
        val lastValidAtMillis: Long?,
        val validObservations: Int,
        val pausedAtMillis: Long? = null,
    ) {
        val confirmed: Boolean
            get() = validObservations >= MIN_VALID_OBSERVATIONS &&
                accumulatedMillis >= CONFIRMATION_MILLIS

        fun pause(atMillis: Long, maximumGapMillis: Long): TimedEvidence {
            val prior = lastValidAtMillis ?: return copy(
                pausedAtMillis = pausedAtMillis ?: atMillis,
            )
            val elapsed = atMillis - prior
            require(elapsed >= 0)
            if (elapsed > maximumGapMillis) {
                return copy(
                    accumulatedMillis = 0,
                    lastValidAtMillis = null,
                    validObservations = 1,
                    pausedAtMillis = prior,
                )
            }
            return copy(
                accumulatedMillis = accumulatedMillis + elapsed,
                lastValidAtMillis = null,
                pausedAtMillis = atMillis,
            )
        }

        fun advance(atMillis: Long, maximumGapMillis: Long): TimedEvidence {
            val prior = lastValidAtMillis
            if (prior == null) {
                if (pausedAtMillis == null || atMillis - pausedAtMillis > maximumGapMillis) {
                    return start(signal, atMillis)
                }
                return copy(
                    lastValidAtMillis = atMillis,
                    validObservations = validObservations + 1,
                    pausedAtMillis = null,
                )
            }
            if (atMillis - prior > maximumGapMillis) return start(signal, atMillis)
            return copy(
                accumulatedMillis = accumulatedMillis + (atMillis - prior),
                lastValidAtMillis = atMillis,
                validObservations = validObservations + 1,
            )
        }

        companion object {
            fun start(signal: Signal, atMillis: Long) = TimedEvidence(
                signal = signal,
                accumulatedMillis = 0,
                lastValidAtMillis = atMillis,
                validObservations = 1,
            )
        }

    }

    companion object {
        const val CONFIRMATION_MILLIS = 400L
        const val MAX_EVIDENCE_GAP_MILLIS = 1_500L
        const val MIN_VALID_OBSERVATIONS = 2
        private const val MAX_DIAGNOSTIC_SAMPLES = 64
    }
}
