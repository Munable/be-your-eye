package app.beyoureyes.monitor

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.MetadataVerificationException
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.data.reference.UprightReferenceBitmapDecoder
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageManifest
import app.beyoureyes.core.vision.ModelPackageRuntime
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import app.beyoureyes.core.vision.ReferenceImageAsset
import app.beyoureyes.core.vision.ReferenceImageMetadata
import app.beyoureyes.core.vision.ReferenceImageProvider
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.VerifiedModelPackage
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays an externally prepared, immutable image bundle through the exact signed production
 * runtimes. The test APK contains no dataset: Gradle injects the bundle and signed package files
 * only when explicitly requested. This runner records raw per-case outcomes; the host scorer owns
 * the suite-level quality gates. Camera/display effects and MonitoringSession rules are outside
 * this layer and are covered by the separate camera-reel runner.
 */
@RunWith(AndroidJUnit4::class)
class ExternalVisionReplayInstrumentedTest {
    @Test
    fun externalBundleRunsThroughSignedProductionRuntimesAndWritesOneSummary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runExternalVisionReplay") == "true")

        val assets = instrumentation.context.assets
        val suite = parseSuite(readAsset(BUNDLE_MANIFEST))
        val root = File(
            instrumentation.targetContext.cacheDir,
            "external-vision-replay-${System.nanoTime()}",
        ).apply { check(mkdirs()) }
        val results = mutableListOf<CaseResult>()
        val startedAt = System.currentTimeMillis()
        val metadataEvaluatedAt = System.currentTimeMillis()
        var catalogSha256 = ""
        var packageVersions: Map<String, String> = emptyMap()
        try {
            val catalogBytes = readAsset(PACKAGE_CATALOG)
            catalogSha256 = sha256(catalogBytes)
            val catalog = SignedMetadataCodec.decodeAndVerifyCatalog(
                catalogBytes,
                nowEpochMillis = metadataEvaluatedAt,
            )
            assertExpiredCatalogFailsClosed(
                catalogBytes = catalogBytes,
                expiredAtEpochMillis = catalog.validUntilEpochMillis,
            )
            require(suite.catalogVersion == catalog.catalog.catalogVersion) {
                "replay suite Catalog ${suite.catalogVersion} does not match signed Catalog " +
                    catalog.catalog.catalogVersion
            }
            val verifiedAssets = VerifiedReplayAssetStore(root.resolve("bundle-assets"), suite)
                .apply { materializeAndVerifyAll() }
            val injectedIds = assets.list(SIGNED_PACKAGES_ROOT).orEmpty().toSet()
            val requiredIds = if (argumentTrue(arguments, "requireAllReplayPackages", true)) {
                CURRENT_PACKAGE_IDS
            } else {
                suite.cases.mapTo(linkedSetOf(), ReplayCase::packageId)
            }
            assertTrue(
                "missing injected signed packages: ${requiredIds - injectedIds}",
                injectedIds.containsAll(requiredIds),
            )
            assertTrue(
                "unexpected package IDs in replay bundle: ${injectedIds - CURRENT_PACKAGE_IDS}",
                CURRENT_PACKAGE_IDS.containsAll(injectedIds),
            )
            assertTrue(
                "suite references packages that were not injected",
                injectedIds.containsAll(suite.cases.map(ReplayCase::packageId)),
            )

            val packages = injectedIds.associateWith { packageId ->
                loadPackage(packageId, catalog, metadataEvaluatedAt, root)
            }
            packageVersions = packages.mapValues { it.value.manifest.packageVersion }
            validateCaseFamilies(suite.cases, packages)

            suite.cases.groupBy { it.packageId to it.runtimeKey() }.forEach { (key, cases) ->
                val fixture = packages.getValue(key.first)
                when (fixture.manifest.runtimeFamily) {
                    RecipeFamily.OBJECT_DETECTION_V1 ->
                        runObjectCases(fixture, cases, metadataEvaluatedAt, verifiedAssets, results)
                    RecipeFamily.READING_PIPELINE_V1 ->
                        runReadingCases(fixture, cases, metadataEvaluatedAt, verifiedAssets, results)
                    RecipeFamily.SIMILARITY_MATCH_V1 ->
                        runReferenceCases(fixture, cases, metadataEvaluatedAt, root, verifiedAssets, results)
                }
            }
        } catch (error: Throwable) {
            results += CaseResult(
                id = "__runner__",
                datasetId = "__runner__",
                packageId = "__runner__",
                kind = "runner",
                inputMode = RUNNER_INPUT_MODE,
                targetId = null,
                outcome = "runner_error",
                expected = null,
                actual = error.stackTraceToString(),
                observation = "error",
            )
        } finally {
            val summary = buildSummary(
                suite = suite,
                catalogSha256 = catalogSha256,
                packageVersions = packageVersions,
                startedAt = startedAt,
                metadataEvaluatedAt = metadataEvaluatedAt,
                results = results,
            )
            writeSummary(summary)
            root.deleteRecursively()
        }

