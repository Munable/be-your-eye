package app.beyoureyes.monitor.feature.assistant

import android.content.Context
import app.beyoureyes.core.data.cloud.CloudConfiguration
import app.beyoureyes.core.data.cloud.SecureSupabaseSessionManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val VOICE_TRANSCRIPTION_SCHEMA_VERSION = "1.0"
internal const val MAX_VOICE_AUDIO_BYTES = 512 * 1024
internal const val MIN_VOICE_DURATION_MILLIS = 400L
internal const val MAX_VOICE_DURATION_MILLIS = 30_000L

private const val VOICE_TRANSCRIPTION_PATH = "/functions/v1/voice-transcription"
private const val MAX_VOICE_REQUEST_BYTES = 768 * 1024
private const val MAX_VOICE_RESPONSE_BYTES = 32 * 1024
private val VOICE_UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
private val VOICE_LOCALE = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*$")
private val VOICE_TEXT_CONTROLS = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]")

internal data class CapturedVoiceRecording(
    val file: File,
    val durationMillis: Long,
) {
    init {
        require(durationMillis in MIN_VOICE_DURATION_MILLIS..MAX_VOICE_DURATION_MILLIS)
    }

    /** Removes the recording immediately; a failed unlink is scrubbed and queued for cleanup. */
    fun delete(): Boolean = deleteEphemeralVoiceFile(file)
}

internal sealed interface VoiceTranscriptionResult {
    data class Completed(val text: String) : VoiceTranscriptionResult
    data object SignInRequired : VoiceTranscriptionResult
    data object SubscriptionRequired : VoiceTranscriptionResult
    data object InvalidRecording : VoiceTranscriptionResult
    data object Unavailable : VoiceTranscriptionResult
}

internal fun interface VoiceTranscriptionGateway {
    suspend fun transcribe(recording: CapturedVoiceRecording, locale: String): VoiceTranscriptionResult
}

internal fun interface VoiceAccessTokenProvider {
    suspend fun accessToken(): String?
}

internal data class VoiceHttpRequest(
    val url: String,
    val publishableKey: String,
    val accessToken: String,
    val body: ByteArray,
)

internal class VoiceHttpResponse(
    val statusCode: Int,
    val body: InputStream,
) : AutoCloseable {
    override fun close() = body.close()
}

internal fun interface VoiceHttpTransport {
    @Throws(IOException::class)
    fun execute(request: VoiceHttpRequest): VoiceHttpResponse
}

private class UrlConnectionVoiceHttpTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 40_000,
) : VoiceHttpTransport {
    override fun execute(request: VoiceHttpRequest): VoiceHttpResponse {
        requireVoiceEndpoint(request.url)
        require(request.publishableKey.isNotBlank() && request.publishableKey.length <= 4_096)
        require(request.accessToken.isNotBlank() && request.accessToken.length <= 16_384)
        require(request.body.size in 1..MAX_VOICE_REQUEST_BYTES)
        val connection = (URL(request.url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("Authorization", "Bearer ${request.accessToken}")
            setRequestProperty("apikey", request.publishableKey)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(request.body.size)
        }
        return try {
            connection.outputStream.use { it.write(request.body) }
            val status = connection.responseCode
            val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
            if (declaredLength > MAX_VOICE_RESPONSE_BYTES) {
                connection.disconnect()
                throw IOException("voice response exceeds the size limit")
            }
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream(byteArrayOf())
            }
            VoiceHttpResponse(
                statusCode = status,
                body = object : InputStream() {
                    override fun read(): Int = stream.read()
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        stream.read(buffer, offset, length)

                    override fun close() {
                        try {
                            stream.close()
                        } finally {
                            connection.disconnect()
                        }
                    }
                },
            )
        } catch (error: Exception) {
            connection.disconnect()
            if (error is IOException) throw error
            throw IOException("voice HTTPS request failed", error)
        }
    }
}

