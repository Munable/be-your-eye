package app.beyoureyes.monitor.feature.assistant

import android.content.Context
import app.beyoureyes.core.data.CatalogPackageEntry
import app.beyoureyes.core.data.MetadataFetchResult
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.data.SignedMetadataHttpClient
import app.beyoureyes.core.data.VerifiedCapabilityCatalog
import app.beyoureyes.core.data.VerifiedCatalogCache
import app.beyoureyes.core.data.VerifiedManifestCache
import app.beyoureyes.core.data.VerifiedManifestDocument
import app.beyoureyes.core.data.cloud.CloudConfiguration
import app.beyoureyes.core.data.cloud.SecureSupabaseSessionManager
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.ClassMapTarget
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.TargetMode
import app.beyoureyes.monitor.BuildConfig
import app.beyoureyes.monitor.diagnostics.RuntimeDiagnostics
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MONITOR_ASSISTANT_PATH = "/functions/v1/monitor-assistant"
private const val MAX_REQUEST_BYTES = 48 * 1024
private const val MAX_RESPONSE_BYTES = 64 * 1024

internal fun interface AssistantAccessTokenProvider {
    suspend fun accessToken(): String?
}

internal data class AssistantHttpRequest(
    val url: String,
    val publishableKey: String,
    val accessToken: String,
    val body: ByteArray,
)

internal class AssistantHttpResponse(
    val statusCode: Int,
    val body: InputStream,
) : AutoCloseable {
    override fun close() = body.close()
}

internal fun interface AssistantHttpTransport {
    @Throws(IOException::class)
    fun execute(request: AssistantHttpRequest): AssistantHttpResponse
}

private class UrlConnectionAssistantHttpTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 35_000,
) : AssistantHttpTransport {
    init {
        require(connectTimeoutMillis in 1..120_000)
        require(readTimeoutMillis in 1..120_000)
    }

    override fun execute(request: AssistantHttpRequest): AssistantHttpResponse {
        requireAssistantEndpoint(request.url)
        require(request.publishableKey.isNotBlank() && request.publishableKey.length <= 4_096)
        require(request.accessToken.isNotBlank() && request.accessToken.length <= 16_384)
        require(request.body.size in 1..MAX_REQUEST_BYTES)
        val connection = (URL(request.url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("Authorization", "Bearer ${request.accessToken}")
            setRequestProperty("apikey", request.publishableKey)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(request.body.size)
        }
        return try {
            connection.outputStream.use { output -> output.write(request.body) }
            val status = connection.responseCode
            val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
            if (declaredLength > MAX_RESPONSE_BYTES) {
                connection.disconnect()
                throw IOException("assistant response exceeds the size limit")
            }
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream(byteArrayOf())
            }
            AssistantHttpResponse(
                statusCode = status,
                body = object : InputStream() {
                    override fun read(): Int = stream.read()
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        stream.read(buffer, offset, length)

                    override fun close() {
                        try {
                            stream.close()
                        } finally {
                            connection.disconnect()
                        }
                    }
                },
            )
        } catch (error: Exception) {
            connection.disconnect()
            if (error is IOException) throw error
            throw IOException("assistant HTTPS request failed", error)
        }
    }
}

