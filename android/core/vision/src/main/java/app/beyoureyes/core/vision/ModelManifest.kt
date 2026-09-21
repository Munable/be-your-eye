package app.beyoureyes.core.vision

import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.time.Instant
import java.util.Base64

private val SHA_256 = Regex("^[0-9a-f]{64}$")
private val SEMVER = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")
private val CANONICAL_READING_DECIMAL = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")
private const val PUBLISHED_PREBUILT_EXPORT_TOOL_NOT_DISCLOSED =
    "published-prebuilt-export-tool-not-disclosed"
private val UNKNOWN_EXPORT_TOOL_MARKER = Regex(
    "(?:^|[-./])(?:unknown|undisclosed|unspecified|unavailable|pending|tbd)(?:$|[-./])|" +
        "(?:^|[-./])not-(?:disclosed|available)(?:$|[-./])",
)
const val READING_MINIMUM_CONFIDENCE_PARAMETER = "minimum_confidence"
const val READING_LOCATOR_PIXEL_THRESHOLD_PARAMETER = "locator_pixel_threshold"
const val READING_LOCATOR_BOX_THRESHOLD_PARAMETER = "locator_box_threshold"
const val READING_LOCATOR_UNCLIP_RATIO_PARAMETER = "locator_unclip_ratio"
const val READING_LOCATOR_MAX_CANDIDATES_PARAMETER = "locator_max_candidates"

enum class BuildChannel(val wireValue: String) {
    COMMUNITY("community"),
    COMMERCIAL("commercial"),
    INTERNAL_EVALUATION("internal-evaluation"),
    DEVELOPMENT_NO_MODEL("development-no-model");

    companion object {
        fun fromWireValue(value: String): BuildChannel? = entries.singleOrNull {
            it.wireValue == value
        }
    }
}

enum class LicenseReviewStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    EXCLUDED_BY_LICENSE,
    WITHDRAWN,
}

enum class RuntimeKind { LITERT, ONNX, CLASSIC_VISION, CTC_VOCABULARY }
enum class ArtifactMediaType {
    LITERT,
    ONNX,
    CLASSIC_VISION_JSON,
    CTC_VOCABULARY_JSON,
}
enum class TensorDataType { UINT8, INT8, INT32, INT64, FLOAT16, FLOAT32 }
enum class TensorLayout { NHWC, NCHW }
enum class ColorSpace { RGB, BGR, GRAY }
enum class QuantizationMode { PER_TENSOR, NONE }

enum class SupportedTask(val wireValue: String) {
    VISUAL_TARGET("visual_target"),
    VISIBLE_STATE("visible_state"),
    STRUCTURED_READING("structured_reading");

    companion object {
        fun fromWireValue(value: String): SupportedTask? = entries.singleOrNull {
            it.wireValue == value
        }
    }
}

enum class OutputTensorSemantic {
    DETECTION_BOXES,
    DETECTION_CLASSES,
    DETECTION_SCORES,
    DETECTION_COUNT,
    CTC_LOGITS,
    SIMILARITY_SCORES,
}

data class SourceReference(
    val url: String,
    val version: String,
)

data class ModelPackageLicense(
    val licenseId: String,
    val licenseTextSha256: String,
    val reviewStatus: LicenseReviewStatus,
    val reviewedAt: String?,
    val reviewEvidenceRef: String?,
    val commercialUseAllowed: Boolean,
    val redistributionAllowed: Boolean,
    val sourceDisclosureRequired: Boolean,
    val licenseGrantId: String? = null,
    val grantExpiresAt: String? = null,
    val reviewEvidenceSha256: String? = null,
)

