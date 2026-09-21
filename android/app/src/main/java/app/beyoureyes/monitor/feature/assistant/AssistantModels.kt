package app.beyoureyes.monitor.feature.assistant

import app.beyoureyes.core.domain.ALLOWED_TRIGGER_DURATIONS_SECONDS
import app.beyoureyes.core.domain.ObjectClassCatalog
import app.beyoureyes.core.domain.ObjectClassDefinition
import java.math.BigDecimal

internal const val ASSISTANT_SCHEMA_VERSION = "3.0"
internal const val ASSISTANT_REFERENCE_IMAGE_COUNT = 3

private val IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")
private val UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
private val SHA256 = Regex("^[0-9a-f]{64}$")
private val LOCALE = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*$")
private val INTENT_KEY = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)+$")
private val INTENT_PATTERN = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)+(?:\\.\\*)?$")
private val TARGET_ID = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
private val DECIMAL = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")
private val FORBIDDEN_TEXT_CONTROLS = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]")

internal enum class AssistantProposalKind(val wireValue: String) {
    REFERENCE_IMAGES("reference_images"),
    VISUAL_DESCRIPTION("visual_description"),
    STRUCTURED_READING("structured_reading");

    companion object {
        fun fromWireValue(value: String): AssistantProposalKind? = entries.singleOrNull {
            it.wireValue == value
        }
    }
}

internal enum class AssistantConversationRole(val wireValue: String) {
    USER("user"),
    ASSISTANT("assistant");
}

internal data class AssistantCatalogBinding(
    val catalogId: String,
    val catalogVersion: String,
    val catalogSignedPayloadSha256: String,
) {
    init {
        requireIdentifier(catalogId, "catalogId")
        requireIdentifier(catalogVersion, "catalogVersion")
        require(SHA256.matches(catalogSignedPayloadSha256)) {
            "catalogSignedPayloadSha256 must be a lowercase SHA-256"
        }
    }
}

internal data class AssistantTargetDescriptor(
    val targetId: String,
    val labelZhCn: String,
    val labelEn: String,
    val aliases: List<String>,
) {
    init {
        require(TARGET_ID.matches(targetId))
        requireStrictText(labelZhCn, 40, "labelZhCn")
        requireStrictText(labelEn, 40, "labelEn")
        require(aliases.size <= 8 && aliases.distinct().size == aliases.size)
        aliases.forEach { requireStrictText(it, 40, "alias") }
    }
}

internal data class AssistantModelProfile(
    val modelProfileKey: String,
    val packageId: String,
    val kind: AssistantProposalKind,
    val intentPatterns: List<String>,
    /** Signed Catalog boundaries. They are read-only context, not model instructions. */
    val applicableScenarios: List<String>,
    val inapplicableScenarios: List<String>,
    val inputRequirements: List<String>,
    val targets: List<AssistantTargetDescriptor>,
    /** Exact signed Catalog model-card name. It is deliberately omitted from the Edge request. */
    val displayName: String,
) {
    init {
        requireIdentifier(modelProfileKey, "modelProfileKey")
        requireIdentifier(packageId, "packageId")
        require(intentPatterns.size in 1..64 && intentPatterns.distinct().size == intentPatterns.size)
        require(intentPatterns.all { codePointLength(it) <= 160 && INTENT_PATTERN.matches(it) })
        requireProfileBoundary(applicableScenarios, "applicableScenarios")
        requireProfileBoundary(inapplicableScenarios, "inapplicableScenarios")
        requireProfileBoundary(inputRequirements, "inputRequirements")
        require(targets.size <= 256 && targets.map { it.targetId }.distinct().size == targets.size)
        require((kind == AssistantProposalKind.VISUAL_DESCRIPTION) == targets.isNotEmpty())
        requireStrictText(displayName, 120, "displayName")
    }

    private fun requireProfileBoundary(values: List<String>, field: String) {
        require(values.size in 1..64 && values.distinct().size == values.size)
        values.forEach { requireStrictText(it, 4_096, field) }
    }

    val targetIds: List<String> get() = targets.map(AssistantTargetDescriptor::targetId)

    fun authorizesIntent(intentKey: String): Boolean = INTENT_KEY.matches(intentKey) &&
        intentPatterns.any { pattern ->
            if (pattern.endsWith(".*")) {
                intentKey.startsWith(pattern.removeSuffix("*"))
            } else {
                intentKey == pattern
            }
        }
}

