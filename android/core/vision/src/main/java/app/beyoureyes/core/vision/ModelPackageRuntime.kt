package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.supportedLocaleForTag
import app.beyoureyes.core.domain.ReadingTemporalEvidenceTracker
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.MAX_REFERENCE_IMAGES as PRODUCT_MAX_REFERENCE_IMAGES
import app.beyoureyes.core.domain.MIN_REFERENCE_IMAGES as PRODUCT_MIN_REFERENCE_IMAGES
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

private val SUPPORTED_LOCALE_TAGS = setOf(
    "en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de", "pt-BR",
)

/** The small, versioned set of runtime contracts understood by the Android application. */
enum class RecipeFamily(val wireValue: String) {
    OBJECT_DETECTION_V1("object_detection_v1"),
    SIMILARITY_MATCH_V1("similarity_match_v1"),
    READING_PIPELINE_V1("reading_pipeline_v1");

    companion object {
        fun fromWireValue(value: String): RecipeFamily? = entries.singleOrNull {
            it.wireValue == value
        }
    }
}

enum class TargetMode(val wireValue: String) {
    OBJECT_CLASS("object_class"),
    REFERENCE_IMAGES("reference_images"),
    NONE("none");

    companion object {
        fun fromWireValue(value: String): TargetMode? = entries.singleOrNull {
            it.wireValue == value
        }
    }
}

/**
 * No image bytes or content URIs enter the shared task contract. A package-specific target
 * encoder resolves these app-private asset IDs only while creating the local runtime.
 */
data class ReferenceImageMetadata(
    val referenceId: String,
    val localAssetId: String,
    val contentSha256: String,
    val width: Int,
    val height: Int,
) {
    init {
        require(referenceId.isNotBlank()) { "referenceId must not be blank" }
        require(localAssetId.isNotBlank()) { "localAssetId must not be blank" }
        require(SHA_256.matches(contentSha256)) { "contentSha256 must be lowercase SHA-256" }
        require(width > 0 && height > 0) { "reference image dimensions must be positive" }
    }
}

/** User intent only. It contains no model ID, threshold, tensor name, or backend parameter. */
sealed interface TargetProfile {
    val targetId: String
    val mode: TargetMode

    data class ObjectClass(
        override val targetId: String,
        val labelZhCn: String,
        val labelEn: String,
        val labels: Map<String, String> = emptyMap(),
    ) : TargetProfile {
        override val mode: TargetMode = TargetMode.OBJECT_CLASS

        init {
            requireValidTargetId(targetId)
            require(labelZhCn.isNotBlank() && labelZhCn.length <= 40)
            require(labelEn.isNotBlank() && labelEn.length <= 40)
            require(labels.keys.all { it in SUPPORTED_LOCALE_TAGS })
            require(labels.values.all { it.isNotBlank() && it.length <= 40 })
        }

        fun localizedLabel(languageTag: String): String {
            val locale = supportedLocaleForTag(languageTag)
            return labels[locale]
                ?: if (locale == "zh-Hans" || locale == "zh-Hant") labelZhCn else labelEn
        }
    }

    data class ReferenceImages(
        override val targetId: String,
        val images: List<ReferenceImageMetadata>,
    ) : TargetProfile {
        override val mode: TargetMode = TargetMode.REFERENCE_IMAGES

        init {
            requireValidTargetId(targetId)
            require(images.size in MIN_REFERENCE_IMAGES..MAX_REFERENCE_IMAGES) {
                "reference images must contain $MIN_REFERENCE_IMAGES..$MAX_REFERENCE_IMAGES items"
            }
            require(images.map { it.referenceId }.distinct().size == images.size) {
                "referenceId values must be unique"
            }
            require(images.map { it.localAssetId }.distinct().size == images.size) {
                "localAssetId values must be unique"
            }
            require(images.map { it.contentSha256 }.distinct().size == images.size) {
                "duplicate reference image content is not accepted"
            }
        }
    }

    companion object {
        const val MIN_REFERENCE_IMAGES = PRODUCT_MIN_REFERENCE_IMAGES
        const val MAX_REFERENCE_IMAGES = PRODUCT_MAX_REFERENCE_IMAGES
        const val MAX_DESCRIPTION_LENGTH = 200

        private fun requireValidTargetId(targetId: String) {
            require(TARGET_ID.matches(targetId)) {
                "targetId must match ${TARGET_ID.pattern}"
            }
        }
    }
}

