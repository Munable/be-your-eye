package app.beyoureyes.monitor

import android.content.Context
import app.beyoureyes.core.data.CatalogModelCard
import app.beyoureyes.core.data.ModelArtifactStore
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.ResolvedSamplingMismatch
import app.beyoureyes.core.data.RoomMonitorStore
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.data.VerifiedCapabilityCatalog
import app.beyoureyes.core.data.VerifiedCatalogCache
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.ModelPackageManifest
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.SamplingPolicySpec
import app.beyoureyes.core.vision.TargetProfile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SamplingConfigResolutionFailure {
    TASK_UNAVAILABLE,
    PACKAGE_UNBOUND,
    CONFIG_MISSING_OR_CORRUPT,
    PACKAGE_UNAVAILABLE,
    MANIFEST_INVALID,
    CONFIG_STALE,
}

internal sealed interface SamplingConfigResolution {
    data class Ready(
        val config: ResolvedSamplingConfig,
        val fieldValidationSpec: SimilarityFieldValidationSpec?,
        val readingPreviewSpec: ReadingPreviewSpec?,
        /** Friendly label derived from the already verified publisher artifact URL. */
        val modelDisplayName: String,
    ) : SamplingConfigResolution

    data class Unavailable(
        val failure: SamplingConfigResolutionFailure,
        val userMessage: String,
    ) : SamplingConfigResolution
}

/**
 * Resolves the exact persisted sampling decision before CameraSetup owns the camera. Every call
 * reacquires the immutable package and checks the signed Manifest bounds. Candidate ordering and
 * vendor/package IDs never influence the interval; a non-default current value is accepted only
 * when the signed Manifest explicitly allows adaptation.
 */