internal data class AssistantCatalogSnapshot(
    val binding: AssistantCatalogBinding,
    val modelProfiles: List<AssistantModelProfile>,
    /** Exact target IDs dynamically admitted by the current signed Catalog and Manifests. */
    val currentExactObjectTargetIds: Set<String>,
) {
    init {
        require(modelProfiles.size in 1..64)
        require(
            modelProfiles.map { it.modelProfileKey to it.packageId }.distinct().size ==
                modelProfiles.size,
        )
        require(currentExactObjectTargetIds.all { TARGET_ID.matches(it) })
        modelProfiles.filter { it.kind == AssistantProposalKind.VISUAL_DESCRIPTION }.forEach { profile ->
            require(currentExactObjectTargetIds.containsAll(profile.targetIds)) {
                "visual profile contains a target outside the current exact object catalog"
            }
        }
        objectClassDefinitions()
    }
}

internal fun AssistantCatalogSnapshot.objectClassDefinitions(): List<ObjectClassDefinition> {
    val descriptors = modelProfiles
        .filter { it.kind == AssistantProposalKind.VISUAL_DESCRIPTION }
        .flatMap(AssistantModelProfile::targets)
        .groupBy(AssistantTargetDescriptor::targetId)
        .map { (targetId, definitions) ->
            val definition = definitions.first()
            require(definitions.all { it == definition }) {
                "signed object target definition conflict: $targetId"
            }
            ObjectClassDefinition(
                targetId = targetId,
                labelZhCn = definition.labelZhCn,
                labelEn = definition.labelEn,
                aliases = definition.aliases.toSet(),
            )
        }
        .sortedBy(ObjectClassDefinition::targetId)
    require(descriptors.mapTo(linkedSetOf(), ObjectClassDefinition::targetId) == currentExactObjectTargetIds) {
        "signed object target inventory differs from admitted profiles"
    }
    if (descriptors.isNotEmpty()) ObjectClassCatalog(descriptors)
    return descriptors
}

internal fun interface AssistantCatalogSnapshotProvider {
    suspend fun load(): AssistantCatalogSnapshot
}

internal data class AssistantConversationMessage(
    val role: AssistantConversationRole,
    val content: String,
) {
    init {
        requireStrictText(content, 2_000, "content")
    }
}

internal data class AssistantTurnRequest(
    val conversationId: String,
    val turnId: String,
    val locale: String,
    val catalog: AssistantCatalogSnapshot,
    val messages: List<AssistantConversationMessage>,
) {
    init {
        require(UUID.matches(conversationId)) { "conversationId must be a lowercase UUID" }
        require(UUID.matches(turnId)) { "turnId must be a lowercase UUID" }
        require(codePointLength(locale) <= 35 && LOCALE.matches(locale)) { "invalid locale" }
        require(messages.size in 1..20)
        require(messages.first().role == AssistantConversationRole.USER)
        require(messages.last().role == AssistantConversationRole.USER)
        require(messages.zipWithNext().none { (left, right) -> left.role == right.role })
        require(messages.sumOf { codePointLength(it.content) } <= 16_000)
    }
}

internal enum class AssistantPresenceCondition(val wireValue: String) {
    APPEARS("appears"),
    REMAINS("remains"),
    DISAPPEARS("disappears");

    companion object {
        fun fromWireValue(value: String): AssistantPresenceCondition? = entries.singleOrNull {
            it.wireValue == value
        }
    }
}

internal data class AssistantPresenceRule(
    val condition: AssistantPresenceCondition,
    val durationSeconds: Int,
) {
    init {
        require(durationSeconds in ALLOWED_TRIGGER_DURATIONS_SECONDS)
    }
}

internal enum class AssistantReadingCondition(val wireValue: String) {
    ABOVE("above"),
    BELOW("below");

    companion object {
        fun fromWireValue(value: String): AssistantReadingCondition? = entries.singleOrNull {
            it.wireValue == value
        }
    }
}

internal sealed interface AssistantReadingRule {
    val durationSeconds: Int

    data class Single(
        val condition: AssistantReadingCondition,
        val thresholdDecimal: String,
        override val durationSeconds: Int,
    ) : AssistantReadingRule {
        init {
            requireCanonicalDecimal(thresholdDecimal, "thresholdDecimal")
            require(durationSeconds in ALLOWED_TRIGGER_DURATIONS_SECONDS)
        }
    }

    data class Outside(
        val lowerThresholdDecimal: String,
        val upperThresholdDecimal: String,
        override val durationSeconds: Int,
    ) : AssistantReadingRule {
        init {
            requireCanonicalDecimal(lowerThresholdDecimal, "lowerThresholdDecimal")
            requireCanonicalDecimal(upperThresholdDecimal, "upperThresholdDecimal")
            require(BigDecimal(lowerThresholdDecimal) < BigDecimal(upperThresholdDecimal))
            require(durationSeconds in ALLOWED_TRIGGER_DURATIONS_SECONDS)
        }
    }
}

internal sealed interface MonitorConfigurationProposal {
    val kind: AssistantProposalKind
    val title: String
    val catalogBinding: AssistantCatalogBinding
    val modelProfileKey: String
    val packageId: String
    val intentKey: String

