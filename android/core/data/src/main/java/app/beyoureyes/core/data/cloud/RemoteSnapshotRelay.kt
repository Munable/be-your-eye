package app.beyoureyes.core.data.cloud

import app.beyoureyes.core.data.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteSnapshotRequest(
    @SerialName("protocol_version") val protocolVersion: String = PROTOCOL_VERSION,
    @SerialName("request_id") val requestId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("source_device_id") val sourceDeviceId: String,
    @SerialName("requester_device_id") val requesterDeviceId: String,
    @SerialName("recipient_public_key") val recipientPublicKey: String,
    @SerialName("issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
) {
    init {
        require(protocolVersion == PROTOCOL_VERSION)
        require(listOf(requestId, eventId, taskId, sourceDeviceId, requesterDeviceId).all(UuidV7::isValid))
        require(sourceDeviceId != requesterDeviceId)
        require(recipientPublicKey.length in 32..MAX_PUBLIC_KEY_BASE64_CHARS)
        require(issuedAtEpochMillis >= 0)
        require(expiresAtEpochMillis in (issuedAtEpochMillis + 1)..(issuedAtEpochMillis + MAX_TTL_MILLIS))
    }

    companion object {
        const val PROTOCOL_VERSION = "snapshot_relay_v1"
        const val MAX_TTL_MILLIS = 30_000L
        const val MAX_PUBLIC_KEY_BASE64_CHARS = 4_096
    }
}

@Serializable
data class RemoteSnapshotResponse(
    @SerialName("protocol_version") val protocolVersion: String = RemoteSnapshotRequest.PROTOCOL_VERSION,
    @SerialName("request_id") val requestId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("source_device_id") val sourceDeviceId: String,
    @SerialName("requester_device_id") val requesterDeviceId: String,
    val status: String,
    @SerialName("ciphertext_base64") val ciphertextBase64: String? = null,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long,
) {
    init {
        require(protocolVersion == RemoteSnapshotRequest.PROTOCOL_VERSION)
        require(listOf(requestId, eventId, sourceDeviceId, requesterDeviceId).all(UuidV7::isValid))
        require(sourceDeviceId != requesterDeviceId)
        require(status in setOf(STATUS_OK, STATUS_UNAVAILABLE))
        require((status == STATUS_OK) == (ciphertextBase64 != null))
        require(ciphertextBase64 == null || ciphertextBase64.length in 32..MAX_CIPHERTEXT_BASE64_CHARS)
        require(expiresAtEpochMillis >= 0)
    }

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_UNAVAILABLE = "unavailable"
        const val MAX_CIPHERTEXT_BASE64_CHARS = 200_000
    }
}

/** Ephemeral authenticated Broadcast transport. It never persists snapshot bytes. */
interface RemoteSnapshotRelay {
    val configured: Boolean
    val requests: Flow<RemoteSnapshotRequest>
    val responses: Flow<RemoteSnapshotResponse>

    suspend fun connect(accountId: String)
    suspend fun disconnect()
    suspend fun send(request: RemoteSnapshotRequest)
    suspend fun send(response: RemoteSnapshotResponse)
}

internal fun snapshotRelayTopic(accountId: String): String {
    requireAccountUuid(accountId)
    return "beyoureyes:snapshots:$accountId"
}

internal fun requireAccountUuid(value: String) {
    require(ACCOUNT_UUID.matches(value)) { "accountId must be UUID" }
}

private val ACCOUNT_UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)