        assertEquals("every replay case must produce one result", suite.cases.size, results.size)
        assertTrue(
            "replay infrastructure error; pull $SUMMARY_FILENAME for details",
            results.none { it.outcome == "runner_error" },
        )
    }

    private fun runObjectCases(
        fixture: PackageFixture,
        cases: List<ReplayCase>,
        now: Long,
        verifiedAssets: VerifiedReplayAssetStore,
        results: MutableList<CaseResult>,
    ) {
        val targetId = checkNotNull(cases.first().targetId)
        val target = checkNotNull(fixture.manifest.adapterContract.classMap)
            .targets.singleOrNull { it.targetId == targetId }
            ?: error("$targetId is absent from ${fixture.manifest.packageId} signed class map")
        createRuntime(
            fixture = fixture,
            now = now,
            targetProfile = TargetProfile.ObjectClass(
                targetId = target.targetId,
                labelZhCn = target.labelZhCn,
                labelEn = target.labelEn,
            ),
        ).use { runtime ->
            cases.forEach { case ->
                results += captureCase(case) {
                    runtime.resetSampling()
                    val observation = runtime.process(
                        decodeFrame(case.asset, 0, verifiedAssets),
                    )
                        .processedObservation()
                    val expectedPresent = case.expected.get("present").asBoolean
                    when (observation) {
                        is Observation.Detections -> {
                            val detections = observation.items.filter { it.label == targetId }
                            val actualPresent = detections.isNotEmpty()
                            val sourceBoxes = case.expected.getAsJsonArray("source_boxes")
                                ?.map { it.asJsonObject.toRect() }
                                .orEmpty()
                            val bestIou = detections.maxOfOrNull { actual ->
                                sourceBoxes.maxOfOrNull { expected -> iou(actual.box, expected) } ?: 0f
                            }
                            CaseResult(
                                case,
                                outcome = when {
                                    !expectedPresent && actualPresent -> "false_positive"
                                    !expectedPresent -> "correct_absence"
                                    !actualPresent -> "miss"
                                    checkNotNull(bestIou) < MINIMUM_OBJECT_IOU -> "localization_mismatch"
                                    else -> "hit"
                                },
                                actual = buildString {
                                    append("present=$actualPresent,detections=${detections.size}")
                                    if (bestIou != null) append(",best_iou=$bestIou")
                                    // The production target profile filters other labels before this point.
                                    append(",top_target_detections=[")
                                    append(
                                        observation.items
                                            .sortedByDescending { it.score }
                                            .take(MAX_TARGET_DIAGNOSTIC_DETECTIONS)
                                            .joinToString("|") { detection ->
                                                val box = detection.box
                                                "${detection.label}:${detection.score}@" +
                                                    "[${box.left},${box.top},${box.right},${box.bottom}]"
                                            },
                                    )
                                    append("]")
                                },
                                observation = "detections",
                            )
                        }
                        is Observation.Unavailable -> CaseResult(
                            case,
                            outcome = "unavailable",
                            actual = observation.diagnosticCode ?: observation.reason.name,
                            observation = "unavailable",
                        )
                        else -> CaseResult(case, "runner_error", observation.toString(), "wrong_type")
                    }
                }
            }
        }
    }

    private fun runReadingCases(
        fixture: PackageFixture,
        cases: List<ReplayCase>,
        now: Long,
        verifiedAssets: VerifiedReplayAssetStore,
        results: MutableList<CaseResult>,
    ) {
        createRuntime(fixture, now, targetProfile = null).use { runtime ->
            cases.forEach { case ->
                results += captureCase(case) {
                    runtime.updateManualReadingScanRegion(case.inputCrop)
                    runtime.resetAutomaticReadingAnchor()
                    runtime.resetSampling()
                    val observation = runtime.process(
                        decodeFrame(case.asset, 0, verifiedAssets),
                    )
                        .processedObservation()
                    val expectedExact = case.expected.nullableString("expected_exact")
                        ?: case.expected.nullableString("value_decimal")
                    val expectedDigits = case.expected.nullableString("expected_digits")
                    when (observation) {
                        is Observation.Reading -> CaseResult(
                            case,
                            outcome = if (
                                when {
                                    expectedExact != null -> observation.valueDecimal == expectedExact
                                    expectedDigits != null -> observation.text.filter(Char::isDigit) == expectedDigits
                                    else -> false
                                }
                            ) {
                                "exact"
                            } else {
                                "wrong_valid"
                            },
                            actual = "text=${observation.text},value_decimal=${observation.valueDecimal}",
                            observation = "reading",
                        )
                        is Observation.Unavailable -> CaseResult(
                            case,
                            outcome = "unavailable",
                            actual = observation.diagnosticCode ?: observation.reason.name,
                            observation = "unavailable",
                        )
                        else -> CaseResult(case, "runner_error", observation.toString(), "wrong_type")
                    }
                }
            }
        }
    }

    private fun runReferenceCases(
        fixture: PackageFixture,
        cases: List<ReplayCase>,
        now: Long,
        root: File,
        verifiedAssets: VerifiedReplayAssetStore,
        results: MutableList<CaseResult>,
    ) {
        cases.forEach { case ->
            results += captureCase(case) {
                val referenceAssets = case.references.mapIndexed { index, asset ->
                    decodeReference(asset, "${case.id}-reference-$index", verifiedAssets)
                }
                val byId = referenceAssets.associateBy(ReferenceImageAsset::localAssetId)
                val provider = ReferenceImageProvider { metadata -> byId[metadata.localAssetId] }
                val target = TargetProfile.ReferenceImages(
                    targetId = checkNotNull(case.targetId),
                    images = referenceAssets.mapIndexed { index, asset ->
                        ReferenceImageMetadata(
                            referenceId = "${case.id}-ref-$index",
                            localAssetId = asset.localAssetId,
                            contentSha256 = asset.contentSha256,
                            width = asset.width,
                            height = asset.height,
                        )
                    },
                )
                createRuntime(fixture, now, target, provider, root).use { runtime ->
                    val observation = runtime.process(
                        decodeFrame(case.asset, 0, verifiedAssets),
                    )
                        .processedObservation()
                    val expectedRelation = case.expected.get("reference_relation").asString
                    val expectedState = if (expectedRelation == "same_object") "present" else "absent"
                    val expectedStateId = "${case.targetId}:$expectedState"
                    when (observation) {
                        is Observation.State -> {
                            val actualStateId = observation.stateId
                            CaseResult(
                                case,
                                outcome = if (actualStateId == expectedStateId) {
                                    "correct_$expectedRelation"
                                } else {
                                    "wrong_$expectedRelation"
                                },
                                actual = actualStateId,
                                observation = "state",
                            )
                        }
                        is Observation.Unavailable -> CaseResult(
                            case,
                            outcome = "unavailable",
                            actual = observation.diagnosticCode ?: observation.reason.name,
                            observation = "unavailable",
                        )
                        else -> CaseResult(case, "runner_error", observation.toString(), "wrong_type")
                    }
                }
            }
        }
    }

    private fun createRuntime(
        fixture: PackageFixture,
        now: Long,
        targetProfile: TargetProfile?,
        referenceProvider: ReferenceImageProvider? = null,
        filesRoot: File? = null,
    ): ModelPackageRuntime {
        val registry = if (referenceProvider == null) {
            ManifestRuntimeComponents.registry()
        } else {
            ManifestRuntimeComponents.registry(
                referenceImageProvider = referenceProvider,
                referenceEmbeddingCache = ReferenceEmbeddingCaches.openAppPrivate(checkNotNull(filesRoot)),
            )
        }
        val created = ModelPackageRuntimeFactory(
            registry = registry,
            normalizer = UprightRgbFrameNormalizer,
            qualityGate = ManifestFrameQualityGates.forManifest(fixture.manifest),
            nowEpochMillis = { now },
        ).create(
            ModelRuntimeRequest(
                verifiedPackage = VerifiedModelPackage(
                    manifest = fixture.manifest,
                    catalogEntryActive = true,
                    catalogSignatureValid = true,
                    catalogManifestSha256Matches = true,
                    manifestSignatureValid = true,
                    artifactSha256Valid = true,
                    licenseTextSha256Valid = true,
                ),
                artifactFile = fixture.artifactsByRole.getValue("primary"),
                artifactFilesByRole = fixture.artifactsByRole,
                buildChannel = BuildChannel.INTERNAL_EVALUATION,
                targetProfile = targetProfile,
            ),
        )
        return (created as? RuntimeCreationResult.Ready)?.runtime
            ?: error("${fixture.manifest.packageId} runtime unavailable: $created")
    }

    private fun loadPackage(
        packageId: String,
        catalog: app.beyoureyes.core.data.VerifiedCapabilityCatalog,
        now: Long,
        root: File,
    ): PackageFixture {
        val manifestBytes = readAsset("$SIGNED_PACKAGES_ROOT/$packageId/manifest.json")
        val document = SignedMetadataCodec.decodeAndVerifyManifest(
            manifestBytes,
            catalog,
            packageId,
            nowEpochMillis = now,
        )
        val packageRoot = File(root, packageId).apply { check(mkdirs()) }
        val files = document.manifest.artifacts.associate { artifact ->
            val output = File(packageRoot, artifact.role)
            copyAssetAndVerify(
                assetPath = "$SIGNED_PACKAGES_ROOT/$packageId/artifacts/${artifact.role}",
                expectedSize = artifact.sizeBytes,
                expectedSha256 = artifact.sha256,
                output = output,
            )
            artifact.role to output
        }
        return PackageFixture(document.manifest, files)
    }

    private fun validateCaseFamilies(
        cases: List<ReplayCase>,
        packages: Map<String, PackageFixture>,
    ) {
        cases.forEach { case ->
            val family = packages.getValue(case.packageId).manifest.runtimeFamily
            val expectedKind = when (family) {
                RecipeFamily.OBJECT_DETECTION_V1 -> "object_presence"
                RecipeFamily.READING_PIPELINE_V1 -> "numeric_reading"
                RecipeFamily.SIMILARITY_MATCH_V1 -> "reference_match"
            }
            require(case.kind == expectedKind) {
                "${case.id}: ${case.kind} cannot use $family"
            }
        }
    }

    private fun parseSuite(bytes: ByteArray): ReplaySuite {
        val root = JsonParser.parseString(bytes.decodeToString()).asJsonObject
        require(root.string("schema_version") == BUNDLE_SCHEMA)
        val cases = root.getAsJsonArray("cases").map { element ->
            val case = element.asJsonObject
            val kind = case.string("kind")
            val targetId = case.nullableString("target_id")
            val expected = case.getAsJsonObject("expected")
            val inputMode = expected.nullableString("input_mode") ?: FULL_FRAME_INPUT_MODE
            require(inputMode in INPUT_MODES) {
                "${case.string("id")}: unsupported input_mode $inputMode"
            }
            val inputCrop = expected.get("input_crop")?.let { element ->
                require(element.isJsonObject) { "${case.string("id")}: input_crop must be an object" }
                element.asJsonObject.toInputCrop(case.string("id"))
            }
            when (inputMode) {
                FULL_FRAME_INPUT_MODE -> require(inputCrop == null) {
                    "${case.string("id")}: full_frame must not declare input_crop"
                }
                MANUAL_ROI_INPUT_MODE -> {
                    require(kind == "numeric_reading") {
                        "${case.string("id")}: manual_roi_diagnostic is numeric-only"
                    }
                    require(inputCrop != null) {
                        "${case.string("id")}: manual_roi_diagnostic needs input_crop"
                    }
                }
            }
            val asset = AssetIdentity(
                path = safeAsset(case.string("asset")),
                sha256 = case.requiredSha256("asset_sha256"),
                sizeBytes = case.requiredPositiveLong("asset_size_bytes"),
            )
            val referencePaths = case.getAsJsonArray("references")
                ?.map { safeAsset(it.asString) }
                .orEmpty()
            val referenceAssets = case.getAsJsonArray("reference_asset_identities")
                ?.map { identity ->
                    val value = identity.asJsonObject
                    AssetIdentity(
                        path = safeAsset(value.string("asset")),
                        sha256 = value.requiredSha256("sha256"),
                        sizeBytes = value.requiredPositiveLong("size_bytes"),
                    )
                }
                .orEmpty()
            when (kind) {
                "object_presence" -> {
                    require(!targetId.isNullOrBlank())
                    val present = expected.get("present")?.takeIf { it.isJsonPrimitive }
                        ?.asJsonPrimitive
                    require(present?.isBoolean == true) { "${case.string("id")}: present must be boolean" }
                    val boxes = expected.getAsJsonArray("source_boxes")
                    if (present.asBoolean) {
                        require(boxes != null && boxes.size() > 0) {
                            "${case.string("id")}: positive object case needs source_boxes"
                        }
                    }
                    boxes?.forEach { it.asJsonObject.toRect() }
                    require(referencePaths.isEmpty() && referenceAssets.isEmpty())
                }
                "numeric_reading" -> {
                    val truthFields = listOf("expected_exact", "expected_digits", "value_decimal")
                        .mapNotNull { expected.nullableString(it) }
                    require(truthFields.size == 1 && truthFields.single().isNotBlank()) {
                        "${case.string("id")}: numeric case needs exactly one expected truth field"
                    }
                    expected.nullableString("expected_digits")?.let { digits ->
                        require(digits.all(Char::isDigit)) { "expected_digits must contain only 0-9" }
                    }
                    require(referencePaths.isEmpty() && referenceAssets.isEmpty())
                }
                "reference_match" -> {
                    require(!targetId.isNullOrBlank())
                    require(expected.string("reference_relation") in REFERENCE_RELATIONS)
                    require(referencePaths.size in 3..20)
                    require(referenceAssets.map(AssetIdentity::path) == referencePaths) {
                        "${case.string("id")}: reference identities must exactly bind references"
                    }
                }
                else -> error("unsupported replay kind: $kind")
            }
            ReplayCase(
                id = case.string("id"),
                datasetId = case.string("dataset_id"),
                packageId = case.string("package_id"),
                kind = kind,
                inputMode = inputMode,
                inputCrop = inputCrop,
                asset = asset,
                targetId = targetId,
                expected = expected,
                references = referenceAssets,
            )
        }
        require(cases.isNotEmpty()) { "external replay bundle has no cases" }
        require(cases.map(ReplayCase::id).distinct().size == cases.size) { "duplicate case IDs" }
        require(cases.all { it.packageId in CURRENT_PACKAGE_IDS }) { "unknown package ID" }
        val identitiesByPath = cases
            .flatMap { listOf(it.asset) + it.references }
            .groupBy(AssetIdentity::path)
        identitiesByPath.forEach { (path, identities) ->
            require(identities.distinct().size == 1) {
                "conflicting replay asset identities for $path"
            }
        }
        val sourceIndexSha256 = root.getAsJsonObject("source_index_sha256")
            .entrySet()
            .associate { (datasetId, value) ->
                require(datasetId.isNotBlank())
                datasetId to value.asString.also { require(SHA_256.matches(it)) }
            }
        require(sourceIndexSha256.isNotEmpty()) { "source_index_sha256 must not be empty" }
        require(sourceIndexSha256.keys.containsAll(cases.map(ReplayCase::datasetId))) {
            "source_index_sha256 does not cover every replay dataset"
        }
        return ReplaySuite(
            suiteId = root.string("suite_id"),
            catalogVersion = root.string("catalog_version"),
            generatedAt = root.string("generated_at"),
            manifestSha256 = sha256(bytes),
            sourceIndexSha256 = sourceIndexSha256,
            cases = cases,
        )
    }

    private fun decodeFrame(
        asset: AssetIdentity,
        sequence: Long,
        verifiedAssets: VerifiedReplayAssetStore,
    ): SourceFrame {
        val upright = UprightReferenceBitmapDecoder.decode(verifiedAssets.file(asset))
        return try {
            val rgb = upright.toTightRgb888()
            SourceFrame(
                sourceSequence = sequence,
                monotonicTimeMillis = sequence * 3_000,
                capturedAtEpochMillis = null,
                width = upright.width,
                height = upright.height,
                rotationDegrees = 0,
                cropRect = PixelRect(0, 0, upright.width, upright.height),
                pixels = FramePixels.Rgb888(rgb, upright.width * 3),
            )
        } finally {
            upright.recycle()
        }
    }

    private fun decodeReference(
        asset: AssetIdentity,
        id: String,
        verifiedAssets: VerifiedReplayAssetStore,
    ): ReferenceImageAsset {
        val frame = decodeFrame(asset, 0, verifiedAssets)
        val rgb = (frame.pixels as FramePixels.Rgb888).bytes
        return ReferenceImageAsset(id, sha256(rgb), frame.width, frame.height, rgb)
    }

    private fun buildSummary(
        suite: ReplaySuite,
        catalogSha256: String,
        packageVersions: Map<String, String>,
        startedAt: Long,
        metadataEvaluatedAt: Long,
        results: List<CaseResult>,
    ): JsonObject = JsonObject().apply {
        addProperty("schema_version", SUMMARY_SCHEMA)
        addProperty("suite_id", suite.suiteId)
        addProperty("catalog_version", suite.catalogVersion)
        addProperty("catalog_sha256", catalogSha256)
        addProperty("bundle_manifest_sha256", suite.manifestSha256)
        add("source_index_sha256", GsonBuilder().create().toJsonTree(suite.sourceIndexSha256))
        addProperty("bundle_generated_at", suite.generatedAt)
        addProperty("started_at_epoch_ms", startedAt)
        addProperty("metadata_evaluated_at_epoch_ms", metadataEvaluatedAt)
        addProperty("finished_at_epoch_ms", System.currentTimeMillis())
        addProperty("object_hit_minimum_iou", MINIMUM_OBJECT_IOU)
        add("package_versions", GsonBuilder().create().toJsonTree(packageVersions))
        addProperty("case_count", suite.cases.size)
        addProperty("result_count", results.size)
        addProperty("runner_error_count", results.count { it.outcome == "runner_error" })
        add("input_modes", aggregateInputModes(results))
        add("object", aggregateObject(results))
        add("reader", aggregateOutcomes(results.filter { it.kind == "numeric_reading" }))
        add("reference", aggregateReference(results))
        add("cases", GsonBuilder().create().toJsonTree(results))
    }

    private fun aggregateObject(results: List<CaseResult>): JsonObject = JsonObject().apply {
        results.filter { it.kind == "object_presence" }
            .groupBy(CaseResult::packageId)
            .forEach { (packageId, packageResults) ->
                add(packageId, JsonObject().apply {
                    addProperty("cases", packageResults.size)
                    add("outcomes", aggregateOutcomes(packageResults))
                    add("classes", JsonObject().apply {
                        packageResults.groupBy { checkNotNull(it.targetId) }.forEach { (target, targetResults) ->
                            add(target, aggregateOutcomes(targetResults))
                        }
                    })
                })
            }
    }

    private fun aggregateReference(results: List<CaseResult>): JsonObject = JsonObject().apply {
        val references = results.filter { it.kind == "reference_match" }
        addProperty("cases", references.size)
        add("outcomes", aggregateOutcomes(references))
        REFERENCE_RELATIONS.forEach { relation ->
            add(
                relation,
                aggregateOutcomes(references.filter { it.expected == relation }),
            )
        }
    }

    private fun aggregateOutcomes(results: List<CaseResult>): JsonObject = JsonObject().apply {
        results.groupingBy(CaseResult::outcome).eachCount().toSortedMap().forEach(::addProperty)
    }

    private fun aggregateInputModes(results: List<CaseResult>): JsonObject = JsonObject().apply {
        results.filter { it.inputMode in INPUT_MODES }
            .groupBy(CaseResult::inputMode)
            .toSortedMap()
            .forEach { (inputMode, modeResults) ->
                add(inputMode, JsonObject().apply {
                    addProperty("cases", modeResults.size)
                    add("outcomes", aggregateOutcomes(modeResults))
                })
            }
    }

    private fun writeSummary(summary: JsonObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n"
        val output = checkNotNull(context.getExternalFilesDir(null)).resolve(SUMMARY_FILENAME)
        output.writeText(json)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("externalVisionReplaySummary", output.absolutePath) },
        )
        json.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            Log.i(LOG_TAG, "${index.toString().padStart(3, '0')}:$chunk")
        }
    }

    private inline fun captureCase(case: ReplayCase, block: () -> CaseResult): CaseResult =
        runCatching(block).getOrElse { error ->
            CaseResult(case, "runner_error", error.stackTraceToString(), "error")
        }

    private fun RuntimeFrameResult.processedObservation(): Observation =
        (this as? RuntimeFrameResult.Processed)?.pipelineResult?.observation
            ?: error("frame was unexpectedly skipped")

    private fun ReplayCase.runtimeKey(): String = when (kind) {
        "object_presence" -> checkNotNull(targetId)
        "numeric_reading" -> "reader"
        "reference_match" -> id
        else -> error("unsupported kind")
    }

    private fun JsonObject.toRect(): NormalizedRect = NormalizedRect(
            left = get("xmin").asFloat,
            top = get("ymin").asFloat,
            right = get("xmax").asFloat,
            bottom = get("ymax").asFloat,
        )

    private fun JsonObject.toInputCrop(caseId: String): NormalizedRect {
        require(string("coordinate_space") == INPUT_CROP_COORDINATE_SPACE) {
            "$caseId: input_crop coordinate_space must be $INPUT_CROP_COORDINATE_SPACE"
        }
        return NormalizedRect(
            left = requiredFiniteNumber("left", caseId),
            top = requiredFiniteNumber("top", caseId),
            right = requiredFiniteNumber("right", caseId),
            bottom = requiredFiniteNumber("bottom", caseId),
        )
    }

    private fun iou(a: NormalizedRect, b: NormalizedRect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return intersection / (areaA + areaB - intersection)
    }

    private fun readAsset(path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { it.readBytes() }

    private fun copyAssetAndVerify(
        assetPath: String,
        expectedSize: Long,
        expectedSha256: String,
        output: File,
    ) {
        require(expectedSize > 0)
        require(SHA_256.matches(expectedSha256))
        check(output.parentFile?.mkdirs() != false || output.parentFile?.isDirectory == true)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(assetPath).use { input ->
                output.outputStream().buffered(COPY_BUFFER_SIZE).use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        sink.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        size = Math.addExact(size, count.toLong())
                    }
                }
            }
            require(size == expectedSize) {
                "$assetPath size mismatch: expected $expectedSize, got $size"
            }
            val actualSha256 = digest.digest().toHex()
            require(actualSha256 == expectedSha256) {
                "$assetPath hash mismatch: expected $expectedSha256, got $actualSha256"
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun Bitmap.toTightRgb888(): ByteArray {
        val output = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), RGB_CHANNELS))
        val row = IntArray(width)
        repeat(height) { y ->
            getPixels(row, 0, width, 0, y, width, 1)
            row.forEachIndexed { x, colour ->
                val offset = (y * width + x) * RGB_CHANNELS
                output[offset] = (colour ushr 16 and 0xff).toByte()
                output[offset + 1] = (colour ushr 8 and 0xff).toByte()
                output[offset + 2] = (colour and 0xff).toByte()
            }
        }
        return output
    }

    private inner class VerifiedReplayAssetStore(
        private val root: File,
        suite: ReplaySuite,
    ) {
        private val identitiesByPath = suite.cases
            .flatMap { listOf(it.asset) + it.references }
            .associateBy(AssetIdentity::path)
        private val filesByPath = mutableMapOf<String, File>()

        init {
            check(root.mkdirs() || root.isDirectory) { "failed to create replay asset directory: $root" }
        }

        fun materializeAndVerifyAll() {
            identitiesByPath.values.sortedBy(AssetIdentity::path).forEach(::materialize)
        }

        fun file(identity: AssetIdentity): File {
            require(identitiesByPath[identity.path] == identity) {
                "unbound replay asset identity: ${identity.path}"
            }
            return filesByPath[identity.path] ?: error("replay asset was not verified: ${identity.path}")
        }

        private fun materialize(identity: AssetIdentity) {
            val output = root.resolve("${sha256(identity.path.encodeToByteArray())}.asset")
            copyAssetAndVerify(
                assetPath = "$BUNDLE_ROOT/${identity.path}",
                expectedSize = identity.sizeBytes,
                expectedSha256 = identity.sha256,
                output = output,
            )
            filesByPath[identity.path] = output
        }
    }

    private fun safeAsset(value: String): String {
        require(value.isNotBlank() && !value.startsWith('/') && '\\' !in value)
        require(value.split('/').none { it.isBlank() || it == "." || it == ".." })
        return value
    }

    private fun argumentTrue(arguments: Bundle, name: String, default: Boolean): Boolean =
        arguments.getString(name)?.toBooleanStrictOrNull() ?: default

    /** Keeps the replay harness honest: signed metadata must not pass when evaluated after expiry. */
    private fun assertExpiredCatalogFailsClosed(
        catalogBytes: ByteArray,
        expiredAtEpochMillis: Long,
    ) {
        val failure = runCatching {
            SignedMetadataCodec.decodeAndVerifyCatalog(
                catalogBytes,
                nowEpochMillis = expiredAtEpochMillis,
            )
        }.exceptionOrNull()
        require(failure is MetadataVerificationException && failure.code == "catalog_expired") {
            "expired signed Catalog unexpectedly passed verification: $failure"
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun JsonObject.string(name: String): String = get(name).asString
    private fun JsonObject.nullableString(name: String): String? = get(name)?.let {
        if (it.isJsonNull) null else it.asString
    }
    private fun JsonObject.requiredSha256(name: String): String = string(name).also {
        require(SHA_256.matches(it)) { "$name must be lowercase SHA-256" }
    }
    private fun JsonObject.requiredPositiveLong(name: String): Long = get(name).asLong.also {
        require(it > 0) { "$name must be positive" }
    }
    private fun JsonObject.requiredFiniteNumber(name: String, caseId: String): Float {
        val primitive = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(primitive?.isNumber == true) { "$caseId: input_crop $name must be a number" }
        return primitive.asFloat.also {
            require(it.isFinite()) { "$caseId: input_crop $name must be finite" }
        }
    }

    private data class ReplaySuite(
        val suiteId: String,
        val catalogVersion: String,
        val generatedAt: String,
        val manifestSha256: String,
        val sourceIndexSha256: Map<String, String>,
        val cases: List<ReplayCase>,
    )

    private data class ReplayCase(
        val id: String,
        val datasetId: String,
        val packageId: String,
        val kind: String,
        val inputMode: String,
        val inputCrop: NormalizedRect?,
        val asset: AssetIdentity,
        val targetId: String?,
        val expected: JsonObject,
        val references: List<AssetIdentity>,
    )

    private data class AssetIdentity(
        val path: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    private data class PackageFixture(
        val manifest: ModelPackageManifest,
        val artifactsByRole: Map<String, File>,
    )

    private data class CaseResult(
        val id: String,
        val datasetId: String,
        val packageId: String,
        val kind: String,
        @SerializedName("input_mode")
        val inputMode: String,
        val targetId: String?,
        val outcome: String,
        val expected: String?,
        val actual: String,
        val observation: String,
    ) {
        constructor(
            case: ReplayCase,
            outcome: String,
            actual: String,
            observation: String,
        ) : this(
            id = case.id,
            datasetId = case.datasetId,
            packageId = case.packageId,
            kind = case.kind,
            inputMode = case.inputMode,
            targetId = case.targetId,
            outcome = outcome,
            expected = when (case.kind) {
                "object_presence" -> case.expected.get("present").asBoolean.toString()
                "numeric_reading" -> listOf("expected_exact", "expected_digits", "value_decimal")
                    .firstNotNullOf { name ->
                        case.expected.get(name)?.takeUnless { it.isJsonNull }?.asString
                    }
                "reference_match" -> case.expected.get("reference_relation").asString
                else -> null
            },
            actual = actual,
            observation = observation,
        )
    }

    private companion object {
        const val BUNDLE_SCHEMA = "beyoureye.external-replay-bundle.v1"
        const val SUMMARY_SCHEMA = "beyoureye.external-replay-summary.v1"
        const val BUNDLE_ROOT = "external-vision-replay/bundle"
        const val BUNDLE_MANIFEST = "$BUNDLE_ROOT/manifest.json"
        const val PACKAGES_ROOT = "external-vision-replay/packages"
        const val SIGNED_PACKAGES_ROOT = "$PACKAGES_ROOT/packages"
        const val PACKAGE_CATALOG = "$PACKAGES_ROOT/catalog.json"
        const val SUMMARY_FILENAME = "external-vision-replay-summary.json"
        const val LOG_TAG = "ExternalVisionReplay"
        const val LOG_CHUNK_SIZE = 3_000
        const val COPY_BUFFER_SIZE = 64 * 1_024
        const val RGB_CHANNELS = 3
        const val MINIMUM_OBJECT_IOU = 0.5f
        const val MAX_TARGET_DIAGNOSTIC_DETECTIONS = 5
        const val FULL_FRAME_INPUT_MODE = "full_frame"
        const val MANUAL_ROI_INPUT_MODE = "manual_roi_diagnostic"
        const val RUNNER_INPUT_MODE = "__runner__"
        const val INPUT_CROP_COORDINATE_SPACE = "normalized_source_image_v1"
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val INPUT_MODES = setOf(FULL_FRAME_INPUT_MODE, MANUAL_ROI_INPUT_MODE)
        val CURRENT_PACKAGE_IDS = setOf(
            "similarity_mediapipe_mobilenet_v3_large_v1",
            "numeric_reader_ppocrv6_medium_v1",
            "efficientdet_lite2_object_v1",
        )
        val REFERENCE_RELATIONS = setOf("same_object", "different_object")
    }
}
