package app.beyoureyes.monitor.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-restorable configuration, including the editable monitor name shown to the user. */
internal object AssistantProposalSavedState {
    private const val PREFIX = "assistant_route_proposal."
    private const val VERSION = "2"

    private const val KEY_VERSION = "${PREFIX}version"
    private const val KEY_KIND = "${PREFIX}kind"
    private const val KEY_TITLE = "${PREFIX}title"
    private const val KEY_CATALOG_ID = "${PREFIX}catalog_id"
    private const val KEY_CATALOG_VERSION = "${PREFIX}catalog_version"
    private const val KEY_CATALOG_SHA256 = "${PREFIX}catalog_sha256"
    private const val KEY_MODEL_PROFILE = "${PREFIX}model_profile"
    private const val KEY_PACKAGE = "${PREFIX}package"
    private const val KEY_INTENT = "${PREFIX}intent"
    private const val KEY_TARGET_ID = "${PREFIX}target_id"
    private const val KEY_DISPLAY_TEXT = "${PREFIX}display_text"
    private const val KEY_PRESENCE_CONDITION = "${PREFIX}presence_condition"
    private const val KEY_READING_CONDITION = "${PREFIX}reading_condition"
    private const val KEY_THRESHOLD = "${PREFIX}threshold"
    private const val KEY_LOWER_THRESHOLD = "${PREFIX}lower_threshold"
    private const val KEY_UPPER_THRESHOLD = "${PREFIX}upper_threshold"
    private const val KEY_DURATION_SECONDS = "${PREFIX}duration_seconds"

    fun containsAny(handle: SavedStateHandle): Boolean =
        handle.keys().any { it.startsWith(PREFIX) }

    fun write(handle: SavedStateHandle, proposal: MonitorConfigurationProposal) {
        clear(handle)
        encode(proposal).forEach { (key, value) -> handle[key] = value }
    }

    fun decode(
        handle: SavedStateHandle,
        expectedKind: AssistantProposalKind,
        catalog: AssistantCatalogSnapshot,
    ): MonitorConfigurationProposal? {
        val values = handle.keys()
            .filter { it.startsWith(PREFIX) }
            .associateWith { key -> handle.get<Any>(key) }
        return decode(values, expectedKind, catalog)
    }

    fun clear(handle: SavedStateHandle) {
        handle.keys().filter { it.startsWith(PREFIX) }.forEach { key ->
            handle.remove<Any>(key)
        }
    }

    internal fun encode(proposal: MonitorConfigurationProposal): Map<String, Any> = buildMap {
        put(KEY_VERSION, VERSION)
        put(KEY_KIND, proposal.kind.wireValue)
        put(KEY_TITLE, proposal.title)
        put(KEY_CATALOG_ID, proposal.catalogBinding.catalogId)
        put(KEY_CATALOG_VERSION, proposal.catalogBinding.catalogVersion)
        put(KEY_CATALOG_SHA256, proposal.catalogBinding.catalogSignedPayloadSha256)
        put(KEY_MODEL_PROFILE, proposal.modelProfileKey)
        put(KEY_PACKAGE, proposal.packageId)
        put(KEY_INTENT, proposal.intentKey)
        when (proposal) {
            is MonitorConfigurationProposal.ReferenceImages -> putPresenceRule(proposal.rule)
            is MonitorConfigurationProposal.VisualDescription -> {
                put(KEY_TARGET_ID, proposal.targetId)
                put(KEY_DISPLAY_TEXT, proposal.displayText)
                putPresenceRule(proposal.rule)
            }
            is MonitorConfigurationProposal.StructuredReading -> when (val rule = proposal.rule) {
                is AssistantReadingRule.Single -> {
                    put(KEY_READING_CONDITION, rule.condition.wireValue)
                    put(KEY_THRESHOLD, rule.thresholdDecimal)
                    put(KEY_DURATION_SECONDS, rule.durationSeconds)
                }
                is AssistantReadingRule.Outside -> {
                    put(KEY_READING_CONDITION, "outside")
                    put(KEY_LOWER_THRESHOLD, rule.lowerThresholdDecimal)
                    put(KEY_UPPER_THRESHOLD, rule.upperThresholdDecimal)
                    put(KEY_DURATION_SECONDS, rule.durationSeconds)
                }
            }
        }
    }

