package app.beyoureyes.monitor.service.monitoring

import java.io.File

/**
 * Remembers that the one-shot pending-baseline prompt was already posted for a task. The markers
 * are app-private empty files keyed by the UUIDv7 task id; the prompt is never repeated for the
 * same pending-baseline period, and a re-confirmed (then deleted) task takes its marker with it.
 */
internal class ReadingBaselinePromptStore(private val filesDir: File) {
    private val dir: File get() = File(filesDir, "reading-baseline-prompts")

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
