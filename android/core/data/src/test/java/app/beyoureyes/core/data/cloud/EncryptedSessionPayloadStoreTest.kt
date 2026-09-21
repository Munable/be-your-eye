package app.beyoureyes.core.data.cloud

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedSessionPayloadStoreTest {
    @Test
    fun writesOnlyCiphertextAndRoundTripsSessionPayload() {
        val file = Files.createTempDirectory("secure-session-test").resolve("session.bin").toFile()
        val key = 0x5a
        val store = EncryptedSessionPayloadStore(
            file,
            SessionPayloadEncryptor { value -> value.map { (it.toInt() xor key).toByte() }.toByteArray() },
            SessionPayloadDecryptor { value -> value.map { (it.toInt() xor key).toByte() }.toByteArray() },
        )
        val session = "access-token|refresh-token".encodeToByteArray()

        store.write(session)

        assertNotEquals(session.decodeToString(), file.readText())
        assertArrayEquals(session, store.read())
        store.delete()
        assertFalse(file.exists())
    }

    @Test
    fun missingOrCorruptPayloadFailsClosed() {
        val file = Files.createTempDirectory("secure-session-test").resolve("session.bin").toFile()
        val store = EncryptedSessionPayloadStore(
            file,
            SessionPayloadEncryptor { it },
            SessionPayloadDecryptor { error("authentication failed") },
        )
        assertThrows(NoSuchElementException::class.java) { store.read() }
        file.writeText("cipher")
        assertThrows(IllegalStateException::class.java) { store.read() }
    }
}
