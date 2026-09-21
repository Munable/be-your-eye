package app.beyoureyes.core.data

import app.beyoureyes.core.vision.AdapterContract
import app.beyoureyes.core.vision.ArtifactComponent
import app.beyoureyes.core.vision.ArtifactMediaType
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.ClassMapSpec
import app.beyoureyes.core.vision.ClassMapTarget
import app.beyoureyes.core.vision.ColorSpace
import app.beyoureyes.core.vision.CtcCollapseSemantics
import app.beyoureyes.core.vision.CtcDecodingSpec
import app.beyoureyes.core.vision.CtcIndexSemantics
import app.beyoureyes.core.vision.CtcScoreSemantics
import app.beyoureyes.core.vision.DeviceCompatibility
import app.beyoureyes.core.vision.EmbeddedPostprocessSpec
import app.beyoureyes.core.vision.InputComponent
import app.beyoureyes.core.vision.InputQuantization
import app.beyoureyes.core.vision.InputRole
import app.beyoureyes.core.vision.LicenseReviewStatus
import app.beyoureyes.core.vision.ManifestSignature
import app.beyoureyes.core.vision.ModelPackageLicense
import app.beyoureyes.core.vision.ModelPackageManifest
import app.beyoureyes.core.vision.OutputComponent
import app.beyoureyes.core.vision.OutputRole
import app.beyoureyes.core.vision.ParameterBound
import app.beyoureyes.core.vision.ParameterProfile
import app.beyoureyes.core.vision.QuantizationMode
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.RuntimeKind
import app.beyoureyes.core.vision.SamplingPolicySpec
import app.beyoureyes.core.vision.SourceReference
import app.beyoureyes.core.vision.SupportedTask
import app.beyoureyes.core.vision.TargetMode
import app.beyoureyes.core.vision.TensorBinding
import app.beyoureyes.core.vision.TensorDataType
import app.beyoureyes.core.vision.TensorLayout
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Collections

private val INTENT_PATTERN = Regex(
    "^[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)*(?:\\.\\*)?$",
)
private val INTENT_KEY = Regex("^[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)*$")

data class CatalogCapability(
    val capabilityId: String,
    val displayName: String,
    val targetModes: Set<TargetMode>,
    val allowedRuleTypes: Set<String>,
    /** Signed release fallback priority; JSON array order is product semantics. */
    val recipeIds: List<String>,
)

data class CatalogHumanReview(
    val reviewedAt: String,
    val licenseConclusion: String,
    val evidenceRefs: Set<String>,
)

data class CatalogModelCard(
    val providerId: String,
    val providerName: String,
    val modelName: String,
    val modelHomeUrl: String,
    val modelKind: String,
)

data class CatalogOperationalCapability(
    val capabilityKey: String,
    val capabilityId: String,
    val recipeId: String,
    val modelCard: CatalogModelCard,
    val intentPatterns: Set<String>,
    val targetIds: Set<String>,
    val professionalDomain: String,
    val displayName: String,
    val status: String,
    val applicableScenarios: Set<String>,
    val inapplicableScenarios: Set<String>,
    val inputRequirements: Set<String>,
    val deviceProfileIds: Set<String>,
    val packageIds: Set<String>,
    val humanReview: CatalogHumanReview,
) {
    fun matchesIntentKey(intentKey: String): Boolean {
        if (!INTENT_KEY.matches(intentKey)) return false
        return intentPatterns.any { pattern ->
            if (pattern.endsWith(".*")) {
                intentKey.startsWith(pattern.removeSuffix("*"))
            } else {
                intentKey == pattern
            }
        }
    }
}

data class CatalogRuntimeRecipe(
    val recipeId: String,
    val capabilityId: String,
    val runtimeFamily: RecipeFamily,
    val promptModes: Set<TargetMode>,
    /** Signed release fallback priority within this recipe. */
    val candidatePackageIds: List<String>,
)

data class CatalogPackageEntry(
    val packageId: String,
    val packageVersion: String,
    val manifestUrl: String,
    val manifestSha256: String,
    val active: Boolean,
)

data class CapabilityCatalogMetadata(
    val catalogId: String,
    val catalogVersion: String,
    val buildChannel: BuildChannel,
    val issuedAtEpochMillis: Long,
    val capabilities: List<CatalogCapability>,
    val operationalCapabilities: List<CatalogOperationalCapability>,
    val runtimeRecipes: List<CatalogRuntimeRecipe>,
    val packages: List<CatalogPackageEntry>,
)

class VerifiedCapabilityCatalog internal constructor(
    val catalog: CapabilityCatalogMetadata,
    val documentSha256: String,
    val signedPayloadSha256: String,
    val validUntilEpochMillis: Long,
    val installedCommunityOnly: Boolean = false,
    documentBytes: ByteArray,
) {
    private val document = documentBytes.copyOf()

    fun copyDocumentBytes(): ByteArray = document.copyOf()

    fun activePackage(packageId: String): CatalogPackageEntry? = catalog.packages.singleOrNull {
        it.packageId == packageId && it.active
    }
}

class VerifiedManifestDocument internal constructor(
    val manifest: ModelPackageManifest,
    val catalogEntry: CatalogPackageEntry,
    val documentSha256: String,
    val signedPayloadSha256: String,
    /** Installation-time Catalog freshness. It is not an installed-package runtime expiry. */
    val catalogFreshUntilEpochMillis: Long,
    /** Runtime upper bound derived only from the signed license grant. */
    val licenseRunValidUntilEpochMillis: Long,
    documentBytes: ByteArray,
) {
    private val document = documentBytes.copyOf()

    fun copyDocumentBytes(): ByteArray = document.copyOf()

    fun toStoreDescriptor(): ModelPackageDescriptor = ModelPackageDescriptor(
        identity = ModelPackageIdentity(manifest.packageId, manifest.packageVersion),
        canonicalManifestSha256 = documentSha256,
        canonicalManifestSizeBytes = document.size.toLong(),
        artifacts = manifest.artifacts.map { artifact ->
            ModelPackageArtifactDescriptor(
                role = artifact.role,
                relativePath = "artifacts/${artifact.role}.${artifact.fileExtension()}",
                sha256 = artifact.sha256,
                sizeBytes = artifact.sizeBytes,
            )
        },
    )
}

/**
 * Strict Android decoder for the signed v4 Catalog and Manifest contracts.
 *
 * Unknown fields, duplicate keys, non-I-JSON text, unknown enum values and invalid cross-references
 * all fail closed before a package can be downloaded or staged.
 */
object SignedMetadataCodec {
    fun decodeAndVerifyCatalog(
        documentBytes: ByteArray,
        keyRegistry: PinnedEd25519KeyRegistry = EmbeddedModelDeliveryPublicKeys.catalog,
        nowEpochMillis: Long,
        maximumAgeMillis: Long = DEFAULT_CATALOG_MAX_AGE_MILLIS,
        allowInstalledCommunity: Boolean = false,
    ): VerifiedCapabilityCatalog {
        require(maximumAgeMillis > 0) { "maximumAgeMillis must be positive" }
        val parsed = StrictSignedJson.parseAndVerify(
            bytes = documentBytes,
            maximumBytes = MAX_SIGNED_CATALOG_BYTES,
            registry = keyRegistry,
        )
        val catalog = parseCatalog(parsed.root)
        if (catalog.issuedAtEpochMillis > nowEpochMillis + MAX_METADATA_CLOCK_SKEW_MILLIS) {
            reject("catalog_from_future", "$.issued_at", "issued_at exceeds allowed clock skew")
        }
        val validUntil = try {
            Math.addExact(catalog.issuedAtEpochMillis, maximumAgeMillis)
        } catch (_: ArithmeticException) {
            reject("catalog_time_overflow", "$.issued_at", "catalog expiry overflows")
        }
        val installedOnly = nowEpochMillis >= validUntil && allowInstalledCommunity &&
            catalog.buildChannel == BuildChannel.COMMUNITY
        if (nowEpochMillis >= validUntil && !installedOnly) reject("catalog_expired", "$.issued_at", "catalog maximum age elapsed")
        return VerifiedCapabilityCatalog(
            catalog = catalog,
            documentSha256 = parsed.rawSha256,
            signedPayloadSha256 = sha256Hex(parsed.canonicalSignedPayload),
            validUntilEpochMillis = validUntil,
            installedCommunityOnly = installedOnly,
            documentBytes = parsed.rawBytes,
        )
    }

