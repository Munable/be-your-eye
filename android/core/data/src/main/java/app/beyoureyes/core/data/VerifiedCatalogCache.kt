package app.beyoureyes.core.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * App-private cache for one already verified signed Catalog.
 *
 * The cache never turns bytes into trusted metadata. Every read is decoded, signature checked and
 * expiry checked again by [SignedMetadataCodec] before use. Keying by the exact configured HTTPS
 * URL prevents one build/channel endpoint from reusing another endpoint's bytes.
 */
class VerifiedCatalogCache(
    filesDirectory: File,
    sourceUrl: String,
) {
    private val directory = filesDirectory.resolve(DIRECTORY_NAME)
    private val file: File

    init {
        requireFixedHttpsUrl(sourceUrl, "$.catalog_url")
        file = directory.resolve("${sha256Hex(sourceUrl.encodeToByteArray())}.json")
    }

    fun readBytesOrNull(): ByteArray? {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) return null
        val size = file.length()
        if (size !in 1..MAX_SIGNED_CATALOG_BYTES.toLong()) return null
        return runCatching { file.readBytes() }
            .getOrNull()
            ?.takeIf { it.size.toLong() == size }
    }

    /** Best effort only: an inability to cache must not invalidate a verified online response. */
    fun write(verifiedCatalog: VerifiedCapabilityCatalog): Boolean = runCatching {
        if (Files.isSymbolicLink(directory.toPath())) return false
        check(directory.isDirectory || directory.mkdirs()) { "Catalog cache is unavailable" }
        val bytes = verifiedCatalog.copyDocumentBytes()
        require(bytes.isNotEmpty() && bytes.size <= MAX_SIGNED_CATALOG_BYTES)
        val temporary = File.createTempFile("catalog-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            check(file.length() == bytes.size.toLong())
            check(sha256Hex(file.readBytes()) == verifiedCatalog.documentSha256)
            true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.getOrDefault(false)

    private companion object {
        const val DIRECTORY_NAME = "signed-catalog-cache-v1"
    }
}
