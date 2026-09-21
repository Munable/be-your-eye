package app.beyoureyes.core.vision

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.domain.GenericObservationRuleEngine
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.ReadingOperator
import app.beyoureyes.core.domain.RuntimeMonitorRule
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API 36 replay of the pre-registered synthetic PTS frame sequence.
 *
 * This goes through the generic Manifest reading family from RGB pixels to ONNX, numeric CTC,
 * reading stability and numeric event projection. It is deliberately not an accuracy, tuning,
 * performance, package-selection or release test. Exact model/tensor assets stay local/ignored.
 */
@RunWith(AndroidJUnit4::class)
class PpOcrV6SmallTemporalReplayInstrumentedTest {
    @Test
    fun signedFixed320PtsFramesMatchFrozenInputDecodeAndEventSemantics() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val reference = readJsonAsset(context, REFERENCE_ASSET)
        requireLocalAssets(context, reference)
        val manifest = PpOcrV6SmallParityInstrumentedTest().manifest(context)
        assertTrue(manifest.structuralErrors().isEmpty())
        val runtimeFiles = mapOf(
            PRIMARY_ROLE to copyAsset(context, MODEL_ASSET),
            VOCABULARY_ROLE to copyAsset(context, VOCABULARY_ASSET),
        )
        val componentContext = RuntimeComponentContext(
            manifest = manifest,
            targetProfile = null,
            artifactFile = runtimeFiles.getValue(PRIMARY_ROLE),
            artifactFilesByRole = runtimeFiles,
            numberOfThreads = 4,
        )
        val preprocessor = checkNotNull(
            ManifestRuntimeComponents.registry().preprocessor(manifest.preprocessId),
        ).factory.create(componentContext)
        val sourceSpecs = reference.objectField("source").objectField("crops")
        val framesByAlias = sourceSpecs.entrySet().associate { (alias, element) ->
            alias to decodeFrame(context, element.asJsonObject)
        }
        val limits = reference.objectField("pre_registered_android_comparison_limits")
        val inputParity = sourceSpecs.entrySet().sortedBy { it.key }.map { (alias, element) ->
            val spec = element.asJsonObject
            val decoded = framesByAlias.getValue(alias)
            assertEquals(spec.string("decoded_rgb_sha256"), decoded.rgb888.sha256())
            val canonical = CanonicalFrame(
                sourceSequence = 0,
                monotonicTimeMillis = 0,
                capturedAtEpochMillis = null,
                width = decoded.width,
                height = decoded.height,
                rgb888 = decoded.rgb888,
            )
            val prepared = preprocessor.prepare(canonical)
            val python = readAssetBytes(context, spec.string("fixed320_input_asset")).asFloatArray()
            val android = prepared.bytes.asFloatArray()
            val difference = differences(android, python)
            assertTrue(
                "$alias fixed320 input max abs ${difference.maximum}",
                difference.maximum <= limits.float("fixed320_input_max_abs"),
            )
            assertTrue(
                "$alias fixed320 input mean abs ${difference.mean}",
                difference.mean <= limits.float("fixed320_input_mean_abs"),
            )
            linkedMapOf<String, Any>(
                "source_alias" to alias,
                "decoded_rgb_sha256" to decoded.rgb888.sha256(),
                "android_input_sha256" to prepared.bytes.sha256(),
                "python_input_sha256" to spec.string("fixed320_input_sha256"),
                "max_abs_delta" to difference.maximum,
                "mean_abs_delta" to difference.mean,
            )
        }

        val verified = VerifiedModelPackage(
            manifest = manifest,
            catalogEntryActive = true,
            catalogSignatureValid = true,
            catalogManifestSha256Matches = true,
            manifestSignatureValid = true,
            artifactSha256Valid = true,
            licenseTextSha256Valid = true,
        )
        val creation = ModelPackageRuntimeFactory(
            registry = ManifestRuntimeComponents.registry(),
            normalizer = UprightRgbFrameNormalizer,
            qualityGate = AcceptAllQualityGate,
        ).create(
            ModelRuntimeRequest(
                verifiedPackage = verified,
                artifactFile = runtimeFiles.getValue(PRIMARY_ROLE),
                artifactFilesByRole = runtimeFiles,
                buildChannel = BuildChannel.INTERNAL_EVALUATION,
                numberOfThreads = 4,
            ),
        )
        assertTrue("generic reading runtime was not ready: $creation", creation is RuntimeCreationResult.Ready)
        val rule = GenericObservationRuleEngine(
            targetId = "structured_reading",
            displayName = "数字读数",
            roi = NormalizedRect(0f, 0f, 1f, 1f),
            rule = RuntimeMonitorRule.ReadingThreshold.Single(
                operator = ReadingOperator.GT,
                thresholdDecimal = "100.0",
                durationMillis = 120,
                hysteresisDecimal = "2.0",
                cooldownMillis = 0,
            ),
        )