    internal fun decode(
        values: Map<String, Any?>,
        expectedKind: AssistantProposalKind,
        catalog: AssistantCatalogSnapshot,
    ): MonitorConfigurationProposal? = runCatching {
        require(values.requiredString(KEY_VERSION) == VERSION)
        val kind = requireNotNull(
            AssistantProposalKind.fromWireValue(values.requiredString(KEY_KIND)),
        )
        require(kind == expectedKind)
        val binding = AssistantCatalogBinding(
            catalogId = values.requiredString(KEY_CATALOG_ID),
            catalogVersion = values.requiredString(KEY_CATALOG_VERSION),
            catalogSignedPayloadSha256 = values.requiredString(KEY_CATALOG_SHA256),
        )
        val common = CommonProposalFields(
            binding = binding,
            modelProfileKey = values.requiredString(KEY_MODEL_PROFILE),
            packageId = values.requiredString(KEY_PACKAGE),
            intentKey = values.requiredString(KEY_INTENT),
        )
        val proposal = when (kind) {
            AssistantProposalKind.REFERENCE_IMAGES -> {
                require(values.keys == COMMON_KEYS + PRESENCE_RULE_KEYS)
                MonitorConfigurationProposal.ReferenceImages(
                    title = values.requiredString(KEY_TITLE),
                    catalogBinding = common.binding,
                    modelProfileKey = common.modelProfileKey,
                    packageId = common.packageId,
                    intentKey = common.intentKey,
                    rule = values.presenceRule(),
                )
            }
            AssistantProposalKind.VISUAL_DESCRIPTION -> {
                require(values.keys == COMMON_KEYS + PRESENCE_RULE_KEYS + VISUAL_TARGET_KEYS)
                MonitorConfigurationProposal.VisualDescription(
                    title = values.requiredString(KEY_TITLE),
                    catalogBinding = common.binding,
                    modelProfileKey = common.modelProfileKey,
                    packageId = common.packageId,
                    intentKey = common.intentKey,
                    targetId = values.requiredString(KEY_TARGET_ID),
                    displayText = values.requiredString(KEY_DISPLAY_TEXT),
                    rule = values.presenceRule(),
                )
            }
            AssistantProposalKind.STRUCTURED_READING -> {
                val condition = values.requiredString(KEY_READING_CONDITION)
                val readingRule = when (condition) {
                    AssistantReadingCondition.ABOVE.wireValue,
                    AssistantReadingCondition.BELOW.wireValue,
                    -> {
                        require(values.keys == COMMON_KEYS + READING_SINGLE_KEYS)
                        AssistantReadingRule.Single(
                            condition = requireNotNull(
                                AssistantReadingCondition.fromWireValue(condition),
                            ),
                            thresholdDecimal = values.requiredString(KEY_THRESHOLD),
                            durationSeconds = values.requiredInt(KEY_DURATION_SECONDS),
                        )
                    }
                    "outside" -> {
                        require(values.keys == COMMON_KEYS + READING_OUTSIDE_KEYS)
                        AssistantReadingRule.Outside(
                            lowerThresholdDecimal = values.requiredString(KEY_LOWER_THRESHOLD),
                            upperThresholdDecimal = values.requiredString(KEY_UPPER_THRESHOLD),
                            durationSeconds = values.requiredInt(KEY_DURATION_SECONDS),
                        )
                    }
                    else -> error("unknown reading condition")
                }
                MonitorConfigurationProposal.StructuredReading(
                    title = values.requiredString(KEY_TITLE),
                    catalogBinding = common.binding,
                    modelProfileKey = common.modelProfileKey,
                    packageId = common.packageId,
                    intentKey = common.intentKey,
                    rule = readingRule,
                )
            }
        }
        require(validateProposalAgainstCatalog(proposal, catalog))
        proposal
    }.getOrNull()

    private fun MutableMap<String, Any>.putPresenceRule(rule: AssistantPresenceRule) {
        put(KEY_PRESENCE_CONDITION, rule.condition.wireValue)
        put(KEY_DURATION_SECONDS, rule.durationSeconds)
    }