    fun decodeAndVerifyManifest(
        documentBytes: ByteArray,
        catalog: VerifiedCapabilityCatalog,
        packageId: String,
        keyRegistry: PinnedEd25519KeyRegistry = EmbeddedModelDeliveryPublicKeys.manifest,
        nowEpochMillis: Long,
    ): VerifiedManifestDocument {
        if (nowEpochMillis >= catalog.validUntilEpochMillis && !catalog.installedCommunityOnly) {
            reject("catalog_expired", "$", "the verified Catalog is no longer valid")
        }
        if (catalog.catalog.buildChannel == BuildChannel.DEVELOPMENT_NO_MODEL) {
            reject("model_delivery_disabled", "$.build_channel", "development-no-model cannot activate packages")
        }
        val entry = catalog.activePackage(packageId)
            ?: reject("package_not_active", "$.packages", "package is missing or withdrawn")
        val parsed = StrictSignedJson.parseAndVerify(
            bytes = documentBytes,
            maximumBytes = MAX_SIGNED_MANIFEST_BYTES,
            registry = keyRegistry,
        )
        if (parsed.rawSha256 != entry.manifestSha256) {
            reject("manifest_hash_mismatch", "$", "Manifest bytes do not match the signed Catalog entry")
        }
        val manifest = parseManifest(parsed.root)
        if (manifest.packageId != entry.packageId || manifest.packageVersion != entry.packageVersion) {
            reject("manifest_identity_mismatch", "$.package_id", "Manifest identity differs from Catalog entry")
        }
        val licenseValidUntil = validateLicenseGate(manifest, catalog.catalog.buildChannel, nowEpochMillis)
        validateOperationalAdmission(manifest, catalog.catalog)
        return VerifiedManifestDocument(
            manifest = manifest,
            catalogEntry = entry,
            documentSha256 = parsed.rawSha256,
            signedPayloadSha256 = sha256Hex(parsed.canonicalSignedPayload),
            catalogFreshUntilEpochMillis = catalog.validUntilEpochMillis,
            licenseRunValidUntilEpochMillis = licenseValidUntil,
            documentBytes = parsed.rawBytes,
        )
    }

    /**
     * Restores a Manifest already protected by [ModelArtifactStore]. It intentionally does not
     * repeat Ed25519 verification: the immutable bytes/hash and unexpired gate report are checked
     * every time a runtime lease is acquired.
     */
    fun decodeStoredManifestDocument(
        documentBytes: ByteArray,
        expectedIdentity: ModelPackageIdentity? = null,
        expectedSha256: String? = null,
    ): ModelPackageManifest {
        if (expectedSha256 != null && sha256Hex(documentBytes) != expectedSha256) {
            reject("stored_manifest_hash_mismatch", "$", "stored Manifest hash differs from lease descriptor")
        }
        val root = StrictSignedJson.parseUnverified(documentBytes, MAX_SIGNED_MANIFEST_BYTES)
        val manifest = parseManifest(root)
        if (expectedIdentity != null &&
            (manifest.packageId != expectedIdentity.packageId ||
                manifest.packageVersion != expectedIdentity.packageVersion)
        ) {
            reject("stored_manifest_identity_mismatch", "$.package_id", "stored Manifest identity differs from lease")
        }
        return manifest
    }

