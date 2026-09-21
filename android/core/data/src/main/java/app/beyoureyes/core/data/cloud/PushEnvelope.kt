package app.beyoureyes.core.data.cloud

/** FCM is only a wake-up hint. Full Event data is fetched through authenticated Supabase. */
data class PushEnvelope(
    val eventId: String,
    val cursorHint: String,
) {
    init {
        require(UUID.matches(eventId)) { "event_id must be a UUID" }
        require(cursorHint.matches(CURSOR)) { "cursor_hint must be a positive decimal cursor" }
    }

    companion object {
        private val UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        private val CURSOR = Regex("^[1-9][0-9]{0,127}$")

        fun parse(data: Map<String, String>): PushEnvelope? {
            if (data.keys != setOf("schema_version", "event_id", "cursor_hint")) return null
            if (data["schema_version"] != "3.0") return null
            return runCatching {
                PushEnvelope(
                    eventId = data.getValue("event_id").lowercase(),
                    cursorHint = data.getValue("cursor_hint"),
                )
            }.getOrNull()
        }
    }
}
