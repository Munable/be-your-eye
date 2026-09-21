package app.beyoureyes.core.vision

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Exact cache identity. Changing model bytes, reference bytes or preprocessing cannot reuse data. */
data class ReferenceEmbeddingCacheKey(
    val artifactSha256: String,
    val referenceSha256: String,
    val preprocessId: String,
) {
    init {
        require(SHA_256.matches(artifactSha256))
        require(SHA_256.matches(referenceSha256))
        require(PREPROCESS_ID.matches(preprocessId))
    }

    internal fun canonicalBytes(): ByteArray = listOf(
        CACHE_KEY_VERSION,
        artifactSha256,
        referenceSha256,
        preprocessId,
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    internal fun fileName(): String = "$referenceSha256--${sha256(canonicalBytes())}$CACHE_SUFFIX"
}

interface ReferenceEmbeddingCache {
    fun load(key: ReferenceEmbeddingCacheKey, expectedDimension: Int): FloatArray?
    fun store(key: ReferenceEmbeddingCacheKey, normalizedEmbedding: FloatArray)
    fun invalidateReferences(referenceSha256: Set<String>)
}

/** App-private persistent cache with a process-local memory layer and corruption detection. */
class PersistentReferenceEmbeddingCache(
    private val rootDirectory: File,
) : ReferenceEmbeddingCache {
    private val canonicalRoot = rootDirectory.canonicalPath

    init {
        synchronized(FILE_LOCK) {
            require(rootDirectory.isDirectory || rootDirectory.mkdirs()) {
                "reference embedding cache directory is unavailable"
            }
            require(!Files.isSymbolicLink(rootDirectory.toPath())) {
                "reference embedding cache directory must not be a symlink"
            }
        }
    }

    override fun load(key: ReferenceEmbeddingCacheKey, expectedDimension: Int): FloatArray? {
        require(expectedDimension in MIN_DIMENSION..MAX_DIMENSION)
        synchronized(FILE_LOCK) {
            val memoryKey = MemoryCacheKey(canonicalRoot, key)
            MEMORY[memoryKey]?.takeIf { it.size == expectedDimension }?.let { return it.copyOf() }
            val file = rootDirectory.resolve(key.fileName())
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) return null
            val decoded = runCatching { decode(file.readBytes(), key, expectedDimension) }.getOrNull()
            if (decoded == null) {
                file.delete()
                return null
            }
            MEMORY[memoryKey] = decoded.copyOf()
            return decoded
        }
    }

    override fun store(key: ReferenceEmbeddingCacheKey, normalizedEmbedding: FloatArray) {
        require(normalizedEmbedding.size in MIN_DIMENSION..MAX_DIMENSION)
        require(normalizedEmbedding.all(Float::isFinite))
        require(isUnitLength(normalizedEmbedding)) { "cached embedding must be L2-normalized" }
        val bytes = encode(key, normalizedEmbedding)
        synchronized(FILE_LOCK) {
            val destination = rootDirectory.resolve(key.fileName())
            val temporary = rootDirectory.resolve(".${destination.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                MEMORY[MemoryCacheKey(canonicalRoot, key)] = normalizedEmbedding.copyOf()
            } finally {
                temporary.delete()
            }
        }
    }

    override fun invalidateReferences(referenceSha256: Set<String>) {
        require(referenceSha256.all(SHA_256::matches))
        if (referenceSha256.isEmpty()) return
        synchronized(FILE_LOCK) {
            MEMORY.keys.removeAll { memoryKey ->
                memoryKey.canonicalRoot == canonicalRoot &&
                    memoryKey.key.referenceSha256 in referenceSha256
            }
            rootDirectory.listFiles().orEmpty()
                .filter { file ->
                    file.isFile && referenceSha256.any { hash ->
                        file.name.startsWith("$hash--") && file.name.endsWith(CACHE_SUFFIX)
                    }
                }
                .forEach { file -> check(file.delete() || !file.exists()) }
        }
    }

    private fun encode(key: ReferenceEmbeddingCacheKey, embedding: FloatArray): ByteArray {
        val payload = ByteArrayOutputStream().also { byteStream ->
            DataOutputStream(byteStream).use { output ->
                output.writeUTF(CACHE_MAGIC)
                output.writeUTF(key.artifactSha256)
                output.writeUTF(key.referenceSha256)
                output.writeUTF(key.preprocessId)
                output.writeInt(embedding.size)
                embedding.forEach(output::writeFloat)
            }
        }.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private fun decode(
        bytes: ByteArray,
        key: ReferenceEmbeddingCacheKey,
        expectedDimension: Int,
    ): FloatArray {
        require(bytes.size > SHA_256_BYTES)
        val payload = bytes.copyOf(bytes.size - SHA_256_BYTES)
        val checksum = bytes.copyOfRange(bytes.size - SHA_256_BYTES, bytes.size)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), checksum))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readUTF() == CACHE_MAGIC)
            require(input.readUTF() == key.artifactSha256)
            require(input.readUTF() == key.referenceSha256)
            require(input.readUTF() == key.preprocessId)
            require(input.readInt() == expectedDimension)
            FloatArray(expectedDimension) { input.readFloat() }.also { embedding ->
                require(input.available() == 0)
                require(embedding.all(Float::isFinite))
                require(isUnitLength(embedding))
            }
        }
    }

    private companion object {
        val FILE_LOCK = Any()
        val MEMORY = linkedMapOf<MemoryCacheKey, FloatArray>()
        const val CACHE_MAGIC = "BYE_REF_EMBED_V1"
        const val SHA_256_BYTES = 32
        const val MIN_DIMENSION = 1
        const val MAX_DIMENSION = 65_536
    }

    private data class MemoryCacheKey(
        val canonicalRoot: String,
        val key: ReferenceEmbeddingCacheKey,
    )
}