    private fun parseCatalog(root: JsonObject): CapabilityCatalogMetadata {
        root.requireExactKeys(
            setOf(
                "schema_version", "catalog_id", "catalog_version", "build_channel", "issued_at",
                "capabilities", "operational_capabilities", "runtime_recipes", "packages", "signature",
            ),
            "$",
        )
        if (root.requireString("schema_version", "$") != "4.0") {
            reject("unsupported_schema", "$.schema_version", "schema_version 4.0 required")
        }
        val catalogId = requireIdentifier(root.requireString("catalog_id", "$"), "$.catalog_id")
        val catalogVersion = requireIdentifier(root.requireString("catalog_version", "$"), "$.catalog_version")
        val buildChannel = BuildChannel.fromWireValue(root.requireString("build_channel", "$"))
            ?: reject("unknown_enum", "$.build_channel", "unknown build channel")
        val issuedAt = requireIsoInstant(root.requireString("issued_at", "$"), "$.issued_at")
        val capabilities = root.requireArray("capabilities", "$").requireNonEmpty("$.capabilities")
            .mapIndexed { index, element -> parseCapability(element.asStrictObject("$.capabilities[$index]"), index) }
        val operationalCapabilities = root.requireArray("operational_capabilities", "$")
            .requireNonEmpty("$.operational_capabilities")
            .mapIndexed { index, element ->
                parseOperationalCapability(
                    element.asStrictObject("$.operational_capabilities[$index]"),
                    index,
                )
            }
        val recipes = root.requireArray("runtime_recipes", "$").requireNonEmpty("$.runtime_recipes")
            .mapIndexed { index, element -> parseRuntimeRecipe(element.asStrictObject("$.runtime_recipes[$index]"), index) }
        val packages = root.requireArray("packages", "$").requireNonEmpty("$.packages")
            .mapIndexed { index, element -> parseCatalogPackage(element.asStrictObject("$.packages[$index]"), index) }
        requireUnique(capabilities.map(CatalogCapability::capabilityId), "$.capabilities", "capability_id")
        requireUnique(
            operationalCapabilities.map(CatalogOperationalCapability::capabilityKey),
            "$.operational_capabilities",
            "capability_key",
        )
        requireUnique(recipes.map(CatalogRuntimeRecipe::recipeId), "$.runtime_recipes", "recipe_id")
        requireUnique(packages.map(CatalogPackageEntry::packageId), "$.packages", "package_id")
        val capabilityById = capabilities.associateBy(CatalogCapability::capabilityId)
        val recipeById = recipes.associateBy(CatalogRuntimeRecipe::recipeId)
        val packageById = packages.associateBy(CatalogPackageEntry::packageId)
        capabilities.forEach { capability ->
            if (capability.capabilityId == "structured_reading") {
                if (capability.targetModes != setOf(TargetMode.NONE)) {
                    reject(
                        "capability_target_mode_mismatch",
                        "$.capabilities",
                        "structured_reading requires exactly target mode none",
                    )
                }
            } else if (TargetMode.NONE in capability.targetModes) {
                reject(
                    "capability_target_mode_mismatch",
                    "$.capabilities",
                    "visual capabilities cannot use target mode none",
                )
            }
            capability.recipeIds.forEach { recipeId ->
                val recipe = recipeById[recipeId]
                    ?: reject("unknown_recipe_reference", "$.capabilities", "unknown recipe $recipeId")
                if (recipe.capabilityId != capability.capabilityId) {
                    reject("recipe_capability_mismatch", "$.capabilities", "recipe belongs to another capability")
                }
            }
        }
        recipes.forEach { recipe ->
            val capability = capabilityById[recipe.capabilityId]
                ?: reject("unknown_capability_reference", "$.runtime_recipes", "unknown capability")
            when (recipe.runtimeFamily) {
                RecipeFamily.READING_PIPELINE_V1 -> {
                    if (recipe.capabilityId != "structured_reading" ||
                        recipe.promptModes != setOf(TargetMode.NONE)
                    ) {
                        reject(
                            "runtime_family_prompt_mode_mismatch",
                            "$.runtime_recipes",
                            "reading_pipeline_v1 requires structured_reading and prompt mode none",
                        )
                    }
                }
                RecipeFamily.OBJECT_DETECTION_V1 -> {
                    if (recipe.promptModes != setOf(TargetMode.OBJECT_CLASS)) {
                        reject(
                            "runtime_family_prompt_mode_mismatch",
                            "$.runtime_recipes",
                            "object_detection_v1 requires exactly target mode object_class",
                        )
                    }
                }
                RecipeFamily.SIMILARITY_MATCH_V1 -> if (TargetMode.NONE in recipe.promptModes) {
                    reject(
                        "runtime_family_prompt_mode_mismatch",
                        "$.runtime_recipes",
                        "similarity_match_v1 cannot use target mode none",
                    )
                }
            }
            if (!capability.targetModes.containsAll(recipe.promptModes)) {
                reject("recipe_prompt_mode_mismatch", "$.runtime_recipes", "recipe prompt modes exceed capability")
            }
            recipe.candidatePackageIds.forEach { packageId ->
                if (packageById[packageId] == null) {
                    reject("unknown_package_reference", "$.runtime_recipes", "unknown package $packageId")
                }
            }
            if (recipe.candidatePackageIds.none { packageById[it]?.active == true }) {
                reject("recipe_without_active_package", "$.runtime_recipes", "recipe has no active package")
            }
        }
        val expectedOperationalStatus = when (buildChannel) {
            BuildChannel.COMMUNITY -> "community"
            BuildChannel.COMMERCIAL -> "commercial"
            BuildChannel.INTERNAL_EVALUATION -> "internal-evaluation"
            BuildChannel.DEVELOPMENT_NO_MODEL -> null
        }
        val liveOperationalPackageIds = linkedSetOf<String>()
        operationalCapabilities.forEach { operational ->
            val path = "$.operational_capabilities"
            if (capabilityById[operational.capabilityId] == null) {
                reject("unknown_capability_reference", path, "unknown operational capability")
            }
            if (expectedOperationalStatus != null && operational.status != expectedOperationalStatus) {
                reject("operational_channel_mismatch", path, "operational capability differs from Catalog channel")
            }
            if (operational.packageIds.isEmpty()) {
                reject("operational_capability_without_package", path, "supported capability requires packages")
            }
            val operationalRecipe = recipeById[operational.recipeId]
                ?: reject("operational_recipe_mismatch", path, "supported model profile requires one recipe")
            if (operationalRecipe.capabilityId != operational.capabilityId ||
                operational.recipeId !in capabilityById.getValue(operational.capabilityId).recipeIds
            ) {
                reject("operational_recipe_mismatch", path, "model profile recipe belongs to another capability")
            }
            if (operationalRecipe.runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1 &&
                operational.targetIds.isEmpty()
            ) {
                reject(
                    "operational_target_coverage_missing",
                    path,
                    "object-detection model profile requires finite target IDs",
                )
            } else if (operationalRecipe.runtimeFamily != RecipeFamily.OBJECT_DETECTION_V1 &&
                operational.targetIds.isNotEmpty()
            ) {
                reject(
                    "operational_target_coverage_forbidden",
                    path,
                    "only object-detection model profiles may declare target IDs",
                )
            }
            val expectedConclusion = if (operational.status == "community") {
                "approved-for-community"
            } else if (operational.status == "commercial") {
                "approved-for-commercial"
            } else {
                "approved-for-internal-evaluation"
            }
            if (operational.humanReview.licenseConclusion != expectedConclusion) {
                reject("operational_review_mismatch", path, "operational license review differs from status")
            }
            operational.packageIds.forEach { packageId ->
                val entry = packageById[packageId]
                    ?: reject("unknown_package_reference", path, "unknown package $packageId")
                if (!entry.active) reject("operational_package_not_active", path, "$packageId is not active")
                if (packageId !in operationalRecipe.candidatePackageIds) {
                    reject("operational_package_recipe_mismatch", path, "$packageId has no matching recipe")
                }
                liveOperationalPackageIds += packageId
            }
        }
        if (buildChannel != BuildChannel.DEVELOPMENT_NO_MODEL) {
            packages.filter(CatalogPackageEntry::active).forEach { entry ->
                if (entry.packageId !in liveOperationalPackageIds) {
                    reject(
                        "active_package_not_in_operational_inventory",
                        "$.packages",
                        "${entry.packageId} has no supported operational capability",
                    )
                }
            }
        }
        return CapabilityCatalogMetadata(
            catalogId,
            catalogVersion,
            buildChannel,
            issuedAt,
            Collections.unmodifiableList(capabilities),
            Collections.unmodifiableList(operationalCapabilities),
            Collections.unmodifiableList(recipes),
            Collections.unmodifiableList(packages),
        )
    }

    private fun parseOperationalCapability(
        value: JsonObject,
        index: Int,
    ): CatalogOperationalCapability {
        val path = "$.operational_capabilities[$index]"
        value.requireExactKeys(
            setOf(
                "capability_key", "capability_id", "recipe_id", "model_card", "intent_patterns",
                "professional_domain", "display_name", "status",
                "applicable_scenarios", "inapplicable_scenarios", "input_requirements",
                "device_profile_ids", "package_ids", "human_review",
            ) + setOf("target_ids").filter(value::has),
            path,
        )
        val capabilityId = value.requireString("capability_id", path)
        if (capabilityId !in CAPABILITY_IDS) reject("unknown_enum", "$path.capability_id", "unknown capability")
        val status = value.requireString("status", path)
        if (status !in OPERATIONAL_STATUSES) reject("unknown_enum", "$path.status", "unknown operational status")
        val review = value.requireObject("human_review", path)
        review.requireExactKeys(setOf("reviewed_at", "license_conclusion", "evidence_refs"), "$path.human_review")
        val reviewedAt = review.requireString("reviewed_at", "$path.human_review")
        requireIsoInstant(reviewedAt, "$path.human_review.reviewed_at")
        val licenseConclusion = review.requireString("license_conclusion", "$path.human_review")
        if (licenseConclusion !in OPERATIONAL_LICENSE_CONCLUSIONS) {
            reject("unknown_enum", "$path.human_review.license_conclusion", "unknown license conclusion")
        }
        val recipeId = requireIdentifier(value.requireString("recipe_id", path), "$path.recipe_id")
        val card = value.requireObject("model_card", path)
        card.requireExactKeys(
            setOf("provider_id", "provider_name", "model_name", "model_home_url", "model_kind"),
            "$path.model_card",
        )
        val kind = card.requireString("model_kind", "$path.model_card")
        if (kind !in MODEL_KINDS) {
            reject("unknown_enum", "$path.model_card.model_kind", "unknown model kind")
        }
        val modelCard = CatalogModelCard(
            providerId = requireIdentifier(
                card.requireString("provider_id", "$path.model_card"),
                "$path.model_card.provider_id",
            ),
            providerName = card.requireString("provider_name", "$path.model_card", 100)
                .also { if (it.isEmpty()) reject("empty_string", "$path.model_card.provider_name", "non-empty string required") },
            modelName = card.requireString("model_name", "$path.model_card", 120)
                .also { if (it.isEmpty()) reject("empty_string", "$path.model_card.model_name", "non-empty string required") },
            modelHomeUrl = requireFixedHttpsUrl(
                card.requireString("model_home_url", "$path.model_card"),
                "$path.model_card.model_home_url",
            ),
            modelKind = kind,
        )
        val intentPatterns = value.requireArray("intent_patterns", path)
            .requireNonEmpty("$path.intent_patterns")
            .stringSet("$path.intent_patterns")
            .onEach { pattern ->
                if (!INTENT_PATTERN.matches(pattern)) {
                    reject("intent_pattern_invalid", "$path.intent_patterns", "canonical exact or terminal wildcard required")
                }
            }
        val targetIds = if (value.has("target_ids")) {
            value.requireArray("target_ids", path).stringSet("$path.target_ids")
                .onEach { requireIdentifier(it, "$path.target_ids") }
        } else {
            emptySet()
        }
        return CatalogOperationalCapability(
            capabilityKey = requireIdentifier(value.requireString("capability_key", path), "$path.capability_key"),
            capabilityId = capabilityId,
            recipeId = recipeId,
            modelCard = modelCard,
            intentPatterns = intentPatterns,
            targetIds = targetIds,
            professionalDomain = value.requireString("professional_domain", path).also {
                if (it.isEmpty()) reject("empty_string", "$path.professional_domain", "non-empty string required")
            },
            displayName = value.requireString("display_name", path).also {
                if (it.isEmpty()) reject("empty_string", "$path.display_name", "non-empty string required")
            },
            status = status,
            applicableScenarios = value.requireArray("applicable_scenarios", path)
                .requireNonEmpty("$path.applicable_scenarios").stringSet("$path.applicable_scenarios"),
            inapplicableScenarios = value.requireArray("inapplicable_scenarios", path)
                .requireNonEmpty("$path.inapplicable_scenarios").stringSet("$path.inapplicable_scenarios"),
            inputRequirements = value.requireArray("input_requirements", path)
                .requireNonEmpty("$path.input_requirements").stringSet("$path.input_requirements"),
            deviceProfileIds = value.requireArray("device_profile_ids", path)
                .requireNonEmpty("$path.device_profile_ids").stringSet("$path.device_profile_ids")
                .onEach { requireIdentifier(it, "$path.device_profile_ids") },
            packageIds = value.requireArray("package_ids", path).stringSet("$path.package_ids")
                .onEach { requireIdentifier(it, "$path.package_ids") },
            humanReview = CatalogHumanReview(
                reviewedAt = reviewedAt,
                licenseConclusion = licenseConclusion,
                evidenceRefs = review.requireArray("evidence_refs", "$path.human_review")
                    .requireNonEmpty("$path.human_review.evidence_refs")
                    .stringSet("$path.human_review.evidence_refs"),
            ),
        )
    }

