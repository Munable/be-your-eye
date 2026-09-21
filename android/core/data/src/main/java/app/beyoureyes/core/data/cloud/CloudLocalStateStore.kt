package app.beyoureyes.core.data.cloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.beyoureyes.core.data.UuidV7
import kotlinx.coroutines.flow.first

interface CloudLocalStateStore {
    /** Stable installation identity within one account; a different account gets a different ID. */
    suspend fun deviceId(accountId: String): String
    suspend fun cursor(accountId: String): Long
    suspend fun setCursor(accountId: String, cursor: Long)
    suspend fun pushToken(): String?
    suspend fun setPushToken(token: String?)
    suspend fun syncedTaskIds(accountId: String): Set<String>
    suspend fun setSyncedTaskIds(accountId: String, taskIds: Set<String>)
    /**
     * Claims local task identities for an account without touching the network. A task can only
     * have one owner on this device; this keeps a direct A -> B account switch from uploading A's
     * local monitoring history into B. Newly created, unowned tasks may be claimed by the account
     * that is currently signed in.
     */
    suspend fun claimUnownedLocalTaskIds(accountId: String, taskIds: Set<String>): Set<String> =
        emptySet()

    suspend fun localTaskIdsOwnedBy(accountId: String): Set<String> = emptySet()

    suspend fun releaseLocalTaskOwnership(accountId: String, taskIds: Set<String>) = Unit

    suspend fun releaseLocalTaskOwnership(accountId: String) = Unit

    /** Clears transient pull/upload state while retaining the remote-task deletion ledger. */
    suspend fun resetAccountSessionState(accountId: String)

    /** Removes every account-scoped sync key after the cloud account itself is deleted. */
    suspend fun clearAccountState(accountId: String)
    suspend fun syncFingerprint(accountId: String, resourceKey: String): String?
    suspend fun setSyncFingerprint(accountId: String, resourceKey: String, value: String?)
}