    private fun Map<String, Any?>.presenceRule() = AssistantPresenceRule(
        condition = requireNotNull(
            AssistantPresenceCondition.fromWireValue(requiredString(KEY_PRESENCE_CONDITION)),
        ),
        durationSeconds = requiredInt(KEY_DURATION_SECONDS),
    )

    private fun Map<String, Any?>.requiredString(key: String): String =
        get(key) as? String ?: error("missing $key")

    private fun Map<String, Any?>.requiredInt(key: String): Int =
        get(key) as? Int ?: error("missing $key")

    private data class CommonProposalFields(
        val binding: AssistantCatalogBinding,
        val modelProfileKey: String,
        val packageId: String,
        val intentKey: String,
    )

    private val COMMON_KEYS = setOf(
        KEY_VERSION,
        KEY_KIND,
        KEY_TITLE,
        KEY_CATALOG_ID,
        KEY_CATALOG_VERSION,
        KEY_CATALOG_SHA256,
        KEY_MODEL_PROFILE,
        KEY_PACKAGE,
        KEY_INTENT,
    )
    private val PRESENCE_RULE_KEYS = setOf(KEY_PRESENCE_CONDITION, KEY_DURATION_SECONDS)
    private val VISUAL_TARGET_KEYS = setOf(KEY_TARGET_ID, KEY_DISPLAY_TEXT)
    private val READING_SINGLE_KEYS = setOf(
        KEY_READING_CONDITION,
        KEY_THRESHOLD,
        KEY_DURATION_SECONDS,
    )
    private val READING_OUTSIDE_KEYS = setOf(
        KEY_READING_CONDITION,
        KEY_LOWER_THRESHOLD,
        KEY_UPPER_THRESHOLD,
        KEY_DURATION_SECONDS,
    )
}

internal sealed interface AssistantProposalRecoveryState {
    data object ManualRoute : AssistantProposalRecoveryState
    data object Validating : AssistantProposalRecoveryState
    data class Ready(val proposal: MonitorConfigurationProposal) : AssistantProposalRecoveryState
    data class Rejected(val retryable: Boolean) : AssistantProposalRecoveryState
}

internal class AssistantProposalRecoveryViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val expectedKind: AssistantProposalKind,
    private val catalogProvider: AssistantCatalogSnapshotProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AssistantProposalRecoveryState>(
        AssistantProposalRecoveryState.Validating,
    )
    val state: StateFlow<AssistantProposalRecoveryState> = mutableState.asStateFlow()

    init {
        if (AssistantProposalSavedState.containsAny(savedStateHandle)) {
            validate()
        } else {
            mutableState.value = AssistantProposalRecoveryState.ManualRoute
        }
    }

    fun retry() {
        if (mutableState.value !is AssistantProposalRecoveryState.Rejected) return
        validate()
    }

    private fun validate() {
        mutableState.value = AssistantProposalRecoveryState.Validating
        viewModelScope.launch {
            try {
                val catalog = catalogProvider.load()
                val proposal = AssistantProposalSavedState.decode(
                    savedStateHandle,
                    expectedKind,
                    catalog,
                )
                mutableState.value = if (proposal == null) {
                    AssistantProposalRecoveryState.Rejected(retryable = false)
                } else {
                    AssistantProposalRecoveryState.Ready(proposal)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = AssistantProposalRecoveryState.Rejected(retryable = true)
            }
        }
    }
}

@Composable
internal fun AssistantProposalRecoveryScreen(
    state: AssistantProposalRecoveryState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = ProductColors.Background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (state == AssistantProposalRecoveryState.Validating) {
                CircularProgressIndicator(color = ProductColors.Cyan)
                Text(
                    stringResource(R.string.assistant_recovery_validating_title),
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.assistant_recovery_validating_body),
                    modifier = Modifier.padding(top = 8.dp),
                    color = ProductColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            } else {
                val retryable = (state as? AssistantProposalRecoveryState.Rejected)?.retryable == true
                Text(
                    stringResource(
                        if (retryable) R.string.assistant_recovery_retry_title
                        else R.string.assistant_recovery_invalid_title,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(
                        if (retryable) R.string.assistant_recovery_retry_body
                        else R.string.assistant_recovery_invalid_body,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    color = ProductColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                if (retryable) {
                    Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.action_back))
                }
            }
        }
    }
}
