package app.beyoureyes.monitor

import android.view.OrientationEventListener
import android.view.Surface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCameraHandoffTest {
    @Test
    fun `setup camera rejects stale paused closed and handed off provider callbacks`() {
        assertTrue(
            shouldAcceptSetupCameraProviderCallback(
                callbackGeneration = 7,
                currentGeneration = 7,
                cameraWanted = true,
                closed = false,
                handedOff = false,
            ),
        )
        assertFalse(
            shouldAcceptSetupCameraProviderCallback(7, 8, true, false, false),
        )
        assertFalse(
            shouldAcceptSetupCameraProviderCallback(7, 7, false, false, false),
        )
        assertFalse(
            shouldAcceptSetupCameraProviderCallback(7, 7, true, true, false),
        )
        assertFalse(
            shouldAcceptSetupCameraProviderCallback(7, 7, true, false, true),
        )
    }

    @Test
    fun `physical device quadrants map to CameraX target rotations`() {
        assertEquals(Surface.ROTATION_0, surfaceRotationForPhysicalOrientation(0))
        assertEquals(Surface.ROTATION_0, surfaceRotationForPhysicalOrientation(359))
        assertEquals(Surface.ROTATION_270, surfaceRotationForPhysicalOrientation(90))
        assertEquals(Surface.ROTATION_180, surfaceRotationForPhysicalOrientation(180))
        assertEquals(Surface.ROTATION_90, surfaceRotationForPhysicalOrientation(270))
        assertEquals(null, surfaceRotationForPhysicalOrientation(OrientationEventListener.ORIENTATION_UNKNOWN))
    }

    @Test
    fun `analyzer barrier closes setup lease before foreground service continues`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val frameEntered = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        try {
            executor.execute {
                order += "frame_started"
                frameEntered.countDown()
                check(releaseFrame.await(5, TimeUnit.SECONDS))
                order += "frame_finished"
            }
            assertTrue(frameEntered.await(5, TimeUnit.SECONDS))

            val handoff = async(start = CoroutineStart.UNDISPATCHED) {
                awaitSetupAnalyzerRelease(executor) { order += "lease_closed" }
                order += "fgs_started"
            }
            assertFalse(handoff.isCompleted)

            releaseFrame.countDown()
            withTimeout(5_000) { handoff.await() }

            assertEquals(
                listOf("frame_started", "frame_finished", "lease_closed", "fgs_started"),
                order,
            )
        } finally {
            releaseFrame.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejected handoff invokes fresh preview creation requests`() {
        val requests = SetupPreviewRestartRequests()
        var fieldOpenCount = 0
        var readingOpenCount = 0
        val opened = mutableListOf<String>()
        requests.rememberFieldValidation { opened += "field-${++fieldOpenCount}" }
        requests.rememberReadingPreview { opened += "reading-${++readingOpenCount}" }

        requests.restartAfterRejectedHandoff()
        requests.restartAfterRejectedHandoff()

        assertEquals(
            listOf("field-1", "reading-1", "field-2", "reading-2"),
            opened,
        )
    }

    @Test
    fun `cleared preview request is not recreated after rejection`() {
        val requests = SetupPreviewRestartRequests()
        var readingOpenCount = 0
        requests.rememberReadingPreview { readingOpenCount++ }
        requests.clearReadingPreview()

        requests.restartAfterRejectedHandoff()

        assertEquals(0, readingOpenCount)
    }
}