internal class TaskBoundSamplingConfigResolver(
    private val appContext: Context,
    private val filesDir: File,
    private val roomStore: RoomMonitorStore,
    private val artifactStore: ModelArtifactStore,
    private val deviceFingerprintSha256: String,
    private val catalogUrl: String,
    private val buildChannel: BuildChannel,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val verifiedModelDisplayNames: VerifiedModelDisplayNameCache =
        VerifiedModelDisplayNameCache(),
) {
    /**
     * Returns only a name previously derived from an exact verified package pointer. Camera setup
     * fills this cache before the monitoring service takes the store's single runtime lease; a
     * stopped monitor can safely perform the normal full resolution on a cache miss.
     */
    suspend fun resolveModelDisplayName(taskId: String): String? = withContext(Dispatchers.IO) {
        if (!UuidV7.isValid(taskId)) return@withContext null
        val pointer = roomStore.find(taskId)?.runtimePackagePointer ?: return@withContext null
        verifiedModelDisplayNames.find(pointer)?.let { return@withContext it }
        (resolve(taskId) as? SamplingConfigResolution.Ready)?.modelDisplayName
    }

    suspend fun resolve(taskId: String): SamplingConfigResolution = withContext(Dispatchers.IO) {
        if (!UuidV7.isValid(taskId)) {
            return@withContext unavailable(SamplingConfigResolutionFailure.TASK_UNAVAILABLE)
        }
        val task = roomStore.find(taskId)
            ?: return@withContext unavailable(SamplingConfigResolutionFailure.TASK_UNAVAILABLE)
        val pointer = task.runtimePackagePointer
            ?: return@withContext unavailable(SamplingConfigResolutionFailure.PACKAGE_UNBOUND)
        val persisted = roomStore.findResolvedSamplingConfig(taskId)
            ?: return@withContext unavailable(
                SamplingConfigResolutionFailure.CONFIG_MISSING_OR_CORRUPT,
            )
        val restored = runCatching {
            StoredTaskRuntimeResolver(filesDir).resolve(
                stored = task,
                samplingIntervalMillis = persisted.intervalMillis,
            )
        }.getOrNull() ?: return@withContext unavailable(
            SamplingConfigResolutionFailure.TASK_UNAVAILABLE,
        )
        val acquisition = artifactStore.acquireRuntimeLease(pointer, nowEpochMillis())
        val lease = (acquisition as? ModelPackageRuntimeLeaseResult.Acquired)?.lease
            ?: return@withContext unavailable(SamplingConfigResolutionFailure.PACKAGE_UNAVAILABLE)
        lease.use {
            val manifest = runCatching {
                SignedMetadataCodec.decodeStoredManifestDocument(
                    documentBytes = lease.canonicalManifest.copyBytes(),
                    expectedIdentity = lease.descriptor.identity,
                    expectedSha256 = lease.descriptor.canonicalManifestSha256,
                )
            }.getOrNull() ?: return@withContext unavailable(
                SamplingConfigResolutionFailure.MANIFEST_INVALID,
            )
            val signedRoute = resolveExactSignedRoute(
                filesDir = filesDir,
                catalogUrl = catalogUrl,
                buildChannel = buildChannel,
                nowEpochMillis = nowEpochMillis(),
                config = persisted,
                restored = restored,
                manifest = manifest,
            ) ?: return@withContext unavailable(SamplingConfigResolutionFailure.CONFIG_STALE)
            val mismatches = taskBoundSamplingMismatches(
                config = persisted,
                expectedTaskId = task.taskId,
                expectedTaskRevision = task.revision,
                expectedCatalogVersion = signedRoute.catalogVersion,
                expectedCapabilityId = signedRoute.capabilityId,
                expectedModelProfileKey = signedRoute.modelProfileKey,
                expectedRecipeId = signedRoute.recipeId,
                expectedIntentKey = signedRoute.intentKey,
                expectedPackagePointer = pointer,
                expectedArtifactIdentitySha256 = lease.descriptor.descriptorSha256,
                expectedDeviceFingerprintSha256 = deviceFingerprintSha256,
                signedSamplingPolicy = manifest.parameterProfile.samplingPolicy,
            )
            if (mismatches.isNotEmpty()) {
                return@withContext unavailable(SamplingConfigResolutionFailure.CONFIG_STALE)
            }
            if (!storedTaskRuntimeFamilyMatches(task.targetMode, manifest.runtimeFamily)) {
                return@withContext unavailable(SamplingConfigResolutionFailure.CONFIG_STALE)
            }
            val fieldValidationSpec = if (
                storedTaskUsesFieldValidation(task.targetMode)
            ) {
                val targetProfile = restored.targetProfile ?: return@withContext unavailable(
                    SamplingConfigResolutionFailure.TASK_UNAVAILABLE,
                )
                if (restored.runtimePackagePointer != persisted.packagePointer ||
                    restored.taskId != persisted.taskId ||
                    restored.revision != persisted.taskRevision ||
                    !requiresSimilarityFieldValidation(targetProfile, manifest.runtimeFamily)
                ) {
                    return@withContext unavailable(
                        SamplingConfigResolutionFailure.CONFIG_STALE,
                    )
                }
                restoredFieldValidationSpec(
                    filesDir = filesDir,
                    restored = restored,
                    targetProfile = targetProfile,
                )
            } else {
                null
            }
            val readingPreviewSpec = if (
                storedTaskUsesReading(task.targetMode)
            ) {
                if (restored.runtimePackagePointer != persisted.packagePointer ||
                    restored.taskId != persisted.taskId ||
                    restored.revision != persisted.taskRevision ||
                    restored.capabilityId != READING_CAPABILITY_ID
                ) {
                    return@withContext unavailable(
                        SamplingConfigResolutionFailure.CONFIG_STALE,
                    )
                }
                ReadingPreviewSpec.production(filesDir, restored)
            } else {
                null
            }
            val modelDisplayName = signedRoute.modelDisplayName
            verifiedModelDisplayNames.remember(pointer, modelDisplayName)
            SamplingConfigResolution.Ready(
                config = persisted,
                fieldValidationSpec = fieldValidationSpec,
                readingPreviewSpec = readingPreviewSpec,
                modelDisplayName = modelDisplayName,
            )
        }
    }

    private fun unavailable(failure: SamplingConfigResolutionFailure) =
        SamplingConfigResolution.Unavailable(
            failure = failure,
            userMessage = when (failure) {
                SamplingConfigResolutionFailure.TASK_UNAVAILABLE ->
                    appContext.getString(R.string.model_task_changed)
                SamplingConfigResolutionFailure.PACKAGE_UNBOUND,
                SamplingConfigResolutionFailure.CONFIG_MISSING_OR_CORRUPT,
                SamplingConfigResolutionFailure.CONFIG_STALE,
                -> appContext.getString(R.string.sampling_config_restore_required)
                SamplingConfigResolutionFailure.PACKAGE_UNAVAILABLE ->
                    appContext.getString(R.string.sampling_package_unavailable)
                SamplingConfigResolutionFailure.MANIFEST_INVALID ->
                    appContext.getString(R.string.model_security_check_retry)
            },
        )

    companion object {
        fun createApp(context: Context): TaskBoundSamplingConfigResolver {
            val appContext = context.applicationContext
            return TaskBoundSamplingConfigResolver(
                appContext = appContext,
                filesDir = appContext.filesDir,
                roomStore = RoomMonitorStore(MonitorDatabaseFactory.open(appContext)),
                artifactStore = ModelPackageStores.open(appContext.filesDir),
                deviceFingerprintSha256 = currentModelPreparationDevice(appContext)
                    .samplingFingerprintSha256,
                catalogUrl = BuildConfig.MODEL_CATALOG_URL,
                buildChannel = BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)
                    ?: BuildChannel.DEVELOPMENT_NO_MODEL,
            )
        }
    }
}

