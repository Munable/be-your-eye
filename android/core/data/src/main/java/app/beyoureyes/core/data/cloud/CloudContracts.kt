package app.beyoureyes.core.data.cloud

import java.net.URI
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Optional account/cloud configuration. An absent or incomplete config is a local-only build. */
sealed interface CloudConfiguration {
    data class Enabled(
        val supabaseUrl: String,
        val publishableKey: String,
        val authRedirectUrl: String? = null,
    ) : CloudConfiguration {
        val accountDeletionUrl: String
            get() = "$supabaseUrl/functions/v1/delete-account"

        init {
            val uri = URI(supabaseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
                "Supabase URL must be an absolute HTTPS URL without user info"
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "Supabase URL must not contain a query or fragment"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Supabase URL must identify the project root"
            }
            require(publishableKey.isNotBlank() && publishableKey.length <= 4096) {
                "Supabase publishable key is invalid"
            }
            authRedirectUrl?.let(::requirePasswordRecoveryRedirectUrl)
        }
    }

    data class Disabled(val reason: String) : CloudConfiguration {
        init {
            require(reason.isNotBlank())
        }
    }

    companion object {
        fun from(
            url: String?,
            publishableKey: String?,
            authRedirectUrl: String? = null,
        ): CloudConfiguration {
            val cleanUrl = url.orEmpty().trim().trimEnd('/')
            val cleanKey = publishableKey.orEmpty().trim()
            if (cleanUrl.isEmpty() && cleanKey.isEmpty()) {
                return Disabled("cloud_not_configured")
            }
            if (cleanUrl.isEmpty() || cleanKey.isEmpty()) {
                return Disabled("cloud_configuration_incomplete")
            }
            val cleanRedirect = authRedirectUrl.orEmpty().trim().ifEmpty { null }
            return runCatching { Enabled(cleanUrl, cleanKey, cleanRedirect) }
                .getOrElse { Disabled("cloud_configuration_invalid") }
        }
    }
}

sealed interface CloudAccountState {
    data class Disabled(val reason: String) : CloudAccountState
    data object SignedOut : CloudAccountState
    data class SignedIn(val accountId: String, val email: String?) : CloudAccountState
}

interface CloudAccountRepository {
    val state: StateFlow<CloudAccountState>
    val accountDeletionAvailable: Boolean
    val passwordRecoveryAvailable: Boolean get() = false
    val passwordRecoveryPending: StateFlow<Boolean> get() = NO_PASSWORD_RECOVERY_PENDING
    /** Waits until a persisted session has been loaded before startup activation decisions. */
    suspend fun awaitInitialization() = Unit
    suspend fun signUp(email: String, password: String): CloudAccountState
    suspend fun signIn(email: String, password: String): CloudAccountState
    suspend fun signOut()
    suspend fun deleteAccount()
    suspend fun requestPasswordReset(email: String) {
        throw CloudUnavailableException("password recovery is not configured")
    }
    fun acceptPasswordRecoveryCallback(callbackUrl: String): Boolean = false
    suspend fun completePasswordRecovery(newPassword: String): CloudAccountState {
        throw CloudUnavailableException("password recovery is not configured")
    }
    fun cancelPasswordRecovery() = Unit
}

private val NO_PASSWORD_RECOVERY_PENDING = MutableStateFlow(false).asStateFlow()

@Serializable
data class CloudDeviceWrite(
    @SerialName("device_id") val deviceId: String,
    @SerialName("schema_version") val schemaVersion: String = "3.0",
    @SerialName("device_profile") val deviceProfile: String = "android_arm64_8gb_launch_v1",
    @SerialName("display_name") val displayName: String,
    @SerialName("android_api") val androidApi: Int,
    val abi: String,
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("gms_available") val gmsAvailable: Boolean,
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean,
    @SerialName("app_version") val appVersion: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
)

@Serializable
data class CloudDeviceRow(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean,
    val revision: Long,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
)

@Serializable
data class CloudTaskWrite(
    @SerialName("task_id") val taskId: String,
    /**
     * The immutable local Task revision represented by [config]. The server preserves this
     * revision on first upload, advances to a newer revision, and never lets an older retry move
     * the cloud row backwards.
     */
    val revision: Long,
    @SerialName("catalog_version") val catalogVersion: String,
    @SerialName("capability_id") val capabilityId: String,
    val title: String,
    @SerialName("monitoring_device_id") val monitoringDeviceId: String,
    val config: JsonObject,
) {
    init {
        require(revision >= 1) { "task revision must be positive" }
    }
}