    private fun parseCapability(value: JsonObject, index: Int): CatalogCapability {
        val path = "$.capabilities[$index]"
        value.requireExactKeys(
            setOf(
                "capability_id", "display_name", "target_modes", "allowed_rule_types",
                "recipe_ids",
            ),
            path,
        )
        val capabilityId = value.requireString("capability_id", path)
        if (capabilityId !in CAPABILITY_IDS) reject("unknown_enum", "$path.capability_id", "unknown capability")
        val targetModes = parseTargetModes(value.requireArray("target_modes", path), "$path.target_modes")
        val rules = value.requireArray("allowed_rule_types", path).requireNonEmpty("$path.allowed_rule_types")
            .stringSet("$path.allowed_rule_types", RULE_TYPES)
        val recipeIds = value.requireArray("recipe_ids", path).requireNonEmpty("$path.recipe_ids")
            .stringSet("$path.recipe_ids").toList()
            .onEach { requireIdentifier(it, "$path.recipe_ids") }
        return CatalogCapability(
            capabilityId,
            value.requireString("display_name", path),
            targetModes,
            rules,
            recipeIds,
        )
    }

    private fun parseRuntimeRecipe(value: JsonObject, index: Int): CatalogRuntimeRecipe {
        val path = "$.runtime_recipes[$index]"
        value.requireExactKeys(
            setOf("recipe_id", "capability_id", "runtime_family", "prompt_modes", "candidate_package_ids"),
            path,
        )
        val capabilityId = value.requireString("capability_id", path)
        if (capabilityId !in CAPABILITY_IDS) reject("unknown_enum", "$path.capability_id", "unknown capability")
        val family = RecipeFamily.fromWireValue(value.requireString("runtime_family", path))
            ?: reject("unknown_enum", "$path.runtime_family", "unsupported runtime family")
        val candidates = value.requireArray("candidate_package_ids", path)
            .requireNonEmpty("$path.candidate_package_ids")
            .stringSet("$path.candidate_package_ids")
            .toList()
            .onEach { requireIdentifier(it, "$path.candidate_package_ids") }
        return CatalogRuntimeRecipe(
            requireIdentifier(value.requireString("recipe_id", path), "$path.recipe_id"),
            capabilityId,
            family,
            parseTargetModes(value.requireArray("prompt_modes", path), "$path.prompt_modes"),
            candidates,
        )
    }

    private fun parseCatalogPackage(value: JsonObject, index: Int): CatalogPackageEntry {
        val path = "$.packages[$index]"
        value.requireExactKeys(
            setOf(
                "package_id", "package_version", "manifest_url", "manifest_sha256", "status",
            ),
            path,
        )
        val status = value.requireString("status", path)
        if (status !in setOf("active", "withdrawn")) reject("unknown_enum", "$path.status", "unknown status")
        return CatalogPackageEntry(
            packageId = requireIdentifier(value.requireString("package_id", path), "$path.package_id"),
            packageVersion = requireSemver(value.requireString("package_version", path), "$path.package_version"),
            manifestUrl = requireFixedHttpsUrl(value.requireString("manifest_url", path), "$path.manifest_url"),
            manifestSha256 = requireSha256(value.requireString("manifest_sha256", path), "$path.manifest_sha256"),
            active = status == "active",
        )
    }

