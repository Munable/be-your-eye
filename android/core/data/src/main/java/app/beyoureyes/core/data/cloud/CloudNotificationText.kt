package app.beyoureyes.core.data.cloud

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun CloudEventRow.displayText(languageTag: String = "en"): String {
    val zh = languageTag.substringBefore('-').equals("zh", ignoreCase = true)
    val type = payload.stringOrNull("type")
    return when (type) {
        "object_episode" -> {
            val target = payload.stringOrNull("target_id") ?: if (zh) "目标" else "Target"
            val condition = payload.stringOrNull("condition")
            when (condition) {
                "disappeared" -> if (zh) "$target 已消失" else "$target disappeared"
                "count_matched" -> {
                    val count = payload.stringOrNull("count")
                    if (zh) {
                        if (count == null) "$target 数量已达到条件" else "$target 数量达到 $count"
                    } else {
                        if (count == null) "$target count met the condition" else "$target count reached $count"
                    }
                }
                else -> if (zh) "$target 已出现" else "$target appeared"
            }
        }
        "visual_condition_met" -> {
            val target = payload.stringOrNull("target_id") ?: if (zh) "目标" else "Target"
            val duration = payload.stringOrNull("duration_ms")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.conditionDurationLabel(zh)
            when (payload.stringOrNull("condition")) {
                "present_for_duration" ->
                    if (zh) {
                        duration?.let { "$target 已持续可见 $it" } ?: "$target 持续可见已达到条件"
                    } else {
                        duration?.let { "$target remained visible for $it" }
                            ?: "$target remained visible long enough"
                    }
                "absent_for_duration" ->
                    if (zh) {
                        duration?.let { "$target 已连续未见 $it" } ?: "$target 持续未见已达到条件"
                    } else {
                        duration?.let { "$target remained absent for $it" }
                            ?: "$target remained absent long enough"
                    }
                else -> if (zh) "目标条件已达到" else "Target condition met"
            }
        }
        "reading_threshold_crossed" -> {
            val reading = payload["reading"] as? JsonObject
            val value = reading?.stringOrNull("display_text") ?: if (zh) "新读数" else "New reading"
            if (zh) "读数达到 $value" else "Reading reached $value"
        }
        "state_transition" -> {
            val from = payload.stringOrNull("from_state")
            val to = payload.stringOrNull("to_state") ?: if (zh) "新状态" else "new state"
            if (from == app.beyoureyes.core.domain.MonitoringSessionTransition.REFERENCE_EPISODE_OPEN &&
                to == app.beyoureyes.core.domain.MonitoringSessionTransition.MONITORING_STOPPED
            ) {
                if (zh) "监控已停止，线索已结束" else "Monitoring stopped; episode ended"
            } else if (from == null) {
                if (zh) "状态变为 $to" else "State changed to $to"
            } else {
                if (zh) "状态由 $from 变为 $to" else "State changed from $from to $to"
            }
        }
        else -> if (zh) "检测到新的监控事件" else "A new monitoring event was detected"
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun Long.conditionDurationLabel(zh: Boolean): String = if (this % 1_000L == 0L) {
    if (zh) "${this / 1_000L} 秒" else "${this / 1_000L} s"
} else {
    if (zh) "$this 毫秒" else "$this ms"
}
