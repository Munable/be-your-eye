package app.beyoureyes.core.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Generic multi-input LiteRT artifact session used by the same Manifest execution graph. */
class LiteRtArtifactSession(
    context: ArtifactSessionContext,
) : ArtifactExecutionSession {
    override val artifactRole: String = context.artifact.role
    private val inputSpecs = context.inputSpecs.sortedBy(InputComponent::tensorIndex)
    private val outputSpecs = context.outputSpecs.sortedBy(OutputComponent::tensorIndex)
    private val interpreter = Interpreter(
        context.artifactFile,
        Interpreter.Options()
            .setNumThreads(context.numberOfThreads)
            .setUseXNNPACK(true),
    )
    private val inputOrdinalByName: Map<String, Int>
    private val outputOrdinalByName: Map<String, Int>
    private var closed = false

    init {
        require(context.artifact.runtime == RuntimeKind.LITERT)
        require(context.artifact.mediaType == ArtifactMediaType.LITERT)
        require(interpreter.inputTensorCount == inputSpecs.size)
        require(interpreter.outputTensorCount == outputSpecs.size)
        inputOrdinalByName = inputSpecs.associate { spec ->
            val ordinal = interpreter.getInputIndex(spec.tensorName)
            val tensor = interpreter.getInputTensor(ordinal)
            require(tensor.index() == spec.tensorIndex)
            require(tensor.dataType() == spec.dataType.toLiteRtType())
            validateQuantization(
                spec.quantization,
                tensor.quantizationParams().scale,
                tensor.quantizationParams().zeroPoint,
            )
            requireRuntimeShapeFitsSignature(
                shapeSignature = tensor.shapeSignature().toList(),
                runtimeShape = spec.runtimeShape,
            )
            if (tensor.shape().toList() != spec.runtimeShape) {
                interpreter.resizeInput(ordinal, spec.runtimeShape.toIntArray(), true)
            }
            spec.tensorName to ordinal
        }
        // Dynamic batch dimensions propagate from the resized input to the output only after
        // allocation. The exact signed runtime shapes are checked again below; an incompatible
        // model therefore fails before processing any user frame.
        interpreter.allocateTensors()
        inputSpecs.forEach { spec ->
            val tensor = interpreter.getInputTensor(checkNotNull(inputOrdinalByName[spec.tensorName]))
            require(tensor.shape().toList() == spec.runtimeShape)
        }
        outputOrdinalByName = outputSpecs.associate { spec ->
            val ordinal = interpreter.getOutputIndex(spec.tensorName)
            val tensor = interpreter.getOutputTensor(ordinal)
            require(tensor.index() == spec.tensorIndex)
            require(tensor.shape().toList() == spec.runtimeShape)
            require(tensor.dataType() == spec.dataType.toLiteRtType())
            spec.tensorName to ordinal
        }
    }

    override fun run(inputsByTensorName: Map<String, RawTensor>): Map<String, RawTensor> {
        check(!closed) { "LiteRT session is closed" }
        require(inputsByTensorName.keys == inputSpecs.map(InputComponent::tensorName).toSet())
        val inputBuffersByOrdinal = inputSpecs.associate { spec ->
            val input = checkNotNull(inputsByTensorName[spec.tensorName])
            require(input.shape == spec.runtimeShape)
            require(input.elementType == spec.dataType.toTensorElementType())
            require(input.bytes.size == spec.runtimeShape.checkedElementCount() * input.elementType.storageByteCount)
            checkNotNull(inputOrdinalByName[spec.tensorName]) to input.bytes.directBuffer()
        }
        val outputBuffersBySpec = outputSpecs.associateWith { spec ->
            ByteBuffer.allocateDirect(
                spec.runtimeShape.checkedElementCount() * spec.dataType.toTensorElementType().storageByteCount,
            ).order(ByteOrder.nativeOrder())
        }
        val orderedInputs = Array(interpreter.inputTensorCount) { ordinal ->
            checkNotNull(inputBuffersByOrdinal[ordinal])
        }
        interpreter.runForMultipleInputsOutputs(
            orderedInputs,
            outputBuffersBySpec.entries.associate { (spec, buffer) ->
                checkNotNull(outputOrdinalByName[spec.tensorName]) to buffer
            },
        )
        return outputSpecs.associate { spec ->
            val buffer = checkNotNull(outputBuffersBySpec[spec]).also(ByteBuffer::rewind)
            spec.tensorName to RawTensor(
                name = spec.tensorName,
                bytes = ByteArray(buffer.remaining()).also(buffer::get),
                shape = spec.runtimeShape,
                elementType = spec.dataType.toTensorElementType(),
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        interpreter.close()
    }

    private fun validateQuantization(
        expected: InputQuantization,
        actualScale: Float,
        actualZeroPoint: Int,
    ) {
        when (expected.mode) {
            QuantizationMode.PER_TENSOR -> {
                require(abs(actualScale.toDouble() - checkNotNull(expected.scale)) <= QUANTIZATION_EPSILON)
                require(actualZeroPoint == expected.zeroPoint)
            }
            QuantizationMode.NONE -> require(actualScale == 0f && actualZeroPoint == 0)
        }
    }

    private companion object {
        const val QUANTIZATION_EPSILON = 1e-6
    }
}

internal fun requireRuntimeShapeFitsSignature(
    shapeSignature: List<Int>,
    runtimeShape: List<Int>,
) {
    require(shapeSignature.size == runtimeShape.size)
    require(runtimeShape.all { it > 0 })
    require(shapeSignature.zip(runtimeShape).all { (signedDimension, runtimeDimension) ->
        signedDimension == -1 || signedDimension == runtimeDimension
    })
}

private fun ByteArray.directBuffer(): ByteBuffer = ByteBuffer.allocateDirect(size)
    .order(ByteOrder.nativeOrder())
    .put(this)
    .also(ByteBuffer::rewind)

private fun TensorDataType.toLiteRtType(): DataType = when (this) {
    TensorDataType.UINT8 -> DataType.UINT8
    TensorDataType.INT8 -> DataType.INT8
    TensorDataType.INT32 -> DataType.INT32
    TensorDataType.INT64 -> DataType.INT64
    TensorDataType.FLOAT16 -> error("LiteRT Java tensor contract does not expose FLOAT16 tensors")
    TensorDataType.FLOAT32 -> DataType.FLOAT32
}
