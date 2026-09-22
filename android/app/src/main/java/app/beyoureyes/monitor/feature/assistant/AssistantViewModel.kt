package app.beyoureyes.monitor.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.monitor.feature.reading.ReadingConditionDraft
import app.beyoureyes.monitor.feature.reading.ReadingConditionMode
import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class AssistantIssue {
    SIGN_IN_REQUIRED,
    SUBSCRIPTION_REQUIRED,
    SUBSCRIPTION_VERIFICATION_REQUIRED,
    INVALID_REQUEST,
    UNAVAILABLE,
}

internal enum class AssistantVoiceState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
}

internal enum class AssistantVoiceIssue {
    SIGN_IN_REQUIRED,
    SUBSCRIPTION_REQUIRED,
    SUBSCRIPTION_VERIFICATION_REQUIRED,
    PERMISSION_REQUIRED,
    TOO_SHORT,
    UNAVAILABLE,
}

internal data class AssistantUiState(
    val messages: List<AssistantConversationMessage> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val issue: AssistantIssue? = null,
    val proposal: MonitorConfigurationProposal? = null,
    val selectedModelName: String? = null,
    val voiceState: AssistantVoiceState = AssistantVoiceState.IDLE,
    val voiceIssue: AssistantVoiceIssue? = null,
    val starterTarget: AssistantTargetDescriptor? = null,
) {
    init {
        require((proposal == null) == (selectedModelName == null))
        selectedModelName?.let { require(it.isNotBlank()) }
    }
}

internal const val ASSISTANT_DRAFT_RETAINED_MESSAGE = "__assistant_draft_retained__"

