package app.beyoureyes.core.data.cloud

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudNotificationTextTest {
    @Test
    fun visualDurationConditionUsesConditionLanguageInsteadOfEpisodeLanguage() {
        assertEquals(
            "apple remained visible for 3 s",
            event(buildJsonObject {
                put("type", "visual_condition_met")
                put("target_id", "apple")
                put("condition", "present_for_duration")
                put("duration_ms", 3_000)
            }).displayText(),
        )
        assertEquals(
            "apple remained absent for 5 s",
            event(buildJsonObject {
                put("type", "visual_condition_met")
                put("target_id", "apple")
                put("condition", "absent_for_duration")
                put("duration_ms", 5_000)
            }).displayText(),
        )
    }

    @Test
    fun objectEpisodeUsesItsDetectionFields() {
        assertEquals(
            "person count reached 2",
            event(buildJsonObject {
                put("type", "object_episode")
                put("target_id", "person")
                put("condition", "count_matched")
                put("duration_ms", 0)
                put("count", 2)
            }).displayText(),
        )
    }

    @Test
    fun readingUsesTheNestedStructuredReadingUnion() {
        assertEquals(
            "Reading reached 123.40kPa",
            event(buildJsonObject {
                put("type", "reading_threshold_crossed")
                put("reading", buildJsonObject {
                    put("type", "structured_reading")
                    put("status", "stable")
                    put("display_text", "123.40kPa")
                    put("value_decimal", "123.40")
                    put("format", buildJsonObject {
                        put("profile_id", "confirmed_reading_format_v2")
                        put("kind", "decimal")
                        put("fractional_digits", 2)
                        put("time_segments", kotlinx.serialization.json.JsonNull)
                        put("unit", "kPa")
                    })
                    put("unit", "kPa")
                    put("confidence", 0.98)
                    put("source_kind", "digital_display")
                    put("observed_at", "2026-08-03T00:00:00Z")
                })
                put("operator", "gte")
                put("threshold_decimal", "120")
            }).displayText(),
        )
    }

    @Test
    fun stateTransitionUsesBothStateFields() {
        assertEquals(
            "State changed from closed to open",
            event(buildJsonObject {
                put("type", "state_transition")
                put("from_state", "closed")
                put("to_state", "open")
            }).displayText(),
        )
    }

    @Test
    fun monitoringStopUsesProductLanguageInsteadOfWireStateNames() {
        assertEquals(
            "Monitoring stopped; episode ended",
            event(buildJsonObject {
                put("type", "state_transition")
                put("from_state", "reference_episode_open")
                put("to_state", "monitoring_stopped")
            }).displayText(),
        )
    }

    @Test
    fun malformedOrUnknownPayloadFallsBackWithoutThrowing() {
        assertEquals(
            "A new monitoring event was detected",
            event(buildJsonObject { put("type", buildJsonObject { put("unexpected", true) }) })
                .displayText(),
        )
    }

    @Test
    fun chineseLocaleUsesChineseNotificationCopy() {
        assertEquals(
            "apple 已出现",
            event(buildJsonObject {
                put("type", "object_episode")
                put("target_id", "apple")
                put("condition", "appeared")
            }).displayText("zh-Hans"),
        )
    }

    private fun event(payload: kotlinx.serialization.json.JsonObject) = CloudEventRow(
        eventId = "01900000-0000-7000-8000-000000000031",
        taskId = "01900000-0000-7000-8000-000000000030",
        taskRevision = 2,
        episodeId = "01900000-0000-7000-8000-000000000032",
        sourceSequence = 9,
        occurredAt = "2026-08-03T00:00:00Z",
        monitoringDeviceId = "01900000-0000-7000-8000-000000000033",
        payload = payload,
    )
}