data class ModelRuntimeRequest(
    val verifiedPackage: VerifiedModelPackage,
    val artifactFile: File,
    val artifactFilesByRole: Map<String, File> = mapOf("primary" to artifactFile),
    val buildChannel: BuildChannel,
    val targetProfile: TargetProfile? = null,
    val samplingPolicyOverride: FrameSamplingPolicy? = null,
    val confirmedReadingFormat: ConfirmedReadingFormat? = null,
    val manualReadingScanRegion: NormalizedRect? = null,
    /** Known-answer startup checks use signed synthetic pixels, not live scene geometry. */
    val enforceAutomaticShapeGuard: Boolean = true,
    val numberOfThreads: Int = DEFAULT_THREAD_COUNT,
) {
    companion object {
        /** PJA110 whole-pipeline sweep: five workers outperformed 2/3/4/6/8 without changing output. */
        const val DEFAULT_THREAD_COUNT = 5
    }
}

enum class RuntimeActivationError {
    MANIFEST_INVALID,
    CATALOG_ENTRY_INACTIVE,
    CATALOG_SIGNATURE_INVALID,
    CATALOG_MANIFEST_HASH_MISMATCH,
    MANIFEST_SIGNATURE_INVALID,
    ARTIFACT_HASH_INVALID,
    LICENSE_TEXT_HASH_INVALID,
    LICENSE_NOT_APPROVED,
    COMMERCIAL_USE_NOT_ALLOWED,
    REDISTRIBUTION_NOT_ALLOWED,
    SOURCE_DISCLOSURE_REQUIRED,
    LICENSE_GRANT_EXPIRED,
    DEVELOPMENT_CHANNEL_HAS_NO_MODEL,
    CAPABILITY_FAMILY_MISMATCH,
    TARGET_MODE_NOT_SUPPORTED,
    ANALYSIS_INTERVAL_NOT_CONFIGURED,
    ANALYSIS_INTERVAL_OVERRIDE_OUT_OF_RANGE,
    ANALYSIS_INTERVAL_ADAPTATION_NOT_ALLOWED,
    OUTPUT_CONTRACT_MISMATCH,
    RUNTIME_KIND_NOT_SUPPORTED,
    COMPOSITE_PACKAGE_NOT_SUPPORTED,
    PREPROCESSOR_NOT_SUPPORTED,
    ADAPTER_NOT_SUPPORTED,
    TARGET_ENCODER_NOT_SUPPORTED,
    BACKEND_NOT_SUPPORTED,
    THREAD_COUNT_OUT_OF_RANGE,
    ARTIFACT_FILE_MISSING,
    ARTIFACT_SIZE_MISMATCH,
    ARTIFACT_SHA256_MISMATCH,
    ARTIFACT_READ_FAILED,
    ARTIFACT_ROLE_SET_MISMATCH,
    COMPONENT_CREATION_FAILED,
    KNOWN_ANSWER_SELF_TEST_FAILED,
}

sealed interface RuntimeCreationResult {
    data class Ready(val runtime: ModelPackageRuntime) : RuntimeCreationResult

    data class Unavailable(
        val errors: Set<RuntimeActivationError>,
    ) : RuntimeCreationResult {
        init {
            require(errors.isNotEmpty())
        }
    }
}

data class RuntimeComponentContext(
    val manifest: ModelPackageManifest,
    val targetProfile: TargetProfile?,
    val artifactFile: File,
    val numberOfThreads: Int,
    val artifactFilesByRole: Map<String, File> = mapOf("primary" to artifactFile),
)

fun interface ManifestPreprocessorFactory {
    fun create(context: RuntimeComponentContext): InputPreprocessor
}

fun interface ManifestOutputAdapterFactory {
    fun create(context: RuntimeComponentContext): OutputAdapter
}

fun interface ManifestBackendFactory {
    fun create(context: RuntimeComponentContext): InferenceBackend
}

data class PreprocessorRegistration(
    val preprocessId: String,
    val recipeFamilies: Set<RecipeFamily>,
    val factory: ManifestPreprocessorFactory,
    val additionalExternalInputRoles: Set<InputRole> = emptySet(),
    val externalTensorProviderFactory: ManifestExternalTensorProviderFactory? = null,
)

data class AdapterRegistration(
    val adapterId: String,
    val recipeFamilies: Set<RecipeFamily>,
    val outputSchemaIds: Set<String>,
    val factory: ManifestOutputAdapterFactory,
)

