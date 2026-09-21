package app.beyoureyes.core.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigurationTest {
    @Test
    fun absentConfigurationSelectsLocalOnlyMode() {
        assertEquals(
            CloudConfiguration.Disabled("cloud_not_configured"),
            CloudConfiguration.from("", ""),
        )
    }

    @Test
    fun incompleteOrInsecureConfigurationNeverCreatesAClient() {
        assertEquals(
            CloudConfiguration.Disabled("cloud_configuration_incomplete"),
            CloudConfiguration.from("https://example.supabase.co", ""),
        )
        assertEquals(
            CloudConfiguration.Disabled("cloud_configuration_invalid"),
            CloudConfiguration.from("http://example.supabase.co", "publishable-key"),
        )
        assertEquals(
            CloudConfiguration.Disabled("cloud_configuration_invalid"),
            CloudConfiguration.from("https://example.supabase.co/auth/v1", "publishable-key"),
        )
    }

    @Test
    fun completeHttpsConfigurationIsEnabled() {
        val result = CloudConfiguration.from(
            "https://example.supabase.co/",
            "publishable-key",
        )
        assertTrue(result is CloudConfiguration.Enabled)
        result as CloudConfiguration.Enabled
        assertEquals("https://example.supabase.co", result.supabaseUrl)
        assertEquals(
            "https://example.supabase.co/functions/v1/delete-account",
            result.accountDeletionUrl,
        )
    }

    @Test
    fun supabaseConfigurationIncludesTheAccountDeletionFunction() {
        val result = CloudConfiguration.from(
            "https://example.supabase.co",
            "publishable-key",
        )
        assertTrue(result is CloudConfiguration.Enabled)
        result as CloudConfiguration.Enabled
        assertEquals(
            "https://example.supabase.co/functions/v1/delete-account",
            result.accountDeletionUrl,
        )
    }

}
