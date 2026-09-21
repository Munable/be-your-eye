package app.beyoureyes.core.data.cloud

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.Realtime
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer

data class CloudClientBundle(
    val account: CloudAccountRepository,
    val dataPlane: CloudDataPlane,
    val snapshotRelay: RemoteSnapshotRelay,
)

object SupabaseCloudClientFactory {
    fun create(context: Context, configuration: CloudConfiguration): CloudClientBundle =
        when (configuration) {
            is CloudConfiguration.Disabled -> {
                val disabled = DisabledCloudClient(configuration.reason)
                CloudClientBundle(
                    disabled,
                    disabled,
                    DisabledRemoteSnapshotRelay,
                )
            }
            is CloudConfiguration.Enabled -> {
                val json = cloudWireJson()
                val client = createSupabaseClient(
                    supabaseUrl = configuration.supabaseUrl,
                    supabaseKey = configuration.publishableKey,
                ) {
                    defaultLogLevel = LogLevel.NONE
                    install(Auth) {
                        sessionManager = SecureSupabaseSessionManager(context, json)
                        autoLoadFromStorage = true
                        autoSaveToStorage = true
                        alwaysAutoRefresh = true
                        // The product uses email/password only. Disabling the Android lifecycle
                        // callback adapter lets auth initialization finish deterministically even
                        // when no Activity has reached STARTED yet (WorkManager, tests, cold sync).
                        // Session refresh still remains enabled for the process lifetime.
                        enableLifecycleCallbacks = false
                        configuration.authRedirectUrl?.let { redirect ->
                            val uri = java.net.URI(redirect)
                            flowType = FlowType.IMPLICIT
                            scheme = uri.scheme
                            host = uri.host
                            defaultRedirectUrl = redirect
                        }
                    }
                    install(Postgrest) {
                        serializer = io.github.jan.supabase.serializer.KotlinXSerializer(json)
                    }
                    install(Realtime) {
                        serializer = io.github.jan.supabase.serializer.KotlinXSerializer(json)
                        requireValidSession = true
                        disconnectOnSessionLoss = true
                        disconnectOnNoSubscriptions = true
                    }
                }
                val enabled = SupabaseCloudClient(
                    client = client,
                    json = json,
                    publishableKey = configuration.publishableKey,
                    accountDeletionUrl = configuration.accountDeletionUrl,
                    authRedirectUrl = configuration.authRedirectUrl,
                )
                CloudClientBundle(
                    account = enabled,
                    dataPlane = enabled,
                    snapshotRelay = SupabaseRemoteSnapshotRelay(client, json),
                )
            }
        }
}

/** Contract payloads must carry their defaulted version/profile fields over the wire. */
internal fun cloudWireJson(): Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

private class DisabledCloudClient(
    reason: String,
) : CloudAccountRepository, CloudDataPlane {
    override val accountDeletionAvailable: Boolean = false
    override val passwordRecoveryAvailable: Boolean = false
    override val configured: Boolean = false
    override val state: StateFlow<CloudAccountState> =
        MutableStateFlow<CloudAccountState>(CloudAccountState.Disabled(reason)).asStateFlow()

    override suspend fun signUp(email: String, password: String) = state.value
    override suspend fun signIn(email: String, password: String) = state.value
    override suspend fun signOut() = Unit
    override suspend fun deleteAccount() = unavailable()
    override suspend fun requestPasswordReset(email: String) = unavailable()
    override suspend fun currentAccountId(): String? = null
    override suspend fun upsertDevice(device: CloudDeviceWrite) = unavailable()
    override suspend fun peerDevices(): List<CloudDeviceRow> = emptyList()
    override suspend fun revokeDevice(deviceId: String): Boolean = unavailable()
    override suspend fun upsertTasks(tasks: List<CloudTaskWrite>) = unavailable()
    override suspend fun deleteTasks(taskIds: List<String>) = unavailable()
    override suspend fun uploadEvents(events: List<CloudEventWrite>): CloudEventBatchResult = unavailable()
    override suspend fun changesAfter(cursor: Long, limit: Int): List<CloudSyncChange> = emptyList()
    override suspend fun event(eventId: String): CloudEventRow? = null
    override suspend fun addReceipt(
        eventId: String,
        deviceId: String,
        type: ReceiptType,
        receivedAt: String,
    ) = unavailable()
    override suspend fun registerPushToken(deviceId: String, token: String) = unavailable()
    override suspend fun unregisterPushToken(deviceId: String) = Unit

    private fun unavailable(): Nothing = throw CloudUnavailableException()
}

