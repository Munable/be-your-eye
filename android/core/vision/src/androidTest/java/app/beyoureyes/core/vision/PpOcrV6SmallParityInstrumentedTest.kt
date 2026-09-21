package app.beyoureyes.core.vision

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local-only parity for one generic `reading_pipeline_v1` package.
 *
 * The exact ONNX and raw Python output are ignored local assets. Absence skips these tests instead
 * of putting third-party model bytes into source control. No package ID or publisher branch exists
 * in production code: the test uses the same finite preprocessor, ONNX session and CTC adapter
 * registrations that any compatible Manifest package uses.
 */
@RunWith(AndroidJUnit4::class)
class PpOcrV6SmallParityInstrumentedTest {
    @Test
    fun fixed320SameCropMatchesPythonInputRawOutputAndNumericDecode() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        requireLocalAssets(context)
        val reference = readReference(context)
        val manifest = manifest(context)
        assertTrue(manifest.structuralErrors().isEmpty())
        val files = copyRuntimeAssets(context)
        val canonical = canonicalFrame(context)
        val registry = ManifestRuntimeComponents.registry()
        val componentContext = RuntimeComponentContext(
            manifest = manifest,
            targetProfile = null,
            artifactFile = files.getValue(PRIMARY_ROLE),
            artifactFilesByRole = files,
            numberOfThreads = 4,
        )

        val preprocessor = checkNotNull(registry.preprocessor(manifest.preprocessId))
            .factory.create(componentContext)
        val prepared = preprocessor.prepare(canonical)
        val pythonInput = readAssetBytes(context, INPUT_REFERENCE_ASSET).asFloatArray()
        val androidInput = prepared.bytes.asFloatArray()
        val inputDifference = differences(androidInput, pythonInput)
        assertEquals(reference.objectField("input").string("sha256"), prepared.bytes.sha256())
        assertEquals(0f, inputDifference.maximum, 0f)

        val session = checkNotNull(registry.artifactSession(RuntimeKind.ONNX)).factory.create(
            ArtifactSessionContext(
                artifact = manifest.artifacts.single { it.role == PRIMARY_ROLE },
                artifactFile = files.getValue(PRIMARY_ROLE),
                inputSpecs = manifest.inputs.filter { it.artifactRole == PRIMARY_ROLE },
                outputSpecs = manifest.outputs.filter { it.artifactRole == PRIMARY_ROLE },
                numberOfThreads = 4,
            ),
        )
        val output = session.use {
            it.run(
                mapOf(
                    INPUT_NAME to RawTensor(
                        name = INPUT_NAME,
                        bytes = prepared.bytes,
                        shape = prepared.shape,
                        elementType = prepared.elementType,
                    ),
                ),
            ).getValue(OUTPUT_NAME)
        }
        val pythonOutput = readAssetBytes(context, OUTPUT_REFERENCE_ASSET).asFloatArray()
        val androidOutput = output.bytes.asFloatArray()
        val outputDifference = differences(androidOutput, pythonOutput)
        val limits = reference.objectField("android_comparison_limits")
        assertTrue(
            "raw ONNX maximum absolute delta ${outputDifference.maximum}",
            outputDifference.maximum <= limits.float("output_max_abs"),
        )
        assertTrue(
            "raw ONNX mean absolute delta ${outputDifference.mean}",
            outputDifference.mean <= limits.float("output_mean_abs"),
        )

        val adapter = checkNotNull(registry.adapter(manifest.postprocessId)).factory.create(
            componentContext,
        )
        val observation = adapter.toObservation(
            RawTensorOutput(listOf(output)),
            prepared.transform,
            canonical,
        ) as app.beyoureyes.core.domain.Observation.Reading
        val decode = reference.objectField("decode")
        assertEquals(decode.string("text"), observation.text)
        assertEquals(decode.string("value_decimal"), observation.valueDecimal)
        assertEquals(decode.float("score"), observation.confidence, 0.0001f)

