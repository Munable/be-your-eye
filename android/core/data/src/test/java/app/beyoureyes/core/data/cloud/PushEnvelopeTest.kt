package app.beyoureyes.core.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushEnvelopeTest {
    @Test
    fun exactDataOnlyEnvelopeIsAccepted() {
        assertEquals(
            PushEnvelope("018f0870-7b8a-7abc-8abc-0123456789ab", "42"),
            PushEnvelope.parse(
                mapOf(
                    "schema_version" to "3.0",
                    "event_id" to "018f0870-7b8a-7abc-8abc-0123456789ab",
                    "cursor_hint" to "42",
                ),
            ),
        )
    }

    @Test
    fun mediaOrUnexpectedFieldsAreRejected() {
        assertNull(
            PushEnvelope.parse(
                mapOf(
                    "schema_version" to "3.0",
                    "event_id" to "018f0870-7b8a-7abc-8abc-0123456789ab",
                    "cursor_hint" to "42",
                    "image" to "forbidden",
                ),
            ),
        )
        assertNull(
            PushEnvelope.parse(
                mapOf(
                    "schema_version" to "3.0",
                    "event_id" to "not-a-uuid",
                    "cursor_hint" to "42",
                ),
            ),
        )
        assertNull(
            PushEnvelope.parse(
                mapOf(
                    "schema_version" to "3.0",
                    "event_id" to "018f0870-7b8a-7abc-8abc-0123456789ab",
                    "cursor_hint" to "0",
                ),
            ),
        )
        assertNull(
            PushEnvelope.parse(
                mapOf(
                    "schema_version" to "2.0",
                    "event_id" to "018f0870-7b8a-7abc-8abc-0123456789ab",
                    "cursor_hint" to "42",
                ),
            ),
        )
    }
}
