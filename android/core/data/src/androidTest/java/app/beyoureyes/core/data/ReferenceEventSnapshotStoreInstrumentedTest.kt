package app.beyoureyes.core.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.SourceFrame
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceEventSnapshotStoreInstrumentedTest {
    @Test
    fun analyzedRgbFrameBecomesOnePrivateJpegAndSupportsEventAndMonitorDeletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(context.cacheDir, "event-snapshot-${System.nanoTime()}")
        val store = ReferenceEventSnapshotStore.openAppPrivate(isolatedFiles)
        val frame = SourceFrame(
            sourceSequence = 9,
            monotonicTimeMillis = 10,
            capturedAtEpochMillis = 11,
            width = 2,
            height = 2,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, 2, 2),
            pixels = FramePixels.Rgb888(
                bytes = byteArrayOf(
                    -1, 0, 0, 0, -1, 0,
                    0, 0, -1, -1, -1, -1,
                ),
                rowStride = 6,
            ),
        )

        val uri = store.capture(TASK_ID, EVENT_ID, frame)
        val file = File(requireNotNull(Uri.parse(uri).path))
        val decoded = BitmapFactory.decodeFile(file.absolutePath)

        assertTrue(file.isFile)
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(uri, store.uriFor(TASK_ID, EVENT_ID))

        decoded.recycle()
        store.deleteEvent(TASK_ID, EVENT_ID)
        assertFalse(file.exists())
        assertNull(store.uriFor(TASK_ID, EVENT_ID))

        assertEquals(uri, store.capture(TASK_ID, EVENT_ID, frame))
        store.deleteTask(TASK_ID)
        assertFalse(file.exists())
        isolatedFiles.deleteRecursively()
    }

    @Test
    fun remoteDerivativeIsAReadableJpegWithinSingleBroadcastBudget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(context.cacheDir, "event-preview-${System.nanoTime()}")
        val store = ReferenceEventSnapshotStore.openAppPrivate(isolatedFiles)
        val width = 1_000
        val height = 800
        val pixels = ByteArray(width * height * 3) { index ->
            ((index * 73 + index / 11) and 0xff).toByte()
        }
        store.capture(
            TASK_ID,
            EVENT_ID,
            SourceFrame(
                sourceSequence = 1,
                monotonicTimeMillis = 1,
                capturedAtEpochMillis = 1,
                width = width,
                height = height,
                rotationDegrees = 0,
                cropRect = PixelRect(0, 0, width, height),
                pixels = FramePixels.Rgb888(pixels, width * 3),
            ),
        )

        val preview = requireNotNull(store.remotePreviewBytes(TASK_ID, EVENT_ID))
        val decoded = BitmapFactory.decodeByteArray(preview, 0, preview.size)

        assertTrue(preview.size <= 120 * 1_024)
        assertTrue(maxOf(decoded.width, decoded.height) <= 720)
        decoded.recycle()
        isolatedFiles.deleteRecursively()
    }

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        const val EVENT_ID = "01900000-0000-7000-8000-000000000001"
    }
}
