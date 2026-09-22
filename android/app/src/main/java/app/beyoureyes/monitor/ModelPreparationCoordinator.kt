package app.beyoureyes.monitor

import app.beyoureyes.core.domain.RuntimeMonitorRule
import android.app.ActivityManager
import android.content.Context
import androidx.core.content.ContextCompat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.beyoureyes.core.data.FixedHttpsTransport
import app.beyoureyes.core.data.CatalogOperationalCapability
import app.beyoureyes.core.data.CatalogRuntimeRecipe
import app.beyoureyes.core.data.MetadataFetchResult
import app.beyoureyes.core.data.ModelPackageActivationResult
import app.beyoureyes.core.data.ModelPackageArtifactSelfTest
import app.beyoureyes.core.data.ModelPackageDeliveryCoordinator
import app.beyoureyes.core.data.ModelPackageDeliveryResult
import app.beyoureyes.core.data.ModelPackageGateReport
import app.beyoureyes.core.data.ModelPackageDownloadProgress
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ModelPackageRejection
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.ModelPackageSlot
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.data.RoomMonitorStore
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.data.SignedMetadataHttpClient
import app.beyoureyes.core.data.StoredLocalTask
import app.beyoureyes.core.data.UrlConnectionFixedHttpsTransport
import app.beyoureyes.core.data.VerifiedCapabilityCatalog
import app.beyoureyes.core.data.VerifiedCatalogCache
import app.beyoureyes.core.data.VerifiedManifestCache
import app.beyoureyes.core.data.VerifiedManifestDocument
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestResult
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestRunner
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import app.beyoureyes.core.vision.ReferenceImageProvider
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.RuntimeActivationError
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.SupportedTask
import app.beyoureyes.core.vision.TargetMode
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.VerifiedModelPackage
import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import app.beyoureyes.monitor.feature.subscription.messageResource
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ModelPreparationFailure {
    CATALOG_NOT_CONFIGURED,
    BUILD_CHANNEL_DISABLED,
    CATALOG_UNAVAILABLE,
    CATALOG_REJECTED,
    CATALOG_CHANGED,
    TASK_UNAVAILABLE,
    NO_COMPATIBLE_PACKAGE,
    SIGN_IN_REQUIRED,
    SUBSCRIPTION_REQUIRED,
    SUBSCRIPTION_VERIFICATION_REQUIRED,
    PACKAGE_DOWNLOAD_FAILED,
    SELF_TEST_FAILED,
    MONITORING_ACTIVE,
    ACTIVATION_FAILED,
}

sealed interface ModelPreparationResult {
    data class Ready(
        val taskId: String,
        val pointer: ModelPackagePointer,
        val slot: ModelPackageSlot,
    ) : ModelPreparationResult {
        val packageId: String get() = pointer.identity.packageId
        val packageVersion: String get() = pointer.identity.packageVersion
    }

    data class Unavailable(
        val failure: ModelPreparationFailure,
        val userMessage: String,
        val canOpenAccount: Boolean = false,
    ) : ModelPreparationResult
}

internal sealed interface TransientReadingPreparationResult {
    data class Ready(val runtime: SamplingConfigResolution.Ready) :
        TransientReadingPreparationResult

    data class Unavailable(
        val failure: ModelPreparationFailure,
        val userMessage: String,
        val canOpenAccount: Boolean = false,
    ) : TransientReadingPreparationResult
}

internal sealed interface TransientReferencePreparationResult {
    data class Ready(val runtime: SamplingConfigResolution.Ready) :
        TransientReferencePreparationResult

    data class Unavailable(
        val failure: ModelPreparationFailure,
        val userMessage: String,
        val canOpenAccount: Boolean = false,
    ) : TransientReferencePreparationResult
}

internal sealed interface TransientObjectPreparationResult {
    data class Ready(val runtime: SamplingConfigResolution.Ready) :
        TransientObjectPreparationResult

    data class Unavailable(
        val failure: ModelPreparationFailure,
        val userMessage: String,
        val canOpenAccount: Boolean = false,
    ) : TransientObjectPreparationResult
}

sealed interface ModelPreparationProgress {
    data object Checking : ModelPreparationProgress
    data class AwaitingDownload(val request: ModelDownloadRequest) : ModelPreparationProgress
    data class UsingDownloaded(val modelName: String) : ModelPreparationProgress
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val modelName: String,
    ) : ModelPreparationProgress
    data object Verifying : ModelPreparationProgress
    data object Activating : ModelPreparationProgress
}

sealed interface ModelPreparationUiState {
    data object Idle : ModelPreparationUiState
    data object Preparing : ModelPreparationUiState
    data class Ready(val packageId: String) : ModelPreparationUiState
    data class Unavailable(val message: String) : ModelPreparationUiState
}

data class ModelPreparationDevice(
    val androidApi: Int,
    val abi: String,
    val marketedMemoryMb: Int,
    val rawTotalMemoryBytes: Long? = null,
    val availableFeatures: Set<String> = emptySet(),
) {
    init {
        require(androidApi >= 1)
        require(abi.isNotBlank())
        require(marketedMemoryMb > 0)
        require(rawTotalMemoryBytes == null || rawTotalMemoryBytes > 0)
    }

    /** Privacy-preserving compatibility fingerprint; stable across processes on this OS profile. */
    val samplingFingerprintSha256: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    appendLine("be-your-eyes-sampling-device-v1")
                    append(androidApi).append('\t')
                    append(abi).append('\t')
                    appendLine(marketedMemoryMb)
                    availableFeatures.sorted().forEach(::appendLine)
                }.toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }
}

