package app.beyoureyes.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitoringPreferencesInstrumentedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var preferences: MonitoringPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = context.preferencesDataStoreFile("monitoring-preferences-test-${System.nanoTime()}")
        preferences = MonitoringPreferences(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { file },
            defaults = MonitoringPreferenceDefaults(false),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun freshDeviceDefaultsPeerAndPerTaskEventNotificationsOff() = runBlocking {
        assertFalse(preferences.eventNotificationsEnabled.first())
        assertTrue(preferences.eventNotificationTaskIds.first().isEmpty())
        assertTrue(preferences.startedTaskIds.first().isEmpty())
    }

    @Test
    fun startedStatePersistsUntilTaskIsDeleted() = runBlocking {
        preferences.markTaskStarted(TASK_1)

        assertEquals(setOf(TASK_1), preferences.startedTaskIds.first())

        preferences.clearTask(TASK_1)

        assertTrue(preferences.startedTaskIds.first().isEmpty())
    }

    @Test
    fun taskNotificationsAreIndependentDevicePreferences() = runBlocking {
        preferences.setEventNotificationsEnabled(TASK_1, true)
        preferences.setEventNotificationsEnabled(TASK_2, true)

        assertEquals(setOf(TASK_1, TASK_2), preferences.eventNotificationTaskIds.first())

        preferences.clearTask(TASK_1)

        assertEquals(setOf(TASK_2), preferences.eventNotificationTaskIds.first())
    }

    private companion object {
        const val TASK_1 = "01900000-0000-7000-8000-000000000000"
        const val TASK_2 = "01900000-0000-7000-8000-000000000001"
    }
}
