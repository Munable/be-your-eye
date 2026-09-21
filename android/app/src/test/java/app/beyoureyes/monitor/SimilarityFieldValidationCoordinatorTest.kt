package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.Detection
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.core.vision.PipelineTimings
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityFieldValidationCoordinatorTest {
    @Test
    fun `checking uses elapsed evidence and keeps session live after confirmation`() {
        val identity = identity()
        val outputs = listOf("present", "present", "present")
        val session = FakeSession(identity.packagePointer) { frame ->
            when (val output = outputs[frame.sourceSequence.toInt() - 1]) {
                "unavailable" -> processed(
                    frame.sourceSequence,
                    Observation.Unavailable(
                        UnavailableReason.LOW_QUALITY,
                        "field_frame_low_quality",
                        frame.sourceSequence,
                    ),
                )
                else -> processed(frame.sourceSequence, state(identity, frame.sourceSequence, output))
            }
        }
        val coordinator = coordinator(identity, session)

        assertTrue(coordinator.state is SimilarityFieldValidationUiState.Checking)
        coordinator.accept(identity, normalizedFrame(1, identity.roi))
        val checking = coordinator.state as SimilarityFieldValidationUiState.Checking
        assertEquals(1, checking.summary.processedFrames)

        val result = coordinator.accept(identity, normalizedFrame(2, identity.roi))

        assertTrue(result is SimilarityFieldValidationUiState.Passed)
        val stableEpisodeId = (result as SimilarityFieldValidationUiState.Passed).stableEpisodeId
        assertEquals(2, result.summary.processedFrames)
        assertEquals(2, result.summary.presentFrames)
        assertEquals(2, session.processCalls)
        assertEquals(0, session.closeCalls)

        val stillPassed = coordinator.accept(identity, normalizedFrame(3, identity.roi))

        assertTrue(stillPassed is SimilarityFieldValidationUiState.Passed)
        assertEquals(
            stableEpisodeId,
            (stillPassed as SimilarityFieldValidationUiState.Passed).stableEpisodeId,
        )
        assertEquals(3, session.processCalls)
        assertEquals(0, session.closeCalls)
        val handoffStatus = coordinator.state.toSetupStatus()
        assertTrue(fieldValidationPassedFor(handoffStatus, identity))
        assertTrue(fieldValidationPassedForEpisode(handoffStatus, identity, stableEpisodeId))

        coordinator.close()

        assertEquals(1, session.closeCalls)
        assertTrue(fieldValidationPassedFor(handoffStatus, identity))
        val closed = coordinator.state as SimilarityFieldValidationUiState.Failed
        assertEquals(SimilarityFieldValidationFailureReason.SESSION_CLOSED, closed.reason)
        assertTrue(closed.toSetupStatus() is SetupFieldValidationStatus.Failed)
    }

    @Test
    fun `skipped runtime frames do not advance elapsed evidence`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            if (frame.sourceSequence <= 2) {
                RuntimeFrameResult.Skipped(frame.sourceSequence)
            } else {
                processed(frame.sourceSequence, state(identity, frame.sourceSequence, "present"))
            }
        }
        val coordinator = coordinator(identity, session)

        repeat(4) { index ->
            val sequence = index.toLong() + 1
            coordinator.accept(identity, normalizedFrame(sequence, identity.roi))
        }

        val passed = coordinator.state as SimilarityFieldValidationUiState.Passed
        assertEquals(2, passed.summary.processedFrames)
        assertEquals(2, passed.summary.presentFrames)
        assertEquals(4, session.processCalls)
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `task revision change invalidates even a passed result and cannot recover`() {
        val identity = identity()
        val session = alwaysPresentSession(identity)
        val coordinator = coordinator(identity, session)
        repeat(2) { index ->
            coordinator.accept(identity, normalizedFrame(index.toLong() + 1, identity.roi))
        }
        assertTrue(coordinator.state is SimilarityFieldValidationUiState.Passed)

        val changed = identity.copy(taskRevision = identity.taskRevision + 1)
        val invalidated = coordinator.ensureIdentity(changed)
        val attemptedReuse = coordinator.ensureIdentity(identity)

        assertTrue(invalidated is SimilarityFieldValidationUiState.Invalidated)
        invalidated as SimilarityFieldValidationUiState.Invalidated
        assertEquals(
            SimilarityFieldValidationInvalidationReason.TASK_IDENTITY_CHANGED,
            invalidated.reason,
        )
        assertEquals(
            setOf(SimilarityFieldValidationIdentityChange.TASK_REVISION),
            invalidated.changes,
        )
        assertSame(invalidated, attemptedReuse)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `lease package pointer change terminally invalidates before processing a frame`() {
        val identity = identity()
        val session = alwaysPresentSession(identity)
        val coordinator = coordinator(identity, session)
        session.packagePointer = pointer(version = "1.0.1", manifest = "b".repeat(64))

        val result = coordinator.accept(identity, normalizedFrame(1, identity.roi))

        assertTrue(result is SimilarityFieldValidationUiState.Invalidated)
        result as SimilarityFieldValidationUiState.Invalidated
        assertEquals(
            SimilarityFieldValidationInvalidationReason.LEASE_PACKAGE_CHANGED,
            result.reason,
        )
        assertEquals(
            setOf(SimilarityFieldValidationIdentityChange.PACKAGE_POINTER),
            result.changes,
        )
        assertEquals(0, session.processCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `frame ROI change terminally invalidates without passing pixels to runtime`() {
        val identity = identity()
        val session = alwaysPresentSession(identity)
        val coordinator = coordinator(identity, session)
        val otherRoi = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)

        val result = coordinator.accept(identity, normalizedFrame(1, otherRoi))

        assertTrue(result is SimilarityFieldValidationUiState.Invalidated)
        result as SimilarityFieldValidationUiState.Invalidated
        assertEquals(SimilarityFieldValidationInvalidationReason.FRAME_ROI_CHANGED, result.reason)
        assertEquals(setOf(SimilarityFieldValidationIdentityChange.ROI), result.changes)
        assertEquals(0, session.processCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `non upright ROI frame fails closed without calling runtime`() {
        val identity = identity()
        val session = alwaysPresentSession(identity)
        val coordinator = coordinator(identity, session)
        val invalidFrame = normalizedFrame(1, identity.roi).let { value ->
            value.copy(sourceFrame = value.sourceFrame.copy(rotationDegrees = 90))
        }

        val result = coordinator.accept(identity, invalidFrame)

        assertTrue(result is SimilarityFieldValidationUiState.Failed)
        assertEquals(
            SimilarityFieldValidationFailureReason.FRAME_NOT_UPRIGHT_ROI,
            (result as SimilarityFieldValidationUiState.Failed).reason,
        )
        assertEquals(0, session.processCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `runtime exception fails closed and releases the exact lease session`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { error("runtime exploded") }
        val coordinator = coordinator(identity, session)

        val result = coordinator.accept(identity, normalizedFrame(1, identity.roi))

        assertTrue(result is SimilarityFieldValidationUiState.Failed)
        assertEquals(
            SimilarityFieldValidationFailureReason.RUNTIME_PROCESSING_FAILED,
            (result as SimilarityFieldValidationUiState.Failed).reason,
        )
        assertEquals(1, session.processCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `initial exact lease acquisition failure is explicit and media free`() {
        val identity = identity()
        val coordinator = SimilarityFieldValidationCoordinator.fromOpenResult(
            identity,
            SimilarityFieldValidationSessionOpenResult.Failed(
                SimilarityFieldValidationFailureReason.PACKAGE_UNAVAILABLE,
                setOf("package_artifact_sha256_mismatch"),
            ),
        )

        val state = coordinator.state as SimilarityFieldValidationUiState.Failed

        assertEquals(SimilarityFieldValidationFailureReason.PACKAGE_UNAVAILABLE, state.reason)
        assertEquals(setOf("package_artifact_sha256_mismatch"), state.diagnosticCodes)
        assertEquals(0, state.summary.processedFrames)
    }

    @Test
    fun `confirmed absence keeps runtime open and a later present interval passes`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            val suffix = if (frame.sourceSequence <= 2) "absent" else "present"
            processed(frame.sourceSequence, state(identity, frame.sourceSequence, suffix))
        }
        val coordinator = coordinator(identity, session)

        repeat(2) { index ->
            coordinator.accept(identity, normalizedFrame(index.toLong() + 1, identity.roi))
        }

        val notFound = coordinator.state as SimilarityFieldValidationUiState.NotFound
        assertEquals(2, notFound.summary.absentFrames)
        assertEquals(0, session.closeCalls)

        repeat(2) { index ->
            coordinator.accept(identity, normalizedFrame(index.toLong() + 3, identity.roi))
        }

        val passed = coordinator.state as SimilarityFieldValidationUiState.Passed
        assertEquals(2, passed.summary.presentFrames)
        assertEquals(2, passed.summary.absentFrames)
        assertEquals(4, session.processCalls)
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `not found changes to checking as soon as a present candidate appears`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            val suffix = if (frame.sourceSequence <= 2) "absent" else "present"
            processed(frame.sourceSequence, state(identity, frame.sourceSequence, suffix))
        }
        val coordinator = coordinator(identity, session)
        repeat(2) { index ->
            coordinator.accept(identity, normalizedFrame(index.toLong() + 1, identity.roi))
        }

        val firstPartial = coordinator.accept(identity, normalizedFrame(3, identity.roi))

        assertTrue(firstPartial is SimilarityFieldValidationUiState.Checking)
        assertEquals(
            SimilarityFieldValidationSignal.PRESENT,
            firstPartial.summary.latestSignal,
        )
    }

    @Test
    fun `passed target loss revokes immediately and reacquires on a fresh interval`() {
        val identity = identity()
        val outputs = listOf("present", "present", "absent", "present", "present")
        val session = FakeSession(identity.packagePointer) { frame ->
            processed(
                frame.sourceSequence,
                state(identity, frame.sourceSequence, outputs[frame.sourceSequence.toInt() - 1]),
            )
        }
        val coordinator = coordinator(identity, session)
        coordinator.accept(identity, normalizedFrame(1, identity.roi))
        val firstPassed = coordinator.accept(identity, normalizedFrame(2, identity.roi))
        assertTrue(firstPassed is SimilarityFieldValidationUiState.Passed)
        val firstEpisodeId = (firstPassed as SimilarityFieldValidationUiState.Passed)
            .stableEpisodeId

        val lost = coordinator.accept(identity, normalizedFrame(3, identity.roi))
        val provisional = coordinator.accept(identity, normalizedFrame(4, identity.roi))
        val reacquired = coordinator.accept(identity, normalizedFrame(5, identity.roi))

        assertTrue(lost is SimilarityFieldValidationUiState.Checking)
        assertEquals(SimilarityFieldValidationSignal.ABSENT, lost.summary.latestSignal)
        assertTrue(provisional is SimilarityFieldValidationUiState.Checking)
        assertEquals(SimilarityFieldValidationSignal.PRESENT, provisional.summary.latestSignal)
        assertTrue(reacquired is SimilarityFieldValidationUiState.Passed)
        assertTrue(
            (reacquired as SimilarityFieldValidationUiState.Passed).stableEpisodeId != firstEpisodeId,
        )
        assertEquals(5, session.processCalls)
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `unavailable pauses a pass without revoking setup eligibility`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            if (frame.sourceSequence == 3L) {
                processed(
                    frame.sourceSequence,
                    Observation.Unavailable(
                        UnavailableReason.LOW_QUALITY,
                        "motion_blur",
                        frame.sourceSequence,
                    ),
                )
            } else {
                processed(
                    frame.sourceSequence,
                    state(identity, frame.sourceSequence, "present"),
                )
            }
        }
        val coordinator = coordinator(identity, session)
        coordinator.accept(identity, normalizedFrame(1, identity.roi))
        val firstPassed = coordinator.accept(identity, normalizedFrame(2, identity.roi))
        assertTrue(firstPassed is SimilarityFieldValidationUiState.Passed)
        val firstEpisodeId = (firstPassed as SimilarityFieldValidationUiState.Passed)
            .stableEpisodeId

        val unavailable = coordinator.accept(identity, normalizedFrame(3, identity.roi))
        val resumed = coordinator.accept(identity, normalizedFrame(4, identity.roi))

        assertTrue(unavailable is SimilarityFieldValidationUiState.Passed)
        assertEquals(
            SimilarityFieldValidationSignal.UNAVAILABLE,
            unavailable.summary.latestSignal,
        )
        assertEquals(0, unavailable.summary.absentFrames)
        assertTrue(resumed is SimilarityFieldValidationUiState.Passed)
        assertEquals(
            firstEpisodeId,
            (unavailable as SimilarityFieldValidationUiState.Passed).stableEpisodeId,
        )
        assertEquals(
            firstEpisodeId,
            (resumed as SimilarityFieldValidationUiState.Passed).stableEpisodeId,
        )
        assertEquals(4, session.processCalls)
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `reference and object setup cadence use each signed minimum interval`() {
        assertEquals(100L, setupFieldValidationSamplingPolicy(100L).analysisIntervalMillis)
        assertEquals(250L, setupFieldValidationSamplingPolicy(250L).analysisIntervalMillis)
    }

    @Test
    fun `latest processed detection is exposed only while the live setup session owns it`() {
        val identity = identity()
        val detection = Detection(
            label = identity.targetId,
            score = 0.91f,
            box = NormalizedRect(0.2f, 0.3f, 0.7f, 0.8f),
        )
        val coordinator = coordinator(
            identity,
            FakeSession(identity.packagePointer) { frame ->
                processed(
                    frame.sourceSequence,
                    Observation.Detections(listOf(detection), frame.sourceSequence),
                )
            },
        )

        coordinator.accept(identity, normalizedFrame(1, identity.roi))

        assertEquals(
            listOf(detection),
            (coordinator.latestObservation as Observation.Detections).items,
        )

        coordinator.close()

        assertNull(coordinator.latestObservation)
    }

    private fun coordinator(
        identity: SimilarityFieldValidationIdentity,
        session: SimilarityFieldValidationRuntimeSession,
    ) = SimilarityFieldValidationCoordinator.fromOpenResult(
        identity,
        SimilarityFieldValidationSessionOpenResult.Ready(session),
    )

    private fun alwaysPresentSession(identity: SimilarityFieldValidationIdentity) =
        FakeSession(identity.packagePointer) { frame ->
            processed(frame.sourceSequence, state(identity, frame.sourceSequence, "present"))
        }

    private fun normalizedFrame(
        sourceSequence: Long,
        roi: NormalizedRect,
    ): NormalizedRoiSourceFrame {
        val width = 24
        val height = 18
        val bytes = ByteArray(width * height * 3) { index ->
            ((index + sourceSequence.toInt()) % 251).toByte()
        }
        return NormalizedRoiSourceFrame(
            roi = roi,
            sourceFrame = SourceFrame(
                sourceSequence = sourceSequence,
                monotonicTimeMillis = sourceSequence * 1_000,
                capturedAtEpochMillis = 1_700_000_000_000 + sourceSequence * 1_000,
                width = width,
                height = height,
                rotationDegrees = 0,
                cropRect = PixelRect(0, 0, width, height),
                pixels = FramePixels.Rgb888(bytes, width * 3),
            ),
        )
    }

    private fun processed(
        sourceSequence: Long,
        observation: Observation,
    ) = RuntimeFrameResult.Processed(
        PipelineResult(
            sourceSequence = sourceSequence,
            monotonicTimeMillis = sourceSequence * 1_000,
            capturedAtEpochMillis = 1_700_000_000_000 + sourceSequence * 1_000,
            observation = observation,
            timings = PipelineTimings(0, 0, 0, 0, 0),
        ),
    )

    private fun state(
        identity: SimilarityFieldValidationIdentity,
        sourceSequence: Long,
        suffix: String,
    ) = Observation.State(
        stateId = "${identity.targetId}:$suffix",
        confidence = 0.9f,
        sourceSequence = sourceSequence,
        stableFrameCount = 1,
    )

    private fun identity() = SimilarityFieldValidationIdentity(
        taskId = "0198f5a4-1357-7abc-8def-0123456789ab",
        taskRevision = 7,
        packagePointer = pointer(),
        roi = NormalizedRect(0f, 0f, 1f, 1f),
        targetId = "field-target-01",
    )

    private fun pointer(
        version: String = "1.0.0",
        manifest: String = "a".repeat(64),
    ) = ModelPackagePointer(
        identity = ModelPackageIdentity("similarity_test_fixture", version),
        canonicalManifestSha256 = manifest,
    )

    private class FakeSession(
        override var packagePointer: ModelPackagePointer,
        private val processor: (SourceFrame) -> RuntimeFrameResult,
    ) : SimilarityFieldValidationRuntimeSession {
        var processCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun process(frame: SourceFrame): RuntimeFrameResult {
            processCalls++
            return processor(frame)
        }

        override fun close() {
            closeCalls++
        }
    }
}