/** Maps OS-visible physical bytes into the RAM classes used by the signed device contract. */
internal object MarketedRamClass {
    fun fromPhysicalBytes(totalBytes: Long): Int {
        require(totalBytes > 0)
        return when {
            totalBytes >= 14L * GIB -> 16_384
            totalBytes >= 10L * GIB -> 12_288
            totalBytes >= 7L * GIB -> 8_192
            totalBytes >= 5L * GIB -> 6_144
            totalBytes >= 3L * GIB -> 4_096
            else -> (totalBytes / MIB).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private const val MIB = 1_024L * 1_024L
    private const val GIB = 1_024L * MIB
}

internal object ModelPreparationCandidatePolicy {
    data class Candidate(
        val recipe: CatalogRuntimeRecipe,
        val packageId: String,
    )

    /**
     * The signed Catalog arrays are the only fallback priority. Runtime recipe/package list order,
     * network completion order and package-id lexical order never participate.
     */
    fun firstEligible(
        capabilityRecipeIds: List<String>,
        runtimeRecipes: Collection<CatalogRuntimeRecipe>,
        isEligible: (Candidate) -> Boolean,
    ): Candidate? = orderedCandidates(capabilityRecipeIds, runtimeRecipes)
        ?.firstOrNull(isEligible)

    fun allEligible(
        capabilityRecipeIds: List<String>,
        runtimeRecipes: Collection<CatalogRuntimeRecipe>,
        isEligible: (Candidate) -> Boolean,
    ): List<Candidate> = orderedCandidates(capabilityRecipeIds, runtimeRecipes)
        ?.filter(isEligible)
        .orEmpty()

    private fun orderedCandidates(
        capabilityRecipeIds: List<String>,
        runtimeRecipes: Collection<CatalogRuntimeRecipe>,
    ): List<Candidate>? {
        val recipesById = runtimeRecipes.associateBy(CatalogRuntimeRecipe::recipeId)
        if (recipesById.size != runtimeRecipes.size) return null
        val orderedRecipes = capabilityRecipeIds.map { recipesById[it] ?: return null }
        return orderedRecipes.flatMap { recipe ->
            recipe.candidatePackageIds.map { packageId -> Candidate(recipe, packageId) }
        }
    }
}

internal data class ManualObjectModelRoute(
    val modelProfileKey: String,
    val recipeId: String,
    val intentKey: String,
    val candidatePackageIds: Set<String>,
)

internal sealed interface ManualObjectModelRouteResolution {
    data class Resolved(val route: ManualObjectModelRoute) : ManualObjectModelRouteResolution
    data object NotFound : ManualObjectModelRouteResolution
    data object Ambiguous : ManualObjectModelRouteResolution
}

/**
 * Resolves a manual object target only from signed Catalog routing coverage. A target covered by
 * more than one active profile is deliberately ambiguous; signed array order is not a model guess.
 */
internal fun resolveUniqueManualObjectModelRoute(
    operationalCapabilities: Collection<CatalogOperationalCapability>,
    runtimeRecipes: Collection<CatalogRuntimeRecipe>,
    capabilityRecipeIds: Collection<String>,
    activePackageIds: Set<String>,
    targetId: String,
    requiredPackageId: String? = null,
): ManualObjectModelRouteResolution {
    val recipesById = runtimeRecipes.associateBy(CatalogRuntimeRecipe::recipeId)
    if (recipesById.size != runtimeRecipes.size) return ManualObjectModelRouteResolution.NotFound
    val allowedRecipeIds = capabilityRecipeIds.toSet()
    val matches = operationalCapabilities.mapNotNull { operational ->
        if (operational.capabilityId != REFERENCE_CAPABILITY_ID ||
            targetId !in operational.targetIds
        ) {
            return@mapNotNull null
        }
        val recipeId = operational.recipeId
        val recipe = recipesById[recipeId] ?: return@mapNotNull null
        if (recipeId !in allowedRecipeIds ||
            recipe.capabilityId != REFERENCE_CAPABILITY_ID ||
            recipe.runtimeFamily != RecipeFamily.OBJECT_DETECTION_V1 ||
            TargetMode.OBJECT_CLASS !in recipe.promptModes
        ) {
            return@mapNotNull null
        }
        val candidatePackageIds = operational.packageIds.filterTo(linkedSetOf()) { packageId ->
            packageId in activePackageIds &&
                packageId in recipe.candidatePackageIds &&
                (requiredPackageId == null || packageId == requiredPackageId)
        }
        if (candidatePackageIds.isEmpty()) return@mapNotNull null
        val intentKey = operational.intentPatterns.mapNotNull { pattern ->
            when {
                pattern.endsWith(".*") -> pattern.removeSuffix("*") + targetId
                pattern.endsWith(".$targetId") -> pattern
                else -> null
            }
        }.distinct().singleOrNull() ?: return@mapNotNull null
        if (!operational.matchesIntentKey(intentKey)) return@mapNotNull null
        ManualObjectModelRoute(
            modelProfileKey = operational.capabilityKey,
            recipeId = recipeId,
            intentKey = intentKey,
            candidatePackageIds = candidatePackageIds,
        )
    }
    return when (matches.size) {
        0 -> ManualObjectModelRouteResolution.NotFound
        1 -> ManualObjectModelRouteResolution.Resolved(matches.single())
        else -> ManualObjectModelRouteResolution.Ambiguous
    }
}

private data class ResolvedSignedModelRoute(
    val modelDisplayName: String,
    val catalogVersion: String,
    val capabilityId: String,
    val modelProfileKey: String,
    val recipeId: String,
    val intentKey: String,
)

private sealed interface ManifestSelection {
    data class Selected(
        val document: VerifiedManifestDocument,
        val route: ResolvedSignedModelRoute,
    ) : ManifestSelection
    data object NotFound : ManifestSelection
    data object Ambiguous : ManifestSelection
    data object Rejected : ManifestSelection
}

internal fun modelSelfTestFailureResource(errors: Set<RuntimeActivationError>): Int =
    R.string.model_self_test_failed

/**
 * Minimum, package-neutral activation smoke test selected by the signed Manifest family.
 *
 * The generated frame is deliberately not accuracy evidence. Reading packages cannot use this
 * content-neutral check; they must pass their signed, artifact-bound known-answer vector instead.
 */
internal fun minimumArtifactSmokeTestPassed(
    family: RecipeFamily,
    result: PipelineResult,
): Boolean {
    if (!result.adapterCompleted) return false
    return when (family) {
        // A generated smoke frame has no ground-truth object. Requiring a detection here would
        // reject a valid package merely because the frame is a negative scene; object quality is
        // owned by the frozen COCO/physical replay gates. The adapter completion and tensor
        // contract still prove that the package can execute safely.
        RecipeFamily.OBJECT_DETECTION_V1 -> true
        RecipeFamily.SIMILARITY_MATCH_V1 ->
            result.observation is Observation.State ||
                (result.observation as? Observation.Unavailable)?.reason == UnavailableReason.LOW_QUALITY
        RecipeFamily.READING_PIPELINE_V1 -> false
    }
}

internal data class ModelPreparationTarget(
    val taskRevision: Long,
    val capabilityId: String,
    val requiredTask: SupportedTask,
    val requiredRuleType: String = "presence_duration",
    val requiredRuntimeFamily: RecipeFamily? = null,
    val targetProfile: TargetProfile?,
    val referenceImageProvider: ReferenceImageProvider?,
    val selfTestFrame: SourceFrame,
    val requiredModelProfileKey: String? = null,
    val requiredPackageId: String? = null,
    val requiredIntentKey: String? = null,
)

internal fun evaluatePreparedModelPackageEligibility(
    candidate: ModelPackageEligibilityCandidate,
    requirement: ModelPackageEligibilityRequirement,
): ModelPackageEligibilityResult = ModelPackageEligibilityEvaluator.evaluate(candidate, requirement)

internal fun preparedModelPackageEligibilityRequirement(
    buildChannel: BuildChannel,
    device: ModelPreparationDevice,
    target: ModelPreparationTarget,
    nowEpochMillis: Long,
): ModelPackageEligibilityRequirement = ModelPackageEligibilityRequirement(
    buildChannel = buildChannel,
    device = device,
    capabilityId = target.capabilityId,
    requiredTask = target.requiredTask,
    targetMode = target.targetProfile?.mode ?: TargetMode.NONE,
    requiredRuleType = target.requiredRuleType,
    requiredRuntimeFamily = target.requiredRuntimeFamily,
    requiredModelProfileKey = target.requiredModelProfileKey ?: target.defaultModelProfileKey(),
    requiredPackageId = target.requiredPackageId,
    requiredIntentKey = target.requiredIntentKey ?: target.defaultIntentKey(),
    requiredObjectTarget = target.targetProfile as? TargetProfile.ObjectClass,
    nowEpochMillis = nowEpochMillis,
)

private fun ModelPreparationTarget.defaultModelProfileKey(): String = when (
    requiredRuntimeFamily
) {
    RecipeFamily.OBJECT_DETECTION_V1 -> COMMON_OBJECT_MODEL_PROFILE_KEY
    RecipeFamily.SIMILARITY_MATCH_V1 -> REFERENCE_MODEL_PROFILE_KEY
    RecipeFamily.READING_PIPELINE_V1 -> READING_MODEL_PROFILE_KEY
    null -> when (targetProfile?.mode ?: TargetMode.NONE) {
        TargetMode.OBJECT_CLASS -> COMMON_OBJECT_MODEL_PROFILE_KEY
        TargetMode.REFERENCE_IMAGES -> REFERENCE_MODEL_PROFILE_KEY
        TargetMode.NONE -> READING_MODEL_PROFILE_KEY
    }
}

private fun ModelPreparationTarget.defaultIntentKey(): String = when (
    targetProfile?.mode ?: TargetMode.NONE
) {
    TargetMode.OBJECT_CLASS -> "object.common.${checkNotNull(targetProfile).targetId}"
    TargetMode.REFERENCE_IMAGES -> "visual.reference.user_target"
    TargetMode.NONE -> "reading.numeric.display"
}

internal fun interface ModelPreparationTargetResolver {
    suspend fun resolve(taskId: String): ModelPreparationTarget?
}

internal fun interface ModelPreparationTaskBinder {
    suspend fun bind(config: ResolvedSamplingConfig): Boolean
}

private fun failClosedProductAccess(): ProductAccessDecision =
    ProductAccessDecision.VERIFICATION_REQUIRED

internal fun productAccessPreparationRejection(
    decision: ProductAccessDecision,
    userMessage: () -> String,
): ModelPreparationResult.Unavailable? = when (decision) {
    ProductAccessDecision.GRANTED -> null
    ProductAccessDecision.SIGN_IN_REQUIRED -> ModelPreparationResult.Unavailable(
        ModelPreparationFailure.SIGN_IN_REQUIRED,
        userMessage(),
        canOpenAccount = true,
    )
    ProductAccessDecision.PRO_REQUIRED -> ModelPreparationResult.Unavailable(
        ModelPreparationFailure.SUBSCRIPTION_REQUIRED,
        userMessage(),
        canOpenAccount = true,
    )
    ProductAccessDecision.VERIFICATION_REQUIRED -> ModelPreparationResult.Unavailable(
        ModelPreparationFailure.SUBSCRIPTION_VERIFICATION_REQUIRED,
        userMessage(),
        canOpenAccount = true,
    )
}

/**
 * One product path for signed Catalog fetch, deterministic compatible-package choice, download,
 * exact artifact self-test, immutable staging, and explicit activation.
 *
 * All package behavior comes from the signed Catalog/Manifest and finite runtime registries. No
 * package ID, vendor, model filename, or artifact URL is special-cased here.
 */
internal class ModelPreparationCoordinator(
    private val appContext: Context,
    private val filesDir: File,
    private val catalogUrl: String,
    private val buildChannel: BuildChannel,
    private val device: ModelPreparationDevice,
    private val targetResolver: ModelPreparationTargetResolver,
    private val taskBinder: ModelPreparationTaskBinder,
    transport: FixedHttpsTransport = UrlConnectionFixedHttpsTransport(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val networkAvailable: () -> Boolean = { true },
    private val productAccess: () -> ProductAccessDecision = ::failClosedProductAccess,
) {
    private val metadataClient = SignedMetadataHttpClient(transport)
    private val store = ModelPackageStores.open(filesDir)
    private val catalogCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VerifiedCatalogCache(filesDir, catalogUrl)
    }
    private val delivery = ModelPackageDeliveryCoordinator(
        store = store,
        downloadDirectory = filesDir.resolve(ModelPackageStores.DOWNLOAD_DIRECTORY_NAME),
        transport = transport,
    )

    suspend fun prepareTask(
        taskId: String,
        onProgress: (ModelPreparationProgress) -> Unit = {},
    ): ModelPreparationResult = prepareResolvedTask(
        taskId = taskId,
        targetOverride = null,
        taskBinderOverride = null,
        onProgress = onProgress,
    )

    /**
     * Prepares a reading package without creating or binding a Room task. A routed setup pins the
     * package from its verified plan; a manual setup selects the first eligible signed-Catalog
     * package. The process-memory UUID becomes final only after camera and rule confirmation.
     */
    suspend fun prepareTransientReading(
        taskId: String,
        onProgress: (ModelPreparationProgress) -> Unit = {},
        requiredModelProfileKey: String? = null,
        requiredPackageId: String? = null,
        requiredIntentKey: String? = null,
    ): TransientReadingPreparationResult {
        var capturedConfig: ResolvedSamplingConfig? = null
        val result = prepareResolvedTask(
            taskId = taskId,
            targetOverride = ModelPreparationTarget(
                taskRevision = 1,
                capabilityId = READING_CAPABILITY_ID,
                requiredTask = SupportedTask.STRUCTURED_READING,
                requiredRuleType = "reading_threshold",
                requiredRuntimeFamily = RecipeFamily.READING_PIPELINE_V1,
                targetProfile = null,
                referenceImageProvider = null,
                selfTestFrame = syntheticSelfTestFrame(),
                requiredModelProfileKey = requiredModelProfileKey,
                requiredPackageId = requiredPackageId,
                requiredIntentKey = requiredIntentKey,
            ),
            taskBinderOverride = ModelPreparationTaskBinder { config ->
                capturedConfig = config
                true
            },
            onProgress = onProgress,
        )
        return when (result) {
            is ModelPreparationResult.Ready -> {
                val config = checkNotNull(capturedConfig)
                check(config.packagePointer == result.pointer)
                TransientReadingPreparationResult.Ready(
                    SamplingConfigResolution.Ready(
                        config = config,
                        fieldValidationSpec = null,
                        readingPreviewSpec = ReadingPreviewSpec(
                            taskId = config.taskId,
                            taskRevision = config.taskRevision,
                            packagePointer = config.packagePointer,
                        ) { identity ->
                            ReadingPreviewCoordinator.openAppPrivate(filesDir, identity)
                        },
                        modelDisplayName = ContextCompat.getContextForLanguage(appContext)
                            .getString(R.string.model_display_reading),
                    ),
                )
            }
            is ModelPreparationResult.Unavailable -> TransientReadingPreparationResult.Unavailable(
                result.failure,
                result.userMessage,
                result.canOpenAccount,
            )
        }
    }

    /**
     * Validates the exact routed reference package and immutable staged material without binding a
     * Room task. The returned field validator reads only the staged private generation; promotion
     * remains the camera confirmation owner's responsibility.
     */
    suspend fun prepareTransientReference(
        stagedTask: StoredLocalTask,
        onProgress: (ModelPreparationProgress) -> Unit = {},
        requiredModelProfileKey: String? = null,
        requiredPackageId: String? = null,
        requiredIntentKey: String? = null,
    ): TransientReferencePreparationResult {
        val restored = runCatching { StoredTaskRuntimeResolver(filesDir).resolve(stagedTask) }
            .getOrElse {
                return TransientReferencePreparationResult.Unavailable(
                    ModelPreparationFailure.TASK_UNAVAILABLE,
                    ContextCompat.getContextForLanguage(appContext)
                        .getString(R.string.model_reference_unreadable),
                )
            }
        val profile = restored.targetProfile as? TargetProfile.ReferenceImages
            ?: return TransientReferencePreparationResult.Unavailable(
                ModelPreparationFailure.TASK_UNAVAILABLE,
                ContextCompat.getContextForLanguage(appContext)
                    .getString(R.string.model_reference_unreadable),
            )
        val asset = restored.referenceImageProvider.load(profile.images.first())
            ?: return TransientReferencePreparationResult.Unavailable(
                ModelPreparationFailure.TASK_UNAVAILABLE,
                ContextCompat.getContextForLanguage(appContext)
                    .getString(R.string.model_reference_unreadable),
            )
        var capturedConfig: ResolvedSamplingConfig? = null
        val result = prepareResolvedTask(
            taskId = stagedTask.taskId,
            targetOverride = ModelPreparationTarget(
                taskRevision = stagedTask.revision,
                capabilityId = REFERENCE_CAPABILITY_ID,
                requiredTask = SupportedTask.VISUAL_TARGET,
                requiredRuleType = "presence_duration",
                requiredRuntimeFamily = RecipeFamily.SIMILARITY_MATCH_V1,
                targetProfile = profile,
                referenceImageProvider = restored.referenceImageProvider,
                selfTestFrame = SourceFrame(
                    sourceSequence = 0,
                    monotonicTimeMillis = 0,
                    capturedAtEpochMillis = null,
                    width = asset.width,
                    height = asset.height,
                    rotationDegrees = 0,
                    cropRect = PixelRect(0, 0, asset.width, asset.height),
                    pixels = FramePixels.Rgb888(asset.rgb888, asset.width * 3),
                ),
                requiredModelProfileKey = requiredModelProfileKey,
                requiredPackageId = requiredPackageId,
                requiredIntentKey = requiredIntentKey,
            ),
            taskBinderOverride = ModelPreparationTaskBinder { config ->
                capturedConfig = config
                true
            },
            onProgress = onProgress,
        )
        return when (result) {
            is ModelPreparationResult.Ready -> {
                val config = checkNotNull(capturedConfig)
                check(config.packagePointer == result.pointer)
                val bound = restored.copy(runtimePackagePointer = config.packagePointer)
                TransientReferencePreparationResult.Ready(
                    SamplingConfigResolution.Ready(
                        config = config,
                        fieldValidationSpec = SimilarityFieldValidationSpec.production(
                            filesDir,
                            bound,
                            profile,
                        ),
                        readingPreviewSpec = null,
                        modelDisplayName = ContextCompat.getContextForLanguage(appContext)
                            .getString(R.string.model_display_reference),
                    ),
                )
            }
            is ModelPreparationResult.Unavailable ->
                TransientReferencePreparationResult.Unavailable(
                    result.failure,
                    result.userMessage,
                    result.canOpenAccount,
                )
        }
    }

    suspend fun prepareTransientObject(
        taskId: String,
        targetProfile: TargetProfile.ObjectClass,
        onProgress: (ModelPreparationProgress) -> Unit = {},
        requiredModelProfileKey: String? = null,
        requiredPackageId: String? = null,
        requiredIntentKey: String? = null,
    ): TransientObjectPreparationResult {
        val assistantBinding = listOf(
            requiredModelProfileKey,
            requiredPackageId,
            requiredIntentKey,
        )
        if (assistantBinding.any { it != null } && assistantBinding.any { it == null }) {
            return TransientObjectPreparationResult.Unavailable(
                ModelPreparationFailure.NO_COMPATIBLE_PACKAGE,
                ContextCompat.getContextForLanguage(appContext)
                    .getString(R.string.model_object_config_incomplete),
            )
        }
        var capturedConfig: ResolvedSamplingConfig? = null
        val result = prepareResolvedTask(
            taskId = taskId,
            targetOverride = ModelPreparationTarget(
                taskRevision = 1,
                capabilityId = REFERENCE_CAPABILITY_ID,
                requiredTask = SupportedTask.VISUAL_TARGET,
                requiredRuleType = "presence_duration",
                requiredRuntimeFamily = RecipeFamily.OBJECT_DETECTION_V1,
                targetProfile = targetProfile,
                referenceImageProvider = null,
                selfTestFrame = syntheticSelfTestFrame(),
                requiredModelProfileKey = requiredModelProfileKey,
                requiredPackageId = requiredPackageId,
                requiredIntentKey = requiredIntentKey,
            ),
            taskBinderOverride = ModelPreparationTaskBinder { config ->
                capturedConfig = config
                true
            },
            onProgress = onProgress,
        )
        return when (result) {
            is ModelPreparationResult.Ready -> {
                val config = checkNotNull(capturedConfig)
                check(config.packagePointer == result.pointer)
                TransientObjectPreparationResult.Ready(
                    SamplingConfigResolution.Ready(
                        config = config,
                        fieldValidationSpec = SimilarityFieldValidationSpec.productionObject(
                            filesDir = filesDir,
                            taskId = taskId,
                            taskRevision = 1,
                            packagePointer = config.packagePointer,
                            targetProfile = targetProfile,
                        ),
                        readingPreviewSpec = null,
                        modelDisplayName = ContextCompat.getContextForLanguage(appContext)
                            .getString(R.string.model_display_object),
                    ),
                )
            }
            is ModelPreparationResult.Unavailable -> TransientObjectPreparationResult.Unavailable(
                result.failure,
                result.userMessage,
                result.canOpenAccount,
            )
        }
    }

    private suspend fun prepareResolvedTask(
        taskId: String,
        targetOverride: ModelPreparationTarget?,
        taskBinderOverride: ModelPreparationTaskBinder?,
        onProgress: (ModelPreparationProgress) -> Unit,
    ): ModelPreparationResult = withContext(Dispatchers.IO) {
        runCatching { onProgress(ModelPreparationProgress.Checking) }
        if (catalogUrl.isBlank()) {
            return@withContext unavailable(
                ModelPreparationFailure.CATALOG_NOT_CONFIGURED,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_service_not_configured),
            )
        }
        if (buildChannel == BuildChannel.DEVELOPMENT_NO_MODEL) {
            return@withContext unavailable(
                ModelPreparationFailure.BUILD_CHANNEL_DISABLED,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_ui_only_build),
            )
        }
        val target = targetOverride ?: runCatching { targetResolver.resolve(taskId) }.getOrNull()
            ?: return@withContext unavailable(
                ModelPreparationFailure.TASK_UNAVAILABLE,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_reference_unreadable),
            )
        val now = nowEpochMillis()
        val shouldUseNetwork = runCatching(networkAvailable).getOrDefault(false)
        val bundledCatalog = CommunityModelMetadata.read(appContext, catalogUrl)
        val fetchedCatalog = bundledCatalog?.let { MetadataFetchResult.Fetched(it) }
            ?: if (shouldUseNetwork) metadataClient.fetchCatalog(catalogUrl) else null
        val catalogBytes = when (fetchedCatalog) {
            is MetadataFetchResult.Fetched -> fetchedCatalog.bytes
            is MetadataFetchResult.Rejected -> return@withContext unavailable(
                ModelPreparationFailure.CATALOG_REJECTED,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_security_check_failed),
            )
            is MetadataFetchResult.Retryable,
            null,
            -> catalogCache.readBytesOrNull() ?: return@withContext unavailable(
                ModelPreparationFailure.CATALOG_UNAVAILABLE,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_download_unavailable),
            )
        }
        val catalog = try {
            SignedMetadataCodec.decodeAndVerifyCatalog(
                documentBytes = catalogBytes,
                nowEpochMillis = now,
                allowInstalledCommunity = buildChannel == BuildChannel.COMMUNITY,
            )
        } catch (_: Exception) {
            return@withContext unavailable(
                ModelPreparationFailure.CATALOG_REJECTED,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_security_check_retry),
            )
        }
        if (fetchedCatalog is MetadataFetchResult.Fetched) catalogCache.write(catalog)
        if (catalog.catalog.buildChannel != buildChannel) {
            return@withContext unavailable(
                ModelPreparationFailure.CATALOG_REJECTED,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_build_mismatch),
            )
        }
        val productAccessDecision = productAccess()
        productAccessPreparationRejection(productAccessDecision) {
            ContextCompat.getContextForLanguage(appContext).getString(productAccessDecision.messageResource())
        }?.let { return@withContext it }
        val manifestSelection = when (
            val selection = selectManifest(catalog, target, now, allowNetwork = shouldUseNetwork)
        ) {
            is ManifestSelection.Selected -> selection
            ManifestSelection.Ambiguous -> return@withContext unavailable(
                ModelPreparationFailure.NO_COMPATIBLE_PACKAGE,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_ambiguous_target),
            )
            ManifestSelection.NotFound -> return@withContext unavailable(
                ModelPreparationFailure.NO_COMPATIBLE_PACKAGE,
                ContextCompat.getContextForLanguage(appContext).getString(if (catalog.installedCommunityOnly) R.string.community_catalog_expired else R.string.model_device_unsupported),
            )
            ManifestSelection.Rejected -> return@withContext unavailable(
                ModelPreparationFailure.CATALOG_REJECTED,
                ContextCompat.getContextForLanguage(appContext).getString(R.string.model_security_check_retry),
            )
        }
        val selected = manifestSelection.document
        val descriptor = selected.toStoreDescriptor()
        val modelName = manifestSelection.route.modelDisplayName
        val requiresDownload = when (
            val installed = store.acquireRuntimeLease(
                ModelPackagePointer(descriptor.identity, descriptor.canonicalManifestSha256), now,
            )
        ) {
            is ModelPackageRuntimeLeaseResult.Acquired -> installed.lease.use { false }
            is ModelPackageRuntimeLeaseResult.Rejected ->
                installed.reasons == setOf(ModelPackageRejection.PACKAGE_NOT_INSTALLED)
        }
        if (requiresDownload) {
            val request = ModelDownloadRequest(
                modelName = modelName,
                purpose = ContextCompat.getContextForLanguage(appContext).getString(when (target.targetProfile) {
                    is TargetProfile.ReferenceImages -> R.string.model_download_reference_purpose
                    is TargetProfile.ObjectClass -> R.string.model_download_object_purpose
                    else -> R.string.model_download_reading_purpose
                }),
                totalBytes = selected.manifest.artifacts.sumOf { it.sizeBytes },
                meteredNetwork = appContext.getSystemService(ConnectivityManager::class.java)
                    ?.isActiveNetworkMetered == true,
            )
            onProgress(ModelPreparationProgress.AwaitingDownload(request))
            request.awaitConfirmation()
            // Consent applies only to this exact signed package, and does not extend access.
            val accessAfterConfirmation = productAccess()
            productAccessPreparationRejection(accessAfterConfirmation) {
                ContextCompat.getContextForLanguage(appContext).getString(accessAfterConfirmation.messageResource())
            }?.let { return@withContext it }
        } else {
            onProgress(ModelPreparationProgress.UsingDownloaded(modelName))
        }
        val preparationContext = currentCoroutineContext()
        preparationContext.ensureActive()
        val preparationNow = nowEpochMillis()
        val gateReport = ModelPackageGateReport(
            reportId = "model-preparation-${catalog.documentSha256}",
            evidenceRef = "signed-catalog/${catalog.documentSha256}",
            packageDescriptorSha256 = descriptor.descriptorSha256,
            catalogEntryActive = selected.catalogEntry.active,
            catalogSignatureValid = true,
            catalogManifestHashValid = true,
            manifestSignatureValid = true,
            licenseGatePassed = true,
            metadataCompatible = true,
            deviceCompatible = true,
            selfTestPassed = false,
            validatedAtEpochMillis = preparationNow,
            catalogFreshUntilEpochMillis = selected.catalogFreshUntilEpochMillis,
            runValidUntilEpochMillis = selected.licenseRunValidUntilEpochMillis,
        )
        var selfTestActivationErrors = emptySet<RuntimeActivationError>()
        val staged = if (catalog.installedCommunityOnly) {
            val pointer = ModelPackagePointer(descriptor.identity, descriptor.canonicalManifestSha256)
            when (val installed = store.acquireRuntimeLease(pointer, preparationNow)) {
                is ModelPackageRuntimeLeaseResult.Acquired -> installed.lease.use { lease ->
                    if (lease.descriptor == descriptor && lease.canonicalManifest.copyBytes()
                            .contentEquals(selected.copyDocumentBytes())) {
                        ModelPackageDeliveryResult.Staged(pointer, alreadyPresent = true)
                    } else ModelPackageDeliveryResult.Rejected(app.beyoureyes.core.data.ModelDeliveryFailure.METADATA_GATE_INVALID)
                }
                is ModelPackageRuntimeLeaseResult.Rejected -> ModelPackageDeliveryResult.Rejected(
                    app.beyoureyes.core.data.ModelDeliveryFailure.STORE_REJECTED, installed.reasons)
            }
        } else delivery.stageVerifiedPackage(
            verifiedManifest = selected,
            gateReport = gateReport,
            nowEpochMillis = preparationNow,
            artifactSelfTest = ModelPackageArtifactSelfTest { manifest, files ->
                runArtifactSelfTest(manifest, files, target).also { outcome ->
                    selfTestActivationErrors = outcome.activationErrors
                }.passed
            },
            onDownloadProgress = { progress ->
                preparationContext.ensureActive()
                onProgress(progress.toPreparationProgress(modelName))
            },
        )
        preparationContext.ensureActive()
        if (staged !is ModelPackageDeliveryResult.Staged) {
            val selfTestFailed = staged is ModelPackageDeliveryResult.Rejected &&
                staged.reason == app.beyoureyes.core.data.ModelDeliveryFailure.SELF_TEST_FAILED
            val monitoringActive = staged.isMonitoringActiveRejection()
            return@withContext unavailable(
                if (monitoringActive) {
                    ModelPreparationFailure.MONITORING_ACTIVE
                } else if (selfTestFailed) {
                    ModelPreparationFailure.SELF_TEST_FAILED
                } else {
                    ModelPreparationFailure.PACKAGE_DOWNLOAD_FAILED
                },
                if (monitoringActive) {
                    ContextCompat.getContextForLanguage(appContext).getString(R.string.model_other_monitor_active)
                } else if (selfTestFailed) {
                    ContextCompat.getContextForLanguage(appContext).getString(modelSelfTestFailureResource(selfTestActivationErrors))
                } else {
                    ContextCompat.getContextForLanguage(appContext).getString(R.string.model_not_ready_retry)
                },
            )
        }
        runCatching { onProgress(ModelPreparationProgress.Activating) }
        val slot = ModelPackageSlot.forCapability(
            target.capabilityId,
            (target.targetProfile?.mode ?: TargetMode.NONE).wireValue,
        )
        val samplingConfig = runCatching {
            ResolvedSamplingConfig.fromSignedManifestDefault(
                taskId = taskId,
                taskRevision = target.taskRevision,
                catalogVersion = manifestSelection.route.catalogVersion,
                capabilityId = manifestSelection.route.capabilityId,
                modelProfileKey = manifestSelection.route.modelProfileKey,
                recipeId = manifestSelection.route.recipeId,
                intentKey = manifestSelection.route.intentKey,
                packagePointer = staged.pointer,
                artifactIdentitySha256 = descriptor.descriptorSha256,
                deviceFingerprintSha256 = device.samplingFingerprintSha256,
                samplingPolicy = selected.manifest.parameterProfile.samplingPolicy,
            )
        }.getOrNull() ?: return@withContext unavailable(
            ModelPreparationFailure.ACTIVATION_FAILED,
            ContextCompat.getContextForLanguage(appContext).getString(R.string.model_invalid_config),
        )
        return@withContext RuntimePackageBindingMutex.mutex.withLock {
            val activationBefore = runCatching { store.activationState(slot) }.getOrNull()
                ?: return@withLock unavailable(
                    ModelPreparationFailure.ACTIVATION_FAILED,
                    ContextCompat.getContextForLanguage(appContext).getString(R.string.model_activation_failed),
                )
            when (
                val activation = delivery.activateStagedPackage(
                    slot,
                    staged.pointer,
                    nowEpochMillis(),
                )
            ) {
                is ModelPackageActivationResult.Activated -> {
                    val bound = try {
                        (taskBinderOverride ?: taskBinder).bind(samplingConfig)
                    } catch (_: Exception) {
                        false
                    }
                    if (!bound) {
                        if (activation.changed) {
                            store.restoreActivationStateAfterFailedBinding(
                                slot = slot,
                                expectedActivatedState = activation.state,
                                stateToRestore = activationBefore,
                            )
                        }
                        unavailable(
                            ModelPreparationFailure.TASK_UNAVAILABLE,
                            ContextCompat.getContextForLanguage(appContext).getString(R.string.model_task_changed),
                        )
                    } else {
                        ModelPreparationResult.Ready(taskId, staged.pointer, slot)
                    }
                }
                is ModelPackageActivationResult.Rejected -> unavailable(
                    ModelPreparationFailure.ACTIVATION_FAILED,
                    ContextCompat.getContextForLanguage(appContext).getString(R.string.model_activation_other_monitor),
                )
            }
        }
    }

    private fun ModelPackageDownloadProgress.toPreparationProgress(modelName: String): ModelPreparationProgress =
        if (downloadedBytes < totalBytes) {
            ModelPreparationProgress.Downloading(downloadedBytes, totalBytes, modelName)
        } else {
            ModelPreparationProgress.Verifying
        }

    private fun selectManifest(
        catalog: VerifiedCapabilityCatalog,
        target: ModelPreparationTarget,
        now: Long,
        allowNetwork: Boolean,
    ): ManifestSelection {
        val capability = catalog.catalog.capabilities.singleOrNull {
            it.capabilityId == target.capabilityId
        } ?: return ManifestSelection.NotFound
        val objectTarget = target.targetProfile as? TargetProfile.ObjectClass
        val hasExplicitObjectRoute = target.requiredModelProfileKey != null ||
            target.requiredIntentKey != null
        val manualObjectSelection = objectTarget != null && !hasExplicitObjectRoute
        val routedTarget = when {
            objectTarget == null -> target
            hasExplicitObjectRoute -> {
                if (target.requiredModelProfileKey == null ||
                    target.requiredPackageId == null ||
                    target.requiredIntentKey == null
                ) {
                    return ManifestSelection.NotFound
                }
                target
            }
            else -> when (
                val resolution = resolveUniqueManualObjectModelRoute(
                    operationalCapabilities = catalog.catalog.operationalCapabilities,
                    runtimeRecipes = catalog.catalog.runtimeRecipes,
                    capabilityRecipeIds = capability.recipeIds,
                    activePackageIds = catalog.catalog.packages
                        .filterTo(linkedSetOf()) { it.active }
                        .mapTo(linkedSetOf()) { it.packageId },
                    targetId = objectTarget.targetId,
                    requiredPackageId = target.requiredPackageId,
                )
            ) {
                ManualObjectModelRouteResolution.Ambiguous -> return ManifestSelection.Ambiguous
                ManualObjectModelRouteResolution.NotFound -> return ManifestSelection.NotFound
                is ManualObjectModelRouteResolution.Resolved -> target.copy(
                    requiredModelProfileKey = resolution.route.modelProfileKey,
                    requiredIntentKey = resolution.route.intentKey,
                )
            }
        }
        val verifiedByPackageId = mutableMapOf<String, VerifiedManifestDocument?>()
        val routeByCandidate = mutableMapOf<ModelPreparationCandidatePolicy.Candidate, ResolvedSignedModelRoute>()
        var manifestMetadataRejected = false
        val isEligible: (ModelPreparationCandidatePolicy.Candidate) -> Boolean = eligibility@ { candidate ->
            if (manifestMetadataRejected) return@eligibility false
            if (routedTarget.requiredPackageId?.let { it != candidate.packageId } == true) {
                return@eligibility false
            }
            val operationalCapability = matchingOperationalModelProfile(
                operationalCapabilities = catalog.catalog.operationalCapabilities,
                modelProfileKey = routedTarget.requiredModelProfileKey
                    ?: routedTarget.defaultModelProfileKey(),
                capabilityId = routedTarget.capabilityId,
                recipeId = candidate.recipe.recipeId,
                packageId = candidate.packageId,
                intentKey = routedTarget.requiredIntentKey ?: routedTarget.defaultIntentKey(),
            ) ?: return@eligibility false
            val verified = if (verifiedByPackageId.containsKey(candidate.packageId)) {
                verifiedByPackageId[candidate.packageId]
            } else {
                val entry = catalog.activePackage(candidate.packageId)
                val document = entry?.let { activeEntry ->
                    val bundled = CommunityModelMetadata.read(appContext, activeEntry.manifestUrl)
                    val online = if (!catalog.installedCommunityOnly && (allowNetwork || bundled != null)) {
                        when (val fetched = bundled?.let { MetadataFetchResult.Fetched(it) }
                            ?: metadataClient.fetchManifest(activeEntry)) {
                            is MetadataFetchResult.Fetched -> try {
                                SignedMetadataCodec.decodeAndVerifyManifest(
                                    documentBytes = fetched.bytes,
                                    catalog = catalog,
                                    packageId = candidate.packageId,
                                    nowEpochMillis = now,
                                ).also(::cacheVerifiedManifest)
                            } catch (_: Throwable) {
                                manifestMetadataRejected = true
                                null
                            }
                            is MetadataFetchResult.Retryable -> null
                            is MetadataFetchResult.Rejected -> {
                                manifestMetadataRejected = true
                                null
                            }
                        }
                    } else {
                        null
                    }
                    if (manifestMetadataRejected) {
                        null
                    } else {
                        online ?: storedManifest(
                            catalog = catalog,
                            entry = activeEntry,
                            target = routedTarget,
                            now = now,
                            onRejected = { manifestMetadataRejected = true },
                        )
                    }
                }
                verifiedByPackageId[candidate.packageId] = document
                document
            } ?: return@eligibility false
            val eligible = evaluatePreparedModelPackageEligibility(
                candidate = ModelPackageEligibilityCandidate.fromVerified(
                    catalog = catalog,
                    document = verified,
                    capability = capability,
                    recipe = candidate.recipe,
                    operationalCapability = operationalCapability,
                ),
                requirement = preparedModelPackageEligibilityRequirement(
                    buildChannel = buildChannel,
                    device = device,
                    target = routedTarget,
                    nowEpochMillis = now,
                ),
            ).eligible
            if (eligible) {
                routeByCandidate[candidate] = ResolvedSignedModelRoute(
                    modelDisplayName = operationalCapability.modelCard.modelName,
                    catalogVersion = catalog.catalog.catalogVersion,
                    capabilityId = operationalCapability.capabilityId,
                    modelProfileKey = operationalCapability.capabilityKey,
                    recipeId = candidate.recipe.recipeId,
                    intentKey = routedTarget.requiredIntentKey ?: routedTarget.defaultIntentKey(),
                )
            }
            eligible
        }
        if (manualObjectSelection) {
            val matches = ModelPreparationCandidatePolicy.allEligible(
                capabilityRecipeIds = capability.recipeIds,
                runtimeRecipes = catalog.catalog.runtimeRecipes,
                isEligible = isEligible,
            )
            if (manifestMetadataRejected) return ManifestSelection.Rejected
            return when (matches.size) {
                0 -> ManifestSelection.NotFound
                1 -> ManifestSelection.Selected(
                    checkNotNull(verifiedByPackageId[matches.single().packageId]),
                    checkNotNull(routeByCandidate[matches.single()]),
                )
                else -> ManifestSelection.Ambiguous
            }
        }
        val selected = ModelPreparationCandidatePolicy.firstEligible(
            capabilityRecipeIds = capability.recipeIds,
            runtimeRecipes = catalog.catalog.runtimeRecipes,
            isEligible = isEligible,
        )
        if (manifestMetadataRejected) return ManifestSelection.Rejected
        selected ?: return ManifestSelection.NotFound
        return ManifestSelection.Selected(
            checkNotNull(verifiedByPackageId[selected.packageId]),
            checkNotNull(routeByCandidate[selected]),
        )
    }

    private fun storedManifest(
        catalog: VerifiedCapabilityCatalog,
        entry: app.beyoureyes.core.data.CatalogPackageEntry,
        target: ModelPreparationTarget,
        now: Long,
        onRejected: () -> Unit,
    ): VerifiedManifestDocument? {
        val slot = ModelPackageSlot.forCapability(
            target.capabilityId,
            (target.targetProfile?.mode ?: TargetMode.NONE).wireValue,
        )
        val pointer = runCatching { store.activationState(slot).current }.getOrNull()
            ?: return null
        if (pointer.identity.packageId != entry.packageId ||
            pointer.identity.packageVersion != entry.packageVersion ||
            pointer.canonicalManifestSha256 != entry.manifestSha256
        ) {
            return null
        }
        val acquired = store.acquireRuntimeLease(pointer, now)
            as? app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult.Acquired
            ?: return null
        return acquired.lease.use { lease ->
            try {
                SignedMetadataCodec.decodeAndVerifyManifest(
                    documentBytes = lease.canonicalManifest.copyBytes(),
                    catalog = catalog,
                    packageId = entry.packageId,
                    nowEpochMillis = now,
                ).also(::cacheVerifiedManifest)
            } catch (_: Throwable) {
                onRejected()
                null
            }
        }
    }

    private fun cacheVerifiedManifest(document: VerifiedManifestDocument) {
        runCatching { VerifiedManifestCache(filesDir, document.catalogEntry).write(document) }
    }

    private fun runArtifactSelfTest(
        verified: VerifiedManifestDocument,
        files: Map<String, File>,
        target: ModelPreparationTarget,
    ): ArtifactSelfTestOutcome {
        val manifest = verified.manifest
        val registry = ManifestRuntimeComponents.registry(
            referenceImageProvider = target.referenceImageProvider,
            referenceEmbeddingCache = ReferenceEmbeddingCaches.openAppPrivate(filesDir),
        )
        val verifiedPackage = VerifiedModelPackage(
            manifest = manifest,
            catalogEntryActive = verified.catalogEntry.active,
            catalogSignatureValid = true,
            catalogManifestSha256Matches = true,
            manifestSignatureValid = true,
            artifactSha256Valid = true,
            licenseTextSha256Valid = true,
        )
        if (manifest.runtimeFamily == RecipeFamily.READING_PIPELINE_V1) {
            return when (
                val result = ManifestKnownAnswerSelfTestRunner.runReading(
                    verifiedPackage = verifiedPackage,
                    artifactFilesByRole = files,
                    buildChannel = buildChannel,
                    registry = registry,
                    nowEpochMillis = nowEpochMillis,
                )
            ) {
                ManifestKnownAnswerSelfTestResult.Passed -> ArtifactSelfTestOutcome(
                    passed = true,
                    activationErrors = emptySet(),
                )
                is ManifestKnownAnswerSelfTestResult.Failed -> ArtifactSelfTestOutcome(
                    passed = false,
                    activationErrors = result.errors,
                )
            }
        }
        val creation = ModelPackageRuntimeFactory(
            registry = registry,
            normalizer = UprightRgbFrameNormalizer,
            qualityGate = ManifestFrameQualityGates.forManifest(manifest),
            nowEpochMillis = nowEpochMillis,
        ).create(
            ModelRuntimeRequest(
                verifiedPackage = verifiedPackage,
                artifactFile = files.getValue("primary"),
                artifactFilesByRole = files,
                buildChannel = buildChannel,
                targetProfile = target.targetProfile,
            ),
        )
        val runtime = (creation as? RuntimeCreationResult.Ready)?.runtime ?: return when (creation) {
            is RuntimeCreationResult.Unavailable -> ArtifactSelfTestOutcome(
                passed = false,
                activationErrors = creation.errors,
            )
            is RuntimeCreationResult.Ready -> error("unreachable")
        }
        val passed = runtime.use {
            if (manifest.runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1) {
                // A smoke frame is deliberately content-neutral. Object detection may return
                // unavailable for it, so the activation check only requires that the exact graph
                // accepts a frame without throwing; positive/negative quality is measured by the
                // frozen replay and device tests.
                val processed = runCatching { runtime.process(target.selfTestFrame) }
                    .getOrNull() as? RuntimeFrameResult.Processed
                processed?.pipelineResult?.adapterCompleted == true
            } else {
                val processed = runtime.process(target.selfTestFrame) as? RuntimeFrameResult.Processed
                    ?: return@use false
                minimumArtifactSmokeTestPassed(manifest.runtimeFamily, processed.pipelineResult)
            }
        }
        return ArtifactSelfTestOutcome(passed, emptySet())
    }

    private fun unavailable(
        failure: ModelPreparationFailure,
        message: String,
        canOpenAccount: Boolean = false,
    ) = ModelPreparationResult.Unavailable(failure, message, canOpenAccount)

    companion object {
        fun createApp(
            context: Context,
            productAccess: () -> ProductAccessDecision = ::failClosedProductAccess,
        ): ModelPreparationCoordinator {
            val appContext = context.applicationContext
            val memory = ActivityManager.MemoryInfo().also { info ->
                appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            }
            val device = currentModelPreparationDevice(appContext, memory)
            val roomStore = RoomMonitorStore(MonitorDatabaseFactory.open(appContext))
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            return ModelPreparationCoordinator(
                appContext = appContext,
                filesDir = appContext.filesDir,
                catalogUrl = BuildConfig.MODEL_CATALOG_URL,
                buildChannel = BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)
                    ?: BuildChannel.DEVELOPMENT_NO_MODEL,
                device = device,
                targetResolver = ModelPreparationTargetResolver { taskId ->
                    val stored = roomStore.find(taskId) ?: return@ModelPreparationTargetResolver null
                    val restored = StoredTaskRuntimeResolver(appContext.filesDir).resolve(stored)
                    val profile = restored.targetProfile
                    val frame = if (profile is TargetProfile.ReferenceImages) {
                        val asset = restored.referenceImageProvider.load(profile.images.first())
                            ?: return@ModelPreparationTargetResolver null
                        SourceFrame(
                            sourceSequence = 0,
                            monotonicTimeMillis = 0,
                            capturedAtEpochMillis = null,
                            width = asset.width,
                            height = asset.height,
                            rotationDegrees = 0,
                            cropRect = PixelRect(0, 0, asset.width, asset.height),
                            pixels = FramePixels.Rgb888(asset.rgb888, asset.width * 3),
                        )
                    } else {
                        syntheticSelfTestFrame()
                    }
                    ModelPreparationTarget(
                        taskRevision = stored.revision,
                        capabilityId = restored.capabilityId,
                        requiredTask = restored.supportedTask,
                        requiredRuleType = when (restored.rule) {
                            is RuntimeMonitorRule.PresenceEpisode -> "presence_duration"
                            is RuntimeMonitorRule.Presence -> "presence_duration"
                            is RuntimeMonitorRule.Absence -> "absence_duration"
                            is RuntimeMonitorRule.ObjectCount -> "object_count"
                            is RuntimeMonitorRule.ReadingThreshold -> "reading_threshold"
                            is RuntimeMonitorRule.StateTransition -> "state_transition"
                        },
                        requiredRuntimeFamily = when (profile) {
                            is TargetProfile.ObjectClass -> RecipeFamily.OBJECT_DETECTION_V1
                            is TargetProfile.ReferenceImages -> RecipeFamily.SIMILARITY_MATCH_V1
                            null -> when (restored.rule) {
                                is RuntimeMonitorRule.ObjectCount -> RecipeFamily.OBJECT_DETECTION_V1
                                is RuntimeMonitorRule.ReadingThreshold -> RecipeFamily.READING_PIPELINE_V1
                                is RuntimeMonitorRule.StateTransition -> RecipeFamily.SIMILARITY_MATCH_V1
                                is RuntimeMonitorRule.PresenceEpisode,
                                is RuntimeMonitorRule.Presence,
                                is RuntimeMonitorRule.Absence,
                                -> RecipeFamily.SIMILARITY_MATCH_V1
                            }
                        },
                        targetProfile = profile,
                        referenceImageProvider = restored.referenceImageProvider,
                        selfTestFrame = frame,
                        requiredPackageId = restored.runtimePackagePointer?.identity?.packageId,
                    )
                },
                taskBinder = ModelPreparationTaskBinder { config ->
                    roomStore.bindRuntimePackage(config)
                },
                networkAvailable = {
                    // Android's external validation probe can be unreachable on an otherwise
                    // usable mainland network; the signed HTTPS fetch is the actual availability check.
                    connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                },
                productAccess = productAccess,
            )
        }

        private fun syntheticSelfTestFrame(): SourceFrame {
            val size = 224
            val rgb = ByteArray(size * size * 3)
            repeat(size) { y ->
                repeat(size) { x ->
                    val value = if (((x / 14) + (y / 14)) % 2 == 0) 36 else 218
                    val offset = (y * size + x) * 3
                    rgb[offset] = value.toByte()
                    rgb[offset + 1] = value.toByte()
                    rgb[offset + 2] = value.toByte()
                }
            }
            return SourceFrame(
                sourceSequence = 0,
                monotonicTimeMillis = 0,
                capturedAtEpochMillis = null,
                width = size,
                height = size,
                rotationDegrees = 0,
                cropRect = PixelRect(0, 0, size, size),
                pixels = FramePixels.Rgb888(rgb, size * 3),
            )
        }

    }

    private data class ArtifactSelfTestOutcome(
        val passed: Boolean,
        val activationErrors: Set<RuntimeActivationError>,
    )
}

internal fun ModelPackageDeliveryResult.isMonitoringActiveRejection(): Boolean =
    this is ModelPackageDeliveryResult.Rejected &&
        ModelPackageRejection.MONITORING_ACTIVE in storeReasons

internal fun currentModelPreparationDevice(
    context: Context,
    memoryInfo: ActivityManager.MemoryInfo? = null,
): ModelPreparationDevice {
    val appContext = context.applicationContext
    val memory = memoryInfo ?: ActivityManager.MemoryInfo().also { info ->
        appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    }
    return ModelPreparationDevice(
        androidApi = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
        marketedMemoryMb = MarketedRamClass.fromPhysicalBytes(memory.totalMem),
        rawTotalMemoryBytes = memory.totalMem,
    )
}
