package app.beyoureyes.monitor.feature.subscription

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account-bound offline entitlement lease encrypted by a non-exportable Android Keystore key. */
internal class SecureEntitlementLeaseStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.noBackupFilesDir, "billing/entitlement-lease-v1.bin")
    private val associatedData = "${appContext.packageName}:entitlement-lease:v1".encodeToByteArray()

    @Synchronized
    fun save(lease: EntitlementLease) {
        val clear = JsonObject().apply {
            addProperty("account_id", lease.accountId)
            addProperty("provider_state", lease.providerState)
            addProperty("expires_at", lease.expiresAt.toString())
            addProperty("refresh_after", lease.refreshAfter.toString())
        }.toString().encodeToByteArray()
        require(clear.size in 1..MAX_PLAIN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(clear)
        val payload = ByteBuffer.allocate(MAGIC.size + 1 + cipher.iv.size + encrypted.size)
            .put(MAGIC)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        require(payload.size <= MAX_CIPHER_BYTES)
        writeAtomically(payload)
    }

    @Synchronized
    fun load(accountId: String, now: Instant = Instant.now()): EntitlementLease? {
        if (!file.isFile) return null
        return runCatching {
            val payload = file.readBytes()
            require(payload.size in MIN_CIPHER_BYTES..MAX_CIPHER_BYTES)
            val buffer = ByteBuffer.wrap(payload)
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            require(magic.contentEquals(MAGIC))
            val ivSize = buffer.get().toInt() and 0xff
            require(ivSize == GCM_IV_BYTES && buffer.remaining() > ivSize)
            val iv = ByteArray(ivSize).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(associatedData)
            val clear = cipher.doFinal(encrypted)
            require(clear.size in 1..MAX_PLAIN_BYTES)
            decodeLease(clear).takeIf { it.isValidFor(accountId, now) }
                ?: error("stored entitlement lease is stale or belongs to another account")
        }.getOrElse {
            runCatching { clear() }
            null
        }
    }

    @Synchronized
    fun clear() {
        if (file.exists()) check(file.delete()) { "entitlement lease could not be deleted" }
    }

    private fun decodeLease(bytes: ByteArray): EntitlementLease {
        val root = JsonParser.parseString(bytes.decodeToString()).asJsonObject
        require(root.keySet() == setOf("account_id", "provider_state", "expires_at", "refresh_after"))
        return EntitlementLease(
            accountId = root.requireString("account_id"),
            providerState = root.requireString("provider_state"),
            expiresAt = Instant.parse(root.requireString("expires_at")),
            refreshAfter = Instant.parse(root.requireString("refresh_after")),
        )
    }

    private fun JsonObject.requireString(name: String): String {
        val value = get(name)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.takeIf(String::isNotBlank) ?: error("blank $name")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun writeAtomically(payload: ByteArray) {
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "billing directory is unavailable" }
        val temporary = File.createTempFile("lease-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
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

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "beyoureyes-entitlement-lease-master-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val MAX_PLAIN_BYTES = 4 * 1024
        const val MAX_CIPHER_BYTES = 8 * 1024
        const val MIN_CIPHER_BYTES = 32
        val MAGIC = byteArrayOf(0x42, 0x59, 0x45, 0x4c, 0x31)
    }
}