class DataStoreCloudLocalStateStore(
    private val dataStore: DataStore<Preferences>,
) : CloudLocalStateStore {
    override suspend fun deviceId(accountId: String): String {
        requireUuid(accountId)
        val key = deviceIdKey(accountId)
        val existing = dataStore.data.first()[key]
        if (existing != null && UuidV7.isValid(existing)) return existing
        val generated = UuidV7.generate()
        var result = generated
        dataStore.edit { values ->
            val concurrent = values[key]
            if (concurrent != null && UuidV7.isValid(concurrent)) {
                result = concurrent
            } else {
                values[key] = generated
            }
        }
        return result
    }

    override suspend fun cursor(accountId: String): Long {
        requireUuid(accountId)
        return dataStore.data.first()[cursorKey(accountId)] ?: 0L
    }

    override suspend fun setCursor(accountId: String, cursor: Long) {
        requireUuid(accountId)
        require(cursor >= 0)
        dataStore.edit { values ->
            val key = cursorKey(accountId)
            val current = values[key] ?: 0L
            require(cursor >= current) { "cloud sync cursor must not move backwards" }
            values[key] = cursor
        }
    }

    override suspend fun pushToken(): String? = dataStore.data.first()[PUSH_TOKEN]

    override suspend fun setPushToken(token: String?) {
        require(token == null || token.length in 20..4096)
        dataStore.edit { values ->
            if (token == null) values.remove(PUSH_TOKEN) else values[PUSH_TOKEN] = token
        }
    }

    override suspend fun syncedTaskIds(accountId: String): Set<String> {
        requireUuid(accountId)
        return dataStore.data.first()[syncedTaskIdsKey(accountId)].orEmpty().toSet()
    }

    override suspend fun setSyncedTaskIds(accountId: String, taskIds: Set<String>) {
        requireUuid(accountId)
        require(taskIds.size <= 100)
        require(taskIds.all(UuidV7::isValid)) { "synced task IDs must be UUIDv7" }
        dataStore.edit { values -> values[syncedTaskIdsKey(accountId)] = taskIds }
    }

    override suspend fun claimUnownedLocalTaskIds(
        accountId: String,
        taskIds: Set<String>,
    ): Set<String> {
        requireUuid(accountId)
        require(taskIds.size <= MAX_LOCAL_TASK_IDS)
        require(taskIds.all(UuidV7::isValid)) { "local task IDs must be UUIDv7" }
        val owned = linkedSetOf<String>()
        dataStore.edit { values ->
            taskIds.forEach { taskId ->
                val key = localTaskOwnerKey(taskId)
                when (val current = values[key]) {
                    null -> {
                        values[key] = accountId
                        owned += taskId
                    }
                    accountId -> owned += taskId
                }
            }
        }
        return owned
    }

    override suspend fun localTaskIdsOwnedBy(accountId: String): Set<String> {
        requireUuid(accountId)
        return dataStore.data.first().asMap().asSequence()
            .filter { (key, value) ->
                key.name.startsWith(LOCAL_TASK_OWNER_PREFIX) && value == accountId
            }
            .map { (key, _) -> key.name.removePrefix(LOCAL_TASK_OWNER_PREFIX) }
            .filter(UuidV7::isValid)
            .toSet()
    }

    override suspend fun releaseLocalTaskOwnership(accountId: String) {
        requireUuid(accountId)
        dataStore.edit { values ->
            values.asMap()
                .filter { (key, value) ->
                    key.name.startsWith(LOCAL_TASK_OWNER_PREFIX) && value == accountId
                }
                .keys
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    values.remove(key as Preferences.Key<Any>)
                }
        }
    }

    override suspend fun releaseLocalTaskOwnership(accountId: String, taskIds: Set<String>) {
        requireUuid(accountId)
        require(taskIds.size <= MAX_LOCAL_TASK_IDS)
        require(taskIds.all(UuidV7::isValid)) { "local task IDs must be UUIDv7" }
        if (taskIds.isEmpty()) return
        dataStore.edit { values ->
            taskIds.forEach { taskId ->
                val key = localTaskOwnerKey(taskId)
                if (values[key] == accountId) values.remove(key)
            }
        }
    }

    override suspend fun resetAccountSessionState(accountId: String) {
        requireUuid(accountId)
        dataStore.edit { values ->
            val cursorName = "cursor_$accountId"
            val fingerprintPrefix = "fingerprint_${accountId}_"
            values.asMap().keys
                .filter { key -> key.name == cursorName || key.name.startsWith(fingerprintPrefix) }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    values.remove(key as Preferences.Key<Any>)
                }
        }
    }

    override suspend fun clearAccountState(accountId: String) {
        resetAccountSessionState(accountId)
        dataStore.edit { values ->
            values.remove(syncedTaskIdsKey(accountId))
            values.remove(deviceIdKey(accountId))
        }
    }

    override suspend fun syncFingerprint(accountId: String, resourceKey: String): String? {
        requireUuid(accountId)
        requireResourceKey(resourceKey)
        return dataStore.data.first()[fingerprintKey(accountId, resourceKey)]
    }

    override suspend fun setSyncFingerprint(
        accountId: String,
        resourceKey: String,
        value: String?,
    ) {
        requireUuid(accountId)
        requireResourceKey(resourceKey)
        require(value == null || value.matches(SHA256))
        dataStore.edit { values ->
            val key = fingerprintKey(accountId, resourceKey)
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun cursorKey(accountId: String) = longPreferencesKey("cursor_$accountId")
    private fun deviceIdKey(accountId: String) = stringPreferencesKey("device_id_$accountId")
    private fun syncedTaskIdsKey(accountId: String) = stringSetPreferencesKey("task_ids_$accountId")
    private fun localTaskOwnerKey(taskId: String) =
        stringPreferencesKey("$LOCAL_TASK_OWNER_PREFIX$taskId")
    private fun fingerprintKey(accountId: String, resourceKey: String) =
        stringPreferencesKey("fingerprint_${accountId}_$resourceKey")

    private companion object {
        const val LOCAL_TASK_OWNER_PREFIX = "local_task_owner_"
        const val MAX_LOCAL_TASK_IDS = 100
        val PUSH_TOKEN = stringPreferencesKey("fcm_registration_token")
        private val UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        private val RESOURCE_KEY = Regex("^[a-z][a-z0-9_.:-]{0,127}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun requireUuid(value: String) {
            require(UUID.matches(value)) { "accountId must be UUID" }
        }

        fun requireResourceKey(value: String) {
            require(RESOURCE_KEY.matches(value)) { "invalid sync resource key" }
        }
    }
}
