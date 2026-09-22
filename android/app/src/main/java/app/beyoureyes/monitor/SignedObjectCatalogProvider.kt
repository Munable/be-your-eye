package app.beyoureyes.monitor

import android.content.Context
import app.beyoureyes.core.data.CatalogPackageEntry
import app.beyoureyes.core.data.MetadataFetchResult
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.data.SignedMetadataHttpClient
import app.beyoureyes.core.data.VerifiedCapabilityCatalog
import app.beyoureyes.core.data.VerifiedCatalogCache
import app.beyoureyes.core.data.VerifiedManifestCache
import app.beyoureyes.core.data.VerifiedManifestDocument
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.TargetMode
import app.beyoureyes.monitor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import app.beyoureyes.core.domain.ObjectClassDefinition
import app.beyoureyes.monitor.feature.objectdetection.ObjectTargetCatalogProvider

internal class ObjectCatalogUnavailableException internal constructor(
    val reason: Reason,
) : IllegalStateException(reason.name) {
    internal enum class Reason {
        NOT_CONFIGURED,
        NETWORK,
        REJECTED,
        BUILD_CHANNEL_MISMATCH,
        NO_ACTIVE_PROFILE,
    }
}

internal class SignedObjectCatalogProvider(
    private val catalogUrl: String,
    private val expectedBuildChannel: BuildChannel,
    private val metadataClient: SignedMetadataHttpClient,
    private val cache: VerifiedCatalogCache,
    private val manifestCacheFactory: (CatalogPackageEntry) -> VerifiedManifestCache,
    private val nowEpochMillis: () -> Long,
    private val bundledMetadata: (String) -> ByteArray? = { null },
) : ObjectTargetCatalogProvider {
    override suspend fun load(): List<ObjectClassDefinition> = withContext(Dispatchers.IO) {
        if (catalogUrl.isBlank() || expectedBuildChannel == BuildChannel.DEVELOPMENT_NO_MODEL) {
            throw ObjectCatalogUnavailableException(
                ObjectCatalogUnavailableException.Reason.NOT_CONFIGURED,
            )
        }
        val fetched = bundledMetadata(catalogUrl)?.let { MetadataFetchResult.Fetched(it) }
            ?: metadataClient.fetchCatalog(catalogUrl)
        val bytes = when (fetched) {
            is MetadataFetchResult.Fetched -> fetched.bytes
            is MetadataFetchResult.Retryable -> cache.readBytesOrNull()
                ?: throw ObjectCatalogUnavailableException(
                    ObjectCatalogUnavailableException.Reason.NETWORK,
                )
            is MetadataFetchResult.Rejected -> throw ObjectCatalogUnavailableException(
                ObjectCatalogUnavailableException.Reason.REJECTED,
            )
        }
        val now = nowEpochMillis()
        val verified = try {
            SignedMetadataCodec.decodeAndVerifyCatalog(
                documentBytes = bytes,
                nowEpochMillis = now,
            )
        } catch (_: Throwable) {
            throw ObjectCatalogUnavailableException(
                ObjectCatalogUnavailableException.Reason.REJECTED,
            )
        }
        if (fetched is MetadataFetchResult.Fetched) cache.write(verified)
        if (verified.catalog.buildChannel != expectedBuildChannel) {
            throw ObjectCatalogUnavailableException(
                ObjectCatalogUnavailableException.Reason.BUILD_CHANNEL_MISMATCH,
            )
        }
        val manifests = verified.fetchActiveObjectManifests(
            metadataClient = metadataClient,
            manifestCacheFactory = manifestCacheFactory,
            nowEpochMillis = now,
            bundledMetadata = bundledMetadata,
        )
        val authorized = verified.catalog.operationalCapabilities.flatMap { it.targetIds }.toSet()
        val definitions = manifests.values.flatMap { document ->
            requireNotNull(document.manifest.adapterContract.classMap).targets.map { target ->
                require(target.targetId in authorized)
                ObjectClassDefinition(target.targetId, target.labelZhCn, target.labelEn,
                    target.aliases.toSet(), target.labels)
            }
        }.groupBy { it.targetId }.map { (_, values) ->
            require(values.all { it == values.first() })
            values.first()
        }
        require(definitions.isNotEmpty())
        definitions
    }

    companion object {
        fun create(context: Context, catalogUrl: String): ObjectTargetCatalogProvider {
            val expectedChannel = BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)
                ?: BuildChannel.DEVELOPMENT_NO_MODEL
            val appContext = context.applicationContext
            return try {
                SignedObjectCatalogProvider(
                    catalogUrl = catalogUrl,
                    expectedBuildChannel = expectedChannel,
                    metadataClient = SignedMetadataHttpClient(),
                    cache = VerifiedCatalogCache(appContext.filesDir, catalogUrl),
                    manifestCacheFactory = { entry ->
                        VerifiedManifestCache(appContext.filesDir, entry)
                    },
                    nowEpochMillis = System::currentTimeMillis,
                    bundledMetadata = { app.beyoureyes.monitor.CommunityModelMetadata.read(appContext, it) },
                )
            } catch (_: Throwable) {
                ObjectTargetCatalogProvider {
                    throw ObjectCatalogUnavailableException(
                        ObjectCatalogUnavailableException.Reason.NOT_CONFIGURED,
                    )
                }
            }
        }
    }
}

