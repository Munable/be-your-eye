package app.beyoureyes.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MonitoringPreferences(
    private val dataStore: DataStore<Preferences>,
    private val defaults: MonitoringPreferenceDefaults,
) {
    /** Per-device preference. Other devices signed into the same account keep their own value. */
    val eventNotificationsEnabled: Flow<Boolean> =
        dataStore.data.map { it[EVENT_NOTIFICATIONS_ENABLED] ?: defaults.eventNotificationsEnabled }

    /** Per-device, per-task notification choices. */
    val eventNotificationTaskIds: Flow<Set<String>> =
        dataStore.data.map { it[EVENT_NOTIFICATION_TASK_IDS].orEmpty().toSet() }

    /** Local lifecycle fact used to distinguish a ready monitor from one that was stopped. */
    val startedTaskIds: Flow<Set<String>> =
        dataStore.data.map { it[STARTED_TASK_IDS].orEmpty().toSet() }

    suspend fun setEventNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[EVENT_NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setEventNotificationsEnabled(taskId: String, enabled: Boolean) {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        dataStore.edit { values ->
            val taskIds = values[EVENT_NOTIFICATION_TASK_IDS].orEmpty().toMutableSet()
            if (enabled) taskIds += taskId else taskIds -= taskId
            values[EVENT_NOTIFICATION_TASK_IDS] = taskIds
        }
    }

    suspend fun markTaskStarted(taskId: String) {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        dataStore.edit { values ->
            values[STARTED_TASK_IDS] = values[STARTED_TASK_IDS].orEmpty() + taskId
        }
    }

    suspend fun clearTask(taskId: String) {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        dataStore.edit { values ->
            values[EVENT_NOTIFICATION_TASK_IDS] =
                values[EVENT_NOTIFICATION_TASK_IDS].orEmpty() - taskId
            values[STARTED_TASK_IDS] = values[STARTED_TASK_IDS].orEmpty() - taskId
        }
    }

    private companion object {
        val EVENT_NOTIFICATIONS_ENABLED = booleanPreferencesKey("event_notifications_enabled")
        val EVENT_NOTIFICATION_TASK_IDS = stringSetPreferencesKey("event_notification_task_ids")
        val STARTED_TASK_IDS = stringSetPreferencesKey("started_task_ids")
    }
}

data class MonitoringPreferenceDefaults(
    val eventNotificationsEnabled: Boolean,
)
