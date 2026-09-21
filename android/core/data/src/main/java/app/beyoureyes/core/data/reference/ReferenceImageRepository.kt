package app.beyoureyes.core.data.reference

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.domain.MAX_REFERENCE_IMAGES
import app.beyoureyes.core.domain.MIN_REFERENCE_IMAGES
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.core.domain.ReferenceMaterialDeduplicator
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReferenceImportResult(
    val accepted: List<ReferenceMaterial>,
    val duplicateCount: Int,
    val unreadableCount: Int,
)

data class StoredReference(
    val relativePath: String,
    val thumbnailRelativePath: String,
    val exactSha256: String,
    val thumbnailSha256: String,
    val differenceHash: Long,
    val meanRed: Int,
    val meanGreen: Int,
    val meanBlue: Int,
    val width: Int,
    val height: Int,
)

/**
 * A complete durable reference generation that is not visible to Room yet.
 *
 * New generations are written and fsynced before the Room revision changes. A failed compare and
 * set can therefore delete this directory, while a committed revision always points at a complete
 * set. Old generations are only pruned after Room commits.
 */
internal class StagedReferenceSet(
    val monitorId: String,
    val references: List<StoredReference>,
    internal val directory: File,
)

class ReferenceImageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    fun newDraftSession(): File = ReferenceDraftStore.newSession(appContext)

    suspend fun inspect(
        incoming: List<Uri>,
        existing: List<ReferenceMaterial>,
        sessionDirectory: File,
    ): ReferenceImportResult = withContext(Dispatchers.IO) {
        ReferenceDraftStore.sessionId(appContext, sessionDirectory)
        val accepted = mutableListOf<ReferenceMaterial>()
        var duplicates = 0
        var unreadable = 0
        incoming.forEach { uri ->
            if (existing.size + accepted.size >= MAX_REFERENCE_IMAGES) return@forEach
            val candidate = runCatching { fingerprint(uri, sessionDirectory) }.getOrNull()
            when {
                candidate == null -> unreadable++
                ReferenceMaterialDeduplicator.isDuplicate(candidate.material, existing + accepted) ->
                    duplicates++
                else -> ReferenceDraftStore.stage(
                    appContext,
                    candidate.material,
                    candidate.canonicalJpeg,
                    candidate.thumbnailJpeg,
                    sessionDirectory,
                )?.let(accepted::add) ?: run { unreadable++ }
            }
        }
        ReferenceImportResult(accepted, duplicates, unreadable)
    }

    internal fun stagePersistentSet(
        monitorId: String,
        materials: List<ReferenceMaterial>,
    ): StagedReferenceSet {
        require(UuidV7.isValid(monitorId))
        require(materials.size in MIN_REFERENCE_IMAGES..MAX_REFERENCE_IMAGES)
        ReferenceMaterialDeduplicator.requireDistinct(materials)
        val monitorDirectory = directory(monitorId)
        require(monitorDirectory.mkdirs() || monitorDirectory.isDirectory)
        val generationDirectory = File(monitorDirectory, "generation-${UUID.randomUUID()}")
        require(generationDirectory.mkdir()) { "reference generation is unavailable" }
        val copied = mutableListOf<StoredReference>()
        try {
            materials.forEachIndexed { index, material ->
                val destination = File(generationDirectory, "reference-${index + 1}.jpg")
                val thumbnailDestination = File(
                    generationDirectory,
                    "reference-${index + 1}.thumb.jpg",
                )
                contentResolver.openInputStream(Uri.parse(material.sourceUri)).use { input ->
                    requireNotNull(input)
                    FileOutputStream(destination).use { output ->
                        readReferenceBytesWithLimit(input) { bytes, count -> output.write(bytes, 0, count) }
                        output.fd.sync()
                    }
                }
                contentResolver.openInputStream(Uri.parse(requireNotNull(material.thumbnailUri))).use { input ->
                    requireNotNull(input)
                    FileOutputStream(thumbnailDestination).use { output ->
                        readReferenceBytesWithLimit(input) { bytes, count -> output.write(bytes, 0, count) }
                        output.fd.sync()
                    }
                }
                require(destination.length() > 0 && sha256(destination) == material.exactSha256)
                require(thumbnailDestination.length() > 0)
                copied += StoredReference(
                    relativePath = destination.relativeTo(appContext.filesDir).path,
                    thumbnailRelativePath = thumbnailDestination.relativeTo(appContext.filesDir).path,
                    exactSha256 = material.exactSha256,
                    thumbnailSha256 = sha256(thumbnailDestination),
                    differenceHash = material.differenceHash,
                    meanRed = material.meanRed,
                    meanGreen = material.meanGreen,
                    meanBlue = material.meanBlue,
                    width = material.width,
                    height = material.height,
                )
            }
            require(copied.size == materials.size)
            syncDirectory(generationDirectory)
            syncDirectory(monitorDirectory)
            return StagedReferenceSet(monitorId, copied, generationDirectory)
        } catch (failure: Throwable) {
            generationDirectory.deleteRecursively()
            removeMonitorDirectoryIfEmpty(monitorDirectory)
            throw failure
        }
    }

    internal fun commitDrafts(materials: Collection<ReferenceMaterial>) =
        ReferenceDraftStore.consumeDraftFiles(appContext, materials)

    fun draftSessionId(sessionDirectory: File): String =
        ReferenceDraftStore.sessionId(appContext, sessionDirectory)

    fun persistDraftSession(
        sessionDirectory: File,
        name: String,
        materials: List<ReferenceMaterial>,
    ) = ReferenceDraftStore.persistSession(appContext, sessionDirectory, name, materials)

    fun restoreDraftSession(
        sessionId: String,
    ): Triple<File, String, List<ReferenceMaterial>>? =
        ReferenceDraftStore.restoreSession(appContext, sessionId)

    fun discardDraftSession(sessionId: String) =
        ReferenceDraftStore.discardSession(appContext, sessionId)

    /**
     * 返回最近一个仍有内容的草稿（sessionId、名称、素材）；空草稿顺手清理并视为不存在。
     */
    fun latestReferenceDraft(): Triple<String, String, List<ReferenceMaterial>>? {
        val sessionId = ReferenceDraftStore.latestSessionId(appContext) ?: return null
        val restored = restoreDraftSession(sessionId) ?: return null
        val (directory, name, materials) = restored
        if (materials.isEmpty() && name.isBlank()) {
            discardDraftSession(sessionId)
            return null
        }
        return Triple(draftSessionId(directory), name, materials)
    }

    internal fun discard(staged: StagedReferenceSet) {
        require(UuidV7.isValid(staged.monitorId))
        // Preparation may have enrolled these references before the CameraX field pass. A
        // cancelled or failed transient setup must remove the derived embeddings together with
        // the staged JPEG generation; otherwise a draft that never became a monitor remains in
        // the app-private cache and can be reused by a later run.
        ReferenceEmbeddingCaches.openAppPrivate(appContext.filesDir).invalidateReferences(
            staged.references.mapTo(linkedSetOf(), StoredReference::exactSha256),
        )
        val monitorDirectory = directory(staged.monitorId).canonicalFile
        val generationDirectory = staged.directory.canonicalFile
        require(generationDirectory.parentFile == monitorDirectory)
        generationDirectory.deleteRecursively()
        removeMonitorDirectoryIfEmpty(monitorDirectory)
    }

    /** Keeps only the generation referenced by the newly committed Room row. */
    internal fun prune(monitorId: String, keep: List<StoredReference>) {
        require(UuidV7.isValid(monitorId))
        val monitorDirectory = directory(monitorId).canonicalFile
        if (!monitorDirectory.exists()) return
        require(monitorDirectory.isDirectory)
        val keptDirectories = keep.mapTo(linkedSetOf()) { reference ->
            val file = File(appContext.filesDir, reference.relativePath).canonicalFile
            val thumbnail = File(appContext.filesDir, reference.thumbnailRelativePath).canonicalFile
            require(file.toPath().startsWith(monitorDirectory.toPath()) && file.isFile)
            require(thumbnail.toPath().startsWith(monitorDirectory.toPath()) && thumbnail.isFile)
            requireNotNull(file.parentFile).also { parent ->
                require(parent.parentFile == monitorDirectory)
            }
        }
        require(keptDirectories.size == 1) { "one monitor revision must use one generation" }
        monitorDirectory.listFiles().orEmpty().forEach { candidate ->
            if (candidate.canonicalFile !in keptDirectories) candidate.deleteRecursively()
        }
    }

    /** Removes generations left before or after a process death without touching Room-owned data. */
    internal fun reconcile(expected: Map<String, List<StoredReference>>) {
        val root = File(appContext.filesDir, REFERENCE_DIRECTORY).canonicalFile
        if (!root.exists()) return
        require(root.isDirectory)
        root.listFiles().orEmpty().forEach { monitorDirectory ->
            val references = expected[monitorDirectory.name]
            if (references == null || !UuidV7.isValid(monitorDirectory.name)) {
                val orphanHashes = monitorDirectory.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("reference-") && it.extension == "jpg" }
                    .mapTo(linkedSetOf(), ::sha256)
                ReferenceEmbeddingCaches.openAppPrivate(appContext.filesDir)
                    .invalidateReferences(orphanHashes)
                monitorDirectory.deleteRecursively()
            } else {
                prune(monitorDirectory.name, references)
            }
        }
    }

    fun load(monitorId: String, references: List<StoredReference>): List<ReferenceMaterial> {
        require(UuidV7.isValid(monitorId))
        return references.map { reference ->
            val file = resolve(monitorId, reference)
            val thumbnail = resolveThumbnail(monitorId, reference)
            ReferenceMaterial(
                sourceUri = Uri.fromFile(file).toString(),
                thumbnailUri = Uri.fromFile(thumbnail).toString(),
                exactSha256 = reference.exactSha256,
                differenceHash = reference.differenceHash,
                meanRed = reference.meanRed,
                meanGreen = reference.meanGreen,
                meanBlue = reference.meanBlue,
                width = reference.width,
                height = reference.height,
            )
        }
    }

    fun delete(monitorId: String) {
        require(UuidV7.isValid(monitorId))
        directory(monitorId).deleteRecursively()
    }

    fun discardDrafts(materials: Collection<ReferenceMaterial>) =
        ReferenceDraftStore.discardDraftFiles(appContext, materials)

    private fun resolve(monitorId: String, reference: StoredReference): File {
        val file = File(appContext.filesDir, reference.relativePath).canonicalFile
        val root = directory(monitorId).canonicalFile
        require(
            file != root && file.toPath().startsWith(root.toPath()) && file.isFile &&
                sha256(file) == reference.exactSha256,
        )
        return file
    }

    private fun resolveThumbnail(monitorId: String, reference: StoredReference): File {
        val file = File(appContext.filesDir, reference.thumbnailRelativePath).canonicalFile
        val root = directory(monitorId).canonicalFile
        require(
            file != root && file.toPath().startsWith(root.toPath()) && file.isFile &&
                sha256(file) == reference.thumbnailSha256,
        )
        return file
    }

    private fun directory(monitorId: String) = File(
        appContext.filesDir,
        "$REFERENCE_DIRECTORY/$monitorId",
    )

    private fun removeMonitorDirectoryIfEmpty(directory: File) {
        if (directory.listFiles()?.isEmpty() == true) directory.delete()
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun fingerprint(uri: Uri, sessionDirectory: File): InspectedReference {
        // Copy one bounded provider response before decoding. This prevents a provider that returns
        // different bytes across opens from bypassing the 64 MiB gate or changing EXIF mid-import.
        val boundedSource = File(sessionDirectory, ".incoming-${UUID.randomUUID()}.tmp")
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                FileOutputStream(boundedSource).use { output ->
                    readReferenceBytesWithLimit(input) { bytes, count ->
                        output.write(bytes, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val bitmap = decodeCanonicalBitmap(boundedSource)
            try {
                val canonicalJpeg = bitmap.toJpeg(CANONICAL_JPEG_QUALITY)
                val exactSha256 = MessageDigest.getInstance("SHA-256").digest(canonicalJpeg)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val width = bitmap.width
                val height = bitmap.height
                val fingerprint = bitmap.toVisualFingerprint()
                val thumbnail = bitmap.toThumbnailJpeg()
                return InspectedReference(
                    ReferenceMaterial(
                        sourceUri = uri.toString(),
                        exactSha256 = exactSha256,
                        differenceHash = fingerprint.differenceHash,
                        meanRed = fingerprint.meanRed,
                        meanGreen = fingerprint.meanGreen,
                        meanBlue = fingerprint.meanBlue,
                        width = width,
                        height = height,
                    ),
                    canonicalJpeg,
                    thumbnail,
                )
            } finally {
                bitmap.recycle()
            }
        } finally {
            boundedSource.delete()
        }
    }

    private fun decodeCanonicalBitmap(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_CANONICAL_EDGE * 2) {
            sample = Math.multiplyExact(sample, 2)
        }
        val upright = UprightReferenceBitmapDecoder.decode(file, sample)
        val ratio = minOf(1f, MAX_CANONICAL_EDGE.toFloat() / maxOf(upright.width, upright.height))
        val scaled = if (ratio == 1f) upright else Bitmap.createScaledBitmap(
            upright,
            maxOf(1, (upright.width * ratio).toInt()),
            maxOf(1, (upright.height * ratio).toInt()),
            true,
        ).also { upright.recycle() }
        val flattened = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.rgb(127, 127, 127))
            drawBitmap(scaled, 0f, 0f, null)
        }
        scaled.recycle()
        return flattened
    }

    private fun Bitmap.toThumbnailJpeg(): ByteArray {
        val ratio = minOf(1f, 160f / maxOf(width, height))
        val scaled = if (ratio == 1f) this else Bitmap.createScaledBitmap(
            this,
            maxOf(1, (width * ratio).toInt()),
            maxOf(1, (height * ratio).toInt()),
            true,
        )
        return try {
            ByteArrayOutputStream().use { output ->
                require(scaled.compress(Bitmap.CompressFormat.JPEG, 85, output))
                output.toByteArray()
            }
        } finally {
            if (scaled !== this) scaled.recycle()
        }
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
        require(compress(Bitmap.CompressFormat.JPEG, quality, output))
        output.toByteArray()
    }

    private fun Bitmap.toVisualFingerprint(): VisualFingerprint {
        val scaled = Bitmap.createScaledBitmap(this, 9, 8, true)
        return try {
            val colors = IntArray(72)
            val gray = IntArray(72)
            scaled.getPixels(colors, 0, 9, 0, 0, 9, 8)
            colors.forEachIndexed { index, color ->
                val r = color shr 16 and 0xff
                val g = color shr 8 and 0xff
                val b = color and 0xff
                gray[index] = (r * 30 + g * 59 + b * 11) / 100
            }
            var red = 0L
            var green = 0L
            var blue = 0L
            val row = IntArray(width)
            repeat(height) { y ->
                getPixels(row, 0, width, 0, y, width, 1)
                row.forEach { color ->
                    red += color shr 16 and 0xff
                    green += color shr 8 and 0xff
                    blue += color and 0xff
                }
            }
            val pixels = width.toLong() * height
            VisualFingerprint(
                ReferenceMaterialDeduplicator.differenceHash(gray),
                (red / pixels).toInt(),
                (green / pixels).toInt(),
                (blue / pixels).toInt(),
            )
        } finally {
            scaled.recycle()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class InspectedReference(
        val material: ReferenceMaterial,
        val canonicalJpeg: ByteArray,
        val thumbnailJpeg: ByteArray,
    )

    private data class VisualFingerprint(
        val differenceHash: Long,
        val meanRed: Int,
        val meanGreen: Int,
        val meanBlue: Int,
    )

    private companion object {
        const val REFERENCE_DIRECTORY = "reference-images"
        const val MAX_CANONICAL_EDGE = 1_024
        const val CANONICAL_JPEG_QUALITY = 95
    }
}
