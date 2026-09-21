package app.beyoureyes.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.monitorPreferencesDataStore by preferencesDataStore(
    name = "monitor_preferences_v1",
)

fun monitorPreferences(context: Context): MonitoringPreferences = MonitoringPreferences(
    dataStore = context.applicationContext.monitorPreferencesDataStore,
    defaults = MonitoringPreferenceDefaults(eventNotificationsEnabled = false),
)