data class BackendRegistration(
    val runtimeKind: RuntimeKind,
    val recipeFamilies: Set<RecipeFamily>,
    val targetModes: Set<TargetMode>,
    val factory: ManifestBackendFactory,
)

data class TargetTensorProviderRegistration(
    val providerId: String,
    val recipeFamilies: Set<RecipeFamily>,
    val targetModes: Set<TargetMode>,
    val externalInputRoles: Set<InputRole>,
    val factory: ManifestExternalTensorProviderFactory,
)

/**
 * Immutable exact-ID registry. There is no package ID switch and no reflection fallback: an
 * unknown family/preprocess/adapter combination stays unavailable until a reusable family is
 * deliberately implemented and tested.
 */
class RuntimeComponentRegistry(
    preprocessors: Collection<PreprocessorRegistration>,
    adapters: Collection<AdapterRegistration>,
    backends: Collection<BackendRegistration>,
    artifactSessions: Collection<ArtifactSessionRegistration> = emptyList(),
    targetTensorProviders: Collection<TargetTensorProviderRegistration> = emptyList(),
) {
    private val preprocessorsById = preprocessors.associateUniqueBy(
        PreprocessorRegistration::preprocessId,
        "preprocessId",
    )
    private val adaptersById = adapters.associateUniqueBy(
        AdapterRegistration::adapterId,
        "adapterId",
    )
    private val backendsByKind = backends.associateUniqueBy(
        BackendRegistration::runtimeKind,
        "runtimeKind",
    )
    private val artifactSessionsByKind = artifactSessions.associateUniqueBy(
        ArtifactSessionRegistration::runtimeKind,
        "artifactSessionRuntimeKind",
    )
    private val targetTensorProvidersById = targetTensorProviders.associateUniqueBy(
        TargetTensorProviderRegistration::providerId,
        "targetTensorProviderId",
    )

    init {
        preprocessors.forEach { registration ->
            require(
                registration.additionalExternalInputRoles.isEmpty() ==
                    (registration.externalTensorProviderFactory == null),
            ) { "external input roles and provider factory must be declared together" }
        }
    }

    internal fun preprocessor(id: String): PreprocessorRegistration? = preprocessorsById[id]
    internal fun adapter(id: String): AdapterRegistration? = adaptersById[id]
    internal fun backend(kind: RuntimeKind): BackendRegistration? = backendsByKind[kind]
    internal fun artifactSession(kind: RuntimeKind): ArtifactSessionRegistration? =
        artifactSessionsByKind[kind]
    internal fun targetTensorProvider(id: String): TargetTensorProviderRegistration? =
        targetTensorProvidersById[id]

    private fun <T, K> Collection<T>.associateUniqueBy(
        key: (T) -> K,
        field: String,
    ): Map<K, T> {
        val values = associateBy(key)
        require(values.size == size) { "duplicate $field registration" }
        return values.toSortedMap(compareBy { it.toString() })
    }
}

sealed interface RuntimeFrameResult {
    val sourceSequence: Long

    data class Skipped(override val sourceSequence: Long) : RuntimeFrameResult

    data class Processed(val pipelineResult: PipelineResult) : RuntimeFrameResult {
        override val sourceSequence: Long = pipelineResult.sourceSequence
    }
}

