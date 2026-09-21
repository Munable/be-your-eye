package app.beyoureyes.core.data.cloud

import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSnapshotCryptoTest {
    @Test
    fun `hpke round trip binds all transfer identities`() {
        val recipient = RemoteSnapshotCrypto.newRecipient()
        val request = request()
        val plaintext = ByteArray(32_768) { index -> (index * 31).toByte() }
        val context = RemoteSnapshotCrypto.contextInfo(ACCOUNT_ID, request)

        val ciphertext = RemoteSnapshotCrypto.encrypt(
            recipient.publicKeysetBytes,
            plaintext,
            context,
        )

        assertNotEquals(plaintext.toList(), ciphertext.toList())
        assertArrayEquals(plaintext, RemoteSnapshotCrypto.decrypt(recipient, ciphertext, context))
        assertThrows(GeneralSecurityException::class.java) {
            RemoteSnapshotCrypto.decrypt(recipient, ciphertext, "$ACCOUNT_ID|wrong".encodeToByteArray())
        }
    }

    @Test
    fun `wire contract is one bounded non self request`() {
        val request = request()
        assertTrue(request.recipientPublicKey.length < RemoteSnapshotRequest.MAX_PUBLIC_KEY_BASE64_CHARS)
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(sourceDeviceId = request.requesterDeviceId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(expiresAtEpochMillis = request.issuedAtEpochMillis + 30_001)
        }
    }

    private fun request(): RemoteSnapshotRequest {
        val recipient = RemoteSnapshotCrypto.newRecipient()
        return RemoteSnapshotRequest(
            requestId = "01900000-0000-7000-8000-000000000001",
            eventId = "01900000-0000-7000-8000-000000000002",
            taskId = "01900000-0000-7000-8000-000000000003",
            sourceDeviceId = "01900000-0000-7000-8000-000000000004",
            requesterDeviceId = "01900000-0000-7000-8000-000000000005",
            recipientPublicKey = java.util.Base64.getEncoder()
                .encodeToString(recipient.publicKeysetBytes),
            issuedAtEpochMillis = 1_000,
            expiresAtEpochMillis = 31_000,
        )
    }

    private companion object {
        const val ACCOUNT_ID = "018f0870-7b8a-4abc-8abc-3123456789ab"
    }
}
