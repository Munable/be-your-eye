package app.beyoureyes.core.vision

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet

/** Internal benchmark seam; product runtime stays CPU until device evidence selects otherwise. */
enum class OnnxExecutionProvider {
    CPU,
    NNAPI_NCHW,
}

/** Generic ONNX Runtime session driven only by one artifact's Manifest tensor contract. */
class OnnxArtifactSession(
    context: ArtifactSessionContext,
    executionProvider: OnnxExecutionProvider = OnnxExecutionProvider.CPU,
) : ArtifactExecutionSession {
    override val artifactRole: String = context.artifact.role
    private val environment = OrtEnvironment.getEnvironment()
    private val inputSpecs = context.inputSpecs.sortedBy(InputComponent::tensorIndex)
    private val outputSpecs = context.outputSpecs.sortedBy(OutputComponent::tensorIndex)
    private val session: OrtSession
    private var closed = false

    init {
        require(context.artifact.runtime == RuntimeKind.ONNX)
        require(context.artifact.mediaType == ArtifactMediaType.ONNX)
        require(context.artifactFile.isFile)
        require(inputSpecs.isNotEmpty() && outputSpecs.isNotEmpty())
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(context.numberOfThreads)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            // Extended ORT rewrites may fuse a valid float16 Erf graph into a contrib Gelu
            // kernel that is unavailable in the Android CPU execution provider. BASIC keeps
            // portable graph semantics while still applying safe constant/basic rewrites.
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            options.setDeterministicCompute(true)
            if (executionProvider == OnnxExecutionProvider.NNAPI_NCHW) {
                options.addNnapi(EnumSet.of(NNAPIFlags.USE_NCHW))
            }
            environment.createSession(context.artifactFile.absolutePath, options)
        }
        try {
            require(session.inputNames == inputSpecs.map(InputComponent::tensorName).toSet()) {
                "ONNX input names do not match inputs[]"
            }
            require(session.outputNames == outputSpecs.map(OutputComponent::tensorName).toSet()) {
                "ONNX output names do not match outputs[]"
            }
            inputSpecs.forEach { spec -> validateNode(checkNotNull(session.inputInfo[spec.tensorName]), spec) }
            outputSpecs.forEach { spec -> validateNode(checkNotNull(session.outputInfo[spec.tensorName]), spec) }
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    override fun run(inputsByTensorName: Map<String, RawTensor>): Map<String, RawTensor> {
        check(!closed) { "ONNX session is closed" }
        require(inputsByTensorName.keys == inputSpecs.map(InputComponent::tensorName).toSet())
        val tensors = inputSpecs.associate { spec ->
            val tensor = checkNotNull(inputsByTensorName[spec.tensorName])
            require(tensor.name == spec.tensorName)
            require(tensor.shape == spec.runtimeShape)
            require(tensor.elementType == spec.dataType.toTensorElementType())
            require(tensor.bytes.size == spec.runtimeShape.checkedElementCount() * tensor.elementType.storageByteCount)
            spec.tensorName to tensor.toOnnxTensor(environment)
        }
        try {
            session.run(tensors, outputSpecs.map(OutputComponent::tensorName).toSet()).use { result ->
                return outputSpecs.associate { spec ->
                    val value = checkNotNull(result[spec.tensorName].orElse(null))
                    val tensor = value as? OnnxTensor
                        ?: error("ONNX output ${spec.tensorName} is not a dense tensor")
                    spec.tensorName to tensor.toRawTensor(spec)
                }
            }
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    private fun validateNode(node: NodeInfo, spec: InputComponent) {
        val info = node.info as? TensorInfo ?: error("ONNX input ${spec.tensorName} is not a tensor")
        validateOnnxDeclaredShape(
            tensorKind = "input",
            tensorName = spec.tensorName,
            onnxShape = info.shape,
            manifestShape = spec.runtimeShape,
        )
        require(info.type == spec.dataType.toOnnxJavaType())
    }

    private fun validateNode(node: NodeInfo, spec: OutputComponent) {
        val info = node.info as? TensorInfo ?: error("ONNX output ${spec.tensorName} is not a tensor")
        validateOnnxDeclaredShape(
            tensorKind = "output",
            tensorName = spec.tensorName,
            onnxShape = info.shape,
            manifestShape = spec.runtimeShape,
        )
        require(info.type == spec.dataType.toOnnxJavaType())
    }
}

/**
 * Resolves an ONNX model's dynamic dimensions from the signed Manifest without weakening static
 * dimensions. ONNX Runtime exposes symbolic/unknown dimensions as negative values; every static
 * dimension remains authoritative and must match the Manifest's concrete positive shape.
 */
internal fun validateOnnxDeclaredShape(
    tensorKind: String,
    tensorName: String,
    onnxShape: LongArray,
    manifestShape: List<Int>,
) {
    require(manifestShape.isNotEmpty() && manifestShape.all { it > 0 }) {
        "Manifest $tensorKind $tensorName shape must contain concrete positive dimensions"
    }
    require(onnxShape.size == manifestShape.size) {
        "ONNX $tensorKind $tensorName rank ${onnxShape.size} does not match Manifest rank ${manifestShape.size}"
    }
    onnxShape.forEachIndexed { index, onnxDimension ->
        val manifestDimension = manifestShape[index].toLong()
        require(onnxDimension < 0L || onnxDimension == manifestDimension) {
            "ONNX $tensorKind $tensorName dimension $index is static $onnxDimension " +
                "but Manifest declares $manifestDimension"
        }
    }
}

internal fun validateOnnxActualOutputShape(
    tensorName: String,
    actualShape: LongArray,
    manifestShape: List<Int>,
) {
    val expectedShape = manifestShape.map(Int::toLong)
    require(actualShape.toList() == expectedShape) {
        "ONNX output $tensorName actual shape ${actualShape.toList()} " +
            "does not match Manifest shape $expectedShape"
    }
}

private fun RawTensor.toOnnxTensor(environment: OrtEnvironment): OnnxTensor {
    val buffer = ByteBuffer.allocateDirect(bytes.size)
        .order(ByteOrder.nativeOrder())
        .put(bytes)
        .also(ByteBuffer::rewind)
    return OnnxTensor.createTensor(
        environment,
        buffer,
        shape.map(Int::toLong).toLongArray(),
        elementType.toOnnxJavaType(),
    )
}

private fun OnnxTensor.toRawTensor(spec: OutputComponent): RawTensor {
    val actualInfo = info
    require(actualInfo.type == spec.dataType.toOnnxJavaType()) {
        "ONNX output ${spec.tensorName} actual type ${actualInfo.type} " +
            "does not match Manifest type ${spec.dataType}"
    }
    validateOnnxActualOutputShape(
        tensorName = spec.tensorName,
        actualShape = actualInfo.shape,
        manifestShape = spec.runtimeShape,
    )
    val expectedBytes = spec.runtimeShape.checkedElementCount() * spec.dataType.toTensorElementType().storageByteCount
    val bytes = ByteArray(expectedBytes)
    val target = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
    when (spec.dataType) {
        TensorDataType.UINT8, TensorDataType.INT8 -> getByteBuffer().duplicate().also { source ->
            source.rewind()
            require(source.remaining() == expectedBytes)
            source.get(bytes)
            target.position(expectedBytes)
        }
        TensorDataType.INT32 -> getIntBuffer().duplicate().also { source ->
            source.rewind()
            target.asIntBuffer().put(source)
            target.position(expectedBytes)
        }
        TensorDataType.INT64 -> getLongBuffer().duplicate().also { source ->
            source.rewind()
            target.asLongBuffer().put(source)
            target.position(expectedBytes)
        }
        TensorDataType.FLOAT16 -> getShortBuffer().duplicate().also { source ->
            source.rewind()
            target.asShortBuffer().put(source)
            target.position(expectedBytes)
        }
        TensorDataType.FLOAT32 -> getFloatBuffer().duplicate().also { source ->
            source.rewind()
            target.asFloatBuffer().put(source)
            target.position(expectedBytes)
        }
    }
    require(target.position() == expectedBytes)
    return RawTensor(
        name = spec.tensorName,
        bytes = bytes,
        shape = spec.runtimeShape,
        elementType = spec.dataType.toTensorElementType(),
    )
}

private fun TensorDataType.toOnnxJavaType(): OnnxJavaType = when (this) {
    TensorDataType.UINT8 -> OnnxJavaType.UINT8
    TensorDataType.INT8 -> OnnxJavaType.INT8
    TensorDataType.INT32 -> OnnxJavaType.INT32
    TensorDataType.INT64 -> OnnxJavaType.INT64
    TensorDataType.FLOAT16 -> OnnxJavaType.FLOAT16
    TensorDataType.FLOAT32 -> OnnxJavaType.FLOAT
}

private fun TensorElementType.toOnnxJavaType(): OnnxJavaType = when (this) {
    TensorElementType.UINT8 -> OnnxJavaType.UINT8
    TensorElementType.INT8 -> OnnxJavaType.INT8
    TensorElementType.INT32 -> OnnxJavaType.INT32
    TensorElementType.INT64 -> OnnxJavaType.INT64
    TensorElementType.FLOAT16 -> OnnxJavaType.FLOAT16
    TensorElementType.FLOAT32 -> OnnxJavaType.FLOAT
}
