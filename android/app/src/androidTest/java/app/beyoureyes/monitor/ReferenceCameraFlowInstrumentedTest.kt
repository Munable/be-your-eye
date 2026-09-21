package app.beyoureyes.monitor

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.uniqueMonitorName
import app.beyoureyes.monitor.feature.home.HomeTags
import app.beyoureyes.monitor.feature.monitoring.ActiveMonitoringTags
import app.beyoureyes.monitor.feature.monitoring.MonitorDetailTags
import app.beyoureyes.monitor.feature.reference.ReferenceTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Product flow with real image decoding/prototype/runtime and an externally supplied camera scene. */
@RunWith(AndroidJUnit4::class)
class ReferenceCameraFlowInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun threeImagesCreatePrototypeStartDirectlyAndRecordOneEpisode() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runStaticReferenceCameraFlow") == "true")
        val repository = compose.activity.appContainer.monitors
        val resources = compose.activity.resources
        val beforeIds = repository.state.value.local.mapTo(mutableSetOf()) { it.monitor.id }
        val expectedAutomaticName = uniqueMonitorName(
            resources.getString(R.string.default_reference_monitor_name),
            repository.state.value.local.map { it.monitor.name },
        )
        val oneRecordedEvent = resources.getQuantityString(
            R.plurals.metric_record_count_accessibility,
            1,
            1,
        )
        val backHome = resources.getString(R.string.action_home_keep_monitoring)
        val modelNotReady = resources.getString(R.string.model_not_ready_retry)
        val historyTab = resources.getString(R.string.tab_history)
        val localTriggerImage = resources.getString(R.string.history_local_trigger_tap)
        val monitorsTab = resources.getString(R.string.tab_monitors)
        val beforeEventIds = repository.state.value.events.mapTo(mutableSetOf()) { it.id }
        val imageUris = REFERENCE_ASSETS.map(::insertReferenceAsset)

        Intents.init()
        try {
            val resultData = Intent().apply {
                clipData = ClipData.newUri(
                    compose.activity.contentResolver,
                    "reference-images",
                    imageUris.first(),
                ).also { data ->
                    imageUris.drop(1).forEach { uri -> data.addItem(ClipData.Item(uri)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intending(hasType("image/*")).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_OK, resultData),
            )

            compose.onNodeWithTag(HomeTags.REFERENCE).performClick()
            compose.onNodeWithTag(ReferenceTags.PICK).performClick()
            compose.waitUntil(timeoutMillis = IMAGE_IMPORT_TIMEOUT_MILLIS) {
                runCatching {
                    compose.onNodeWithTag(ReferenceTags.START).assertIsEnabled()
                }.isSuccess
            }
            assertFalse(
                MonitoringRuntimeState.status.value.phase in
                    setOf(MonitoringPhase.STARTING, MonitoringPhase.RUNNING),
            )
            // Complete image configuration starts directly; test recognition is optional.
            compose.onNodeWithTag(ReferenceTags.START).performClick()
            compose.waitUntil(timeoutMillis = MODEL_AND_CAMERA_TIMEOUT_MILLIS) {
                MonitoringRuntimeState.status.value.phase == MonitoringPhase.RUNNING
            }

            val created = withTimeout(MONITORING_TIMEOUT_MILLIS) {
                repository.state.first { snapshot ->
                    snapshot.local.any { it.monitor.id !in beforeIds } &&
                        snapshot.events.count { it.id !in beforeEventIds } == 1
                }
            }
            val monitor = created.local.single { it.monitor.id !in beforeIds }.monitor
            val appearedEvent = created.events.single { it.id !in beforeEventIds }
            val triggerSnapshotUri = requireNotNull(appearedEvent.localTriggerSnapshotUri)
            val triggerSnapshotFile = java.io.File(requireNotNull(Uri.parse(triggerSnapshotUri).path))
            assertEquals(MonitorKind.REFERENCE, monitor.kind)
            assertEquals(expectedAutomaticName, monitor.name)
            assertFalse(created.notificationsEnabled(monitor.id))
            assertTrue(triggerSnapshotFile.isFile)
            val decodedTriggerSnapshot = BitmapFactory.decodeFile(triggerSnapshotFile.absolutePath)
            assertNotNull(decodedTriggerSnapshot)
            decodedTriggerSnapshot?.recycle()
            compose.onNodeWithTag(HomeTags.ACTIVE).performClick()
            compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
                compose.onAllNodesWithContentDescription(oneRecordedEvent)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(oneRecordedEvent).assertExists()
            compose.onNodeWithTag(ActiveMonitoringTags.PREVIEW).assertExists()
            compose.onNodeWithText(backHome).performClick()
            compose.onNodeWithTag(HomeTags.REFERENCE).assertDoesNotExist()
            compose.onNodeWithTag(HomeTags.ACTIVE).performClick()
            compose.onNodeWithText(modelNotReady).assertDoesNotExist()
            compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
                compose.onAllNodesWithContentDescription(oneRecordedEvent)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(oneRecordedEvent).assertExists()
            compose.onNodeWithTag(ActiveMonitoringTags.PREVIEW).assertExists()

            delay(NO_DUPLICATE_WINDOW_MILLIS)
            assertEquals(1, repository.state.value.events.count { it.id !in beforeEventIds })
            compose.onNodeWithTag(ActiveMonitoringTags.STOP).performClick()
            compose.onNodeWithTag(ActiveMonitoringTags.STOP_CONFIRM).performClick()
            compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
                MonitoringRuntimeState.status.value.phase == MonitoringPhase.STOPPED
            }
            compose.onNodeWithText(historyTab).performClick()
            assertTrue(
                compose.onAllNodesWithTag("trigger_snapshot")
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                compose.onAllNodesWithText(localTriggerImage)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            compose.onNodeWithText(monitorsTab).performClick()
            compose.onNodeWithTag(HomeTags.monitor(monitor.id)).performClick()
            compose.onNodeWithTag(MonitorDetailTags.DELETE).performScrollTo().performClick()
            compose.onNodeWithTag(MonitorDetailTags.DELETE_CONFIRM).performClick()
            compose.waitUntil(timeoutMillis = MONITORING_TIMEOUT_MILLIS) {
                repository.state.value.local.none { it.monitor.id == monitor.id } &&
                    repository.state.value.events.none { it.id !in beforeEventIds } &&
                    !triggerSnapshotFile.exists()
            }
            assertFalse(triggerSnapshotFile.exists())
        } finally {
            Intents.release()
            imageUris.forEach { uri ->
                // Best-effort MediaStore cleanup: the app under test may already have removed or
                // replaced a source row during import, and scoped storage then reports the stale
                // Uri as foreign-owned. Cleanup must never mask the product-flow assertions.
                runCatching { compose.activity.contentResolver.delete(uri, null, null) }
            }
        }
    }

    private fun insertReferenceAsset(name: String): Uri {
        val resolver = compose.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${System.nanoTime()}-$name")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BeYourEyeFunctional")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        )
        resolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output)
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use {
                it.copyTo(output)
            }
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    private companion object {
        val REFERENCE_ASSETS = listOf(
            "reference-camera-target-1.png",
            "reference-camera-target-2.png",
            "reference-camera-target-3.png",
        )
        const val IMAGE_IMPORT_TIMEOUT_MILLIS = 30_000L
        const val MODEL_AND_CAMERA_TIMEOUT_MILLIS = 300_000L
        const val MONITORING_TIMEOUT_MILLIS = 30_000L
        const val NO_DUPLICATE_WINDOW_MILLIS = 15_000L
    }
}
