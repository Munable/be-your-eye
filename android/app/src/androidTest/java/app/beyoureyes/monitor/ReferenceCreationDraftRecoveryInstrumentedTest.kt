package app.beyoureyes.monitor

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.monitor.feature.reference.ReferenceCreationViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceCreationDraftRecoveryInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun assistantNameAndTwoSecondsPersistWithoutOverwritingAnEditedDraft() {
        val repository = compose.activity.appContainer.monitors
        val handle = SavedStateHandle()
        val rule = MonitorRule.TargetPresence(PresenceRuleKind.APPEARS, 2)
        val first = ReferenceCreationViewModel(
            repository, handle, initialName = "老板出现时提醒", initialRule = rule,
        )
        try {
            assertEquals("老板出现时提醒", first.state.value.name)
            assertEquals(rule, first.state.value.rule)
            first.setName("办公室门口")
            val restored = ReferenceCreationViewModel(
                repository, cloneHandle(handle), initialName = "旧提案名称",
            )
            assertEquals("办公室门口", restored.state.value.name)
            assertEquals(rule, restored.state.value.rule)
        } finally {
            repository.discardReferenceDraftSession(
                requireNotNull(handle.get<String>(ReferenceCreationViewModel.DRAFT_SESSION_KEY)),
            )
        }
    }

    @Test
    fun nameAndPrivateImagesSurviveSavedStateRoundTrip(): Unit = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments()
                .getString("runReferenceDraftRecovery") == "true",
        )
        val repository = compose.activity.appContainer.monitors
        val beforeMonitorIds = repository.state.value.local.mapTo(linkedSetOf()) { it.monitor.id }
        val imageUris = REFERENCE_ASSETS.map(::insertReferenceAsset)
        try {
            val firstHandle = SavedStateHandle()
            val first = ReferenceCreationViewModel(repository, firstHandle)
            val chosenRule = MonitorRule.TargetPresence(PresenceRuleKind.APPEARS, 3)
            first.setPresenceRule(chosenRule)
            first.setNotificationsEnabled(true)
            // Simulates the Activity's already-saved Bundle before background import completes.
            val stoppedHandle = cloneHandle(firstHandle)
            first.setName("恢复后的参考监控")
            first.addImages(imageUris)
            withTimeout(IMPORT_TIMEOUT_MILLIS) {
                first.state.first { !it.importing && it.materials.size == 3 }
            }

            val restored = ReferenceCreationViewModel(repository, stoppedHandle)
            assertEquals("恢复后的参考监控", restored.state.value.name)
            assertEquals(3, restored.state.value.materials.size)
            assertEquals(chosenRule, restored.state.value.rule)
            assertTrue(restored.state.value.notificationsEnabled)
            assertTrue(restored.state.value.sufficiency.canContinue)
            assertEquals(chosenRule, restored.draft()?.rule)
            assertTrue(restored.draft()?.notificationsEnabled == true)

            restored.createAndOpen()
            assertEquals(
                beforeMonitorIds,
                repository.state.value.local.mapTo(linkedSetOf()) { it.monitor.id },
            )
            assertTrue(restored.cameraRequested.value)
            clearViewModel(restored)
            val afterCancellation = ReferenceCreationViewModel(repository, stoppedHandle)
            assertEquals("恢复后的参考监控", afterCancellation.state.value.name)
            assertEquals(3, afterCancellation.state.value.materials.size)

            afterCancellation.state.value.materials.toList().forEach(afterCancellation::remove)
            assertTrue(afterCancellation.state.value.materials.isEmpty())
            afterCancellation.addImages(imageUris)
            withTimeout(IMPORT_TIMEOUT_MILLIS) {
                afterCancellation.state.first { !it.importing && it.materials.size == 3 }
            }
        } finally {
            imageUris.forEach { compose.activity.contentResolver.delete(it, null, null) }
            repository.state.value.local.filter { it.monitor.id !in beforeMonitorIds }.forEach {
                repository.delete(it.monitor.id)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun cloneHandle(source: SavedStateHandle): SavedStateHandle = SavedStateHandle.createHandle(
        source.savedStateProvider().saveState(),
        null,
    )

    private fun clearViewModel(viewModel: ViewModel) {
        val clear = ViewModel::class.java.declaredMethods.single {
            it.name.startsWith("clear$") && it.parameterCount == 0
        }
        clear.isAccessible = true
        clear.invoke(viewModel)
    }

    private fun insertReferenceAsset(name: String): Uri {
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "${System.nanoTime()}-$name")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BeYourEyeDraftRecovery")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
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
        const val IMPORT_TIMEOUT_MILLIS = 30_000L
    }
}
