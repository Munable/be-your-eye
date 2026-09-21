package app.beyoureyes.monitor.feature.subscription

import android.content.Context
import app.beyoureyes.core.data.cloud.CloudConfiguration
import app.beyoureyes.core.data.cloud.SecureSupabaseSessionManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val PRO_SUBSCRIPTION_PRODUCT_ID = "be_your_eye_pro"
private const val ENTITLEMENT_PATH = "/functions/v1/play-entitlement"
private const val WEBSITE_ENTITLEMENT_PATH = "/functions/v1/product-entitlement"
private const val MAX_REQUEST_BYTES = 8 * 1024
private const val MAX_RESPONSE_BYTES = 16 * 1024

internal data class SubscriptionEntitlement(
    val active: Boolean,
    val state: String,
    val expiresAt: Instant?,
    val refreshAfter: Instant?,
)

internal sealed interface SubscriptionEntitlementResult {
    data class Completed(val entitlement: SubscriptionEntitlement) : SubscriptionEntitlementResult
    data object SignInRequired : SubscriptionEntitlementResult
    data object Unavailable : SubscriptionEntitlementResult
}

internal interface SubscriptionEntitlementGateway {
    suspend fun status(): SubscriptionEntitlementResult
    suspend fun verifyPurchase(purchaseToken: String): SubscriptionEntitlementResult
}

internal fun interface SubscriptionTokenProvider {
    suspend fun token(): String?
}

internal data class SubscriptionHttpRequest(
    val url: String,
    val publishableKey: String,
    val accessToken: String,
    val body: ByteArray,
)

internal class SubscriptionHttpResponse(
    val statusCode: Int,
    val body: InputStream,
) : AutoCloseable {
    override fun close() = body.close()
}

internal fun interface SubscriptionHttpTransport {
    fun execute(request: SubscriptionHttpRequest): SubscriptionHttpResponse
}

private class UrlConnectionSubscriptionHttpTransport : SubscriptionHttpTransport {
    override fun execute(request: SubscriptionHttpRequest): SubscriptionHttpResponse {
        requireEntitlementEndpoint(request.url)
        require(request.publishableKey.isNotBlank() && request.publishableKey.length <= 4_096)
        require(request.accessToken.isNotBlank() && request.accessToken.length <= 16_384)
        require(request.body.size in 1..MAX_REQUEST_BYTES)
        val connection = (URL(request.url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = 10_000
            readTimeout = 20_000
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
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            if (declared > MAX_RESPONSE_BYTES) {
                connection.disconnect()
                throw IOException("entitlement response exceeds size limit")
            }
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream(byteArrayOf())
            }
            SubscriptionHttpResponse(
                status,
                object : InputStream() {
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
            throw IOException("entitlement HTTPS request failed", error)
        }
    }
}

internal class SupabaseSubscriptionEntitlementGateway internal constructor(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val tokenProvider: SubscriptionTokenProvider,
    private val transport: SubscriptionHttpTransport = UrlConnectionSubscriptionHttpTransport(),
) : SubscriptionEntitlementGateway {
    init {
        requireEntitlementEndpoint(endpointUrl)
        require(publishableKey.isNotBlank() && publishableKey.length <= 4_096)
    }

    override suspend fun status(): SubscriptionEntitlementResult =
        execute("""{"action":"status"}""".encodeToByteArray())

    override suspend fun verifyPurchase(purchaseToken: String): SubscriptionEntitlementResult {
        require(purchaseToken.length in 20..4_096 && PURCHASE_TOKEN.matches(purchaseToken))
        val body = JsonObject().apply {
            addProperty("action", "verify_purchase")
            addProperty("product_id", PRO_SUBSCRIPTION_PRODUCT_ID)
            addProperty("purchase_token", purchaseToken)
        }.toString().encodeToByteArray()
        return execute(body)
    }

    private suspend fun execute(body: ByteArray): SubscriptionEntitlementResult {
        val accessToken = try {
            tokenProvider.token()?.trim()?.takeIf(String::isNotEmpty)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: return SubscriptionEntitlementResult.SignInRequired
        return try {
            withContext(Dispatchers.IO) {
                transport.execute(
                    SubscriptionHttpRequest(endpointUrl, publishableKey, accessToken, body),
                ).use { response ->
                    val bytes = readBounded(response.body, MAX_RESPONSE_BYTES)
                    when (response.statusCode) {
                        200 -> SubscriptionEntitlementResult.Completed(decodeStatus(bytes))
                        401 -> SubscriptionEntitlementResult.SignInRequired
                        else -> SubscriptionEntitlementResult.Unavailable
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SubscriptionEntitlementResult.Unavailable
        }
    }

    companion object {
        fun create(context: Context, supabaseUrl: String, publishableKey: String, websiteBillingEnabled: Boolean = false): SubscriptionEntitlementGateway {
            val configuration = CloudConfiguration.from(supabaseUrl, publishableKey)
            if (configuration !is CloudConfiguration.Enabled) return DisabledSubscriptionEntitlementGateway
            val sessions = SecureSupabaseSessionManager(context.applicationContext)
            return SupabaseSubscriptionEntitlementGateway(
                endpointUrl = configuration.supabaseUrl + if (websiteBillingEnabled) WEBSITE_ENTITLEMENT_PATH else ENTITLEMENT_PATH,
                publishableKey = configuration.publishableKey,
                tokenProvider = SubscriptionTokenProvider {
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

internal data object DisabledSubscriptionEntitlementGateway : SubscriptionEntitlementGateway {
    override suspend fun status() = SubscriptionEntitlementResult.Unavailable
    override suspend fun verifyPurchase(purchaseToken: String) = SubscriptionEntitlementResult.Unavailable
}

private fun decodeStatus(bytes: ByteArray): SubscriptionEntitlement {
    val text = decodeUtf8(bytes)
    val root = JsonParser.parseString(text)
    require(root.isJsonObject)
    val objectValue = root.asJsonObject
    require(objectValue.keySet() == setOf("product_id", "active", "state", "expires_at", "refresh_after"))
    require(objectValue.get("product_id").asString == PRO_SUBSCRIPTION_PRODUCT_ID)
    val activeElement = objectValue.get("active")
    require(activeElement.isJsonPrimitive && activeElement.asJsonPrimitive.isBoolean)
    val stateElement = objectValue.get("state")
    require(stateElement.isJsonPrimitive && stateElement.asJsonPrimitive.isString)
    val state = stateElement.asString
    require(state.length in 1..100)
    return SubscriptionEntitlement(
        active = activeElement.asBoolean,
        state = state,
        expiresAt = nullableInstant(objectValue, "expires_at"),
        refreshAfter = nullableInstant(objectValue, "refresh_after"),
    )
}

private fun nullableInstant(root: JsonObject, name: String): Instant? {
    val value = root.get(name)
    if (value.isJsonNull) return null
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
    return Instant.parse(value.asString)
}

private fun requireEntitlementEndpoint(value: String) {
    val uri = java.net.URI(value)
    require(
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath in setOf(ENTITLEMENT_PATH, WEBSITE_ENTITLEMENT_PATH),
    )
}

private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4_096)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (output.size() + read > limit) throw IOException("response exceeds size limit")
        output.write(buffer, 0, read)
    }
    require(output.size() > 0)
    return output.toByteArray()
}

private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private val PURCHASE_TOKEN = Regex("^[A-Za-z0-9._=\\-]+$")
