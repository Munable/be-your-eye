package app.beyoureyes.monitor.feature.reading

import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.ReadingFormatKind
import app.beyoureyes.monitor.ReadingPreviewBaselineDraft
import app.beyoureyes.monitor.ReadingPreviewIdentity
import app.beyoureyes.monitor.ReadingPreviewLiveState
import app.beyoureyes.monitor.ReadingPreviewStatus
import app.beyoureyes.monitor.ReadingPreviewValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingConditionControlsTest {
    @Test
    fun `confirmation is available only for a current stable value`() {
        val value = value("333")
        val waiting = ReadingPreviewStatus.AwaitingConfirmation(
            expectedIdentity = identity(),
            processedFrames = 3,
            unavailableFrames = 0,
            live = ReadingPreviewLiveState.Stable(value),
            baselineDraft = ReadingPreviewBaselineDraft(value),
        )

        assertTrue(readingConfirmationEnabled(waiting))
        assertFalse(
            readingConfirmationEnabled(
                ReadingPreviewStatus.Scanning(
                    identity(),
                    processedFrames = 4,
                    unavailableFrames = 1,
                    live = ReadingPreviewLiveState.Unavailable(
                        UnavailableReason.LOW_QUALITY,
                        "blurred_reading",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `any current stable value enables start without losing the confirmed baseline`() {
        val baseline = value("333")
        val unavailable = confirmed(
            baseline,
            ReadingPreviewLiveState.Unavailable(UnavailableReason.NO_FRAME, "no_frame"),
        )

        assertFalse(readingConditionStartEnabled(unavailable, saving = false))
        assertFalse(readingConditionStartEnabled(confirmed(baseline), saving = true))
        assertTrue(readingConditionStartEnabled(confirmed(baseline), saving = false))
        assertTrue(
            readingConditionStartEnabled(
                confirmed(baseline, ReadingPreviewLiveState.Stable(value("334"))),
                saving = false,
            ),
        )
        assertFalse(
            readingConditionStartEnabled(
                confirmed(baseline, ReadingPreviewLiveState.Candidate(value("334"))),
                saving = false,
            ),
        )
    }

    @Test
    fun `reading condition inputs create canonical above below and outside rules`() {
        assertEquals(
            MonitorRule.ReadingThreshold.Single(ReadingComparison.GT, "333", true),
            readingRuleFromInputs(ReadingConditionMode.ABOVE, "333.0", "", "", profile),
        )
        assertEquals(
            MonitorRule.ReadingThreshold.Single(ReadingComparison.LT, "-12.5", true),
            readingRuleFromInputs(ReadingConditionMode.BELOW, "-12.50", "", "", profile),
        )
        assertEquals(
            MonitorRule.ReadingThreshold.Outside("10", "20", true),
            readingRuleFromInputs(ReadingConditionMode.OUTSIDE, "", "10.0", "20.00", profile),
        )
        assertNull(readingRuleFromInputs(ReadingConditionMode.OUTSIDE, "", "20", "10", profile))
        assertNull(readingRuleFromInputs(ReadingConditionMode.ABOVE, "not-a-number", "", "", profile))
    }

    @Test
    fun `invalid condition disables start even with a stable live reading`() {
        assertFalse(
            readingConditionStartEnabled(
                confirmed(value("333")),
                saving = false,
                conditionValid = false,
            ),
        )
    }

    @Test
    fun `stopped configured monitor restores condition and ignores a new confirmed value`() {
        val persisted = MonitorRule.ReadingThreshold.Single(
            ReadingComparison.GTE,
            "100",
            configured = true,
            durationSeconds = 10,
        )

        val restored = readingConditionDraft(persisted)
        val afterConfirmation = readingConditionDraft(persisted, confirmedBaseline = "50")
        val submission = readingConditionSubmission(persisted, afterConfirmation, profile)

        assertEquals(ReadingConditionMode.ABOVE, restored.mode)
        assertEquals("100", restored.threshold)
        assertEquals(10, restored.durationSeconds)
        assertEquals(restored, afterConfirmation)
        assertEquals(persisted, submission?.rule)
        assertFalse(submission?.requiresPersistence ?: true)
    }

    @Test
    fun `new reading monitor uses confirmed value as the condition default`() {
        val unconfigured = MonitorRule.ReadingThreshold.Single(
            ReadingComparison.GT,
            "0",
            configured = false,
        )

        val beforeConfirmation = readingConditionDraft(unconfigured)
        val afterConfirmation = readingConditionDraft(unconfigured, confirmedBaseline = "333")
        val submission = readingConditionSubmission(unconfigured, afterConfirmation, profile)

        assertEquals("", beforeConfirmation.threshold)
        assertEquals(ReadingConditionMode.ABOVE, afterConfirmation.mode)
        assertEquals("333", afterConfirmation.threshold)
        assertEquals(
            MonitorRule.ReadingThreshold.Single(ReadingComparison.GT, "333", true),
            submission?.rule,
        )
        assertTrue(submission?.requiresPersistence == true)
    }

    @Test
    fun `saved timer thresholds reopen in the confirmed display format`() {
        val timer = ConfirmedReadingFormat(ReadingFormatKind.TIME, timeSegments = 3)
        val persisted = MonitorRule.ReadingThreshold.Single(
            ReadingComparison.GT,
            "142",
            configured = true,
        )

        val draft = readingConditionDraft(persisted, confirmedFormat = timer)

        assertEquals("00:02:22", draft.threshold)
        assertEquals(persisted, readingConditionSubmission(persisted, draft, timer)?.rule)
    }

    @Test
    fun `prefilled duration is persisted with edited reading inputs`() {
        val unconfigured = MonitorRule.ReadingThreshold.Single(
            thresholdDecimal = "0",
            configured = false,
        )
        val submission = readingConditionSubmission(
            persistedRule = unconfigured,
            draft = ReadingConditionDraft(
                mode = ReadingConditionMode.OUTSIDE,
                lowerThreshold = "10",
                upperThreshold = "20",
                durationSeconds = 30,
            ),
            confirmedReadingFormat = profile,
        )

        assertEquals(
            MonitorRule.ReadingThreshold.Outside(
                lowerThresholdDecimal = "10",
                upperThresholdDecimal = "20",
                configured = true,
                durationSeconds = 30,
            ),
            submission?.rule,
        )
        assertTrue(submission?.requiresPersistence == true)
    }

    private fun confirmed(
        baseline: ReadingPreviewValue,
        live: ReadingPreviewLiveState = ReadingPreviewLiveState.Stable(baseline),
    ) = ReadingPreviewStatus.Confirmed(
        expectedIdentity = identity(),
        value = baseline,
        confirmedFormat = profile,
        processedFrames = 4,
        unavailableFrames = if (live is ReadingPreviewLiveState.Unavailable) 1 else 0,
        live = live,
    )

    private fun value(value: String) = ReadingPreviewValue(value, value, 0.96f, profile)

    private val profile = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL)

    private fun identity() = ReadingPreviewIdentity(
        taskId = "0198f5a4-1357-7abc-8def-0123456789ab",
        taskRevision = 4,
        packagePointer = ModelPackagePointer(
            ModelPackageIdentity("reading_test_fixture", "1.0.0"),
            "a".repeat(64),
        ),
        roi = NormalizedRect(0.12f, 0.34f, 0.88f, 0.66f),
    )
}
