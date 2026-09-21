package app.beyoureyes.core.data.cloud

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudWireSerializationTest {
    @Test
    fun eventDefaultsArePresentOnTheAuthenticatedRpcWire() {
        val event = CloudEventWrite(
            eventId = "019fc479-153a-7ab6-a92f-8356beee3014",
            taskId = "019fc479-1539-7e13-8d3f-9e575f3647f8",
            taskRevision = 1,
            episodeId = "019fc479-153b-7cf5-ac61-149b84299f66",
            sourceSequence = 1,
            occurredAt = Instant.EPOCH.toString(),
            payload = buildJsonObject { put("type", "object_episode") },
        )

        val encoded = cloudWireJson().encodeToString(event)

        assertEquals("3.0", cloudWireJson().parseToJsonElement(encoded).jsonObject
            .getValue("schema_version").jsonPrimitive.content)
    }

    @Test
    fun deviceDefaultsArePresentOnThePostgrestUpsertWire() {
        val device = CloudDeviceWrite(
            deviceId = "019fc479-153a-7ab6-a92f-8356beee3014",
            displayName = "test",
            androidApi = 36,
            abi = "arm64-v8a",
            memoryMb = 8_192,
            gmsAvailable = true,
            notificationsEnabled = false,
            appVersion = "test",
        )

        val objectValue = cloudWireJson().parseToJsonElement(
            cloudWireJson().encodeToString(device),
        ).jsonObject

        assertEquals("3.0", objectValue.getValue("schema_version").jsonPrimitive.content)
        assertEquals(
            "android_arm64_8gb_launch_v1",
            objectValue.getValue("device_profile").jsonPrimitive.content,
        )
    }


    @Test
    fun finiteObjectSummaryUsesTheStructuredObjectDetectionMode() {
        val summary = CloudTaskSummary(
            schemaVersion = "4.0",
            taskId = "019fc479-1539-7e13-8d3f-9e575f3647f8",
            revision = 1,
            capabilityId = "visual_target",
            title = "找猫",
            targetDefinition = buildJsonObject { put("mode", "object_detection") },
            monitoringDeviceId = "019fc479-153a-7ab6-a92f-8356beee3014",
        )

        val encoded = cloudWireJson().parseToJsonElement(
            cloudWireJson().encodeToString(summary),
        ).jsonObject

        assertEquals(
            "object_detection",
            encoded.getValue("target_definition").jsonObject.getValue("mode").jsonPrimitive.content,
        )
        assertEquals("找猫", encoded.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun legacyTextTransportModeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudTaskSummary(
                schemaVersion = "4.0",
                taskId = "019fc479-1539-7e13-8d3f-9e575f3647f8",
                revision = 1,
                capabilityId = "visual_target",
                title = "找猫",
                targetDefinition = buildJsonObject { put("mode", "text") },
                monitoringDeviceId = "019fc479-153a-7ab6-a92f-8356beee3014",
            )
        }
    }

    @Test
    fun taskWireCarriesTheExactLocalRevisionForTheAuthenticatedBatchRpc() {
        val task = CloudTaskWrite(
            taskId = "019fc479-1539-7e13-8d3f-9e575f3647f8",
            revision = 7,
            catalogVersion = "2026.08.03.3",
            capabilityId = "visual_target",
            title = "门口有人",
            monitoringDeviceId = "019fc479-153a-7ab6-a92f-8356beee3014",
            config = buildJsonObject { put("contract_fixture", true) },
        )

        val objectValue = cloudWireJson().parseToJsonElement(
            cloudWireJson().encodeToString(task),
        ).jsonObject

        assertEquals(7L, objectValue.getValue("revision").jsonPrimitive.content.toLong())
        assertEquals("门口有人", objectValue.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun taskWireRejectsANonPositiveLocalRevisionBeforeNetworkUse() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudTaskWrite(
                taskId = "019fc479-1539-7e13-8d3f-9e575f3647f8",
                revision = 0,
                catalogVersion = "2026.08.03.3",
                capabilityId = "visual_target",
                title = "门口有人",
                monitoringDeviceId = "019fc479-153a-7ab6-a92f-8356beee3014",
                config = buildJsonObject { put("contract_fixture", true) },
            )
        }
    }
}
