package app.beyoureyes.monitor.feature.monitoring

import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.ReadingFormatKind
import app.beyoureyes.monitor.feature.reading.ReadingConditionMode
import app.beyoureyes.monitor.feature.reading.ReadingConditionValidation
import app.beyoureyes.monitor.feature.reading.validateReadingCondition
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.pluralText
import app.beyoureyes.monitor.design.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MonitorDetailContractTest {
    @Test
    fun `monitor detail names all three direct capability types`() {
        assertEquals(uiText(R.string.route_reference_images), monitorKindDisplayName(MonitorKind.REFERENCE))
        assertEquals(uiText(R.string.route_numeric_reading), monitorKindDisplayName(MonitorKind.READING))
        assertEquals(uiText(R.string.object_creation_title), monitorKindDisplayName(MonitorKind.OBJECT_DETECTION))
    }

    @Test
    fun `time rule summary never exposes canonical seconds`() {
        val format = ConfirmedReadingFormat(
            kind = ReadingFormatKind.TIME,
            timeSegments = 3,
        )

        assertEquals(
            uiText(
                R.string.reading_rule_gt_summary,
                "02:09:14",
                pluralText(R.plurals.duration_seconds, 1, 1),
            ),
            readingRuleSummary(
                MonitorRule.ReadingThreshold.Single(
                    comparison = ReadingComparison.GT,
                    thresholdDecimal = "7754",
                    configured = true,
                ),
                format,
            ),
        )
    }

    @Test
    fun `above and below accept negative decimals and normalize input`() {
        assertEquals(
            MonitorRule.ReadingThreshold.Single(
                comparison = ReadingComparison.GT,
                thresholdDecimal = "-12.5",
                configured = true,
            ),
            validRule(ReadingConditionMode.ABOVE, " -12.500 "),
        )
        assertEquals(
            MonitorRule.ReadingThreshold.Single(
                comparison = ReadingComparison.LT,
                thresholdDecimal = "0.25",
                configured = true,
            ),
            validRule(ReadingConditionMode.BELOW, ".250"),
        )
    }

    @Test
    fun `editing a reading threshold preserves the selected duration`() {
        assertEquals(
            MonitorRule.ReadingThreshold.Single(
                comparison = ReadingComparison.GT,
                thresholdDecimal = "12.5",
                configured = true,
                durationSeconds = 30,
            ),
            validRule(
                mode = ReadingConditionMode.ABOVE,
                threshold = "12.5",
                durationSeconds = 30,
            ),
        )
    }

    @Test
    fun `outside produces one range rule only when lower is below upper`() {
        assertEquals(
            MonitorRule.ReadingThreshold.Outside(
                lowerThresholdDecimal = "-2.5",
                upperThresholdDecimal = "7.25",
                configured = true,
            ),
            validRule(
                mode = ReadingConditionMode.OUTSIDE,
                lower = "-2.50",
                upper = "7.250",
            ),
        )

        assertTrue(
            validateReadingCondition(
                ReadingConditionMode.OUTSIDE,
                thresholdInput = "",
                lowerInput = "7",
                upperInput = "7",
                confirmedFormat = profile,
            ) is ReadingConditionValidation.Invalid,
        )
        assertTrue(
            validateReadingCondition(
                ReadingConditionMode.OUTSIDE,
                thresholdInput = "",
                lowerInput = "8",
                upperInput = "7",
                confirmedFormat = profile,
            ) is ReadingConditionValidation.Invalid,
        )
    }

    @Test
    fun `invalid decimal is rejected instead of silently changing the condition`() {
        listOf("", "--1", "1,2", "NaN", "Infinity").forEach { input ->
            assertTrue(
                input,
                validateReadingCondition(
                    ReadingConditionMode.ABOVE,
                    thresholdInput = input,
                    lowerInput = "",
                    upperInput = "",
                    confirmedFormat = profile,
                ) is ReadingConditionValidation.Invalid,
            )
        }
    }

    @Test
    fun `comma decimal input follows the active locale`() {
        val result = validateReadingCondition(
            mode = ReadingConditionMode.ABOVE,
            thresholdInput = "1,25",
            lowerInput = "",
            upperInput = "",
            confirmedFormat = profile,
            locale = Locale.GERMANY,
        )

        assertEquals(
            "1.25",
            (result as ReadingConditionValidation.Valid).rule.let {
                (it as MonitorRule.ReadingThreshold.Single).thresholdDecimal
            },
        )
    }

    @Test
    fun `reference editor saves only a changed distinct set of three to twenty images`() {
        val original = (0 until 3).map(::material)
        assertFalse(
            ReferenceEditorState(4, original, original).canSave,
        )
        assertTrue(
            ReferenceEditorState(4, original, original + material(3)).canSave,
        )
        assertFalse(
            ReferenceEditorState(4, original, original.take(2)).canSave,
        )
        assertFalse(
            ReferenceEditorState(
                expectedRevision = 4,
                original = original,
                materials = original + material(3),
                importing = true,
            ).canSave,
        )
    }

    @Test
    fun `delete is available only while stopped and outside reference editing`() {
        assertTrue(
            monitorDetailDeleteAllowed(
                thisMonitorActive = false,
                referenceEditorOpen = false,
            ),
        )
        assertFalse(
            monitorDetailDeleteAllowed(
                thisMonitorActive = false,
                referenceEditorOpen = true,
            ),
        )
        assertFalse(
            monitorDetailDeleteAllowed(
                thisMonitorActive = true,
                referenceEditorOpen = false,
            ),
        )
    }

    @Test
    fun `camera waits only while disclosure is loading and can repair an unbound monitor`() {
        assertFalse(
            monitorDetailCameraAllowed(
                anotherMonitorActive = false,
                referenceEditorOpen = false,
                modelDisclosure = ModelDisclosureState.Loading,
            ),
        )
        assertTrue(
            monitorDetailCameraAllowed(
                anotherMonitorActive = false,
                referenceEditorOpen = false,
                modelDisclosure = ModelDisclosureState.Unavailable,
            ),
        )
        assertTrue(
            monitorDetailCameraAllowed(
                anotherMonitorActive = false,
                referenceEditorOpen = false,
                modelDisclosure = ModelDisclosureState.Ready("PP-OCRv6 medium rec onnx"),
            ),
        )
        assertFalse(
            monitorDetailCameraAllowed(
                anotherMonitorActive = true,
                referenceEditorOpen = false,
                modelDisclosure = ModelDisclosureState.Ready("PP-OCRv6 medium rec onnx"),
            ),
        )
        assertFalse(
            monitorDetailCameraAllowed(
                anotherMonitorActive = false,
                referenceEditorOpen = true,
                modelDisclosure = ModelDisclosureState.Ready("PP-OCRv6 medium rec onnx"),
            ),
        )
    }

    @Test
    fun `active monitor never claims its recognition model is waiting to be prepared`() {
        assertEquals(
            uiText(R.string.model_disclosure_running),
            monitorModelDisclosureSummary(
                active = true,
                modelDisclosure = ModelDisclosureState.Unavailable,
            ),
        )
        assertEquals(
            uiText(R.string.model_disclosure_running),
            monitorModelDisclosureSummary(
                active = true,
                modelDisclosure = ModelDisclosureState.Loading,
            ),
        )
        assertEquals(
            uiText(R.string.model_disclosure_named, "PP-OCRv6 Medium"),
            monitorModelDisclosureSummary(
                active = true,
                modelDisclosure = ModelDisclosureState.Ready("PP-OCRv6 Medium"),
            ),
        )
    }

    @Test
    fun `unbound and resolver failure both settle as unavailable instead of loading forever`() =
        runBlocking {
            assertEquals(
                ModelDisclosureState.Unavailable,
                resolveModelDisclosureState { null },
            )
            assertEquals(
                ModelDisclosureState.Unavailable,
                resolveModelDisclosureState { error("resolver failed") },
            )
        }

    @Test
    fun `resolver cancellation propagates`() {
        var propagated = false
        try {
            runBlocking {
                resolveModelDisclosureState { throw CancellationException("view model cleared") }
            }
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    private fun validRule(
        mode: ReadingConditionMode,
        threshold: String = "",
        lower: String = "",
        upper: String = "",
        durationSeconds: Int = 1,
    ): MonitorRule.ReadingThreshold = (
        validateReadingCondition(
            mode,
            threshold,
            lower,
            upper,
            profile,
            durationSeconds,
        ) as ReadingConditionValidation.Valid
        ).rule

    private val profile = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL)

    private fun material(index: Int) = ReferenceMaterial(
        sourceUri = "file:///reference-$index.img",
        exactSha256 = index.toString(16).padStart(64, '0'),
        differenceHash = index.toLong().shl(16),
        meanRed = index,
        meanGreen = index,
        meanBlue = index,
        width = 100,
        height = 100,
    )
}