/** Process-local cache; keys include identity, version and canonical signed Manifest hash. */
internal class VerifiedModelDisplayNameCache {
    private val names = ConcurrentHashMap<ModelPackagePointer, String>()

    fun find(pointer: ModelPackagePointer): String? = names[pointer]

    fun remember(pointer: ModelPackagePointer, displayName: String) {
        require(displayName.isNotBlank())
        names[pointer] = displayName
    }
}

internal fun signedModelDisplayName(modelCard: CatalogModelCard): String? =
    modelCard.modelName.trim().takeIf(String::isNotEmpty)

internal fun storedTaskUsesReferenceImages(targetMode: String): Boolean =
    targetMode == app.beyoureyes.core.domain.MonitorKind.REFERENCE.wireValue

internal fun storedTaskUsesObjectDetection(targetMode: String): Boolean =
    targetMode == app.beyoureyes.core.domain.MonitorKind.OBJECT_DETECTION.wireValue

internal fun storedTaskUsesFieldValidation(targetMode: String): Boolean =
    storedTaskUsesReferenceImages(targetMode) || storedTaskUsesObjectDetection(targetMode)

internal fun storedTaskUsesReading(targetMode: String): Boolean =
    targetMode == app.beyoureyes.core.domain.MonitorKind.READING.wireValue

internal fun storedTaskRuntimeFamilyMatches(
    targetMode: String,
    runtimeFamily: RecipeFamily,
): Boolean = when (targetMode) {
    app.beyoureyes.core.domain.MonitorKind.REFERENCE.wireValue ->
        runtimeFamily == RecipeFamily.SIMILARITY_MATCH_V1
    app.beyoureyes.core.domain.MonitorKind.READING.wireValue ->
        runtimeFamily == RecipeFamily.READING_PIPELINE_V1
    app.beyoureyes.core.domain.MonitorKind.OBJECT_DETECTION.wireValue ->
        runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1
    else -> false
}

/** Recreates the exact live setup validator after a configured visual task is reopened. */
internal fun restoredFieldValidationSpec(
    filesDir: File,
    restored: RestoredMonitoringTask,
    targetProfile: TargetProfile,
): SimilarityFieldValidationSpec = when (targetProfile) {
    is TargetProfile.ReferenceImages -> SimilarityFieldValidationSpec.production(
        filesDir = filesDir,
        task = restored,
        targetProfile = targetProfile,
    )
    is TargetProfile.ObjectClass -> SimilarityFieldValidationSpec.productionObject(
        filesDir = filesDir,
        taskId = restored.taskId,
        taskRevision = restored.revision,
        packagePointer = requireNotNull(restored.runtimePackagePointer),
        targetProfile = targetProfile,
    )
}

/** Pure validation seam shared by the resolver contract and its local JVM regression tests. */
internal fun taskBoundSamplingMismatches(
    config: ResolvedSamplingConfig,
    expectedTaskId: String,
    expectedTaskRevision: Long,
    expectedCatalogVersion: String,
    expectedCapabilityId: String,
    expectedModelProfileKey: String,
    expectedRecipeId: String,
    expectedIntentKey: String,
    expectedPackagePointer: ModelPackagePointer,
    expectedArtifactIdentitySha256: String,
    expectedDeviceFingerprintSha256: String,
    signedSamplingPolicy: SamplingPolicySpec,
): Set<ResolvedSamplingMismatch> = config.exactMismatchReasons(
    expectedTaskId = expectedTaskId,
    expectedTaskRevision = expectedTaskRevision,
    expectedCatalogVersion = expectedCatalogVersion,
    expectedCapabilityId = expectedCapabilityId,
    expectedModelProfileKey = expectedModelProfileKey,
    expectedRecipeId = expectedRecipeId,
    expectedIntentKey = expectedIntentKey,
    expectedPackagePointer = expectedPackagePointer,
    expectedArtifactIdentitySha256 = expectedArtifactIdentitySha256,
    expectedDeviceFingerprintSha256 = expectedDeviceFingerprintSha256,
    signedSamplingPolicy = signedSamplingPolicy,
)

