package app.beyoureyes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTemporalEvidenceTrackerTest {
    @Test
    fun `two observations spanning 120 milliseconds confirm a dynamic stream`() {
        val tracker = ReadingTemporalEvidenceTracker()

        val first = tracker.accept(reading(1, "00:02:22", "142"), 1_000, 220) as Observation.Reading
        val second = tracker.accept(reading(2, "00:02:23", "143"), 1_120, 230) as Observation.Reading

        assertFalse(first.stable)
        assertTrue(second.stable)
        assertEquals("143", second.valueDecimal)
        assertEquals(3_680L, tracker.maximumEvidenceGapMillis)
    }

    @Test
    fun `a new anchor starts evidence on the same frame without switching targets`() {
        val tracker = ReadingTemporalEvidenceTracker()
        tracker.accept(reading(1, "7", "7", trackId = 1), 0, 400)

        val changed = tracker.accept(reading(2, "8", "8", trackId = 2), 150, 400) as Observation.Reading
        val confirmed = tracker.accept(reading(3, "9", "9", trackId = 2), 300, 400) as Observation.Reading

        assertFalse(changed.stable)
        assertTrue(confirmed.stable)
        assertEquals(5_000L, tracker.maximumEvidenceGapMillis)
    }

    @Test
    fun `workload adapted sampling interval keeps one reading evidence stream`() {
        val tracker = ReadingTemporalEvidenceTracker()

        val first = tracker.accept(reading(1, "34", "34"), 1_000, 200) as Observation.Reading
        val second = tracker.accept(reading(2, "34", "34"), 4_000, 200) as Observation.Reading

        assertFalse(first.stable)
        assertTrue(second.stable)
        assertEquals(3_200L, tracker.maximumEvidenceGapMillis)
    }

    @Test
    fun `signed three second cadence retains direct reader evidence with scheduling margin`() {
        val tracker = ReadingTemporalEvidenceTracker()

        val first = tracker.accept(reading(1, "34", "34"), 1_000, 260) as Observation.Reading
        val second = tracker.accept(reading(2, "34", "34"), 4_000, 260) as Observation.Reading

        assertFalse(first.stable)
        assertTrue(second.stable)
        assertEquals(4_160L, tracker.maximumEvidenceGapMillis)
    }

    @Test
    fun `short unavailable pauses evidence and retains the last reading`() {
        val tracker = ReadingTemporalEvidenceTracker()
        tracker.accept(reading(1, "42", "42"), 0, 250)

        val miss = tracker.accept(unavailable(2), 500, 250) as Observation.Unavailable
        val resumed = tracker.accept(reading(3, "43", "43"), 700, 250) as Observation.Reading

        assertTrue(miss.evidencePaused)
        assertEquals("42", miss.retainedReading?.valueDecimal)
        assertEquals(500L, miss.retainedReadingAgeMillis)
        assertTrue(resumed.stable)
    }

    @Test
    fun `expired unavailable clears evidence before the next reading`() {
        val tracker = ReadingTemporalEvidenceTracker()
        tracker.accept(reading(1, "42", "42"), 0, 200)

        val miss = tracker.accept(unavailable(2), 3_201, 200) as Observation.Unavailable
        val resumed = tracker.accept(reading(3, "43", "43"), 3_300, 200) as Observation.Reading

        assertFalse(miss.evidencePaused)
        assertFalse(resumed.stable)
    }

    @Test
    fun `decimal punctuation changes do not discard same digit evidence`() {
        val tracker = ReadingTemporalEvidenceTracker()
        tracker.accept(reading(1, "515", "515"), 0, 200)

        val stable = tracker.accept(
            reading(
                sequence = 2,
                text = "5.15",
                value = "5.15",
                fractionalDigits = 2,
            ),
            120,
            200,
        ) as Observation.Reading

        assertTrue(stable.stable)
    }

    @Test
    fun `format kind changes still reset evidence`() {
        val tracker = ReadingTemporalEvidenceTracker()
        tracker.accept(reading(1, "515", "515"), 0, 200)

        val changed = tracker.accept(
            Observation.Reading(
                text = "00:08:35",
                valueDecimal = "515",
                stable = false,
                sourceSequence = 2,
                confidence = 0.95f,
                format = ConfirmedReadingFormat(ReadingFormatKind.TIME, timeSegments = 3),
            ),
            120,
            200,
        ) as Observation.Reading

        assertFalse(changed.stable)
    }

    private fun reading(
        sequence: Long,
        text: String,
        value: String,
        trackId: Long = 1,
        fractionalDigits: Int = 0,
    ) =
        Observation.Reading(
            text = text,
            valueDecimal = value,
            stable = false,
            sourceSequence = sequence,
            confidence = 0.95f,
            format = if (':' in text) {
                ConfirmedReadingFormat(ReadingFormatKind.TIME, timeSegments = 3)
            } else {
                ConfirmedReadingFormat(
                    ReadingFormatKind.DECIMAL,
                    fractionalDigits = fractionalDigits,
                )
            },
            targetTrackId = trackId,
        )

    private fun unavailable(sequence: Long) = Observation.Unavailable(
        reason = UnavailableReason.LOW_QUALITY,
        diagnosticCode = "test",
        sourceSequence = sequence,
    )
}
