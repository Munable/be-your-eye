package app.beyoureyes.monitor.feature.assistant

import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {
    @Test
    fun `starter uses a signed target without submitting a conversation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var loads = 0
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway { error("example must not submit") },
                catalogProvider = AssistantCatalogSnapshotProvider {
                    loads += 1
                    functionalAssistantCatalogSnapshot()
                },
                accessDecision = { ProductAccessDecision.GRANTED },
            )
            viewModel.loadStarterTarget()
            viewModel.loadStarterTarget()
            advanceUntilIdle()
            assertEquals(1, loads)
            assertEquals("apple", viewModel.state.value.starterTarget?.targetId)
            assertTrue(viewModel.state.value.messages.isEmpty())
            assertNull(viewModel.state.value.proposal)
            viewModel.setInput("apple")
            assertEquals("apple", viewModel.state.value.input)
            assertTrue(viewModel.state.value.messages.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `starter failure leaves ordinary input usable and respects access`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var access = ProductAccessDecision.SIGN_IN_REQUIRED
            var loads = 0
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway { error("example must not submit") },
                catalogProvider = AssistantCatalogSnapshotProvider { loads += 1; error("unavailable") },
                accessDecision = { access },
            )
            viewModel.loadStarterTarget()
            advanceUntilIdle()
            assertEquals(0, loads)
            access = ProductAccessDecision.GRANTED
            viewModel.loadStarterTarget()
            advanceUntilIdle()
            assertEquals(1, loads)
            assertNull(viewModel.state.value.starterTarget)
            assertNull(viewModel.state.value.issue)
            viewModel.setInput("watch a number")
            assertEquals("watch a number", viewModel.state.value.input)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `signed-out send preserves the turn and does not load the catalog`() {
        var catalogLoads = 0
        var gatewayTurns = 0
        val viewModel = AssistantViewModel(
            gateway = MonitorAssistantGateway {
                gatewayTurns += 1
                error("gateway must not run before sign-in")
            },
            catalogProvider = AssistantCatalogSnapshotProvider {
                catalogLoads += 1
                error("catalog must not load before sign-in")
            },
            accessDecision = { ProductAccessDecision.SIGN_IN_REQUIRED },
        )

        viewModel.setInput(USER_TEXT)
        viewModel.send()

        val state = viewModel.state.value
        assertEquals("", state.input)
        assertEquals(
            listOf(AssistantConversationMessage(AssistantConversationRole.USER, USER_TEXT)),
            state.messages,
        )
        assertFalse(state.sending)
        assertEquals(AssistantIssue.SIGN_IN_REQUIRED, state.issue)
        assertEquals(0, catalogLoads)
        assertEquals(0, gatewayTurns)
    }

    @Test
    fun `sign-in retry sends the preserved turn after loading the catalog`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val snapshot = functionalAssistantCatalogSnapshot()
            var catalogLoads = 0
            var sentRequest: AssistantTurnRequest? = null
            var accessDecision = ProductAccessDecision.SIGN_IN_REQUIRED
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway { request ->
                    sentRequest = request
                    AssistantGatewayResult.Completed(
                        AssistantTurnResponse(
                            conversationId = request.conversationId,
                            turnId = request.turnId,
                            result = AssistantTurnResult.Message(ASSISTANT_TEXT),
                        ),
                    )
                },
                catalogProvider = AssistantCatalogSnapshotProvider {
                    catalogLoads += 1
                    snapshot
                },
                accessDecision = { accessDecision },
            )
            viewModel.setInput(USER_TEXT)
            viewModel.send()

            accessDecision = ProductAccessDecision.GRANTED
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(1, catalogLoads)
            assertEquals(
                listOf(AssistantConversationMessage(AssistantConversationRole.USER, USER_TEXT)),
                sentRequest?.messages,
            )
            val state = viewModel.state.value
            assertEquals(
                listOf(
                    AssistantConversationMessage(AssistantConversationRole.USER, USER_TEXT),
                    AssistantConversationMessage(AssistantConversationRole.ASSISTANT, ASSISTANT_TEXT),
                ),
                state.messages,
            )
            assertFalse(state.sending)
            assertNull(state.issue)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `proposal state carries the exact signed model-card name`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val snapshot = functionalAssistantCatalogSnapshot()
            val profile = snapshot.modelProfiles.single {
                it.modelProfileKey == "common_objects_tensorflow_efficientdet_lite2"
            }
            val proposal = MonitorConfigurationProposal.VisualDescription(
                title = "苹果出现",
                catalogBinding = snapshot.binding,
                modelProfileKey = profile.modelProfileKey,
                packageId = profile.packageId,
                intentKey = "object.common.apple",
                targetId = "apple",
                displayText = "苹果",
                rule = AssistantPresenceRule(AssistantPresenceCondition.APPEARS, 1),
            )
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway { request ->
                    AssistantGatewayResult.Completed(
                        AssistantTurnResponse(
                            conversationId = request.conversationId,
                            turnId = request.turnId,
                            result = AssistantTurnResult.Proposal(proposal),
                        ),
                    )
                },
                catalogProvider = AssistantCatalogSnapshotProvider { snapshot },
                accessDecision = { ProductAccessDecision.GRANTED },
            )

            viewModel.setInput(USER_TEXT)
            viewModel.send()
            advanceUntilIdle()

            assertEquals(proposal, viewModel.state.value.proposal)
            assertEquals("EfficientDet-Lite2 COCO", viewModel.state.value.selectedModelName)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `returning from a proposal preserves one valid conversation for refinement`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val snapshot = functionalAssistantCatalogSnapshot()
            val profile = snapshot.modelProfiles.single {
                it.modelProfileKey == "common_objects_tensorflow_efficientdet_lite2"
            }
            val proposal = MonitorConfigurationProposal.VisualDescription(
                title = "苹果出现",
                catalogBinding = snapshot.binding,
                modelProfileKey = profile.modelProfileKey,
                packageId = profile.packageId,
                intentKey = "object.common.apple",
                targetId = "apple",
                displayText = "苹果",
                rule = AssistantPresenceRule(AssistantPresenceCondition.APPEARS, 3),
            )
            val requests = mutableListOf<AssistantTurnRequest>()
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway { request ->
                    requests += request
                    AssistantGatewayResult.Completed(
                        AssistantTurnResponse(
                            conversationId = request.conversationId,
                            turnId = request.turnId,
                            result = if (requests.size == 1) {
                                AssistantTurnResult.Proposal(proposal)
                            } else {
                                AssistantTurnResult.Message("还需要修改什么？")
                            },
                        ),
                    )
                },
                catalogProvider = AssistantCatalogSnapshotProvider { snapshot },
                accessDecision = { ProductAccessDecision.GRANTED },
            )

            viewModel.setInput("苹果出现 3 秒时通知我")
            viewModel.send()
            advanceUntilIdle()
            assertEquals(proposal, viewModel.state.value.proposal)

            viewModel.backToConversation()
            val returned = viewModel.state.value
            assertNull(returned.proposal)
            assertNull(returned.selectedModelName)
            assertEquals(
                listOf(AssistantConversationRole.USER, AssistantConversationRole.ASSISTANT),
                returned.messages.map(AssistantConversationMessage::role),
            )

            viewModel.setInput("改成 5 秒")
            viewModel.send()
            advanceUntilIdle()

            assertEquals(2, requests.size)
            assertEquals(requests[0].conversationId, requests[1].conversationId)
            assertEquals(
                listOf(
                    AssistantConversationRole.USER,
                    AssistantConversationRole.ASSISTANT,
                    AssistantConversationRole.USER,
                ),
                requests[1].messages.map(AssistantConversationMessage::role),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stale signed-in session stops on one sign-in-required response`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var gatewayTurns = 0
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway {
                    gatewayTurns += 1
                    AssistantGatewayResult.SignInRequired
                },
                catalogProvider = AssistantCatalogSnapshotProvider {
                    functionalAssistantCatalogSnapshot()
                },
                accessDecision = { ProductAccessDecision.GRANTED },
            )

            viewModel.setInput(USER_TEXT)
            viewModel.send()
            advanceUntilIdle()

            assertEquals(1, gatewayTurns)
            assertFalse(viewModel.state.value.sending)
            assertEquals(AssistantIssue.SIGN_IN_REQUIRED, viewModel.state.value.issue)

            advanceUntilIdle()
            assertEquals(1, gatewayTurns)
            assertEquals(AssistantIssue.SIGN_IN_REQUIRED, viewModel.state.value.issue)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `voice transcript becomes the user turn and is sent without a second tap`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val file = File.createTempFile("assistant-voice-vm-", ".m4a").apply {
            writeBytes(ByteArray(64))
        }
        try {
            var sent: AssistantTurnRequest? = null
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway { request ->
                    sent = request
                    AssistantGatewayResult.Completed(
                        AssistantTurnResponse(
                            request.conversationId,
                            request.turnId,
                            AssistantTurnResult.Message(ASSISTANT_TEXT),
                        ),
                    )
                },
                catalogProvider = AssistantCatalogSnapshotProvider {
                    functionalAssistantCatalogSnapshot()
                },
                voiceGateway = VoiceTranscriptionGateway { _, _ ->
                    VoiceTranscriptionResult.Completed(USER_TEXT)
                },
                accessDecision = { ProductAccessDecision.GRANTED },
            )

            assertTrue(viewModel.voiceCaptureStarted())
            viewModel.submitVoice(CapturedVoiceRecording(file, 1_200))
            advanceUntilIdle()

            assertEquals(USER_TEXT, sent?.messages?.single()?.content)
            assertEquals(
                listOf(
                    AssistantConversationMessage(AssistantConversationRole.USER, USER_TEXT),
                    AssistantConversationMessage(AssistantConversationRole.ASSISTANT, ASSISTANT_TEXT),
                ),
                viewModel.state.value.messages,
            )
            assertEquals(AssistantVoiceState.IDLE, viewModel.state.value.voiceState)
            assertFalse(file.exists())
        } finally {
            Dispatchers.resetMain()
            file.delete()
        }
    }

    @Test
    fun `signed-out voice press requests sign-in without recording state`() {
        val viewModel = AssistantViewModel(
            gateway = MonitorAssistantGateway { error("unused") },
            catalogProvider = AssistantCatalogSnapshotProvider { error("unused") },
            accessDecision = { ProductAccessDecision.SIGN_IN_REQUIRED },
        )

        assertFalse(viewModel.voiceCaptureStarted())
        assertEquals(AssistantVoiceState.IDLE, viewModel.state.value.voiceState)
        assertEquals(AssistantVoiceIssue.SIGN_IN_REQUIRED, viewModel.state.value.voiceIssue)
    }

    @Test
    fun `granting microphone permission clears the permission prompt immediately`() {
        val viewModel = AssistantViewModel(
            gateway = MonitorAssistantGateway { error("unused") },
            catalogProvider = AssistantCatalogSnapshotProvider { error("unused") },
            accessDecision = { ProductAccessDecision.GRANTED },
        )

        viewModel.voicePermissionRequired()
        assertEquals(AssistantVoiceIssue.PERMISSION_REQUIRED, viewModel.state.value.voiceIssue)

        viewModel.voicePermissionResolved(true)

        assertEquals(null, viewModel.state.value.voiceIssue)
        assertEquals(AssistantVoiceState.IDLE, viewModel.state.value.voiceState)
    }

    @Test
    fun `recorder start failure returns to idle with visible issue`() {
        val viewModel = AssistantViewModel(
            gateway = MonitorAssistantGateway { error("unused") },
            catalogProvider = AssistantCatalogSnapshotProvider { error("unused") },
            accessDecision = { ProductAccessDecision.GRANTED },
        )

        assertTrue(viewModel.voiceCaptureStarted())
        viewModel.voiceCaptureUnavailable()

        assertEquals(AssistantVoiceState.IDLE, viewModel.state.value.voiceState)
        assertEquals(AssistantVoiceIssue.UNAVAILABLE, viewModel.state.value.voiceIssue)
    }

    @Test
    fun `signed-in account without active or verified Pro cannot dispatch text`() {
        val cases = listOf(
            ProductAccessDecision.PRO_REQUIRED to AssistantIssue.SUBSCRIPTION_REQUIRED,
            ProductAccessDecision.VERIFICATION_REQUIRED to
                AssistantIssue.SUBSCRIPTION_VERIFICATION_REQUIRED,
        )
        cases.forEach { (decision, expectedIssue) ->
            var catalogLoads = 0
            var gatewayTurns = 0
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway {
                    gatewayTurns += 1
                    error("gateway must remain behind local Pro admission")
                },
                catalogProvider = AssistantCatalogSnapshotProvider {
                    catalogLoads += 1
                    error("catalog must remain behind local Pro admission")
                },
                accessDecision = { decision },
            )

            viewModel.setInput(USER_TEXT)
            viewModel.send()
            viewModel.retry()

            assertEquals(expectedIssue, viewModel.state.value.issue)
            assertFalse(viewModel.state.value.sending)
            assertEquals(0, catalogLoads)
            assertEquals(0, gatewayTurns)
        }
    }

    @Test
    fun `voice admission blocks permission and recorder stages without Pro`() {
        val cases = listOf(
            ProductAccessDecision.SIGN_IN_REQUIRED to AssistantVoiceIssue.SIGN_IN_REQUIRED,
            ProductAccessDecision.PRO_REQUIRED to AssistantVoiceIssue.SUBSCRIPTION_REQUIRED,
            ProductAccessDecision.VERIFICATION_REQUIRED to
                AssistantVoiceIssue.SUBSCRIPTION_VERIFICATION_REQUIRED,
        )
        cases.forEach { (decision, expectedIssue) ->
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway { error("unused") },
                catalogProvider = AssistantCatalogSnapshotProvider { error("unused") },
                accessDecision = { decision },
            )

            assertFalse(viewModel.voiceAccessGranted())
            assertFalse(viewModel.voiceCaptureStarted())
            assertEquals(AssistantVoiceState.IDLE, viewModel.state.value.voiceState)
            assertEquals(expectedIssue, viewModel.state.value.voiceIssue)
        }
    }

    @Test
    fun `entitlement loss before voice transport deletes recording and skips transcription`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val file = File.createTempFile("assistant-voice-gate-", ".m4a").apply {
            writeBytes(ByteArray(64))
        }
        try {
            var accessDecision = ProductAccessDecision.GRANTED
            var uploads = 0
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway { error("unused") },
                catalogProvider = AssistantCatalogSnapshotProvider { error("unused") },
                voiceGateway = VoiceTranscriptionGateway { _, _ ->
                    uploads += 1
                    error("voice upload must remain behind local Pro admission")
                },
                accessDecision = { accessDecision },
            )

            assertTrue(viewModel.voiceCaptureStarted())
            viewModel.submitVoice(CapturedVoiceRecording(file, 1_200))
            accessDecision = ProductAccessDecision.PRO_REQUIRED
            advanceUntilIdle()

            assertEquals(0, uploads)
            assertFalse(file.exists())
            assertEquals(AssistantVoiceState.IDLE, viewModel.state.value.voiceState)
            assertEquals(AssistantVoiceIssue.SUBSCRIPTION_REQUIRED, viewModel.state.value.voiceIssue)
        } finally {
            Dispatchers.resetMain()
            file.delete()
        }
    }

    @Test
    fun `entitlement loss while catalog loads prevents assistant upload`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var accessDecision = ProductAccessDecision.GRANTED
            var gatewayTurns = 0
            val viewModel = AssistantViewModel(
                gateway = MonitorAssistantGateway {
                    gatewayTurns += 1
                    error("assistant upload must recheck Pro after catalog loading")
                },
                catalogProvider = AssistantCatalogSnapshotProvider {
                    accessDecision = ProductAccessDecision.VERIFICATION_REQUIRED
                    functionalAssistantCatalogSnapshot()
                },
                accessDecision = { accessDecision },
            )

            viewModel.setInput(USER_TEXT)
            viewModel.send()
            advanceUntilIdle()

            assertEquals(0, gatewayTurns)
            assertFalse(viewModel.state.value.sending)
            assertEquals(
                AssistantIssue.SUBSCRIPTION_VERIFICATION_REQUIRED,
                viewModel.state.value.issue,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        const val USER_TEXT = "苹果出现时记录"
        const val ASSISTANT_TEXT = "要持续多久？"
    }
}
