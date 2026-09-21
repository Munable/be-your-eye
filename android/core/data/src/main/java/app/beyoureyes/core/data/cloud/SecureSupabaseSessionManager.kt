package app.beyoureyes.core.data.cloud

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal fun interface SessionPayloadEncryptor {
    fun encrypt(plainText: ByteArray): ByteArray
}

internal fun interface SessionPayloadDecryptor {
    fun decrypt(cipherText: ByteArray): ByteArray
}

/** Small encrypted file store kept separate so its corruption/atomic-write behavior is testable. */
internal class EncryptedSessionPayloadStore(
    private val file: File,
    private val encryptor: SessionPayloadEncryptor,
    private val decryptor: SessionPayloadDecryptor,
) {
    fun write(value: ByteArray) {
        require(value.isNotEmpty() && value.size <= MAX_PLAIN_BYTES)
        val encrypted = encryptor.encrypt(value)
        require(encrypted.isNotEmpty() && encrypted.size <= MAX_CIPHER_BYTES)
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "session directory is unavailable" }
        val temporary = File.createTempFile("session-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encrypted)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun read(): ByteArray {
        if (!file.isFile) throw NoSuchElementException("no persisted Supabase session")
        val encrypted = file.readBytes()
        require(encrypted.isNotEmpty() && encrypted.size <= MAX_CIPHER_BYTES) {
            "persisted Supabase session has an invalid size"
        }
        val clear = decryptor.decrypt(encrypted)
        require(clear.isNotEmpty() && clear.size <= MAX_PLAIN_BYTES) {
            "decrypted Supabase session has an invalid size"
        }
        return clear
    }

    fun delete() {
        if (file.exists()) check(file.delete()) { "persisted Supabase session could not be deleted" }
    }

    private companion object {
        const val MAX_PLAIN_BYTES = 64 * 1024
        const val MAX_CIPHER_BYTES = 80 * 1024
    }
}

/**
 * Supabase-kt SessionManager backed by an Android Keystore protected Tink AEAD.
 *
 * Tokens never enter DataStore, logs, BuildConfig, Room, or Android backup. The encrypted payload
 * lives under noBackupFilesDir and is bound to this application through associated data.
 */
class SecureSupabaseSessionManager(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SessionManager {
    private val mutex = Mutex()
    private val appContext = context.applicationContext
    private val aead: Aead by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { createAead(appContext) }
    private val associatedData = "${appContext.packageName}:supabase-session:v1".encodeToByteArray()
    private val store = EncryptedSessionPayloadStore(
        file = File(appContext.noBackupFilesDir, "cloud/supabase-session-v1.bin"),
        encryptor = SessionPayloadEncryptor { clear -> aead.encrypt(clear, associatedData) },
        decryptor = SessionPayloadDecryptor { cipher -> aead.decrypt(cipher, associatedData) },
    )

    override suspend fun saveSession(session: UserSession) = mutex.withLock {
        withContext(Dispatchers.IO) {
            store.write(json.encodeToString(UserSession.serializer(), session).encodeToByteArray())
        }
    }

    override suspend fun loadSession(): UserSession = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bytes = try {
                store.read()
            } catch (error: NoSuchElementException) {
                throw error
            } catch (error: Throwable) {
                runCatching { store.delete() }
                throw IllegalStateException("persisted Supabase session is unreadable", error)
            }
            try {
                json.decodeFromString(UserSession.serializer(), bytes.decodeToString())
            } catch (error: Throwable) {
                runCatching { store.delete() }
                throw IllegalStateException("persisted Supabase session is invalid", error)
            }
        }
    }

    override suspend fun deleteSession() = mutex.withLock {
        withContext(Dispatchers.IO) { store.delete() }
    }

    private companion object {
        const val KEYSET_NAME = "beyoureyes-supabase-session-keyset-v1"
        const val KEYSET_PREFERENCES = "beyoureyes-secure-keysets-v1"
        const val MASTER_KEY_URI = "android-keystore://beyoureyes-supabase-session-master-v1"

        @Suppress("DEPRECATION")
        fun createAead(context: Context): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREFERENCES)
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        }
    }
}