data class ArtifactComponent(
    val role: String,
    val runtime: RuntimeKind,
    val mediaType: ArtifactMediaType,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Signed index contract between one CTC logits tensor and its package vocabulary sidecar. */
data class CtcDecodingSpec(
    val vocabularyArtifactRole: String,
    val blankIndex: Int,
    val indexSemantics: CtcIndexSemantics,
    val collapseSemantics: CtcCollapseSemantics,
    val scoreSemantics: CtcScoreSemantics,
)

/** Signed package-level known answer; v1 binds the executable LiteRT/ONNX `primary` artifact. */
data class ReadingKnownAnswerSelfTest(
    val schemaId: String,
    val artifactRole: String,
    val artifactSha256: String,
    val input: ReadingKnownAnswerInput,
    val expected: ReadingKnownAnswerExpected,
)

data class ReadingKnownAnswerInput(
    val encoding: String,
    val width: Int,
    val height: Int,
    val rgb888Base64: String,
    val sha256: String,
)

data class ReadingKnownAnswerExpected(
    val text: String,
    val valueDecimal: String,
    val unit: String?,
    val minimumConfidence: Float,
)

enum class CtcIndexSemantics { ZERO_BASED_TOKEN_ORDER_V1 }
enum class CtcCollapseSemantics {
    CTC_GREEDY_ARGMAX_V1,
}
enum class CtcScoreSemantics {
    PROBABILITIES_V1,
    UNNORMALIZED_LOGITS_SOFTMAX_V1,
}

data class InputSpec(
    val tensorIndex: Int,
    val tensorName: String,
    val width: Int,
    val height: Int,
    val channels: Int,
    val dataType: TensorDataType,
    val layout: TensorLayout,
    val colorSpace: ColorSpace,
    val runtimeShape: List<Int>,
    val quantization: InputQuantization,
)

data class InputQuantization(
    val mode: QuantizationMode,
    val scale: Double? = null,
    val zeroPoint: Int? = null,
)

enum class InputRole {
    IMAGE,
    IMAGE_TENSOR,
    IMAGE_FEATURES,
    REFERENCE_IMAGES,
    REFERENCE_FEATURES,
    READING_TASK_SPEC,
    DETECTION_BOXES,
    DETECTION_CLASSES,
    DETECTION_SCORES,
    DETECTION_COUNT,
}

enum class OutputRole {
    IMAGE_TENSOR,
    IMAGE_FEATURES,
    REFERENCE_FEATURES,
    DETECTION_BOXES,
    DETECTION_CLASSES,
    DETECTION_SCORES,
    DETECTION_COUNT,
    CTC_LOGITS,
    SIMILARITY_SCORES,
    TEXT_PROBABILITY_MAP,
}

data class InputComponent(
    val role: InputRole,
    val artifactRole: String,
    val tensorIndex: Int,
    val tensorName: String,
    val dataType: TensorDataType,
    val runtimeShape: List<Int>,
    val quantization: InputQuantization,
    val layout: TensorLayout? = null,
    val colorSpace: ColorSpace? = null,
)

data class OutputComponent(
    val role: OutputRole,
    val artifactRole: String,
    val tensorIndex: Int,
    val tensorName: String,
    val dataType: TensorDataType,
    val runtimeShape: List<Int>,
)

data class TensorBinding(
    val sourceArtifactRole: String,
    val sourceTensorName: String,
    val targetArtifactRole: String,
    val targetTensorName: String,
)

data class OutputTensorSpec(
    val index: Int,
    val name: String,
    val semantic: OutputTensorSemantic,
    val dataType: TensorDataType,
    val runtimeShape: List<Int>,
)

data class ClassMapSpec(
    val identity: String,
    val sha256: String,
    val classIdBase: Int,
    val targets: List<ClassMapTarget> = emptyList(),
)

data class EmbeddedPostprocessSpec(
    val maxDetections: Int?,
    val scoreThreshold: Double?,
    val nmsIouThreshold: Double?,
)

data class AdapterContract(
    val schemaId: String,
    val classMap: ClassMapSpec?,
    val embeddedPostprocess: EmbeddedPostprocessSpec?,
)

data class ParameterBound(
    val minimum: Double,
    val maximum: Double,
)

data class SamplingPolicySpec(
    val defaultIntervalMillis: Long?,
    val minimumIntervalMillis: Long?,
    val maximumIntervalMillis: Long?,
    val adaptiveAllowed: Boolean,
)

data class ParameterProfile(
    val defaults: Map<String, Double>,
    val allowedBounds: Map<String, ParameterBound>,
    val samplingPolicy: SamplingPolicySpec,
)

data class DeviceCompatibility(
    val deviceProfileIds: Set<String>,
    val minAndroidApi: Int,
    val maxAndroidApi: Int? = null,
    val supportedAbis: Set<String>,
    val minimumMemoryMb: Int,
    val requiredFeatures: Set<String>,
)

data class ManifestSignature(
    val canonicalization: String,
    val algorithm: String,
    val signingKeyId: String,
    val valueBase64: String,
)

/** Canonical Android representation of contracts/schemas/model-manifest.schema.json v4. */
data class ModelPackageManifest(
    val schemaVersion: String,
    val packageId: String,
    val packageVersion: String,
    val runtimeFamily: RecipeFamily,
    val supportedTasks: Set<SupportedTask>,
    val promptModes: Set<TargetMode>,
    val modelSource: SourceReference,
    val weightsSource: SourceReference,
    val codeSource: SourceReference,
    val exportToolSource: SourceReference,
    val license: ModelPackageLicense,
    val artifacts: List<ArtifactComponent>,
    val inputs: List<InputComponent>,
    val outputs: List<OutputComponent>,
    val bindings: List<TensorBinding>,
    val adapterContract: AdapterContract,
    val preprocessId: String,
    val postprocessId: String,
    val ctcDecoding: CtcDecodingSpec? = null,
    val selfTest: ReadingKnownAnswerSelfTest? = null,
    val parameterProfile: ParameterProfile,
    val deviceCompatibility: DeviceCompatibility,
    val signature: ManifestSignature,
) {
    /** Compatibility view used by the deterministic selector; values are v3 task IDs. */
    val capabilityIds: Set<String>
        get() = supportedTasks.mapTo(linkedSetOf(), SupportedTask::wireValue)

    /** Runtime registries historically called this an adapter ID. The signed field is postprocess_id. */
    val adapterId: String
        get() = postprocessId

    /** Redundant fail-closed checks run after JSON Schema decoding and before selection/activation. */
    fun structuralErrors(): Set<String> = buildSet {
        if (schemaVersion != SCHEMA_VERSION) add("schema_version")
        if (packageId.isBlank()) add("package_id")
        if (!SEMVER.matches(packageVersion)) add("package_version")
        if (supportedTasks.isEmpty()) add("supported_tasks")
        if (promptModes.isEmpty()) add("prompt_modes")

        listOf(modelSource, weightsSource, codeSource, exportToolSource).forEachIndexed { index, ref ->
            if (!ref.url.isAbsoluteUri() || ref.version.isBlank()) add("source_$index")
        }
        validateExportToolSource()
        validateLicense()
        validateArtifactAndInputSet()
        validateAdapterContract()
        validateRuntimeFamily()
        validateReadingKnownAnswer()
        validateParameterProfile()
        validateDeviceCompatibility()

        if (preprocessId.isBlank()) add("preprocess_id")
        if (postprocessId.isBlank()) add("postprocess_id")
        if (signature.canonicalization != "RFC8785") add("signature_canonicalization")
        if (signature.algorithm != "Ed25519") add("signature_algorithm")
        if (signature.signingKeyId.isBlank()) add("signature_key_id")
        if (!signature.valueBase64.isValidBase64()) add("signature_value")
    }

    private fun MutableSet<String>.validateExportToolSource() {
        val version = exportToolSource.version
        if (version == PUBLISHED_PREBUILT_EXPORT_TOOL_NOT_DISCLOSED) {
            if (exportToolSource.url != modelSource.url && exportToolSource.url != weightsSource.url) {
                add("prebuilt_export_source_mismatch")
            }
            return
        }
        val normalized = version.trim().lowercase().replace(Regex("[\\s_]+"), "-")
        if (UNKNOWN_EXPORT_TOOL_MARKER.containsMatchIn(normalized) ||
            normalized in setOf("n/a", "na", "none", "null")
        ) {
            add("export_tool_unknown_sentinel_invalid")
        }
    }

    private fun MutableSet<String>.validateLicense() {
        if (license.licenseId.isBlank()) add("license_id")
        if (!SHA_256.matches(license.licenseTextSha256)) add("license_text_sha256")
        if (license.reviewedAt != null && !license.reviewedAt.isIsoInstant()) add("reviewed_at")
        if (license.reviewEvidenceRef != null && license.reviewEvidenceRef.isBlank()) {
            add("review_evidence_ref")
        }
        if (license.reviewEvidenceSha256 != null && !SHA_256.matches(license.reviewEvidenceSha256)) {
            add("review_evidence_sha256")
        }
        if (license.reviewStatus == LicenseReviewStatus.APPROVED &&
            (license.reviewedAt == null || license.reviewEvidenceRef == null)
        ) {
            add("approved_review_evidence")
        }
        if ((license.licenseGrantId == null) != (license.grantExpiresAt == null)) {
            add("license_grant_pair")
        }
        if (license.licenseGrantId != null && license.licenseGrantId.isBlank()) add("license_grant_id")
        if (license.grantExpiresAt != null && !license.grantExpiresAt.isIsoInstant()) {
            add("grant_expires_at")
        }
    }

    private fun MutableSet<String>.validateArtifactAndInputSet() {
        if (artifacts.isEmpty()) {
            add("artifacts")
            return
        }
        if (artifacts.map { it.role }.toSet().size != artifacts.size) add("duplicate_artifact_role")
        val artifactsByRole = artifacts.associateBy(ArtifactComponent::role)
        artifacts.forEach { component ->
            if (!ARTIFACT_ROLE.matches(component.role)) add("artifact_role")
            if (!component.url.isAbsoluteUri() || !SHA_256.matches(component.sha256) ||
                component.sizeBytes <= 0
            ) {
                add("artifact_component")
            }
            val expectedMediaType = when (component.runtime) {
                RuntimeKind.LITERT -> ArtifactMediaType.LITERT
                RuntimeKind.ONNX -> ArtifactMediaType.ONNX
                RuntimeKind.CLASSIC_VISION -> ArtifactMediaType.CLASSIC_VISION_JSON
                RuntimeKind.CTC_VOCABULARY -> ArtifactMediaType.CTC_VOCABULARY_JSON
            }
            if (component.mediaType != expectedMediaType) add("artifact_component_runtime_mismatch")
        }
        val primary = artifactsByRole["primary"]
        if (primary == null) {
            add("primary_artifact_missing")
        }

        if (inputs.isEmpty()) add("inputs")
        if (inputs.map { it.artifactRole to it.tensorIndex }.toSet().size != inputs.size) {
            add("duplicate_artifact_tensor_index")
        }
        if (inputs.map { it.artifactRole to it.tensorName }.toSet().size != inputs.size) {
            add("duplicate_artifact_tensor_name")
        }
        inputs.forEach { input ->
            if (input.artifactRole !in artifactsByRole) add("input_artifact_missing")
            if (input.tensorIndex < 0 || input.tensorName.isBlank() || input.runtimeShape.isEmpty() ||
                input.runtimeShape.size > 8 || input.runtimeShape.any { it <= 0 }
            ) {
                add("input_component")
            }
            validateComponentQuantization(input)
            if (input.role in IMAGE_INPUT_ROLES) {
                validateImageInput(input)
            } else if (input.layout != null || input.colorSpace != null) {
                add("non_image_tensor_metadata_forbidden")
            }
        }
        val primaryImages = inputs.filter {
            it.role == InputRole.IMAGE && it.artifactRole == "primary"
        }
        if (primaryImages.size != 1) {
            add("primary_image_input_missing")
        }

        validateExecutionGraph(artifactsByRole)
    }

    private fun MutableSet<String>.validateExecutionGraph(
        artifactsByRole: Map<String, ArtifactComponent>,
    ) {
        if (outputs.isEmpty()) {
            add("outputs")
            return
        }
        val inputsByEndpoint = inputs.associateBy { it.artifactRole to it.tensorName }
        if (outputs.map { it.artifactRole to it.tensorIndex }.toSet().size != outputs.size) {
            add("duplicate_artifact_output_index")
        }
        if (outputs.map { it.artifactRole to it.tensorName }.toSet().size != outputs.size) {
            add("duplicate_artifact_output_name")
        }
        if (outputs.map(OutputComponent::role).toSet().size != outputs.size) {
            add("duplicate_output_role")
        }
        val outputsByEndpoint = outputs.associateBy { it.artifactRole to it.tensorName }
        outputs.forEach { output ->
            if (output.artifactRole !in artifactsByRole) add("output_artifact_missing")
            if (output.tensorIndex < 0 || output.tensorName.isBlank() ||
                output.runtimeShape.isEmpty() || output.runtimeShape.size > 8 ||
                output.runtimeShape.any { it <= 0 }
            ) {
                add("output_component")
            }
        }

        val bindingKeys = mutableSetOf<Pair<Pair<String, String>, Pair<String, String>>>()
        val bindingTargets = mutableSetOf<Pair<String, String>>()
        val boundSourceEndpoints = mutableSetOf<Pair<String, String>>()
        val executableArtifacts = artifacts.filterNot(ArtifactComponent::isStaticSidecar)
        val executableRoles = executableArtifacts.mapTo(linkedSetOf(), ArtifactComponent::role)
        val edges = executableArtifacts.associate { it.role to mutableSetOf<String>() }
        val indegree = executableArtifacts.associate { it.role to 0 }.toMutableMap()
        bindings.forEach { binding ->
            val sourceEndpoint = binding.sourceArtifactRole to binding.sourceTensorName
            val targetEndpoint = binding.targetArtifactRole to binding.targetTensorName
            if (!bindingKeys.add(sourceEndpoint to targetEndpoint)) add("duplicate_binding")
            val source = outputsByEndpoint[sourceEndpoint]
            val target = inputsByEndpoint[targetEndpoint]
            if (source == null) add("binding_source_missing")
            if (target == null) add("binding_target_missing")
            if (!bindingTargets.add(targetEndpoint)) add("binding_target_ambiguous")
            if (source != null && target != null) {
                if (binding.sourceArtifactRole !in executableRoles ||
                    binding.targetArtifactRole !in executableRoles
                ) {
                    add("binding_sidecar_forbidden")
                }
                if (source.role !in BINDABLE_OUTPUT_ROLES ||
                    source.role.name != target.role.name
                ) {
                    add("binding_role_mismatch")
                }
                if (source.dataType != target.dataType || source.runtimeShape != target.runtimeShape) {
                    add("binding_tensor_mismatch")
                }
                if (binding.sourceArtifactRole == binding.targetArtifactRole) {
                    add("binding_self_edge")
                } else if (binding.sourceArtifactRole in edges && binding.targetArtifactRole in edges &&
                    checkNotNull(edges[binding.sourceArtifactRole]).add(binding.targetArtifactRole)
                ) {
                    indegree[binding.targetArtifactRole] =
                        checkNotNull(indegree[binding.targetArtifactRole]) + 1
                }
                boundSourceEndpoints += sourceEndpoint
            }
        }

        inputs.forEach { input ->
            val endpoint = input.artifactRole to input.tensorName
            if (input.role in BINDING_REQUIRED_INPUT_ROLES && endpoint !in bindingTargets) {
                add("internal_input_unbound")
            }
            if (input.role in RUNTIME_ONLY_INPUT_ROLES && endpoint in bindingTargets) {
                add("external_input_bound")
            }
        }
        val runtimeSuppliedInputs = inputs.filter { input ->
            (input.artifactRole to input.tensorName) !in bindingTargets
        }
        if (TargetMode.OBJECT_CLASS in promptModes &&
            runtimeSuppliedInputs.any { it.role in OBJECT_TARGET_INPUT_ROLES }
        ) {
            add("object_target_tensor_forbidden")
        }
        if (TargetMode.REFERENCE_IMAGES in promptModes &&
            runtimeSuppliedInputs.none { it.role in REFERENCE_TARGET_INPUT_ROLES }
        ) {
            add("reference_target_dependency_missing")
        }
        if (TargetMode.REFERENCE_IMAGES !in promptModes &&
            runtimeSuppliedInputs.any { it.role in REFERENCE_TARGET_INPUT_ROLES }
        ) {
            add("reference_target_dependency_forbidden")
        }
        outputs.forEach { output ->
            val endpoint = output.artifactRole to output.tensorName
            val runtimeConsumedReadingOutput = runtimeFamily == RecipeFamily.READING_PIPELINE_V1 &&
                output.role == OutputRole.TEXT_PROBABILITY_MAP
            if (endpoint !in boundSourceEndpoints && output.role.toFinalSemanticOrNull() == null &&
                !runtimeConsumedReadingOutput
            ) {
                add("internal_output_unbound")
            }
        }

        val rootRoles = indegree.filterValues { it == 0 }.keys
        val queue = java.util.PriorityQueue<String>().apply { addAll(rootRoles) }
        var visited = 0
        while (queue.isNotEmpty()) {
            val role = queue.remove()
            visited++
            checkNotNull(edges[role]).sorted().forEach { target ->
                val next = checkNotNull(indegree[target]) - 1
                indegree[target] = next
                if (next == 0) queue += target
            }
        }
        if (visited != executableArtifacts.size) add("execution_graph_cycle")
        executableArtifacts.forEach { artifact ->
            val artifactInputs = inputs.filter { it.artifactRole == artifact.role }
            val artifactOutputs = outputs.filter { it.artifactRole == artifact.role }
            if (artifactInputs.isEmpty() || artifactOutputs.isEmpty()) add("artifact_graph_disconnected")
            if (artifact.role in rootRoles &&
                artifactInputs.none { input ->
                    (input.artifactRole to input.tensorName) !in bindingTargets
                }
            ) {
                add("artifact_root_external_input_missing")
            }
        }
        val ctcVocabularyArtifacts = artifacts.filter(ArtifactComponent::isCtcVocabularySidecar)
        ctcVocabularyArtifacts.forEach { artifact ->
            if (inputs.any { it.artifactRole == artifact.role } ||
                outputs.any { it.artifactRole == artifact.role }
            ) {
                add("ctc_vocabulary_sidecar_tensor_forbidden")
            }
        }
        validateCtcDecoding(ctcVocabularyArtifacts)
    }

    private fun MutableSet<String>.validateCtcDecoding(
        vocabularyArtifacts: List<ArtifactComponent>,
    ) {
        val spec = ctcDecoding
        if (runtimeFamily != RecipeFamily.READING_PIPELINE_V1) {
            if (spec != null || vocabularyArtifacts.isNotEmpty()) add("ctc_decoding_forbidden")
            return
        }
        if (spec == null) {
            add("ctc_decoding_missing")
            return
        }
        if (!ARTIFACT_ROLE.matches(spec.vocabularyArtifactRole)) {
            add("ctc_vocabulary_artifact_role")
        }
        val vocabulary = vocabularyArtifacts.singleOrNull {
            it.role == spec.vocabularyArtifactRole
        }
        if (vocabulary == null || vocabularyArtifacts.size != 1) {
            add("ctc_vocabulary_artifact_missing")
        }
        val classCount = finalOutputs()
            .singleOrNull { it.role == OutputRole.CTC_LOGITS }
            ?.runtimeShape
            ?.lastOrNull()
        if (spec.blankIndex < 0 || classCount == null || spec.blankIndex >= classCount) {
            add("ctc_blank_index_out_of_range")
        }
        if (spec.collapseSemantics != CtcCollapseSemantics.CTC_GREEDY_ARGMAX_V1) {
            add("ctc_collapse_semantics_unsupported")
        }
    }

    private fun MutableSet<String>.validateComponentQuantization(input: InputComponent) {
        when (input.dataType) {
            TensorDataType.UINT8, TensorDataType.INT8 -> {
                if (input.quantization.mode != QuantizationMode.PER_TENSOR ||
                    input.quantization.scale?.let { it.isFinite() && it > 0.0 } != true ||
                    input.quantization.zeroPoint == null
                ) {
                    add("input_quantization_required")
                } else {
                    val range = if (input.dataType == TensorDataType.UINT8) 0..255 else -128..127
                    if (input.quantization.zeroPoint !in range) {
                        add("quantization_zero_point_out_of_range")
                    }
                }
            }
            TensorDataType.INT32,
            TensorDataType.INT64,
            TensorDataType.FLOAT16,
            TensorDataType.FLOAT32,
            -> if (input.quantization != InputQuantization(QuantizationMode.NONE)) {
                add("input_quantization_forbidden")
            }
        }
    }

    private fun MutableSet<String>.validateImageInput(input: InputComponent) {
        val layout = input.layout
        val colorSpace = input.colorSpace
        if (layout == null || colorSpace == null || input.runtimeShape.size != 4) {
            add("image_tensor_metadata_required")
            return
        }
        val channelIndex = if (layout == TensorLayout.NHWC) 3 else 1
        val expectedChannels = if (colorSpace == ColorSpace.GRAY) 1 else 3
        if (input.runtimeShape[channelIndex] != expectedChannels) {
            add("input_color_channels_mismatch")
        }
    }

    private fun MutableSet<String>.validateAdapterContract() {
        val output = adapterContract
        if (output.schemaId !in ALLOWED_OUTPUT_SCHEMA_IDS) add("adapter_contract")
        output.classMap?.let { classMap ->
            if (classMap.identity.isBlank()) add("class_map_identity")
            if (!SHA_256.matches(classMap.sha256)) add("class_map_sha256")
            if (classMap.classIdBase !in 0..1) add("class_map_base")
            if (classMap.targets.map(ClassMapTarget::rawClassId).distinct().size != classMap.targets.size) {
                add("class_map_duplicate_raw_id")
            }
            if (classMap.targets.map(ClassMapTarget::targetId).distinct().size != classMap.targets.size) {
                add("class_map_duplicate_target_id")
            }
            val aliases = mutableMapOf<String, String>()
            classMap.targets.forEach { target ->
                (target.aliases + target.labelZhCn + target.labelEn).forEach { alias ->
                    val key = Normalizer.normalize(alias, Normalizer.Form.NFKC)
                        .trim()
                        .replace(Regex("\\s+"), " ")
                        .lowercase(Locale.ROOT)
                    val previous = aliases.put(key, target.targetId)
                    if (previous != null && previous != target.targetId) add("class_map_alias_conflict")
                }
            }
        }
        output.embeddedPostprocess?.let { postprocess ->
            if (postprocess.maxDetections != null && postprocess.maxDetections <= 0) {
                add("postprocess_max_detections")
            }
            if (postprocess.scoreThreshold != null &&
                (!postprocess.scoreThreshold.isFinite() || postprocess.scoreThreshold !in 0.0..1.0)
            ) {
                add("postprocess_score_threshold")
            }
            if (postprocess.nmsIouThreshold != null &&
                (!postprocess.nmsIouThreshold.isFinite() || postprocess.nmsIouThreshold !in 0.0..1.0)
            ) {
                add("postprocess_nms_iou_threshold")
            }
        }
    }

    private fun MutableSet<String>.validateRuntimeFamily() {
        val policy = FAMILY_POLICIES[runtimeFamily]
        if (policy == null) {
            add("runtime_family")
            return
        }
        if (!policy.supportedTasks.containsAll(supportedTasks)) add("runtime_family_task_mismatch")
        if (!policy.promptModes.containsAll(promptModes)) add("runtime_family_prompt_mode_mismatch")
        if (adapterContract.schemaId !in policy.outputSchemaIds) add("runtime_family_output_mismatch")

        val finalOutputs = finalOutputs()
        val semantics = finalOutputs.mapNotNull { it.role.toFinalSemanticOrNull() }.toSet()
        when (runtimeFamily) {
            RecipeFamily.OBJECT_DETECTION_V1 -> {
                if (semantics != DETECTION_SEMANTICS) add("object_detection_tensor_semantics_mismatch")
                validateObjectDetectionShapes()
                if (adapterContract.schemaId == OBJECT_SCHEMA_ID &&
                    (adapterContract.classMap == null || adapterContract.classMap.targets.isEmpty())
                ) {
                    add("object_detection_class_map_missing")
                }
            }
            RecipeFamily.SIMILARITY_MATCH_V1 -> {
                if (TargetMode.REFERENCE_IMAGES in promptModes &&
                    preprocessId != REFERENCE_LOCALIZED_PREPROCESS_ID
                ) {
                    add("reference_preprocess_contract_mismatch")
                }
                if (semantics != SIMILARITY_SEMANTICS) {
                    add("similarity_match_tensor_semantics_mismatch")
                }
                val tensor = finalOutputs.singleOrNull()
                if (tensor == null || tensor.dataType != TensorDataType.FLOAT32 ||
                    tensor.runtimeShape.size != 2 || tensor.runtimeShape.first() != 1
                ) {
                    add("similarity_match_tensor_shape_mismatch")
                }
                if (adapterContract.classMap != null || adapterContract.embeddedPostprocess != null) {
                    add("similarity_match_detection_metadata_forbidden")
                }
                val bindingTargets = bindings.mapTo(hashSetOf()) {
                    it.targetArtifactRole to it.targetTensorName
                }
                val runtimeReferenceFeatures = inputs.filter { input ->
                    input.role == InputRole.REFERENCE_FEATURES &&
                        (input.artifactRole to input.tensorName) !in bindingTargets
                }
                if (runtimeReferenceFeatures.isNotEmpty()) {
                    val reference = runtimeReferenceFeatures.singleOrNull()
                    val imageFeatures = reference?.let { referenceInput ->
                        inputs.singleOrNull { input ->
                            input.role == InputRole.IMAGE_FEATURES &&
                                input.artifactRole == referenceInput.artifactRole
                        }
                    }
                    if (reference == null ||
                        reference.dataType != TensorDataType.FLOAT32 ||
                        reference.runtimeShape.size != 2 ||
                        reference.runtimeShape.first() !in REFERENCE_PROTOTYPE_COUNTS ||
                        (imageFeatures != null && (
                            imageFeatures.dataType != TensorDataType.FLOAT32 ||
                                imageFeatures.runtimeShape.size != 2 ||
                                imageFeatures.runtimeShape.first() != 4 ||
                                reference.runtimeShape[1] != imageFeatures.runtimeShape[1]
                            ))
                    ) {
                        add("reference_prototype_tensor_shape_mismatch")
                    }
                }
            }
            RecipeFamily.READING_PIPELINE_V1 -> {
                if (inputs.any { it.role in MODEL_PROMPT_INPUT_ROLES }) {
                    add("reading_model_prompt_input_forbidden")
                }
                if (postprocessId != NUMERIC_CTC_POSTPROCESS_ID) {
                    add("structured_reading_ctc_adapter_mismatch")
                }
                if (semantics != READING_SEMANTICS) add("numeric_ctc_tensor_semantics_mismatch")
                val tensor = finalOutputs.singleOrNull()
                if (tensor == null || tensor.dataType != TensorDataType.FLOAT32 ||
                    tensor.runtimeShape.size != 3 || tensor.runtimeShape.first() != 1 ||
                    tensor.runtimeShape[1] < 1 ||
                    tensor.runtimeShape.last() !in MIN_CTC_CLASS_COUNT..MAX_CTC_CLASS_COUNT
                ) {
                    add("numeric_ctc_tensor_shape_mismatch")
                }
                if (adapterContract.classMap != null || adapterContract.embeddedPostprocess != null) {
                    add("numeric_reading_detection_metadata_forbidden")
                }
                validateReadingLocator()
                val minimumConfidence = parameterProfile.defaults[
                    READING_MINIMUM_CONFIDENCE_PARAMETER
                ]
                if (minimumConfidence == null) {
                    add("reading_minimum_confidence_missing")
                } else if (!minimumConfidence.isFinite() || minimumConfidence !in 0.0..1.0) {
                    add("reading_minimum_confidence_invalid")
                }
            }
        }
    }

    private fun MutableSet<String>.validateReadingLocator() {
        val locatorArtifact = artifacts.singleOrNull { it.role == READING_LOCATOR_ARTIFACT_ROLE }
        val locatorInput = inputs.singleOrNull {
            it.artifactRole == READING_LOCATOR_ARTIFACT_ROLE && it.role == InputRole.IMAGE
        }
        val locatorOutput = outputs.singleOrNull {
            it.artifactRole == READING_LOCATOR_ARTIFACT_ROLE &&
                it.role == OutputRole.TEXT_PROBABILITY_MAP
        }
        if (locatorArtifact == null || locatorArtifact.isStaticSidecar ||
            locatorInput == null || locatorOutput == null
        ) {
            add("reading_auto_locator_missing")
            return
        }
        if (inputs.count { it.role == InputRole.IMAGE } != 2 ||
            locatorInput.dataType != TensorDataType.FLOAT32 ||
            locatorInput.layout != TensorLayout.NCHW ||
            locatorInput.colorSpace != ColorSpace.BGR ||
            locatorInput.runtimeShape.size != 4 ||
            locatorInput.runtimeShape[0] != 1 || locatorInput.runtimeShape[1] != 3 ||
            locatorInput.runtimeShape[2] !in MIN_READING_LOCATOR_SIDE..MAX_READING_LOCATOR_SIDE ||
            locatorInput.runtimeShape[3] !in MIN_READING_LOCATOR_SIDE..MAX_READING_LOCATOR_SIDE ||
            locatorInput.runtimeShape[2] % 32 != 0 || locatorInput.runtimeShape[3] % 32 != 0 ||
            locatorOutput.dataType != TensorDataType.FLOAT32 ||
            locatorOutput.runtimeShape != listOf(
                1,
                1,
                locatorInput.runtimeShape[2],
                locatorInput.runtimeShape[3],
            )
        ) {
            add("reading_auto_locator_tensor_contract_invalid")
        }
        requireReadingLocatorParameter(
            READING_LOCATOR_PIXEL_THRESHOLD_PARAMETER,
            0.0,
            1.0,
        )
        requireReadingLocatorParameter(
            READING_LOCATOR_BOX_THRESHOLD_PARAMETER,
            0.0,
            1.0,
        )
        requireReadingLocatorParameter(
            READING_LOCATOR_UNCLIP_RATIO_PARAMETER,
            1.0,
            3.0,
        )
        val maximumCandidates = parameterProfile.defaults[
            READING_LOCATOR_MAX_CANDIDATES_PARAMETER
        ]
        if (maximumCandidates == null || !maximumCandidates.isFinite() ||
            maximumCandidates % 1.0 != 0.0 || maximumCandidates !in 1.0..3_000.0
        ) {
            add("reading_auto_locator_max_candidates_invalid")
        }
    }

    private fun MutableSet<String>.requireReadingLocatorParameter(
        name: String,
        minimum: Double,
        maximum: Double,
    ) {
        val value = parameterProfile.defaults[name]
        if (value == null || !value.isFinite() || value !in minimum..maximum) {
            add("reading_auto_locator_parameter_invalid")
        }
    }

    private fun MutableSet<String>.validateReadingKnownAnswer() {
        val spec = selfTest
        if (runtimeFamily != RecipeFamily.READING_PIPELINE_V1) {
            if (spec != null) add("reading_known_answer_forbidden")
            return
        }
        if (spec == null) {
            add("reading_known_answer_missing")
            return
        }
        if (spec.schemaId != READING_KNOWN_ANSWER_SCHEMA_ID) add("reading_known_answer_schema")
        if (spec.artifactRole != READING_KNOWN_ANSWER_ARTIFACT_ROLE) {
            add("reading_known_answer_artifact_role")
        }
        val artifact = artifacts.singleOrNull {
            it.role == READING_KNOWN_ANSWER_ARTIFACT_ROLE
        }
        if (artifact == null || artifact.sha256 != spec.artifactSha256) {
            add("reading_known_answer_artifact_mismatch")
        }
        if (artifact != null && artifact.runtime !in READING_KNOWN_ANSWER_EXECUTABLE_RUNTIMES) {
            add("reading_known_answer_primary_runtime")
        }
        val bytes = runCatching { Base64.getDecoder().decode(spec.input.rgb888Base64) }.getOrNull()
        if (spec.input.encoding != READING_KNOWN_ANSWER_INPUT_ENCODING ||
            spec.input.width !in 1..MAX_KNOWN_ANSWER_DIMENSION ||
            spec.input.height !in 1..MAX_KNOWN_ANSWER_DIMENSION ||
            bytes == null
        ) {
            add("reading_known_answer_input_invalid")
        } else {
            val expectedSize = spec.input.width.toLong() * spec.input.height.toLong() * 3L
            if (bytes.size.toLong() != expectedSize) add("reading_known_answer_input_size_mismatch")
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (!SHA_256.matches(spec.input.sha256) || digest != spec.input.sha256) {
                add("reading_known_answer_input_hash_mismatch")
            }
        }
        if (spec.expected.text.isBlank() || spec.expected.text.length > 128 ||
            !CANONICAL_READING_DECIMAL.matches(spec.expected.valueDecimal) ||
            spec.expected.valueDecimal == "-0" || spec.expected.valueDecimal.startsWith("-0.") ||
            spec.expected.unit?.let { it.isBlank() || it.length > 32 } == true ||
            !spec.expected.minimumConfidence.isFinite() ||
            spec.expected.minimumConfidence !in 0f..1f
        ) {
            add("reading_known_answer_expected_invalid")
        }
    }

    private fun MutableSet<String>.validateObjectDetectionShapes() {
        val tensors = finalOutputs()
        if (tensors.size != DETECTION_SEMANTICS.size) return
        val byRole = tensors.associateBy(OutputComponent::role)
        val boxes = byRole[OutputRole.DETECTION_BOXES] ?: return
        val classes = byRole[OutputRole.DETECTION_CLASSES] ?: return
        val scores = byRole[OutputRole.DETECTION_SCORES] ?: return
        val count = byRole[OutputRole.DETECTION_COUNT] ?: return
        val maximum = boxes.runtimeShape.getOrNull(1)
        if (maximum == null ||
            boxes.runtimeShape != listOf(1, maximum, 4) ||
            classes.runtimeShape != listOf(1, maximum) ||
            scores.runtimeShape != listOf(1, maximum) ||
            count.runtimeShape != listOf(1)
        ) {
            add("object_detection_tensor_shape_mismatch")
        }
        if (tensors.any { it.dataType != TensorDataType.FLOAT32 }) {
            add("object_detection_tensor_dtype_mismatch")
        }
        val configuredMaximum = adapterContract.embeddedPostprocess?.maxDetections
        if (configuredMaximum != null && maximum != null && configuredMaximum != maximum) {
            add("postprocess_max_detections_mismatch")
        }
    }

    private fun MutableSet<String>.validateParameterProfile() {
        val defaults = parameterProfile.defaults
        val bounds = parameterProfile.allowedBounds
        if (defaults.keys != bounds.keys) {
            defaults.keys.minus(bounds.keys).forEach { add("parameter_bounds_missing") }
            bounds.keys.minus(defaults.keys).forEach { add("parameter_default_missing") }
        }
        defaults.forEach { (name, value) ->
            if (name.isBlank() || !value.isFinite()) add("parameter_default_invalid")
            val bound = bounds[name] ?: return@forEach
            if (!bound.minimum.isFinite() || !bound.maximum.isFinite()) {
                add("parameter_bounds_invalid")
            } else if (bound.minimum > bound.maximum) {
                add("parameter_bounds_inverted")
            } else if (value !in bound.minimum..bound.maximum) {
                add("parameter_default_out_of_bounds")
            }
        }
        bounds.keys.filter(String::isBlank).forEach { add("parameter_name_invalid") }

        val sampling = parameterProfile.samplingPolicy
        val values = listOf(
            sampling.defaultIntervalMillis,
            sampling.minimumIntervalMillis,
            sampling.maximumIntervalMillis,
        )
        if (values.any { it == null } && values.any { it != null }) {
            add("sampling_policy_incomplete")
        } else if (values.all { it != null }) {
            val default = checkNotNull(sampling.defaultIntervalMillis)
            val minimum = checkNotNull(sampling.minimumIntervalMillis)
            val maximum = checkNotNull(sampling.maximumIntervalMillis)
            if (listOf(default, minimum, maximum).any {
                    it !in FrameSamplingPolicy.MIN_INTERVAL_MILLIS..
                        FrameSamplingPolicy.MAX_INTERVAL_MILLIS
                }
            ) {
                add("sampling_policy_bounds_invalid")
            }
            if (!(minimum <= default && default <= maximum)) {
                add("sampling_policy_range_invalid")
            }
        }
    }

    private fun MutableSet<String>.validateDeviceCompatibility() {
        val compatibility = deviceCompatibility
        if (compatibility.deviceProfileIds.isEmpty() ||
            !ALLOWED_DEVICE_PROFILE_IDS.containsAll(compatibility.deviceProfileIds)
        ) {
            add("device_profile_ids")
        }
        if (compatibility.minAndroidApi < 26) add("min_android_api")
        if (compatibility.maxAndroidApi?.let { it < compatibility.minAndroidApi } == true) {
            add("max_android_api")
        }
        if (compatibility.supportedAbis.isEmpty() ||
            !ALLOWED_ABIS.containsAll(compatibility.supportedAbis)
        ) {
            add("supported_abis")
        }
        if (compatibility.minimumMemoryMb < LAUNCH_MINIMUM_MEMORY_MB) add("minimum_memory_mb")
        if (compatibility.requiredFeatures.any(String::isBlank)) add("required_features")
    }

    /** Extra commercial activation checks not expressible as basic structural validity. */
    fun commercialIoErrors(): Set<String> = buildSet {
        if (parameterProfile.samplingPolicy.defaultIntervalMillis == null ||
            parameterProfile.samplingPolicy.minimumIntervalMillis == null ||
            parameterProfile.samplingPolicy.maximumIntervalMillis == null
        ) {
            add("sampling_policy_unknown")
        }
        if (runtimeFamily != RecipeFamily.OBJECT_DETECTION_V1) return@buildSet
        val postprocess = adapterContract.embeddedPostprocess
        if (postprocess == null) {
            add("embedded_postprocess_unknown")
        } else {
            if (postprocess.maxDetections == null) add("postprocess_max_detections_unknown")
            if (postprocess.scoreThreshold == null) add("postprocess_score_threshold_unknown")
            if (postprocess.nmsIouThreshold == null) add("postprocess_nms_threshold_unknown")
        }
    }

    private companion object {
        const val SCHEMA_VERSION = "4.0"
        const val READING_KNOWN_ANSWER_SCHEMA_ID = "reading_known_answer_v1"
        const val READING_KNOWN_ANSWER_ARTIFACT_ROLE = "primary"
        const val READING_KNOWN_ANSWER_INPUT_ENCODING = "rgb888_base64_v1"
        const val MAX_KNOWN_ANSWER_DIMENSION = 4096
        const val LAUNCH_MINIMUM_MEMORY_MB = 8192
        const val OBJECT_SCHEMA_ID = "object_detection_v1"
        const val REFERENCE_LOCALIZED_PREPROCESS_ID =
            "class_agnostic_localize_letterbox_multi_crop_rgb_v3"

        val ALLOWED_OUTPUT_SCHEMA_IDS = setOf(
            OBJECT_SCHEMA_ID,
            "similarity_match_v1",
            "structured_reading_v2",
        )
        val ALLOWED_DEVICE_PROFILE_IDS = setOf("android_arm64_8gb_launch_v1")
        val READING_KNOWN_ANSWER_EXECUTABLE_RUNTIMES = setOf(
            RuntimeKind.LITERT,
            RuntimeKind.ONNX,
        )
        val ARTIFACT_ROLE = Regex("^[a-z][a-z0-9_]*$")
        val ALLOWED_ABIS = setOf("arm64-v8a")
        val DETECTION_SEMANTICS = setOf(
            OutputTensorSemantic.DETECTION_BOXES,
            OutputTensorSemantic.DETECTION_CLASSES,
            OutputTensorSemantic.DETECTION_SCORES,
            OutputTensorSemantic.DETECTION_COUNT,
        )
        val SIMILARITY_SEMANTICS = setOf(OutputTensorSemantic.SIMILARITY_SCORES)
        const val MIN_CTC_CLASS_COUNT = 2
        const val MAX_CTC_CLASS_COUNT = 65_536
        const val NUMERIC_CTC_POSTPROCESS_ID = "structured_reading_ctc_v2"
        const val READING_LOCATOR_ARTIFACT_ROLE = "locator"
        const val MIN_READING_LOCATOR_SIDE = 32
        const val MAX_READING_LOCATOR_SIDE = 1_280
        val READING_SEMANTICS = setOf(OutputTensorSemantic.CTC_LOGITS)
        val FAMILY_POLICIES = mapOf(
            RecipeFamily.OBJECT_DETECTION_V1 to FamilyPolicy(
                supportedTasks = setOf(SupportedTask.VISUAL_TARGET, SupportedTask.VISIBLE_STATE),
                promptModes = setOf(TargetMode.OBJECT_CLASS),
                outputSchemaIds = setOf(OBJECT_SCHEMA_ID),
            ),
            RecipeFamily.SIMILARITY_MATCH_V1 to FamilyPolicy(
                supportedTasks = setOf(SupportedTask.VISUAL_TARGET, SupportedTask.VISIBLE_STATE),
                promptModes = setOf(TargetMode.REFERENCE_IMAGES),
                outputSchemaIds = setOf("similarity_match_v1"),
            ),
            RecipeFamily.READING_PIPELINE_V1 to FamilyPolicy(
                supportedTasks = setOf(SupportedTask.STRUCTURED_READING),
                promptModes = setOf(TargetMode.NONE),
                outputSchemaIds = setOf("structured_reading_v2"),
            ),
        )
        /** Inputs that must come from the runtime and can never be artifact-to-artifact bindings. */
        val RUNTIME_ONLY_INPUT_ROLES = setOf(
            InputRole.IMAGE,
            InputRole.REFERENCE_IMAGES,
            InputRole.READING_TASK_SPEC,
        )
        val IMAGE_INPUT_ROLES = setOf(
            InputRole.IMAGE,
            InputRole.IMAGE_TENSOR,
            InputRole.REFERENCE_IMAGES,
        )
        val BINDING_REQUIRED_INPUT_ROLES = setOf(
            InputRole.IMAGE_TENSOR,
            InputRole.IMAGE_FEATURES,
            InputRole.DETECTION_BOXES,
            InputRole.DETECTION_CLASSES,
            InputRole.DETECTION_SCORES,
            InputRole.DETECTION_COUNT,
        )
        val OBJECT_TARGET_INPUT_ROLES = emptySet<InputRole>()
        val REFERENCE_TARGET_INPUT_ROLES = setOf(InputRole.REFERENCE_IMAGES, InputRole.REFERENCE_FEATURES)
        val REFERENCE_PROTOTYPE_COUNTS = setOf(1, 4, 12)
        val MODEL_PROMPT_INPUT_ROLES = REFERENCE_TARGET_INPUT_ROLES
        val BINDABLE_OUTPUT_ROLES = setOf(
            OutputRole.IMAGE_TENSOR,
            OutputRole.IMAGE_FEATURES,
            OutputRole.REFERENCE_FEATURES,
            OutputRole.DETECTION_BOXES,
            OutputRole.DETECTION_CLASSES,
            OutputRole.DETECTION_SCORES,
            OutputRole.DETECTION_COUNT,
        )
    }
}

internal val ModelPackageManifest.primaryArtifact: ArtifactComponent
    get() = artifacts.single { it.role == "primary" }

internal val ArtifactComponent.isTokenizerSidecar: Boolean
    get() = false

internal val ArtifactComponent.isCtcVocabularySidecar: Boolean
    get() = runtime == RuntimeKind.CTC_VOCABULARY

internal val ArtifactComponent.isStaticSidecar: Boolean
    get() = isTokenizerSidecar || isCtcVocabularySidecar

internal val ModelPackageManifest.primaryImageInput: InputComponent
    get() = inputs.single { it.role == InputRole.IMAGE && it.artifactRole == "primary" }

internal fun ModelPackageManifest.finalOutputs(): List<OutputComponent> =
    bindings.mapTo(hashSetOf(), TensorBinding::sourceEndpoint).let { boundSources ->
        outputs.filter { output ->
            output.endpoint !in boundSources && output.role.toFinalSemanticOrNull() != null
        }
    }

internal fun InputComponent.toImageInputSpec(): InputSpec {
    require(role == InputRole.IMAGE || role == InputRole.IMAGE_TENSOR ||
        role == InputRole.REFERENCE_IMAGES
    )
    val resolvedLayout = requireNotNull(layout)
    val resolvedColorSpace = requireNotNull(colorSpace)
    require(runtimeShape.size == 4)
    val (height, width, channels) = when (resolvedLayout) {
        TensorLayout.NHWC -> Triple(runtimeShape[1], runtimeShape[2], runtimeShape[3])
        TensorLayout.NCHW -> Triple(runtimeShape[2], runtimeShape[3], runtimeShape[1])
    }
    return InputSpec(
        tensorIndex = tensorIndex,
        tensorName = tensorName,
        width = width,
        height = height,
        channels = channels,
        dataType = dataType,
        layout = resolvedLayout,
        colorSpace = resolvedColorSpace,
        runtimeShape = runtimeShape,
        quantization = quantization,
    )
}

internal fun OutputComponent.toTensorSpec(): OutputTensorSpec = OutputTensorSpec(
    index = tensorIndex,
    name = tensorName,
    semantic = requireNotNull(role.toFinalSemanticOrNull()),
    dataType = dataType,
    runtimeShape = runtimeShape,
)

internal fun OutputRole.toFinalSemanticOrNull(): OutputTensorSemantic? =
    runCatching { OutputTensorSemantic.valueOf(name) }.getOrNull()

private data class FamilyPolicy(
    val supportedTasks: Set<SupportedTask>,
    val promptModes: Set<TargetMode>,
    val outputSchemaIds: Set<String>,
)

/** Integrity results are computed locally; successful download never implies any of them. */
data class VerifiedModelPackage(
    val manifest: ModelPackageManifest,
    val catalogEntryActive: Boolean,
    val catalogSignatureValid: Boolean,
    val catalogManifestSha256Matches: Boolean,
    val manifestSignatureValid: Boolean,
    val artifactSha256Valid: Boolean,
    val licenseTextSha256Valid: Boolean,
)

private fun String.isAbsoluteUri(): Boolean = runCatching { URI(this).isAbsolute }.getOrDefault(false)
private fun String.isIsoInstant(): Boolean = runCatching { Instant.parse(this) }.isSuccess
private fun String.isValidBase64(): Boolean =
    isNotBlank() && runCatching { java.util.Base64.getDecoder().decode(this) }.isSuccess
