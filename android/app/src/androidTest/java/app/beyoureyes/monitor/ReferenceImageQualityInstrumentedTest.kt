package app.beyoureyes.monitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.reference.ReferenceDraftStore
import app.beyoureyes.core.data.reference.ReferenceImageRepository
import app.beyoureyes.core.vision.ReferenceEmbeddingCacheKey
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import app.beyoureyes.core.vision.NeuralReferenceRuntimeComponents
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceImageQualityInstrumentedTest {
    @Test
    fun everyReadableDistinctReferenceCountsTowardSufficiency() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "reference-quality-${System.nanoTime()}").apply {
            mkdirs()
        }
        val repository = ReferenceImageRepository(context)
        val session = repository.newDraftSession()
        try {
            val sharp = writeBitmap(directory.resolve("sharp.png"), checkerBitmap())
            val duplicate = directory.resolve("sharp-copy.png").also {
                sharp.copyTo(it)
            }
            val dark = writeBitmap(directory.resolve("dark.png"), solidBitmap(2))
            val flat = writeBitmap(directory.resolve("flat.png"), solidBitmap(128))
            val unreadable = directory.resolve("broken.png").apply { writeText("not an image") }

            val result = repository.inspect(
                incoming = listOf(sharp, duplicate, dark, flat, unreadable).map(Uri::fromFile),
                existing = emptyList(),
                sessionDirectory = session,
            )

            assertEquals(3, result.accepted.size)
            assertEquals(1, result.duplicateCount)
            assertEquals(1, result.unreadableCount)
            assertTrue(result.accepted.all { it.width == 96 && it.height == 96 })
        } finally {
            directory.deleteRecursively()
            session.deleteRecursively()
        }
    }

    @Test
    fun sevenMixedSizeOrientationAndFormatImagesRemainSevenCanonicalReferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "reference-mixed-${System.nanoTime()}").apply {
            mkdirs()
        }
        val repository = ReferenceImageRepository(context)
        val session = repository.newDraftSession()
        try {
            val sources = listOf(
                writePattern(directory.resolve("one.jpg"), 320, 180, 1, Bitmap.CompressFormat.JPEG),
                writePattern(directory.resolve("two.png"), 720, 1_280, 2, Bitmap.CompressFormat.PNG),
                writePattern(directory.resolve("three.webp"), 1_600, 900, 3, Bitmap.CompressFormat.WEBP_LOSSLESS),
                writePattern(directory.resolve("four.jpg"), 5_000, 700, 4, Bitmap.CompressFormat.JPEG),
                writePattern(directory.resolve("five.png"), 480, 480, 5, Bitmap.CompressFormat.PNG),
                copyAsset(
                    directory.resolve("six.heic"),
                    "reference-fixtures/seven.heic",
                ),
                writePattern(directory.resolve("seven-rotated.jpg"), 1_200, 600, 7, Bitmap.CompressFormat.JPEG)
                    .also { file ->
                        ExifInterface(file.path).apply {
                            setAttribute(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_ROTATE_90.toString(),
                            )
                            saveAttributes()
                        }
                    },
            )

            val result = repository.inspect(
                incoming = sources.map(Uri::fromFile),
                existing = emptyList(),
                sessionDirectory = session,
            )

            assertEquals(7, result.accepted.size)
            assertEquals(0, result.duplicateCount)
            assertEquals(0, result.unreadableCount)
            assertTrue(result.accepted.all { maxOf(it.width, it.height) <= 1_024 })
            assertEquals(512, result.accepted.last().width)
            assertEquals(1_024, result.accepted.last().height)
            result.accepted.forEach { material ->
                val canonical = File(requireNotNull(Uri.parse(material.sourceUri).path))
                val thumbnail = File(requireNotNull(Uri.parse(material.thumbnailUri).path))
                assertTrue(canonical.inputStream().use { it.read() == 0xff && it.read() == 0xd8 })
                val thumbnailBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(thumbnail.path, thumbnailBounds)
                assertTrue(maxOf(thumbnailBounds.outWidth, thumbnailBounds.outHeight) <= 160)
            }
        } finally {
            directory.deleteRecursively()
            session.deleteRecursively()
        }
    }

    @Test
    fun acceptedDraftOwnsThumbnailAndRemovalDeletesPrivateFilesAndEmbeddingCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDirectory = File(context.cacheDir, "reference-draft-source-${System.nanoTime()}").apply {
            mkdirs()
        }
        val repository = ReferenceImageRepository(context)
        val session = repository.newDraftSession()
        try {
            val source = writeBitmap(sourceDirectory.resolve("sharp.png"), checkerBitmap())
            val result = repository.inspect(
                incoming = listOf(Uri.fromFile(source)),
                existing = emptyList(),
                sessionDirectory = session,
            )
            val material = result.accepted.single()
            val privateImage = File(requireNotNull(Uri.parse(material.sourceUri).path))
            val privateThumbnail = File(requireNotNull(Uri.parse(material.thumbnailUri).path))
            assertTrue(privateImage.isFile)
            assertTrue(privateThumbnail.isFile)
            assertTrue(source.isFile)

            val cache = ReferenceEmbeddingCaches.openAppPrivate(context.filesDir)
            val key = ReferenceEmbeddingCacheKey(
                "a".repeat(64),
                material.exactSha256,
                NeuralReferenceRuntimeComponents.PREPROCESS_ID,
            )
            cache.store(key, floatArrayOf(0.6f, 0.8f))
            assertNotNull(cache.load(key, 2))

            ReferenceDraftStore.removeReferences(context, listOf(material))

            assertFalse(privateImage.exists())
            assertFalse(privateThumbnail.exists())
            assertTrue(source.isFile)
            assertEquals(null, cache.load(key, 2))
        } finally {
            sourceDirectory.deleteRecursively()
            session.deleteRecursively()
        }
    }

    private fun checkerBitmap(): Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
        repeat(96) { y ->
            repeat(96) { x ->
                val value = if (((x / 8) + (y / 8)) % 2 == 0) 35 else 220
                it.setPixel(x, y, Color.rgb(value, value, value))
            }
        }
    }

    private fun solidBitmap(value: Int): Bitmap =
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.rgb(value, value, value))
        }

    private fun writeBitmap(file: File, bitmap: Bitmap): File = try {
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        file
    } finally {
        bitmap.recycle()
    }

    private fun writePattern(
        file: File,
        width: Int,
        height: Int,
        seed: Int,
        format: Bitmap.CompressFormat,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.rgb((seed * 31) % 255, (seed * 67) % 255, (seed * 101) % 255))
            val paint = android.graphics.Paint().apply {
                color = Color.rgb((seed * 83 + 40) % 255, (seed * 43 + 80) % 255, (seed * 19 + 120) % 255)
            }
            val stripe = maxOf(8f, minOf(width, height) / (seed + 3f))
            repeat(seed + 2) { index ->
                val left = (index * width.toFloat() / (seed + 2)).coerceAtMost(width - stripe)
                canvas.drawRect(left, index * 7f, left + stripe, height - index * 5f, paint)
            }
            file.outputStream().use { output -> check(bitmap.compress(format, 95, output)) }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyAsset(file: File, assetPath: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        testContext.assets.open(assetPath).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }
}