        var sampled = 0
        var skipped = 0
        var candidate = 0
        var stable = 0
        var unavailable = 0
        var adapterUnavailable = 0
        var conflicts = 0
        val eventSequences = mutableListOf<Long>()
        val eventEpisodes = mutableListOf<Long>()
        val actualRows = mutableListOf<Map<String, Any?>>()
        val decodedTexts = linkedMapOf<String, String>()
        val timeline = reference.arrayField("timeline")
        (creation as RuntimeCreationResult.Ready).runtime.use { runtime ->
            timeline.forEach { element ->
                val expected = element.asJsonObject
                val sequence = expected.long("source_sequence")
                val pts = expected.long("pts_ms")
                val alias = expected.string("source_alias")
                val decoded = framesByAlias.getValue(alias)
                val result = runtime.process(
                    SourceFrame(
                        sourceSequence = sequence,
                        monotonicTimeMillis = pts,
                        capturedAtEpochMillis = BASE_CAPTURED_AT_EPOCH_MILLIS + pts,
                        width = decoded.width,
                        height = decoded.height,
                        rotationDegrees = 0,
                        cropRect = PixelRect(0, 0, decoded.width, decoded.height),
                        pixels = FramePixels.Rgb888(decoded.rgb888, decoded.width * RGB_CHANNELS),
                    ),
                )
                val expectedSampled = expected.boolean("sampled")
                if (!expectedSampled) {
                    assertTrue("source $sequence should be sampled out", result is RuntimeFrameResult.Skipped)
                    skipped += 1
                    actualRows += linkedMapOf(
                        "source_sequence" to sequence,
                        "pts_ms" to pts,
                        "source_alias" to alias,
                        "sampled" to false,
                    )
                    return@forEach
                }

                assertTrue("source $sequence should be processed", result is RuntimeFrameResult.Processed)
                sampled += 1
                val processed = result as RuntimeFrameResult.Processed
                assertEquals(sequence, processed.sourceSequence)
                val observation = processed.pipelineResult.observation
                val expectedObservation = expected.objectField("observation")
                val actualObservation = when (observation) {
                    is Observation.Reading -> {
                        assertEquals("reading", expectedObservation.string("kind"))
                        assertEquals(expectedObservation.string("text"), observation.text)
                        assertEquals(
                            expectedObservation.string("value_decimal"),
                            observation.valueDecimal,
                        )
                        assertEquals(expectedObservation.boolean("stable"), observation.stable)
                        decodedTexts.putIfAbsent(alias, observation.text)
                        if (observation.stable) stable += 1 else candidate += 1
                        linkedMapOf<String, Any?>(
                            "kind" to "reading",
                            "text" to observation.text,
                            "value_decimal" to observation.valueDecimal,
                            "stable" to observation.stable,
                            "confidence" to observation.confidence,
                        )
                    }
                    is Observation.Unavailable -> {
                        assertEquals("unavailable", expectedObservation.string("kind"))
                        assertEquals(
                            expectedObservation.string("diagnostic_code"),
                            observation.diagnosticCode,
                        )
                        unavailable += 1
                        if (observation.diagnosticCode == "numeric_decode_invalid") {
                            adapterUnavailable += 1
                        }
                        if (observation.diagnosticCode == "reading_value_conflict") conflicts += 1
                        linkedMapOf<String, Any?>(
                            "kind" to "unavailable",
                            "reason" to observation.reason.name.lowercase(),
                            "diagnostic_code" to observation.diagnosticCode,
                        )
                    }
                    else -> error("reading family emitted ${observation::class.java.simpleName}")
                }
                val event = rule.evaluate(observation, pts)
                val expectedEpisode = expected.nullableLong("event_episode")
                if (expectedEpisode == null) {
                    assertNull("unexpected event at source $sequence", event)
                } else {
                    assertNotNull("missing event at source $sequence", event)
                    assertEquals(expectedEpisode, event!!.episode)
                    assertEquals(sequence, event.sourceSequence)
                    assertEquals(pts, event.triggeredAtMonotonicMillis)
                    eventSequences += event.sourceSequence
                    eventEpisodes += event.episode
                }
                actualRows += linkedMapOf(
                    "source_sequence" to sequence,
                    "pts_ms" to pts,
                    "source_alias" to alias,
                    "sampled" to true,
                    "observation" to actualObservation,
                    "event_episode" to event?.episode,
                    "timings_nanos_observational_only" to linkedMapOf(
                        "normalize" to processed.pipelineResult.timings.normalizeNanos,
                        "quality" to processed.pipelineResult.timings.qualityNanos,
                        "preprocess" to processed.pipelineResult.timings.preprocessNanos,
                        "inference" to processed.pipelineResult.timings.inferenceNanos,
                        "adapter" to processed.pipelineResult.timings.adapterNanos,
                    ),
                )
            }
        }

