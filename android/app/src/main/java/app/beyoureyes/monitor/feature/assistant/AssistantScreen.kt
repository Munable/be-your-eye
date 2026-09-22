package app.beyoureyes.monitor.feature.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import app.beyoureyes.monitor.feature.account.currentAppLanguageTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.ProductIconBadge
import app.beyoureyes.monitor.design.ProductPanel
import app.beyoureyes.monitor.design.ProductPrimaryButton
import app.beyoureyes.monitor.design.ProductTone
import app.beyoureyes.monitor.design.durationSecondsLabel
import app.beyoureyes.monitor.design.ProductTopBar
import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import kotlinx.coroutines.delay

internal object AssistantTags {
    const val SCREEN = "assistant"
    const val INPUT = "assistant_input"
    const val STARTER = "assistant_starter"
    const val TARGET_STARTER = "assistant_target_starter"
    const val SEND = "assistant_send"
    const val VOICE = "assistant_voice"
    const val SIGN_IN = "assistant_sign_in"
    const val ACCESS_ACTION = "assistant_access_action"
    const val RETRY = "assistant_retry"
    const val PROPOSAL = "assistant_proposal"
    const val CONFIRM = "assistant_proposal_confirm"
}

private val ASSISTANT_ACCESS_ISSUES = setOf(
    AssistantIssue.SIGN_IN_REQUIRED,
    AssistantIssue.SUBSCRIPTION_REQUIRED,
    AssistantIssue.SUBSCRIPTION_VERIFICATION_REQUIRED,
)

@Composable
internal fun AssistantScreen(
    viewModel: AssistantViewModel,
    accessDecision: ProductAccessDecision,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onConfirmProposal: (MonitorConfigurationProposal) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(accessDecision) {
        if (accessDecision == ProductAccessDecision.GRANTED) {
            viewModel.loadStarterTarget()
            if (state.issue in ASSISTANT_ACCESS_ISSUES) viewModel.retry()
        }
    }
    val proposal = state.proposal
    if (proposal != null) {
        AssistantProposalScreen(
            proposal = proposal,
            selectedModelName = requireNotNull(state.selectedModelName),
            onBackToChat = viewModel::backToConversation,
            onConfirm = { onConfirmProposal(proposal) },
        )
    } else {
        AssistantConversationScreen(
            state = state,
            onInputChange = viewModel::setInput,
            onSend = viewModel::send,
            onRetry = viewModel::retry,
            onCancelFailedTurn = viewModel::cancelFailedTurn,
            onOpenAccount = onOpenAccount,
            onBack = onBack,
            accessDecision = accessDecision,
            onVoiceAccessCheck = viewModel::voiceAccessGranted,
            onVoiceCaptureStarted = viewModel::voiceCaptureStarted,
            onVoiceCaptureCancelled = viewModel::voiceCaptureCancelled,
            onVoiceCaptureUnavailable = viewModel::voiceCaptureUnavailable,
            onVoicePermissionRequired = viewModel::voicePermissionRequired,
            onVoicePermissionResolved = viewModel::voicePermissionResolved,
            onVoiceCaptured = viewModel::submitVoice,
        )
    }
}

