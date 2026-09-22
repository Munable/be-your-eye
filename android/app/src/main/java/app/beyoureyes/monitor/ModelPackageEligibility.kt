package app.beyoureyes.monitor

import app.beyoureyes.core.data.CatalogCapability
import app.beyoureyes.core.data.CatalogOperationalCapability
import app.beyoureyes.core.data.CatalogPackageEntry
import app.beyoureyes.core.data.CatalogRuntimeRecipe
import app.beyoureyes.core.data.VerifiedCapabilityCatalog
import app.beyoureyes.core.data.VerifiedManifestDocument
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.ClassMapTarget
import app.beyoureyes.core.vision.LicenseReviewStatus
import app.beyoureyes.core.vision.ModelPackageManifest
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.SupportedTask
import app.beyoureyes.core.vision.TargetMode
import app.beyoureyes.core.vision.TargetProfile
import java.time.Instant

internal const val LAUNCH_DEVICE_PROFILE_ID = "android_arm64_8gb_launch_v1"
internal const val REFERENCE_MODEL_PROFILE_KEY = "reference_object_matching"
internal const val COMMON_OBJECT_MODEL_PROFILE_KEY =
    "common_objects_tensorflow_efficientdet_lite2"
internal const val READING_MODEL_PROFILE_KEY = "numeric_display_reading"
internal val APP_MODEL_RUNTIME_FAMILIES = setOf(
    RecipeFamily.OBJECT_DETECTION_V1,
    RecipeFamily.SIMILARITY_MATCH_V1,
    RecipeFamily.READING_PIPELINE_V1,
)

internal enum class ModelPackageIneligibility {
    DEVELOPMENT_CHANNEL_DISABLED,
    BUILD_CHANNEL_MISMATCH,
    SIGNED_METADATA_CHAIN_INVALID,
    CATALOG_OR_LICENSE_GATE_EXPIRED,
    CATALOG_ENTRY_INACTIVE,
    MANIFEST_STRUCTURE_INVALID,
    LICENSE_NOT_APPROVED,
    COMMERCIAL_LICENSE_FORBIDDEN,
    COMMERCIAL_METADATA_INCOMPLETE,
    CAPABILITY_MISMATCH,
    MODEL_PROFILE_MISMATCH,
    PACKAGE_SELECTION_MISMATCH,
    INTENT_NOT_COVERED,
    RECIPE_MISMATCH,
    RULE_NOT_ALLOWED,
    TASK_NOT_SUPPORTED,
    TARGET_MODE_NOT_SUPPORTED,
    TARGET_NOT_SUPPORTED,
    TARGET_METADATA_MISMATCH,
    RUNTIME_FAMILY_MISMATCH,
    RUNTIME_FAMILY_UNAVAILABLE,
    ANDROID_API_INCOMPATIBLE,
    ABI_INCOMPATIBLE,
    INSUFFICIENT_MEMORY,
    REQUIRED_FEATURE_MISSING,
}

internal data class ModelPackageEligibilityResult(
    val reasons: Set<ModelPackageIneligibility>,
) {
    val eligible: Boolean get() = reasons.isEmpty()
}

/** One normalized request shared by smart routing and manual model preparation. */
internal data class ModelPackageEligibilityRequirement(
    val buildChannel: BuildChannel,
    val device: ModelPreparationDevice,
    val capabilityId: String,
    val requiredTask: SupportedTask,
    val targetMode: TargetMode,
    val requiredRuleType: String,
    val requiredRuntimeFamily: RecipeFamily?,
    val requiredModelProfileKey: String,
    val requiredPackageId: String?,
    val requiredIntentKey: String,
    val requiredObjectTarget: TargetProfile.ObjectClass?,
    val nowEpochMillis: Long,
    val availableRuntimeFamilies: Set<RecipeFamily> = APP_MODEL_RUNTIME_FAMILIES,
) {
    init {
        require(capabilityId.isNotBlank())
        require(requiredRuleType.isNotBlank())
        require(requiredModelProfileKey.isNotBlank())
        require(requiredPackageId == null || requiredPackageId.isNotBlank())
        require(requiredIntentKey.matches(Regex("^[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)*$")))
        require((targetMode == TargetMode.OBJECT_CLASS) == (requiredObjectTarget != null))
        require(nowEpochMillis >= 0)
        require(availableRuntimeFamilies.isNotEmpty())
    }
}

/**
 * Immutable facts from one already decoded Catalog/Manifest pair. Tests can construct the same
 * facts without weakening the production rule that only verified document types enter here.
 */
