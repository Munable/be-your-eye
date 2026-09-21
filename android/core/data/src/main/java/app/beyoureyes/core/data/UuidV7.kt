package app.beyoureyes.core.data

import java.security.SecureRandom

/** UUIDv7 identity used by Task, Event and episode public contracts. */
object UuidV7 {
    private val secureRandom = SecureRandom()
    private val pattern = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )

    fun isValid(value: String): Boolean = pattern.matches(value)

    @Synchronized
    fun generate(epochMillis: Long = System.currentTimeMillis()): String {
        require(epochMillis in 0..0xFFFF_FFFF_FFFFL) { "epochMillis exceeds UUIDv7 range" }
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        for (index in 0 until 6) {
            bytes[5 - index] = (epochMillis ushr (index * 8)).toByte()
        }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return buildString(36) {
            bytes.forEachIndexed { index, byte ->
                if (index in HYPHEN_BYTE_INDICES) append('-')
                append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    private val HYPHEN_BYTE_INDICES = setOf(4, 6, 8, 10)
}