        val expectedSummary = reference.objectField("expected_summary")
        assertEquals(expectedSummary.int("sampled_frames"), sampled)
        assertEquals(expectedSummary.int("skipped_frames"), skipped)
        assertEquals(expectedSummary.int("stabilized_candidate"), candidate)
        assertEquals(expectedSummary.int("stabilized_stable"), stable)
        assertEquals(expectedSummary.int("stabilized_unavailable"), unavailable)
        assertEquals(expectedSummary.int("adapter_unavailable"), adapterUnavailable)
        assertEquals(expectedSummary.int("conflict_resets"), conflicts)
        assertEquals(
            expectedSummary.getAsJsonArray("event_source_sequences").map { it.asLong },
            eventSequences,
        )
        assertEquals(expectedSummary.int("threshold_events"), eventSequences.size)
        assertEquals(listOf(1L, 2L), eventEpisodes)
        assertEquals("38.0", decodedTexts["low_38_0"])
        assertEquals("224.8", decodedTexts["high_224_8"])
        assertEquals("0.3", decodedTexts["rearm_0_30"])
        assertFalse("invalid numeric crop must not become a reading", decodedTexts.containsKey("invalid_carrot"))

        val result = linkedMapOf<String, Any?>(
            "schema_version" to "1.0",
            "status" to "api36_synthetic_pts_generic_reading_engineering_passed",
            "classification" to (
                "synthetic_pts_frame_sequence_generic_android_engineering_" +
                    "not_accuracy_tuning_selection_or_release"
                ),
            "device" to linkedMapOf(
                "api" to android.os.Build.VERSION.SDK_INT,
                "abi" to android.os.Build.SUPPORTED_ABIS.first(),
                "fingerprint" to android.os.Build.FINGERPRINT,
                "model" to android.os.Build.MODEL,
                "is_emulator" to isEmulator(),
            ),
            "reference_sha256" to readAssetBytes(context, REFERENCE_ASSET).sha256(),
            "model_sha256" to runtimeFiles.getValue(PRIMARY_ROLE).readBytes().sha256(),
            "vocabulary_sha256" to runtimeFiles.getValue(VOCABULARY_ROLE).readBytes().sha256(),
            "runtime" to linkedMapOf(
                "family" to manifest.runtimeFamily.wireValue,
                "preprocess_id" to manifest.preprocessId,
                "adapter_id" to manifest.postprocessId,
                "package_specific_android_backend" to false,
                "production_source_changed_for_test" to false,
                "production_apk_contains_local_model_or_tensor_assets" to false,
            ),
            "decode_parity" to linkedMapOf(
                "signed_fixed320_texts" to decodedTexts,
                "invalid_numeric_diagnostic" to "numeric_decode_invalid",
                "official_dynamic_difference_scope" to reference.objectField("decode_parity_scope"),
            ),
            "preprocess_parity" to linkedMapOf(
                "limits_frozen_before_android_run" to true,
                "results" to inputParity,
            ),
            "summary" to linkedMapOf(
                "source_frames" to timeline.size(),
                "sampled_frames" to sampled,
                "skipped_frames" to skipped,
                "adapter_unavailable" to adapterUnavailable,
                "stabilized_candidate" to candidate,
                "stabilized_stable" to stable,
                "stabilized_unavailable" to unavailable,
                "conflict_resets" to conflicts,
                "threshold_candidate_interruptions" to
                    expectedSummary.int("threshold_candidate_interruptions"),
                "threshold_candidate_interruptions_evidence" to
                    "frozen observation/event projection exact; private rule phase not introspected",
                "threshold_rearms" to expectedSummary.int("threshold_rearms"),
                "threshold_rearms_evidence" to
                    "second exact event after frozen below-hysteresis stable sequence",
                "threshold_events" to eventSequences.size,
                "event_source_sequences" to eventSequences,
            ),
            "timeline" to actualRows,
            "interpretation" to linkedMapOf(
                "proven" to (
                    "API36 accepted the frozen RGB/PTS frame sequence through the generic " +
                        "Manifest reading family and reproduced the fixed320 decode/event semantics."
                    ),
                "not_proven" to (
                    "model accuracy, real-video quality, independent test performance, physical-device " +
                        "latency/power/thermal/stability, release or Catalog eligibility"
                    ),
            ),
        )
        val resultJson = GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n"
        checkNotNull(context.getExternalFilesDir(null)).resolve(RESULT_FILENAME).writeText(resultJson)
        resultJson.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            Log.i(LOG_TAG, "${index.toString().padStart(3, '0')}:$chunk")
        }
    }

    private fun requireLocalAssets(context: android.content.Context, reference: JsonObject) {
        val available = context.assets.list("")?.toSet().orEmpty()
        assumeTrue("local ignored exact ONNX asset is absent", MODEL_ASSET in available)
        reference.objectField("source").objectField("crops").entrySet().forEach { (_, element) ->
            val spec = element.asJsonObject
            assumeTrue(
                "local ignored Python input asset is absent: ${spec.string("fixed320_input_asset")}",
                spec.string("fixed320_input_asset") in available,
            )
        }
    }

    private fun decodeFrame(
        context: android.content.Context,
        spec: JsonObject,
    ): DecodedFrame {
        val bitmap = context.assets.open(spec.string("asset_name")).use(BitmapFactory::decodeStream)
        checkNotNull(bitmap)
        assertEquals(spec.int("width"), bitmap.width)
        assertEquals(spec.int("height"), bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(pixels.size * RGB_CHANNELS)
        pixels.forEachIndexed { index, pixel ->
            rgb[index * RGB_CHANNELS] = ((pixel ushr 16) and 0xff).toByte()
            rgb[index * RGB_CHANNELS + 1] = ((pixel ushr 8) and 0xff).toByte()
            rgb[index * RGB_CHANNELS + 2] = (pixel and 0xff).toByte()
        }
        bitmap.recycle()
        return DecodedFrame(spec.int("width"), spec.int("height"), rgb)
    }

    private fun copyAsset(context: android.content.Context, assetName: String): File =
        File(context.cacheDir, "ppocr-temporal-${assetName.substringAfterLast('/')}").also { target ->
            context.assets.open(assetName).use { input -> target.outputStream().use(input::copyTo) }
        }

    private fun readJsonAsset(context: android.content.Context, name: String): JsonObject =
        context.assets.open(name).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }

    private fun readAssetBytes(context: android.content.Context, name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

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
    private fun JsonObject.arrayField(name: String) = getAsJsonArray(name)
    private fun JsonObject.string(name: String): String = get(name).asString
    private fun JsonObject.int(name: String): Int = get(name).asInt
    private fun JsonObject.long(name: String): Long = get(name).asLong
    private fun JsonObject.float(name: String): Float = get(name).asFloat
    private fun JsonObject.boolean(name: String): Boolean = get(name).asBoolean
    private fun JsonObject.nullableLong(name: String): Long? = get(name).let {
        if (it == null || it.isJsonNull) null else it.asLong
    }

    private fun isEmulator(): Boolean = android.os.Build.FINGERPRINT.startsWith("generic") ||
        android.os.Build.FINGERPRINT.contains("emulator") ||
        android.os.Build.MODEL.contains("sdk_gphone")

    private data class DecodedFrame(val width: Int, val height: Int, val rgb888: ByteArray)
    private data class Difference(val maximum: Float, val mean: Float)

    private companion object {
        const val PRIMARY_ROLE = "primary"
        const val VOCABULARY_ROLE = "vocabulary"
        const val MODEL_ASSET = "inference.onnx"
        const val VOCABULARY_ASSET = "ctc-vocabulary.json"
        const val REFERENCE_ASSET = "temporal-replay-reference.json"
        const val RESULT_FILENAME = "ppocrv6-small-temporal-api36-result.json"
        const val LOG_TAG = "PPOCR_TEMPORAL"
        const val LOG_CHUNK_SIZE = 3_000
        const val RGB_CHANNELS = 3
        const val BASE_CAPTURED_AT_EPOCH_MILLIS = 1_775_000_000_000L
    }
}
