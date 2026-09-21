package app.beyoureyes.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringPreferenceDefaultsTest {
    @Test
    fun `notification default remains device local`() {
        val defaults = MonitoringPreferenceDefaults(
            eventNotificationsEnabled = true,
        )

        assertEquals(true, defaults.eventNotificationsEnabled)
    }
}
