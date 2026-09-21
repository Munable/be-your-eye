package app.beyoureyes.core.data.reference

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import app.beyoureyes.core.domain.MAX_REFERENCE_IMAGES
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.core.domain.ReferenceMaterialDeduplicator
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** App-private reference drafts; original picker URIs are never deleted or treated as durable. */
object ReferenceDraftStore {
    fun newSession(context: Context): File {
        val root = root(context)
        require(root.isDirectory || root.mkdirs()) { "reference draft root is unavailable" }
        return File(root, "session-${UUID.randomUUID()}").also { directory ->
            require(directory.mkdir()) { "reference draft session is unavailable" }
        }
    }

    fun stage(
        context: Context,
        material: ReferenceMaterial,
        canonicalJpeg: ByteArray,
        thumbnailJpeg: ByteArray,
        sessionDirectory: File,
    ): ReferenceMaterial? {
        val baseName = "${material.exactSha256}-${UUID.randomUUID()}"
        val image = File(sessionDirectory, "$baseName.img")
        val thumbnail = File(sessionDirectory, "$baseName.thumb.jpg")
        return runCatching {
            val root = requireOwnedSession(context, sessionDirectory)
            val ownedImage = File(root, image.name)
            val ownedThumbnail = File(root, thumbnail.name)
            writeCanonicalSource(ownedImage, canonicalJpeg, material.exactSha256)
            writePrivateBytes(ownedThumbnail, thumbnailJpeg)
            material.copy(
                sourceUri = Uri.fromFile(ownedImage).toString(),
                thumbnailUri = Uri.fromFile(ownedThumbnail).toString(),
            )
        }.getOrElse {
            deleteOwnedUri(sessionDirectory, Uri.fromFile(image))
            deleteOwnedUri(sessionDirectory, Uri.fromFile(thumbnail))
            null
        }
    }

    /** Returns the opaque identifier persisted by SavedStateHandle, never a filesystem path. */
    fun sessionId(context: Context, sessionDirectory: File): String {
        val session = requireOwnedSession(context, sessionDirectory)
        return requireNotNull(parseSessionId(session.name))
    }