internal data class ModelPackageEligibilityCandidate(
    val catalogBuildChannel: BuildChannel,
    val catalogValidUntilEpochMillis: Long,
    val licenseRunValidUntilEpochMillis: Long,
    val signedMetadataChainValid: Boolean,
    val catalogEntry: CatalogPackageEntry,
    val manifestDocumentSha256: String,
    val capability: CatalogCapability,
    val recipe: CatalogRuntimeRecipe,
    val operationalCapability: CatalogOperationalCapability,
    val manifest: ModelPackageManifest,
) {
    companion object {
        fun fromVerified(
            catalog: VerifiedCapabilityCatalog,
            document: VerifiedManifestDocument,
            capability: CatalogCapability,
            recipe: CatalogRuntimeRecipe,
            operationalCapability: CatalogOperationalCapability,
        ): ModelPackageEligibilityCandidate {
            val catalogEntry = catalog.catalog.packages.singleOrNull {
                it.packageId == document.catalogEntry.packageId
            } ?: document.catalogEntry
            val chainValid = catalog.activePackage(document.manifest.packageId) == catalogEntry &&
                document.catalogEntry == catalogEntry &&
                document.documentSha256 == catalogEntry.manifestSha256 &&
                document.manifest.packageId == catalogEntry.packageId &&
                document.manifest.packageVersion == catalogEntry.packageVersion &&
                catalog.catalog.operationalCapabilities.singleOrNull {
                    it.capabilityKey == operationalCapability.capabilityKey
                } == operationalCapability
            return ModelPackageEligibilityCandidate(
                catalogBuildChannel = catalog.catalog.buildChannel,
                catalogValidUntilEpochMillis = catalog.validUntilEpochMillis,
                licenseRunValidUntilEpochMillis = document.licenseRunValidUntilEpochMillis,
                signedMetadataChainValid = chainValid,
                catalogEntry = catalogEntry,
                manifestDocumentSha256 = document.documentSha256,
                capability = capability,
                recipe = recipe,
                operationalCapability = operationalCapability,
                manifest = document.manifest,
            )
        }
    }
}

