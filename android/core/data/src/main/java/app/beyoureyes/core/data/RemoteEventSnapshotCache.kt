package app.beyoureyes.core.data

import android.graphics.BitmapFactory
import android.net.Uri
import app.beyoureyes.core.data.cloud.RemoteSnapshotCrypto
import app.beyoureyes.core.data.cloud.requireAccountUuid
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Decrypted peer previews cached only on the viewing device. */
class RemoteEventSnapshotCache private constructor(
    private val root: File,
) {
    fun uriFor(accountId: String, sourceDeviceId: String, eventId: String): String? =
        fileFor(accountId, sourceDeviceId, eventId)
            .takeIf(File::isFile)
            ?.let { Uri.fromFile(it).toString() }

    fun store(
        accountId: String,
        sourceDeviceId: String,
        eventId: String,
        jpeg: ByteArray,
    ): String {
        requireIdentifiers(accountId, sourceDeviceId, eventId)
        require(jpeg.size in 1..RemoteSnapshotCrypto.MAX_PLAINTEXT_BYTES)
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0)
        require(maxOf(bounds.outWidth, bounds.outHeight) <= MAX_LONG_EDGE_PIXELS)

        val directory = sourceDirectory(accountId, sourceDeviceId)
        check(directory.isDirectory || directory.mkdirs()) { "remote snapshot cache is unavailable" }
        val destination = fileFor(accountId, sourceDeviceId, eventId)
        val temporary = File(directory, ".$eventId-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(jpeg)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        return Uri.fromFile(destination).toString()
    }

    fun clearAccount(accountId: String) {
        requireAccountUuid(accountId)
        accountDirectory(accountId).deleteRecursively()
    }

    fun retain(accountId: String, eventIds: Set<String>) {
        requireAccountUuid(accountId)
        require(eventIds.all(UuidV7::isValid))
        accountDirectory(accountId).listFiles().orEmpty().filter(File::isDirectory).forEach { source ->
            source.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                val eventId = file.name.removeSuffix(".jpg")
                if (file.extension != "jpg" || eventId !in eventIds) file.delete()
            }
            if (source.list().isNullOrEmpty()) source.delete()
        }
    }

    private fun fileFor(accountId: String, sourceDeviceId: String, eventId: String): File {
        requireIdentifiers(accountId, sourceDeviceId, eventId)
        return File(sourceDirectory(accountId, sourceDeviceId), "$eventId.jpg")
    }

    private fun accountDirectory(accountId: String): File = File(root, accountId.lowercase())

    private fun sourceDirectory(accountId: String, sourceDeviceId: String): File =
        File(accountDirectory(accountId), sourceDeviceId)

    private fun requireIdentifiers(accountId: String, sourceDeviceId: String, eventId: String) {
        requireAccountUuid(accountId)
        require(UuidV7.isValid(sourceDeviceId) && UuidV7.isValid(eventId))
    }

    companion object {
        private const val DIRECTORY = "remote-reference-event-snapshots-v1"
        private const val MAX_LONG_EDGE_PIXELS = 720

        fun openAppPrivate(filesDir: File): RemoteEventSnapshotCache =
            RemoteEventSnapshotCache(File(filesDir, DIRECTORY))
    }
}