    private fun parseManifest(root: JsonObject): ModelPackageManifest {
        val manifestKeys = MANIFEST_KEYS.toMutableSet().apply {
            if (root.has("ctc_decoding")) add("ctc_decoding")
            if (root.has("self_test")) add("self_test")
        }
        root.requireExactKeys(manifestKeys, "$")
        if (root.requireString("schema_version", "$") != "4.0") {
            reject("unsupported_schema", "$.schema_version", "schema_version 4.0 required")
        }
        val signature = parseManifestSignature(root.requireObject("signature", "$"))
        val manifest = ModelPackageManifest(
            schemaVersion = "4.0",
            packageId = requireIdentifier(root.requireString("package_id", "$"), "$.package_id"),
            packageVersion = requireSemver(root.requireString("package_version", "$"), "$.package_version"),
            runtimeFamily = RecipeFamily.fromWireValue(root.requireString("runtime_family", "$"))
                ?: reject("unknown_enum", "$.runtime_family", "unsupported runtime family"),
            supportedTasks = parseSupportedTasks(root.requireArray("supported_tasks", "$")),
            promptModes = parseTargetModes(root.requireArray("prompt_modes", "$"), "$.prompt_modes"),
            modelSource = parseSource(root.requireObject("model_source", "$"), "$.model_source"),
            weightsSource = parseSource(root.requireObject("weights_source", "$"), "$.weights_source"),
            codeSource = parseSource(root.requireObject("code_source", "$"), "$.code_source"),
            exportToolSource = parseSource(root.requireObject("export_tool_source", "$"), "$.export_tool_source"),
            license = parseLicense(root.requireObject("license", "$")),
            artifacts = parseArtifacts(root.requireArray("artifacts", "$")),
            inputs = parseInputs(root.requireArray("inputs", "$")),
            outputs = parseOutputs(root.requireArray("outputs", "$")),
            bindings = parseBindings(root.requireArray("bindings", "$")),
            adapterContract = parseAdapter(root.requireObject("adapter_contract", "$")),
            preprocessId = requireIdentifier(root.requireString("preprocess_id", "$"), "$.preprocess_id"),
            postprocessId = requireIdentifier(root.requireString("postprocess_id", "$"), "$.postprocess_id"),
            ctcDecoding = root.get("ctc_decoding")?.let { element ->
                val value = element.asStrictObject("$.ctc_decoding")
                value.requireExactKeys(
                    setOf(
                        "vocabulary_artifact_role", "blank_index", "index_semantics",
                        "collapse_semantics", "score_semantics",
                    ),
                    "$.ctc_decoding",
                )
                CtcDecodingSpec(
                    vocabularyArtifactRole = requireIdentifier(
                        value.requireString("vocabulary_artifact_role", "$.ctc_decoding"),
                        "$.ctc_decoding.vocabulary_artifact_role",
                    ),
                    blankIndex = value.requireLong("blank_index", "$.ctc_decoding")
                        .toIntExact("$.ctc_decoding.blank_index"),
                    indexSemantics = when (
                        value.requireString("index_semantics", "$.ctc_decoding")
                    ) {
                        "zero_based_token_order_v1" -> CtcIndexSemantics.ZERO_BASED_TOKEN_ORDER_V1
                        else -> reject(
                            "unknown_enum",
                            "$.ctc_decoding.index_semantics",
                            "unknown CTC index semantics",
                        )
                    },
                    collapseSemantics = when (
                        value.requireString("collapse_semantics", "$.ctc_decoding")
                    ) {
                        "ctc_greedy_argmax_v1" -> CtcCollapseSemantics.CTC_GREEDY_ARGMAX_V1
                        else -> reject(
                            "unknown_enum",
                            "$.ctc_decoding.collapse_semantics",
                            "unknown CTC collapse semantics",
                        )
                    },
                    scoreSemantics = when (
                        value.requireString("score_semantics", "$.ctc_decoding")
                    ) {
                        "probabilities_v1" -> CtcScoreSemantics.PROBABILITIES_V1
                        "unnormalized_logits_softmax_v1" ->
                            CtcScoreSemantics.UNNORMALIZED_LOGITS_SOFTMAX_V1
                        else -> reject(
                            "unknown_enum",
                            "$.ctc_decoding.score_semantics",
                            "unknown CTC score semantics",
                        )
                    },
                )
            },
            selfTest = root.get("self_test")?.let { element ->
                val value = element.asStrictObject("$.self_test")
                value.requireExactKeys(
                    setOf("schema_id", "artifact_role", "artifact_sha256", "input", "expected"),
                    "$.self_test",
                )
                val input = value.requireObject("input", "$.self_test")
                input.requireExactKeys(
                    setOf("encoding", "width", "height", "rgb888_base64", "sha256"),
                    "$.self_test.input",
                )
                val expected = value.requireObject("expected", "$.self_test")
                expected.requireExactKeys(
                    setOf("text", "value_decimal", "unit", "minimum_confidence"),
                    "$.self_test.expected",
                )
                app.beyoureyes.core.vision.ReadingKnownAnswerSelfTest(
                    schemaId = value.requireString("schema_id", "$.self_test"),
                    artifactRole = requireIdentifier(
                        value.requireString("artifact_role", "$.self_test"),
                        "$.self_test.artifact_role",
                    ),
                    artifactSha256 = requireSha256(
                        value.requireString("artifact_sha256", "$.self_test"),
                        "$.self_test.artifact_sha256",
                    ),
                    input = app.beyoureyes.core.vision.ReadingKnownAnswerInput(
                        encoding = input.requireString("encoding", "$.self_test.input"),
                        width = input.requireLong("width", "$.self_test.input")
                            .toIntExact("$.self_test.input.width"),
                        height = input.requireLong("height", "$.self_test.input")
                            .toIntExact("$.self_test.input.height"),
                        // The signed known-answer image is bounded by the entire 2 MiB Manifest,
                        // not the 4096-character metadata-string default.
                        rgb888Base64 = input.requireString(
                            "rgb888_base64",
                            "$.self_test.input",
                            MAX_SIGNED_MANIFEST_BYTES,
                        ),
                        sha256 = requireSha256(
                            input.requireString("sha256", "$.self_test.input"),
                            "$.self_test.input.sha256",
                        ),
                    ),
                    expected = app.beyoureyes.core.vision.ReadingKnownAnswerExpected(
                        text = expected.requireString("text", "$.self_test.expected"),
                        valueDecimal = expected.requireString(
                            "value_decimal",
                            "$.self_test.expected",
                        ),
                        unit = expected.optionalString("unit", "$.self_test.expected"),
                        minimumConfidence = expected.requireDouble(
                            "minimum_confidence",
                            "$.self_test.expected",
                        ).toFloat(),
                    ),
                )
            },
            parameterProfile = parseParameterProfile(root.requireObject("parameter_profile", "$")),
            deviceCompatibility = parseDeviceCompatibility(root.requireObject("device_compatibility", "$")),
            signature = signature,
        )
        val structuralErrors = manifest.structuralErrors()
        if (structuralErrors.isNotEmpty()) {
            reject("manifest_semantic_error", "$", structuralErrors.sorted().take(8).joinToString(","))
        }
        return manifest
    }

    private fun parseSource(value: JsonObject, path: String): SourceReference {
        value.requireExactKeys(setOf("url", "version"), path)
        return SourceReference(
            requireAbsoluteSourceUrl(value.requireString("url", path), "$path.url"),
            value.requireString("version", path),
        )
    }

    private fun parseLicense(value: JsonObject): ModelPackageLicense {
        val path = "$.license"
        val licenseKeys = buildSet {
            addAll(
                setOf(
                "license_id", "license_text_sha256", "review_status", "reviewed_at",
                "review_evidence_ref", "commercial_use_allowed", "redistribution_allowed",
                "source_disclosure_required", "license_grant_id", "grant_expires_at",
                ),
            )
            if (value.has("review_evidence_sha256")) add("review_evidence_sha256")
        }
        value.requireExactKeys(licenseKeys, path)
        val status = when (value.requireString("review_status", path)) {
            "pending_review" -> LicenseReviewStatus.PENDING_REVIEW
            "approved" -> LicenseReviewStatus.APPROVED
            "rejected" -> LicenseReviewStatus.REJECTED
            "excluded_by_license" -> LicenseReviewStatus.EXCLUDED_BY_LICENSE
            "withdrawn" -> LicenseReviewStatus.WITHDRAWN
            else -> reject("unknown_enum", "$path.review_status", "unknown license review status")
        }
        val reviewedAt = value.optionalString("reviewed_at", path)?.also {
            requireIsoInstant(it, "$path.reviewed_at")
        }
        val grantExpiresAt = value.optionalString("grant_expires_at", path)?.also {
            requireIsoInstant(it, "$path.grant_expires_at")
        }
        return ModelPackageLicense(
            licenseId = value.requireString("license_id", path),
            licenseTextSha256 = requireSha256(value.requireString("license_text_sha256", path), "$path.license_text_sha256"),
            reviewStatus = status,
            reviewedAt = reviewedAt,
            reviewEvidenceRef = value.optionalString("review_evidence_ref", path),
            commercialUseAllowed = value.requireBoolean("commercial_use_allowed", path),
            redistributionAllowed = value.requireBoolean("redistribution_allowed", path),
            sourceDisclosureRequired = value.requireBoolean("source_disclosure_required", path),
            licenseGrantId = value.optionalString("license_grant_id", path),
            grantExpiresAt = grantExpiresAt,
            reviewEvidenceSha256 = value.stringIfPresent("review_evidence_sha256", path)?.let {
                requireSha256(it, "$path.review_evidence_sha256")
            },
        )
    }