/** Pure, deterministic and package/vendor-neutral Catalog + Manifest eligibility gate. */
internal object ModelPackageEligibilityEvaluator {
    fun evaluate(
        candidate: ModelPackageEligibilityCandidate,
        requirement: ModelPackageEligibilityRequirement,
    ): ModelPackageEligibilityResult = ModelPackageEligibilityResult(buildSet {
        val manifest = candidate.manifest
        val entry = candidate.catalogEntry
        val capability = candidate.capability
        val recipe = candidate.recipe
        val operational = candidate.operationalCapability
        val compatibility = manifest.deviceCompatibility
        val license = manifest.license

        if (requirement.buildChannel == BuildChannel.DEVELOPMENT_NO_MODEL) {
            add(ModelPackageIneligibility.DEVELOPMENT_CHANNEL_DISABLED)
        }
        if (candidate.catalogBuildChannel != requirement.buildChannel) {
            add(ModelPackageIneligibility.BUILD_CHANNEL_MISMATCH)
        }
        if (!candidate.signedMetadataChainValid ||
            candidate.manifestDocumentSha256 != entry.manifestSha256 ||
            manifest.packageId != entry.packageId ||
            manifest.packageVersion != entry.packageVersion
        ) {
            add(ModelPackageIneligibility.SIGNED_METADATA_CHAIN_INVALID)
        }
        if (!entry.active) add(ModelPackageIneligibility.CATALOG_ENTRY_INACTIVE)
        if (candidate.catalogValidUntilEpochMillis <= requirement.nowEpochMillis ||
            candidate.licenseRunValidUntilEpochMillis <= requirement.nowEpochMillis ||
            license.grantExpiresAt?.let(::instantMillisOrNull)
                ?.let { it <= requirement.nowEpochMillis } == true
        ) {
            add(ModelPackageIneligibility.CATALOG_OR_LICENSE_GATE_EXPIRED)
        }
        if (manifest.structuralErrors().isNotEmpty()) {
            add(ModelPackageIneligibility.MANIFEST_STRUCTURE_INVALID)
        }
        if (license.reviewStatus != LicenseReviewStatus.APPROVED ||
            license.reviewedAt == null || license.reviewEvidenceRef.isNullOrBlank()
        ) {
            add(ModelPackageIneligibility.LICENSE_NOT_APPROVED)
        }

        if (capability.capabilityId != requirement.capabilityId ||
            recipe.capabilityId != requirement.capabilityId ||
            requirement.requiredTask.wireValue != requirement.capabilityId
        ) {
            add(ModelPackageIneligibility.CAPABILITY_MISMATCH)
        }
        if (operational.capabilityKey != requirement.requiredModelProfileKey ||
            operational.capabilityId != requirement.capabilityId ||
            operational.recipeId != recipe.recipeId ||
            manifest.packageId !in operational.packageIds
        ) {
            add(ModelPackageIneligibility.MODEL_PROFILE_MISMATCH)
        }
        if (requirement.requiredPackageId?.let { it != manifest.packageId } == true) {
            add(ModelPackageIneligibility.PACKAGE_SELECTION_MISMATCH)
        }
        if (!operational.matchesIntentKey(requirement.requiredIntentKey)) {
            add(ModelPackageIneligibility.INTENT_NOT_COVERED)
        }
        if (recipe.recipeId !in capability.recipeIds || manifest.packageId !in recipe.candidatePackageIds) {
            add(ModelPackageIneligibility.RECIPE_MISMATCH)
        }
        if (requirement.requiredRuleType !in capability.allowedRuleTypes) {
            add(ModelPackageIneligibility.RULE_NOT_ALLOWED)
        }
        if (requirement.requiredTask !in manifest.supportedTasks) {
            add(ModelPackageIneligibility.TASK_NOT_SUPPORTED)
        }
        if (requirement.targetMode !in capability.targetModes ||
            requirement.targetMode !in recipe.promptModes ||
            requirement.targetMode !in manifest.promptModes
        ) {
            add(ModelPackageIneligibility.TARGET_MODE_NOT_SUPPORTED)
        }
        requirement.requiredObjectTarget?.let { target ->
            val signedTarget = manifest.adapterContract.classMap?.targets
                ?.singleOrNull { it.targetId == target.targetId }
            if (target.targetId !in operational.targetIds || signedTarget == null) {
                add(ModelPackageIneligibility.TARGET_NOT_SUPPORTED)
            } else if (!signedTarget.exactlyMatches(target)) {
                add(ModelPackageIneligibility.TARGET_METADATA_MISMATCH)
            }
        }
        if (manifest.runtimeFamily != recipe.runtimeFamily ||
            requirement.requiredRuntimeFamily?.let { it != recipe.runtimeFamily } == true
        ) {
            add(ModelPackageIneligibility.RUNTIME_FAMILY_MISMATCH)
        }
        if (manifest.runtimeFamily !in requirement.availableRuntimeFamilies) {
            add(ModelPackageIneligibility.RUNTIME_FAMILY_UNAVAILABLE)
        }

        val device = requirement.device
        if (device.androidApi < compatibility.minAndroidApi ||
            compatibility.maxAndroidApi?.let { device.androidApi > it } == true
        ) {
            add(ModelPackageIneligibility.ANDROID_API_INCOMPATIBLE)
        }
        if (device.abi !in compatibility.supportedAbis) {
            add(ModelPackageIneligibility.ABI_INCOMPATIBLE)
        }
        if (device.marketedMemoryMb < compatibility.minimumMemoryMb) {
            add(ModelPackageIneligibility.INSUFFICIENT_MEMORY)
        }
        if (!device.availableFeatures.containsAll(compatibility.requiredFeatures)) {
            add(ModelPackageIneligibility.REQUIRED_FEATURE_MISSING)
        }

        // Commercial admission adds exact redistribution and runtime-metadata checks. Model
        // provider benchmark claims are not encoded as product accuracy promises.
        if (requirement.buildChannel in setOf(BuildChannel.COMMUNITY, BuildChannel.COMMERCIAL)) {
            if (!license.commercialUseAllowed || !license.redistributionAllowed ||
                license.sourceDisclosureRequired ||
                license.reviewEvidenceSha256?.matches(Regex("^[0-9a-f]{64}$")) != true
            ) {
                add(ModelPackageIneligibility.COMMERCIAL_LICENSE_FORBIDDEN)
            }
            if (manifest.commercialIoErrors().isNotEmpty()) {
                add(ModelPackageIneligibility.COMMERCIAL_METADATA_INCOMPLETE)
            }
        }
    })

    private fun instantMillisOrNull(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()
}

internal fun matchingOperationalModelProfile(
    operationalCapabilities: Collection<CatalogOperationalCapability>,
    modelProfileKey: String,
    capabilityId: String,
    recipeId: String,
    packageId: String,
    intentKey: String,
): CatalogOperationalCapability? = operationalCapabilities.singleOrNull { operational ->
    operational.capabilityKey == modelProfileKey &&
        operational.capabilityId == capabilityId &&
        operational.recipeId == recipeId &&
        packageId in operational.packageIds &&
        operational.matchesIntentKey(intentKey)
}

internal fun exactSignedObjectTargetMatch(
    targets: Collection<ClassMapTarget>?,
    target: TargetProfile.ObjectClass,
): Boolean = targets
    ?.singleOrNull { it.targetId == target.targetId }
    ?.exactlyMatches(target) == true

private fun ClassMapTarget.exactlyMatches(target: TargetProfile.ObjectClass): Boolean =
    targetId == target.targetId &&
        labelZhCn == target.labelZhCn &&
        labelEn == target.labelEn