        val result = linkedMapOf<String, Any>(
            "schema_version" to "1.0",
            "device" to linkedMapOf(
                "api" to android.os.Build.VERSION.SDK_INT,
                "abi" to android.os.Build.SUPPORTED_ABIS.first(),
                "fingerprint" to android.os.Build.FINGERPRINT,
                "is_emulator" to isEmulator(),
            ),
            "model_sha256" to files.getValue(PRIMARY_ROLE).readBytes().sha256(),
            "vocabulary_sha256" to files.getValue(VOCABULARY_ROLE).readBytes().sha256(),
            "input" to linkedMapOf(
                "android_sha256" to prepared.bytes.sha256(),
                "python_sha256" to reference.objectField("input").string("sha256"),
                "max_abs_delta" to inputDifference.maximum,
                "mean_abs_delta" to inputDifference.mean,
            ),
            "output" to linkedMapOf(
                "android_sha256" to output.bytes.sha256(),
                "python_sha256" to reference.objectField("output").string("sha256"),
                "max_abs_delta" to outputDifference.maximum,
                "mean_abs_delta" to outputDifference.mean,
            ),
            "decode" to linkedMapOf(
                "android_text" to observation.text,
                "python_text" to decode.string("text"),
                "android_score" to observation.confidence,
                "python_score" to decode.float("score"),
                "score_abs_delta" to abs(observation.confidence - decode.float("score")),
            ),
            "classification" to "development_recipe_runtime_parity_not_accuracy_or_release",
        )
        val resultJson = GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n"
        checkNotNull(context.getExternalFilesDir(null))
            .resolve(RESULT_FILENAME)
            .writeText(resultJson)
        Log.i(LOG_TAG, resultJson.replace('\n', ' '))
    }

    @Test
    fun internalEvaluationRunsThroughFactoryWhileCommercialActivationFailsClosed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        requireLocalAssets(context)
        val manifest = manifest(context)
        val files = copyRuntimeAssets(context)
        val verified = VerifiedModelPackage(
            manifest = manifest,
            catalogEntryActive = true,
            catalogSignatureValid = true,
            catalogManifestSha256Matches = true,
            manifestSignatureValid = true,
            artifactSha256Valid = true,
            licenseTextSha256Valid = true,
        )
        val factory = ModelPackageRuntimeFactory(
            registry = ManifestRuntimeComponents.registry(),
            normalizer = UprightRgbFrameNormalizer,
            qualityGate = AcceptAllQualityGate,
        )
        val internal = factory.create(
            ModelRuntimeRequest(
                verifiedPackage = verified,
                artifactFile = files.getValue(PRIMARY_ROLE),
                artifactFilesByRole = files,
                buildChannel = BuildChannel.INTERNAL_EVALUATION,
                numberOfThreads = 4,
            ),
        ) as RuntimeCreationResult.Ready
        internal.runtime.use { runtime ->
            val processed = runtime.process(sourceFrame(context)) as RuntimeFrameResult.Processed
            val reading = processed.pipelineResult.observation as
                app.beyoureyes.core.domain.Observation.Reading
            assertEquals("-12.3", reading.text)
            assertFalse(reading.stable)
        }

        val commercial = factory.create(
            ModelRuntimeRequest(
                verifiedPackage = verified,
                artifactFile = files.getValue(PRIMARY_ROLE),
                artifactFilesByRole = files,
                buildChannel = BuildChannel.COMMERCIAL,
                numberOfThreads = 4,
            ),
        )
        if (!readReference(context).objectField("license").boolean("redistribution_allowed")) {
            val unavailable = commercial as RuntimeCreationResult.Unavailable
            assertTrue(RuntimeActivationError.REDISTRIBUTION_NOT_ALLOWED in unavailable.errors)
        } else {
            (commercial as RuntimeCreationResult.Ready).runtime.close()
        }
    }

    internal fun manifest(context: android.content.Context): ModelPackageManifest {
        val knownAnswerRgb = canonicalFrame(context).rgb888
        val reference = readReference(context)
        val model = reference.objectField("model")
        val license = reference.objectField("license")
        val modelVariant = model.string("variant")
        val modelRevision = model.string("revision")
        val modelSha256 = model.string("sha256")
        val modelSizeBytes = model.long("size_bytes")
        val repositoryId = model.string("repository_id")
        return ModelPackageManifest(
        schemaVersion = "3.0",
        packageId = "numeric_reader_ppocrv6_${modelVariant}_v1",
        packageVersion = "0.1.0-internal-parity.1",
        runtimeFamily = RecipeFamily.READING_PIPELINE_V1,
        supportedTasks = setOf(SupportedTask.STRUCTURED_READING),
        promptModes = setOf(TargetMode.NONE),
        modelSource = SourceReference(
            "https://github.com/PaddlePaddle/PaddleOCR/tree/$PADDLEOCR_REVISION",
            PADDLEOCR_REVISION,
        ),
        weightsSource = SourceReference(
            "https://huggingface.co/$repositoryId/tree/$modelRevision",
            modelRevision,
        ),
        codeSource = SourceReference(
            "https://github.com/PaddlePaddle/PaddleOCR/tree/$PADDLEOCR_REVISION",
            PADDLEOCR_REVISION,
        ),
        exportToolSource = SourceReference(
            "https://github.com/PaddlePaddle/PaddleOCR/blob/$PADDLEOCR_REVISION/docs/version3.x/inference_deployment/others/obtaining_onnx_models.en.md",
            "exact_converter_revisions_and_command_pending",
        ),
        license = ModelPackageLicense(
            licenseId = license.string("license_id"),
            licenseTextSha256 = license.string("license_text_sha256"),
            reviewStatus = LicenseReviewStatus.APPROVED,
            reviewedAt = "2026-08-03T01:22:23Z",
            reviewEvidenceRef =
                "model-tools/v3/reviews/ppocrv6-$modelVariant-rec-onnx-license-review.md",
            commercialUseAllowed = license.boolean("commercial_use_allowed"),
            redistributionAllowed = license.boolean("redistribution_allowed"),
            sourceDisclosureRequired = license.boolean("source_disclosure_required"),
        ),
        artifacts = listOf(
            ArtifactComponent(
                role = PRIMARY_ROLE,
                runtime = RuntimeKind.ONNX,
                mediaType = ArtifactMediaType.ONNX,
                url = "https://huggingface.co/$repositoryId/resolve/$modelRevision/inference.onnx",
                sha256 = modelSha256,
                sizeBytes = modelSizeBytes,
            ),
            ArtifactComponent(
                role = VOCABULARY_ROLE,
                runtime = RuntimeKind.CTC_VOCABULARY,
                mediaType = ArtifactMediaType.CTC_VOCABULARY_JSON,
                url = "local-isolated-evaluation://ppocrv6-$modelVariant/ctc-vocabulary.json",
                sha256 = VOCABULARY_SHA256,
                sizeBytes = VOCABULARY_SIZE_BYTES,
            ),
        ),
        inputs = listOf(
            InputComponent(
                role = InputRole.IMAGE,
                artifactRole = PRIMARY_ROLE,
                tensorIndex = 0,
                tensorName = INPUT_NAME,
                dataType = TensorDataType.FLOAT32,
                runtimeShape = INPUT_SHAPE,
                quantization = InputQuantization(QuantizationMode.NONE),
                layout = TensorLayout.NCHW,
                colorSpace = ColorSpace.BGR,
            ),
        ),
        outputs = listOf(
            OutputComponent(
                role = OutputRole.CTC_LOGITS,
                artifactRole = PRIMARY_ROLE,
                tensorIndex = 0,
                tensorName = OUTPUT_NAME,
                dataType = TensorDataType.FLOAT32,
                runtimeShape = OUTPUT_SHAPE,
            ),
        ),
        bindings = emptyList(),
        adapterContract = AdapterContract(
            schemaId = ManifestRuntimeComponents.OUTPUT_NUMERIC_READING,
            classMap = null,
            embeddedPostprocess = null,
        ),
        preprocessId = ManifestRuntimeComponents.PREPROCESS_PPOCR_AUTO_LOCATE,
        postprocessId = ManifestRuntimeComponents.ADAPTER_NUMERIC_CTC,
        ctcDecoding = CtcDecodingSpec(
            vocabularyArtifactRole = VOCABULARY_ROLE,
            blankIndex = 0,
            indexSemantics = CtcIndexSemantics.ZERO_BASED_TOKEN_ORDER_V1,
            collapseSemantics = CtcCollapseSemantics.CTC_GREEDY_ARGMAX_V1,
            scoreSemantics = CtcScoreSemantics.PROBABILITIES_V1,
        ),
        selfTest = ReadingKnownAnswerSelfTest(
            schemaId = "reading_known_answer_v1",
            artifactRole = PRIMARY_ROLE,
            artifactSha256 = modelSha256,
            input = ReadingKnownAnswerInput(
                encoding = "rgb888_base64_v1",
                width = 320,
                height = 48,
                rgb888Base64 = Base64.getEncoder().encodeToString(knownAnswerRgb),
                sha256 = knownAnswerRgb.sha256(),
            ),
            expected = ReadingKnownAnswerExpected(
                text = "-12.3",
                valueDecimal = "-12.3",
                unit = null,
                minimumConfidence = 0f,
            ),
        ),
        parameterProfile = ParameterProfile(
            defaults = mapOf(
                READING_MINIMUM_CONFIDENCE_PARAMETER to 0.0,
                READING_LOCATOR_PIXEL_THRESHOLD_PARAMETER to 0.2,
                READING_LOCATOR_BOX_THRESHOLD_PARAMETER to 0.4,
                READING_LOCATOR_UNCLIP_RATIO_PARAMETER to 1.4,
                READING_LOCATOR_MAX_CANDIDATES_PARAMETER to 3000.0,
            ),
            allowedBounds = mapOf(
                READING_MINIMUM_CONFIDENCE_PARAMETER to ParameterBound(0.0, 1.0),
                READING_LOCATOR_PIXEL_THRESHOLD_PARAMETER to ParameterBound(0.0, 1.0),
                READING_LOCATOR_BOX_THRESHOLD_PARAMETER to ParameterBound(0.0, 1.0),
                READING_LOCATOR_UNCLIP_RATIO_PARAMETER to ParameterBound(1.0, 3.0),
                READING_LOCATOR_MAX_CANDIDATES_PARAMETER to ParameterBound(1.0, 3000.0),
            ),
            samplingPolicy = SamplingPolicySpec(500, 250, 2_000, true),
        ),
        deviceCompatibility = DeviceCompatibility(
            deviceProfileIds = setOf("android_arm64_8gb_launch_v1"),
            minAndroidApi = 26,
            supportedAbis = setOf("arm64-v8a"),
            minimumMemoryMb = 8_192,
            requiredFeatures = emptySet(),
        ),
        signature = ManifestSignature("RFC8785", "Ed25519", "test-only-not-releasable", "AA=="),
        )
    }

    private fun requireLocalAssets(context: android.content.Context) {
        val available = context.assets.list("")?.toSet().orEmpty()
        assumeTrue("local ignored exact ONNX asset is absent", MODEL_ASSET in available)
        assumeTrue("local ignored Python raw output asset is absent", OUTPUT_REFERENCE_ASSET in available)
    }

    private fun copyRuntimeAssets(context: android.content.Context): Map<String, File> = mapOf(
        PRIMARY_ROLE to copyAsset(context, MODEL_ASSET),
        VOCABULARY_ROLE to copyAsset(context, VOCABULARY_ASSET),
    )

    private fun copyAsset(context: android.content.Context, assetName: String): File =
        File(context.cacheDir, "ppocr-${assetName.substringAfterLast('/')}").also { target ->
            context.assets.open(assetName).use { input -> target.outputStream().use(input::copyTo) }
        }

    private fun canonicalFrame(context: android.content.Context): CanonicalFrame {
        val bitmap = context.assets.open(IMAGE_ASSET).use(BitmapFactory::decodeStream)
        checkNotNull(bitmap)
        require(bitmap.width == 320 && bitmap.height == 48)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { index, pixel ->
            rgb[index * 3] = ((pixel ushr 16) and 0xff).toByte()
            rgb[index * 3 + 1] = ((pixel ushr 8) and 0xff).toByte()
            rgb[index * 3 + 2] = (pixel and 0xff).toByte()
        }
        bitmap.recycle()
        return CanonicalFrame(1, 0, null, 320, 48, rgb)
    }

    private fun sourceFrame(context: android.content.Context): SourceFrame {
        val canonical = canonicalFrame(context)
        return SourceFrame(
            sourceSequence = canonical.sourceSequence,
            monotonicTimeMillis = canonical.monotonicTimeMillis,
            capturedAtEpochMillis = null,
            width = canonical.width,
            height = canonical.height,
            rotationDegrees = 0,
            cropRect = PixelRect(0, 0, canonical.width, canonical.height),
            pixels = FramePixels.Rgb888(canonical.rgb888, canonical.width * 3),
        )
    }

    private fun readReference(context: android.content.Context): JsonObject =
        context.assets.open(REFERENCE_ASSET).bufferedReader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }

    private fun readAssetBytes(context: android.content.Context, assetName: String): ByteArray =
        context.assets.open(assetName).use { it.readBytes() }

    private fun ByteArray.asFloatArray(): FloatArray = ByteBuffer.wrap(this)
        .order(ByteOrder.LITTLE_ENDIAN)
        .let { buffer -> FloatArray(size / Float.SIZE_BYTES) { buffer.float } }

    private fun differences(actual: FloatArray, expected: FloatArray): Difference {
        require(actual.size == expected.size)
        var maximum = 0f
        var sum = 0.0
        actual.indices.forEach { index ->
            val delta = abs(actual[index] - expected[index])
            if (delta > maximum) maximum = delta
            sum += delta
        }
        return Difference(maximum, (sum / actual.size).toFloat())
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun JsonObject.objectField(name: String): JsonObject = getAsJsonObject(name)
    private fun JsonObject.string(name: String): String = get(name).asString
    private fun JsonObject.float(name: String): Float = get(name).asFloat
    private fun JsonObject.long(name: String): Long = get(name).asLong
    private fun JsonObject.boolean(name: String): Boolean = get(name).asBoolean

    private fun isEmulator(): Boolean = android.os.Build.FINGERPRINT.startsWith("generic") ||
        android.os.Build.FINGERPRINT.contains("emulator") ||
        android.os.Build.MODEL.contains("sdk_gphone")

    private data class Difference(val maximum: Float, val mean: Float)

    private companion object {
        const val LOG_TAG = "PPOCR_PARITY"
        const val RESULT_FILENAME = "ppocr-fixed320-parity-result.json"
        const val PADDLEOCR_REVISION = "2661c7c0ef5c613e8f93c6e93b2e052399f0f854"
        const val VOCABULARY_SHA256 =
            "684a00dd6ca9dd491468412c305aee6a450f69a2800108ddb23837c15fbcaf49"
        const val VOCABULARY_SIZE_BYTES = 112_437L
        const val PRIMARY_ROLE = "primary"
        const val VOCABULARY_ROLE = "vocabulary"
        const val INPUT_NAME = "x"
        const val OUTPUT_NAME = "fetch_name_0"
        const val MODEL_ASSET = "inference.onnx"
        const val VOCABULARY_ASSET = "ctc-vocabulary.json"
        const val IMAGE_ASSET = "fixed320-numeric.png"
        const val INPUT_REFERENCE_ASSET = "fixed320-input.f32le.bin"
        const val OUTPUT_REFERENCE_ASSET = "fixed320-output.f32le.bin"
        const val REFERENCE_ASSET = "fixed320-reference.json"
        val INPUT_SHAPE = listOf(1, 3, 48, 320)
        val OUTPUT_SHAPE = listOf(1, 40, 18_710)
    }
}