    data class ReferenceImages(
        override val title: String,
        override val catalogBinding: AssistantCatalogBinding,
        override val modelProfileKey: String,
        override val packageId: String,
        override val intentKey: String,
        val rule: AssistantPresenceRule,
    ) : MonitorConfigurationProposal {
        override val kind = AssistantProposalKind.REFERENCE_IMAGES

        init {
            validateCommonProposal(title, modelProfileKey, packageId, intentKey)
        }
    }

    data class VisualDescription(
        override val title: String,
        override val catalogBinding: AssistantCatalogBinding,
        override val modelProfileKey: String,
        override val packageId: String,
        override val intentKey: String,
        val targetId: String,
        val displayText: String,
        val rule: AssistantPresenceRule,
    ) : MonitorConfigurationProposal {
        override val kind = AssistantProposalKind.VISUAL_DESCRIPTION

        init {
            validateCommonProposal(title, modelProfileKey, packageId, intentKey)
            require(TARGET_ID.matches(targetId)) { "invalid targetId" }
            requireStrictText(displayText, 100, "displayText")
        }
    }

    data class StructuredReading(
        override val title: String,
        override val catalogBinding: AssistantCatalogBinding,
        override val modelProfileKey: String,
        override val packageId: String,
        override val intentKey: String,
        val rule: AssistantReadingRule,
    ) : MonitorConfigurationProposal {
        override val kind = AssistantProposalKind.STRUCTURED_READING

        init {
            validateCommonProposal(title, modelProfileKey, packageId, intentKey)
        }
    }
}

internal sealed interface AssistantTurnResult {
    data class Message(val content: String) : AssistantTurnResult {
        init {
            requireStrictText(content, 600, "content")
        }
    }

    data class Proposal(val proposal: MonitorConfigurationProposal) : AssistantTurnResult
}

internal data class AssistantTurnResponse(
    val conversationId: String,
    val turnId: String,
    val result: AssistantTurnResult,
) {
    init {
        require(UUID.matches(conversationId))
        require(UUID.matches(turnId))
    }
}

internal sealed interface AssistantGatewayResult {
    data class Completed(val response: AssistantTurnResponse) : AssistantGatewayResult
    data object SignInRequired : AssistantGatewayResult
    data object SubscriptionRequired : AssistantGatewayResult
    data object InvalidRequest : AssistantGatewayResult
    data object Unavailable : AssistantGatewayResult
}

internal fun interface MonitorAssistantGateway {
    suspend fun turn(request: AssistantTurnRequest): AssistantGatewayResult
}

internal fun validateProposalAgainstCatalog(
    proposal: MonitorConfigurationProposal,
    catalog: AssistantCatalogSnapshot,
): Boolean {
    if (proposal.catalogBinding != catalog.binding) return false
    val profile = catalog.modelProfiles.singleOrNull {
        it.modelProfileKey == proposal.modelProfileKey && it.packageId == proposal.packageId
    } ?: return false
    if (profile.kind != proposal.kind || !profile.authorizesIntent(proposal.intentKey)) return false
    return when (proposal) {
        is MonitorConfigurationProposal.ReferenceImages -> true
        is MonitorConfigurationProposal.StructuredReading -> true
        is MonitorConfigurationProposal.VisualDescription -> {
            val descriptor = profile.targets.singleOrNull { it.targetId == proposal.targetId }
                ?: return false
            proposal.targetId in catalog.currentExactObjectTargetIds &&
                proposal.displayText in
                (descriptor.aliases + descriptor.labelZhCn + descriptor.labelEn)
        }
    }
}

private fun validateCommonProposal(
    title: String,
    modelProfileKey: String,
    packageId: String,
    intentKey: String,
) {
    requireStrictText(title, 100, "title")
    requireIdentifier(modelProfileKey, "modelProfileKey")
    requireIdentifier(packageId, "packageId")
    require(codePointLength(intentKey) <= 160 && INTENT_KEY.matches(intentKey)) { "invalid intentKey" }
}

private fun requireIdentifier(value: String, name: String) {
    require(codePointLength(value) <= 256 && IDENTIFIER.matches(value)) { "invalid $name" }
}

private fun requireCanonicalDecimal(value: String, name: String) {
    require(value.length <= 64 && DECIMAL.matches(value) && !Regex("^-0(?:\\.0+)?$").matches(value)) {
        "invalid $name"
    }
}

private fun requireStrictText(value: String, maximumCodePoints: Int, name: String) {
    require(
        value == value.trim() &&
            codePointLength(value) in 1..maximumCodePoints &&
            !FORBIDDEN_TEXT_CONTROLS.containsMatchIn(value),
    ) { "invalid $name" }
}

private fun codePointLength(value: String): Int = value.codePointCount(0, value.length)