    /** Atomically persists the complete draft state inside its app-private session. */
    fun persistSession(
        context: Context,
        sessionDirectory: File,
        name: String,
        materials: List<ReferenceMaterial>,
    ) {
        require(name.length <= 100)
        require(materials.size <= MAX_REFERENCE_IMAGES)
        ReferenceMaterialDeduplicator.requireDistinct(materials)
        val session = requireOwnedSession(context, sessionDirectory)
        val saved = materials.map { material -> savedMaterial(session, material) }
        val metadata = JSONObject().apply {
            put(METADATA_SCHEMA, METADATA_SCHEMA_VERSION)
            put(METADATA_NAME, name)
            put(
                METADATA_MATERIALS,
                JSONArray().apply {
                    saved.forEach { material -> put(material.toMetadataJson()) }
                },
            )
        }.toString().encodeToByteArray()
        require(metadata.size in 1..MAX_METADATA_BYTES)
        val destination = File(session, METADATA_FILE)
        require(!Files.isSymbolicLink(destination.toPath()))
        val temporary = File(session, ".$METADATA_FILE.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(metadata)
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, destination.absolutePath)
            syncDirectory(session)
        } finally {
            temporary.delete()
        }
    }

    /** Restores one app-private draft solely from its opaque session id and atomic metadata. */
    fun restoreSession(
        context: Context,
        sessionId: String,
    ): Triple<File, String, List<ReferenceMaterial>>? {
        val session = resolveSession(context, sessionId) ?: return null
        return runCatching {
            val metadataFile = ownedRegularFile(session, METADATA_FILE)
            require(metadataFile.length() in 1..MAX_METADATA_BYTES)
            val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
            require(metadata.keys().asSequence().toSet() == METADATA_KEYS)
            require(metadata.getInt(METADATA_SCHEMA) == METADATA_SCHEMA_VERSION)
            val name = metadata.getString(METADATA_NAME)
            require(name.length <= 100)
            val encodedMaterials = metadata.getJSONArray(METADATA_MATERIALS)
            require(encodedMaterials.length() <= MAX_REFERENCE_IMAGES)
            val savedMaterials = List(encodedMaterials.length()) { index ->
                encodedMaterials.getJSONObject(index).toSavedMaterial()
            }
            require(savedMaterials.size <= MAX_REFERENCE_IMAGES)
            val restored = savedMaterials.map { saved -> restoreMaterial(session, saved) }
            ReferenceMaterialDeduplicator.requireDistinct(restored)
            val referencedFiles = restored.flatMapTo(linkedSetOf<File>()) { material ->
                listOfNotNull(
                    Uri.parse(material.sourceUri).lastPathSegment?.let {
                        ownedRegularFile(session, it)
                    },
                    material.thumbnailUri?.let(Uri::parse)?.lastPathSegment?.let {
                        ownedRegularFile(session, it)
                    },
                )
            }.mapTo(linkedSetOf()) { it.canonicalPath }.apply {
                add(metadataFile.canonicalPath)
            }
            session.listFiles().orEmpty().forEach { candidate ->
                require(!Files.isSymbolicLink(candidate.toPath()) && candidate.isFile) {
                    "reference draft contains a non-regular entry"
                }
                if (candidate.canonicalPath !in referencedFiles) candidate.delete()
            }
            Triple(session, name, restored)
        }.getOrElse {
            discardSession(context, sessionId)
            null
        }
    }

    /** Deletes one complete app-private draft, including files not yet published to UI state. */
    fun discardSession(context: Context, sessionId: String) {
        val root = root(context)
        val canonicalId = canonicalSessionId(sessionId) ?: return
        val candidate = File(root, "$SESSION_PREFIX$canonicalId")
        if (Files.isSymbolicLink(candidate.toPath())) {
            Files.deleteIfExists(candidate.toPath())
            return
        }
        val session = runCatching { candidate.canonicalFile }.getOrNull() ?: return
        if (session.parentFile != root || !session.isDirectory) return
        session.listFiles().orEmpty().forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) {
                Files.deleteIfExists(child.toPath())
            } else {
                child.deleteRecursively()
            }
        }
        session.delete()
    }

    suspend fun removeReferences(
        context: Context,
        materials: Collection<ReferenceMaterial>,
    ) = withContext(Dispatchers.IO) {
        ReferenceEmbeddingCaches.openAppPrivate(context.filesDir).invalidateReferences(
            materials.mapTo(linkedSetOf(), ReferenceMaterial::exactSha256),
        )
        deleteDraftFiles(context, materials)
    }

    /** Removes only picker drafts; durable task files and their still-valid embeddings are kept. */
    fun discardDraftFiles(
        context: Context,
        materials: Collection<ReferenceMaterial>,
    ) {
        deleteDraftFiles(context, materials)
    }

    /** Called after durable task insertion; the complete owning draft session is now obsolete. */
    fun consumeDraftFiles(context: Context, materials: Collection<ReferenceMaterial>) {        val sessionIds = materials.asSequence()
            .flatMap { material -> sequenceOf(material.sourceUri, material.thumbnailUri ?: "") }
            .mapNotNull { raw -> owningSessionId(context, Uri.parse(raw)) }
            .toSet()
        deleteDraftFiles(context, materials)
        sessionIds.forEach { sessionId -> discardSession(context, sessionId) }
    }

    /**
     * 首页草稿恢复卡片：返回最近一个仍带有效元数据的草稿会话 id；只看 app 私有目录内的
     * session 目录，不读取图片内容。空草稿（无图片且无名称）由调用方决定丢弃。
     */
    fun latestSessionId(context: Context): String? {
        val root = root(context)
        if (!root.isDirectory) return null
        return root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            .filter { File(it, METADATA_FILE).isFile }
            .maxByOrNull { File(it, METADATA_FILE).lastModified() }
            ?.let { parseSessionId(it.name) }
    }

    fun deleteSessionIfEmpty(context: Context, sessionDirectory: File) {
        val ownedRoot = root(context)
        val session = runCatching { sessionDirectory.canonicalFile }.getOrNull() ?: return
        if (isStrictDescendant(session, ownedRoot) && session.list().orEmpty().isEmpty()) {
            session.delete()
        }
    }

    private fun deleteDraftFiles(context: Context, materials: Collection<ReferenceMaterial>) {
        val ownedRoot = root(context)
        materials.forEach { material ->
            listOfNotNull(material.sourceUri, material.thumbnailUri).forEach { raw ->
                deleteOwnedUri(ownedRoot, Uri.parse(raw))
            }
        }
        materials.asSequence()
            .flatMap { sequenceOf(it.sourceUri, it.thumbnailUri ?: "") }
            .mapNotNull { raw -> Uri.parse(raw).path?.let(::File)?.parentFile }
            .distinctBy { it.path }
            .forEach { parent ->
                if (isStrictDescendant(parent, ownedRoot) && parent.list().orEmpty().isEmpty()) {
                    parent.delete()
                }
            }
    }

    private fun writeCanonicalSource(
        destination: File,
        bytes: ByteArray,
        expectedSha256: String,
    ) {
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            require(bytes.isNotEmpty() && bytes.size <= MAX_REFERENCE_SOURCE_BYTES)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            require(temporary.length() > 0 && sha256(temporary) == expectedSha256) {
                "reference draft bytes changed while copying"
            }
            require(temporary.renameTo(destination)) { "reference draft could not be committed" }
        } finally {
            temporary.delete()
        }
    }

    private fun writePrivateBytes(destination: File, bytes: ByteArray) {
        require(bytes.isNotEmpty())
        FileOutputStream(destination).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun requireOwnedSession(context: Context, directory: File): File {
        val root = root(context)
        val canonical = directory.canonicalFile
        require(!Files.isSymbolicLink(canonical.toPath()))
        require(
            canonical.isDirectory && canonical.parentFile == root &&
                parseSessionId(canonical.name) != null,
        ) {
            "reference draft session escaped app cache"
        }
        return canonical
    }

    private fun resolveSession(context: Context, sessionId: String): File? {
        val canonicalId = canonicalSessionId(sessionId) ?: return null
        val candidate = File(root(context), "$SESSION_PREFIX$canonicalId")
        return runCatching { requireOwnedSession(context, candidate) }.getOrNull()
    }

    private fun restoreMaterial(session: File, saved: ReferenceMaterial): ReferenceMaterial {
        require(saved.width <= 1_024 && saved.height <= 1_024)
        val imageName = saved.sourceUri
        val thumbnailName = requireNotNull(saved.thumbnailUri)
        val token = imageName.removeSuffix(IMAGE_SUFFIX)
        require(imageName != token && token.startsWith("${saved.exactSha256}-"))
        require(canonicalSessionId(token.removePrefix("${saved.exactSha256}-")) != null)
        require(thumbnailName == "$token$THUMBNAIL_SUFFIX")
        val image = ownedRegularFile(session, imageName)
        val thumbnail = ownedRegularFile(session, thumbnailName)
        require(image.length() in 1..MAX_REFERENCE_SOURCE_BYTES)
        require(thumbnail.length() in 1..MAX_THUMBNAIL_BYTES)
        return saved.copy(
            sourceUri = Uri.fromFile(image).toString(),
            thumbnailUri = Uri.fromFile(thumbnail).toString(),
        )
    }

    private fun savedMaterial(session: File, material: ReferenceMaterial): ReferenceMaterial {
        val imageName = requireNotNull(Uri.parse(material.sourceUri).lastPathSegment)
        val thumbnailName = requireNotNull(material.thumbnailUri?.let(Uri::parse)?.lastPathSegment)
        val saved = material.copy(sourceUri = imageName, thumbnailUri = thumbnailName)
        val restored = restoreMaterial(session, saved)
        require(
            Uri.parse(restored.sourceUri).path == Uri.parse(material.sourceUri).path &&
                Uri.parse(restored.thumbnailUri).path == Uri.parse(material.thumbnailUri).path,
        )
        return saved
    }

    private fun ReferenceMaterial.toMetadataJson() = JSONObject().apply {
        put(SOURCE_FILE, sourceUri)
        put(THUMBNAIL_FILE, thumbnailUri)
        put(EXACT_SHA256, exactSha256)
        put(DIFFERENCE_HASH, differenceHash)
        put(MEAN_RED, meanRed)
        put(MEAN_GREEN, meanGreen)
        put(MEAN_BLUE, meanBlue)
        put(WIDTH, width)
        put(HEIGHT, height)
    }

    private fun JSONObject.toSavedMaterial(): ReferenceMaterial {
        require(keys().asSequence().toSet() == MATERIAL_KEYS)
        return ReferenceMaterial(
            sourceUri = getString(SOURCE_FILE),
            thumbnailUri = getString(THUMBNAIL_FILE),
            exactSha256 = getString(EXACT_SHA256),
            differenceHash = getLong(DIFFERENCE_HASH),
            meanRed = getInt(MEAN_RED),
            meanGreen = getInt(MEAN_GREEN),
            meanBlue = getInt(MEAN_BLUE),
            width = getInt(WIDTH),
            height = getInt(HEIGHT),
        )
    }

    private fun ownedRegularFile(session: File, fileName: String): File {
        require(fileName == File(fileName).name && fileName.isNotBlank())
        val candidate = File(session, fileName)
        require(!Files.isSymbolicLink(candidate.toPath()))
        val canonical = candidate.canonicalFile
        require(canonical.parentFile == session && canonical.isFile)
        return canonical
    }

    private fun deleteOwnedUri(root: File, uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return
        val path = uri.path ?: return
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        if (!isStrictDescendant(candidate, root) || Files.isSymbolicLink(candidate.toPath())) return
        if (candidate.isFile) candidate.delete()
    }

    private fun owningSessionId(context: Context, uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return null
        val parent = uri.path?.let(::File)?.parentFile ?: return null
        val session = runCatching { parent.canonicalFile }.getOrNull() ?: return null
        if (session.parentFile != root(context) || Files.isSymbolicLink(session.toPath())) return null
        return parseSessionId(session.name)
    }

    private fun root(context: Context) = File(context.cacheDir, ROOT_DIRECTORY).canonicalFile

    private fun parseSessionId(directoryName: String): String? =
        directoryName.takeIf { it.startsWith(SESSION_PREFIX) }
            ?.removePrefix(SESSION_PREFIX)
            ?.let(::canonicalSessionId)

    private fun canonicalSessionId(value: String): String? = runCatching {
        UUID.fromString(value).toString().takeIf { it == value }
    }.getOrNull()

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun isStrictDescendant(candidate: File, root: File): Boolean {
        val candidatePath = candidate.canonicalFile.toPath()
        val rootPath = root.canonicalFile.toPath()
        return candidatePath != rootPath && candidatePath.startsWith(rootPath)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val ROOT_DIRECTORY = "reference-drafts"
    private const val SESSION_PREFIX = "session-"
    private const val IMAGE_SUFFIX = ".img"
    private const val THUMBNAIL_SUFFIX = ".thumb.jpg"
    private const val MAX_THUMBNAIL_BYTES = 4L * 1024L * 1024L
    private const val MAX_METADATA_BYTES = 64 * 1024
    private const val METADATA_FILE = ".draft.json"
    private const val METADATA_SCHEMA = "schema"
    private const val METADATA_SCHEMA_VERSION = 1
    private const val METADATA_NAME = "name"
    private const val METADATA_MATERIALS = "materials"
    private const val SOURCE_FILE = "source_file"
    private const val THUMBNAIL_FILE = "thumbnail_file"
    private const val EXACT_SHA256 = "exact_sha256"
    private const val DIFFERENCE_HASH = "difference_hash"
    private const val MEAN_RED = "mean_red"
    private const val MEAN_GREEN = "mean_green"
    private const val MEAN_BLUE = "mean_blue"
    private const val WIDTH = "width"
    private const val HEIGHT = "height"
    private val METADATA_KEYS = setOf(METADATA_SCHEMA, METADATA_NAME, METADATA_MATERIALS)
    private val MATERIAL_KEYS = setOf(
        SOURCE_FILE,
        THUMBNAIL_FILE,
        EXACT_SHA256,
        DIFFERENCE_HASH,
        MEAN_RED,
        MEAN_GREEN,
        MEAN_BLUE,
        WIDTH,
        HEIGHT,
    )
}

const val MAX_REFERENCE_SOURCE_BYTES: Long = 64L * 1024L * 1024L

/** Bounds every pass over an untrusted picker stream, including providers that change per open. */
fun readReferenceBytesWithLimit(
    input: InputStream,
    maximumBytes: Long = MAX_REFERENCE_SOURCE_BYTES,
    consume: (ByteArray, Int) -> Unit,
): Long {
    require(maximumBytes > 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total = Math.addExact(total, count.toLong())
        require(total <= maximumBytes) { "reference source exceeds byte limit" }
        consume(buffer, count)
    }
    require(total > 0) { "reference source is empty" }
    return total
}