object ReferenceEmbeddingCaches {
    const val DIRECTORY_NAME = "reference-embedding-cache-v1"

    fun openAppPrivate(filesDirectory: File): PersistentReferenceEmbeddingCache {
        require(filesDirectory.isDirectory || filesDirectory.mkdirs())
        return PersistentReferenceEmbeddingCache(filesDirectory.resolve(DIRECTORY_NAME))
    }
}

interface ReferenceEmbeddingRunner : AutoCloseable {
    fun embed(asset: ReferenceImageAsset): FloatArray
}

fun interface ReferenceEmbeddingRunnerFactory {
    fun create(context: RuntimeComponentContext): ReferenceEmbeddingRunner
}

/** Runs the signed localizer -> candidate crops -> embedder prefix for reference enrollment. */
class LocalizedReferenceEmbeddingRunner(
    private val context: RuntimeComponentContext,
) : ReferenceEmbeddingRunner {
    private val manifest = context.manifest
    private val localizerRole = "primary"
    private val cropOutput = manifest.outputs.single { it.role == OutputRole.IMAGE_TENSOR }
    private val cropRole = cropOutput.artifactRole
    private val embeddingOutput = manifest.outputs.single { it.role == OutputRole.IMAGE_FEATURES }
    private val embeddingRole = embeddingOutput.artifactRole
    private val primaryInput = manifest.primaryImageInput
    private val preprocessor = ManifestRgbLetterboxPreprocessor(
        primaryInput.toImageInputSpec(),
        ManifestRgbLetterboxPreprocessor.PixelEncodingMode.UINT8_RAW,
    )
    private val sessionsByRole: Map<String, ArtifactExecutionSession>
    private var closed = false

    init {
        require(manifest.runtimeFamily == RecipeFamily.SIMILARITY_MATCH_V1)
        require(manifest.preprocessId == NeuralReferenceRuntimeComponents.PREPROCESS_ID)
        require(setOf(localizerRole, cropRole, embeddingRole).size == 3)
        require(manifest.artifacts.single { it.role == localizerRole }.runtime == RuntimeKind.LITERT)
        require(manifest.artifacts.single { it.role == cropRole }.runtime == RuntimeKind.CLASSIC_VISION)
        require(manifest.artifacts.single { it.role == embeddingRole }.runtime == RuntimeKind.LITERT)
        sessionsByRole = listOf(localizerRole, cropRole, embeddingRole).associateWith { role ->
            val artifact = manifest.artifacts.single { it.role == role }
            val sessionContext = ArtifactSessionContext(
                artifact = artifact,
                artifactFile = context.artifactFilesByRole.getValue(role),
                inputSpecs = manifest.inputs.filter { it.artifactRole == role },
                outputSpecs = manifest.outputs.filter { it.artifactRole == role },
                numberOfThreads = context.numberOfThreads,
            )
            when (artifact.runtime) {
                RuntimeKind.LITERT -> LiteRtArtifactSession(sessionContext)
                RuntimeKind.CLASSIC_VISION -> ProminentObjectCropArtifactSession(sessionContext)
                else -> error("localized reference encoder uses unsupported runtime")
            }
        }
        requireLocalizedReferenceGraph(manifest)
    }

    override fun embed(asset: ReferenceImageAsset): FloatArray {
        check(!closed)
        val prepared = preprocessor.prepare(
            CanonicalFrame(
                sourceSequence = 0,
                monotonicTimeMillis = 0,
                capturedAtEpochMillis = null,
                width = asset.width,
                height = asset.height,
                rgb888 = asset.rgb888,
            ),
        )
        val external = manifest.inputs
            .filter { input ->
                input.role == InputRole.IMAGE &&
                    input.artifactRole in setOf(localizerRole, cropRole)
            }
            .associate { input ->
                require(input.dataType == primaryInput.dataType)
                require(input.runtimeShape == primaryInput.runtimeShape)
                require(input.layout == primaryInput.layout)
                require(input.colorSpace == primaryInput.colorSpace)
                require(input.quantization == primaryInput.quantization)
                input.endpoint to RawTensor(
                    name = input.tensorName,
                    bytes = prepared.bytes,
                    shape = prepared.shape,
                    elementType = prepared.elementType,
                )
            }
        val produced = linkedMapOf<TensorEndpoint, RawTensor>()
        listOf(localizerRole, cropRole, embeddingRole).forEach { role ->
            val inputs = manifest.inputs.filter { it.artifactRole == role }.associate { spec ->
                val binding = manifest.bindings.singleOrNull { it.targetEndpoint == spec.endpoint }
                val tensor = binding?.let { produced[it.sourceEndpoint] } ?: external[spec.endpoint]
                spec.tensorName to checkNotNull(tensor) { "localized encoder input is unavailable" }
            }
            val outputs = sessionsByRole.getValue(role).run(inputs)
            manifest.outputs.filter { it.artifactRole == role }.forEach { spec ->
                produced[spec.endpoint] = checkNotNull(outputs[spec.tensorName])
            }
        }
        val candidateEmbeddings = produced.getValue(embeddingOutput.endpoint)
            .floatValues(embeddingOutput.runtimeShape)
        val candidateCount = embeddingOutput.runtimeShape[0]
        val featureDimension = embeddingOutput.runtimeShape[1]
        require(candidateCount == ProminentObjectCropArtifactSession.CANDIDATE_COUNT)
        // Reference photos are already the user-declared target. Enroll their deterministic
        // full-frame candidate; camera inference compares that prototype with every localized
        // candidate plus its own full frame.
        return candidateEmbeddings.copyOfRange(
            (candidateCount - 1) * featureDimension,
            candidateCount * featureDimension,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        listOf(embeddingRole, cropRole, localizerRole).forEach { role ->
            runCatching { sessionsByRole.getValue(role).close() }
        }
    }
}

private fun defaultReferenceEmbeddingRunnerFactory() =
    ReferenceEmbeddingRunnerFactory(::LocalizedReferenceEmbeddingRunner)

private fun ModelPackageManifest.referenceEncoderIdentitySha256(): String {
    val canonical = buildString {
        append(preprocessId).append('\n')
        artifacts.sortedBy(ArtifactComponent::role).forEach { artifact ->
            append(artifact.role).append(':').append(artifact.sha256).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)
    return sha256(canonical)
}

internal data class NormalizedReferenceEmbedding(
    val asset: ReferenceImageAsset,
    val values: FloatArray,
) {
    init {
        require(values.isNotEmpty() && values.all(Float::isFinite))
        require(isUnitLength(values))
    }
}

/** Encodes every imported reference and averages the signed finite orientation prototypes. */
class ReferencePrototypeExternalTensorProvider(
    context: RuntimeComponentContext,
    referenceImageProvider: ReferenceImageProvider,
    private val cache: ReferenceEmbeddingCache,
    enrollmentProcessor: ReferenceEnrollmentProcessor = ReferenceEnrollmentProcessor(),
    runnerFactory: ReferenceEmbeddingRunnerFactory =
        defaultReferenceEmbeddingRunnerFactory(),
) : ManifestExternalTensorProvider {
    private val manifest = context.manifest
    private val requiredInput: InputComponent
    private val prototypeTensor: RawTensor

    init {
        require(manifest.runtimeFamily == RecipeFamily.SIMILARITY_MATCH_V1)
        require(manifest.preprocessId == NeuralReferenceRuntimeComponents.PREPROCESS_ID)
        require(manifest.postprocessId == NeuralReferenceRuntimeComponents.POSTPROCESS_ID)
        val target = context.targetProfile as? TargetProfile.ReferenceImages
            ?: error("neural reference matching requires a reference-image target")
        val boundTargets = manifest.bindings.mapTo(hashSetOf(), TensorBinding::targetEndpoint)
        requiredInput = manifest.inputs.single {
            it.role == InputRole.REFERENCE_FEATURES && it.endpoint !in boundTargets
        }
        require(requiredInput.dataType == TensorDataType.FLOAT32)
        require(requiredInput.runtimeShape.size == 2)
        val prototypeCount = requiredInput.runtimeShape.first()
        require(prototypeCount in SUPPORTED_PROTOTYPE_COUNTS)
        val embeddingInput = manifest.primaryImageInput.toImageInputSpec()
        require(prototypeCount == 1 || embeddingInput.width == embeddingInput.height) {
            "rotated reference prototypes require a square embedding input"
        }
        val featureDimension = requiredInput.runtimeShape[1]
        val encoderOutput = manifest.outputs.single { it.role == OutputRole.IMAGE_FEATURES }
        require(encoderOutput.dataType == TensorDataType.FLOAT32)
        require(encoderOutput.runtimeShape == listOf(
            ProminentObjectCropArtifactSession.CANDIDATE_COUNT,
            featureDimension,
        ))

        val enrollment = enrollmentProcessor.resolve(target, referenceImageProvider)
        require(enrollment.accepted.size in
            TargetProfile.MIN_REFERENCE_IMAGES..TargetProfile.MAX_REFERENCE_IMAGES
        )
        var runner: ReferenceEmbeddingRunner? = null
        val prototypes = try {
            fun uprightEmbedding(asset: ReferenceImageAsset): FloatArray {
                val key = ReferenceEmbeddingCacheKey(
                    artifactSha256 = manifest.referenceEncoderIdentitySha256(),
                    referenceSha256 = asset.contentSha256,
                    preprocessId = manifest.referenceAssetCacheIdentity(asset),
                )
                return cache.load(key, featureDimension) ?: run {
                    val value = l2Normalize(
                        (runner ?: runnerFactory.create(context).also { runner = it }).embed(asset),
                    )
                    require(value.size == featureDimension)
                    cache.store(key, value)
                    value
                }
            }

            val upright = enrollment.accepted.map { asset ->
                NormalizedReferenceEmbedding(asset, uprightEmbedding(asset))
            }
            referencePrototypeVariants(prototypeCount).flatMap { variant ->
                val orientedEmbeddings = upright.map { accepted ->
                    if (variant.isIdentity) {
                        accepted.values
                    } else {
                        val key = ReferenceEmbeddingCacheKey(
                            artifactSha256 = manifest.referenceEncoderIdentitySha256(),
                            referenceSha256 = accepted.asset.contentSha256,
                            preprocessId = manifest.referenceAssetCacheIdentity(accepted.asset)
                                .cacheIdentity(variant),
                        )
                        cache.load(key, featureDimension) ?: run {
                            val transformed = accepted.asset.transformedForPrototype(
                                variant = variant,
                                targetWidth = embeddingInput.width,
                                targetHeight = embeddingInput.height,
                            )
                            val value = l2Normalize(
                                (runner ?: runnerFactory.create(context).also { runner = it })
                                    .embed(transformed),
                            )
                            require(value.size == featureDimension)
                            cache.store(key, value)
                            value
                        }
                    }
                }
                l2Normalize(
                    FloatArray(featureDimension) { dimension ->
                        orientedEmbeddings.sumOf { it[dimension].toDouble() }
                            .div(orientedEmbeddings.size)
                            .toFloat()
                    },
                ).asList()
            }.toFloatArray()
        } finally {
            runner?.close()
        }
        prototypeTensor = RawTensor(
            name = requiredInput.tensorName,
            bytes = neuralFloatBytes(prototypes),
            shape = requiredInput.runtimeShape,
            elementType = TensorElementType.FLOAT32,
        )
    }

    override fun provide(context: ExternalTensorContext): Map<TensorEndpoint, RawTensor> {
        require(context.manifest === manifest || context.manifest == manifest)
        require(context.requiredInputs == listOf(requiredInput))
        return mapOf(requiredInput.endpoint to prototypeTensor)
    }

    private companion object {
        val SUPPORTED_PROTOTYPE_COUNTS = setOf(1, 4, 12)
    }
}

private data class ReferencePrototypeVariant(
    val cropPercent: Int,
    val quarterTurnsClockwise: Int,
) {
    init {
        require(cropPercent in REFERENCE_CROP_PERCENTAGES)
        require(quarterTurnsClockwise in 0..3)
    }

    val isIdentity: Boolean get() = cropPercent == 100 && quarterTurnsClockwise == 0
}

private fun referencePrototypeVariants(prototypeCount: Int): List<ReferencePrototypeVariant> = when (
    prototypeCount
) {
    1 -> listOf(ReferencePrototypeVariant(100, 0))
    4 -> (0..3).map { ReferencePrototypeVariant(100, it) }
    12 -> REFERENCE_CROP_PERCENTAGES.flatMap { crop ->
        (0..3).map { rotation -> ReferencePrototypeVariant(crop, rotation) }
    }
    else -> error("unsupported reference prototype count")
}

private fun String.cacheIdentity(variant: ReferencePrototypeVariant): String {
    if (variant.isIdentity) return this
    return buildString {
        append(this@cacheIdentity)
        if (variant.cropPercent != 100) append(".crop_").append(variant.cropPercent)
        if (variant.quarterTurnsClockwise != 0) {
            append(".rotate_").append(variant.quarterTurnsClockwise * 90)
        }
    }
}

private fun ModelPackageManifest.referenceAssetCacheIdentity(
    asset: ReferenceImageAsset,
): String = "$preprocessId.$REFERENCE_RUNTIME_DECODE_ID.${asset.width}x${asset.height}"

private fun ReferenceImageAsset.transformedForPrototype(
    variant: ReferencePrototypeVariant,
    targetWidth: Int,
    targetHeight: Int,
): ReferenceImageAsset {
    if (variant.isIdentity) return this
    val croppedWidth = (width * variant.cropPercent / 100).coerceIn(1, width)
    val croppedHeight = (height * variant.cropPercent / 100).coerceIn(1, height)
    val cropLeft = (width - croppedWidth) / 2
    val cropTop = (height - croppedHeight) / 2
    val resized = resizeRgbBilinearRegion(
        source = rgb888,
        sourceWidth = width,
        sourceHeight = height,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropWidth = croppedWidth,
        cropHeight = croppedHeight,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
    )
    val quarterTurns = variant.quarterTurnsClockwise
    if (quarterTurns == 0) {
        return copy(
            localAssetId = "$localAssetId@crop-${variant.cropPercent}",
            width = targetWidth,
            height = targetHeight,
            rgb888 = resized,
        )
    }
    val rotatedWidth = if (quarterTurns % 2 == 0) targetWidth else targetHeight
    val rotatedHeight = if (quarterTurns % 2 == 0) targetHeight else targetWidth
    val rotated = ByteArray(resized.size)
    repeat(targetHeight) { sourceY ->
        repeat(targetWidth) { sourceX ->
            val destinationX: Int
            val destinationY: Int
            when (quarterTurns) {
                1 -> {
                    destinationX = targetHeight - 1 - sourceY
                    destinationY = sourceX
                }
                2 -> {
                    destinationX = targetWidth - 1 - sourceX
                    destinationY = targetHeight - 1 - sourceY
                }
                else -> {
                    destinationX = sourceY
                    destinationY = targetWidth - 1 - sourceX
                }
            }
            val source = (sourceY * targetWidth + sourceX) * RGB_CHANNELS
            val destination = (destinationY * rotatedWidth + destinationX) * RGB_CHANNELS
            resized.copyInto(rotated, destination, source, source + RGB_CHANNELS)
        }
    }
    return copy(
        localAssetId = buildString {
            append(localAssetId)
            if (variant.cropPercent != 100) append("@crop-").append(variant.cropPercent)
            append("@rotate-").append(quarterTurns * 90)
        },
        width = rotatedWidth,
        height = rotatedHeight,
        rgb888 = rotated,
    )
}

/**
 * Resizes directly from a canonical source crop into the model input. Avoiding an intermediate
 * crop also keeps the bounded 1024 px reference path from duplicating several MiB per prototype.
 */
internal fun resizeRgbBilinearRegion(
    source: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): ByteArray {
    require(source.size.toLong() == sourceWidth.toLong() * sourceHeight * RGB_CHANNELS)
    require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0)
    require(cropLeft + cropWidth <= sourceWidth && cropTop + cropHeight <= sourceHeight)
    require(targetWidth > 0 && targetHeight > 0)
    val output = ByteArray(targetWidth * targetHeight * RGB_CHANNELS)
    repeat(targetHeight) { targetY ->
        val sourceY = cropTop + ((targetY + 0.5) * cropHeight / targetHeight - 0.5)
            .coerceIn(0.0, (cropHeight - 1).toDouble())
        val y0 = floor(sourceY).toInt()
        val y1 = (y0 + 1).coerceAtMost(cropTop + cropHeight - 1)
        val fy = sourceY - y0
        repeat(targetWidth) { targetX ->
            val sourceX = cropLeft + ((targetX + 0.5) * cropWidth / targetWidth - 0.5)
                .coerceIn(0.0, (cropWidth - 1).toDouble())
            val x0 = floor(sourceX).toInt()
            val x1 = (x0 + 1).coerceAtMost(cropLeft + cropWidth - 1)
            val fx = sourceX - x0
            repeat(RGB_CHANNELS) { channel ->
                fun sample(x: Int, y: Int): Int =
                    source[(y * sourceWidth + x) * RGB_CHANNELS + channel].toInt() and 0xff
                val top = sample(x0, y0) * (1.0 - fx) + sample(x1, y0) * fx
                val bottom = sample(x0, y1) * (1.0 - fx) + sample(x1, y1) * fx
                output[(targetY * targetWidth + targetX) * RGB_CHANNELS + channel] =
                    (top * (1.0 - fy) + bottom * fy)
                        .roundToInt()
                        .coerceIn(0, 255)
                        .toByte()
            }
        }
    }
    return output
}

/** Signed class-agnostic top-three object candidates plus a full-frame fallback. */
class ProminentObjectCropArtifactSession(
    context: ArtifactSessionContext,
) : ArtifactExecutionSession {
    override val artifactRole: String = context.artifact.role
    private val imageInput = context.inputSpecs.single { it.role == InputRole.IMAGE }
    private val boxesInput = context.inputSpecs.single { it.role == InputRole.DETECTION_BOXES }
    private val classesInput = context.inputSpecs.single { it.role == InputRole.DETECTION_CLASSES }
    private val scoresInput = context.inputSpecs.single { it.role == InputRole.DETECTION_SCORES }
    private val countInput = context.inputSpecs.single { it.role == InputRole.DETECTION_COUNT }
    private val output = context.outputSpecs.single { it.role == OutputRole.IMAGE_TENSOR }
    private val scoreThreshold: Float
    private val paddingFraction: Float
    private var closed = false

    init {
        require(context.artifact.runtime == RuntimeKind.CLASSIC_VISION)
        require(context.inputSpecs.size == 5 && context.outputSpecs.size == 1)
        require(imageInput.dataType == TensorDataType.UINT8)
        require(imageInput.layout == TensorLayout.NHWC && imageInput.colorSpace == ColorSpace.RGB)
        require(imageInput.runtimeShape.size == 4 && imageInput.runtimeShape.first() == 1 &&
            imageInput.runtimeShape.last() == RGB_CHANNELS
        )
        val maximum = boxesInput.runtimeShape.getOrNull(1)
        require(maximum != null && boxesInput.runtimeShape == listOf(1, maximum, 4))
        require(classesInput.runtimeShape == listOf(1, maximum))
        require(scoresInput.runtimeShape == listOf(1, maximum))
        require(countInput.runtimeShape == listOf(1))
        require(listOf(boxesInput, classesInput, scoresInput, countInput).all {
            it.dataType == TensorDataType.FLOAT32
        })
        require(output.dataType == TensorDataType.FLOAT32)
        require(output.runtimeShape.size == 4 && output.runtimeShape.first() == CANDIDATE_COUNT &&
            output.runtimeShape.last() == RGB_CHANNELS
        )
        val config = requireObjectCropArtifact(context.artifactFile)
        scoreThreshold = config.first
        paddingFraction = config.second
    }

    override fun run(inputsByTensorName: Map<String, RawTensor>): Map<String, RawTensor> {
        check(!closed)
        require(inputsByTensorName.keys == contextInputNames())
        val image = inputsByTensorName.getValue(imageInput.tensorName)
        require(image.shape == imageInput.runtimeShape && image.elementType == TensorElementType.UINT8)
        val boxes = inputsByTensorName.getValue(boxesInput.tensorName)
            .floatValues(boxesInput.runtimeShape)
        inputsByTensorName.getValue(classesInput.tensorName)
            .floatValues(classesInput.runtimeShape)
        val scores = inputsByTensorName.getValue(scoresInput.tensorName)
            .floatValues(scoresInput.runtimeShape)
        val countValue = inputsByTensorName.getValue(countInput.tensorName)
            .floatValues(countInput.runtimeShape).single()
        val count = countValue.roundToInt()
        require(kotlin.math.abs(countValue - count) <= COUNT_EPSILON)
        require(count in 0..scores.size)
        val sourceHeight = imageInput.runtimeShape[1]
        val sourceWidth = imageInput.runtimeShape[2]
        require((0 until count).all { scores[it].isFinite() })
        val selected = (0 until count)
            .filter { scores[it] >= scoreThreshold }
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .take(LOCALIZED_CANDIDATE_COUNT)
        val normalizedCrops = selected.map { selectedIndex ->
            val offset = selectedIndex * BOX_COORDINATES
            val top = boxes[offset].coerceIn(0f, 1f)
            val left = boxes[offset + 1].coerceIn(0f, 1f)
            val bottom = boxes[offset + 2].coerceIn(0f, 1f)
            val right = boxes[offset + 3].coerceIn(0f, 1f)
            require(listOf(top, left, bottom, right).all(Float::isFinite))
            require(bottom > top && right > left)
            floatArrayOf(
                (top - (bottom - top) * paddingFraction).coerceIn(0f, 1f),
                (left - (right - left) * paddingFraction).coerceIn(0f, 1f),
                (bottom + (bottom - top) * paddingFraction).coerceIn(0f, 1f),
                (right + (right - left) * paddingFraction).coerceIn(0f, 1f),
            )
        }.toMutableList().apply {
            // Keep a fixed signed batch. Missing detector candidates are valid and use the whole
            // frame; the final row is always the whole frame, independent of detector ordering.
            while (size < CANDIDATE_COUNT) add(FULL_FRAME_CROP)
        }
        val outputHeight = output.runtimeShape[1]
        val outputWidth = output.runtimeShape[2]
        val floatBytes = ByteBuffer.allocate(
            CANDIDATE_COUNT * outputHeight * outputWidth * RGB_CHANNELS * Float.SIZE_BYTES,
        ).order(ByteOrder.nativeOrder()).also { buffer ->
            normalizedCrops.forEach { normalizedCrop ->
                val (paddedTop, paddedLeft, paddedBottom, paddedRight) = normalizedCrop
                val cropLeft = floor(paddedLeft * sourceWidth).toInt()
                    .coerceIn(0, sourceWidth - 1)
                val cropTop = floor(paddedTop * sourceHeight).toInt()
                    .coerceIn(0, sourceHeight - 1)
                val cropRight = ceil(paddedRight * sourceWidth).toInt()
                    .coerceIn(cropLeft + 1, sourceWidth)
                val cropBottom = ceil(paddedBottom * sourceHeight).toInt()
                    .coerceIn(cropTop + 1, sourceHeight)
                val resized = resizeRgbBilinearRegion(
                    source = image.bytes,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    cropLeft = cropLeft,
                    cropTop = cropTop,
                    cropWidth = cropRight - cropLeft,
                    cropHeight = cropBottom - cropTop,
                    targetWidth = outputWidth,
                    targetHeight = outputHeight,
                )
                resized.forEach { value -> buffer.putFloat((value.toInt() and 0xff) / 255f) }
            }
        }.array()
        return mapOf(
            output.tensorName to RawTensor(
                name = output.tensorName,
                bytes = floatBytes,
                shape = output.runtimeShape,
                elementType = TensorElementType.FLOAT32,
            ),
        )
    }

    override fun close() {
        closed = true
    }

    private fun contextInputNames() = setOf(
        imageInput.tensorName,
        boxesInput.tensorName,
        classesInput.tensorName,
        scoresInput.tensorName,
        countInput.tensorName,
    )

    private fun requireObjectCropArtifact(file: File): Pair<Float, Float> {
        require(file.isFile && file.length() in 1..MAX_CROP_ARTIFACT_BYTES)
        val root = file.reader(Charsets.UTF_8).use(JsonParser::parseReader).asJsonObject
        require(root.keySet() == CROP_ARTIFACT_FIELDS)
        require(root.getAsJsonPrimitive("schema_version").asString == "1.0")
        require(root.getAsJsonPrimitive("runtime_family").asString == CROP_RUNTIME_FAMILY)
        require(root.getAsJsonPrimitive("selection").asString == "top_three_plus_full_frame_v2")
        require(root.getAsJsonPrimitive("box_encoding").asString ==
            "ymin_xmin_ymax_xmax_normalized_v1"
        )
        val threshold = root.getAsJsonPrimitive("score_threshold").asFloat
        val padding = root.getAsJsonPrimitive("padding_fraction").asFloat
        require(threshold.isFinite() && threshold in 0f..1f)
        require(padding.isFinite() && padding in 0f..MAX_PADDING_FRACTION)
        return threshold to padding
    }

    companion object {
        const val CROP_RUNTIME_FAMILY = "prominent_object_candidates_v2"
        const val CANDIDATE_COUNT = 4
        private const val LOCALIZED_CANDIDATE_COUNT = CANDIDATE_COUNT - 1
        private const val RGB_CHANNELS = 3
        private const val BOX_COORDINATES = 4
        private const val COUNT_EPSILON = 1e-4f
        private const val MAX_PADDING_FRACTION = 0.5f
        private const val MAX_CROP_ARTIFACT_BYTES = 4_096L
        private val CROP_ARTIFACT_FIELDS = setOf(
            "schema_version",
            "runtime_family",
            "selection",
            "box_encoding",
            "score_threshold",
            "padding_fraction",
        )
        private val FULL_FRAME_CROP = floatArrayOf(0f, 0f, 1f, 1f)
    }
}

/** Manifest-graph head returning the best candidate-to-prototype cosine. */
class PrototypeCosineArtifactSession(
    context: ArtifactSessionContext,
) : ArtifactExecutionSession {
    override val artifactRole: String = context.artifact.role
    private val imageInput = context.inputSpecs.single { it.role == InputRole.IMAGE_FEATURES }
    private val referenceInput = context.inputSpecs.single { it.role == InputRole.REFERENCE_FEATURES }
    private val output = context.outputSpecs.single { it.role == OutputRole.SIMILARITY_SCORES }
    private var closed = false

    init {
        require(context.artifact.runtime == RuntimeKind.CLASSIC_VISION)
        require(context.artifact.mediaType == ArtifactMediaType.CLASSIC_VISION_JSON)
        require(context.inputSpecs.size == 2 && context.outputSpecs.size == 1)
        require(imageInput.dataType == TensorDataType.FLOAT32)
        require(referenceInput.dataType == TensorDataType.FLOAT32)
        require(output.dataType == TensorDataType.FLOAT32)
        require(imageInput.runtimeShape.size == 2 &&
            imageInput.runtimeShape.first() == ProminentObjectCropArtifactSession.CANDIDATE_COUNT
        )
        require(referenceInput.runtimeShape.size == 2)
        require(referenceInput.runtimeShape.first() in SUPPORTED_PROTOTYPE_COUNTS)
        require(imageInput.runtimeShape[1] == referenceInput.runtimeShape[1])
        require(output.runtimeShape == listOf(1, 1))
        requirePrototypeCosineArtifact(context.artifactFile)
    }

    override fun run(inputsByTensorName: Map<String, RawTensor>): Map<String, RawTensor> {
        check(!closed)
        require(inputsByTensorName.keys == setOf(imageInput.tensorName, referenceInput.tensorName))
        val image = checkNotNull(inputsByTensorName[imageInput.tensorName])
            .floatValues(imageInput.runtimeShape)
        val reference = checkNotNull(inputsByTensorName[referenceInput.tensorName])
            .floatValues(referenceInput.runtimeShape)
        val candidateCount = imageInput.runtimeShape[0]
        val featureDimension = imageInput.runtimeShape[1]
        val prototypeCount = referenceInput.runtimeShape[0]
        var bestCosine = -1.0
        repeat(candidateCount) { candidateIndex ->
            var imageEnergy = 0.0
            repeat(featureDimension) { featureIndex ->
                val value = image[candidateIndex * featureDimension + featureIndex]
                imageEnergy += value * value
            }
            require(imageEnergy > MIN_NORM_SQUARED)
            repeat(prototypeCount) { prototypeIndex ->
                var dot = 0.0
                var referenceEnergy = 0.0
                repeat(featureDimension) { featureIndex ->
                    val imageValue = image[candidateIndex * featureDimension + featureIndex]
                    val referenceValue = reference[prototypeIndex * featureDimension + featureIndex]
                    dot += imageValue * referenceValue
                    referenceEnergy += referenceValue * referenceValue
                }
                require(referenceEnergy > MIN_NORM_SQUARED)
                bestCosine = maxOf(
                    bestCosine,
                    (dot / sqrt(imageEnergy * referenceEnergy)).coerceIn(-1.0, 1.0),
                )
            }
        }
        return mapOf(
            output.tensorName to RawTensor(
                output.tensorName,
                neuralFloatBytes(floatArrayOf(bestCosine.toFloat())),
                output.runtimeShape,
                TensorElementType.FLOAT32,
            ),
        )
    }

    override fun close() {
        closed = true
    }

    private fun requirePrototypeCosineArtifact(file: File) {
        require(file.isFile && file.length() in 1..MAX_HEAD_ARTIFACT_BYTES)
        val root = file.reader(Charsets.UTF_8).use(JsonParser::parseReader).asJsonObject
        require(root.keySet() == HEAD_ARTIFACT_FIELDS)
        require(root.getAsJsonPrimitive("schema_version").asString == "1.0")
        require(root.getAsJsonPrimitive("runtime_family").asString == HEAD_RUNTIME_FAMILY)
    }

    companion object {
        const val HEAD_RUNTIME_FAMILY = "l2_prototype_cosine_candidates_v2"
        private const val MAX_HEAD_ARTIFACT_BYTES = 4_096L
        private const val MIN_NORM_SQUARED = 1e-12
        private val SUPPORTED_PROTOTYPE_COUNTS = setOf(1, 4, 12)
        private val HEAD_ARTIFACT_FIELDS = setOf("schema_version", "runtime_family")
    }
}

/** Finite family registrations. Package IDs and model vendors are never inputs to dispatch. */
object NeuralReferenceRuntimeComponents {
    const val PREPROCESS_ID = "class_agnostic_localize_letterbox_multi_crop_rgb_v3"
    const val POSTPROCESS_ID = "similarity_logit_state_v1"

    fun preprocessorRegistration(
        referenceImageProvider: ReferenceImageProvider,
        cache: ReferenceEmbeddingCache,
        runnerFactory: ReferenceEmbeddingRunnerFactory = defaultReferenceEmbeddingRunnerFactory(),
    ) = PreprocessorRegistration(
        preprocessId = PREPROCESS_ID,
        recipeFamilies = setOf(RecipeFamily.SIMILARITY_MATCH_V1),
        factory = ManifestPreprocessorFactory { context ->
            requireLocalizedReferenceGraph(context.manifest)
            ManifestRgbLetterboxPreprocessor(
                context.manifest.primaryImageInput.toImageInputSpec(),
                ManifestRgbLetterboxPreprocessor.PixelEncodingMode.UINT8_RAW,
            )
        },
        additionalExternalInputRoles = setOf(InputRole.REFERENCE_FEATURES),
        externalTensorProviderFactory = ManifestExternalTensorProviderFactory { context ->
            ReferencePrototypeExternalTensorProvider(
                context,
                referenceImageProvider,
                cache,
                runnerFactory = runnerFactory,
            )
        },
    )

    fun artifactSessionRegistration() = ArtifactSessionRegistration(
        runtimeKind = RuntimeKind.CLASSIC_VISION,
        factory = ManifestArtifactSessionFactory { context ->
            when (classicVisionRuntimeFamily(context.artifactFile)) {
                PrototypeCosineArtifactSession.HEAD_RUNTIME_FAMILY ->
                    PrototypeCosineArtifactSession(context)
                ProminentObjectCropArtifactSession.CROP_RUNTIME_FAMILY ->
                    ProminentObjectCropArtifactSession(context)
                else -> error("unsupported signed classic-vision runtime family")
            }
        },
    )
}

internal fun requireLocalizedReferenceGraph(manifest: ModelPackageManifest) {
    require(manifest.preprocessId == NeuralReferenceRuntimeComponents.PREPROCESS_ID)
    val primaryImage = manifest.primaryImageInput
    require(primaryImage.dataType == TensorDataType.UINT8)
    val detectionOutputs = manifest.outputs.filter { it.artifactRole == "primary" }
    require(detectionOutputs.mapTo(linkedSetOf(), OutputComponent::role) == setOf(
        OutputRole.DETECTION_BOXES,
        OutputRole.DETECTION_CLASSES,
        OutputRole.DETECTION_SCORES,
        OutputRole.DETECTION_COUNT,
    ))
    val cropOutput = manifest.outputs.single { it.role == OutputRole.IMAGE_TENSOR }
    require(cropOutput.runtimeShape.size == 4)
    require(cropOutput.runtimeShape[0] == ProminentObjectCropArtifactSession.CANDIDATE_COUNT)
    val cropInputs = manifest.inputs.filter { it.artifactRole == cropOutput.artifactRole }
    require(cropInputs.mapTo(linkedSetOf(), InputComponent::role) == setOf(
        InputRole.IMAGE,
        InputRole.DETECTION_BOXES,
        InputRole.DETECTION_CLASSES,
        InputRole.DETECTION_SCORES,
        InputRole.DETECTION_COUNT,
    ))
    val imageFeatureOutput = manifest.outputs.single { it.role == OutputRole.IMAGE_FEATURES }
    val embedderInputs = manifest.inputs.filter { it.artifactRole == imageFeatureOutput.artifactRole }
    require(embedderInputs.single().role == InputRole.IMAGE_TENSOR)
    require(embedderInputs.single().runtimeShape == cropOutput.runtimeShape)
    require(imageFeatureOutput.runtimeShape.size == 2)
    require(imageFeatureOutput.runtimeShape[0] ==
        ProminentObjectCropArtifactSession.CANDIDATE_COUNT
    )
    val headInput = manifest.inputs.single { it.role == InputRole.IMAGE_FEATURES }
    require(headInput.artifactRole != imageFeatureOutput.artifactRole)
    require(headInput.runtimeShape == imageFeatureOutput.runtimeShape)
    val referenceInput = manifest.inputs.single { it.role == InputRole.REFERENCE_FEATURES }
    require(referenceInput.runtimeShape.size == 2)
    require(referenceInput.runtimeShape[1] == imageFeatureOutput.runtimeShape[1])
    val requiredBindings = buildSet {
        detectionOutputs.forEach { output ->
            add(output.endpoint to cropInputs.single { it.role.name == output.role.name }.endpoint)
        }
        add(cropOutput.endpoint to embedderInputs.single().endpoint)
        add(imageFeatureOutput.endpoint to headInput.endpoint)
    }
    require(manifest.bindings.mapTo(linkedSetOf()) {
        it.sourceEndpoint to it.targetEndpoint
    } == requiredBindings)
    val frameRoots = manifest.inputs.filter { input ->
        input.role == InputRole.IMAGE && manifest.bindings.none { it.targetEndpoint == input.endpoint }
    }
    require(frameRoots.size == 2)
    frameRoots.forEach { input ->
        require(input.dataType == primaryImage.dataType)
        require(input.runtimeShape == primaryImage.runtimeShape)
        require(input.layout == primaryImage.layout)
        require(input.colorSpace == primaryImage.colorSpace)
        require(input.quantization == primaryImage.quantization)
    }
}

private fun classicVisionRuntimeFamily(file: File): String {
    require(file.isFile && file.length() in 1..4_096L)
    return file.reader(Charsets.UTF_8).use(JsonParser::parseReader).asJsonObject
        .getAsJsonPrimitive("runtime_family").asString
}

private fun RawTensor.floatValues(expectedShape: List<Int>): FloatArray {
    require(shape == expectedShape)
    require(elementType == TensorElementType.FLOAT32)
    require(bytes.size == expectedShape.checkedElementCount() * Float.SIZE_BYTES)
    return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).let { buffer ->
        FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }.also { values ->
            require(values.all(Float::isFinite))
        }
    }
}

