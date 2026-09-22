package app.beyoureyes.monitor.feature.peers

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PeerProtocolTest {
    private val now = 1_800_000_000_000L
    private fun alert(at: Long = now) = PeerAlert(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        at, "reading", "锅炉压力 • Pressure", "9.2 MPa")

    @Test fun `QR round trip preserves pairing and independent groups cannot read each other`() {
        val pair = PeerPairing.create("https://ntfy.sh/")
        assertEquals(pair, PeerPairing.parse(pair.code()))
        val message = alert()
        val encrypted = PeerCipher.seal(pair, message)
        assertFalse(encrypted.contains(message.monitor))
        assertFalse(encrypted.contains(pair.key))
        assertEquals(message, PeerCipher.open(pair, encrypted, now))
        assertThrows(Exception::class.java) { PeerCipher.open(PeerPairing.create("https://ntfy.sh"), encrypted, now) }
        assertThrows(Exception::class.java) {
            PeerCipher.open(pair.copy(topic = PeerPairing.create(pair.relay).topic), encrypted, now)
        }
    }

    @Test fun `every encryption uses a new nonce and modified ciphertext is rejected`() {
        val pair = PeerPairing.create("https://ntfy.sh")
        val message = alert()
        val encrypted = PeerCipher.seal(pair, message)
        assertNotEquals(encrypted, PeerCipher.seal(pair, message))
        val chars = encrypted.toCharArray()
        chars[30] = if (chars[30] == 'A') 'B' else 'A'
        assertThrows(Exception::class.java) { PeerCipher.open(pair, String(chars), now) }
        assertThrows(Exception::class.java) { PeerCipher.open(pair, "bye1." + "A".repeat(4000), now) }
    }

    @Test fun `expired and future replay payloads are rejected`() {
        val pair = PeerPairing.create("https://ntfy.sh")
        for (at in listOf(now - 86_400_001, now + 300_001)) {
            assertThrows(Exception::class.java) { PeerCipher.open(pair, PeerCipher.seal(pair, alert(at)), now) }
        }
        assertEquals(now - 60_000, PeerCipher.open(pair, PeerCipher.seal(pair, alert(now - 60_000)), now).at)
    }

    @Test fun `pairing refuses cleartext credentials paths and malformed secrets`() {
        for (url in listOf("http://ntfy.sh", "https://user:secret@ntfy.sh", "https://ntfy.sh/topic",
            "https://ntfy.sh?key=secret", "https://ntfy.sh#fragment", "https://ntfy.sh:0")) {
            assertThrows(Exception::class.java) { PeerPairing.create(url) }
        }
        val pair = PeerPairing.create("https://example.org:8443")
        assertEquals(pair, PeerPairing.parse(pair.code()))
        assertThrows(Exception::class.java) { pair.copy(key = "short") }
        assertThrows(Exception::class.java) { pair.copy(topic = "guessable") }
        assertThrows(Exception::class.java) { PeerPairing.parse("beyoureye-pair:2:wrong") }
    }

    @Test fun `message content is bounded and only supported kinds may be shown`() {
        assertThrows(Exception::class.java) { alert().copy(monitor = "a".repeat(161)) }
        assertThrows(Exception::class.java) { alert().copy(value = "x\nmalicious") }
        assertThrows(Exception::class.java) { alert().copy(kind = "open_url") }
        assertThrows(Exception::class.java) { alert().copy(id = "1-1-1-1-1") }
        val pair = PeerPairing.create("https://ntfy.sh")
        assertTrue(PeerCipher.seal(pair, alert().copy(monitor = "界".repeat(160), value = "界".repeat(64))).length < 4096)
    }
}
