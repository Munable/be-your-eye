package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Detection
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.domain.parseStructuredReading
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

data class ResolvedClassMap(
    val identity: String,
    val sha256: String,
    val labelsByRawClassId: Map<Int, String>,
    val targetsByRawClassId: Map<Int, ClassMapTarget> = emptyMap(),
) {
    init {
        require(identity.isNotBlank())
        require(sha256.matches(Regex("^[0-9a-f]{64}$")))
        require(labelsByRawClassId.isNotEmpty())
        require(labelsByRawClassId.keys.all { it >= 0 })
        require(labelsByRawClassId.values.all { it.isNotBlank() })
        require(targetsByRawClassId.keys.all { it >= 0 })
        require(targetsByRawClassId.values.all { it.rawClassId >= 0 })
        require(targetsByRawClassId.all { (rawId, target) -> rawId == target.rawClassId })
        require(
            targetsByRawClassId.values.map(ClassMapTarget::targetId).distinct().size ==
                targetsByRawClassId.size,
        )
    }
}

/** One signed, finite class-map row. It is the only bridge from model IDs to product IDs. */
data class ClassMapTarget(
    val rawClassId: Int,
    val targetId: String,
    val labelZhCn: String,
    val labelEn: String,
    val aliases: Set<String> = emptySet(),
) {
    init {
        require(rawClassId >= 0)
        require(targetId.matches(Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")))
        require(labelZhCn.isNotBlank() && labelZhCn.length <= 40)
        require(labelEn.isNotBlank() && labelEn.length <= 40)
        require(aliases.all(String::isNotBlank))
    }
}

/** Associated class-map files are verified outside the model artifact and resolved by hash. */
fun interface ClassMapProvider {
    fun resolve(spec: ClassMapSpec): ResolvedClassMap?
}

object RejectAllClassMaps : ClassMapProvider {
    override fun resolve(spec: ClassMapSpec): ResolvedClassMap? = null
}

/**
 * Generic single-image LiteRT executor. Tensor names, shapes, data types and quantization come
 * exclusively from the package Manifest; no package ID or model vendor appears in this class.
 */
class ManifestLiteRtBackend(
    context: RuntimeComponentContext,
) : InferenceBackend, AutoCloseable {
    private val manifest = context.manifest
    private val inputSpec = manifest.primaryImageInput.toImageInputSpec()
    private val outputSpecs = manifest.finalOutputs().map(OutputComponent::toTensorSpec)
        .sortedBy(OutputTensorSpec::index)
    private val interpreter = Interpreter(
        context.artifactFile,
        Interpreter.Options()
            .setNumThreads(context.numberOfThreads)
            .setUseXNNPACK(true),
    )
    private val inputBuffer = ByteBuffer.allocateDirect(
        inputSpec.runtimeShape.elementCount() * inputSpec.dataType.byteCount,
    ).order(ByteOrder.nativeOrder())
    private val outputBuffers = outputSpecs.associateWith { spec ->
        ByteBuffer.allocateDirect(
            spec.runtimeShape.elementCount() * spec.dataType.byteCount,
        ).order(ByteOrder.nativeOrder())
    }
    private val inputOrdinal: Int
    private val outputOrdinals: Map<OutputTensorSpec, Int>

    init {
        interpreter.allocateTensors()
        require(interpreter.inputTensorCount == 1) {
            "manifest_litert_v1 supports exactly one image input"
        }
        inputOrdinal = interpreter.getInputIndex(inputSpec.tensorName)
        val inputTensor = interpreter.getInputTensor(inputOrdinal)
        require(inputTensor.index() == inputSpec.tensorIndex) { "input tensor index mismatch" }
        require(inputTensor.shape().toList() == inputSpec.runtimeShape) { "input shape mismatch" }
        require(inputTensor.dataType() == inputSpec.dataType.toLiteRtDataType()) {
            "input data type mismatch"
        }
        validateQuantization(inputTensor.quantizationParams().scale, inputTensor.quantizationParams().zeroPoint)

        require(interpreter.outputTensorCount == outputSpecs.size) { "output tensor count mismatch" }
        outputOrdinals = outputSpecs.associateWith { spec ->
            val ordinal = interpreter.getOutputIndex(spec.name)
            val tensor = interpreter.getOutputTensor(ordinal)
            require(tensor.index() == spec.index) { "output tensor index mismatch: ${spec.name}" }
            require(tensor.shape().toList() == spec.runtimeShape) {
                "output tensor shape mismatch: ${spec.name}"
            }
            require(tensor.dataType() == spec.dataType.toLiteRtDataType()) {
                "output tensor data type mismatch: ${spec.name}"
            }
            ordinal
        }
    }

    override fun infer(input: PreparedInput): RawTensorOutput {
        require(input.shape == inputSpec.runtimeShape) { "prepared input shape mismatch" }
        require(input.elementType == inputSpec.dataType.toElementType()) {
            "prepared input data type mismatch"
        }
        val expectedInputBytes = inputSpec.runtimeShape.elementCount() * input.elementType.byteCount
        require(input.bytes.size == expectedInputBytes) { "prepared input byte count mismatch" }
        inputBuffer.clear()
        inputBuffer.put(input.bytes)
        inputBuffer.rewind()
        outputBuffers.values.forEach(ByteBuffer::clear)
        interpreter.runForMultipleInputsOutputs(
            arrayOf(inputBuffer),
            outputBuffers.entries.associate { (spec, buffer) ->
                checkNotNull(outputOrdinals[spec]) to buffer
            },
        )
        return RawTensorOutput(
            outputSpecs.map { spec ->
                val buffer = checkNotNull(outputBuffers[spec]).also(ByteBuffer::rewind)
                RawTensor(
                    name = spec.name,
                    bytes = ByteArray(buffer.remaining()).also(buffer::get),
                    shape = spec.runtimeShape,
                    elementType = spec.dataType.toElementType(),
                )
            },
        )
    }

    override fun close() {
        interpreter.close()
    }

    private fun validateQuantization(actualScale: Float, actualZeroPoint: Int) {
        when (inputSpec.quantization.mode) {
            QuantizationMode.PER_TENSOR -> {
                val expectedScale = checkNotNull(inputSpec.quantization.scale)
                require(abs(actualScale.toDouble() - expectedScale) <= QUANTIZATION_EPSILON) {
                    "input quantization scale mismatch"
                }
                require(actualZeroPoint == inputSpec.quantization.zeroPoint) {
                    "input quantization zero point mismatch"
                }
            }
            QuantizationMode.NONE -> {
                require(actualScale == 0f && actualZeroPoint == 0) {
                    "unexpected input quantization"
                }
            }
        }
    }

    private companion object {
        const val QUANTIZATION_EPSILON = 1e-6
    }
}

/** Manifest-sized nearest-neighbour letterbox; uint8/int8/float preprocessing is ID-defined. */
class ManifestRgbLetterboxPreprocessor(
    private val inputSpec: InputSpec,
    private val mode: PixelEncodingMode,
) : InputPreprocessor {
    private val output = mode.allocate(inputSpec.width * inputSpec.height * RGB_CHANNELS)

    init {
        require(inputSpec.layout == TensorLayout.NHWC) { "only NHWC is supported" }
        require(inputSpec.colorSpace == ColorSpace.RGB) { "only RGB is supported" }
        require(inputSpec.channels == RGB_CHANNELS)
        require(inputSpec.dataType == mode.tensorDataType)
    }

    override fun prepare(frame: CanonicalFrame): PreparedInput {
        val inputWidth = inputSpec.width
        val inputHeight = inputSpec.height
        val scale = minOf(
            inputWidth.toDouble() / frame.width,
            inputHeight.toDouble() / frame.height,
        )
        val targetWidth = (frame.width * scale).toInt().coerceIn(1, inputWidth)
        val targetHeight = (frame.height * scale).toInt().coerceIn(1, inputHeight)
        val offsetX = (inputWidth - targetWidth) / 2
        val offsetY = (inputHeight - targetHeight) / 2
        // Runtime.process is synchronized, so one scratch tensor is safe and avoids allocating
        // a full input tensor for every camera frame. Padding is reset because the letterboxed
        // area is not written by the source loop on non-square frames.
        mode.reset(output)

        repeat(targetHeight) { destinationY ->
            val sourceY = (destinationY.toLong() * frame.height / targetHeight)
                .toInt()
                .coerceAtMost(frame.height - 1)
            repeat(targetWidth) { destinationX ->
                val sourceX = (destinationX.toLong() * frame.width / targetWidth)
                    .toInt()
                    .coerceAtMost(frame.width - 1)
                val sourceOffset = (sourceY * frame.width + sourceX) * RGB_CHANNELS
                val destinationPixel = (destinationY + offsetY) * inputWidth + destinationX + offsetX
                repeat(RGB_CHANNELS) { channel ->
                    mode.write(
                        output = output,
                        elementIndex = destinationPixel * RGB_CHANNELS + channel,
                        unsignedPixel = frame.rgb888[sourceOffset + channel].toInt() and 0xff,
                    )
                }
            }
        }

        return PreparedInput(
            bytes = output,
            shape = inputSpec.runtimeShape,
            elementType = inputSpec.dataType.toElementType(),
            transform = LetterboxTransform(
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                scaleX = targetWidth.toFloat() / frame.width,
                scaleY = targetHeight.toFloat() / frame.height,
                offsetX = offsetX.toFloat(),
                offsetY = offsetY.toFloat(),
            ),
        )
    }

    enum class PixelEncodingMode(val tensorDataType: TensorDataType) {
        UINT8_RAW(TensorDataType.UINT8) {
            override fun allocate(elementCount: Int): ByteArray = ByteArray(elementCount)
            override fun reset(output: ByteArray) = output.fill(0)
            override fun write(output: ByteArray, elementIndex: Int, unsignedPixel: Int) {
                output[elementIndex] = unsignedPixel.toByte()
            }
        },
        INT8_MINUS_128(TensorDataType.INT8) {
            override fun allocate(elementCount: Int): ByteArray = ByteArray(elementCount) {
                Byte.MIN_VALUE
            }
            override fun reset(output: ByteArray) = output.fill(Byte.MIN_VALUE)
            override fun write(output: ByteArray, elementIndex: Int, unsignedPixel: Int) {
                output[elementIndex] = (unsignedPixel - 128).toByte()
            }
        },
        FLOAT32_ZERO_TO_ONE(TensorDataType.FLOAT32) {
            override fun allocate(elementCount: Int): ByteArray = ByteArray(
                elementCount * Float.SIZE_BYTES,
            )
            override fun reset(output: ByteArray) = output.fill(0)
            override fun write(output: ByteArray, elementIndex: Int, unsignedPixel: Int) {
                ByteBuffer.wrap(output)
                    .order(ByteOrder.nativeOrder())
                    .putFloat(elementIndex * Float.SIZE_BYTES, unsignedPixel / 255f)
            }
        };

        abstract fun allocate(elementCount: Int): ByteArray
        abstract fun reset(output: ByteArray)
        abstract fun write(output: ByteArray, elementIndex: Int, unsignedPixel: Int)
    }

    private companion object {
        const val RGB_CHANNELS = 3
    }
}

/** Direct RGB bilinear resize for object detectors; the source aspect ratio is not preserved. */
class ManifestObjectRgbDirectResizePreprocessor(
    private val inputSpec: InputSpec,
) : InputPreprocessor {
    private val output = ByteArray(inputSpec.width * inputSpec.height * RGB_CHANNELS)
    private var cachedMapping: BilinearResizeMapping? = null

    init {
        require(inputSpec.layout == TensorLayout.NHWC) { "only NHWC is supported" }
        require(inputSpec.colorSpace == ColorSpace.RGB) { "only RGB is supported" }
        require(inputSpec.channels == RGB_CHANNELS)
        require(inputSpec.dataType == TensorDataType.UINT8)
    }

    override fun prepare(frame: CanonicalFrame): PreparedInput {
        val mapping = mappingFor(frame.width, frame.height)
        val source = frame.rgb888
        var destinationY = 0
        var destinationOffset = 0
        while (destinationY < inputSpec.height) {
            val yWeight = mapping.yWeights[destinationY]
            val inverseYWeight = WEIGHT_ONE - yWeight
            val sourceRow0 = mapping.y0Rows[destinationY]
            val sourceRow1 = mapping.y1Rows[destinationY]
            var destinationX = 0
            while (destinationX < inputSpec.width) {
                val xWeight = mapping.xWeights[destinationX]
                val inverseXWeight = WEIGHT_ONE - xWeight
                val weight00 = inverseXWeight * inverseYWeight
                val weight10 = xWeight * inverseYWeight
                val weight01 = inverseXWeight * yWeight
                val weight11 = xWeight * yWeight
                val sourceX0 = mapping.x0Offsets[destinationX]
                val sourceX1 = mapping.x1Offsets[destinationX]
                val source00 = sourceRow0 + sourceX0
                val source10 = sourceRow0 + sourceX1
                val source01 = sourceRow1 + sourceX0
                val source11 = sourceRow1 + sourceX1
                var channel = 0
                while (channel < RGB_CHANNELS) {
                    val weighted =
                        (source[source00 + channel].toInt() and MAX_PIXEL_VALUE) * weight00 +
                            (source[source10 + channel].toInt() and MAX_PIXEL_VALUE) * weight10 +
                            (source[source01 + channel].toInt() and MAX_PIXEL_VALUE) * weight01 +
                            (source[source11 + channel].toInt() and MAX_PIXEL_VALUE) * weight11
                    output[destinationOffset + channel] =
                        ((weighted + WEIGHT_ROUNDING) ushr WEIGHT_SUM_BITS).toByte()
                    channel += 1
                }
                destinationOffset += RGB_CHANNELS
                destinationX += 1
            }
            destinationY += 1
        }

        return PreparedInput(
            bytes = output,
            shape = inputSpec.runtimeShape,
            elementType = TensorElementType.UINT8,
            transform = LetterboxTransform(
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                inputWidth = inputSpec.width,
                inputHeight = inputSpec.height,
                scaleX = inputSpec.width.toFloat() / frame.width,
                scaleY = inputSpec.height.toFloat() / frame.height,
                offsetX = 0f,
                offsetY = 0f,
            ),
        )
    }

    private fun mappingFor(sourceWidth: Int, sourceHeight: Int): BilinearResizeMapping {
        val cached = cachedMapping
        if (cached != null &&
            cached.sourceWidth == sourceWidth && cached.sourceHeight == sourceHeight
        ) {
            return cached
        }
        return BilinearResizeMapping(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            x0Offsets = IntArray(inputSpec.width),
            x1Offsets = IntArray(inputSpec.width),
            xWeights = IntArray(inputSpec.width),
            y0Rows = IntArray(inputSpec.height),
            y1Rows = IntArray(inputSpec.height),
            yWeights = IntArray(inputSpec.height),
        ).also { mapping ->
            var destinationX = 0
            while (destinationX < inputSpec.width) {
                val coordinate = halfPixelCoordinate(
                    destination = destinationX,
                    destinationSize = inputSpec.width,
                    sourceSize = sourceWidth,
                )
                val lower = floor(coordinate).toInt()
                mapping.x0Offsets[destinationX] = lower * RGB_CHANNELS
                mapping.x1Offsets[destinationX] =
                    (lower + 1).coerceAtMost(sourceWidth - 1) * RGB_CHANNELS
                mapping.xWeights[destinationX] = fixedWeight(coordinate - lower)
                destinationX += 1
            }
            var destinationY = 0
            while (destinationY < inputSpec.height) {
                val coordinate = halfPixelCoordinate(
                    destination = destinationY,
                    destinationSize = inputSpec.height,
                    sourceSize = sourceHeight,
                )
                val lower = floor(coordinate).toInt()
                mapping.y0Rows[destinationY] = lower * sourceWidth * RGB_CHANNELS
                mapping.y1Rows[destinationY] =
                    (lower + 1).coerceAtMost(sourceHeight - 1) * sourceWidth * RGB_CHANNELS
                mapping.yWeights[destinationY] = fixedWeight(coordinate - lower)
                destinationY += 1
            }
            cachedMapping = mapping
        }
    }

    private fun halfPixelCoordinate(
        destination: Int,
        destinationSize: Int,
        sourceSize: Int,
    ): Double = ((destination + 0.5) * sourceSize / destinationSize - 0.5)
        .coerceIn(0.0, (sourceSize - 1).toDouble())

    private fun fixedWeight(value: Double): Int =
        (value * WEIGHT_ONE).roundToInt().coerceIn(0, WEIGHT_ONE)

    private data class BilinearResizeMapping(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val x0Offsets: IntArray,
        val x1Offsets: IntArray,
        val xWeights: IntArray,
        val y0Rows: IntArray,
        val y1Rows: IntArray,
        val yWeights: IntArray,
    )

    private companion object {
        const val RGB_CHANNELS = 3
        const val MAX_PIXEL_VALUE = 255
        // Eleven fractional bits keep the complete four-pixel weighted sum within signed Int.
        const val WEIGHT_BITS = 11
        const val WEIGHT_ONE = 1 shl WEIGHT_BITS
        const val WEIGHT_SUM_BITS = WEIGHT_BITS * 2
        const val WEIGHT_ROUNDING = 1 shl (WEIGHT_SUM_BITS - 1)
    }
}

/** Generic adapter for postprocessed SSD-style boxes/classes/scores/count tensors. */
class ManifestSsdDetectionAdapter(
    private val manifest: ModelPackageManifest,
    private val targetProfile: TargetProfile?,
    classMapProvider: ClassMapProvider,
) : OutputAdapter {
    private val bySemantic = manifest.finalOutputs().map(OutputComponent::toTensorSpec)
        .associateBy(OutputTensorSpec::semantic)
    private val postprocess = requireNotNull(manifest.adapterContract.embeddedPostprocess) {
        "ssd_detection_v1 requires embedded postprocess metadata"
    }
    private val outputMaximum = checkNotNull(postprocess.maxDetections)
    private val configuredMaximum = manifest.parameterProfile.defaults[MAX_DETECTIONS_PARAMETER]
        ?.takeIf {
            it.isFinite() &&
                it % 1.0 == 0.0 &&
                it in 1.0..Int.MAX_VALUE.toDouble()
        }
        ?.toInt()
        ?: error("ssd_detection_v1 requires a positive integer max_detections default")
    private val effectiveMaximum = minOf(outputMaximum, configuredMaximum)
    private val resolvedClassMap = manifest.adapterContract.classMap?.let { spec ->
        val resolved = requireNotNull(classMapProvider.resolve(spec)) { "class map is unavailable" }
        require(resolved.identity == spec.identity && resolved.sha256 == spec.sha256) {
            "class map identity or hash mismatch"
        }
        resolved
    }
    private val labels = resolvedClassMap?.labelsByRawClassId
    private val targets = resolvedClassMap?.targetsByRawClassId

    init {
        require(manifest.adapterContract.schemaId in DETECTION_SCHEMA_IDS)
        require(bySemantic.keys == DETECTION_SEMANTICS)
        require(targetProfile != null || !labels.isNullOrEmpty()) {
            "object detector requires a runtime target profile or class map"
        }
    }

    override fun toObservation(
        output: RawTensorOutput,
        transform: LetterboxTransform,
        frame: CanonicalFrame,
    ): Observation {
        val tensors = output.tensors.associateBy(RawTensor::name)
        val boxesSpec = bySemantic.getValue(OutputTensorSemantic.DETECTION_BOXES)
        val classesSpec = bySemantic.getValue(OutputTensorSemantic.DETECTION_CLASSES)
        val scoresSpec = bySemantic.getValue(OutputTensorSemantic.DETECTION_SCORES)
        val countSpec = bySemantic.getValue(OutputTensorSemantic.DETECTION_COUNT)
        val boxes = tensors.requireFloatTensor(boxesSpec)
        val classes = tensors.requireFloatTensor(classesSpec)
        val scores = tensors.requireFloatTensor(scoresSpec)
        val countValue = tensors.requireFloatTensor(countSpec).single()
        require(countValue.isFinite() && countValue == countValue.toInt().toFloat()) {
            "detection count must be a finite integer"
        }
        val count = countValue.toInt()
        require(count in 0..outputMaximum)
        val minimumScore = checkNotNull(postprocess.scoreThreshold).toFloat()
        val items = buildList {
            repeat(minOf(count, effectiveMaximum)) { index ->
                val score = scores[index]
                require(score.isFinite() && score in 0f..1f)
                if (score < minimumScore) return@repeat
                val classValue = classes[index]
                require(classValue.isFinite() && classValue == classValue.toInt().toFloat())
                val rawClassId = classValue.toInt()
                val label = when (val profile = targetProfile) {
                    is TargetProfile.ObjectClass -> {
                        val target = targets?.get(rawClassId)
                            ?: labels?.get(rawClassId)?.takeIf { it == profile.targetId }
                                ?.let {
                                    ClassMapTarget(
                                        rawClassId = rawClassId,
                                        targetId = it,
                                        labelZhCn = profile.labelZhCn,
                                        labelEn = profile.labelEn,
                                    )
                                }
                            ?: return@repeat
                        if (target.targetId != profile.targetId) return@repeat
                        target.targetId
                    }
                    is TargetProfile.ReferenceImages,
                    null,
                    -> labels?.get(rawClassId) ?: return@repeat
                }
                val offset = index * BOX_COORDINATES
                mapBox(
                    yMin = boxes[offset],
                    xMin = boxes[offset + 1],
                    yMax = boxes[offset + 2],
                    xMax = boxes[offset + 3],
                    transform = transform,
                )?.let { box -> add(Detection(label, score, box)) }
            }
        }
        return Observation.Detections(items, frame.sourceSequence)
    }

    private fun mapBox(
        yMin: Float,
        xMin: Float,
        yMax: Float,
        xMax: Float,
        transform: LetterboxTransform,
    ): NormalizedRect? {
        require(listOf(yMin, xMin, yMax, xMax).all(Float::isFinite))
        require(yMin <= yMax && xMin <= xMax)
        val left = ((xMin * transform.inputWidth - transform.offsetX) /
            transform.scaleX / transform.sourceWidth).coerceIn(0f, 1f)
        val right = ((xMax * transform.inputWidth - transform.offsetX) /
            transform.scaleX / transform.sourceWidth).coerceIn(0f, 1f)
        val top = ((yMin * transform.inputHeight - transform.offsetY) /
            transform.scaleY / transform.sourceHeight).coerceIn(0f, 1f)
        val bottom = ((yMax * transform.inputHeight - transform.offsetY) /
            transform.scaleY / transform.sourceHeight).coerceIn(0f, 1f)
        return if (left < right && top < bottom) NormalizedRect(left, top, right, bottom) else null
    }

    private fun Map<String, RawTensor>.requireFloatTensor(spec: OutputTensorSpec): FloatArray {
        val tensor = requireNotNull(this[spec.name]) { "${spec.name} tensor is missing" }
        require(tensor.elementType == TensorElementType.FLOAT32)
        require(tensor.shape == spec.runtimeShape)
        require(tensor.bytes.size == spec.runtimeShape.elementCount() * Float.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(tensor.bytes).order(ByteOrder.nativeOrder())
        return FloatArray(tensor.bytes.size / Float.SIZE_BYTES) { buffer.float }
    }

    private companion object {
        val DETECTION_SCHEMA_IDS = setOf("object_detection_v1")
        const val MAX_DETECTIONS_PARAMETER = "max_detections"
        const val BOX_COORDINATES = 4
    }
}

/** Generic vocabulary-driven CTC decoding followed by the finite structured-reading grammar. */
class ManifestStructuredReadingCtcAdapter(
    private val manifest: ModelPackageManifest,
    vocabularyFile: File,
) : OutputAdapter {
    private val tensorSpec = manifest.finalOutputs().single().toTensorSpec()
    private val decoding = requireNotNull(manifest.ctcDecoding)
    private val vocabulary = CtcVocabularyCodec.read(
        file = vocabularyFile,
        decoding = decoding,
        expectedClassCount = tensorSpec.runtimeShape.last(),
    )
    private val minimumConfidence = manifest.readingMinimumConfidence().toFloat()
    private val scoreScratch = FloatArray(tensorSpec.runtimeShape.elementCount())
    private val selectedIndexScratch = IntArray(tensorSpec.runtimeShape[1])
    private val selectedConfidenceScratch = DoubleArray(tensorSpec.runtimeShape[1])
    private val validRowScratch = BooleanArray(tensorSpec.runtimeShape[1])

    init {
        require(manifest.adapterContract.schemaId == NUMERIC_SCHEMA_ID)
        require(tensorSpec.semantic == OutputTensorSemantic.CTC_LOGITS)
        require(tensorSpec.dataType == TensorDataType.FLOAT32)
        require(tensorSpec.runtimeShape.size == 3 && tensorSpec.runtimeShape.first() == 1)
        require(vocabulary.tokens.size == tensorSpec.runtimeShape.last())
    }

    override fun toObservation(
        output: RawTensorOutput,
        transform: LetterboxTransform,
        frame: CanonicalFrame,
    ): Observation {
        val tensor = output.tensors.singleOrNull { it.name == tensorSpec.name }
            ?: return unavailable(frame, "numeric_tensor_missing")
        if (tensor.elementType != TensorElementType.FLOAT32 || tensor.shape != tensorSpec.runtimeShape ||
            tensor.bytes.size != tensorSpec.runtimeShape.elementCount() * Float.SIZE_BYTES
        ) {
            return unavailable(frame, "numeric_tensor_mismatch")
        }
        val timeSteps = tensorSpec.runtimeShape[1]
        val classCount = tensorSpec.runtimeShape[2]
        ByteBuffer.wrap(tensor.bytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .get(scoreScratch)
        val result = decodeGreedy(scoreScratch, timeSteps, classCount)
            ?: return unavailable(frame, "numeric_probabilities_invalid")
        require(decoding.collapseSemantics == CtcCollapseSemantics.CTC_GREEDY_ARGMAX_V1)
        if (result.text.isBlank()) {
            return unavailable(
                frame,
                "structured_reading_decode_invalid",
                UnavailableReason.LOW_QUALITY,
            )
        }
        val confidence = result.confidence
        if (!confidence.isFinite() || confidence < minimumConfidence) {
            return unavailable(
                frame,
                "numeric_confidence_below_threshold",
                UnavailableReason.LOW_QUALITY,
            )
        }
        val reading = parseStructuredReading(result.text) ?: return unavailable(
            frame,
            "structured_reading_decode_invalid",
            UnavailableReason.LOW_QUALITY,
        )
        return Observation.Reading(
            text = reading.text,
            valueDecimal = reading.valueDecimal,
            stable = false,
            sourceSequence = frame.sourceSequence,
            confidence = confidence,
            unit = reading.format.unit,
            format = reading.format,
        )
    }

    /**
     * Validates and greedily decodes one CTC row at a time. The production vocabulary has 18,710
     * entries, so materializing both a FloatArray and a DoubleArray for every frame added another
     * full-tensor copy and scan after ONNX inference. A single reusable FloatArray is filled by the
     * platform's bulk FloatBuffer copy, then scanned once per row. ModelPackageRuntime serializes
     * process calls, so the scratch array never leaks across frames. This preserves the exact
     * contract while avoiding roughly six megabytes of transient allocation per recognized line.
     */
    private fun decodeGreedy(
        scores: FloatArray,
        timeSteps: Int,
        classCount: Int,
    ): NumericDecodeResult? {
        var emittedConfidenceSum = 0.0
        var emittedCount = 0
        val decodeRow: (Int) -> Unit = { time ->
            validRowScratch[time] = when (decoding.scoreSemantics) {
                CtcScoreSemantics.PROBABILITIES_V1 -> decodeProbabilityRow(
                    scores,
                    time,
                    classCount,
                )
                CtcScoreSemantics.UNNORMALIZED_LOGITS_SOFTMAX_V1 -> decodeLogitRow(
                    scores,
                    time,
                    classCount,
                )
            }
        }
        if (timeSteps * classCount >= PARALLEL_DECODE_MINIMUM_ELEMENTS) {
            IntStream.range(0, timeSteps).parallel().forEach(decodeRow)
        } else {
            repeat(timeSteps, decodeRow)
        }
        var validTime = 0
        while (validTime < timeSteps) {
            if (!validRowScratch[validTime]) return null
            validTime++
        }
        val text = buildString {
            var previous = -1
            repeat(timeSteps) { time ->
                val selected = selectedIndexScratch[time]
                if (selected != vocabulary.blankIndex && selected != previous) {
                    append(vocabulary.tokens[selected])
                    emittedConfidenceSum += selectedConfidenceScratch[time]
                    emittedCount++
                }
                previous = selected
            }
        }
        val confidence = if (emittedCount == 0) 0f else (emittedConfidenceSum / emittedCount).toFloat()
        return NumericDecodeResult(text, confidence)
    }

    private fun decodeProbabilityRow(scores: FloatArray, time: Int, classCount: Int): Boolean {
        val base = time * classCount
        val end = base + classCount
        var cursor = base
        var sum = 0.0
        var selected = 0
        var selectedScore = Float.NEGATIVE_INFINITY
        while (cursor < end) {
            val score = scores[cursor]
            if (score.isNaN() || score < 0f || score > 1f) return false
            sum += score.toDouble()
            if (score > selectedScore) {
                selected = cursor - base
                selectedScore = score
            }
            cursor++
        }
        if (abs(sum - 1.0) > PROBABILITY_SUM_TOLERANCE) return false
        selectedIndexScratch[time] = selected
        selectedConfidenceScratch[time] = selectedScore.toDouble()
        return true
    }

    private fun decodeLogitRow(scores: FloatArray, time: Int, classCount: Int): Boolean {
        val base = time * classCount
        val end = base + classCount
        var cursor = base
        var selected = 0
        var selectedScore = Float.NEGATIVE_INFINITY
        while (cursor < end) {
            val score = scores[cursor]
            if (!score.isFinite()) return false
            if (score > selectedScore) {
                selected = cursor - base
                selectedScore = score
            }
            cursor++
        }
        var denominator = 0.0
        cursor = base
        while (cursor < end) {
            denominator += exp((scores[cursor] - selectedScore).toDouble())
            cursor++
        }
        if (!denominator.isFinite() || denominator <= 0.0) return false
        selectedIndexScratch[time] = selected
        selectedConfidenceScratch[time] = 1.0 / denominator
        return true
    }

    private data class NumericDecodeResult(val text: String, val confidence: Float)

    private fun unavailable(
        frame: CanonicalFrame,
        code: String,
        reason: UnavailableReason = UnavailableReason.INCOMPATIBLE_OUTPUT,
    ) = Observation.Unavailable(
        reason = reason,
        diagnosticCode = code,
        sourceSequence = frame.sourceSequence,
    )

    private companion object {
        const val NUMERIC_SCHEMA_ID = "structured_reading_v2"
        const val PROBABILITY_SUM_TOLERANCE = 1e-3
        const val PARALLEL_DECODE_MINIMUM_ELEMENTS = 100_000
    }
}

internal fun ModelPackageManifest.readingMinimumConfidence(): Double {
    require(runtimeFamily == RecipeFamily.READING_PIPELINE_V1)
    val configured = requireNotNull(
        parameterProfile.defaults[READING_MINIMUM_CONFIDENCE_PARAMETER],
    ) { "reading minimum confidence is not configured" }
    require(configured.isFinite() && configured in 0.0..1.0)
    return configured
}

/** Generic CLIP-style whole-frame similarity adapter; package thresholds remain Manifest data. */
class ManifestSimilarityStateAdapter(
    private val manifest: ModelPackageManifest,
    private val targetProfile: TargetProfile?,
) : OutputAdapter {
    private val tensorSpec = manifest.finalOutputs().single().toTensorSpec()
    private val threshold = requireNotNull(manifest.parameterProfile.defaults["match_threshold"])
    private val rejectionMargin = manifest.parameterProfile.defaults["rejection_margin"] ?: 0.0

    init {
        require(manifest.runtimeFamily == RecipeFamily.SIMILARITY_MATCH_V1)
        require(manifest.adapterContract.schemaId == ManifestRuntimeComponents.OUTPUT_SIMILARITY)
        require(tensorSpec.semantic == OutputTensorSemantic.SIMILARITY_SCORES)
        require(tensorSpec.dataType == TensorDataType.FLOAT32)
        require(tensorSpec.runtimeShape.size == 2 && tensorSpec.runtimeShape.first() == 1)
        require(threshold.isFinite())
        require(rejectionMargin.isFinite() && rejectionMargin >= 0.0)
        requireNotNull(targetProfile)
    }

    override fun toObservation(
        output: RawTensorOutput,
        transform: LetterboxTransform,
        frame: CanonicalFrame,
    ): Observation {
        val tensor = output.tensors.singleOrNull { it.name == tensorSpec.name }
            ?: return unavailable(frame, "similarity_tensor_missing", UnavailableReason.INCOMPATIBLE_OUTPUT)
        if (tensor.elementType != TensorElementType.FLOAT32 || tensor.shape != tensorSpec.runtimeShape ||
            tensor.bytes.size != tensorSpec.runtimeShape.elementCount() * Float.SIZE_BYTES
        ) {
            return unavailable(frame, "similarity_tensor_mismatch", UnavailableReason.INCOMPATIBLE_OUTPUT)
        }
        val values = ByteBuffer.wrap(tensor.bytes).order(ByteOrder.nativeOrder()).let { buffer ->
            FloatArray(tensor.bytes.size / Float.SIZE_BYTES) { buffer.float }
        }
        if (values.isEmpty() || values.any { !it.isFinite() }) {
            return unavailable(frame, "similarity_score_invalid", UnavailableReason.INCOMPATIBLE_OUTPUT)
        }
        val score = values.max().toDouble()
        val targetId = checkNotNull(targetProfile).targetId
        return when {
            score >= threshold -> Observation.State(
                stateId = "$targetId:present",
                confidence = sigmoid(score - threshold).toFloat(),
                sourceSequence = frame.sourceSequence,
                stableFrameCount = 1,
            )
            score <= threshold - rejectionMargin -> Observation.State(
                stateId = "$targetId:absent",
                confidence = (1.0 - sigmoid(score - threshold)).toFloat(),
                sourceSequence = frame.sourceSequence,
                stableFrameCount = 1,
            )
            else -> unavailable(frame, "similarity_ambiguous", UnavailableReason.LOW_QUALITY)
        }
    }

    private fun sigmoid(value: Double): Double = when {
        value >= 0.0 -> 1.0 / (1.0 + exp(-value))
        else -> exp(value) / (1.0 + exp(value))
    }

    private fun unavailable(
        frame: CanonicalFrame,
        code: String,
        reason: UnavailableReason,
    ) = Observation.Unavailable(reason, code, frame.sourceSequence)
}

/** Built-in finite v3 families; multi-input encoders require an explicit registered family. */
object ManifestRuntimeComponents {
    const val PREPROCESS_UINT8 = "letterbox_rgb_uint8_v1"
    const val PREPROCESS_OBJECT_UINT8 = "object_rgb_uint8_v1"
    const val PREPROCESS_INT8 = "letterbox_rgb_int8_minus128_v1"
    const val PREPROCESS_FLOAT = "letterbox_rgb_float32_0_1_v1"
    const val PREPROCESS_PPOCR_RECOGNITION = PpOcrRecognitionPreprocessor.ID
    const val PREPROCESS_PPOCR_GRAYSCALE_MINMAX = PpOcrRecognitionPreprocessor.GRAYSCALE_MINMAX_ID
    const val PREPROCESS_PPOCR_AUTO_LOCATE = PpOcrDetectionPreprocessor.ID
    const val ADAPTER_OBJECT_DETECTION = "object_detection_v1"
    const val ADAPTER_SSD = ADAPTER_OBJECT_DETECTION
    const val ADAPTER_NUMERIC_CTC = "structured_reading_ctc_v2"
    const val OUTPUT_NUMERIC_READING = "structured_reading_v2"
    const val OUTPUT_SIMILARITY = "similarity_match_v1"
    const val ADAPTER_SIMILARITY_LOGIT = "similarity_logit_state_v1"

    fun registry(
        classMapProvider: ClassMapProvider = ObjectDetectionClassMap.provider,
        @Suppress("UNUSED_PARAMETER")
        targetAwareBackendFactory: ManifestBackendFactory? = null,
        referenceImageProvider: ReferenceImageProvider? = null,
        referenceEmbeddingCache: ReferenceEmbeddingCache? = null,
        onnxExecutionProvider: OnnxExecutionProvider = OnnxExecutionProvider.CPU,
    ): RuntimeComponentRegistry {
        val genericFamilies = setOf(
            RecipeFamily.OBJECT_DETECTION_V1,
            RecipeFamily.SIMILARITY_MATCH_V1,
            RecipeFamily.READING_PIPELINE_V1,
        )
        val allFamilies = genericFamilies
        val genericBackend = ManifestBackendFactory(::ManifestLiteRtBackend)
        val detectorFamilies = setOf(RecipeFamily.OBJECT_DETECTION_V1)
        val preprocessors = mutableListOf(
                preprocessor(PREPROCESS_UINT8, allFamilies, ManifestRgbLetterboxPreprocessor.PixelEncodingMode.UINT8_RAW),
                PreprocessorRegistration(
                    preprocessId = PREPROCESS_OBJECT_UINT8,
                    recipeFamilies = setOf(RecipeFamily.OBJECT_DETECTION_V1),
                    factory = ManifestPreprocessorFactory { context ->
                        ManifestObjectRgbDirectResizePreprocessor(
                            context.manifest.primaryImageInput.toImageInputSpec(),
                        )
                    },
                ),
                preprocessor(PREPROCESS_INT8, allFamilies, ManifestRgbLetterboxPreprocessor.PixelEncodingMode.INT8_MINUS_128),
                preprocessor(PREPROCESS_FLOAT, allFamilies, ManifestRgbLetterboxPreprocessor.PixelEncodingMode.FLOAT32_ZERO_TO_ONE),
                PreprocessorRegistration(
                    preprocessId = PREPROCESS_PPOCR_AUTO_LOCATE,
                    recipeFamilies = setOf(RecipeFamily.READING_PIPELINE_V1),
                    factory = ManifestPreprocessorFactory { context ->
                        PpOcrRecognitionPreprocessor(context.manifest.primaryImageInput)
                    },
                ),
            )
        val adapters = mutableListOf(
                AdapterRegistration(
                    adapterId = ADAPTER_OBJECT_DETECTION,
                    recipeFamilies = detectorFamilies,
                    outputSchemaIds = setOf("object_detection_v1"),
                    factory = ManifestOutputAdapterFactory { context ->
                        ManifestSsdDetectionAdapter(
                            manifest = context.manifest,
                            targetProfile = context.targetProfile,
                            classMapProvider = classMapProvider,
                        )
                    },
                ),
                AdapterRegistration(
                    adapterId = ADAPTER_NUMERIC_CTC,
                    recipeFamilies = setOf(RecipeFamily.READING_PIPELINE_V1),
                    outputSchemaIds = setOf(OUTPUT_NUMERIC_READING),
                    factory = ManifestOutputAdapterFactory { context ->
                        val decoding = requireNotNull(context.manifest.ctcDecoding)
                        ManifestStructuredReadingCtcAdapter(
                            manifest = context.manifest,
                            vocabularyFile = checkNotNull(
                                context.artifactFilesByRole[decoding.vocabularyArtifactRole],
                            ),
                        )
                    },
                ),
                AdapterRegistration(
                    adapterId = ADAPTER_SIMILARITY_LOGIT,
                    recipeFamilies = setOf(RecipeFamily.SIMILARITY_MATCH_V1),
                    outputSchemaIds = setOf(OUTPUT_SIMILARITY),
                    factory = ManifestOutputAdapterFactory { context ->
                        ManifestSimilarityStateAdapter(context.manifest, context.targetProfile)
                    },
                ),
            )
        val backends = mutableListOf(
                BackendRegistration(
                    runtimeKind = RuntimeKind.LITERT,
                    recipeFamilies = allFamilies,
                    targetModes = setOf(TargetMode.NONE, TargetMode.OBJECT_CLASS),
                    factory = genericBackend,
                ),
        )
        if (referenceImageProvider != null) {
            if (referenceEmbeddingCache != null) {
                preprocessors += NeuralReferenceRuntimeComponents.preprocessorRegistration(
                    referenceImageProvider,
                    referenceEmbeddingCache,
                )
            }
        }
        return RuntimeComponentRegistry(
            preprocessors = preprocessors,
            adapters = adapters,
            backends = backends,
            artifactSessions = listOf(
                ArtifactSessionRegistration(
                    runtimeKind = RuntimeKind.LITERT,
                    factory = ManifestArtifactSessionFactory(::LiteRtArtifactSession),
                ),
                ArtifactSessionRegistration(
                    runtimeKind = RuntimeKind.ONNX,
                    factory = ManifestArtifactSessionFactory { context ->
                        OnnxArtifactSession(context, onnxExecutionProvider)
                    },
                ),
                NeuralReferenceRuntimeComponents.artifactSessionRegistration(),
            ),
        )
    }

    private fun preprocessor(
        id: String,
        families: Set<RecipeFamily>,
        mode: ManifestRgbLetterboxPreprocessor.PixelEncodingMode,
    ) = PreprocessorRegistration(
        preprocessId = id,
        recipeFamilies = families,
        factory = ManifestPreprocessorFactory { context ->
            ManifestRgbLetterboxPreprocessor(context.manifest.primaryImageInput.toImageInputSpec(), mode)
        },
    )

}

private val TensorElementType.byteCount: Int
    get() = when (this) {
        TensorElementType.UINT8, TensorElementType.INT8 -> Byte.SIZE_BYTES
        TensorElementType.INT32 -> Int.SIZE_BYTES
        TensorElementType.INT64 -> Long.SIZE_BYTES
        TensorElementType.FLOAT16 -> Short.SIZE_BYTES
        TensorElementType.FLOAT32 -> Float.SIZE_BYTES
    }

private val TensorDataType.byteCount: Int
    get() = when (this) {
        TensorDataType.UINT8, TensorDataType.INT8 -> Byte.SIZE_BYTES
        TensorDataType.INT32 -> Int.SIZE_BYTES
        TensorDataType.INT64 -> Long.SIZE_BYTES
        TensorDataType.FLOAT16 -> Short.SIZE_BYTES
        TensorDataType.FLOAT32 -> Float.SIZE_BYTES
    }

private fun TensorDataType.toElementType(): TensorElementType = when (this) {
    TensorDataType.UINT8 -> TensorElementType.UINT8
    TensorDataType.INT8 -> TensorElementType.INT8
    TensorDataType.INT32 -> TensorElementType.INT32
    TensorDataType.INT64 -> TensorElementType.INT64
    TensorDataType.FLOAT16 -> TensorElementType.FLOAT16
    TensorDataType.FLOAT32 -> TensorElementType.FLOAT32
}

private fun TensorDataType.toLiteRtDataType(): DataType = when (this) {
    TensorDataType.UINT8 -> DataType.UINT8
    TensorDataType.INT8 -> DataType.INT8
    TensorDataType.FLOAT32 -> DataType.FLOAT32
    TensorDataType.FLOAT16 -> error("LiteRT Java tensor contract does not expose FLOAT16 tensors")
    TensorDataType.INT32, TensorDataType.INT64 ->
        error("generic image backend does not expose integer token inputs")
}

private fun List<Int>.elementCount(): Int = fold(1) { count, dimension ->
    Math.multiplyExact(count, dimension)
}

private val DETECTION_SEMANTICS = setOf(
    OutputTensorSemantic.DETECTION_BOXES,
    OutputTensorSemantic.DETECTION_CLASSES,
    OutputTensorSemantic.DETECTION_SCORES,
    OutputTensorSemantic.DETECTION_COUNT,
)