    private fun parseArtifacts(array: JsonArray): List<ArtifactComponent> {
        array.requireNonEmpty("$.artifacts")
        val artifacts = array.mapIndexed { index, element ->
            val path = "$.artifacts[$index]"
            val value = element.asStrictObject(path)
            value.requireExactKeys(setOf("role", "runtime", "media_type", "url", "sha256", "size_bytes"), path)
            ArtifactComponent(
                role = requireIdentifier(value.requireString("role", path), "$path.role"),
                runtime = when (value.requireString("runtime", path)) {
                    "litert" -> RuntimeKind.LITERT
                    "onnx" -> RuntimeKind.ONNX
                    "classic_vision" -> RuntimeKind.CLASSIC_VISION
                    "ctc_vocabulary" -> RuntimeKind.CTC_VOCABULARY
                    else -> reject("unknown_enum", "$path.runtime", "unknown runtime")
                },
                mediaType = when (value.requireString("media_type", path)) {
                    "application/vnd.google.litert" -> ArtifactMediaType.LITERT
                    "application/onnx" -> ArtifactMediaType.ONNX
                    "application/vnd.beyoureyes.classic-vision+json" -> ArtifactMediaType.CLASSIC_VISION_JSON
                    "application/vnd.beyoureyes.ctc-vocabulary+json" ->
                        ArtifactMediaType.CTC_VOCABULARY_JSON
                    else -> reject("unknown_enum", "$path.media_type", "unknown media type")
                },
                url = requireFixedHttpsUrl(value.requireString("url", path), "$path.url"),
                sha256 = requireSha256(value.requireString("sha256", path), "$path.sha256"),
                sizeBytes = value.requireLong("size_bytes", path).also {
                    if (it <= 0) reject("size_out_of_range", "$path.size_bytes", "positive size required")
                },
            )
        }
        requireUnique(artifacts.map(ArtifactComponent::role), "$.artifacts", "role")
        return Collections.unmodifiableList(artifacts)
    }

    private fun parseInputs(array: JsonArray): List<InputComponent> {
        array.requireNonEmpty("$.inputs")
        return Collections.unmodifiableList(array.mapIndexed { index, element ->
            val path = "$.inputs[$index]"
            val value = element.asStrictObject(path)
            value.requireExactKeys(
                setOf(
                    "role", "artifact_role", "tensor_index", "tensor_name", "dtype", "layout",
                    "color_space", "runtime_shape", "quantization",
                ),
                path,
            )
            InputComponent(
                role = when (value.requireString("role", path)) {
                    "image" -> InputRole.IMAGE
                    "image_tensor" -> InputRole.IMAGE_TENSOR
                    "image_features" -> InputRole.IMAGE_FEATURES
                    "reference_images" -> InputRole.REFERENCE_IMAGES
                    "reference_features" -> InputRole.REFERENCE_FEATURES
                    "reading_task_spec" -> InputRole.READING_TASK_SPEC
                    "detection_boxes" -> InputRole.DETECTION_BOXES
                    "detection_classes" -> InputRole.DETECTION_CLASSES
                    "detection_scores" -> InputRole.DETECTION_SCORES
                    "detection_count" -> InputRole.DETECTION_COUNT
                    else -> reject("unknown_enum", "$path.role", "unknown input role")
                },
                artifactRole = requireIdentifier(value.requireString("artifact_role", path), "$path.artifact_role"),
                tensorIndex = value.requireLong("tensor_index", path).toIntExact("$path.tensor_index"),
                tensorName = value.requireString("tensor_name", path),
                dataType = parseDataType(value.requireString("dtype", path), "$path.dtype"),
                runtimeShape = parseShape(value.requireArray("runtime_shape", path), "$path.runtime_shape"),
                quantization = parseQuantization(value.requireObject("quantization", path), "$path.quantization"),
                layout = value.optionalEnum("layout", path) { wire ->
                    when (wire) {
                        "NHWC" -> TensorLayout.NHWC
                        "NCHW" -> TensorLayout.NCHW
                        else -> null
                    }
                },
                colorSpace = value.optionalEnum("color_space", path) { wire ->
                    when (wire) {
                        "RGB" -> ColorSpace.RGB
                        "BGR" -> ColorSpace.BGR
                        "GRAY" -> ColorSpace.GRAY
                        else -> null
                    }
                },
            )
        })
    }

    private fun parseOutputs(array: JsonArray): List<OutputComponent> {
        array.requireNonEmpty("$.outputs")
        return Collections.unmodifiableList(array.mapIndexed { index, element ->
            val path = "$.outputs[$index]"
            val value = element.asStrictObject(path)
            value.requireExactKeys(
                setOf("role", "artifact_role", "tensor_index", "tensor_name", "dtype", "runtime_shape"),
                path,
            )
            OutputComponent(
                role = when (value.requireString("role", path)) {
                    "image_tensor" -> OutputRole.IMAGE_TENSOR
                    "image_features" -> OutputRole.IMAGE_FEATURES
                    "reference_features" -> OutputRole.REFERENCE_FEATURES
                    "detection_boxes" -> OutputRole.DETECTION_BOXES
                    "detection_classes" -> OutputRole.DETECTION_CLASSES
                    "detection_scores" -> OutputRole.DETECTION_SCORES
                    "detection_count" -> OutputRole.DETECTION_COUNT
                    "ctc_logits" -> OutputRole.CTC_LOGITS
                    "similarity_scores" -> OutputRole.SIMILARITY_SCORES
                    "text_probability_map" -> OutputRole.TEXT_PROBABILITY_MAP
                    else -> reject("unknown_enum", "$path.role", "unknown output role")
                },
                artifactRole = requireIdentifier(value.requireString("artifact_role", path), "$path.artifact_role"),
                tensorIndex = value.requireLong("tensor_index", path).toIntExact("$path.tensor_index"),
                tensorName = value.requireString("tensor_name", path),
                dataType = parseDataType(value.requireString("dtype", path), "$path.dtype"),
                runtimeShape = parseShape(value.requireArray("runtime_shape", path), "$path.runtime_shape"),
            )
        })
    }

    private fun parseBindings(array: JsonArray): List<TensorBinding> = Collections.unmodifiableList(
        array.mapIndexed { index, element ->
            val path = "$.bindings[$index]"
            val value = element.asStrictObject(path)
            value.requireExactKeys(
                setOf("source_artifact_role", "source_tensor_name", "target_artifact_role", "target_tensor_name"),
                path,
            )
            TensorBinding(
                requireIdentifier(value.requireString("source_artifact_role", path), "$path.source_artifact_role"),
                value.requireString("source_tensor_name", path),
                requireIdentifier(value.requireString("target_artifact_role", path), "$path.target_artifact_role"),
                value.requireString("target_tensor_name", path),
            )
        },
    )

    private fun parseAdapter(value: JsonObject): AdapterContract {
        val path = "$.adapter_contract"
        value.requireExactKeys(setOf("schema_id", "class_map", "embedded_postprocess"), path)
        val schemaId = value.requireString("schema_id", path)
        if (schemaId !in ADAPTER_SCHEMAS) reject("unknown_enum", "$path.schema_id", "unsupported adapter contract")
        val classMapValue = value.requireElement("class_map", path)
        val classMap = if (classMapValue.isJsonNull) null else {
            val map = classMapValue.asStrictObject("$path.class_map")
            val keys = setOf("identity", "sha256", "class_id_base") +
                if (map.has("targets")) setOf("targets") else emptySet()
            map.requireExactKeys(keys, "$path.class_map")
            val base = map.requireLong("class_id_base", "$path.class_map").toIntExact("$path.class_map.class_id_base")
            if (base !in setOf(0, 1)) reject("class_id_base_out_of_range", "$path.class_map.class_id_base", "0 or 1 required")
            val targets = map.get("targets")?.asJsonArray?.mapIndexed { index, raw ->
                val targetPath = "$path.class_map.targets[$index]"
                val target = raw.asStrictObject(targetPath)
                target.requireExactKeys(
                    setOf("raw_class_id", "target_id", "label_zh_cn", "label_en", "aliases"),
                    targetPath,
                )
                val aliases = target.requireArray("aliases", targetPath)
                    .mapIndexed { aliasIndex, alias ->
                        if (!alias.isJsonPrimitive || !alias.asJsonPrimitive.isString) {
                            reject("invalid_type", "$targetPath.aliases[$aliasIndex]", "string required")
                        }
                        alias.asString
                    }.toSet()
                ClassMapTarget(
                    rawClassId = target.requireLong("raw_class_id", targetPath)
                        .toIntExact("$targetPath.raw_class_id"),
                    targetId = requireIdentifier(target.requireString("target_id", targetPath), "$targetPath.target_id"),
                    labelZhCn = target.requireString("label_zh_cn", targetPath),
                    labelEn = target.requireString("label_en", targetPath),
                    aliases = aliases,
                )
            } ?: emptyList()
            ClassMapSpec(
                map.requireString("identity", "$path.class_map"),
                requireSha256(map.requireString("sha256", "$path.class_map"), "$path.class_map.sha256"),
                base,
                targets,
            )
        }
        val postprocessValue = value.requireElement("embedded_postprocess", path)
        val embedded = if (postprocessValue.isJsonNull) null else {
            val postprocess = postprocessValue.asStrictObject("$path.embedded_postprocess")
            postprocess.requireExactKeys(
                setOf("max_detections", "score_threshold", "nms_iou_threshold"),
                "$path.embedded_postprocess",
            )
            EmbeddedPostprocessSpec(
                maxDetections = postprocess.optionalLong("max_detections", "$path.embedded_postprocess")
                    ?.toIntExact("$path.embedded_postprocess.max_detections"),
                scoreThreshold = postprocess.optionalDouble("score_threshold", "$path.embedded_postprocess"),
                nmsIouThreshold = postprocess.optionalDouble("nms_iou_threshold", "$path.embedded_postprocess"),
            )
        }
        return AdapterContract(schemaId, classMap, embedded)
    }

