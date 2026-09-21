package app.beyoureyes.core.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordRecoveryTest {
    @Test
    fun `exact HTTPS callback accepts only a recovery bearer pair`() {
        val tokens = parsePasswordRecoveryCallback(
            expectedRedirectUrl = REDIRECT,
            callbackUrl = "$REDIRECT#access_token=${"a".repeat(40)}&refresh_token=${"r".repeat(12)}" +
                "&expires_in=3600&token_type=bearer&type=recovery&sb=",
        )

        assertEquals("a".repeat(40), tokens?.accessToken)
        assertEquals("r".repeat(12), tokens?.refreshToken)
        assertEquals(3_600L, tokens?.expiresInSeconds)
    }

    @Test
    fun `callback rejects host path query type duplicate and unknown-field drift`() {
        val validFragment = "access_token=${"a".repeat(40)}&refresh_token=${"r".repeat(40)}" +
            "&expires_in=3600&token_type=bearer&type=recovery"
        listOf(
            "https://evil.example/auth/callback#$validFragment",
            "https://account.example/other#$validFragment",
            "$REDIRECT?next=x#$validFragment",
            "$REDIRECT#${validFragment.replace("type=recovery", "type=signup")}",
            "$REDIRECT#$validFragment&type=recovery",
            "$REDIRECT#$validFragment&extra=value",
            "$REDIRECT#$validFragment&sb=unexpected",
            "$REDIRECT#${validFragment.replace("expires_in=3600", "expires_in=0")}",
            "$REDIRECT#${validFragment.replace("&expires_in=3600", "")}",
            "$REDIRECT#${"x".repeat(40_001)}",
        ).forEach { callback ->
            assertNull(callback, parsePasswordRecoveryCallback(REDIRECT, callback))
        }
    }

    @Test
    fun `cloud configuration fails closed on a noncanonical recovery URL`() {
        assertEquals(
            CloudConfiguration.Enabled(
                "https://project.supabase.co",
                "publishable-key",
                REDIRECT,
            ),
            CloudConfiguration.from(
                "https://project.supabase.co",
                "publishable-key",
                REDIRECT,
            ),
        )
        listOf(
            "http://account.example/auth/callback",
            "https://account.example/auth/other",
            "https://account.example/auth/callback?next=x",
        ).forEach { redirect ->
            assertEquals(
                CloudConfiguration.Disabled("cloud_configuration_invalid"),
                CloudConfiguration.from(
                    "https://project.supabase.co",
                    "publishable-key",
                    redirect,
                ),
            )
        }
    }

    private companion object {
        const val REDIRECT = "https://account.example/auth/callback"
    }
}