private data class ExactSignedRoute(
    val catalogVersion: String,
    val capabilityId: String,
    val modelProfileKey: String,
    val recipeId: String,
    val intentKey: String,
    val modelDisplayName: String,
)

/** Re-verifies the persisted route against the same signed Catalog and stored Manifest. */
private fun resolveExactSignedRoute(
    filesDir: File,
    catalogUrl: String,
    buildChannel: BuildChannel,
    nowEpochMillis: Long,
    config: ResolvedSamplingConfig,
    restored: RestoredMonitoringTask,
    manifest: ModelPackageManifest,
): ExactSignedRoute? {
    val catalog = runCatching {
        val bytes = VerifiedCatalogCache(filesDir, catalogUrl)
            .readBytesOrNull() ?: return null
        SignedMetadataCodec.decodeAndVerifyCatalog(
            documentBytes = bytes,
            nowEpochMillis = nowEpochMillis,
        )
    }.getOrNull() ?: return null
    return exactSignedRoute(catalog, buildChannel, config, restored, manifest)
}

private fun exactSignedRoute(
    catalog: VerifiedCapabilityCatalog,
    buildChannel: BuildChannel,
    config: ResolvedSamplingConfig,
    restored: RestoredMonitoringTask,
    manifest: ModelPackageManifest,
): ExactSignedRoute? {
    if (catalog.catalog.buildChannel != buildChannel ||
        catalog.catalog.catalogVersion != config.catalogVersion ||
        restored.taskId != config.taskId || restored.revision != config.taskRevision ||
        restored.runtimePackagePointer != config.packagePointer ||
        restored.capabilityId != config.capabilityId ||
        restored.supportedTask !in manifest.supportedTasks
    ) return null
    val entry = catalog.activePackage(config.packagePointer.identity.packageId) ?: return null
    if (entry.packageVersion != config.packagePointer.identity.packageVersion ||
        entry.manifestSha256 != config.packagePointer.canonicalManifestSha256 ||
        manifest.packageId != entry.packageId ||
        manifest.packageVersion != entry.packageVersion
    ) return null
    val capability = catalog.catalog.capabilities.singleOrNull {
        it.capabilityId == config.capabilityId
    } ?: return null
    val recipe = catalog.catalog.runtimeRecipes.singleOrNull {
        it.recipeId == config.recipeId && it.capabilityId == config.capabilityId
    } ?: return null
    val targetMode = when (restored.kind) {
        app.beyoureyes.core.domain.MonitorKind.REFERENCE ->
            app.beyoureyes.core.vision.TargetMode.REFERENCE_IMAGES
        app.beyoureyes.core.domain.MonitorKind.READING ->
            app.beyoureyes.core.vision.TargetMode.NONE
        app.beyoureyes.core.domain.MonitorKind.OBJECT_DETECTION ->
            app.beyoureyes.core.vision.TargetMode.OBJECT_CLASS
    }
    if (recipe.recipeId !in capability.recipeIds || entry.packageId !in recipe.candidatePackageIds ||
        recipe.runtimeFamily != manifest.runtimeFamily || targetMode !in recipe.promptModes ||
        targetMode !in manifest.promptModes
    ) return null
    val operational = matchingOperationalModelProfile(
        operationalCapabilities = catalog.catalog.operationalCapabilities,
        modelProfileKey = config.modelProfileKey,
        capabilityId = config.capabilityId,
        recipeId = config.recipeId,
        packageId = entry.packageId,
        intentKey = config.intentKey,
    ) ?: return null
    val modelDisplayName = signedModelDisplayName(operational.modelCard) ?: return null
    val objectTarget = restored.targetProfile as? TargetProfile.ObjectClass
    if (objectTarget != null &&
        (objectTarget.targetId !in operational.targetIds ||
            !exactSignedObjectTargetMatch(manifest.adapterContract.classMap?.targets, objectTarget))
    ) return null
    return ExactSignedRoute(
        catalogVersion = catalog.catalog.catalogVersion,
        capabilityId = operational.capabilityId,
        modelProfileKey = operational.capabilityKey,
        recipeId = checkNotNull(operational.recipeId),
        intentKey = config.intentKey,
        modelDisplayName = modelDisplayName,
    )
}
