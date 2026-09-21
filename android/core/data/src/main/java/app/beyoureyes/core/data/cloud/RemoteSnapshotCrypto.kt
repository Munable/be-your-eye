package app.beyoureyes.core.data.cloud

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig

internal data class RemoteSnapshotRecipient(
    val privateKeyset: KeysetHandle,
    val publicKeysetBytes: ByteArray,
)

internal object RemoteSnapshotCrypto {
    init {
        HybridConfig.register()
    }

    private val parameters: HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
        .setVariant(HpkeParameters.Variant.TINK)
        .build()

    fun newRecipient(): RemoteSnapshotRecipient {
        val privateKeyset = KeysetHandle.generateNew(parameters)
        val publicKeysetBytes = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            privateKeyset.publicKeysetHandle,
        )
        return RemoteSnapshotRecipient(privateKeyset, publicKeysetBytes)
    }

    fun encrypt(publicKeysetBytes: ByteArray, plaintext: ByteArray, contextInfo: ByteArray): ByteArray {
        require(publicKeysetBytes.size in 32..MAX_PUBLIC_KEYSET_BYTES)
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES)
        val publicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(publicKeysetBytes)
        return publicKeyset
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
            .encrypt(plaintext, contextInfo)
    }

    fun decrypt(
        recipient: RemoteSnapshotRecipient,
        ciphertext: ByteArray,
        contextInfo: ByteArray,
    ): ByteArray {
        require(ciphertext.size in 1..MAX_CIPHERTEXT_BYTES)
        return recipient.privateKeyset
            .getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
            .decrypt(ciphertext, contextInfo)
    }

    fun contextInfo(accountId: String, request: RemoteSnapshotRequest): ByteArray {
        requireAccountUuid(accountId)
        return listOf(
            RemoteSnapshotRequest.PROTOCOL_VERSION,
            accountId.lowercase(),
            request.requestId,
            request.eventId,
            request.taskId,
            request.sourceDeviceId,
            request.requesterDeviceId,
            request.expiresAtEpochMillis.toString(),
        ).joinToString(separator = "|").encodeToByteArray()
    }

    private const val MAX_PUBLIC_KEYSET_BYTES = 3_072
    const val MAX_PLAINTEXT_BYTES = 120 * 1_024
    private const val MAX_CIPHERTEXT_BYTES = 124 * 1_024
}
