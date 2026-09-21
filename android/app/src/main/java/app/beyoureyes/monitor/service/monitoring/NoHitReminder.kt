package app.beyoureyes.monitor.service.monitoring

import java.io.File

/**
 * Pure decision rules for the gentle no-hit reminder. A task that has existed for
 * [REMINDER_AGE_MILLIS] without producing any Event gets exactly one local, non-blocking
 * reminder suggesting the optional field test; monitoring keeps running unchanged.
 */
internal object NoHitReminderPolicy {
    const val REMINDER_AGE_MILLIS: Long = 24L * 60L * 60L * 1000L

    /** Bounds the Room event-existence probe while a long-running session stays open. */
    const val CHECK_INTERVAL_MILLIS: Long = 10L * 60L * 1000L

    fun reminderDue(createdAtEpochMillis: Long, nowEpochMillis: Long): Boolean =
        nowEpochMillis - createdAtEpochMillis >= REMINDER_AGE_MILLIS

    fun checkDue(lastCheckAtMonotonicMillis: Long?, nowMonotonicMillis: Long): Boolean =
        lastCheckAtMonotonicMillis == null ||
            nowMonotonicMillis - lastCheckAtMonotonicMillis >= CHECK_INTERVAL_MILLIS
}

/**
 * Remembers that the one-shot no-hit reminder was already posted for a task. The markers are
 * app-private empty files keyed by the UUIDv7 task id; the reminder is never repeated, and a
 * deleted task takes its marker with it.
 */
internal class NoHitReminderStore(private val filesDir: File) {
    private val dir: File get() = File(filesDir, "no-hit-reminders")

    /** Returns true exactly once per task; later calls return false without touching the disk. */
    @Synchronized
    fun claim(taskId: String): Boolean {
        require(taskId.isNotBlank())
        val marker = File(dir, taskId)
        if (marker.isFile) return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return runCatching { marker.createNewFile() }.getOrDefault(false)
    }
}
