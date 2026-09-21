package app.beyoureyes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorNamingTest {
    @Test
    fun `localized base name is preserved when unused`() {
        assertEquals("Apple monitor", uniqueMonitorName("Apple monitor", emptyList()))
    }

    @Test
    fun `duplicate localized names receive a suffix`() {
        assertEquals(
            "Apple monitor 3",
            uniqueMonitorName("Apple monitor", listOf("Apple monitor", "Apple monitor 2")),
        )
    }
}
