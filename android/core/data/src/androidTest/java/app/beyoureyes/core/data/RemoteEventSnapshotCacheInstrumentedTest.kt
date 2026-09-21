package app.beyoureyes.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteEventSnapshotCacheInstrumentedTest {
    @Test
    fun decryptedPreviewIsPrivateAccountScopedAndPrunedWithItsEvent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(context.cacheDir, "remote-cache-${System.nanoTime()}")
        val cache = RemoteEventSnapshotCache.openAppPrivate(isolatedFiles)
        val jpeg = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888)
            try {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output))
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }

        val uri = cache.store(ACCOUNT_ID, SOURCE_DEVICE_ID, EVENT_ID, jpeg)
        val file = File(requireNotNull(Uri.parse(uri).path))

        assertTrue(file.isFile)
        assertNotNull(cache.uriFor(ACCOUNT_ID, SOURCE_DEVICE_ID, EVENT_ID))
        cache.retain(ACCOUNT_ID, setOf(EVENT_ID))
        assertTrue(file.isFile)
        cache.retain(ACCOUNT_ID, emptySet())
        assertFalse(file.exists())
        assertEquals(null, cache.uriFor(ACCOUNT_ID, SOURCE_DEVICE_ID, EVENT_ID))
        isolatedFiles.deleteRecursively()
    }

    private companion object {
        const val ACCOUNT_ID = "018f0870-7b8a-4abc-8abc-3123456789ab"
        const val SOURCE_DEVICE_ID = "01900000-0000-7000-8000-000000000010"
        const val EVENT_ID = "01900000-0000-7000-8000-000000000011"
    }
}
