package app.beyoureyes.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalFirebaseConfigurationTest {
    @Test
    fun emptyConfigurationKeepsFirebaseDisabled() {
        assertFalse(OptionalFirebaseConfiguration("", "", "", "").enabled)
    }

    @Test
    fun everyPublicValueIsRequiredBeforeFirebaseCanStart() {
        listOf(
            OptionalFirebaseConfiguration("", "app", "project", "sender"),
            OptionalFirebaseConfiguration("api", "", "project", "sender"),
            OptionalFirebaseConfiguration("api", "app", "", "sender"),
            OptionalFirebaseConfiguration("api", "app", "project", ""),
        ).forEach { configuration ->
            assertFalse(configuration.enabled)
        }
    }

    @Test
    fun completeConfigurationEnablesFirebase() {
        assertTrue(OptionalFirebaseConfiguration("api", "app", "project", "sender").enabled)
    }
}