/** One in-memory conversation. It has no Room, model preparation, camera, or service side effects. */
internal class AssistantViewModel(
    private val gateway: MonitorAssistantGateway,
    private val catalogProvider: AssistantCatalogSnapshotProvider,
    private val voiceGateway: VoiceTranscriptionGateway = DisabledVoiceTranscriptionGateway,
    private val accessDecision: () -> ProductAccessDecision,
    private val languageTag: () -> String = { "en" },
) : ViewModel() {
    private val mutableState = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = mutableState.asStateFlow()

    private var conversationId = UUID.randomUUID().toString()
    private var catalog: AssistantCatalogSnapshot? = null
    private var pendingRequest: AssistantTurnRequest? = null
    private var pendingUserText: String? = null
    private var turnJob: Job? = null
    private var voiceJob: Job? = null
    private var starterJob: Job? = null

    fun loadStarterTarget() {
        if (currentAssistantAccessIssue() != null || catalog != null ||
            starterJob?.isActive == true || mutableState.value.messages.isNotEmpty()
        ) return
        starterJob = viewModelScope.launch {
            try {
                val loaded = catalogProvider.load()
                if (currentAssistantAccessIssue() != null) return@launch
                catalog = loaded
                mutableState.value = mutableState.value.copy(
                    starterTarget = loaded.modelProfiles
                        .firstOrNull { it.kind == AssistantProposalKind.VISUAL_DESCRIPTION }
                        ?.targets?.firstOrNull { it.targetId in loaded.currentExactObjectTargetIds },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // An optional example must not prevent typing or the normal send/retry flow.
            }
        }
    }

    fun setInput(value: String) {
        if (mutableState.value.sending || pendingRequest != null ||
            mutableState.value.voiceState != AssistantVoiceState.IDLE
        ) return
        mutableState.value = mutableState.value.copy(
            input = value.take(MAX_INPUT_LENGTH),
            issue = null,
            voiceIssue = null,
        )
    }

    fun send() {
        val text = mutableState.value.input.trim()
        if (text.isEmpty() || mutableState.value.sending || pendingRequest != null ||
            mutableState.value.voiceState != AssistantVoiceState.IDLE
        ) return
        val accessIssue = currentAssistantAccessIssue()
        val userMessage = AssistantConversationMessage(AssistantConversationRole.USER, text)
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages + userMessage,
            input = "",
            sending = accessIssue == null,
            issue = accessIssue,
        )
        pendingUserText = text
        if (accessIssue == null) runTurn(request = null)
    }

    /** Check before Android's microphone permission dialog is requested. */
    fun voiceAccessGranted(): Boolean = currentVoiceAccessIssue()?.let { issue ->
        mutableState.value = mutableState.value.copy(
            voiceState = AssistantVoiceState.IDLE,
            voiceIssue = issue,
        )
        false
    } ?: true

    /** Rechecks the same server-authoritative state immediately before recorder.start(). */
    fun voiceCaptureStarted(): Boolean {
        if (!voiceAccessGranted()) return false
        if (mutableState.value.sending || pendingRequest != null ||
            mutableState.value.voiceState != AssistantVoiceState.IDLE
        ) return false
        mutableState.value = mutableState.value.copy(
            voiceState = AssistantVoiceState.RECORDING,
            voiceIssue = null,
            issue = null,
        )
        return true
    }

    fun voiceCaptureCancelled(tooShort: Boolean = false) {
        if (mutableState.value.voiceState != AssistantVoiceState.RECORDING) return
        mutableState.value = mutableState.value.copy(
            voiceState = AssistantVoiceState.IDLE,
            voiceIssue = if (tooShort) AssistantVoiceIssue.TOO_SHORT else null,
        )
    }

    fun voicePermissionRequired() {
        if (mutableState.value.voiceState != AssistantVoiceState.IDLE) return
        mutableState.value = mutableState.value.copy(
            voiceIssue = AssistantVoiceIssue.PERMISSION_REQUIRED,
        )
    }

    fun voicePermissionResolved(granted: Boolean) {
        if (mutableState.value.voiceState != AssistantVoiceState.IDLE) return
        mutableState.value = mutableState.value.copy(
            voiceIssue = if (granted) {
                mutableState.value.voiceIssue.takeUnless {
                    it == AssistantVoiceIssue.PERMISSION_REQUIRED
                }
            } else {
                AssistantVoiceIssue.PERMISSION_REQUIRED
            },
        )
    }

    fun voiceCaptureUnavailable() {
        if (mutableState.value.voiceState != AssistantVoiceState.RECORDING) return
        voiceFailed(AssistantVoiceIssue.UNAVAILABLE)
    }

    fun submitVoice(recording: CapturedVoiceRecording) {
        val accessIssue = currentVoiceAccessIssue()
        if (accessIssue != null || mutableState.value.voiceState != AssistantVoiceState.RECORDING) {
            recording.delete()
            mutableState.value = mutableState.value.copy(
                voiceState = AssistantVoiceState.IDLE,
                voiceIssue = accessIssue,
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            voiceState = AssistantVoiceState.TRANSCRIBING,
            voiceIssue = null,
        )
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            try {
                currentVoiceAccessIssue()?.let { issue ->
                    voiceFailed(issue)
                    return@launch
                }
                when (val result = voiceGateway.transcribe(recording, languageTag())) {
                    is VoiceTranscriptionResult.Completed -> submitTranscribedText(result.text)
                    VoiceTranscriptionResult.SignInRequired -> voiceFailed(
                        AssistantVoiceIssue.SIGN_IN_REQUIRED,
                    )
                    VoiceTranscriptionResult.SubscriptionRequired -> voiceFailed(
                        AssistantVoiceIssue.SUBSCRIPTION_REQUIRED,
                    )
                    VoiceTranscriptionResult.InvalidRecording -> voiceFailed(
                        AssistantVoiceIssue.TOO_SHORT,
                    )
                    VoiceTranscriptionResult.Unavailable -> voiceFailed(
                        AssistantVoiceIssue.UNAVAILABLE,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                voiceFailed(AssistantVoiceIssue.UNAVAILABLE)
            } finally {
                recording.delete()
            }
        }
    }

    fun retry() {
        if (pendingUserText == null) return
        val request = pendingRequest
        if (mutableState.value.sending) return
        currentAssistantAccessIssue()?.let { issue ->
            mutableState.value = mutableState.value.copy(sending = false, issue = issue)
            return
        }
        mutableState.value = mutableState.value.copy(sending = true, issue = null)
        runTurn(request)
    }

    fun cancelFailedTurn() {
        val text = pendingUserText ?: return
        if (mutableState.value.sending) return
        pendingRequest = null
        pendingUserText = null
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages.dropLastWhile {
                it.role == AssistantConversationRole.USER && it.content == text
            },
            input = text,
            issue = null,
        )
    }

    /** Returns from the proposal to the same conversation so the user can refine it in context. */
    fun backToConversation() {
        val current = mutableState.value
        if (current.proposal == null) return
        mutableState.value = current.copy(
            messages = current.messages + AssistantConversationMessage(
                role = AssistantConversationRole.ASSISTANT,
                content = ASSISTANT_DRAFT_RETAINED_MESSAGE,
            ),
            proposal = null,
            selectedModelName = null,
            issue = null,
        )
    }

    private fun submitTranscribedText(value: String) {
        val text = value.trim().take(MAX_INPUT_LENGTH)
        if (text.isEmpty()) {
            voiceFailed(AssistantVoiceIssue.UNAVAILABLE)
            return
        }
        currentAssistantAccessIssue()?.let { issue ->
            mutableState.value = mutableState.value.copy(
                input = text,
                sending = false,
                issue = issue,
                voiceState = AssistantVoiceState.IDLE,
                voiceIssue = currentVoiceAccessIssue(),
            )
            return
        }
        val userMessage = AssistantConversationMessage(AssistantConversationRole.USER, text)
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages + userMessage,
            input = "",
            sending = true,
            issue = null,
            voiceState = AssistantVoiceState.IDLE,
            voiceIssue = null,
        )
        pendingUserText = text
        runTurn(request = null)
    }

    private fun voiceFailed(issue: AssistantVoiceIssue) {
        mutableState.value = mutableState.value.copy(
            voiceState = AssistantVoiceState.IDLE,
            voiceIssue = issue,
        )
    }

    private fun runTurn(request: AssistantTurnRequest?) {
        turnJob = viewModelScope.launch {
            try {
                currentAssistantAccessIssue()?.let { issue ->
                    fail(issue)
                    return@launch
                }
                starterJob?.join()
                val currentCatalog = catalog ?: catalogProvider.load().also { catalog = it }
                val outgoing = request ?: AssistantTurnRequest(
                    conversationId = conversationId,
                    turnId = UUID.randomUUID().toString(),
                    locale = languageTag(),
                    catalog = currentCatalog,
                    messages = requestMessages(mutableState.value.messages),
                ).also { pendingRequest = it }
                currentAssistantAccessIssue()?.let { issue ->
                    fail(issue)
                    return@launch
                }
                when (val outcome = gateway.turn(outgoing)) {
                    is AssistantGatewayResult.Completed -> accept(outcome.response, currentCatalog)
                    AssistantGatewayResult.SignInRequired -> fail(AssistantIssue.SIGN_IN_REQUIRED)
                    AssistantGatewayResult.SubscriptionRequired -> fail(
                        AssistantIssue.SUBSCRIPTION_REQUIRED,
                    )
                    AssistantGatewayResult.InvalidRequest -> invalidRequest()
                    AssistantGatewayResult.Unavailable -> fail(AssistantIssue.UNAVAILABLE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(AssistantIssue.UNAVAILABLE)
            }
        }
    }

    private fun accept(response: AssistantTurnResponse, currentCatalog: AssistantCatalogSnapshot) {
        pendingRequest = null
        pendingUserText = null
        mutableState.value = when (val result = response.result) {
            is AssistantTurnResult.Message -> mutableState.value.copy(
                messages = mutableState.value.messages + AssistantConversationMessage(
                    AssistantConversationRole.ASSISTANT,
                    result.content,
                ),
                sending = false,
                issue = null,
            )
            is AssistantTurnResult.Proposal -> mutableState.value.copy(
                sending = false,
                issue = null,
                proposal = result.proposal,
                selectedModelName = currentCatalog.modelProfiles.single {
                    it.modelProfileKey == result.proposal.modelProfileKey &&
                        it.packageId == result.proposal.packageId
                }.displayName,
            )
        }
    }

    private fun fail(issue: AssistantIssue) {
        mutableState.value = mutableState.value.copy(sending = false, issue = issue)
    }

    private fun invalidRequest() {
        val text = pendingUserText.orEmpty()
        pendingRequest = null
        pendingUserText = null
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages.dropLast(1),
            input = text,
            sending = false,
            issue = AssistantIssue.INVALID_REQUEST,
        )
    }

    private fun currentAssistantAccessIssue(): AssistantIssue? =
        accessDecision().assistantIssueOrNull()

    private fun currentVoiceAccessIssue(): AssistantVoiceIssue? =
        accessDecision().assistantVoiceIssueOrNull()

    private companion object {
        const val MAX_INPUT_LENGTH = 500

        /** A request may carry at most ten alternating turns; discard complete oldest pairs only. */
        fun requestMessages(messages: List<AssistantConversationMessage>): List<AssistantConversationMessage> =
            messages.takeLast(19)
    }
}

internal fun ProductAccessDecision.assistantIssueOrNull(): AssistantIssue? = when (this) {
    ProductAccessDecision.GRANTED -> null
    ProductAccessDecision.SIGN_IN_REQUIRED -> AssistantIssue.SIGN_IN_REQUIRED
    ProductAccessDecision.PRO_REQUIRED -> AssistantIssue.SUBSCRIPTION_REQUIRED
    ProductAccessDecision.VERIFICATION_REQUIRED ->
        AssistantIssue.SUBSCRIPTION_VERIFICATION_REQUIRED
}

internal fun ProductAccessDecision.assistantVoiceIssueOrNull(): AssistantVoiceIssue? = when (this) {
    ProductAccessDecision.GRANTED -> null
    ProductAccessDecision.SIGN_IN_REQUIRED -> AssistantVoiceIssue.SIGN_IN_REQUIRED
    ProductAccessDecision.PRO_REQUIRED -> AssistantVoiceIssue.SUBSCRIPTION_REQUIRED
    ProductAccessDecision.VERIFICATION_REQUIRED ->
        AssistantVoiceIssue.SUBSCRIPTION_VERIFICATION_REQUIRED
}

internal fun MonitorConfigurationProposal.StructuredReading.toReadingConditionDraft(): ReadingConditionDraft =
    when (val readingRule = rule) {
        is AssistantReadingRule.Single -> ReadingConditionDraft(
            mode = when (readingRule.condition) {
                AssistantReadingCondition.ABOVE -> ReadingConditionMode.ABOVE
                AssistantReadingCondition.BELOW -> ReadingConditionMode.BELOW
            },
            threshold = readingRule.thresholdDecimal,
            durationSeconds = readingRule.durationSeconds,
        )
        is AssistantReadingRule.Outside -> ReadingConditionDraft(
            mode = ReadingConditionMode.OUTSIDE,
            lowerThreshold = readingRule.lowerThresholdDecimal,
            upperThreshold = readingRule.upperThresholdDecimal,
            durationSeconds = readingRule.durationSeconds,
        )
    }

internal fun AssistantPresenceRule.toMonitorRule(): MonitorRule.TargetPresence =
    MonitorRule.TargetPresence(
        kind = when (condition) {
            AssistantPresenceCondition.APPEARS -> PresenceRuleKind.APPEARS
            AssistantPresenceCondition.REMAINS -> PresenceRuleKind.REMAINS
            AssistantPresenceCondition.DISAPPEARS -> PresenceRuleKind.DISAPPEARS
        },
        durationSeconds = durationSeconds,
    )