@Composable
private fun AssistantConversationScreen(
    state: AssistantUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onCancelFailedTurn: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
    accessDecision: ProductAccessDecision,
    onVoiceAccessCheck: () -> Boolean,
    onVoiceCaptureStarted: () -> Boolean,
    onVoiceCaptureCancelled: (Boolean) -> Unit,
    onVoiceCaptureUnavailable: () -> Unit,
    onVoicePermissionRequired: () -> Unit,
    onVoicePermissionResolved: (Boolean) -> Unit,
    onVoiceCaptured: (CapturedVoiceRecording) -> Unit,
) {
    BackHandler(onBack = onBack)
    val listState = rememberLazyListState()
    val initialMessageCount = remember { state.messages.size }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val accessBlocked = accessDecision != ProductAccessDecision.GRANTED
    val composerEnabled = !accessBlocked && !state.sending &&
        state.issue !in ASSISTANT_ACCESS_ISSUES && state.issue != AssistantIssue.UNAVAILABLE &&
        state.voiceState == AssistantVoiceState.IDLE
    val showStarter = composerEnabled && state.messages.isEmpty() &&
        state.input.isBlank() && state.issue == null
    val starterText = stringResource(R.string.assistant_starter_reading)
    val targetStarterText = state.starterTarget?.let { target ->
        val label = target.localizedLabel(currentAppLanguageTag(LocalContext.current))
        stringResource(R.string.assistant_starter_target, label)
    }
    val issueVisible = state.issue != null && !(accessBlocked && state.issue in ASSISTANT_ACCESS_ISSUES)
    val itemCount = 1 + state.messages.size + (if (accessBlocked) 1 else 0) +
        (if (state.sending) 1 else 0) + (if (issueVisible) 1 else 0)
    LaunchedEffect(state.messages.size, state.sending, issueVisible, accessBlocked) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    Surface(
        modifier = Modifier.fillMaxSize().testTag(AssistantTags.SCREEN),
        color = ProductColors.Background,
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ProductTopBar(
                title = stringResource(R.string.assistant_title),
                eyebrow = stringResource(R.string.assistant_eyebrow),
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.assistant_privacy_note),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                color = ProductColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "welcome") {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        AssistantBubble(
                            role = AssistantConversationRole.ASSISTANT,
                            text = stringResource(R.string.assistant_welcome),
                        )
                        if (showStarter) {
                            AssistantStarterCard(
                                title = stringResource(R.string.assistant_starter_title),
                                text = starterText,
                                icon = Icons.Outlined.Tune,
                                modifier = Modifier.testTag(AssistantTags.STARTER),
                                onClick = {
                                    onInputChange(starterText)
                                    inputFocusRequester.requestFocus()
                                    keyboardController?.show()
                                },
                            )
                            if (targetStarterText != null) {
                                AssistantStarterCard(
                                    title = stringResource(R.string.assistant_starter_target_title),
                                    text = targetStarterText,
                                    icon = Icons.Outlined.Visibility,
                                    modifier = Modifier.testTag(AssistantTags.TARGET_STARTER),
                                    onClick = {
                                        onInputChange(targetStarterText)
                                        inputFocusRequester.requestFocus()
                                        keyboardController?.show()
                                    },
                                )
                            }
                        }
                    }
                }
                itemsIndexed(
                    state.messages,
                    key = { index, message -> "message-$index-${message.role}" },
                ) { index, message ->
                    AssistantBubble(
                        message.role,
                        message.content,
                        modifier = Modifier.animateItem(
                            fadeInSpec = if (index >= initialMessageCount) tween(180) else null,
                            placementSpec = tween(220),
                            fadeOutSpec = tween(120),
                        ),
                    )
                }
                if (accessDecision != ProductAccessDecision.GRANTED) {
                    item(key = "access") {
                        AssistantAccessCard(
                            decision = accessDecision,
                            onOpenAccount = onOpenAccount,
                        )
                    }
                }
                if (state.sending) item(key = "thinking") {
                    AssistantThinkingBubble(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(180),
                            placementSpec = tween(220),
                            fadeOutSpec = tween(120),
                        ),
                    )
                }
                state.issue?.takeUnless {
                    accessDecision != ProductAccessDecision.GRANTED &&
                        it in ASSISTANT_ACCESS_ISSUES
                }?.let { issue ->
                    item(key = "issue") {
                        AssistantIssueCard(
                            issue = issue,
                            onRetry = onRetry,
                            onCancel = onCancelFailedTurn,
                            onOpenAccount = onOpenAccount,
                        )
                    }
                }
            }
            AssistantComposer(
                value = state.input,
                enabled = composerEnabled,
                focusRequester = inputFocusRequester,
                onValueChange = onInputChange,
                onSend = onSend,
                accessDecision = accessDecision,
                voiceState = state.voiceState,
                voiceIssue = state.voiceIssue,
                onOpenAccount = onOpenAccount,
                onVoiceAccessCheck = onVoiceAccessCheck,
                onVoiceCaptureStarted = onVoiceCaptureStarted,
                onVoiceCaptureCancelled = onVoiceCaptureCancelled,
                onVoiceCaptureUnavailable = onVoiceCaptureUnavailable,
                onVoicePermissionRequired = onVoicePermissionRequired,
                onVoicePermissionResolved = onVoicePermissionResolved,
                onVoiceCaptured = onVoiceCaptured,
            )
        }
    }
}