internal class SupabaseMonitorAssistantGateway internal constructor(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val tokenProvider: AssistantAccessTokenProvider,
    private val transport: AssistantHttpTransport = UrlConnectionAssistantHttpTransport(),
    private val onFailureDiagnostic: (Map<String, String>) -> Unit = {},
) : MonitorAssistantGateway {
    init {
        requireAssistantEndpoint(endpointUrl)
        require(publishableKey.isNotBlank() && publishableKey.length <= 4_096)
    }

    override suspend fun turn(request: AssistantTurnRequest): AssistantGatewayResult {
        val startedAt = System.nanoTime()
        fun recordFailure(stage: String, reason: String) {
            runCatching {
                onFailureDiagnostic(mapOf(
                    "stage" to stage,
                    "reason" to reason,
                    "turn_id" to request.turnId,
                    "duration_ms" to ((System.nanoTime() - startedAt) / 1_000_000).toString(),
                ))
            }
        }
        val accessToken = try {
            tokenProvider.accessToken()?.trim()?.takeIf(String::isNotEmpty)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: return AssistantGatewayResult.SignInRequired

        val body = runCatching { AssistantWireCodec.encodeRequest(request) }
            .getOrElse { return AssistantGatewayResult.InvalidRequest }
        if (body.size !in 1..MAX_REQUEST_BYTES) return AssistantGatewayResult.InvalidRequest

        val response = try {
            withContext(Dispatchers.IO) {
                transport.execute(
                    AssistantHttpRequest(
                        url = endpointUrl,
                        publishableKey = publishableKey,
                        accessToken = accessToken,
                        body = body,
                    ),
                ).use { networkResponse ->
                    RawAssistantHttpResponse(
                        statusCode = networkResponse.statusCode,
                        body = readBounded(networkResponse.body, MAX_RESPONSE_BYTES),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recordFailure("transport", error.javaClass.simpleName)
            return AssistantGatewayResult.Unavailable
        }

        return when (response.statusCode) {
            200 -> {
                val decoded = runCatching {
                    AssistantWireCodec.decodeResponse(response.body, request)
                }.getOrNull() ?: run {
                    recordFailure("response_decode", "invalid_response")
                    return AssistantGatewayResult.Unavailable
                }
                AssistantGatewayResult.Completed(decoded)
            }
            400 -> if (AssistantWireCodec.errorCode(response.body) == "invalid_request") {
                AssistantGatewayResult.InvalidRequest
            } else {
                AssistantGatewayResult.Unavailable
            }
            401 -> AssistantGatewayResult.SignInRequired
            402 -> AssistantGatewayResult.SubscriptionRequired
            else -> {
                recordFailure("http", response.statusCode.toString())
                AssistantGatewayResult.Unavailable
            }
        }
    }

    companion object {
        fun create(
            context: Context,
            supabaseUrl: String,
            publishableKey: String,
        ): MonitorAssistantGateway {
            val configuration = CloudConfiguration.from(supabaseUrl, publishableKey)
            if (configuration !is CloudConfiguration.Enabled) return DisabledMonitorAssistantGateway
            val sessionManager = SecureSupabaseSessionManager(context.applicationContext)
            return SupabaseMonitorAssistantGateway(
                endpointUrl = configuration.supabaseUrl + MONITOR_ASSISTANT_PATH,
                publishableKey = configuration.publishableKey,
                tokenProvider = AssistantAccessTokenProvider {
                    try {
                        sessionManager.loadSession().accessToken
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                },
                onFailureDiagnostic = { details ->
                    RuntimeDiagnostics.record(context, "assistant_request_failed", details)
                },
            )
        }
    }
}

private data class RawAssistantHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

internal data object DisabledMonitorAssistantGateway : MonitorAssistantGateway {
    override suspend fun turn(request: AssistantTurnRequest): AssistantGatewayResult =
        AssistantGatewayResult.Unavailable
}

internal class AssistantCatalogUnavailableException internal constructor(
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

internal class SignedAssistantCatalogSnapshotProvider(
    private val catalogUrl: String,
    private val expectedBuildChannel: BuildChannel,
    private val metadataClient: SignedMetadataHttpClient,
    private val cache: VerifiedCatalogCache,
    private val manifestCacheFactory: (CatalogPackageEntry) -> VerifiedManifestCache,
    private val nowEpochMillis: () -> Long,
    private val bundledMetadata: (String) -> ByteArray? = { null },
    private val installedPackage: (CatalogPackageEntry, Long) -> Boolean = { _, _ -> false },
) : AssistantCatalogSnapshotProvider {
    override suspend fun load(): AssistantCatalogSnapshot = withContext(Dispatchers.IO) {
        if (catalogUrl.isBlank() || expectedBuildChannel == BuildChannel.DEVELOPMENT_NO_MODEL) {
            throw AssistantCatalogUnavailableException(
                AssistantCatalogUnavailableException.Reason.NOT_CONFIGURED,
            )
        }
        val fetched = bundledMetadata(catalogUrl)?.let { MetadataFetchResult.Fetched(it) }
            ?: metadataClient.fetchCatalog(catalogUrl)
        val bytes = when (fetched) {
            is MetadataFetchResult.Fetched -> fetched.bytes
            is MetadataFetchResult.Retryable -> cache.readBytesOrNull()
                ?: throw AssistantCatalogUnavailableException(
                    AssistantCatalogUnavailableException.Reason.NETWORK,
                )
            is MetadataFetchResult.Rejected -> throw AssistantCatalogUnavailableException(
                AssistantCatalogUnavailableException.Reason.REJECTED,
            )
        }
        val now = nowEpochMillis()
        val verified = try {
            SignedMetadataCodec.decodeAndVerifyCatalog(
                documentBytes = bytes,
                nowEpochMillis = now,
                allowInstalledCommunity = expectedBuildChannel == BuildChannel.COMMUNITY,
            )
        } catch (_: Throwable) {
            throw AssistantCatalogUnavailableException(
                AssistantCatalogUnavailableException.Reason.REJECTED,
            )
        }
        if (fetched is MetadataFetchResult.Fetched) cache.write(verified)
        if (verified.catalog.buildChannel != expectedBuildChannel) {
            throw AssistantCatalogUnavailableException(
                AssistantCatalogUnavailableException.Reason.BUILD_CHANNEL_MISMATCH,
            )
        }
        val manifests = verified.fetchActiveObjectManifests(
            metadataClient = metadataClient,
            manifestCacheFactory = manifestCacheFactory,
            nowEpochMillis = now,
            bundledMetadata = bundledMetadata,
            installedPackage = installedPackage,
        )
        verified.toAssistantSnapshot(manifests)
    }

    companion object {
        fun create(context: Context, catalogUrl: String): AssistantCatalogSnapshotProvider {
            val expectedChannel = BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)
                ?: BuildChannel.DEVELOPMENT_NO_MODEL
            val appContext = context.applicationContext
            return try {
                SignedAssistantCatalogSnapshotProvider(
                    catalogUrl = catalogUrl,
                    expectedBuildChannel = expectedChannel,
                    metadataClient = SignedMetadataHttpClient(),
                    cache = VerifiedCatalogCache(appContext.filesDir, catalogUrl),
                    manifestCacheFactory = { entry ->
                        VerifiedManifestCache(appContext.filesDir, entry)
                    },
                    nowEpochMillis = System::currentTimeMillis,
                    bundledMetadata = { app.beyoureyes.monitor.CommunityModelMetadata.read(appContext, it) },
                    installedPackage = { entry, now ->
                        val result = app.beyoureyes.core.data.ModelPackageStores.open(appContext.filesDir)
                            .acquireRuntimeLease(app.beyoureyes.core.data.ModelPackagePointer(
                                app.beyoureyes.core.data.ModelPackageIdentity(entry.packageId, entry.packageVersion),
                                entry.manifestSha256), now)
                        (result as? app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult.Acquired)
                            ?.lease?.use { true } ?: false
                    },
                )
            } catch (_: Throwable) {
                AssistantCatalogSnapshotProvider {
                    throw AssistantCatalogUnavailableException(
                        AssistantCatalogUnavailableException.Reason.NOT_CONFIGURED,
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
    installedPackage: (CatalogPackageEntry, Long) -> Boolean,
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
        val entry = activePackage(packageId) ?: throw AssistantCatalogUnavailableException(
            AssistantCatalogUnavailableException.Reason.REJECTED,
        )
        val cache = try {
            manifestCacheFactory(entry)
        } catch (_: Throwable) {
            throw AssistantCatalogUnavailableException(
                AssistantCatalogUnavailableException.Reason.REJECTED,
            )
        }
        if (installedCommunityOnly && !installedPackage(entry, nowEpochMillis)) return@forEach
        val fetched = bundledMetadata(entry.manifestUrl)?.let { MetadataFetchResult.Fetched(it) }
            ?: metadataClient.fetchManifest(entry)
        val bytes = when (fetched) {
            is MetadataFetchResult.Fetched -> fetched.bytes
            is MetadataFetchResult.Retryable -> cache.readBytesOrNull()
                ?: return@forEach
            is MetadataFetchResult.Rejected -> throw AssistantCatalogUnavailableException(
                AssistantCatalogUnavailableException.Reason.REJECTED,
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
            throw AssistantCatalogUnavailableException(
                AssistantCatalogUnavailableException.Reason.REJECTED,
            )
        }
        if (fetched is MetadataFetchResult.Fetched) cache.write(verified)
        verifiedByPackageId[packageId] = verified
    }
    return verifiedByPackageId
}

private object AssistantWireCodec {
    fun encodeRequest(request: AssistantTurnRequest): ByteArray = JsonObject().apply {
        addProperty("schema_version", ASSISTANT_SCHEMA_VERSION)
        addProperty("conversation_id", request.conversationId)
        addProperty("turn_id", request.turnId)
        addProperty("locale", request.locale)
        add("catalog_binding", request.catalog.binding.toJson())
        add("model_profiles", JsonArray().also { profiles ->
            request.catalog.modelProfiles.forEach { profile ->
                profiles.add(JsonObject().apply {
                    addProperty("model_profile_key", profile.modelProfileKey)
                    addProperty("package_id", profile.packageId)
                    addProperty("kind", profile.kind.wireValue)
                    add("intent_patterns", profile.intentPatterns.toJsonArray())
                    add("applicable_scenarios", profile.applicableScenarios.toJsonArray())
                    add("inapplicable_scenarios", profile.inapplicableScenarios.toJsonArray())
                    add("input_requirements", profile.inputRequirements.toJsonArray())
                    add("targets", JsonArray().also { targets ->
                        profile.targets.forEach { target -> targets.add(target.toJson()) }
                    })
                })
            }
        })
        add("messages", JsonArray().also { messages ->
            request.messages.forEach { message ->
                messages.add(JsonObject().apply {
                    addProperty("role", message.role.wireValue)
                    addProperty("content", message.content)
                })
            }
        })
    }.toString().encodeToByteArray()

    fun decodeResponse(bytes: ByteArray, request: AssistantTurnRequest): AssistantTurnResponse {
        val root = parseObject(bytes).exact(
            "schema_version",
            "conversation_id",
            "turn_id",
            "result",
        )
        require(root.requiredString("schema_version") == ASSISTANT_SCHEMA_VERSION)
        val conversationId = root.requiredString("conversation_id")
        val turnId = root.requiredString("turn_id")
        require(conversationId == request.conversationId && turnId == request.turnId)
        val resultObject = root.requiredObject("result")
        val result = when (resultObject.requiredString("type")) {
            "message" -> {
                resultObject.exact("type", "content")
                AssistantTurnResult.Message(resultObject.requiredString("content"))
            }
            "proposal" -> {
                resultObject.exact("type", "proposal")
                val proposal = decodeProposal(resultObject.requiredObject("proposal"))
                require(validateProposalAgainstCatalog(proposal, request.catalog))
                AssistantTurnResult.Proposal(proposal)
            }
            else -> error("unknown assistant result")
        }
        return AssistantTurnResponse(conversationId, turnId, result)
    }

    fun errorCode(bytes: ByteArray): String? = runCatching {
        parseObject(bytes).exact("code").requiredString("code")
    }.getOrNull()

    private fun decodeProposal(value: JsonObject): MonitorConfigurationProposal {
        value.exact(
            "schema_version",
            "kind",
            "title",
            "catalog_binding",
            "model_profile_key",
            "package_id",
            "intent_key",
            "target",
            "rule",
        )
        require(value.requiredString("schema_version") == ASSISTANT_SCHEMA_VERSION)
        val kind = requireNotNull(
            AssistantProposalKind.fromWireValue(value.requiredString("kind")),
        )
        val title = value.requiredString("title")
        val binding = decodeBinding(value.requiredObject("catalog_binding"))
        val profileKey = value.requiredString("model_profile_key")
        val packageId = value.requiredString("package_id")
        val intentKey = value.requiredString("intent_key")
        val target = value.requiredObject("target")
        val rule = value.requiredObject("rule")
        return when (kind) {
            AssistantProposalKind.REFERENCE_IMAGES -> {
                target.exact("mode", "required_image_count")
                require(target.requiredString("mode") == "reference_images")
                require(target.requiredInt("required_image_count") == ASSISTANT_REFERENCE_IMAGE_COUNT)
                MonitorConfigurationProposal.ReferenceImages(
                    title = title,
                    catalogBinding = binding,
                    modelProfileKey = profileKey,
                    packageId = packageId,
                    intentKey = intentKey,
                    rule = decodePresenceRule(rule),
                )
            }
            AssistantProposalKind.VISUAL_DESCRIPTION -> {
                target.exact("mode", "target_id", "display_text")
                require(target.requiredString("mode") == "visual_description")
                MonitorConfigurationProposal.VisualDescription(
                    title = title,
                    catalogBinding = binding,
                    modelProfileKey = profileKey,
                    packageId = packageId,
                    intentKey = intentKey,
                    targetId = target.requiredString("target_id"),
                    displayText = target.requiredString("display_text"),
                    rule = decodePresenceRule(rule),
                )
            }
            AssistantProposalKind.STRUCTURED_READING -> {
                target.exact("mode")
                require(target.requiredString("mode") == "structured_reading")
                MonitorConfigurationProposal.StructuredReading(
                    title = title,
                    catalogBinding = binding,
                    modelProfileKey = profileKey,
                    packageId = packageId,
                    intentKey = intentKey,
                    rule = decodeReadingRule(rule),
                )
            }
        }
    }

    private fun decodeBinding(value: JsonObject): AssistantCatalogBinding {
        value.exact("catalog_id", "catalog_version", "catalog_signed_payload_sha256")
        return AssistantCatalogBinding(
            catalogId = value.requiredString("catalog_id"),
            catalogVersion = value.requiredString("catalog_version"),
            catalogSignedPayloadSha256 = value.requiredString("catalog_signed_payload_sha256"),
        )
    }

    private fun decodePresenceRule(value: JsonObject): AssistantPresenceRule {
        value.exact("type", "condition", "duration_seconds")
        require(value.requiredString("type") == "target_presence")
        return AssistantPresenceRule(
            condition = requireNotNull(
                AssistantPresenceCondition.fromWireValue(value.requiredString("condition")),
            ),
            durationSeconds = value.requiredInt("duration_seconds"),
        )
    }

    private fun decodeReadingRule(value: JsonObject): AssistantReadingRule {
        require(value.requiredString("type") == "reading_threshold")
        return when (val condition = value.requiredString("condition")) {
            "above", "below" -> {
                value.exact("type", "condition", "threshold_decimal", "duration_seconds")
                AssistantReadingRule.Single(
                    condition = requireNotNull(AssistantReadingCondition.fromWireValue(condition)),
                    thresholdDecimal = value.requiredString("threshold_decimal"),
                    durationSeconds = value.requiredInt("duration_seconds"),
                )
            }
            "outside" -> {
                value.exact(
                    "type",
                    "condition",
                    "lower_threshold_decimal",
                    "upper_threshold_decimal",
                    "duration_seconds",
                )
                AssistantReadingRule.Outside(
                    lowerThresholdDecimal = value.requiredString("lower_threshold_decimal"),
                    upperThresholdDecimal = value.requiredString("upper_threshold_decimal"),
                    durationSeconds = value.requiredInt("duration_seconds"),
                )
            }
            else -> error("unknown reading condition")
        }
    }

    private fun parseObject(bytes: ByteArray): JsonObject {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RESPONSE_BYTES)
        val decoded = decodeUtf8(bytes)
        val element = JsonParser.parseString(decoded)
        require(element.isJsonObject)
        return element.asJsonObject
    }
}

internal fun VerifiedCapabilityCatalog.toAssistantSnapshot(
    manifestsByPackageId: Map<String, VerifiedManifestDocument>,
): AssistantCatalogSnapshot {
    val recipes = catalog.runtimeRecipes.associateBy { it.recipeId }
    val activePackages = catalog.packages.filter { it.active }.mapTo(hashSetOf()) { it.packageId }
    val objectTargetsByPackageId = manifestsByPackageId.mapValues { (packageId, document) ->
        require(document.catalogEntry.packageId == packageId) {
            "verified object Manifest identity differs from its package key"
        }
        require(document.manifest.runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1) {
            "non-object Manifest supplied to object target snapshot"
        }
        requireNotNull(document.manifest.adapterContract.classMap).targets
    }
    val profiles = catalog.operationalCapabilities
        .asSequence()
        .flatMap { capability ->
            val recipe = recipes[capability.recipeId] ?: return@flatMap emptySequence()
            val kind = when {
                recipe.runtimeFamily == RecipeFamily.SIMILARITY_MATCH_V1 &&
                    TargetMode.REFERENCE_IMAGES in recipe.promptModes ->
                    AssistantProposalKind.REFERENCE_IMAGES
                recipe.runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1 &&
                    TargetMode.OBJECT_CLASS in recipe.promptModes ->
                    AssistantProposalKind.VISUAL_DESCRIPTION
                recipe.runtimeFamily == RecipeFamily.READING_PIPELINE_V1 &&
                    TargetMode.NONE in recipe.promptModes ->
                    AssistantProposalKind.STRUCTURED_READING
                else -> return@flatMap emptySequence()
            }
            val availableObjectTargets = if (kind == AssistantProposalKind.VISUAL_DESCRIPTION) {
                availableObjectPackageTargets(
                    authorizedTargetIds = capability.targetIds,
                    requestedPackageIds = capability.packageIds,
                    targetsByPackageId = objectTargetsByPackageId,
                )
            } else {
                emptyMap()
            }
            capability.packageIds.asSequence()
                .filter(activePackages::contains)
                .filter { packageId ->
                    kind != AssistantProposalKind.VISUAL_DESCRIPTION ||
                        availableObjectTargets.containsKey(packageId)
                }
                .map { packageId ->
                    val targets = if (kind == AssistantProposalKind.VISUAL_DESCRIPTION) {
                        availableObjectTargets.getValue(packageId)
                            .map(ClassMapTarget::toAssistantTarget)
                    } else {
                        emptyList()
                    }
                    AssistantModelProfile(
                        modelProfileKey = capability.capabilityKey,
                        packageId = packageId,
                        kind = kind,
                        intentPatterns = capability.intentPatterns.sorted(),
                        applicableScenarios = capability.applicableScenarios.sorted(),
                        inapplicableScenarios = capability.inapplicableScenarios.sorted(),
                        inputRequirements = capability.inputRequirements.sorted(),
                        targets = targets.sortedBy(AssistantTargetDescriptor::targetId),
                        displayName = capability.modelCard.modelName,
                    )
                }
        }
        .sortedWith(compareBy(AssistantModelProfile::modelProfileKey, AssistantModelProfile::packageId))
        .toList()
    if (profiles.isEmpty()) {
        throw AssistantCatalogUnavailableException(
            AssistantCatalogUnavailableException.Reason.NO_ACTIVE_PROFILE,
        )
    }
    val exactTargets = profiles
        .filter { it.kind == AssistantProposalKind.VISUAL_DESCRIPTION }
        .flatMap(AssistantModelProfile::targets)
        .mapTo(linkedSetOf(), AssistantTargetDescriptor::targetId)
    return AssistantCatalogSnapshot(
        binding = AssistantCatalogBinding(
            catalogId = catalog.catalogId,
            catalogVersion = catalog.catalogVersion,
            catalogSignedPayloadSha256 = signedPayloadSha256,
        ),
        modelProfiles = profiles,
        currentExactObjectTargetIds = exactTargets.toSet(),
    ).also { it.objectClassDefinitions() }
}

/** Missing package metadata removes only that package; available targets may never widen Catalog. */
internal fun availableObjectPackageTargets(
    authorizedTargetIds: Set<String>,
    requestedPackageIds: Set<String>,
    targetsByPackageId: Map<String, List<ClassMapTarget>>,
): Map<String, List<ClassMapTarget>> = buildMap {
    requestedPackageIds.forEach { packageId ->
        val targets = targetsByPackageId[packageId] ?: return@forEach
        require(targets.isNotEmpty()) { "verified object Manifest has no class-map targets" }
        val targetIds = targets.mapTo(linkedSetOf(), ClassMapTarget::targetId)
        require(authorizedTargetIds.containsAll(targetIds)) {
            "signed object Manifest targets exceed Catalog authorization"
        }
        put(packageId, targets)
    }
}

private fun AssistantCatalogBinding.toJson(): JsonObject = JsonObject().apply {
    addProperty("catalog_id", catalogId)
    addProperty("catalog_version", catalogVersion)
    addProperty("catalog_signed_payload_sha256", catalogSignedPayloadSha256)
}

private fun ClassMapTarget.toAssistantTarget(): AssistantTargetDescriptor =
    AssistantTargetDescriptor(
        targetId = targetId,
        labelZhCn = labelZhCn,
        labelEn = labelEn,
        aliases = aliases.sorted(),
        labels = labels,
    )

private fun AssistantTargetDescriptor.toJson(): JsonObject = JsonObject().apply {
    addProperty("target_id", targetId)
    addProperty("label_zh_cn", labelZhCn)
    addProperty("label_en", labelEn)
    if (labels.isNotEmpty()) add("labels", labels.toJsonObject())
    add("aliases", aliases.toJsonArray())
}

private fun Map<String, String>.toJsonObject(): JsonObject = JsonObject().also { objectValue ->
    forEach { (key, value) -> objectValue.addProperty(key, value) }
}

private fun List<String>.toJsonArray(): JsonArray = JsonArray().also { array ->
    forEach(array::add)
}

private fun JsonObject.exact(vararg keys: String): JsonObject {
    require(keySet() == keys.toSet()) { "assistant JSON contains missing or unknown fields" }
    return this
}

private fun JsonObject.requiredObject(name: String): JsonObject {
    val value = get(name) ?: error("missing $name")
    require(value.isJsonObject) { "$name must be an object" }
    return value.asJsonObject
}

private fun JsonObject.requiredString(name: String): String {
    val value = get(name) ?: error("missing $name")
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
    return value.asString
}

private fun JsonObject.requiredInt(name: String): Int {
    val value = get(name) ?: error("missing $name")
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
    val raw = value.toString()
    require(Regex("^(?:0|[1-9][0-9]*)$").matches(raw)) { "$name must be an integer" }
    return raw.toInt()
}

private fun readBounded(input: InputStream, maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(8_192)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > maximumBytes) throw IOException("assistant response exceeds the size limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private fun requireAssistantEndpoint(value: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(
        uri != null &&
            uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawPath == MONITOR_ASSISTANT_PATH,
    ) { "invalid monitor assistant endpoint" }
}