private class SupabaseCloudClient(
    private val client: SupabaseClient,
    private val json: Json,
    private val publishableKey: String,
    private val accountDeletionUrl: String,
    private val authRedirectUrl: String?,
) : CloudAccountRepository, CloudDataPlane {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<CloudAccountState>(CloudAccountState.SignedOut)
    override val state: StateFlow<CloudAccountState> = mutableState.asStateFlow()
    override val configured: Boolean = true
    override val accountDeletionAvailable: Boolean = true
    override val passwordRecoveryAvailable: Boolean = authRedirectUrl != null
    private val mutablePasswordRecoveryPending = MutableStateFlow(false)
    override val passwordRecoveryPending: StateFlow<Boolean> =
        mutablePasswordRecoveryPending.asStateFlow()
    @Volatile
    private var pendingPasswordRecoveryCallback: String? = null

    init {
        scope.launch {
            client.auth.sessionStatus.collect { status -> mutableState.value = status.toAccountState() }
        }
    }

    override suspend fun awaitInitialization() {
        client.auth.awaitInitialization()
    }

    override suspend fun signUp(email: String, password: String): CloudAccountState {
        validateCredentials(email, password)
        client.auth.awaitInitialization()
        client.auth.signUpWith(Email) {
            this.email = email.trim().lowercase(Locale.ROOT)
            this.password = password
        }
        return currentState()
    }

    override suspend fun signIn(email: String, password: String): CloudAccountState {
        validateCredentials(email, password)
        client.auth.awaitInitialization()
        client.auth.signInWith(Email) {
            this.email = email.trim().lowercase(Locale.ROOT)
            this.password = password
        }
        return currentState()
    }

    override suspend fun requestPasswordReset(email: String) {
        val redirect = authRedirectUrl
            ?: throw CloudUnavailableException("password recovery is not configured")
        validateEmail(email)
        client.auth.awaitInitialization()
        client.auth.resetPasswordForEmail(
            email = email.trim().lowercase(Locale.ROOT),
            redirectUrl = redirect,
        )
    }

    override fun acceptPasswordRecoveryCallback(callbackUrl: String): Boolean {
        val redirect = authRedirectUrl ?: return false
        if (parsePasswordRecoveryCallback(redirect, callbackUrl) == null) return false
        pendingPasswordRecoveryCallback = callbackUrl
        mutablePasswordRecoveryPending.value = true
        return true
    }

    override suspend fun completePasswordRecovery(newPassword: String): CloudAccountState {
        require(newPassword.length in 8..256) { "password must contain 8 to 256 characters" }
        val redirect = authRedirectUrl
            ?: throw CloudUnavailableException("password recovery is not configured")
        val callback = pendingPasswordRecoveryCallback
            ?: throw CloudUnavailableException("password recovery callback is missing")
        val tokens = parsePasswordRecoveryCallback(redirect, callback)
            ?: throw CloudUnavailableException("password recovery callback is invalid")
        client.auth.awaitInitialization()
        try {
            // importAuthToken cannot retain the callback's expiry metadata. With automatic
            // refresh enabled that creates an already-expired session and races updateUser with
            // an immediate refresh. Import the complete recovery session instead.
            client.auth.importSession(
                session = UserSession(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    providerRefreshToken = null,
                    providerToken = null,
                    expiresIn = tokens.expiresInSeconds,
                    tokenType = "bearer",
                    user = null,
                    type = "recovery",
                ),
                autoRefresh = true,
            )
            client.auth.updateUser { password = newPassword }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    client.auth.clearSession()
                    mutableState.value = CloudAccountState.SignedOut
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
        pendingPasswordRecoveryCallback = null
        mutablePasswordRecoveryPending.value = false
        return currentState()
    }

    override fun cancelPasswordRecovery() {
        pendingPasswordRecoveryCallback = null
        mutablePasswordRecoveryPending.value = false
    }

    override suspend fun signOut() {
        try {
            // The SDK's remote revoke call can wait on a dead network longer than the UI
            // action. Bound it, then always fence the local session below.
            withTimeoutOrNull(3_000) {
                try {
                    client.auth.signOut()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Local sign-out remains successful when the remote revoke is unavailable.
                }
            }
        } catch (cancelled: CancellationException) {
            // A canceled UI job must not leave a locally persisted session alive. The caller
            // still observes cancellation, while the auth state is fenced in a non-cancellable
            // cleanup boundary.
            withContext(NonCancellable) {
                client.auth.clearSession()
                mutableState.value = CloudAccountState.SignedOut
            }
            throw cancelled
        } catch (_: Throwable) {
            // Remote sign-out is best effort. Clearing the local session is the product action,
            // so offline/expired-network sign-out must still return the app to SignedOut.
        }
        client.auth.clearSession()
        mutableState.value = CloudAccountState.SignedOut
    }

    override suspend fun deleteAccount() {
        client.auth.awaitInitialization()
        val accessToken = client.auth.currentAccessTokenOrNull()
            ?: throw CloudUnavailableException("Supabase account session is required")
        withContext(Dispatchers.IO) {
            val connection = URL(accountDeletionUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "DELETE"
                connection.doOutput = true
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("apikey", publishableKey)
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                val body = "{}".encodeToByteArray()
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                check(status == HttpURLConnection.HTTP_NO_CONTENT) {
                    "Supabase account deletion function returned HTTP $status"
                }
            } finally {
                connection.disconnect()
            }
        }
        client.auth.clearSession()
        mutableState.value = CloudAccountState.SignedOut
    }

    override suspend fun currentAccountId(): String? {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
    }

    override suspend fun upsertDevice(device: CloudDeviceWrite) {
        requireAccount()
        client.from("devices").upsert(device) { onConflict = "device_id" }
    }

    override suspend fun peerDevices(): List<CloudDeviceRow> {
        requireAccount()
        val devices = mutableListOf<CloudDeviceRow>()
        var pageStart = 0L
        do {
            val page = client.from("devices").select {
                filter { exact("revoked_at", null) }
                order("updated_at", Order.DESCENDING)
                order("device_id", Order.ASCENDING)
                range(pageStart..(pageStart + DEVICE_PAGE_SIZE - 1L))
            }.decodeList<CloudDeviceRow>()
            devices += page
            pageStart += page.size
        } while (page.size == DEVICE_PAGE_SIZE)
        return devices
    }

    override suspend fun revokeDevice(deviceId: String): Boolean {
        requireUuid(deviceId)
        requireAccount()
        return client.postgrest.rpc(
            function = "beyoureyes_client_revoke_device",
            parameters = buildJsonObject { put("p_device_id", deviceId) },
        ).decodeAs()
    }

    override suspend fun upsertTasks(tasks: List<CloudTaskWrite>) {
        require(tasks.size <= 100)
        if (tasks.isEmpty()) return
        requireAccount()
        val encoded = json.encodeToJsonElement(ListSerializer(CloudTaskWrite.serializer()), tasks)
        require(encoded is JsonArray)
        client.postgrest.rpc(
            function = "beyoureyes_client_upsert_task_batch",
            parameters = buildJsonObject { put("p_tasks", encoded) },
        )
    }

    override suspend fun deleteTasks(taskIds: List<String>) {
        require(taskIds.size in 1..100)
        require(taskIds.distinct().size == taskIds.size)
        taskIds.forEach(::requireUuid)
        requireAccount()
        taskIds.forEach { taskId ->
            client.from("tasks").delete { filter { eq("task_id", taskId) } }
        }
    }

    override suspend fun uploadEvents(events: List<CloudEventWrite>): CloudEventBatchResult {
        require(events.size in 1..100)
        require(events.map(CloudEventWrite::eventId).distinct().size == events.size)
        requireAccount()
        val encoded = json.encodeToJsonElement(ListSerializer(CloudEventWrite.serializer()), events)
        require(encoded is JsonArray)
        return client.postgrest.rpc(
            function = "beyoureyes_client_insert_event_batch",
            parameters = buildJsonObject { put("p_events", encoded) },
        ).decodeAs()
    }

    override suspend fun changesAfter(cursor: Long, limit: Int): List<CloudSyncChange> {
        require(cursor >= 0)
        require(limit in 1..500)
        requireAccount()
        return client.from("sync_changes").select {
            filter { gt("sequence", cursor) }
            order("sequence", Order.ASCENDING)
            limit(limit.toLong())
        }.decodeList()
    }

    override suspend fun event(eventId: String): CloudEventRow? {
        requireUuid(eventId)
        requireAccount()
        val event = client.from("events").select {
            filter { eq("event_id", eventId) }
            limit(1)
        }.decodeList<CloudEventTableRow>().singleOrNull() ?: return null
        val task = client.from("tasks").select {
            filter { eq("task_id", event.taskId) }
            limit(1)
        }.decodeList<CloudTaskDeviceRow>().singleOrNull() ?: return null
        return CloudEventRow(
            eventId = event.eventId,
            taskId = event.taskId,
            taskRevision = event.taskRevision,
            episodeId = event.episodeId,
            sourceSequence = event.sourceSequence,
            occurredAt = event.occurredAt,
            monitoringDeviceId = task.monitoringDeviceId,
            payload = event.payload,
        )
    }

    override suspend fun addReceipt(
        eventId: String,
        deviceId: String,
        type: ReceiptType,
        receivedAt: String,
    ) {
        requireUuid(eventId)
        requireUuid(deviceId)
        requireAccount()
        client.postgrest.rpc(
            function = "beyoureyes_client_add_event_receipt",
            parameters = buildJsonObject {
                put("p_event_id", eventId)
                put("p_device_id", deviceId)
                put("p_receipt_type", type.wireValue)
                put("p_received_at", receivedAt)
            },
        )
    }

    override suspend fun registerPushToken(deviceId: String, token: String) {
        requireUuid(deviceId)
        require(token.length in 20..4096)
        requireAccount()
        client.postgrest.rpc(
            function = "beyoureyes_client_register_push_token",
            parameters = buildJsonObject {
                put("p_device_id", deviceId)
                put("p_token", token)
            },
        )
    }

    override suspend fun unregisterPushToken(deviceId: String) {
        requireUuid(deviceId)
        if (currentAccountId() == null) return
        client.postgrest.rpc(
            function = "beyoureyes_client_unregister_push_token",
            parameters = buildJsonObject { put("p_device_id", deviceId) },
        )
    }

    private suspend fun requireAccount(): String = currentAccountId()
        ?: throw CloudUnavailableException("Supabase account session is required")

    private fun currentState(): CloudAccountState {
        val user = client.auth.currentUserOrNull() ?: return CloudAccountState.SignedOut
        return CloudAccountState.SignedIn(user.id, user.email)
            .also { mutableState.value = it }
    }

    private fun SessionStatus.toAccountState(): CloudAccountState = when (this) {
        is SessionStatus.Authenticated -> session.user?.let { user ->
            CloudAccountState.SignedIn(user.id, user.email)
        } ?: CloudAccountState.SignedOut
        else -> CloudAccountState.SignedOut
    }

    private companion object {
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

        fun validateCredentials(email: String, password: String) {
            validateEmail(email)
            require(password.length in 8..256) { "password must contain 8 to 256 characters" }
        }

        fun validateEmail(email: String) {
            require(email.length in 3..320 && EMAIL.matches(email.trim())) { "invalid email" }
        }

        fun requireUuid(value: String) {
            require(UUID.matches(value)) { "invalid UUID" }
        }

    }
}

private const val DEVICE_PAGE_SIZE = 100
