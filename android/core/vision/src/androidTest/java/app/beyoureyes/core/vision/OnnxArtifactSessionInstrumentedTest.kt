package app.beyoureyes.core.vision

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxArtifactSessionInstrumentedTest {
    @Test
    fun officialTinyModelRunsThroughGenericOnnxArtifactSession() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modelFile = copyAsset(context, "mul_1.onnx")
        val artifact = ArtifactComponent(
            role = "primary",
            runtime = RuntimeKind.ONNX,
            mediaType = ArtifactMediaType.ONNX,
            url = "https://github.com/microsoft/onnxruntime/raw/main/onnxruntime/test/testdata/mul_1.onnx",
            sha256 = "71f431c4e9321ec6fbeb158d02ed240459a7dcc98673fa79a4f439ce42efaf10",
            sizeBytes = 130,
        )
        val input = InputComponent(
            role = InputRole.IMAGE_FEATURES,
            artifactRole = "primary",
            tensorIndex = 0,
            tensorName = "X",
            dataType = TensorDataType.FLOAT32,
            runtimeShape = listOf(3, 2),
            quantization = InputQuantization(QuantizationMode.NONE),
        )
        val output = OutputComponent(
            role = OutputRole.IMAGE_FEATURES,
            artifactRole = "primary",
            tensorIndex = 0,
            tensorName = "Y",
            dataType = TensorDataType.FLOAT32,
            runtimeShape = listOf(3, 2),
        )
        OnnxArtifactSession(
            ArtifactSessionContext(
                artifact = artifact,
                artifactFile = modelFile,
                inputSpecs = listOf(input),
                outputSpecs = listOf(output),
                numberOfThreads = 2,
            ),
        ).use { session ->
            val bytes = ByteBuffer.allocate(6 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .also { buffer -> (1..6).forEach { buffer.putFloat(it.toFloat()) } }
                .array()
            val result = session.run(
                mapOf(
                    "X" to RawTensor("X", bytes, listOf(3, 2), TensorElementType.FLOAT32),
                ),
            ).getValue("Y")
            val values = ByteBuffer.wrap(result.bytes)
                .order(ByteOrder.nativeOrder())
                .let { buffer -> FloatArray(6) { buffer.float } }
            assertArrayEquals(floatArrayOf(1f, 4f, 9f, 16f, 25f, 36f), values, 0f)
        }
    }

    @Test
    fun symbolicDimensionsResolveFromManifestAndRunAtConcreteShape() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modelFile = copyAsset(context, "identity_dynamic.ortfixture")
        dynamicSession(modelFile, inputShape = listOf(2, 3), outputShape = listOf(2, 3)).use { session ->
            val expected = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
            val output = session.run(
                mapOf("X" to floatTensor("X", expected, listOf(2, 3))),
            ).getValue("Y")

            assertArrayEquals(expected, output.floatValues(), 0f)
        }
    }

    @Test
    fun runRejectsInputShapeThatDiffersFromManifest() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modelFile = copyAsset(context, "identity_dynamic.ortfixture")
        dynamicSession(modelFile, inputShape = listOf(2, 3), outputShape = listOf(2, 3)).use { session ->
            assertThrows(IllegalArgumentException::class.java) {
                session.run(
                    mapOf(
                        "X" to floatTensor(
                            "X",
                            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
                            listOf(3, 2),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun actualOutputShapeThatDiffersFromManifestIsRejected() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modelFile = copyAsset(context, "identity_dynamic.ortfixture")
        dynamicSession(modelFile, inputShape = listOf(2, 3), outputShape = listOf(2, 4)).use { session ->
            assertThrows(IllegalArgumentException::class.java) {
                session.run(
                    mapOf(
                        "X" to floatTensor(
                            "X",
                            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
                            listOf(2, 3),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun staticModelDimensionThatDiffersFromManifestIsRejectedAtInitialization() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modelFile = copyAsset(context, "mul_1.onnx")
        assertThrows(IllegalArgumentException::class.java) {
            OnnxArtifactSession(
                ArtifactSessionContext(
                    artifact = ArtifactComponent(
                        role = "primary",
                        runtime = RuntimeKind.ONNX,
                        mediaType = ArtifactMediaType.ONNX,
                        url = "https://github.com/microsoft/onnxruntime/raw/main/onnxruntime/test/testdata/mul_1.onnx",
                        sha256 = "71f431c4e9321ec6fbeb158d02ed240459a7dcc98673fa79a4f439ce42efaf10",
                        sizeBytes = 130,
                    ),
                    artifactFile = modelFile,
                    inputSpecs = listOf(inputComponent(listOf(2, 3))),
                    outputSpecs = listOf(outputComponent(listOf(3, 2))),
                    numberOfThreads = 2,
                ),
            )
        }
    }

    private fun dynamicSession(
        modelFile: java.io.File,
        inputShape: List<Int>,
        outputShape: List<Int>,
    ): OnnxArtifactSession = OnnxArtifactSession(
        ArtifactSessionContext(
            artifact = ArtifactComponent(
                role = "primary",
                runtime = RuntimeKind.ONNX,
                mediaType = ArtifactMediaType.ONNX,
                url = "fixture://identity_dynamic.onnx",
                sha256 = "4bd0d3736bdcfd4ff7c2ed7b4cae8b17e23035b1593a8c989e9d05f599c484be",
                sizeBytes = 138,
            ),
            artifactFile = modelFile,
            inputSpecs = listOf(inputComponent(inputShape)),
            outputSpecs = listOf(outputComponent(outputShape)),
            numberOfThreads = 2,
        ),
    )

    private fun inputComponent(shape: List<Int>) = InputComponent(
        role = InputRole.IMAGE_FEATURES,
        artifactRole = "primary",
        tensorIndex = 0,
        tensorName = "X",
        dataType = TensorDataType.FLOAT32,
        runtimeShape = shape,
        quantization = InputQuantization(QuantizationMode.NONE),
    )

    private fun outputComponent(shape: List<Int>) = OutputComponent(
        role = OutputRole.IMAGE_FEATURES,
        artifactRole = "primary",
        tensorIndex = 0,
        tensorName = "Y",
        dataType = TensorDataType.FLOAT32,
        runtimeShape = shape,
    )

    private fun floatTensor(name: String, values: FloatArray, shape: List<Int>): RawTensor {
        val bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { buffer -> values.forEach(buffer::putFloat) }
            .array()
        return RawTensor(name, bytes, shape, TensorElementType.FLOAT32)
    }

    private fun RawTensor.floatValues(): FloatArray = ByteBuffer.wrap(bytes)
        .order(ByteOrder.nativeOrder())
        .let { buffer -> FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float } }

    private fun copyAsset(
        context: android.content.Context,
        assetName: String,
    ): java.io.File = java.io.File(context.cacheDir, assetName).also { target ->
        context.assets.open(assetName).use { input ->
            target.outputStream().use(input::copyTo)
        }
    }
}
