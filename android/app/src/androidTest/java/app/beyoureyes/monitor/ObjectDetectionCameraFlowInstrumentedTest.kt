package app.beyoureyes.monitor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.monitor.feature.home.HomeTags
import app.beyoureyes.monitor.feature.monitoring.ActiveMonitoringTags
import app.beyoureyes.monitor.feature.monitoring.DirectStartTags
import app.beyoureyes.monitor.feature.monitoring.MonitorDetailTags
import app.beyoureyes.monitor.feature.objectdetection.ObjectCreationTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One bounded current-package CameraX route against an externally displayed apple scene. */
@RunWith(AndroidJUnit4::class)
class ObjectDetectionCameraFlowInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appleSelectionUsesLite2AndRecordsOnePresenceEpisode() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runStaticObjectCameraFlow") == "true")
        val modelAndCameraTimeoutMillis = arguments.getString("modelAndCameraTimeoutMillis")
            ?.toLongOrNull()
            ?.coerceIn(MIN_MODEL_AND_CAMERA_TIMEOUT_MILLIS, DEFAULT_MODEL_AND_CAMERA_TIMEOUT_MILLIS)
            ?: DEFAULT_MODEL_AND_CAMERA_TIMEOUT_MILLIS
        val repository = compose.activity.appContainer.monitors
        val beforeIds = repository.state.value.local.mapTo(mutableSetOf()) { it.monitor.id }
        val beforeEventIds = repository.state.value.events.mapTo(mutableSetOf()) { it.id }

        compose.onNodeWithTag(HomeTags.OBJECT).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = CATALOG_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(ObjectCreationTags.INPUT).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ObjectCreationTags.INPUT).performClick().performTextInput("a")
        // Removing the empty-state artwork must retain the input identity and IME focus.
        compose.onNodeWithTag(ObjectCreationTags.INPUT).assertIsFocused().performTextInput("pple")
        val suggestionTag = "${ObjectCreationTags.SUGGESTION}_apple"
        compose.waitUntil(timeoutMillis = CATALOG_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(suggestionTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(suggestionTag).assertIsDisplayed().performClick()
        compose.onNodeWithTag(ObjectCreationTags.LIST)
            .performScrollToNode(hasTestTag(ObjectCreationTags.CONTINUE))
        compose.onNodeWithTag(ObjectCreationTags.CONTINUE).performClick()
        // 保存并开始监控 goes through the no-camera direct-start screen. The fixture grants the
        // camera permission before instrumentation, so the screen starts monitoring on its own;
        // the permission prompt is only a fallback for a revoked grant.
        compose.waitUntil(timeoutMillis = CATALOG_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(DirectStartTags.SCREEN).fetchSemanticsNodes().isNotEmpty() ||
                MonitoringRuntimeState.status.value.phase in
                    setOf(MonitoringPhase.STARTING, MonitoringPhase.RUNNING)
        }
        if (compose.onAllNodesWithTag(DirectStartTags.PERMISSION).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(DirectStartTags.PERMISSION).performClick()
        }
        compose.waitUntil(timeoutMillis = modelAndCameraTimeoutMillis) {
            MonitoringRuntimeState.status.value.phase == MonitoringPhase.RUNNING
        }

        val snapshot = withTimeout(MONITORING_TIMEOUT_MILLIS) {
            repository.state.first { state ->
                state.local.any { it.monitor.id !in beforeIds } &&
                    state.events.count { it.id !in beforeEventIds } == 1
            }
        }
        val created = snapshot.local.single { it.monitor.id !in beforeIds }
        assertEquals(MonitorKind.OBJECT_DETECTION, created.monitor.kind)
        assertEquals("apple", (created.monitor.target as MonitorTarget.ObjectClass).targetId)
        assertEquals(LITE2_PACKAGE_ID, created.runtimePackagePointer?.identity?.packageId)
        assertEquals(1, snapshot.events.count { it.id !in beforeEventIds })

        compose.onNodeWithTag(HomeTags.ACTIVE).performClick()
        delay(NO_DUPLICATE_WINDOW_MILLIS)
        assertEquals(1, repository.state.value.events.count { it.id !in beforeEventIds })
        compose.onNodeWithTag(ActiveMonitoringTags.STOP).performClick()
        compose.onNodeWithTag(ActiveMonitoringTags.STOP_CONFIRM).performClick()
        compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
            MonitoringRuntimeState.status.value.phase == MonitoringPhase.STOPPED
        }
        compose.onNodeWithTag(HomeTags.monitor(created.monitor.id)).performClick()
        compose.onNodeWithTag(MonitorDetailTags.DELETE).performScrollTo().performClick()
        compose.onNodeWithTag(MonitorDetailTags.DELETE_CONFIRM).performClick()
        compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
            repository.state.value.local.none { it.monitor.id == created.monitor.id } &&
                repository.state.value.events.none { it.id !in beforeEventIds }
        }
    }

    private companion object {
        const val LITE2_PACKAGE_ID = "efficientdet_lite2_object_v1"
        const val CATALOG_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_MODEL_AND_CAMERA_TIMEOUT_MILLIS = 1_800_000L
        const val MIN_MODEL_AND_CAMERA_TIMEOUT_MILLIS = 30_000L
        const val MONITORING_TIMEOUT_MILLIS = 30_000L
        const val NO_DUPLICATE_WINDOW_MILLIS = 3_000L
    }
}
