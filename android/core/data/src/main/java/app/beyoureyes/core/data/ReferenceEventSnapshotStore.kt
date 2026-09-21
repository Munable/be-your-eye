package app.beyoureyes.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.Yuv420FrameNormalizer
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

data class ReferenceEventSnapshotReconcileResult(
    val inspectedFiles: Int,
    val deletedFiles: Int,
    val reachedLimit: Boolean,
) {
    init {
        require(inspectedFiles >= 0 && deletedFiles in 0..inspectedFiles)
    }
}

/** One app-private JPEG from the analyzed frame that confirmed a reference appearance. */
class ReferenceEventSnapshotStore private constructor(
    private val root: File,
) {
    fun capture(taskId: String, eventId: String, frame: SourceFrame): String {
        require(UuidV7.isValid(taskId) && UuidV7.isValid(eventId))
        val taskDirectory = taskDirectory(taskId)
        check(taskDirectory.isDirectory || taskDirectory.mkdirs()) {
            "event snapshot directory is unavailable"
        }
        val destination = fileFor(taskId, eventId)
        if (destination.isFile) return destination.toPrivateUri()

        val canonical = when (frame.pixels) {
            is FramePixels.Yuv420 -> Yuv420FrameNormalizer.normalize(frame)
            is FramePixels.Rgb888 -> UprightRgbFrameNormalizer.normalize(frame)
        }
        val sourceBitmap = canonical.rgb888.toBitmap(canonical.width, canonical.height)
        val outputBitmap = sourceBitmap.downscaleLongestEdge(MAX_LONG_EDGE_PIXELS)
        val temporary = File(taskDirectory, ".$eventId-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    "event snapshot JPEG encoding failed"
                }
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            return destination.toPrivateUri()
        } finally {
            temporary.delete()
            if (outputBitmap !== sourceBitmap) outputBitmap.recycle()
            sourceBitmap.recycle()
        }
    }

    fun uriFor(taskId: String, eventId: String): String? =
        taskId.takeIf(UuidV7::isValid)?.let { validTaskId ->
            eventId.takeIf(UuidV7::isValid)?.let { validEventId ->
                fileFor(validTaskId, validEventId)
            }
        }
        ?.takeIf(File::isFile)
        ?.toPrivateUri()

    /** A bounded derivative for one live encrypted relay response; the 1280 px local JPEG stays intact. */
    fun remotePreviewBytes(taskId: String, eventId: String): ByteArray? {
        if (!UuidV7.isValid(taskId) || !UuidV7.isValid(eventId)) return null
        val source = BitmapFactory.decodeFile(fileFor(taskId, eventId).absolutePath) ?: return null
        try {
            var longestEdge = REMOTE_MAX_LONG_EDGE_PIXELS
            while (longestEdge >= REMOTE_MIN_LONG_EDGE_PIXELS) {
                val scaled = source.downscaleLongestEdge(longestEdge)
                try {
                    REMOTE_JPEG_QUALITIES.forEach { quality ->
                        val encoded = ByteArrayOutputStream().use { output ->
                            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                            output.toByteArray()
                        }
                        if (encoded.size <= REMOTE_MAX_BYTES) return encoded
                    }
                } finally {
                    if (scaled !== source) scaled.recycle()
                }
                longestEdge = (longestEdge * 0.8f).roundToInt()
            }
            return null
        } finally {
            source.recycle()
        }
    }

    fun deleteEvent(taskId: String, eventId: String) {
        if (UuidV7.isValid(taskId) && UuidV7.isValid(eventId)) {
            fileFor(taskId, eventId).delete()
        }
    }

    fun deleteTask(taskId: String) {
        if (UuidV7.isValid(taskId)) taskDirectory(taskId).deleteRecursively()
    }

    /**
     * Deletes only pre-startup files that have no matching local Task/Event pair.
     *
     * The cutoff protects a capture currently being committed by this process. Traversal is
     * bounded so damaged private storage cannot turn application startup into an unbounded scan.
     */
    fun reconcile(
        createdBeforeOrAtEpochMillis: Long,
        maximumFiles: Int,
        isDurableLocalEvent: (taskId: String, eventId: String) -> Boolean,
    ): ReferenceEventSnapshotReconcileResult {
        require(createdBeforeOrAtEpochMillis >= 0)
        require(maximumFiles in 1..MAX_RECONCILE_FILES)
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            return ReferenceEventSnapshotReconcileResult(0, 0, false)
        }

        var inspected = 0
        var deleted = 0
        var taskDirectories = 0
        var reachedLimit = false
        Files.newDirectoryStream(root.toPath()).use { rootEntries ->
            val iterator = rootEntries.iterator()
            while (iterator.hasNext()) {
                if (inspected >= maximumFiles || taskDirectories >= MAX_RECONCILE_TASK_DIRECTORIES) {
                    reachedLimit = true
                    break
                }
                val taskPath = iterator.next()
                if (!Files.isDirectory(taskPath, LinkOption.NOFOLLOW_LINKS)) {
                    inspected++
                    if (taskPath.isOldEnough(createdBeforeOrAtEpochMillis) &&
                        runCatching { Files.deleteIfExists(taskPath) }.getOrDefault(false)
                    ) {
                        deleted++
                    }
                    continue
                }
                taskDirectories++
                val taskId = taskPath.fileName.toString()
                Files.newDirectoryStream(taskPath).use { taskEntries ->
                    val taskIterator = taskEntries.iterator()
                    while (taskIterator.hasNext()) {
                        if (inspected >= maximumFiles) {
                            reachedLimit = true
                            break
                        }
                        val file = taskIterator.next()
                        inspected++
                        val fileName = file.fileName.toString()
                        val eventId = fileName.removeSuffix(JPEG_SUFFIX)
                            .takeIf { fileName.endsWith(JPEG_SUFFIX) && UuidV7.isValid(it) }
                        val durable = UuidV7.isValid(taskId) && eventId != null &&
                            Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                            runCatching { isDurableLocalEvent(taskId, eventId) }.getOrDefault(true)
                        if (!durable && file.isOldEnough(createdBeforeOrAtEpochMillis) &&
                            runCatching { Files.deleteIfExists(file) }.getOrDefault(false)
                        ) {
                            deleted++
                        }
                    }
                }
                runCatching { Files.deleteIfExists(taskPath) }
            }
        }
        return ReferenceEventSnapshotReconcileResult(inspected, deleted, reachedLimit)
    }

    private fun taskDirectory(taskId: String): File = File(root, taskId)

    private fun fileFor(taskId: String, eventId: String): File =
        File(taskDirectory(taskId), "$eventId.jpg")

    private fun File.toPrivateUri(): String = Uri.fromFile(this).toString()

    companion object {
        private const val DIRECTORY = "reference-event-snapshots-v1"
        private const val JPEG_SUFFIX = ".jpg"
        private const val MAX_LONG_EDGE_PIXELS = 1_280
        private const val JPEG_QUALITY = 88
        private const val REMOTE_MAX_LONG_EDGE_PIXELS = 720
        private const val REMOTE_MIN_LONG_EDGE_PIXELS = 240
        private const val REMOTE_MAX_BYTES = 120 * 1_024
        private const val MAX_RECONCILE_FILES = 1_000
        private const val MAX_RECONCILE_TASK_DIRECTORIES = 100
        private val REMOTE_JPEG_QUALITIES = intArrayOf(78, 68, 58, 48)

        fun openAppPrivate(filesDir: File): ReferenceEventSnapshotStore =
            ReferenceEventSnapshotStore(File(filesDir, DIRECTORY))
    }
}

private fun java.nio.file.Path.isOldEnough(cutoffEpochMillis: Long): Boolean = runCatching {
    Files.getLastModifiedTime(this, LinkOption.NOFOLLOW_LINKS).toMillis() <= cutoffEpochMillis
}.getOrDefault(false)

private fun ByteArray.toBitmap(width: Int, height: Int): Bitmap {
    require(size == width * height * 3)
    val colors = IntArray(width * height)
    colors.indices.forEach { index ->
        val offset = index * 3
        colors[index] = (0xff shl 24) or
            ((this[offset].toInt() and 0xff) shl 16) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            (this[offset + 2].toInt() and 0xff)
    }
    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
}

private fun Bitmap.downscaleLongestEdge(maximum: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maximum) return this
    val scale = maximum.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}
