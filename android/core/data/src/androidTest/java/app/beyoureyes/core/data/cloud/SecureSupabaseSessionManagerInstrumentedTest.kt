package app.beyoureyes.core.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureSupabaseSessionManagerInstrumentedTest {
    @Test
    fun accessAndRefreshTokensRoundTripThroughKeystoreCiphertext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SecureSupabaseSessionManager(context)
        runCatching { manager.deleteSession() }
        val session = Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
            """
            {
              "access_token":"access-token-not-plaintext-on-disk",
              "refresh_token":"refresh-token-not-plaintext-on-disk",
              "expires_in":3600,
              "token_type":"bearer",
              "user":{
                "id":"01900000-0000-7000-8000-000000000099",
                "aud":"authenticated",
                "role":"authenticated",
                "email":"test@example.com",
                "app_metadata":{},
                "user_metadata":{},
                "created_at":"2026-08-03T00:00:00Z",
                "updated_at":"2026-08-03T00:00:00Z"
              }
            }
            """.trimIndent(),
        )

        manager.saveSession(session)
        val restored = manager.loadSession()

        assertEquals(session.accessToken, restored.accessToken)
        assertEquals(session.refreshToken, restored.refreshToken)
        val persisted = File(context.noBackupFilesDir, "cloud/supabase-session-v1.bin")
        val raw = persisted.readBytes().decodeToString()
        assertFalse(raw.contains(session.accessToken))
        assertFalse(raw.contains(session.refreshToken))

        manager.deleteSession()
        assertThrows(NoSuchElementException::class.java) { runBlocking { manager.loadSession() } }
        Unit
    }
}