/** A configured package instance. Every family exposes one branch of the closed Observation type. */
class ModelPackageRuntime internal constructor(
    val packageId: String,
    val packageVersion: String,
    val runtimeFamily: RecipeFamily,
    val targetProfile: TargetProfile?,
    samplingPolicy: FrameSamplingPolicy,
    private val signedSamplingPolicy: SamplingPolicySpec = SamplingPolicySpec(
        defaultIntervalMillis = samplingPolicy.analysisIntervalMillis,
        minimumIntervalMillis = FrameSamplingPolicy.MIN_INTERVAL_MILLIS,
        maximumIntervalMillis = FrameSamplingPolicy.MAX_INTERVAL_MILLIS,
        adaptiveAllowed = true,
    ),
    private val pipeline: VisionPipeline,
    private val readingEvidenceTracker: ReadingTemporalEvidenceTracker?,
    private val confirmedReadingFormat: ConfirmedReadingFormat?,
    closeables: List<AutoCloseable>,
) : AutoCloseable {
    var samplingPolicy: FrameSamplingPolicy = samplingPolicy
        private set
    private val sampler = MonotonicFrameSampler(samplingPolicy)
    private val closeables = closeables.distinctBy(System::identityHashCode)
    private val readingScanController = pipeline as? ReadingScanController
    private var closed = false

    @Synchronized
    fun process(frame: SourceFrame): RuntimeFrameResult {
        check(!closed) { "runtime is closed" }
        if (!sampler.shouldProcess(frame.monotonicTimeMillis)) {
            return RuntimeFrameResult.Skipped(frame.sourceSequence)
        }
        val processed = pipeline.process(frame)
        val normalized = when {
            processed.observation is Observation.Unavailable -> processed
            runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1 &&
                processed.observation is Observation.Detections -> {
                processed
            }
            runtimeFamily == RecipeFamily.SIMILARITY_MATCH_V1 &&
                processed.observation is Observation.State -> processed
            runtimeFamily == RecipeFamily.READING_PIPELINE_V1 &&
                processed.observation is Observation.Reading -> processed
            else -> processed.copy(
                observation = Observation.Unavailable(
                    reason = UnavailableReason.INCOMPATIBLE_OUTPUT,
                    diagnosticCode = "recipe_output_mismatch",
                    sourceSequence = frame.sourceSequence,
                ),
            )
        }
        val formatted = if (runtimeFamily == RecipeFamily.READING_PIPELINE_V1) {
            normalized.copy(
                observation = normalized.observation.applyConfirmedReadingFormat(
                    confirmedReadingFormat,
                ),
            )
        } else {
            normalized
        }
        val stabilized = if (runtimeFamily == RecipeFamily.READING_PIPELINE_V1) {
            formatted.copy(
                observation = checkNotNull(readingEvidenceTracker).accept(
                    observation = formatted.observation,
                    monotonicMillis = formatted.monotonicTimeMillis,
                    processingLatencyMillis = formatted.timings.totalNanos.toCeilingMillis(),
                ),
            )
        } else {
            formatted
        }
        return RuntimeFrameResult.Processed(stabilized)
    }

    @Synchronized
    fun updateManualReadingScanRegion(region: NormalizedRect?) {
        check(!closed) { "runtime is closed" }
        readingScanController?.updateManualScanRegion(region) ?: return
        readingEvidenceTracker?.reset()
    }

    @Synchronized
    fun resetAutomaticReadingAnchor() {
        check(!closed) { "runtime is closed" }
        readingScanController?.resetAutomaticAnchor() ?: return
        readingEvidenceTracker?.reset()
    }

    @Synchronized
    fun resetSampling() {
        check(!closed) { "runtime is closed" }
        sampler.reset()
        readingEvidenceTracker?.reset()
    }

    /** Generic cadence update; signed bounds/adaptive policy are enforced before this seam. */
    @Synchronized
    fun updateSamplingPolicy(policy: FrameSamplingPolicy) {
        check(!closed) { "runtime is closed" }
        val minimum = checkNotNull(signedSamplingPolicy.minimumIntervalMillis)
        val maximum = checkNotNull(signedSamplingPolicy.maximumIntervalMillis)
        require(signedSamplingPolicy.adaptiveAllowed) {
            "signed Manifest does not allow runtime sampling adaptation"
        }
        require(policy.analysisIntervalMillis in minimum..maximum) {
            "runtime sampling update is outside signed Manifest bounds"
        }
        samplingPolicy = policy
        sampler.updatePolicy(policy)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closeables.asReversed().forEach { closeable -> runCatching { closeable.close() } }
    }
}

