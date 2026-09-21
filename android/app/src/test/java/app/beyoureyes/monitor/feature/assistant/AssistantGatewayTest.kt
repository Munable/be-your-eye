package app.beyoureyes.monitor.feature.assistant

import app.beyoureyes.core.vision.ClassMapTarget
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantGatewayTest {
    @Test
    fun disabledGatewayNeverManufacturesAssistantOutput() = runBlocking {
        assertEquals(AssistantGatewayResult.Unavailable, DisabledMonitorAssistantGateway.turn(request()))
    }

    @Test
    fun missingOfflineObjectManifestOmitsOnlyThatPackageWithoutWideningTargets() {
        val common = ClassMapTarget(16, "cat", "猫", "cat", setOf("小猫"))
        val unavailable = ClassMapTarget(52, "apple", "苹果", "apple")

        val available = availableObjectPackageTargets(
            authorizedTargetIds = setOf("cat", "apple"),
            requestedPackageIds = setOf("common-package", "unavailable-package"),
            targetsByPackageId = mapOf("common-package" to listOf(common)),
        )

        assertEquals(setOf("common-package"), available.keys)
        assertEquals(listOf(common), available.getValue("common-package"))
        assertFalse(available.containsKey("unavailable-package"))
        assertThrows(IllegalArgumentException::class.java) {
            availableObjectPackageTargets(
                authorizedTargetIds = setOf("cat"),
                requestedPackageIds = setOf("unavailable-package"),
                targetsByPackageId = mapOf("unavailable-package" to listOf(unavailable)),
            )
        }
    }

    @Test
    fun realGatewaySendsJwtBoundStrictRequestAndDecodesMessage() = runBlocking {
        var sent: AssistantHttpRequest? = null
        val gateway = gateway { request ->
            sent = request
            response(
                200,
                """{
                  "schema_version":"3.0",
                  "conversation_id":"$CONVERSATION_ID",
                  "turn_id":"$TURN_ID",
                  "result":{"type":"message","content":"要出现多久后记录？"}
                }""",
            )
        }

        val result = gateway.turn(request())

        val completed = result as AssistantGatewayResult.Completed
        assertEquals(
            "要出现多久后记录？",
            (completed.response.result as AssistantTurnResult.Message).content,
        )
        val network = requireNotNull(sent)
        assertEquals("session-jwt", network.accessToken)
        assertEquals("publishable-key", network.publishableKey)
        val wire = JsonParser.parseString(network.body.decodeToString()).asJsonObject
        assertEquals(ASSISTANT_SCHEMA_VERSION, wire["schema_version"].asString)
        assertEquals(3, wire["model_profiles"].asJsonArray.size())
        assertFalse(wire.has("capability_gaps"))
        val objectProfile = wire["model_profiles"].asJsonArray
            .map { it.asJsonObject }
            .single { it["model_profile_key"].asString == "common_objects_tensorflow_efficientdet_lite2" }
        val apple = objectProfile["targets"].asJsonArray
            .map { it.asJsonObject }
            .single { it["target_id"].asString == "apple" }
        assertEquals("苹果", apple["label_zh_cn"].asString)
        assertEquals(
            listOf("固定机位下检测签名类别表中的常见物体"),
            objectProfile["applicable_scenarios"].asJsonArray.map { it.asString },
        )
        assertEquals(
            listOf("签名类别表外目标", "专业安全现象", "人物身份识别"),
            objectProfile["inapplicable_scenarios"].asJsonArray.map { it.asString },
        )
        assertEquals(
            listOf("目标清晰可见", "固定纵向后摄", "持续供电"),
            objectProfile["input_requirements"].asJsonArray.map { it.asString },
        )
        assertEquals(
            "EfficientDet-Lite2 COCO",
            functionalAssistantCatalogSnapshot().modelProfiles.single {
                it.modelProfileKey == "common_objects_tensorflow_efficientdet_lite2"
            }.displayName,
        )
        assertTrue(network.body.size < 48 * 1024)
        assertFalse(network.body.decodeToString().contains("displayName"))
        assertFalse(network.body.decodeToString().contains("EfficientDet-Lite2 COCO"))
    }

    @Test
    fun unknownFieldsAndEchoDriftFailClosed() = runBlocking {
        val unknown = gateway {
            response(
                200,
                """{
                  "schema_version":"3.0",
                  "conversation_id":"$CONVERSATION_ID",
                  "turn_id":"$TURN_ID",
                  "result":{"type":"message","content":"请补充目标。","extra":true}
                }""",
            )
        }
        val wrongEcho = gateway {
            response(
                200,
                """{
                  "schema_version":"3.0",
                  "conversation_id":"$OTHER_ID",
                  "turn_id":"$TURN_ID",
                  "result":{"type":"message","content":"请补充目标。"}
                }""",
            )
        }

        assertEquals(AssistantGatewayResult.Unavailable, unknown.turn(request()))
        assertEquals(AssistantGatewayResult.Unavailable, wrongEcho.turn(request()))
    }

    @Test
    fun proposalMustMatchCurrentSignedSnapshotAndExactObjectTarget() = runBlocking {
        val valid = gateway { response(200, visualProposalResponse("apple")) }
        val outsideSnapshot = gateway { response(200, visualProposalResponse("steam_leak")) }
        val spoofedLabel = gateway {
            response(200, visualProposalResponse("apple", displayText = "蒸汽泄漏"))
        }

        val completed = valid.turn(request()) as AssistantGatewayResult.Completed
        val proposal = (completed.response.result as AssistantTurnResult.Proposal).proposal
            as MonitorConfigurationProposal.VisualDescription
        assertEquals("apple", proposal.targetId)
        assertEquals(AssistantGatewayResult.Unavailable, outsideSnapshot.turn(request()))
        assertEquals(AssistantGatewayResult.Unavailable, spoofedLabel.turn(request()))
    }

    @Test
    fun referenceAndReadingProposalBranchesDecodeWithoutWideningTheSchema() = runBlocking {
        val reference = gateway { response(200, referenceProposalResponse()) }
            .turn(request()) as AssistantGatewayResult.Completed
        assertTrue(
            (reference.response.result as AssistantTurnResult.Proposal).proposal is
                MonitorConfigurationProposal.ReferenceImages,
        )

        val reading = gateway { response(200, readingProposalResponse()) }
            .turn(request()) as AssistantGatewayResult.Completed
        val proposal = (reading.response.result as AssistantTurnResult.Proposal).proposal
            as MonitorConfigurationProposal.StructuredReading
        val rule = proposal.rule as AssistantReadingRule.Outside
        assertEquals("10", rule.lowerThresholdDecimal)
        assertEquals("20", rule.upperThresholdDecimal)
    }

    @Test
    fun httpAndSessionFailuresMapToFiniteUiOutcomes() = runBlocking {
        var transportCalled = false
        val signedOut = SupabaseMonitorAssistantGateway(
            endpointUrl = ENDPOINT,
            publishableKey = "publishable-key",
            tokenProvider = AssistantAccessTokenProvider { null },
            transport = AssistantHttpTransport {
                transportCalled = true
                response(200, "{}")
            },
        )
        assertEquals(AssistantGatewayResult.SignInRequired, signedOut.turn(request()))
        assertFalse(transportCalled)

        assertEquals(
            AssistantGatewayResult.InvalidRequest,
            gateway { response(400, """{"code":"invalid_request"}""") }.turn(request()),
        )
        assertEquals(
            AssistantGatewayResult.SignInRequired,
            gateway { response(401, """{"code":"sign_in_required"}""") }.turn(request()),
        )
        assertEquals(
            AssistantGatewayResult.SubscriptionRequired,
            gateway { response(402, """{"code":"subscription_required"}""") }.turn(request()),
        )
        assertEquals(
            AssistantGatewayResult.Unavailable,
            gateway { response(503, """{"code":"provider_unavailable"}""") }.turn(request()),
        )
    }

    @Test
    fun failureDiagnosticsDistinguishTransportFromReplyWithoutRecordingContent() = runBlocking {
        val diagnostics = mutableListOf<Map<String, String>>()
        for (transport in listOf(
            AssistantHttpTransport { throw java.net.SocketTimeoutException("private request content") },
            AssistantHttpTransport { response(503, "private response content") },
            AssistantHttpTransport { response(200, "private malformed response") },
        )) {
            val gateway = SupabaseMonitorAssistantGateway(
                endpointUrl = ENDPOINT,
                publishableKey = "publishable-key",
                tokenProvider = AssistantAccessTokenProvider { "session-jwt" },
                transport = transport,
                onFailureDiagnostic = diagnostics::add,
            )
            assertEquals(AssistantGatewayResult.Unavailable, gateway.turn(request()))
        }
        assertEquals(listOf("transport", "http", "response_decode"), diagnostics.map { it["stage"] })
        assertEquals(listOf("SocketTimeoutException", "503", "invalid_response"), diagnostics.map { it["reason"] })
        assertTrue(diagnostics.all { it.keys == setOf("stage", "reason", "turn_id", "duration_ms") })
        assertTrue(diagnostics.all { it["turn_id"] == TURN_ID && it.getValue("duration_ms").toLong() >= 0 })
        assertFalse(diagnostics.toString().contains("private"))
        assertFalse(diagnostics.toString().contains("session-jwt"))
    }

    private fun gateway(
        response: (AssistantHttpRequest) -> AssistantHttpResponse,
    ) = SupabaseMonitorAssistantGateway(
        endpointUrl = ENDPOINT,
        publishableKey = "publishable-key",
        tokenProvider = AssistantAccessTokenProvider { "session-jwt" },
        transport = AssistantHttpTransport(response),
    )

    private fun request(
        snapshot: AssistantCatalogSnapshot = functionalAssistantCatalogSnapshot(),
        messages: List<AssistantConversationMessage> = listOf(
            AssistantConversationMessage(AssistantConversationRole.USER, "苹果出现时记录"),
        ),
    ) = AssistantTurnRequest(
        conversationId = CONVERSATION_ID,
        turnId = TURN_ID,
        locale = "zh-CN",
        catalog = snapshot,
        messages = messages,
    )

    private fun visualProposalResponse(
        targetId: String,
        displayText: String = "苹果",
    ) = """{
      "schema_version":"3.0",
      "conversation_id":"$CONVERSATION_ID",
      "turn_id":"$TURN_ID",
      "result":{
        "type":"proposal",
        "proposal":{
          "schema_version":"3.0",
          "kind":"visual_description",
          "title":"苹果出现",
          "catalog_binding":{
            "catalog_id":"functional-assistant",
            "catalog_version":"1.0.0",
            "catalog_signed_payload_sha256":"${"0".repeat(64)}"
          },
          "model_profile_key":"common_objects_tensorflow_efficientdet_lite2",
          "package_id":"efficientdet_lite2_object_v1",
          "intent_key":"object.common.apple",
          "target":{"mode":"visual_description","target_id":"$targetId","display_text":"$displayText"},
          "rule":{"type":"target_presence","condition":"appears","duration_seconds":1}
        }
      }
    }"""

    private fun referenceProposalResponse() = """{
      "schema_version":"3.0",
      "conversation_id":"$CONVERSATION_ID",
      "turn_id":"$TURN_ID",
      "result":{"type":"proposal","proposal":{
        "schema_version":"3.0",
        "kind":"reference_images",
        "title":"参考目标",
        "catalog_binding":{
          "catalog_id":"functional-assistant",
          "catalog_version":"1.0.0",
          "catalog_signed_payload_sha256":"${"0".repeat(64)}"
        },
        "model_profile_key":"reference_object_matching",
        "package_id":"similarity_mediapipe_mobilenet_v3_large_v1",
        "intent_key":"visual.reference.object",
        "target":{"mode":"reference_images","required_image_count":3},
        "rule":{"type":"target_presence","condition":"appears","duration_seconds":1}
      }}
    }"""

    private fun readingProposalResponse() = """{
      "schema_version":"3.0",
      "conversation_id":"$CONVERSATION_ID",
      "turn_id":"$TURN_ID",
      "result":{"type":"proposal","proposal":{
        "schema_version":"3.0",
        "kind":"structured_reading",
        "title":"读数越界",
        "catalog_binding":{
          "catalog_id":"functional-assistant",
          "catalog_version":"1.0.0",
          "catalog_signed_payload_sha256":"${"0".repeat(64)}"
        },
        "model_profile_key":"numeric_display_reading",
        "package_id":"numeric_reader_ppocrv6_medium_v1",
        "intent_key":"reading.numeric.temperature",
        "target":{"mode":"structured_reading"},
        "rule":{
          "type":"reading_threshold",
          "condition":"outside",
          "lower_threshold_decimal":"10",
          "upper_threshold_decimal":"20",
          "duration_seconds":3
        }
      }}
    }"""

    private fun response(status: Int, body: String) = AssistantHttpResponse(
        statusCode = status,
        body = ByteArrayInputStream(body.encodeToByteArray()),
    )

    private companion object {
        const val ENDPOINT = "https://example.supabase.co/functions/v1/monitor-assistant"
        const val CONVERSATION_ID = "019aa111-1111-7111-8111-111111111111"
        const val TURN_ID = "019aa222-2222-7222-8222-222222222222"
        const val OTHER_ID = "019aa333-3333-7333-8333-333333333333"
    }
}