private fun VerifiedCapabilityCatalog.fetchActiveObjectManifests(
    metadataClient: SignedMetadataHttpClient,
    manifestCacheFactory: (CatalogPackageEntry) -> VerifiedManifestCache,
    nowEpochMillis: Long,
    bundledMetadata: (String) -> ByteArray?,
): Map<String, VerifiedManifestDocument> {
    val recipes = catalog.runtimeRecipes.associateBy { it.recipeId }
    val packageIds = catalog.operationalCapabilities
        .asSequence()
        .filter { capability ->
            val recipe = recipes[capability.recipeId]
            recipe?.runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1 &&
                TargetMode.OBJECT_CLASS in recipe.promptModes
        }
        .flatMap { it.packageIds.asSequence() }
        .distinct()
        .sorted()
        .toList()
    val verifiedByPackageId = linkedMapOf<String, VerifiedManifestDocument>()
    packageIds.forEach { packageId ->
        val entry = activePackage(packageId) ?: throw ObjectCatalogUnavailableException(
            ObjectCatalogUnavailableException.Reason.REJECTED,
        )
        val cache = try {
            manifestCacheFactory(entry)
        } catch (_: Throwable) {
            throw ObjectCatalogUnavailableException(
                ObjectCatalogUnavailableException.Reason.REJECTED,
            )
        }
        val fetched = bundledMetadata(entry.manifestUrl)?.let { MetadataFetchResult.Fetched(it) }
            ?: metadataClient.fetchManifest(entry)
        val bytes = when (fetched) {
            is MetadataFetchResult.Fetched -> fetched.bytes
            is MetadataFetchResult.Retryable -> cache.readBytesOrNull()
                ?: return@forEach
            is MetadataFetchResult.Rejected -> throw ObjectCatalogUnavailableException(
                ObjectCatalogUnavailableException.Reason.REJECTED,
            )
        }
        val verified = try {
            SignedMetadataCodec.decodeAndVerifyManifest(
                documentBytes = bytes,
                catalog = this,
                packageId = packageId,
                nowEpochMillis = nowEpochMillis,
            )
        } catch (_: Throwable) {
            throw ObjectCatalogUnavailableException(
                ObjectCatalogUnavailableException.Reason.REJECTED,
            )
        }
        if (fetched is MetadataFetchResult.Fetched) cache.write(verified)
        verifiedByPackageId[packageId] = verified
    }
    return verifiedByPackageId
}
