package app.beyoureyes.core.data.reference

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.beyoureyes.core.vision.ReferenceImageAsset
import app.beyoureyes.core.vision.ReferenceImageMetadata
import app.beyoureyes.core.vision.ReferenceImageProvider
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class PrivateReferenceRecord(
    val localAssetId: String,
    val relativePath: String,
    val contentSha256: String,
    val storedWidth: Int,
    val storedHeight: Int,
) {
    init {
        require(localAssetId.isNotBlank())
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute)
        require(REFERENCE_SHA_256.matches(contentSha256))
        require(storedWidth > 0 && storedHeight > 0)
    }
}

/** Resolves only pre-declared app-private files and returns upright, tightly packed RGB888. */
class PrivateReferenceImageProvider(
    filesDir: File,
    records: Collection<PrivateReferenceRecord>,
) : ReferenceImageProvider {
    private val canonicalFilesDir = filesDir.canonicalFile
    private val recordsByAssetId = records.associateBy(PrivateReferenceRecord::localAssetId)
    private val cached = mutableMapOf<String, ReferenceImageAsset>()

    init {
        require(recordsByAssetId.size == records.size) { "duplicate local reference asset ID" }
    }

    fun metadataFor(
        record: PrivateReferenceRecord,
        referenceId: String,
    ): ReferenceImageMetadata {
        require(recordsByAssetId[record.localAssetId] == record)
        val asset = loadRecord(record)
        return ReferenceImageMetadata(
            referenceId = referenceId,
            localAssetId = asset.localAssetId,
            contentSha256 = asset.contentSha256,
            width = asset.width,
            height = asset.height,
        )
    }

    override fun load(metadata: ReferenceImageMetadata): ReferenceImageAsset? {
        val record = recordsByAssetId[metadata.localAssetId] ?: return null
        val asset = runCatching { loadRecord(record) }.getOrNull() ?: return null
        return asset.takeIf {
            it.contentSha256 == metadata.contentSha256 &&
                it.width == metadata.width &&
                it.height == metadata.height
        }
    }

    private fun loadRecord(record: PrivateReferenceRecord): ReferenceImageAsset = synchronized(cached) {
        val file = resolveRegularFile(record.relativePath)
        require(sha256(file) == record.contentSha256) { "reference image hash mismatch" }
        cached[record.localAssetId]?.let { return@synchronized it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..MAX_RUNTIME_EDGE && bounds.outHeight in 1..MAX_RUNTIME_EDGE)
        require(
            (bounds.outWidth == record.storedWidth && bounds.outHeight == record.storedHeight) ||
                (bounds.outWidth == record.storedHeight && bounds.outHeight == record.storedWidth),
        ) { "reference image source dimensions mismatch" }
        val upright = UprightReferenceBitmapDecoder.decode(
            file = file,
            inSampleSize = referenceRuntimeInSampleSize(bounds.outWidth, bounds.outHeight),
        )
        require(upright.width <= MAX_RUNTIME_EDGE && upright.height <= MAX_RUNTIME_EDGE) {
            upright.recycle()
            "reference image runtime dimensions exceed the bounded decoder"
        }
        val runtimeWidth = upright.width
        val runtimeHeight = upright.height
        val rgb = try {
            upright.toTightRgb888()
        } finally {
            upright.recycle()
        }
        ReferenceImageAsset(
            localAssetId = record.localAssetId,
            contentSha256 = record.contentSha256,
            width = runtimeWidth,
            height = runtimeHeight,
            rgb888 = rgb,
        ).also { cached[record.localAssetId] = it }
    }

    private fun resolveRegularFile(relativePath: String): File {
        val candidate = File(canonicalFilesDir, relativePath)
        require(!Files.isSymbolicLink(candidate.toPath())) { "reference image must not be a symlink" }
        val canonical = candidate.canonicalFile
        require(canonical.path.startsWith(canonicalFilesDir.path + File.separator)) {
            "reference image escaped app-private storage"
        }
        require(canonical.isFile) { "reference image is missing" }
        return canonical
    }

    private fun Bitmap.toTightRgb888(): ByteArray {
        require(width in 1..MAX_RUNTIME_EDGE && height in 1..MAX_RUNTIME_EDGE)
        val output = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), RGB_CHANNELS))
        val row = IntArray(width)
        repeat(height) { y ->
            getPixels(row, 0, width, 0, y, width, 1)
            row.forEachIndexed { x, colour ->
                val offset = (y * width + x) * RGB_CHANNELS
                output[offset] = (colour shr 16 and 0xff).toByte()
                output[offset + 1] = (colour shr 8 and 0xff).toByte()
                output[offset + 2] = (colour and 0xff).toByte()
            }
        }
        return output
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
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val RGB_CHANNELS = 3
        const val MAX_RUNTIME_EDGE = 1_024
    }
}

private val REFERENCE_SHA_256 = Regex("^[0-9a-f]{64}$")

/** One orientation-normalization implementation shared by enrollment fingerprints and runtime. */
object UprightReferenceBitmapDecoder {
    fun decode(file: File, inSampleSize: Int = 1): Bitmap {
        require(inSampleSize >= 1 && inSampleSize.countOneBits() == 1)
        val source = requireNotNull(
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = android.graphics.ColorSpace.get(
                        android.graphics.ColorSpace.Named.SRGB,
                    )
                    this.inSampleSize = inSampleSize
                },
            ),
        ) { "reference image decode failed" }
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return source.orient(orientation)
    }

    fun decode(contentResolver: ContentResolver, uri: Uri, inSampleSize: Int = 1): Bitmap {
        require(inSampleSize >= 1 && inSampleSize.countOneBits() == 1)
        val source = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            requireNotNull(
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inPreferredColorSpace = android.graphics.ColorSpace.get(
                            android.graphics.ColorSpace.Named.SRGB,
                        )
                        this.inSampleSize = inSampleSize
                    },
                ),
            )
        }
        val orientation = runCatching {
            contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                requireNotNull(descriptor)
                ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return source.orient(orientation)
    }

    private fun Bitmap.orient(orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) return this
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                else -> error("unsupported EXIF orientation")
            }
        }
        return try {
            Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        } catch (error: Exception) {
            recycle()
            throw error
        }.also { transformed ->
            if (transformed !== this) recycle()
        }
    }
}

internal fun referenceRuntimeInSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (maxOf(width, height) / sample > 1_024) sample *= 2
    return sample
}
