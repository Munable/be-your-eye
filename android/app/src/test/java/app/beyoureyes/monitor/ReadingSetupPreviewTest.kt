package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.domain.parseStructuredReading
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.core.vision.PipelineTimings
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSetupPreviewTest {
    @Test
    fun `scan again resets the automatic anchor and requires a fresh baseline`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            processed(frame.sourceSequence, reading(frame.sourceSequence, "42", stable = true))
        }
        val coordinator = coordinator(identity, session)
        coordinator.accept(identity, frame(1, identity.roi))
        assertTrue(coordinator.confirm(identity) is ReadingPreviewStatus.Confirmed)

        val reset = coordinator.updateManualScanRegion(identity, null)

        assertEquals(1, session.anchorResetCalls)
        assertTrue(reset is ReadingPreviewStatus.Scanning)
        assertFalse(readingPreviewConfirmedFor(reset, identity))
        assertFalse(readingPreviewInferencePaused(reset))
        assertTrue(coordinator.accept(identity, frame(2, identity.roi)) is ReadingPreviewStatus.AwaitingConfirmation)
    }

    @Test
    fun `unreadable scenes may suggest a box but never pause automatic scanning`() {
        val identity = identity()
        val unreadable = ReadingPreviewStatus.Scanning(
            expectedIdentity = identity,
            processedFrames = 1,
            unavailableFrames = 1,
            live = ReadingPreviewLiveState.Unavailable(
                UnavailableReason.LOW_QUALITY,
                "structured_reading_candidates_unreadable",
            ),
        )
        val recoverableBlur = unreadable.copy(
            live = ReadingPreviewLiveState.Unavailable(
                UnavailableReason.LOW_QUALITY,
                "blurred_reading",
            ),
        )
        val shapeUnavailable = unreadable.copy(
            live = ReadingPreviewLiveState.Unavailable(
                UnavailableReason.LOW_QUALITY,
                "numeric_target_shape_unavailable",
            ),
        )
        val candidatesUnreadable = unreadable.copy(
            live = ReadingPreviewLiveState.Unavailable(
                UnavailableReason.LOW_QUALITY,
                "structured_reading_candidates_unreadable",
            ),
        )

        assertTrue(readingPreviewSuggestsManualRegion(unreadable))
        assertFalse(readingPreviewInferencePaused(unreadable))
        assertTrue(readingPreviewSuggestsManualRegion(shapeUnavailable))
        assertTrue(readingPreviewSuggestsManualRegion(candidatesUnreadable))
        assertFalse(readingPreviewSuggestsManualRegion(recoverableBlur))
        assertFalse(readingPreviewInferencePaused(shapeUnavailable))
        assertTrue(
            readingPreviewInferencePaused(
                ReadingPreviewStatus.Confirmed(
                    expectedIdentity = identity,
                    value = readingValue("34"),
                    confirmedFormat = readingValue("34").format,
                    processedFrames = 2,
                    unavailableFrames = 0,
                    live = ReadingPreviewLiveState.Stable(readingValue("34")),
                ),
            ),
        )
    }

    @Test
    fun `stable production reading keeps running and preserves confirmed decimal format`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            val observation = when (frame.sourceSequence) {
                1L -> reading(frame.sourceSequence, "1306", stable = true)
                2L -> reading(frame.sourceSequence, "1307", stable = false)
                3L, 6L -> Observation.Unavailable(
                    UnavailableReason.LOW_QUALITY,
                    "blurred_reading",
                    frame.sourceSequence,
                )
                4L -> reading(frame.sourceSequence, "1308", stable = true)
                5L -> reading(frame.sourceSequence, "1309", stable = false)
                7L -> reading(frame.sourceSequence, "1310", stable = true)
                else -> reading(frame.sourceSequence, "1308", stable = true)
            }
            processed(frame.sourceSequence, observation)
        }
        val coordinator = coordinator(identity, session)

        val firstStable = coordinator.accept(identity, frame(1, identity.roi))

        assertTrue(firstStable is ReadingPreviewStatus.AwaitingConfirmation)
        assertFalse(readingPreviewConfirmedFor(firstStable, identity))
        assertEquals(0, session.closeCalls)

        val candidate = coordinator.accept(identity, frame(2, identity.roi)) as ReadingPreviewStatus.Scanning
        assertEquals("1307", (candidate.live as ReadingPreviewLiveState.Candidate).value.text)
        val unavailable = coordinator.accept(identity, frame(3, identity.roi)) as ReadingPreviewStatus.Scanning
        assertTrue(unavailable.live is ReadingPreviewLiveState.Unavailable)

        val latestStable = coordinator.accept(identity, frame(4, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation
        assertEquals("1308", latestStable.live.value.text)
        assertTrue(latestStable.correctBaseline(" 13.08 "))
        assertEquals("13.08", latestStable.value.valueDecimal)

        val confirmed = coordinator.confirm(identity)

        assertTrue(confirmed is ReadingPreviewStatus.Confirmed)
        assertTrue(readingPreviewConfirmedFor(confirmed, identity))
        assertEquals("13.08", (confirmed as ReadingPreviewStatus.Confirmed).value.valueDecimal)
        assertEquals(2, confirmed.confirmedFormat.fractionalDigits)

        val liveCandidate = coordinator.accept(identity, frame(5, identity.roi))
            as ReadingPreviewStatus.Confirmed
        assertEquals("13.08", liveCandidate.value.valueDecimal)
        assertEquals(
            "13.09",
            (liveCandidate.live as ReadingPreviewLiveState.Candidate).value.valueDecimal,
        )
        assertFalse(readingPreviewConfirmedFor(liveCandidate, identity))

        val liveUnavailable = coordinator.accept(identity, frame(6, identity.roi))
            as ReadingPreviewStatus.Confirmed
        assertEquals("13.08", liveUnavailable.value.valueDecimal)
        assertTrue(liveUnavailable.live is ReadingPreviewLiveState.Unavailable)
        assertFalse(readingPreviewConfirmedFor(liveUnavailable, identity))

        val recovered = coordinator.accept(identity, frame(7, identity.roi))
            as ReadingPreviewStatus.Confirmed
        assertEquals("13.08", recovered.value.valueDecimal)
        val recoveredValue = (recovered.live as ReadingPreviewLiveState.Stable).value
        assertEquals("13.10", recoveredValue.text)
        assertEquals("13.1", recoveredValue.valueDecimal)
        assertTrue(readingPreviewConfirmedFor(recovered, identity))
        assertEquals(0, session.closeCalls)

        val matched = coordinator.accept(identity, frame(8, identity.roi))
            as ReadingPreviewStatus.Confirmed
        assertEquals("13.08", (matched.live as ReadingPreviewLiveState.Stable).value.valueDecimal)
        assertTrue(readingPreviewConfirmedFor(matched, identity))

        coordinator.close()

        assertEquals(1, session.closeCalls)
        assertTrue(coordinator.state is ReadingPreviewStatus.Confirmed)
    }

    @Test
    fun `invalid manual correction does not replace the recognized baseline`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            processed(frame.sourceSequence, reading(frame.sourceSequence, "333", stable = true))
        }
        val coordinator = coordinator(identity, session)
        val awaiting = coordinator.accept(identity, frame(1, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation

        assertFalse(awaiting.correctBaseline("13:06"))
        assertEquals("333", awaiting.value.valueDecimal)

        val confirmed = coordinator.confirm(identity) as ReadingPreviewStatus.Confirmed
        assertEquals("333", confirmed.value.valueDecimal)
    }

    @Test
    fun `manual correction rejects OCR digit and sign substitution`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            processed(frame.sourceSequence, reading(frame.sourceSequence, "38.4", stable = true))
        }
        val coordinator = coordinator(identity, session)
        val awaiting = coordinator.accept(identity, frame(1, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation

        assertFalse(awaiting.correctBaseline("-38.5"))
        assertFalse(awaiting.correctBaseline("38.5"))
        val confirmed = coordinator.confirm(identity) as ReadingPreviewStatus.Confirmed

        assertEquals("38.4", confirmed.value.valueDecimal)
        assertEquals(1, confirmed.confirmedFormat.fractionalDigits)
        assertEquals(
            "38.4",
            (confirmed.live as ReadingPreviewLiveState.Stable).value.text,
        )
        assertTrue(readingPreviewConfirmedFor(confirmed, identity))
    }

    @Test
    fun `manual confirmation restores an omitted decimal point without changing live digits`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            processed(frame.sourceSequence, reading(frame.sourceSequence, "148", stable = true))
        }
        val coordinator = coordinator(identity, session)
        val awaiting = coordinator.accept(identity, frame(1, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation

        assertTrue(awaiting.correctBaseline("14.8"))
        val confirmed = coordinator.confirm(identity) as ReadingPreviewStatus.Confirmed

        assertEquals("14.8", confirmed.value.valueDecimal)
        assertEquals(1, confirmed.confirmedFormat.fractionalDigits)
        assertEquals("14.8", (confirmed.live as ReadingPreviewLiveState.Stable).value.text)
    }

    @Test
    fun `unavailable frames keep scanning until a stable reading arrives`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            if (frame.sourceSequence == 1L) {
                RuntimeFrameResult.Skipped(frame.sourceSequence)
            } else if (frame.sourceSequence <= 40L) {
                processed(
                    frame.sourceSequence,
                    Observation.Unavailable(
                        UnavailableReason.LOW_QUALITY,
                        "blurred_reading",
                        frame.sourceSequence,
                    ),
                )
            } else {
                processed(frame.sourceSequence, reading(frame.sourceSequence, "333", stable = true))
            }
        }
        val coordinator = coordinator(identity, session)

        repeat(40) { index ->
            coordinator.accept(identity, frame(index.toLong() + 1, identity.roi))
        }

        assertTrue(coordinator.state is ReadingPreviewStatus.Scanning)
        assertEquals(0, session.closeCalls)

        val stable = coordinator.accept(identity, frame(41, identity.roi))

        assertTrue(stable is ReadingPreviewStatus.AwaitingConfirmation)
        assertEquals("333", (stable as ReadingPreviewStatus.AwaitingConfirmation).value.text)
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `confirmation fixes the visible stable draft when unavailable wins the click race`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            if (frame.sourceSequence == 1L || frame.sourceSequence == 3L) {
                processed(frame.sourceSequence, reading(frame.sourceSequence, "123", stable = true))
            } else {
                processed(
                    frame.sourceSequence,
                    Observation.Unavailable(
                        UnavailableReason.LOW_QUALITY,
                        "temporary_glare",
                        frame.sourceSequence,
                    ),
                )
            }
        }
        val coordinator = coordinator(identity, session)

        val visible = coordinator.accept(identity, frame(1, identity.roi))
        assertTrue(visible is ReadingPreviewStatus.AwaitingConfirmation)

        val raced = coordinator.accept(identity, frame(2, identity.roi))
        assertTrue(raced is ReadingPreviewStatus.Scanning)
        assertTrue((raced as ReadingPreviewStatus.Scanning).live is ReadingPreviewLiveState.Unavailable)

        val confirmed = coordinator.confirm(identity) as ReadingPreviewStatus.Confirmed
        assertEquals("123", confirmed.value.valueDecimal)
        assertTrue(confirmed.live is ReadingPreviewLiveState.Unavailable)
        assertFalse(readingPreviewConfirmedFor(confirmed, identity))

        val recovered = coordinator.accept(identity, frame(3, identity.roi))
            as ReadingPreviewStatus.Confirmed
        assertEquals("123", recovered.value.valueDecimal)
        assertTrue(readingPreviewConfirmedFor(recovered, identity))
    }

    @Test
    fun `format correction freezes the chosen stable sample even when a frame wins the click race`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            processed(
                frame.sourceSequence,
                when (frame.sourceSequence) {
                    1L -> reading(frame.sourceSequence, "032", stable = true)
                    2L -> reading(frame.sourceSequence, "033", stable = true)
                    else -> Observation.Unavailable(
                        UnavailableReason.LOW_QUALITY,
                        "temporary_glare",
                        frame.sourceSequence,
                    )
                },
            )
        }
        val coordinator = coordinator(identity, session)
        val visible = coordinator.accept(identity, frame(1, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation
        coordinator.accept(identity, frame(2, identity.roi))
        coordinator.accept(identity, frame(3, identity.roi))

        val editing = coordinator.beginFormatCorrection(visible)
            as ReadingPreviewStatus.AwaitingConfirmation
        assertTrue(editing.editingFormat)
        assertTrue(readingPreviewInferencePaused(editing))
        assertEquals("032", editing.live.value.text)
        assertTrue(editing === coordinator.accept(identity, frame(4, identity.roi)))
        assertEquals(3, session.processCalls)
        assertFalse(editing.correctBaseline("04.2"))
        assertFalse(editing.correctBaseline("-03.2"))
        assertTrue(editing.correctBaseline("03.2"))

        val confirmed = coordinator.confirmFormatCorrection(editing) as ReadingPreviewStatus.Confirmed
        assertEquals("3.2", confirmed.value.valueDecimal)
        assertEquals(1, confirmed.confirmedFormat.fractionalDigits)
    }

    @Test
    fun `returning from format correction resumes scanning with a fresh baseline`() {
        val identity = identity()
        val session = FakeSession(identity.packagePointer) { frame ->
            processed(
                frame.sourceSequence,
                when (frame.sourceSequence) {
                    1L -> reading(frame.sourceSequence, "032", stable = true)
                    2L -> Observation.Unavailable(UnavailableReason.LOW_QUALITY, "temporary_glare", frame.sourceSequence)
                    else -> reading(frame.sourceSequence, "045", stable = true)
                },
            )
        }
        val coordinator = coordinator(identity, session)
        val visible = coordinator.accept(identity, frame(1, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation
        val editing = coordinator.beginFormatCorrection(visible)
            as ReadingPreviewStatus.AwaitingConfirmation
        assertTrue(editing.correctBaseline("03.2"))

        val scanning = coordinator.cancelFormatCorrection(identity) as ReadingPreviewStatus.Scanning
        assertFalse(readingPreviewInferencePaused(scanning))
        assertTrue(scanning.live is ReadingPreviewLiveState.Waiting)
        val unavailable = coordinator.accept(identity, frame(2, identity.roi)) as ReadingPreviewStatus.Scanning
        assertTrue(unavailable.live is ReadingPreviewLiveState.Unavailable)
        val next = coordinator.accept(identity, frame(3, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation
        assertFalse(next.editingFormat)
        assertEquals("045", next.value.text)
        assertEquals(3, session.processCalls)
    }

    @Test
    fun `redrawing the region discards the correction sample and rejects stale editing clicks`() {
        val identity = identity()
        val coordinator = coordinator(
            identity,
            FakeSession(identity.packagePointer) { frame ->
                processed(frame.sourceSequence, reading(frame.sourceSequence, "032", stable = true))
            },
        )
        val visible = coordinator.accept(identity, frame(1, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation
        val editing = coordinator.beginFormatCorrection(visible)
            as ReadingPreviewStatus.AwaitingConfirmation

        val reset = coordinator.updateManualScanRegion(identity, NormalizedRect(0.2f, 0.3f, 0.6f, 0.7f))
        assertTrue(reset is ReadingPreviewStatus.Scanning)
        assertFalse(readingPreviewInferencePaused(reset))
        assertTrue(reset === coordinator.beginFormatCorrection(editing))
        assertTrue(reset === coordinator.confirmFormatCorrection(editing))
        val next = coordinator.accept(identity, frame(2, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation
        assertFalse(next.editingFormat)
        assertTrue(next === coordinator.beginFormatCorrection(visible))
        assertTrue(next === coordinator.beginFormatCorrection(editing))
        assertTrue(next === coordinator.confirmFormatCorrection(editing))
    }

    @Test
    fun `closed or replaced tasks cannot reuse an old correction sample`() {
        val identity = identity()
        val original = coordinator(
            identity,
            FakeSession(identity.packagePointer) { frame ->
                processed(frame.sourceSequence, reading(frame.sourceSequence, "032", stable = true))
            },
        )
        val visible = original.accept(identity, frame(1, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation
        val editing = original.beginFormatCorrection(visible) as ReadingPreviewStatus.AwaitingConfirmation
        original.close()
        assertTrue(original.beginFormatCorrection(visible) is ReadingPreviewStatus.Failed)
        assertTrue(original.confirmFormatCorrection(editing) is ReadingPreviewStatus.Failed)

        val replacementIdentity = identity.copy(taskId = "replacement-task")
        val replacement = coordinator(
            replacementIdentity,
            FakeSession(identity.packagePointer) { frame -> RuntimeFrameResult.Skipped(frame.sourceSequence) },
        )
        val initial = replacement.state
        assertTrue(initial === replacement.beginFormatCorrection(visible))
        assertTrue(initial === replacement.cancelFormatCorrection(identity))
        assertTrue(initial === replacement.confirmFormatCorrection(editing))

        val invalidated = coordinator(
            identity,
            FakeSession(identity.packagePointer) { frame ->
                processed(frame.sourceSequence, reading(frame.sourceSequence, "032", stable = true))
            },
        )
        val selected = invalidated.accept(identity, frame(1, identity.roi))
            as ReadingPreviewStatus.AwaitingConfirmation
        val frozen = invalidated.beginFormatCorrection(selected) as ReadingPreviewStatus.AwaitingConfirmation
        val stopped = invalidated.ensureIdentity(identity.copy(taskRevision = identity.taskRevision + 1))
        assertTrue(stopped is ReadingPreviewStatus.Invalidated)
        assertTrue(stopped === invalidated.confirmFormatCorrection(frozen))
    }

    @Test
    fun `task revision package or roi change invalidates an old preview`() {
        val identity = identity()
        val changes = listOf(
            identity.copy(taskId = "0198f5a4-1357-7abc-8def-0123456789ac"),
            identity.copy(taskRevision = identity.taskRevision + 1),
            identity.copy(
                packagePointer = ModelPackagePointer(
                    ModelPackageIdentity("reading_test_fixture_next", "1.0.0"),
                    "b".repeat(64),
                ),
            ),
            identity.copy(roi = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)),
        )

        changes.forEach { changed ->
            val coordinator = coordinator(
                identity,
                FakeSession(identity.packagePointer) {
                    RuntimeFrameResult.Skipped(it.sourceSequence)
                },
            )
            val result = coordinator.ensureIdentity(changed)

            assertTrue(result is ReadingPreviewStatus.Invalidated)
            assertFalse(readingPreviewConfirmedFor(result, identity))
        }
    }

    private fun coordinator(identity: ReadingPreviewIdentity, session: ReadingPreviewRuntimeSession) =
        ReadingPreviewCoordinator.fromOpenResult(identity, ReadingPreviewOpenResult.Ready(session))

    private fun identity() = ReadingPreviewIdentity(
        taskId = "0198f5a4-1357-7abc-8def-0123456789ab",
        taskRevision = 4,
        packagePointer = ModelPackagePointer(
            ModelPackageIdentity("reading_test_fixture", "1.0.0"),
            "a".repeat(64),
        ),
        roi = NormalizedRect(0.12f, 0.34f, 0.88f, 0.66f),
    )

    private fun frame(sequence: Long, roi: NormalizedRect): NormalizedRoiSourceFrame {
        val width = 24
        val height = 12
        return NormalizedRoiSourceFrame(
            roi,
            SourceFrame(
                sourceSequence = sequence,
                monotonicTimeMillis = sequence * 1_000,
                capturedAtEpochMillis = 1_700_000_000_000 + sequence * 1_000,
                width = width,
                height = height,
                rotationDegrees = 0,
                cropRect = PixelRect(0, 0, width, height),
                pixels = FramePixels.Rgb888(ByteArray(width * height * 3), width * 3),
            ),
        )
    }

    private fun processed(sequence: Long, observation: Observation) =
        RuntimeFrameResult.Processed(
            PipelineResult(
                sourceSequence = sequence,
                monotonicTimeMillis = sequence * 1_000,
                capturedAtEpochMillis = 1_700_000_000_000 + sequence * 1_000,
                observation = observation,
                timings = PipelineTimings(0, 0, 0, 0, 0),
            ),
        )

    private fun reading(sequence: Long, value: String, stable: Boolean): Observation.Reading {
        val parsed = checkNotNull(parseStructuredReading(value))
        return Observation.Reading(
            text = parsed.text,
            valueDecimal = parsed.valueDecimal,
            stable = stable,
            sourceSequence = sequence,
            confidence = 0.96f,
            unit = parsed.format.unit,
            format = parsed.format,
        )
    }

    private fun readingValue(value: String): ReadingPreviewValue {
        val parsed = checkNotNull(parseStructuredReading(value))
        return ReadingPreviewValue(
            text = parsed.text,
            valueDecimal = parsed.valueDecimal,
            confidence = 0.96f,
            format = parsed.format,
        )
    }

    private class FakeSession(
        override val packagePointer: ModelPackagePointer,
        private val processor: (SourceFrame) -> RuntimeFrameResult,
    ) : ReadingPreviewRuntimeSession {
        var closeCalls = 0
            private set
        var processCalls = 0
            private set
        var anchorResetCalls = 0
            private set

        override fun resetAutomaticAnchor() {
            anchorResetCalls++
        }

        override fun process(frame: SourceFrame): RuntimeFrameResult {
            processCalls++
            return processor(frame)
        }

        override fun close() {
            closeCalls++
        }
    }
}