class ModelPackageRuntimeFactory(
    private val registry: RuntimeComponentRegistry,
    private val normalizer: FrameNormalizer,
    private val qualityGate: QualityGate,
    private val clock: NanoClock = SystemNanoClock,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun create(request: ModelRuntimeRequest): RuntimeCreationResult {
        val errors = validate(request)
        if (errors.isNotEmpty()) {
            return RuntimeCreationResult.Unavailable(errors)
        }

        val manifest = request.verifiedPackage.manifest
        val preprocessorRegistration = checkNotNull(registry.preprocessor(manifest.preprocessId))
        val adapterRegistration = checkNotNull(registry.adapter(manifest.adapterId))
        val context = RuntimeComponentContext(
            manifest = manifest,
            targetProfile = request.targetProfile,
            artifactFile = request.artifactFile,
            artifactFilesByRole = request.artifactFilesByRole,
            numberOfThreads = request.numberOfThreads,
        )
        val owned = mutableListOf<AutoCloseable>()
        val pipeline = try {
            val preprocessor = preprocessorRegistration.factory.create(context).also {
                (it as? AutoCloseable)?.let(owned::add)
            }
            val externalTensorProvider = preprocessorRegistration.externalTensorProviderFactory?.create(context)
                ?: RejectAdditionalExternalTensors
            (externalTensorProvider as? AutoCloseable)?.let(owned::add)
            val adapter = adapterRegistration.factory.create(context).also {
                (it as? AutoCloseable)?.let(owned::add)
            }
            if (manifest.runtimeFamily == RecipeFamily.READING_PIPELINE_V1) {
                val locatorArtifact = manifest.artifacts.single { it.role == "locator" }
                val locatorInput = manifest.inputs.single {
                    it.artifactRole == locatorArtifact.role && it.role == InputRole.IMAGE
                }
                val locatorOutput = manifest.outputs.single {
                    it.artifactRole == locatorArtifact.role &&
                        it.role == OutputRole.TEXT_PROBABILITY_MAP
                }
                val recognizerArtifact = manifest.primaryArtifact
                val recognizerInput = manifest.primaryImageInput
                val locatorSession = checkNotNull(registry.artifactSession(locatorArtifact.runtime))
                    .factory.create(
                        ArtifactSessionContext(
                            artifact = locatorArtifact,
                            artifactFile = checkNotNull(
                                request.artifactFilesByRole[locatorArtifact.role],
                            ),
                            inputSpecs = listOf(locatorInput),
                            outputSpecs = listOf(locatorOutput),
                            numberOfThreads = request.numberOfThreads,
                        ),
                    ).also(owned::add)
                val recognizerSession = checkNotNull(
                    registry.artifactSession(recognizerArtifact.runtime),
                ).factory.create(
                    ArtifactSessionContext(
                        artifact = recognizerArtifact,
                        artifactFile = checkNotNull(
                            request.artifactFilesByRole[recognizerArtifact.role],
                        ),
                        inputSpecs = manifest.inputs.filter {
                            it.artifactRole == recognizerArtifact.role
                        },
                        outputSpecs = manifest.outputs.filter {
                            it.artifactRole == recognizerArtifact.role
                        },
                        numberOfThreads = request.numberOfThreads,
                    ),
                ).also(owned::add)
                ReadingScenePipeline(
                    normalizer = normalizer,
                    qualityGate = qualityGate,
                    detectorInput = locatorInput,
                    detectorOutput = locatorOutput,
                    detectorPreprocessor = PpOcrDetectionPreprocessor(locatorInput),
                    detectorSession = locatorSession,
                    recognizerInput = recognizerInput,
                    recognizerPreprocessor = preprocessor,
                    recognizerSession = recognizerSession,
                    recognizerAdapter = adapter,
                    pixelThreshold = manifest.parameterProfile.defaults.getValue(
                        READING_LOCATOR_PIXEL_THRESHOLD_PARAMETER,
                    ).toFloat(),
                    boxThreshold = manifest.parameterProfile.defaults.getValue(
                        READING_LOCATOR_BOX_THRESHOLD_PARAMETER,
                    ).toFloat(),
                    unclipRatio = manifest.parameterProfile.defaults.getValue(
                        READING_LOCATOR_UNCLIP_RATIO_PARAMETER,
                    ).toFloat(),
                    maximumCandidates = manifest.parameterProfile.defaults.getValue(
                        READING_LOCATOR_MAX_CANDIDATES_PARAMETER,
                    ).toInt(),
                    initialManualScanRegion = request.manualReadingScanRegion,
                    enforceAutomaticShapeGuard = request.enforceAutomaticShapeGuard,
                    clock = clock,
                )
            } else if (manifest.usesExecutionGraph()) {
                val sessions = mutableListOf<ArtifactExecutionSession>()
                try {
                    manifest.artifacts.filterNot(ArtifactComponent::isStaticSidecar)
                        .sortedBy(ArtifactComponent::role).forEach { artifact ->
                        val registration = checkNotNull(registry.artifactSession(artifact.runtime))
                        sessions += registration.factory.create(
                            ArtifactSessionContext(
                                artifact = artifact,
                                artifactFile = checkNotNull(request.artifactFilesByRole[artifact.role]),
                                inputSpecs = manifest.inputs.filter { it.artifactRole == artifact.role },
                                outputSpecs = manifest.outputs.filter { it.artifactRole == artifact.role },
                                numberOfThreads = request.numberOfThreads,
                            ),
                        )
                    }
                    val graph = ManifestExecutionGraph(manifest, sessions).also(owned::add)
                    val targetTensors = externalTensorProvider.provide(
                        ExternalTensorContext(
                            manifest = manifest,
                            requiredInputs = graph.requiredTargetInputs,
                            targetProfile = request.targetProfile,
                        ),
                    )
                    graph.prepareTarget(targetTensors)
                    GraphObservationPipeline(
                        manifest = manifest,
                        targetProfile = request.targetProfile,
                        normalizer = normalizer,
                        qualityGate = qualityGate,
                        primaryImagePreprocessor = preprocessor,
                        graph = graph,
                        adapter = adapter,
                        clock = clock,
                    )
                } catch (error: Exception) {
                    sessions.asReversed().forEach { runCatching { it.close() } }
                    throw error
                }
            } else {
                val backendRegistration = checkNotNull(registry.backend(manifest.primaryArtifact.runtime))
                val backend = backendRegistration.factory.create(context).also {
                    (it as? AutoCloseable)?.let(owned::add)
                }
                ObservationPipeline(
                    normalizer = normalizer,
                    qualityGate = qualityGate,
                    preprocessor = preprocessor,
                    backend = backend,
                    adapter = adapter,
                    clock = clock,
                )
            }
        } catch (error: Exception) {
            owned.asReversed().distinctBy(System::identityHashCode).forEach {
                runCatching { it.close() }
            }
            return RuntimeCreationResult.Unavailable(
                setOf(RuntimeActivationError.COMPONENT_CREATION_FAILED),
            )
        }
        val samplingPolicy = request.samplingPolicyOverride ?: FrameSamplingPolicy(
            checkNotNull(manifest.parameterProfile.samplingPolicy.defaultIntervalMillis),
        )
        return RuntimeCreationResult.Ready(
            ModelPackageRuntime(
                packageId = manifest.packageId,
                packageVersion = manifest.packageVersion,
                runtimeFamily = manifest.runtimeFamily,
                targetProfile = request.targetProfile,
                samplingPolicy = samplingPolicy,
                signedSamplingPolicy = manifest.parameterProfile.samplingPolicy,
                pipeline = pipeline,
                readingEvidenceTracker = if (manifest.runtimeFamily == RecipeFamily.READING_PIPELINE_V1) {
                    ReadingTemporalEvidenceTracker()
                } else {
                    null
                },
                confirmedReadingFormat = request.confirmedReadingFormat,
                closeables = owned,
            ),
        )
    }

    private fun validate(request: ModelRuntimeRequest): Set<RuntimeActivationError> = buildSet {
        val verified = request.verifiedPackage
        val manifest = verified.manifest
        val family = manifest.runtimeFamily
        val targetMode = request.targetProfile?.mode ?: TargetMode.NONE

        if (manifest.structuralErrors().isNotEmpty()) add(RuntimeActivationError.MANIFEST_INVALID)
        if (!verified.catalogEntryActive) add(RuntimeActivationError.CATALOG_ENTRY_INACTIVE)
        if (!verified.catalogSignatureValid) add(RuntimeActivationError.CATALOG_SIGNATURE_INVALID)
        if (!verified.catalogManifestSha256Matches) {
            add(RuntimeActivationError.CATALOG_MANIFEST_HASH_MISMATCH)
        }
        if (!verified.manifestSignatureValid) add(RuntimeActivationError.MANIFEST_SIGNATURE_INVALID)
        if (!verified.artifactSha256Valid) add(RuntimeActivationError.ARTIFACT_HASH_INVALID)
        if (!verified.licenseTextSha256Valid) {
            add(RuntimeActivationError.LICENSE_TEXT_HASH_INVALID)
        }

        when (request.buildChannel) {
            BuildChannel.COMMUNITY, BuildChannel.COMMERCIAL -> {
                if (manifest.commercialIoErrors().isNotEmpty()) {
                    add(RuntimeActivationError.MANIFEST_INVALID)
                }
                if (manifest.license.reviewStatus != LicenseReviewStatus.APPROVED) {
                    add(RuntimeActivationError.LICENSE_NOT_APPROVED)
                }
                if (!manifest.license.commercialUseAllowed) {
                    add(RuntimeActivationError.COMMERCIAL_USE_NOT_ALLOWED)
                }
                if (!manifest.license.redistributionAllowed) {
                    add(RuntimeActivationError.REDISTRIBUTION_NOT_ALLOWED)
                }
                if (manifest.license.sourceDisclosureRequired) {
                    add(RuntimeActivationError.SOURCE_DISCLOSURE_REQUIRED)
                }
            }
            BuildChannel.INTERNAL_EVALUATION -> {
                if (manifest.license.reviewStatus != LicenseReviewStatus.APPROVED) {
                    add(RuntimeActivationError.LICENSE_NOT_APPROVED)
                }
            }
            BuildChannel.DEVELOPMENT_NO_MODEL -> {
                add(RuntimeActivationError.DEVELOPMENT_CHANNEL_HAS_NO_MODEL)
            }
        }
        manifest.license.grantExpiresAt?.let { value ->
            val expiry = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
            if (expiry == null || expiry <= nowEpochMillis()) {
                add(RuntimeActivationError.LICENSE_GRANT_EXPIRED)
            }
        }

        if (targetMode !in manifest.promptModes) {
            add(RuntimeActivationError.TARGET_MODE_NOT_SUPPORTED)
        }
        val interval = request.samplingPolicyOverride?.analysisIntervalMillis
            ?: manifest.parameterProfile.samplingPolicy.defaultIntervalMillis
        val minimumInterval = manifest.parameterProfile.samplingPolicy.minimumIntervalMillis
        val maximumInterval = manifest.parameterProfile.samplingPolicy.maximumIntervalMillis
        if (interval == null) {
            add(RuntimeActivationError.ANALYSIS_INTERVAL_NOT_CONFIGURED)
        } else if (minimumInterval == null || maximumInterval == null ||
            interval !in minimumInterval..maximumInterval
        ) {
            add(RuntimeActivationError.ANALYSIS_INTERVAL_OVERRIDE_OUT_OF_RANGE)
        }
        if (request.samplingPolicyOverride != null &&
            !manifest.parameterProfile.samplingPolicy.adaptiveAllowed &&
            interval != manifest.parameterProfile.samplingPolicy.defaultIntervalMillis
        ) {
            add(RuntimeActivationError.ANALYSIS_INTERVAL_ADAPTATION_NOT_ALLOWED)
        }
        if (!outputContractMatches(manifest, family)) {
            add(RuntimeActivationError.OUTPUT_CONTRACT_MISMATCH)
        }

        val preprocessor = registry.preprocessor(manifest.preprocessId)
        if (preprocessor == null || family !in preprocessor.recipeFamilies) {
            add(RuntimeActivationError.PREPROCESSOR_NOT_SUPPORTED)
        } else if (manifest.usesExecutionGraph()) {
            val primaryInput = manifest.inputs.singleOrNull {
                it.role == InputRole.IMAGE && it.artifactRole == "primary"
            }
            val boundTargets = manifest.bindings.mapTo(hashSetOf(), TensorBinding::targetEndpoint)
            val additionalExternalRoles = manifest.inputs
                .filter { input ->
                    input != primaryInput && input.role != InputRole.IMAGE &&
                        input.endpoint !in boundTargets
                }
                .mapTo(linkedSetOf(), InputComponent::role)
            if (!preprocessor.additionalExternalInputRoles.containsAll(additionalExternalRoles)) {
                add(RuntimeActivationError.PREPROCESSOR_NOT_SUPPORTED)
            }
        }
        val adapter = registry.adapter(manifest.adapterId)
        if (adapter == null || family !in adapter.recipeFamilies ||
            manifest.adapterContract.schemaId !in adapter.outputSchemaIds
        ) {
            add(RuntimeActivationError.ADAPTER_NOT_SUPPORTED)
        }
        if (manifest.usesExecutionGraph()) {
            if (manifest.artifacts.filterNot(ArtifactComponent::isStaticSidecar)
                    .any { registry.artifactSession(it.runtime) == null }
            ) {
                add(RuntimeActivationError.BACKEND_NOT_SUPPORTED)
                add(RuntimeActivationError.RUNTIME_KIND_NOT_SUPPORTED)
            }
        } else {
            val backend = registry.backend(manifest.primaryArtifact.runtime)
            if (backend == null || family !in backend.recipeFamilies ||
                targetMode !in backend.targetModes
            ) {
                add(RuntimeActivationError.BACKEND_NOT_SUPPORTED)
            }
        }
        if (request.numberOfThreads !in MIN_THREAD_COUNT..MAX_THREAD_COUNT) {
            add(RuntimeActivationError.THREAD_COUNT_OUT_OF_RANGE)
        }

        val expectedRoles = manifest.artifacts.map(ArtifactComponent::role).toSet()
        if (request.artifactFilesByRole.keys != expectedRoles ||
            request.artifactFilesByRole["primary"] != request.artifactFile
        ) {
            add(RuntimeActivationError.ARTIFACT_ROLE_SET_MISMATCH)
        }
        manifest.artifacts.forEach { artifact ->
            val file = request.artifactFilesByRole[artifact.role]
            if (file != null) addAll(validateArtifact(file, artifact))
        }
    }

    private fun outputContractMatches(
        manifest: ModelPackageManifest,
        family: RecipeFamily,
    ): Boolean {
        val semantics = manifest.finalOutputs().map { requireNotNull(it.role.toFinalSemanticOrNull()) }.toSet()
        return when (family) {
            RecipeFamily.OBJECT_DETECTION_V1 ->
                semantics == DETECTION_SEMANTICS &&
                    manifest.adapterContract.schemaId == "object_detection_v1" &&
                    manifest.adapterContract.classMap != null
            RecipeFamily.SIMILARITY_MATCH_V1 ->
                semantics == SIMILARITY_SEMANTICS &&
                    manifest.adapterContract.schemaId == "similarity_match_v1" &&
                    manifest.adapterContract.classMap == null
            RecipeFamily.READING_PIPELINE_V1 ->
                semantics == READING_SEMANTICS &&
                    manifest.adapterContract.schemaId == "structured_reading_v2" &&
                    manifest.adapterContract.classMap == null
        }
    }

    private fun validateArtifact(
        file: File,
        artifact: ArtifactComponent,
    ): Set<RuntimeActivationError> = buildSet {
        if (!file.isFile) {
            add(RuntimeActivationError.ARTIFACT_FILE_MISSING)
            return@buildSet
        }
        if (file.length() != artifact.sizeBytes) {
            add(RuntimeActivationError.ARTIFACT_SIZE_MISMATCH)
        }
        val actualHash = runCatching { sha256(file) }.getOrElse {
            add(RuntimeActivationError.ARTIFACT_READ_FAILED)
            null
        }
        if (actualHash != null && actualHash != artifact.sha256) {
            add(RuntimeActivationError.ARTIFACT_SHA256_MISMATCH)
        }
    }

    // A single classic-vision artifact consumes declared target material while its finite backend
    // is created; tensor runtimes consume every additional root through the execution graph.
    private fun ModelPackageManifest.usesExecutionGraph(): Boolean =
        artifacts.count { !it.isStaticSidecar } > 1 || bindings.isNotEmpty() ||
            primaryArtifact.runtime == RuntimeKind.ONNX ||
            (primaryArtifact.runtime != RuntimeKind.CLASSIC_VISION && inputs.size > 1)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val MIN_THREAD_COUNT = 1
        const val MAX_THREAD_COUNT = 16
    }
}