@Composable
private fun AssistantStarterCard(
    title: String,
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ProductPanel(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProductIconBadge(
                icon,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    title,
                    color = ProductColors.TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text,
                    color = ProductColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = ProductColors.Cyan,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    role: AssistantConversationRole,
    text: String,
    modifier: Modifier = Modifier,
) {
    val user = role == AssistantConversationRole.USER
    val displayedText = if (!user && text == ASSISTANT_DRAFT_RETAINED_MESSAGE) {
        stringResource(R.string.assistant_draft_retained)
    } else {
        text
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (user) ProductColors.Cyan else ProductColors.Surface,
            contentColor = if (user) androidx.compose.ui.graphics.Color(0xFF002F34) else ProductColors.TextPrimary,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (user) 18.dp else 5.dp,
                bottomEnd = if (user) 5.dp else 18.dp,
            ),
        ) {
            Text(
                displayedText,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)
                    .fillMaxWidth(0.84f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AssistantThinkingBubble(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(color = ProductColors.Surface, shape = RoundedCornerShape(18.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.assistant_thinking),
                    modifier = Modifier.padding(start = 9.dp),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AssistantIssueCard(
    issue: AssistantIssue,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val title: String
    val body: String
    when (issue) {
        AssistantIssue.SIGN_IN_REQUIRED -> {
            title = stringResource(R.string.assistant_issue_sign_in_title)
            body = stringResource(R.string.assistant_issue_sign_in_body)
        }
        AssistantIssue.SUBSCRIPTION_REQUIRED -> {
            title = stringResource(R.string.assistant_issue_subscription_title)
            body = stringResource(R.string.assistant_issue_subscription_body)
        }
        AssistantIssue.SUBSCRIPTION_VERIFICATION_REQUIRED -> {
            title = stringResource(R.string.assistant_issue_verification_title)
            body = stringResource(R.string.assistant_issue_verification_body)
        }
        AssistantIssue.INVALID_REQUEST -> {
            title = stringResource(R.string.assistant_issue_invalid_title)
            body = stringResource(R.string.assistant_issue_invalid_body)
        }
        AssistantIssue.UNAVAILABLE -> {
            title = stringResource(R.string.assistant_issue_unavailable_title)
            body = stringResource(R.string.assistant_issue_unavailable_body)
        }
    }
    ProductPanel(tone = ProductTone.WAITING) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                modifier = Modifier.padding(top = 4.dp),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            when (issue) {
                AssistantIssue.SIGN_IN_REQUIRED -> ProductPrimaryButton(
                    text = stringResource(R.string.action_open_account),
                    onClick = onOpenAccount,
                    leadingIcon = Icons.Outlined.Lock,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .testTag(AssistantTags.SIGN_IN),
                )
                AssistantIssue.SUBSCRIPTION_REQUIRED -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProductPrimaryButton(
                        text = stringResource(R.string.action_open_account),
                        onClick = onOpenAccount,
                        leadingIcon = Icons.Outlined.Lock,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.assistant_action_subscribed_retry)) }
                    }
                }
                AssistantIssue.SUBSCRIPTION_VERIFICATION_REQUIRED -> ProductPrimaryButton(
                    text = stringResource(R.string.action_check_subscription),
                    onClick = onOpenAccount,
                    leadingIcon = Icons.Outlined.Lock,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .testTag(AssistantTags.ACCESS_ACTION),
                )
                AssistantIssue.UNAVAILABLE -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.assistant_action_edit_description)) }
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag(AssistantTags.RETRY),
                    ) { Text(stringResource(R.string.action_retry)) }
                }
                AssistantIssue.INVALID_REQUEST -> Unit
            }
        }
    }
}

