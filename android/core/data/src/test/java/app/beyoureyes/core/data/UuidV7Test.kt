package app.beyoureyes.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidV7Test {
    @Test
    fun `generated UUID carries requested millisecond timestamp version and variant`() {
        val timestamp = 1_700_000_000_123L
        val value = UuidV7.generate(timestamp)

        assertTrue(UuidV7.isValid(value))
        assertEquals(timestamp, value.replace("-", "").take(12).toLong(16))
        assertEquals('7', value[14])
        assertTrue(value[19].lowercaseChar() in setOf('8', '9', 'a', 'b'))
    }

    @Test
    fun `same millisecond still produces distinct identities`() {
        val first = UuidV7.generate(42)
        val second = UuidV7.generate(42)

        assertNotEquals(first, second)
        assertTrue(UuidV7.isValid(first))
        assertTrue(UuidV7.isValid(second))
    }

    @Test
    fun `other UUID versions and legacy identifiers fail closed`() {
        assertFalse(UuidV7.isValid("01900000-0000-4000-8000-000000000001"))
        assertFalse(UuidV7.isValid("01900000-0000-7000-8000-00000000000A"))
        assertFalse(UuidV7.isValid("event-1"))
        assertFalse(UuidV7.isValid(""))
    }
}
