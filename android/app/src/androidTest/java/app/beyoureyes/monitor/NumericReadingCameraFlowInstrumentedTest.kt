package app.beyoureyes.monitor

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.parseStructuredReading
import app.beyoureyes.monitor.feature.home.HomeTags
import app.beyoureyes.monitor.feature.monitoring.ActiveMonitoringTags
import app.beyoureyes.monitor.feature.monitoring.CameraTags
import app.beyoureyes.monitor.feature.monitoring.MonitorDetailTags
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full product path against an externally supplied fixed camera scene.
 *
 * The host runner opts in explicitly. Ordinary instrumentation runs do not assume a particular
 * camera scene and skip this test instead of substituting a fixture behind CameraX.
 */
@RunWith(AndroidJUnit4::class)
class NumericReadingCameraFlowInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createConfirmThresholdAndRecordExactlyOneEventFromCameraX() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runStaticReadingCameraFlow") == "true")
        val expected = requireNotNull(arguments.getString("expectedReading"))
        val confirmedReading = arguments.getString("confirmedReading") ?: expected
        val modelAndCameraTimeoutMillis = arguments.getString("modelAndCameraTimeoutMillis")
            ?.toLongOrNull()
            ?.coerceIn(MIN_MODEL_AND_CAMERA_TIMEOUT_MILLIS, DEFAULT_MODEL_AND_CAMERA_TIMEOUT_MILLIS)
            ?: DEFAULT_MODEL_AND_CAMERA_TIMEOUT_MILLIS
        val expectedValue = requireNotNull(parseStructuredReading(expected))
        val confirmedValue = requireNotNull(parseStructuredReading(confirmedReading))
        val confirmedFormat = requireNotNull(
            ConfirmedReadingFormat.calibrate(expected, confirmedReading),
        )
        // A correction may replace only the saved baseline digits. An omitted decimal point may
        // calibrate display precision when the camera digit sequence and sign remain unchanged;
        // every live frame must still apply the confirmed structure before monitoring starts.
        val formattedExpected = requireNotNull(confirmedFormat.apply(expected))
        val thresholdDecimal = confirmedValue.decimalValue.subtract(BigDecimal.ONE)
            .stripTrailingZeros().toPlainString()
        val threshold = requireNotNull(confirmedFormat.displayThreshold(thresholdDecimal))
        require(expectedValue.decimalValue > BigDecimal(thresholdDecimal))

        val repository = compose.activity.appContainer.monitors
        val resources = compose.activity.resources
        val currentReading = resources.getString(R.string.reading_current_value, expected)
        val oneRecordedEvent = resources.getQuantityString(
            R.plurals.metric_record_count_accessibility,
            1,
            1,
        )
        val backHome = resources.getString(R.string.action_home_keep_monitoring)
        val modelNotReady = resources.getString(R.string.model_not_ready_retry)
        val beforeIds = repository.state.value.local.mapTo(mutableSetOf()) { it.monitor.id }
        val beforeEventIds = repository.state.value.events.mapTo(mutableSetOf()) { it.id }

        compose.onNodeWithTag(HomeTags.READING).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = modelAndCameraTimeoutMillis) {
            compose.onAllNodesWithTag(CameraTags.READING_TARGET_OVERLAY)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(CameraTags.READING_TARGET_OVERLAY).assertExists()
        compose.waitUntil(timeoutMillis = modelAndCameraTimeoutMillis) {
            compose.onAllNodesWithText(currentReading, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(currentReading, substring = true).assertExists()
        if (confirmedReading == expected) {
            compose.onNodeWithTag(CameraTags.READING_CONFIRM).performScrollTo().performClick()
        } else {
            compose.onNodeWithTag(CameraTags.READING_CORRECTION_TOGGLE)
                .performScrollTo().performClick()
            compose.onNodeWithTag(CameraTags.READING_CORRECTION).performTextClearance()
            compose.onNodeWithTag(CameraTags.READING_CORRECTION).performTextInput(confirmedReading)
            compose.onNodeWithTag(CameraTags.READING_CORRECTION).performImeAction()
            compose.onNodeWithTag(CameraTags.READING_CONFIRM).performScrollTo().performClick()
        }
        // Confirmation is serialized on the same analyzer executor that owns the live preview.
        // Wait for the confirmed UI instead of racing the next Compose lookup against that handoff.
        compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(CameraTags.THRESHOLD)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(CameraTags.THRESHOLD).performScrollTo().performTextClearance()
        compose.onNodeWithTag(CameraTags.THRESHOLD).performTextInput(threshold)
        compose.onNodeWithTag(CameraTags.THRESHOLD).performImeAction()
        compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
            runCatching {
                compose.onNodeWithTag(CameraTags.START).assertIsEnabled()
            }.isSuccess
        }
        compose.onNodeWithTag(CameraTags.START).performScrollTo().performClick()

        try {
            compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
                MonitoringRuntimeState.status.value.phase == MonitoringPhase.RUNNING
            }
        } catch (error: Throwable) {
            failWithRuntimeEvidence(error)
        }
        val created = withTimeout(MONITORING_TIMEOUT_MILLIS) {
            repository.state.first { snapshot ->
                snapshot.local.any { it.monitor.id !in beforeIds } &&
                    snapshot.events.count { it.id !in beforeEventIds } == 1
            }
        }
        val monitor = created.local.single { it.monitor.id !in beforeIds }
        val readingRule = monitor.monitor.rule as MonitorRule.ReadingThreshold.Single
        val target = monitor.monitor.target as MonitorTarget.NumericReading
        assertEquals(
            confirmedFormat.fractionalDigits,
            target.confirmedFormat?.fractionalDigits,
        )
        assertEquals(confirmedFormat, target.confirmedFormat)
        assertEquals(thresholdDecimal, readingRule.thresholdDecimal)
        assertFalse(created.notificationsEnabled(monitor.monitor.id))
        assertEquals(1, created.events.count { it.id !in beforeEventIds })
        val event = created.events.single { it.id !in beforeEventIds }
        val readingEvent = event.fact as MonitorEventFact.ReadingThresholdCrossed
        assertEquals(formattedExpected.valueDecimal, readingEvent.valueDecimal)
        assertEquals(formattedExpected.text, readingEvent.displayText)
        assertEquals(confirmedFormat, readingEvent.format)
        // Successful creation returns to the monitor list so the transient setup route is gone.
        // Open the active task before asserting the running-screen presentation.
        compose.onNodeWithTag(HomeTags.ACTIVE).performClick()
        compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
            compose.onAllNodesWithContentDescription(oneRecordedEvent)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(oneRecordedEvent).assertExists()

        // Running starts with the live viewfinder. Black-screen monitoring is an explicit option
        // that detaches only the preview surface; tapping it restores the same running camera.
        compose.onNodeWithTag(ActiveMonitoringTags.PREVIEW).assertExists()
        compose.onNodeWithTag(ActiveMonitoringTags.BLACK_TOGGLE).performClick()
        compose.onNodeWithTag(ActiveMonitoringTags.BLACK_SCREEN).assertExists()
        compose.onNodeWithTag(ActiveMonitoringTags.PREVIEW).assertDoesNotExist()
        compose.onNodeWithTag(ActiveMonitoringTags.BLACK_SCREEN).performClick()
        compose.onNodeWithTag(ActiveMonitoringTags.PREVIEW).assertExists()

        // Leaving the live screen keeps monitoring active. Reopening the active monitor must show
        // its live state and the default viewfinder instead of trying to reacquire the package.
        compose.onNodeWithText(backHome).performClick()
        compose.onNodeWithTag(HomeTags.READING).assertDoesNotExist()
        compose.onNodeWithTag(HomeTags.ACTIVE).performClick()
        compose.onNodeWithText(modelNotReady).assertDoesNotExist()
        compose.onNodeWithContentDescription(oneRecordedEvent).assertExists()
        compose.onNodeWithTag(ActiveMonitoringTags.PREVIEW).assertExists()

        // A constant scene is one episode. Continued positive frames must not create duplicates.
        delay(NO_DUPLICATE_WINDOW_MILLIS)
        assertEquals(1, repository.state.value.events.count { it.id !in beforeEventIds })

        compose.onNodeWithTag(ActiveMonitoringTags.STOP).performClick()
        compose.onNodeWithTag(ActiveMonitoringTags.STOP_CONFIRM).performClick()
        compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
            MonitoringRuntimeState.status.value.phase == MonitoringPhase.STOPPED
        }
        compose.onNodeWithTag(HomeTags.monitor(monitor.monitor.id)).performClick()
        compose.onNodeWithTag(MonitorDetailTags.DELETE).performScrollTo().performClick()
        compose.onNodeWithTag(MonitorDetailTags.DELETE_CONFIRM).performClick()
        compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
            repository.state.value.local.none { it.monitor.id == monitor.monitor.id } &&
                repository.state.value.events.none { it.id !in beforeEventIds }
        }
    }

    @Test
    fun nonNumericCameraSceneRemainsUnavailableAndCannotStartMonitoring() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runStaticReadingRejectionFlow") == "true")
        val modelAndCameraTimeoutMillis = arguments.getString("modelAndCameraTimeoutMillis")
            ?.toLongOrNull()
            ?.coerceIn(MIN_MODEL_AND_CAMERA_TIMEOUT_MILLIS, DEFAULT_MODEL_AND_CAMERA_TIMEOUT_MILLIS)
            ?: DEFAULT_MODEL_AND_CAMERA_TIMEOUT_MILLIS

        val repository = compose.activity.appContainer.monitors
        val resources = compose.activity.resources
        val readingUnavailable = resources.getString(R.string.reading_unavailable)
        val beforeIds = repository.state.value.local.mapTo(mutableSetOf()) { it.monitor.id }
        val beforeEventIds = repository.state.value.events.mapTo(mutableSetOf()) { it.id }

        compose.onNodeWithTag(HomeTags.READING).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = modelAndCameraTimeoutMillis) {
            compose.onAllNodesWithText(readingUnavailable, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(readingUnavailable, substring = true).assertExists()
        compose.onNodeWithTag(CameraTags.READING_CONFIRM).assertDoesNotExist()
        compose.onNodeWithTag(CameraTags.START).assertDoesNotExist()
        delay(REJECTION_OBSERVATION_WINDOW_MILLIS)
        assertEquals(0, repository.state.value.events.count { it.id !in beforeEventIds })
        assertFalse(MonitoringRuntimeState.status.value.phase == MonitoringPhase.RUNNING)

        compose.activity.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
            repository.state.value.local.none { it.monitor.id !in beforeIds }
        }
        assertEquals(0, repository.state.value.events.count { it.id !in beforeEventIds })
    }

    private fun failWithRuntimeEvidence(cause: Throwable): Nothing {
        val status = MonitoringRuntimeState.status.value
        val details = buildString {
            append("phase=").append(status.phase)
            append(" health=").append(status.health)
            append(" monitorId=").append(status.monitorId)
            append(" message=").append(status.message)
        }
        val cacheDir = compose.activity.cacheDir
        runCatching { File(cacheDir, FAILURE_PHASE_FILE).writeText(details) }
        runCatching {
            File(cacheDir, FAILURE_UI_FILE).writeText(
                compose.onRoot(useUnmergedTree = true).printToString(),
            )
        }
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(cacheDir, FAILURE_SCREEN_FILE).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }
        throw AssertionError("monitoring did not reach RUNNING: $details", cause)
    }

    private companion object {
        // The frozen reader package is 75 MiB. A throttled but progressing R2 download is valid and
        // resumable, so the real-network route must not turn five minutes of transport latency into
        // a false OCR failure. Runtime preparation still exposes retryable/rejected states itself.
        const val DEFAULT_MODEL_AND_CAMERA_TIMEOUT_MILLIS = 1_800_000L
        const val MIN_MODEL_AND_CAMERA_TIMEOUT_MILLIS = 30_000L
        const val MONITORING_TIMEOUT_MILLIS = 30_000L
        const val NO_DUPLICATE_WINDOW_MILLIS = 15_000L
        const val REJECTION_OBSERVATION_WINDOW_MILLIS = 3_000L
        const val FAILURE_PHASE_FILE = "functional-failure-phase.txt"
        const val FAILURE_UI_FILE = "functional-failure-ui.txt"
        const val FAILURE_SCREEN_FILE = "functional-failure-screen.png"
    }
}