@Composable
private fun AssistantAccessCard(
    decision: ProductAccessDecision,
    onOpenAccount: () -> Unit,
) {
    val presentation = assistantAccessPresentation(decision)
    ProductPanel(tone = ProductTone.WAITING) {
        Column {
            Text(stringResource(presentation.titleRes), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(presentation.bodyRes),
                modifier = Modifier.padding(top = 4.dp),
                color = ProductColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            ProductPrimaryButton(
                text = stringResource(presentation.actionRes),
                onClick = onOpenAccount,
                leadingIcon = Icons.Outlined.Lock,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .testTag(AssistantTags.ACCESS_ACTION),
            )
        }
    }
}

internal data class AssistantAccessPresentation(
    val titleRes: Int,
    val bodyRes: Int,
    val actionRes: Int,
)

internal fun assistantAccessPresentation(
    decision: ProductAccessDecision,
): AssistantAccessPresentation = when (decision) {
    ProductAccessDecision.GRANTED -> error("granted access has no presentation")
    ProductAccessDecision.SIGN_IN_REQUIRED -> AssistantAccessPresentation(
        titleRes = R.string.assistant_access_sign_in_title,
        bodyRes = R.string.assistant_access_sign_in_body,
        actionRes = R.string.action_open_account,
    )
    ProductAccessDecision.PRO_REQUIRED -> AssistantAccessPresentation(
        titleRes = R.string.assistant_access_subscription_title,
        bodyRes = R.string.assistant_access_subscription_body,
        actionRes = R.string.action_open_account,
    )
    ProductAccessDecision.VERIFICATION_REQUIRED -> AssistantAccessPresentation(
        titleRes = R.string.assistant_issue_verification_title,
        bodyRes = R.string.assistant_access_verification_body,
        actionRes = R.string.action_check_subscription,
    )
}

@Composable
private fun AssistantComposer(
    value: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    accessDecision: ProductAccessDecision,
    voiceState: AssistantVoiceState,
    voiceIssue: AssistantVoiceIssue?,
    onOpenAccount: () -> Unit,
    onVoiceAccessCheck: () -> Boolean,
    onVoiceCaptureStarted: () -> Boolean,
    onVoiceCaptureCancelled: (Boolean) -> Unit,
    onVoiceCaptureUnavailable: () -> Unit,
    onVoicePermissionRequired: () -> Unit,
    onVoicePermissionResolved: (Boolean) -> Unit,
    onVoiceCaptured: (CapturedVoiceRecording) -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember(context) { AssistantVoiceRecorder(context) }
    var microphoneGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        microphoneGranted = granted
        onVoicePermissionResolved(granted)
    }
    DisposableEffect(recorder) {
        onDispose {
            recorder.close()
            onVoiceCaptureCancelled(false)
        }
    }
    LaunchedEffect(voiceState, recorder, accessDecision) {
        if (voiceState == AssistantVoiceState.RECORDING) {
            if (accessDecision != ProductAccessDecision.GRANTED) {
                recorder.cancel()
                onVoiceCaptureCancelled(false)
                return@LaunchedEffect
            }
            delay(MAX_VOICE_DURATION_MILLIS)
            val recording = recorder.stop()
            if (recording == null) {
                onVoiceCaptureCancelled(false)
            } else {
                onVoiceCaptured(recording)
            }
        }
    }
    Surface(color = ProductColors.Surface) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    placeholder = { Text(stringResource(R.string.assistant_input_example)) },
                    shape = RoundedCornerShape(20.dp),
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester)
                        .testTag(AssistantTags.INPUT),
                    trailingIcon = {
                        IconButton(
                            onClick = onSend,
                            enabled = enabled && value.isNotBlank(),
                            modifier = Modifier.padding(end = 4.dp).testTag(AssistantTags.SEND),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = ProductColors.Cyan,
                                contentColor = ProductColors.Background,
                                disabledContainerColor = ProductColors.SurfaceHighlight,
                                disabledContentColor = ProductColors.TextMuted,
                            ),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = stringResource(R.string.action_send),
                            )
                        }
                    },
                )
            }
            VoiceHoldButton(
                enabled = accessDecision == ProductAccessDecision.GRANTED &&
                    (enabled || voiceState == AssistantVoiceState.RECORDING),
                state = voiceState,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    .testTag(AssistantTags.VOICE),
                onTapToType = { focusRequester.requestFocus() },
                onPress = {
                    when {
                        !onVoiceAccessCheck() -> false
                        !microphoneGranted -> {
                            onVoicePermissionRequired()
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        }
                        !onVoiceCaptureStarted() -> false
                        !recorder.start() -> {
                            onVoiceCaptureUnavailable()
                            false
                        }
                        else -> true
                    }
                },
                onRelease = {
                    val recording = recorder.stop()
                    if (recording == null) {
                        onVoiceCaptureCancelled(true)
                    } else {
                        onVoiceCaptured(recording)
                    }
                },
                onCancel = {
                    recorder.cancel()
                    onVoiceCaptureCancelled(false)
                },
            )
            voiceIssue?.let { issue ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (issue) {
                            AssistantVoiceIssue.SIGN_IN_REQUIRED ->
                                stringResource(R.string.assistant_voice_sign_in_required)
                            AssistantVoiceIssue.SUBSCRIPTION_REQUIRED ->
                                stringResource(R.string.assistant_voice_subscription_required)
                            AssistantVoiceIssue.SUBSCRIPTION_VERIFICATION_REQUIRED ->
                                stringResource(R.string.assistant_voice_verification_required)
                            AssistantVoiceIssue.PERMISSION_REQUIRED ->
                                stringResource(R.string.assistant_voice_permission_required)
                            AssistantVoiceIssue.TOO_SHORT ->
                                stringResource(R.string.assistant_voice_too_short)
                            AssistantVoiceIssue.UNAVAILABLE ->
                                stringResource(R.string.assistant_voice_unavailable)
                        },
                        color = ProductColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (issue == AssistantVoiceIssue.SIGN_IN_REQUIRED ||
                        issue == AssistantVoiceIssue.SUBSCRIPTION_REQUIRED ||
                        issue == AssistantVoiceIssue.SUBSCRIPTION_VERIFICATION_REQUIRED
                    ) {
                        TextButton(onClick = onOpenAccount) {
                            Text(
                                if (issue == AssistantVoiceIssue.SUBSCRIPTION_VERIFICATION_REQUIRED) {
                                    stringResource(R.string.action_check_subscription)
                                } else {
                                    stringResource(R.string.action_open_account)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VoiceHoldButton(
    enabled: Boolean,
    state: AssistantVoiceState,
    modifier: Modifier,
    onTapToType: () -> Unit,
    onPress: () -> Boolean,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    val interactable = enabled && state != AssistantVoiceState.TRANSCRIBING
    val stateLabel = when (state) {
        AssistantVoiceState.IDLE -> stringResource(R.string.assistant_voice_idle)
        AssistantVoiceState.RECORDING -> stringResource(R.string.assistant_voice_recording)
        AssistantVoiceState.TRANSCRIBING -> stringResource(R.string.assistant_voice_transcribing)
    }
    val shape = RoundedCornerShape(24.dp)
    val startVoiceLabel = stringResource(R.string.assistant_voice_accessibility_start)
    val stopVoiceLabel = stringResource(R.string.assistant_voice_accessibility_stop)
    val cancelVoiceLabel = stringResource(R.string.assistant_voice_accessibility_cancel)
    Surface(
        color = if (state == AssistantVoiceState.RECORDING) {
            ProductColors.CyanSoft
        } else {
            ProductColors.Surface
        },
        contentColor = if (interactable) ProductColors.Cyan else ProductColors.TextMuted,
        shape = shape,
        border = BorderStroke(1.dp, ProductColors.Border),
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!interactable) disabled()
                onClick {
                    if (interactable) onTapToType()
                    interactable
                }
                contentDescription = stateLabel
                stateDescription = stateLabel
                customActions = when {
                    !interactable -> emptyList()
                    state == AssistantVoiceState.IDLE -> listOf(
                        CustomAccessibilityAction(startVoiceLabel) { onPress() },
                    )
                    state == AssistantVoiceState.RECORDING -> listOf(
                        CustomAccessibilityAction(stopVoiceLabel) {
                            onRelease()
                            true
                        },
                        CustomAccessibilityAction(cancelVoiceLabel) {
                            onCancel()
                            true
                        },
                    )
                    else -> emptyList()
                }
            }
            .pointerInput(enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                if (awaitLongPressOrCancellation(down.id) == null) {
                    val released = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (interactable && released?.previousPressed == true && !released.pressed) {
                        onTapToType()
                    }
                    return@awaitEachGesture
                }
                if (!enabled || state == AssistantVoiceState.TRANSCRIBING || !onPress()) {
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                if (waitForUpOrCancellation() != null) onRelease() else onCancel()
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state == AssistantVoiceState.TRANSCRIBING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.MicNone, contentDescription = null)
            }
            Text(
                stateLabel,
                Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistantProposalScreen(
    proposal: MonitorConfigurationProposal,
    selectedModelName: String,
    onBackToChat: () -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler(onBack = onBackToChat)
    Surface(
        modifier = Modifier.fillMaxSize().testTag(AssistantTags.PROPOSAL),
        color = ProductColors.Background,
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ProductTopBar(
                title = stringResource(R.string.assistant_proposal_title),
                eyebrow = stringResource(R.string.assistant_proposal_eyebrow),
                onBack = onBackToChat,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductIconBadge(Icons.Outlined.CheckCircle, null)
                        Column(Modifier.padding(start = 13.dp)) {
                            Text(
                                proposal.title,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                proposal.routeLabel(),
                                color = ProductColors.Cyan,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                if (proposal is MonitorConfigurationProposal.ReferenceImages) {
                    item { ProposalNextStepPanel(proposal) }
                }
                item {
                    ProductPanel {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProposalRow(stringResource(R.string.assistant_proposal_route), proposal.routeLabel())
                            ProposalRow(stringResource(R.string.assistant_proposal_target), proposal.targetLabel())
                            ProposalRow(stringResource(R.string.assistant_proposal_trigger), proposal.triggerLabel())
                            ProposalRow(
                                stringResource(R.string.assistant_proposal_duration),
                                durationSecondsLabel(proposal.durationSeconds()),
                            )
                            ProposalRow(
                                stringResource(R.string.assistant_proposal_local_notification),
                                stringResource(R.string.assistant_proposal_notification_prefilled),
                            )
                            ProposalRow(
                                stringResource(R.string.assistant_proposal_model),
                                assistantModelDisplayName(selectedModelName),
                            )
                        }
                    }
                }
                if (proposal !is MonitorConfigurationProposal.ReferenceImages) {
                    item { ProposalNextStepPanel(proposal) }
                }
                item {
                    Text(
                        stringResource(R.string.assistant_proposal_disclaimer),
                        color = ProductColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Surface(color = ProductColors.Background) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    ProductPrimaryButton(
                        text = stringResource(R.string.action_continue_setup),
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth().testTag(AssistantTags.CONFIRM),
                    )
                    TextButton(
                        onClick = onBackToChat,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.assistant_action_back_to_chat)) }
                }
            }
        }
    }
}

@Composable
private fun ProposalNextStepPanel(proposal: MonitorConfigurationProposal) {
    ProductPanel(tone = ProductTone.NEUTRAL) {
        Row(verticalAlignment = Alignment.Top) {
            ProductIconBadge(Icons.Outlined.Tune, null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(proposal.nextStepTitle(), style = MaterialTheme.typography.titleMedium)
                Text(
                    proposal.nextStepBody(),
                    modifier = Modifier.padding(top = 4.dp),
                    color = ProductColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ProposalRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = ProductColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            modifier = Modifier.padding(start = 20.dp).weight(1f),
            color = ProductColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MonitorConfigurationProposal.routeLabel(): String = when (this) {
    is MonitorConfigurationProposal.ReferenceImages -> stringResource(R.string.route_reference_images)
    is MonitorConfigurationProposal.VisualDescription -> stringResource(R.string.route_visual_description)
    is MonitorConfigurationProposal.StructuredReading -> stringResource(R.string.route_numeric_reading)
}

internal fun assistantModelDisplayName(selectedModelName: String): String =
    selectedModelName.also { require(it.isNotBlank()) }

@Composable
private fun MonitorConfigurationProposal.targetLabel(): String = when (this) {
    is MonitorConfigurationProposal.ReferenceImages -> title
    is MonitorConfigurationProposal.VisualDescription -> displayText
    is MonitorConfigurationProposal.StructuredReading -> stringResource(R.string.assistant_numeric_target)
}

@Composable
private fun MonitorConfigurationProposal.triggerLabel(): String = when (this) {
    is MonitorConfigurationProposal.ReferenceImages -> rule.condition.productLabel()
    is MonitorConfigurationProposal.VisualDescription -> rule.condition.productLabel()
    is MonitorConfigurationProposal.StructuredReading -> when (val readingRule = rule) {
        is AssistantReadingRule.Single -> when (readingRule.condition) {
            AssistantReadingCondition.ABOVE -> stringResource(
                R.string.condition_above_value,
                readingRule.thresholdDecimal,
            )
            AssistantReadingCondition.BELOW -> stringResource(
                R.string.condition_below_value,
                readingRule.thresholdDecimal,
            )
        }
        is AssistantReadingRule.Outside ->
            stringResource(
                R.string.condition_outside_values,
                readingRule.lowerThresholdDecimal,
                readingRule.upperThresholdDecimal,
            )
    }
}

@Composable
private fun AssistantPresenceCondition.productLabel(): String = when (this) {
    AssistantPresenceCondition.APPEARS -> stringResource(R.string.presence_appears)
    AssistantPresenceCondition.REMAINS -> stringResource(R.string.presence_remains)
    AssistantPresenceCondition.DISAPPEARS -> stringResource(R.string.presence_disappears)
}

private fun MonitorConfigurationProposal.durationSeconds(): Int = when (this) {
    is MonitorConfigurationProposal.ReferenceImages -> rule.durationSeconds
    is MonitorConfigurationProposal.VisualDescription -> rule.durationSeconds
    is MonitorConfigurationProposal.StructuredReading -> rule.durationSeconds
}

@Composable
private fun MonitorConfigurationProposal.nextStepTitle(): String = when (this) {
    is MonitorConfigurationProposal.ReferenceImages -> stringResource(R.string.assistant_reference_next_title)
    is MonitorConfigurationProposal.VisualDescription -> stringResource(R.string.assistant_no_upload_title)
    is MonitorConfigurationProposal.StructuredReading -> stringResource(R.string.assistant_no_upload_title)
}

@Composable
private fun MonitorConfigurationProposal.nextStepBody(): String = when (this) {
    is MonitorConfigurationProposal.ReferenceImages ->
        stringResource(R.string.assistant_reference_next_body)
    is MonitorConfigurationProposal.VisualDescription ->
        stringResource(R.string.assistant_visual_next_body)
    is MonitorConfigurationProposal.StructuredReading ->
        stringResource(R.string.assistant_reading_next_body)
}
