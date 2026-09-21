package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.core.vision.PipelineTimings
import app.beyoureyes.core.vision.RuntimeFrameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityFieldValidationReducerTest {
    @Test
    fun `first present is an immediate media free provisional signal`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        val provisional = reducer.accept(
            identity,
            processed(1, 100, state(identity, 1, "present")),
        )

        assertTrue(provisional is SimilarityFieldValidationState.Collecting)
        assertEquals(SimilarityFieldValidationSignal.PRESENT, provisional.summary.latestSignal)
        assertEquals(1, provisional.summary.presentFrames)
    }

    @Test
    fun `present is confirmed by elapsed time rather than a fixed frame count`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        reducer.accept(identity, processed(1, 0, state(identity, 1, "present")))
        val stillChecking = reducer.accept(
            identity,
            processed(2, 399, state(identity, 2, "present")),
        )
        val passed = reducer.accept(identity, processed(3, 400, state(identity, 3, "present")))

        assertTrue(stillChecking is SimilarityFieldValidationState.Collecting)
        assertTrue(passed is SimilarityFieldValidationState.Passed)
        assertEquals(3, passed.summary.processedFrames)
        assertEquals(3, passed.summary.presentFrames)
    }

    @Test
    fun `two valid observations can pass when they span the confirmation interval`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        reducer.accept(identity, processed(1, 100, state(identity, 1, "present")))
        val passed = reducer.accept(identity, processed(2, 500, state(identity, 2, "present")))

        assertTrue(passed is SimilarityFieldValidationState.Passed)
    }

    @Test
    fun `skipped sampling results never advance elapsed evidence`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        reducer.accept(identity, processed(1, 100, state(identity, 1, "present")))
        repeat(20) { sequence ->
            reducer.accept(identity, RuntimeFrameResult.Skipped(sequence.toLong() + 2))
        }
        val collecting = reducer.state

        assertTrue(collecting is SimilarityFieldValidationState.Collecting)
        assertEquals(1, collecting.summary.processedFrames)
    }

    @Test
    fun `unavailable pauses evidence and never becomes absence`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        reducer.accept(identity, processed(1, 0, state(identity, 1, "present")))
        reducer.accept(
            identity,
            processed(2, 100, unavailable(2, UnavailableReason.LOW_QUALITY, "underexposed")),
        )
        val stillChecking = reducer.accept(
            identity,
            processed(3, 500, state(identity, 3, "present")),
        )
        val passed = reducer.accept(identity, processed(4, 900, state(identity, 4, "present")))

        assertTrue(stillChecking is SimilarityFieldValidationState.Collecting)
        assertTrue(passed is SimilarityFieldValidationState.Passed)
        assertEquals(1, passed.summary.unavailableFrames)
        assertEquals(1, passed.summary.unavailableDiagnosticCodes["underexposed"])
    }

    @Test
    fun `long unavailable gap discards only the partial candidate`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        reducer.accept(identity, processed(1, 0, state(identity, 1, "present")))
        reducer.accept(identity, processed(2, 100, unavailable(2, UnavailableReason.NO_FRAME)))
        val reset = reducer.accept(identity, processed(3, 1_700, state(identity, 3, "present")))
        val passed = reducer.accept(identity, processed(4, 2_100, state(identity, 4, "present")))

        assertTrue(reset is SimilarityFieldValidationState.Collecting)
        assertTrue(passed is SimilarityFieldValidationState.Passed)
    }

    @Test
    fun `alternating short unavailable frames retain bounded present evidence`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        reducer.accept(identity, processed(1, 0, state(identity, 1, "present")))
        reducer.accept(
            identity,
            processed(2, 150, unavailable(2, UnavailableReason.LOW_QUALITY)),
        )
        reducer.accept(identity, processed(3, 250, state(identity, 3, "present")))
        reducer.accept(
            identity,
            processed(4, 450, unavailable(4, UnavailableReason.LOW_QUALITY)),
        )
        reducer.accept(
            identity,
            processed(5, 550, state(identity, 5, "present")),
        )
        reducer.accept(
            identity,
            processed(6, 700, unavailable(6, UnavailableReason.LOW_QUALITY)),
        )
        val passed = reducer.accept(
            identity,
            processed(7, 800, state(identity, 7, "present")),
        )

        assertTrue(passed is SimilarityFieldValidationState.Passed)
    }

    @Test
    fun `confirmed absence reports not found and keeps observing`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        reducer.accept(identity, processed(1, 0, state(identity, 1, "absent")))
        val notFound = reducer.accept(identity, processed(2, 400, state(identity, 2, "absent")))

        assertTrue(notFound is SimilarityFieldValidationState.NotFound)
        assertEquals(2, notFound.summary.absentFrames)

        val entering = reducer.accept(identity, processed(3, 500, state(identity, 3, "present")))
        val passed = reducer.accept(identity, processed(4, 900, state(identity, 4, "present")))

        assertTrue(entering is SimilarityFieldValidationState.Collecting)
        assertTrue(passed is SimilarityFieldValidationState.Passed)
    }

    @Test
    fun `signal change starts a new candidate immediately`() {
        val identity = identity()
        val reducer = SimilarityFieldValidationReducer(identity)

        reducer.accept(identity, processed(1, 0, state(identity, 1, "present")))
        reducer.accept(identity, processed(2, 300, state(identity, 2, "present")))
        val changed = reducer.accept(identity, processed(3, 400, state(identity, 3, "absent")))
        val notFound = reducer.accept(identity, processed(4, 800, state(identity, 4, "absent")))

        assertTrue(changed is SimilarityFieldValidationState.Collecting)
        assertTrue(notFound is SimilarityFieldValidationState.NotFound)
    }

    @Test
    fun `confirmed pass is revoked by absence and needs a fresh present interval`() {
        val identity = identity()
        val reducer = passingReducer(identity)
        val firstEpisodeId = (reducer.state as SimilarityFieldValidationState.Passed)
            .stableEpisodeId

        val lost = reducer.accept(identity, processed(3, 500, state(identity, 3, "absent")))
        val reacquiring = reducer.accept(
            identity,
            processed(4, 600, state(identity, 4, "present")),
        )
        val notYetPassed = reducer.accept(
            identity,
            processed(5, 999, state(identity, 5, "present")),
        )
        val passedAgain = reducer.accept(
            identity,
            processed(6, 1_000, state(identity, 6, "present")),
        )

        assertTrue(lost is SimilarityFieldValidationState.Collecting)
        assertEquals(SimilarityFieldValidationSignal.ABSENT, lost.summary.latestSignal)
        assertTrue(reacquiring is SimilarityFieldValidationState.Collecting)
        assertEquals(SimilarityFieldValidationSignal.PRESENT, reacquiring.summary.latestSignal)
        assertTrue(notYetPassed is SimilarityFieldValidationState.Collecting)
        assertTrue(passedAgain is SimilarityFieldValidationState.Passed)
        assertTrue(
            (passedAgain as SimilarityFieldValidationState.Passed).stableEpisodeId != firstEpisodeId,
        )
    }

    @Test
    fun `unavailable pauses a pass without revoking setup eligibility`() {
        val identity = identity()
        val reducer = passingReducer(identity)
        val firstEpisodeId = (reducer.state as SimilarityFieldValidationState.Passed)
            .stableEpisodeId

        val unavailable = reducer.accept(
            identity,
            processed(3, 500, unavailable(3, UnavailableReason.LOW_QUALITY, "motion_blur")),
        )
        val resumed = reducer.accept(
            identity,
            processed(4, 600, state(identity, 4, "present")),
        )

        assertTrue(unavailable is SimilarityFieldValidationState.Passed)
        assertEquals(
            SimilarityFieldValidationSignal.UNAVAILABLE,
            unavailable.summary.latestSignal,
        )
        assertEquals(0, unavailable.summary.absentFrames)
        assertEquals(1, unavailable.summary.unavailableFrames)
        assertTrue(resumed is SimilarityFieldValidationState.Passed)
        assertEquals(SimilarityFieldValidationSignal.PRESENT, resumed.summary.latestSignal)
        assertEquals(
            firstEpisodeId,
            (unavailable as SimilarityFieldValidationState.Passed).stableEpisodeId,
        )
        assertEquals(
            firstEpisodeId,
            (resumed as SimilarityFieldValidationState.Passed).stableEpisodeId,
        )
    }

    @Test
    fun `wrong target state and wrong observation family fail closed`() {
        val identity = identity()
        val wrongTarget = SimilarityFieldValidationReducer(identity).accept(
            identity,
            processed(1, 0, Observation.State("another-target:present", 0.9f, 1, 1)),
        )
        val wrongFamily = SimilarityFieldValidationReducer(identity).accept(
            identity,
            processed(
                1,
                0,
                Observation.Reading(
                    text = "12",
                    valueDecimal = "12",
                    stable = true,
                    sourceSequence = 1,
                    confidence = 1f,
                ),
            ),
        )

        assertEquals(
            SimilarityFieldValidationFailure.INCOMPATIBLE_OBSERVATION,
            (wrongTarget as SimilarityFieldValidationState.Failed).failure,
        )
        assertEquals(
            SimilarityFieldValidationFailure.INCOMPATIBLE_OBSERVATION,
            (wrongFamily as SimilarityFieldValidationState.Failed).failure,
        )
    }

    @Test
    fun `duplicate sequence or regressing clock cannot satisfy evidence`() {
        val identity = identity()
        val duplicateReducer = SimilarityFieldValidationReducer(identity)
        duplicateReducer.accept(identity, processed(9, 100, state(identity, 9, "present")))
        val duplicate = duplicateReducer.accept(
            identity,
            processed(9, 500, state(identity, 9, "present")),
        )

        val clockReducer = SimilarityFieldValidationReducer(identity)
        clockReducer.accept(identity, processed(1, 500, state(identity, 1, "present")))
        val clockRegression = clockReducer.accept(
            identity,
            processed(2, 400, state(identity, 2, "present")),
        )

        assertEquals(
            SimilarityFieldValidationFailure.SOURCE_SEQUENCE_INVALID,
            (duplicate as SimilarityFieldValidationState.Failed).failure,
        )
        assertEquals(
            SimilarityFieldValidationFailure.SOURCE_SEQUENCE_INVALID,
            (clockRegression as SimilarityFieldValidationState.Failed).failure,
        )
    }

    @Test
    fun `revision change invalidates a passed result and cannot recover`() {
        val identity = identity()
        val reducer = passingReducer(identity)
        val changed = identity.copy(taskRevision = identity.taskRevision + 1)

        val invalidated = reducer.ensureIdentity(changed)
        val attemptedReuse = reducer.ensureIdentity(identity)

        assertTrue(invalidated is SimilarityFieldValidationState.Invalidated)
        invalidated as SimilarityFieldValidationState.Invalidated
        assertEquals(
            setOf(SimilarityFieldValidationIdentityChange.TASK_REVISION),
            invalidated.changes,
        )
        assertEquals(invalidated, attemptedReuse)
    }

    @Test
    fun `package pointer and ROI changes are exact identity changes`() {
        val identity = identity()
        val changed = identity.copy(
            packagePointer = pointer(manifestSha256 = "b".repeat(64)),
            roi = NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f),
        )
        val result = SimilarityFieldValidationReducer(identity).ensureIdentity(changed)

        assertTrue(result is SimilarityFieldValidationState.Invalidated)
        assertEquals(
            setOf(
                SimilarityFieldValidationIdentityChange.PACKAGE_POINTER,
                SimilarityFieldValidationIdentityChange.ROI,
            ),
            (result as SimilarityFieldValidationState.Invalidated).changes,
        )
        assertEquals(0, result.summary.processedFrames)
    }

    private fun passingReducer(identity: SimilarityFieldValidationIdentity) =
        SimilarityFieldValidationReducer(identity).also { reducer ->
            reducer.accept(identity, processed(1, 0, state(identity, 1, "present")))
            reducer.accept(identity, processed(2, 400, state(identity, 2, "present")))
            assertTrue(reducer.state is SimilarityFieldValidationState.Passed)
        }

    private fun identity() = SimilarityFieldValidationIdentity(
        taskId = "0198f5a4-1357-7abc-8def-0123456789ab",
        taskRevision = 4,
        packagePointer = pointer(),
        roi = NormalizedRect(0f, 0f, 1f, 1f),
        targetId = "target-reference-01",
    )

    private fun pointer(
        version: String = "1.0.0",
        manifestSha256: String = "a".repeat(64),
    ) = ModelPackagePointer(
        identity = ModelPackageIdentity(
            packageId = "similarity_test_fixture",
            packageVersion = version,
        ),
        canonicalManifestSha256 = manifestSha256,
    )

    private fun state(
        identity: SimilarityFieldValidationIdentity,
        sourceSequence: Long,
        suffix: String,
    ) = Observation.State(
        stateId = "${identity.targetId}:$suffix",
        confidence = 0.75f,
        sourceSequence = sourceSequence,
        stableFrameCount = 1,
    )

    private fun unavailable(
        sourceSequence: Long,
        reason: UnavailableReason,
        diagnosticCode: String? = null,
    ) = Observation.Unavailable(reason, diagnosticCode, sourceSequence)

    private fun processed(
        sourceSequence: Long,
        monotonicTimeMillis: Long,
        observation: Observation,
    ) = RuntimeFrameResult.Processed(
        PipelineResult(
            sourceSequence = sourceSequence,
            monotonicTimeMillis = monotonicTimeMillis,
            capturedAtEpochMillis = null,
            observation = observation,
            timings = PipelineTimings(0, 0, 0, 0, 0),
        ),
    )
}
