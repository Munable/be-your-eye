package app.beyoureyes.monitor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.monitor.feature.monitoring.DirectStartScreen
import app.beyoureyes.monitor.feature.monitoring.FieldSetupController
import app.beyoureyes.monitor.feature.monitoring.MonitorCameraState
import app.beyoureyes.monitor.feature.monitoring.modelPreparationMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression: first-run direct start must expose the decision it waits for. */
@RunWith(AndroidJUnit4::class)
class DirectStartDownloadInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun firstDownloadShowsSizeAndConfirmBeforeSavingOrStarting() {
        val request = ModelDownloadRequest("Recognition test model", "Find the selected target", 7_557_887, false)
        val progress = ModelPreparationProgress.AwaitingDownload(request)
        var saves = 0
        var starts = 0
        val controller = object : FieldSetupController {
            override val state = MutableStateFlow<MonitorCameraState>(
                MonitorCameraState.Loading(modelPreparationMessage(progress), progress),
            )
            override fun retry() = Unit
            override fun setNotificationsEnabled(enabled: Boolean) = Unit
            override suspend fun persist(): MonitorCameraState.Ready? { saves++; return null }
            override fun leave(onLeft: () -> Unit) = onLeft()
        }
        compose.setContent {
            BeYourEyeTheme {
                DirectStartScreen(
                    viewModel = controller,
                    cameraPermissionGranted = true,
                    cameraPermissionDenied = false,
                    onRequestCameraPermission = {},
                    onOpenAppSettings = {},
                    onStartMonitoring = { starts++; error("Cannot start before files are ready") },
                    onCheckProductAccess = { null },
                    onStarted = {}, onOpenAccount = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText("Recognition test model").assertExists()
        compose.onNodeWithText("7.6 MB", substring = true).assertExists()
        compose.onNodeWithTag("model_download_confirm").performScrollTo().performClick()
        runBlocking { withTimeout(1_000) { request.awaitConfirmation() } }
        assertEquals(0, saves)
        assertEquals(0, starts)
    }
}