    private fun parseParameterProfile(value: JsonObject): ParameterProfile {
        val path = "$.parameter_profile"
        value.requireExactKeys(setOf("defaults", "allowed_bounds", "sampling_policy"), path)
        val defaultsObject = value.requireObject("defaults", path)
        val defaults = defaultsObject.entrySet().associate { (name, raw) ->
            requireIdentifier(name, "$path.defaults")
            if (!raw.isJsonPrimitive || !raw.asJsonPrimitive.isNumber) {
                reject("invalid_type", "$path.defaults.$name", "number required")
            }
            name to defaultsObject.requireDouble(name, "$path.defaults")
        }
        val boundsObject = value.requireObject("allowed_bounds", path)
        val bounds = boundsObject.entrySet().associate { (name, raw) ->
            requireIdentifier(name, "$path.allowed_bounds")
            val bound = raw.asStrictObject("$path.allowed_bounds.$name")
            bound.requireExactKeys(setOf("minimum", "maximum"), "$path.allowed_bounds.$name")
            name to ParameterBound(
                bound.requireDouble("minimum", "$path.allowed_bounds.$name"),
                bound.requireDouble("maximum", "$path.allowed_bounds.$name"),
            )
        }
        val sampling = value.requireObject("sampling_policy", path)
        sampling.requireExactKeys(
            setOf("default_interval_ms", "min_interval_ms", "max_interval_ms", "adaptive_allowed"),
            "$path.sampling_policy",
        )
        return ParameterProfile(
            defaults = Collections.unmodifiableMap(defaults),
            allowedBounds = Collections.unmodifiableMap(bounds),
            samplingPolicy = SamplingPolicySpec(
                defaultIntervalMillis = sampling.optionalLong("default_interval_ms", "$path.sampling_policy"),
                minimumIntervalMillis = sampling.optionalLong("min_interval_ms", "$path.sampling_policy"),
                maximumIntervalMillis = sampling.optionalLong("max_interval_ms", "$path.sampling_policy"),
                adaptiveAllowed = sampling.requireBoolean("adaptive_allowed", "$path.sampling_policy"),
            ),
        )
    }

    private fun parseDeviceCompatibility(value: JsonObject): DeviceCompatibility {
        val path = "$.device_compatibility"
        value.requireExactKeys(
            setOf("device_profile_ids", "min_android_api", "max_android_api", "abis", "min_memory_mb", "required_features"),
            path,
        )
        val profiles = value.requireArray("device_profile_ids", path).requireNonEmpty("$path.device_profile_ids")
            .stringSet("$path.device_profile_ids")
        if (profiles != setOf("android_arm64_8gb_launch_v1")) {
            reject("unsupported_device_profile", "$path.device_profile_ids", "launch 8 GB profile required")
        }
        val abis = value.requireArray("abis", path).requireNonEmpty("$path.abis")
            .stringSet("$path.abis", setOf("arm64-v8a"))
        return DeviceCompatibility(
            deviceProfileIds = profiles,
            minAndroidApi = value.requireLong("min_android_api", path).toIntExact("$path.min_android_api"),
            maxAndroidApi = value.optionalLong("max_android_api", path)?.toIntExact("$path.max_android_api"),
            supportedAbis = abis,
            minimumMemoryMb = value.requireLong("min_memory_mb", path).toIntExact("$path.min_memory_mb"),
            requiredFeatures = value.requireArray("required_features", path).stringSet("$path.required_features"),
        )
    }

    private fun parseManifestSignature(value: JsonObject): ManifestSignature {
        val path = "$.signature"
        value.requireExactKeys(setOf("canonicalization", "algorithm", "signing_key_id", "value"), path)
        val canonicalization = value.requireString("canonicalization", path)
        val algorithm = value.requireString("algorithm", path)
        if (canonicalization != "RFC8785" || algorithm != "Ed25519") {
            reject("invalid_signature_contract", path, "RFC8785/Ed25519 required")
        }
        val keyId = value.requireString("signing_key_id", path)
        val encoded = value.requireString("value", path)
        if (decodeCanonicalBase64(encoded, "$path.value").size != 64) {
            reject("invalid_signature_encoding", "$path.value", "Ed25519 signatures contain 64 bytes")
        }
        return ManifestSignature(canonicalization, algorithm, keyId, encoded)
    }

    private fun parseSupportedTasks(array: JsonArray): Set<SupportedTask> {
        val wires = array.requireNonEmpty("$.supported_tasks").stringSet("$.supported_tasks", CAPABILITY_IDS)
        return wires.mapTo(linkedSetOf()) { wire ->
            SupportedTask.fromWireValue(wire)
                ?: reject("unknown_enum", "$.supported_tasks", "unknown supported task")
        }
    }

    private fun parseTargetModes(array: JsonArray, path: String): Set<TargetMode> {
        val wires = array.requireNonEmpty(path).stringSet(
            path,
            setOf("object_class", "reference_images", "none"),
        )
        return wires.mapTo(linkedSetOf()) { wire ->
            TargetMode.fromWireValue(wire)
                ?: reject("unknown_enum", path, "unknown target mode")
        }
    }

    private fun parseQuantization(value: JsonObject, path: String): InputQuantization {
        val mode = value.requireString("mode", path)
        return when (mode) {
            "none" -> {
                value.requireExactKeys(setOf("mode"), path)
                InputQuantization(QuantizationMode.NONE)
            }
            "per_tensor" -> {
                value.requireExactKeys(setOf("mode", "scale", "zero_point"), path)
                InputQuantization(
                    QuantizationMode.PER_TENSOR,
                    scale = value.requireDouble("scale", path),
                    zeroPoint = value.requireLong("zero_point", path).toIntExact("$path.zero_point"),
                )
            }
            else -> reject("unknown_enum", "$path.mode", "unknown quantization mode")
        }
    }

    private fun parseShape(array: JsonArray, path: String): List<Int> {
        if (array.size() !in 1..8) reject("shape_rank_out_of_range", path, "shape rank must be 1..8")
        return Collections.unmodifiableList(array.mapIndexed { index, raw ->
            if (!raw.isJsonPrimitive || !raw.asJsonPrimitive.isNumber) {
                reject("invalid_type", "$path[$index]", "integer required")
            }
            val holder = JsonObject().also { it.add("value", raw) }
            holder.requireLong("value", path).also {
                if (it <= 0) reject("shape_dimension_out_of_range", "$path[$index]", "positive dimension required")
            }.toIntExact("$path[$index]")
        })
    }

