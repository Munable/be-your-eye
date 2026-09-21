package app.beyoureyes.core.data

import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.domain.ReadingOperator
import app.beyoureyes.core.domain.ReadingSourceKind
import app.beyoureyes.core.domain.RestrictedEventPayload
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorEventUnknownReason
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import app.beyoureyes.core.domain.MonitorReadingOperator
import app.beyoureyes.core.domain.MonitorVisualEventCondition
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.ReadingFormatKind
import app.beyoureyes.core.domain.VisualEventCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorEventFactJsonTest {
    @Test
    fun `visual duration conditions project as one-shot facts instead of episodes`() {
        val payload = RestrictedEventPayloadJson.encode(
            RestrictedEventPayload.VisualConditionMet(
                targetId = "apple",
                condition = VisualEventCondition.ABSENT_FOR_DURATION,
                durationMillis = 5_000,
            ),
        )
        val fact = event(payload).toMonitorEvent().fact

        assertEquals(
            MonitorEventFact.VisualConditionMet(
                targetId = "apple",
                condition = MonitorVisualEventCondition.ABSENT_FOR_DURATION,
                durationMillis = 5_000,
            ),
            fact,
        )
        assertEquals(
            fact,
            event(payload, isRemote = true, remoteTitle = "苹果监控").toMonitorEvent().fact,
        )
    }

    @Test
    fun `object appeared and disappeared are projected from payload not notification copy`() {
        val appeared = event(
            payload = RestrictedEventPayloadJson.encode(
                RestrictedEventPayload.ObjectEpisode(
                    targetId = "reference_target",
                    condition = ObjectEventCondition.APPEARED,
                    durationMillis = 3_000,
                    count = 1,
                ),
            ),
            notificationText = "deliberately unrelated",
        ).toMonitorEvent()
        val disappeared = event(
            payload = RestrictedEventPayloadJson.encode(
                RestrictedEventPayload.ObjectEpisode(
                    targetId = "reference_target",
                    condition = ObjectEventCondition.DISAPPEARED,
                    durationMillis = 1_000,
                    count = 0,
                ),
            ),
        ).toMonitorEvent()

        assertEquals(
            MonitorEventFact.ObjectEpisode(
                "reference_target",
                MonitorObjectEventCondition.APPEARED,
                3_000,
                1,
            ),
            appeared.fact,
        )
        assertEquals("deliberately unrelated", appeared.text)
        assertEquals(
            MonitorObjectEventCondition.DISAPPEARED,
            (disappeared.fact as MonitorEventFact.ObjectEpisode).condition,
        )
    }

    @Test
    fun `single and outside reading facts retain renderable values and thresholds`() {
        val single = readingPayload(
            value = "333.0",
            operator = ReadingOperator.GTE,
            threshold = "300",
        )
        val outside = readingPayload(
            value = "25",
            operator = ReadingOperator.OUTSIDE,
            lower = "10",
            upper = "20",
        )

        assertEquals(
            MonitorEventFact.ReadingThresholdCrossed(
                displayText = "333.0",
                valueDecimal = "333.0",
                format = decimalFormat("333.0"),
                unit = null,
                operator = MonitorReadingOperator.GTE,
                thresholdDecimal = "300",
            ),
            event(single).toMonitorEvent().fact,
        )
        assertEquals(
            MonitorEventFact.ReadingThresholdCrossed(
                displayText = "25",
                valueDecimal = "25",
                format = decimalFormat("25"),
                unit = null,
                operator = MonitorReadingOperator.OUTSIDE,
                lowerThresholdDecimal = "10",
                upperThresholdDecimal = "20",
            ),
            event(outside).toMonitorEvent().fact,
        )
    }

    @Test
    fun `local and remote rows use exactly the same fact projection`() {
        val payload = readingPayload("-12.5", ReadingOperator.LT, threshold = "0")
        val local = event(payload).toMonitorEvent()
        val remote = event(
            payload = payload,
            isRemote = true,
            remoteTitle = "冷库温度",
        ).toMonitorEvent()

        assertEquals(local.fact, remote.fact)
        assertFalse(local.isRemote)
        assertTrue(remote.isRemote)
        assertEquals("冷库温度", remote.remoteMonitorName)
    }

    @Test
    fun `state transition remains a typed fact instead of notification prose`() {
        val payload = RestrictedEventPayloadJson.encode(
            RestrictedEventPayload.StateTransition(fromState = "closed", toState = "open"),
        )

        assertEquals(
            MonitorEventFact.StateTransition("closed", "open"),
            event(payload, notificationText = "unrelated").toMonitorEvent().fact,
        )
    }

    @Test
    fun `malformed unknown and invalid payloads become explicit unknown facts`() {
        val projected = listOf(
            event("not-json"),
            event("{\"type\":\"future_event\"}"),
            event("{\"type\":\"object_episode\",\"condition\":\"appeared\"}"),
        ).map(TimelineEvent::toMonitorEvent)

        assertEquals(
            listOf(
                MonitorEventUnknownReason.MALFORMED_PAYLOAD,
                MonitorEventUnknownReason.UNSUPPORTED_TYPE,
                MonitorEventUnknownReason.INVALID_FIELDS,
            ),
            projected.map { (it.fact as MonitorEventFact.Unknown).reason },
        )
    }

    private fun readingPayload(
        value: String,
        operator: ReadingOperator,
        threshold: String? = null,
        lower: String? = null,
        upper: String? = null,
    ) = RestrictedEventPayloadJson.encode(
        RestrictedEventPayload.ReadingThresholdCrossed(
            displayText = value,
            valueDecimal = value,
            format = decimalFormat(value),
            confidence = 0.96f,
            sourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
            observedAtEpochMillis = 1_786_147_323_000,
            operator = operator,
            thresholdDecimal = threshold,
            lowerThresholdDecimal = lower,
            upperThresholdDecimal = upper,
            unit = null,
        ),
    )

    private fun decimalFormat(value: String) = ConfirmedReadingFormat(
        kind = ReadingFormatKind.DECIMAL,
        fractionalDigits = value.substringAfter('.', "").length,
    )

    private fun event(
        payload: String,
        notificationText: String = "compatibility notification",
        isRemote: Boolean = false,
        remoteTitle: String? = null,
    ) = TimelineEvent(
        eventId = "event-id",
        taskId = "monitor-id",
        taskRevision = 3,
        occurredAtEpochMillis = 1_786_147_323_000,
        notificationText = notificationText,
        payloadJson = payload,
        remoteTaskTitle = remoteTitle,
        remoteCapabilityId = if (isRemote) "structured_reading" else null,
        remoteAccountId = if (isRemote) "account-id" else null,
        isRemoteTask = isRemote,
    )
}
