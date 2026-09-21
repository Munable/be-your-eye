package app.beyoureyes.monitor.feature.assistant

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptionGatewayTest {
    @Test
    fun `disabled gateway never manufactures a transcript`() = runBlocking {
        val recording = CapturedVoiceRecording(File("missing-functional-voice.m4a"), 1_200)
        assertEquals(
            VoiceTranscriptionResult.Unavailable,
            DisabledVoiceTranscriptionGateway.transcribe(recording, "zh-CN"),
        )
    }

    @Test
    fun `recording deletion removes audio and orphan cleanup is idempotent`() {
        val directory = kotlin.io.path.createTempDirectory("assistant-voice-").toFile()
        val recordingFile = File(directory, "voice-test.m4a").apply { writeBytes(ByteArray(64)) }
        val recording = CapturedVoiceRecording(recordingFile, 1_200)

        assertTrue(recording.delete())
        assertFalse(recordingFile.exists())
        assertTrue(recording.delete())
        File(directory, "voice-orphan.m4a").writeBytes(ByteArray(64))
        assertTrue(clearOrphanedVoiceRecordings(directory))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        directory.delete()
    }

    @Test
    fun `authenticated MPEG-4 recording is bounded and decoded against its request id`() = runBlocking {
        val file = createMp4File()
        var captured: VoiceHttpRequest? = null
        val gateway = SupabaseVoiceTranscriptionGateway(
            endpointUrl = "https://project.supabase.co/functions/v1/voice-transcription",
            publishableKey = "publishable-key",
            tokenProvider = VoiceAccessTokenProvider { "access-token" },
            transport = VoiceHttpTransport { request ->
                captured = request
                val requestId = JsonParser.parseString(request.body.decodeToString())
                    .asJsonObject.get("request_id").asString
                VoiceHttpResponse(
                    200,
                    ByteArrayInputStream(
                        """{"schema_version":"1.0","request_id":"$requestId","text":"苹果出现时提醒我"}"""
                            .encodeToByteArray(),
                    ),
                )
            },
        )

        val result = gateway.transcribe(CapturedVoiceRecording(file, 1_200), "zh-CN")

        assertEquals(VoiceTranscriptionResult.Completed("苹果出现时提醒我"), result)
        assertEquals("access-token", captured?.accessToken)
        assertEquals("publishable-key", captured?.publishableKey)
        val audio = JsonParser.parseString(captured!!.body.decodeToString())
            .asJsonObject.getAsJsonObject("audio")
        assertEquals(file.length().toInt(), audio.get("byte_length").asInt)
        assertTrue(audio.get("base64").asString.isNotBlank())
        file.delete()
        Unit
    }

    @Test
    fun `missing session and malformed capture fail before transport`() = runBlocking {
        var transports = 0
        val gateway = SupabaseVoiceTranscriptionGateway(
            endpointUrl = "https://project.supabase.co/functions/v1/voice-transcription",
            publishableKey = "publishable-key",
            tokenProvider = VoiceAccessTokenProvider { null },
            transport = VoiceHttpTransport {
                transports += 1
                error("transport must not run")
            },
        )
        val valid = createMp4File()
        assertEquals(
            VoiceTranscriptionResult.SignInRequired,
            gateway.transcribe(CapturedVoiceRecording(valid, 1_200), "zh-CN"),
        )
        assertEquals(0, transports)
        valid.delete()

        val malformed = File.createTempFile("voice-malformed-", ".m4a").apply {
            writeBytes(ByteArray(64))
        }
        val authenticated = SupabaseVoiceTranscriptionGateway(
            endpointUrl = "https://project.supabase.co/functions/v1/voice-transcription",
            publishableKey = "publishable-key",
            tokenProvider = VoiceAccessTokenProvider { "access-token" },
            transport = VoiceHttpTransport { error("transport must not run") },
        )
        assertEquals(
            VoiceTranscriptionResult.InvalidRecording,
            authenticated.transcribe(CapturedVoiceRecording(malformed, 1_200), "zh-CN"),
        )
        malformed.delete()
        Unit
    }

    @Test
    fun `response rejects non-string transcript and malformed UTF-8`() = runBlocking {
        val invalidBodies = listOf<(String) -> ByteArray>(
            { requestId ->
                """{"schema_version":"1.0","request_id":"$requestId","text":true}"""
                    .encodeToByteArray()
            },
            { requestId ->
                "{\"schema_version\":\"1.0\",\"request_id\":\"$requestId\",\"text\":\""
                    .encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".encodeToByteArray()
            },
        )
        for (body in invalidBodies) {
            val file = createMp4File()
            val gateway = SupabaseVoiceTranscriptionGateway(
                endpointUrl = "https://project.supabase.co/functions/v1/voice-transcription",
                publishableKey = "publishable-key",
                tokenProvider = VoiceAccessTokenProvider { "access-token" },
                transport = VoiceHttpTransport { request ->
                    val requestId = JsonParser.parseString(request.body.decodeToString())
                        .asJsonObject.get("request_id").asString
                    VoiceHttpResponse(200, ByteArrayInputStream(body(requestId)))
                },
            )

            assertEquals(
                VoiceTranscriptionResult.Unavailable,
                gateway.transcribe(CapturedVoiceRecording(file, 1_200), "zh-CN"),
            )
            file.delete()
        }
    }

    @Test
    fun `server subscription rejection remains a fail-closed fallback`() = runBlocking {
        val file = createMp4File()
        val gateway = SupabaseVoiceTranscriptionGateway(
            endpointUrl = "https://project.supabase.co/functions/v1/voice-transcription",
            publishableKey = "publishable-key",
            tokenProvider = VoiceAccessTokenProvider { "access-token" },
            transport = VoiceHttpTransport {
                VoiceHttpResponse(
                    402,
                    ByteArrayInputStream("""{"code":"subscription_required"}""".encodeToByteArray()),
                )
            },
        )

        assertEquals(
            VoiceTranscriptionResult.SubscriptionRequired,
            gateway.transcribe(CapturedVoiceRecording(file, 1_200), "zh-CN"),
        )
        file.delete()
        Unit
    }

    private fun createMp4File(): File = File.createTempFile("voice-valid-", ".m4a").apply {
        val bytes = ByteArray(64)
        bytes[3] = 24
        "ftypM4A ".encodeToByteArray().copyInto(bytes, destinationOffset = 4)
        writeBytes(bytes)
    }
}
