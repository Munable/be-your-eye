package app.beyoureyes.monitor.feature.peers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class PeerPairing(val relay: String, val topic: String, val key: String) {
    init {
        val uri = URI(relay)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty() &&
            uri.port in -1..65535 && uri.port != 0)
        require(Regex("bye-[a-f0-9]{48}").matches(topic))
        require(Base64.getUrlDecoder().decode(key).size == 32)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(key)) == key)
    }
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$relay/$topic/$key".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun code(): String = "beyoureye-pair:1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
        JsonObject().apply { addProperty("relay", relay); addProperty("topic", topic); addProperty("key", key) }
            .toString().toByteArray(Charsets.UTF_8))
    companion object {
        fun create(relay: String): PeerPairing {
            val random = SecureRandom()
            val topicBytes = ByteArray(24).also(random::nextBytes)
            val key = ByteArray(32).also(random::nextBytes)
            return PeerPairing(relay.trim().removeSuffix("/"),
                "bye-" + topicBytes.joinToString("") { "%02x".format(it) },
                Base64.getUrlEncoder().withoutPadding().encodeToString(key))
        }
        fun parse(code: String): PeerPairing {
            require(code.length in 32..2048 && code.startsWith("beyoureye-pair:1:"))
            val obj = JsonParser.parseString(Base64.getUrlDecoder().decode(code.removePrefix("beyoureye-pair:1:"))
                .toString(Charsets.UTF_8)).asJsonObject
            require(obj.keySet() == setOf("relay", "topic", "key"))
            return PeerPairing(obj["relay"].asString, obj["topic"].asString, obj["key"].asString)
        }
    }
}

internal data class PeerAlert(
    val id: String,
    val sender: String,
    val at: Long,
    val kind: String,
    val monitor: String,
    val value: String = "",
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(UUID.fromString(sender).toString() == sender)
        require(at > 0 && kind in setOf("reading", "appeared", "left", "matched", "test"))
        require(monitor.length <= 160 && value.length <= 64)
        require((monitor + value).none { it.code < 32 || it.code == 127 })
    }
    fun json(): JsonObject = JsonObject().apply {
        addProperty("id", id); addProperty("sender", sender); addProperty("at", at)
        addProperty("kind", kind); addProperty("monitor", monitor); addProperty("value", value)
    }
    companion object {
        fun parse(obj: JsonObject): PeerAlert {
            require(obj.keySet() == setOf("id", "sender", "at", "kind", "monitor", "value"))
            return PeerAlert(obj["id"].asString, obj["sender"].asString, obj["at"].asLong,
                obj["kind"].asString, obj["monitor"].asString, obj["value"].asString)
        }
    }
}

/** Only authenticated ciphertext leaves the phone. The relay never receives the pairing key. */
internal object PeerCipher {
    private const val PREFIX = "bye1."
    fun seal(pair: PeerPairing, alert: PeerAlert): String {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getUrlDecoder().decode(pair.key), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD((PREFIX + pair.topic).toByteArray(Charsets.UTF_8))
        val bytes = nonce + cipher.doFinal(alert.json().toString().toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
    fun open(pair: PeerPairing, message: String, now: Long): PeerAlert {
        require(message.startsWith(PREFIX) && message.length <= 3072)
        val bytes = Base64.getUrlDecoder().decode(message.removePrefix(PREFIX))
        require(bytes.size >= 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getUrlDecoder().decode(pair.key), "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD((PREFIX + pair.topic).toByteArray(Charsets.UTF_8))
        val text = cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        val alert = PeerAlert.parse(JsonParser.parseString(text).asJsonObject)
        require(alert.at >= now - 86_400_000L && alert.at <= now + 300_000L)
        return alert
    }
}