internal class SupabaseVoiceTranscriptionGateway internal constructor(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val tokenProvider: VoiceAccessTokenProvider,
    private val transport: VoiceHttpTransport = UrlConnectionVoiceHttpTransport(),
) : VoiceTranscriptionGateway {
    init {
        requireVoiceEndpoint(endpointUrl)
        require(publishableKey.isNotBlank() && publishableKey.length <= 4_096)
    }

    override suspend fun transcribe(
        recording: CapturedVoiceRecording,
        locale: String,
    ): VoiceTranscriptionResult {
        if (!VOICE_LOCALE.matches(locale)) return VoiceTranscriptionResult.InvalidRecording
        val token = try {
            tokenProvider.accessToken()?.trim()?.takeIf(String::isNotEmpty)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: return VoiceTranscriptionResult.SignInRequired
        val bytes = try {
            withContext(Dispatchers.IO) {
                if (!recording.file.isFile || recording.file.length() !in 32..MAX_VOICE_AUDIO_BYTES.toLong()) {
                    null
                } else {
                    recording.file.readBytes()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: return VoiceTranscriptionResult.InvalidRecording
        if (!hasMp4FileTypeBox(bytes)) return VoiceTranscriptionResult.InvalidRecording
        val requestId = java.util.UUID.randomUUID().toString()
        val body = VoiceWireCodec.encodeRequest(requestId, locale, recording.durationMillis, bytes)
        if (body.size !in 1..MAX_VOICE_REQUEST_BYTES) return VoiceTranscriptionResult.InvalidRecording
        val response = try {
            withContext(Dispatchers.IO) {
                transport.execute(
                    VoiceHttpRequest(endpointUrl, publishableKey, token, body),
                ).use { network ->
                    network.statusCode to readVoiceBounded(network.body, MAX_VOICE_RESPONSE_BYTES)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return VoiceTranscriptionResult.Unavailable
        }
        return when (response.first) {
            200 -> VoiceWireCodec.decodeResponse(response.second, requestId)
                ?.let(VoiceTranscriptionResult::Completed)
                ?: VoiceTranscriptionResult.Unavailable
            400 -> VoiceTranscriptionResult.InvalidRecording
            401 -> VoiceTranscriptionResult.SignInRequired
            402 -> VoiceTranscriptionResult.SubscriptionRequired
            else -> VoiceTranscriptionResult.Unavailable
        }
    }

    companion object {
        fun create(context: Context, supabaseUrl: String, publishableKey: String): VoiceTranscriptionGateway {
            val configuration = CloudConfiguration.from(supabaseUrl, publishableKey)
            if (configuration !is CloudConfiguration.Enabled) return DisabledVoiceTranscriptionGateway
            val sessions = SecureSupabaseSessionManager(context.applicationContext)
            return SupabaseVoiceTranscriptionGateway(
                endpointUrl = configuration.supabaseUrl + VOICE_TRANSCRIPTION_PATH,
                publishableKey = configuration.publishableKey,
                tokenProvider = VoiceAccessTokenProvider {
                    try {
                        sessions.loadSession().accessToken
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                },
            )
        }
    }
}

internal data object DisabledVoiceTranscriptionGateway : VoiceTranscriptionGateway {
    override suspend fun transcribe(
        recording: CapturedVoiceRecording,
        locale: String,
    ): VoiceTranscriptionResult = VoiceTranscriptionResult.Unavailable
}

private object VoiceWireCodec {
    fun encodeRequest(requestId: String, locale: String, durationMillis: Long, bytes: ByteArray): ByteArray {
        require(VOICE_UUID.matches(requestId))
        require(VOICE_LOCALE.matches(locale))
        val root = JsonObject().apply {
            addProperty("schema_version", VOICE_TRANSCRIPTION_SCHEMA_VERSION)
            addProperty("request_id", requestId)
            addProperty("locale", locale)
            add("audio", JsonObject().apply {
                addProperty("media_type", "audio/mp4")
                addProperty("duration_millis", durationMillis)
                addProperty("base64", java.util.Base64.getEncoder().encodeToString(bytes))
                addProperty("byte_length", bytes.size)
            })
        }
        return root.toString().encodeToByteArray()
    }

    fun decodeResponse(bytes: ByteArray, expectedRequestId: String): String? = runCatching {
        val root = JsonParser.parseString(decodeVoiceUtf8(bytes)).asJsonObject
        if (root.keySet() != setOf("schema_version", "request_id", "text")) return null
        val schema = root.get("schema_version").takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: return null
        val requestId = root.get("request_id").takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: return null
        val text = root.get("text").takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: return null
        if (schema != VOICE_TRANSCRIPTION_SCHEMA_VERSION || requestId != expectedRequestId) return null
        if (text != text.trim() || text.codePointCount(0, text.length) !in 1..500 ||
            VOICE_TEXT_CONTROLS.containsMatchIn(text)
        ) return null
        text
    }.getOrNull()
}

private fun requireVoiceEndpoint(value: String) {
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
    require(uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.rawPath == VOICE_TRANSCRIPTION_PATH)
}

private fun hasMp4FileTypeBox(bytes: ByteArray): Boolean = bytes.size >= 32 &&
    bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
    bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()

private fun decodeVoiceUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private fun readVoiceBounded(input: InputStream, maximumBytes: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        if (total > maximumBytes) throw IOException("voice response exceeds the size limit")
        result.write(buffer, 0, read)
    }
    return result.toByteArray()
}