/** Minimal peer-task metadata used only to label downloaded Events. It is never runnable. */
@Serializable
data class CloudTaskSummary(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("task_id") val taskId: String,
    val revision: Long,
    @SerialName("capability_id") val capabilityId: String,
    val title: String,
    @SerialName("target_definition") val targetDefinition: JsonObject,
    @SerialName("monitoring_device_id") val monitoringDeviceId: String,
) {
    val targetDefinitionMode: String
        get() = targetDefinition["mode"]?.jsonPrimitive?.contentOrNull.orEmpty()

    init {
        require(schemaVersion == "4.0")
        require(revision >= 1)
        require(capabilityId in setOf("visual_target", "visible_state", "structured_reading"))
        require(targetDefinitionMode in setOf("object_detection", "reference_images", "none"))
        require(title.isNotBlank() && title.length <= 100)
        require(app.beyoureyes.core.data.UuidV7.isValid(monitoringDeviceId))
    }
}

@Serializable
data class CloudEventWrite(
    @SerialName("schema_version") val schemaVersion: String = "3.0",
    @SerialName("event_id") val eventId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("task_revision") val taskRevision: Long,
    @SerialName("episode_id") val episodeId: String,
    @SerialName("source_sequence") val sourceSequence: Long,
    @SerialName("occurred_at") val occurredAt: String,
    val payload: JsonObject,
)

@Serializable
data class CloudEventRow(
    @SerialName("event_id") val eventId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("task_revision") val taskRevision: Long,
    @SerialName("episode_id") val episodeId: String,
    @SerialName("source_sequence") val sourceSequence: Long,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("monitoring_device_id") val monitoringDeviceId: String,
    val payload: JsonObject,
) {
    init {
        require(app.beyoureyes.core.data.UuidV7.isValid(monitoringDeviceId))
    }
}

@Serializable
internal data class CloudEventTableRow(
    @SerialName("event_id") val eventId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("task_revision") val taskRevision: Long,
    @SerialName("episode_id") val episodeId: String,
    @SerialName("source_sequence") val sourceSequence: Long,
    @SerialName("occurred_at") val occurredAt: String,
    val payload: JsonObject,
)

@Serializable
internal data class CloudTaskDeviceRow(
    @SerialName("monitoring_device_id") val monitoringDeviceId: String,
)

@Serializable
data class CloudEventBatchResult(
    @SerialName("accepted_ids") val acceptedIds: List<String>,
    @SerialName("duplicate_ids") val duplicateIds: List<String>,
)

@Serializable
data class CloudSyncChange(
    val sequence: Long,
    @SerialName("resource_type") val resourceType: String,
    val operation: String,
    @SerialName("resource_id") val resourceId: String,
    val value: JsonObject? = null,
)

enum class ReceiptType(val wireValue: String) {
    FETCHED("fetched"),
    DISPLAYED("displayed"),
}

/** Authenticated Supabase PostgREST/RPC boundary. */
interface CloudDataPlane {
    val configured: Boolean
    suspend fun currentAccountId(): String?
    suspend fun upsertDevice(device: CloudDeviceWrite)
    suspend fun peerDevices(): List<CloudDeviceRow>
    suspend fun revokeDevice(deviceId: String): Boolean
    suspend fun upsertTasks(tasks: List<CloudTaskWrite>)
    suspend fun deleteTasks(taskIds: List<String>)
    suspend fun uploadEvents(events: List<CloudEventWrite>): CloudEventBatchResult
    suspend fun changesAfter(cursor: Long, limit: Int): List<CloudSyncChange>
    suspend fun event(eventId: String): CloudEventRow?
    suspend fun addReceipt(
        eventId: String,
        deviceId: String,
        type: ReceiptType,
        receivedAt: String,
    )
    suspend fun registerPushToken(deviceId: String, token: String)
    suspend fun unregisterPushToken(deviceId: String)
}

class CloudUnavailableException(message: String = "optional cloud is unavailable") :
    IllegalStateException(message)
