package app.beyoureyes.core.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * App-private cache for one Manifest admitted by an exact signed Catalog package entry.
 *
 * URL and SHA-256 are both part of the cache identity. Reads also hash the bytes before returning
 * them, and callers still decode and verify the signature, Catalog admission and freshness before
 * use. A changed Catalog entry can therefore never inherit bytes from an older entry.
 */
class VerifiedManifestCache(
    filesDirectory: File,
    private val catalogEntry: CatalogPackageEntry,
) {
    private val directory = filesDirectory.resolve(DIRECTORY_NAME)
    private val file: File

    init {
        requireFixedHttpsUrl(catalogEntry.manifestUrl, "$.packages.manifest_url")
        require(catalogEntry.manifestSha256.matches(SHA_256))
        val identity = "${catalogEntry.manifestUrl}\u0000${catalogEntry.manifestSha256}"
        file = directory.resolve("${sha256Hex(identity.encodeToByteArray())}.json")
    }

    fun readBytesOrNull(): ByteArray? {
        if (Files.isSymbolicLink(directory.toPath()) ||
            !file.isFile ||
            Files.isSymbolicLink(file.toPath())
        ) {
            return null
        }
        val size = file.length()
        if (size !in 1..MAX_SIGNED_MANIFEST_BYTES.toLong()) return null
        return runCatching { file.readBytes() }
            .getOrNull()
            ?.takeIf { bytes ->
                bytes.size.toLong() == size && sha256Hex(bytes) == catalogEntry.manifestSha256
            }
    }

    /** Best effort only: an inability to cache must not invalidate a verified online response. */
    fun write(verifiedManifest: VerifiedManifestDocument): Boolean = runCatching {
        require(verifiedManifest.catalogEntry == catalogEntry)
        require(verifiedManifest.documentSha256 == catalogEntry.manifestSha256)
        if (Files.isSymbolicLink(directory.toPath())) return false
        check(directory.isDirectory || directory.mkdirs()) { "Manifest cache is unavailable" }
        val bytes = verifiedManifest.copyDocumentBytes()
        require(bytes.isNotEmpty() && bytes.size <= MAX_SIGNED_MANIFEST_BYTES)
        require(sha256Hex(bytes) == catalogEntry.manifestSha256)
        val temporary = File.createTempFile("manifest-", ".tmp", directory)
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
            check(sha256Hex(file.readBytes()) == catalogEntry.manifestSha256)
            true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.getOrDefault(false)

    private companion object {
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        const val DIRECTORY_NAME = "signed-manifest-cache-v1"
    }
}