private fun Long.toCeilingMillis(): Long =
    if (this == 0L) 0L else Math.addExact(this, 999_999L) / 1_000_000L

private fun Observation.applyConfirmedReadingFormat(
    profile: ConfirmedReadingFormat?,
): Observation {
    if (profile == null || this !is Observation.Reading) return this
    val formatted = profile.apply(text) ?: return Observation.Unavailable(
        reason = UnavailableReason.LOW_QUALITY,
        diagnosticCode = "confirmed_reading_format_rejected",
        sourceSequence = sourceSequence,
    )
    return copy(
        text = formatted.text,
        valueDecimal = formatted.valueDecimal,
        unit = formatted.format.unit,
        format = formatted.format,
    )
}

private val DETECTION_SEMANTICS = setOf(
    OutputTensorSemantic.DETECTION_BOXES,
    OutputTensorSemantic.DETECTION_CLASSES,
    OutputTensorSemantic.DETECTION_SCORES,
    OutputTensorSemantic.DETECTION_COUNT,
)

private val READING_SEMANTICS = setOf(OutputTensorSemantic.CTC_LOGITS)

private val SIMILARITY_SEMANTICS = setOf(OutputTensorSemantic.SIMILARITY_SCORES)

private val SHA_256 = Regex("^[0-9a-f]{64}$")
private val TARGET_ID = Regex("^[a-z0-9][a-z0-9_.-]{0,127}$")