    private fun parseDataType(value: String, path: String): TensorDataType = when (value) {
        "uint8" -> TensorDataType.UINT8
        "int8" -> TensorDataType.INT8
        "int32" -> TensorDataType.INT32
        "int64" -> TensorDataType.INT64
        "float16" -> TensorDataType.FLOAT16
        "float32" -> TensorDataType.FLOAT32
        else -> reject("unknown_enum", path, "unknown tensor data type")
    }

    private fun validateLicenseGate(
        manifest: ModelPackageManifest,
        buildChannel: BuildChannel,
        nowEpochMillis: Long,
    ): Long {
        val license = manifest.license
        val reviewedAtValue = license.reviewedAt
        if (license.reviewStatus != LicenseReviewStatus.APPROVED ||
            reviewedAtValue == null || license.reviewEvidenceRef == null
        ) {
            reject("license_not_approved", "$.license", "an evidenced approved review is required")
        }
        val reviewedAt = requireIsoInstant(
            checkNotNull(reviewedAtValue),
            "$.license.reviewed_at",
        )
        if (reviewedAt > nowEpochMillis + MAX_METADATA_CLOCK_SKEW_MILLIS) {
            reject("license_review_from_future", "$.license.reviewed_at", "review timestamp exceeds allowed clock skew")
        }
        if (buildChannel in setOf(BuildChannel.COMMUNITY, BuildChannel.COMMERCIAL)) {
            if (license.reviewEvidenceSha256 == null) {
                reject(
                    "license_review_evidence_hash_missing",
                    "$.license.review_evidence_sha256",
                    "commercial license review evidence SHA-256 is required",
                )
            }
            if (!license.commercialUseAllowed) reject("commercial_use_forbidden", "$.license", "commercial use not allowed")
            if (!license.redistributionAllowed) reject("redistribution_forbidden", "$.license", "redistribution not allowed")
            if (license.sourceDisclosureRequired) reject("source_disclosure_required", "$.license", "closed commercial channel forbidden")
            if (manifest.commercialIoErrors().isNotEmpty()) {
                reject("commercial_metadata_incomplete", "$", manifest.commercialIoErrors().sorted().joinToString(","))
            }
        }
        val expiry = license.grantExpiresAt?.let { requireIsoInstant(it, "$.license.grant_expires_at") }
            ?: Long.MAX_VALUE
        if (nowEpochMillis >= expiry) reject("license_grant_expired", "$.license.grant_expires_at", "license grant expired")
        return expiry
    }

    private fun validateOperationalAdmission(
        manifest: ModelPackageManifest,
        catalog: CapabilityCatalogMetadata,
    ) {
        val admissions = catalog.operationalCapabilities.filter { operational ->
            manifest.packageId in operational.packageIds
        }
        if (admissions.isEmpty()) {
            reject(
                "package_not_in_operational_inventory",
                "$.package_id",
                "active package has no supported operational capability",
            )
        }
        admissions.forEach { operational ->
            val recipe = catalog.runtimeRecipes.singleOrNull {
                it.recipeId == operational.recipeId
            } ?: reject(
                "operational_recipe_mismatch",
                "$.package_id",
                "operational model profile has no exact runtime recipe",
            )
            if (manifest.runtimeFamily != recipe.runtimeFamily ||
                manifest.supportedTasks.none { it.wireValue == recipe.capabilityId } ||
                !manifest.promptModes.containsAll(recipe.promptModes)
            ) {
                reject(
                    "operational_recipe_manifest_mismatch",
                    "$.package_id",
                    "Manifest does not implement ${operational.capabilityKey} recipe ${recipe.recipeId}",
                )
            }
            if (recipe.runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1) {
                val manifestTargetIds = manifest.adapterContract.classMap?.targets
                    ?.mapTo(linkedSetOf(), ClassMapTarget::targetId)
                    ?: emptySet()
                if (!operational.targetIds.containsAll(manifestTargetIds) ||
                    (operational.packageIds.size == 1 && operational.targetIds != manifestTargetIds)
                ) {
                    reject(
                        "operational_target_coverage_manifest_mismatch",
                        "$.adapter_contract.class_map.targets",
                        "Manifest targets are not exactly authorized by ${operational.capabilityKey}",
                    )
                }
            }
            if (manifest.supportedTasks.none { it.wireValue == operational.capabilityId }) {
                reject(
                    "operational_capability_manifest_mismatch",
                    "$.supported_tasks",
                    "Manifest does not implement ${operational.capabilityKey}",
                )
            }
            if (!manifest.deviceCompatibility.deviceProfileIds.containsAll(operational.deviceProfileIds)) {
                reject(
                    "operational_device_profile_mismatch",
                    "$.device_compatibility.device_profile_ids",
                    "Manifest does not cover the operational device boundary",
                )
            }
            val reviewRef = manifest.license.reviewEvidenceRef
            if (reviewRef == null || reviewRef !in operational.humanReview.evidenceRefs) {
                reject(
                    "operational_review_evidence_mismatch",
                    "$.license.review_evidence_ref",
                    "Manifest review is not bound by the operational capability",
                )
            }
        }
    }

    private val MANIFEST_KEYS = setOf(
        "schema_version", "package_id", "package_version", "runtime_family", "supported_tasks",
        "prompt_modes", "model_source", "weights_source", "code_source", "export_tool_source",
        "license", "artifacts", "inputs", "outputs", "bindings", "adapter_contract",
        "preprocess_id", "postprocess_id", "parameter_profile", "device_compatibility",
        "signature",
    )
    private val CAPABILITY_IDS = setOf("visual_target", "visible_state", "structured_reading")
    private val OPERATIONAL_STATUSES = setOf("internal-evaluation", "commercial", "community")
    private val OPERATIONAL_LICENSE_CONCLUSIONS = setOf(
        "approved-for-internal-evaluation",
        "approved-for-commercial",
        "approved-for-community",
    )
    private val MODEL_KINDS = setOf("general", "specialist")
    private val RULE_TYPES = setOf(
        "presence_duration", "absence_duration", "object_count", "reading_threshold", "state_transition",
    )
    private val ADAPTER_SCHEMAS = setOf(
        "object_detection_v1", "similarity_match_v1", "structured_reading_v2",
    )
}

private fun JsonObject.stringIfPresent(name: String, path: String): String? =
    if (has(name)) requireString(name, path) else null

private fun ArtifactComponent.fileExtension(): String = when (mediaType) {
    ArtifactMediaType.LITERT -> "tflite"
    ArtifactMediaType.ONNX -> "onnx"
    ArtifactMediaType.CLASSIC_VISION_JSON -> "json"
    ArtifactMediaType.CTC_VOCABULARY_JSON -> "ctc-vocabulary.json"
}

private fun com.google.gson.JsonElement.asStrictObject(path: String): JsonObject {
    if (!isJsonObject) reject("invalid_type", path, "object required")
    return asJsonObject
}

private inline fun <T> JsonObject.optionalEnum(
    name: String,
    path: String,
    decode: (String) -> T?,
): T? {
    val value = requireElement(name, path)
    if (value.isJsonNull) return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        reject("invalid_type", "$path.$name", "string or null required")
    }
    return decode(value.asString)
        ?: reject("unknown_enum", "$path.$name", "unknown value ${value.asString}")
}

private fun Long.toIntExact(path: String): Int = try {
    Math.toIntExact(this)
} catch (_: ArithmeticException) {
    reject("integer_out_of_range", path, "integer is outside Int range")
}

private fun requireUnique(values: List<String>, path: String, field: String) {
    if (values.distinct().size != values.size) reject("duplicate_identity", path, "duplicate $field")
}