private fun l2Normalize(values: FloatArray): FloatArray {
    require(values.isNotEmpty() && values.all(Float::isFinite))
    val energy = values.sumOf { value -> value.toDouble() * value }
    require(energy > MIN_NORMALIZATION_ENERGY)
    val norm = sqrt(energy).toFloat()
    return FloatArray(values.size) { values[it] / norm }
}

private fun isUnitLength(values: FloatArray): Boolean {
    val energy = values.sumOf { value -> value.toDouble() * value }
    return energy.isFinite() && kotlin.math.abs(energy - 1.0) <= UNIT_LENGTH_EPSILON
}

private fun neuralFloatBytes(values: FloatArray): ByteArray = ByteBuffer
    .allocate(values.size * Float.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .also { buffer -> values.forEach(buffer::putFloat) }
    .array()

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val CACHE_KEY_VERSION = "reference_embedding_cache_key_v1"
private const val CACHE_SUFFIX = ".refembed"
private const val REFERENCE_RUNTIME_DECODE_ID = "upright_max1024_v1"
private const val MIN_NORMALIZATION_ENERGY = 1e-12
private const val UNIT_LENGTH_EPSILON = 1e-4
private const val RGB_CHANNELS = 3
private val REFERENCE_CROP_PERCENTAGES = listOf(100, 75, 50)
private val SHA_256 = Regex("^[0-9a-f]{64}$")
private val PREPROCESS_ID = Regex("^[a-z][a-z0-9_.-]{0,127}$")
