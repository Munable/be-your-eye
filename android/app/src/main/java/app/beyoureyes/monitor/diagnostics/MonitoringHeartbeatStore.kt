package app.beyoureyes.monitor.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val SAFE_MONITOR_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

internal data class MonitoringHeartbeat(
    val monitorId: String,
    val startedAtEpochMillis: Long,
    val lastHeartbeatAtEpochMillis: Long,
) {
    init {
        require(SAFE_MONITOR_ID.matches(monitorId))
        require(startedAtEpochMillis >= 0)
        require(lastHeartbeatAtEpochMillis >= startedAtEpochMillis)
    }
}

/**
 * One small crash marker for the single allowed monitoring session.
 *
 * It contains no monitor name, frame, reading or event content. A clean stop deletes it; a process
 * death leaves it behind so the next process can report an explicit stopped state instead of idle.
 */
internal class MonitoringHeartbeatStore(private val filesDir: File) {
    private val directory = filesDir.resolve(DIRECTORY_NAME)
    private val marker = directory.resolve(FILE_NAME)

    fun begin(monitorId: String, nowEpochMillis: Long): Boolean = synchronized(FILE_LOCK) {
        val existing = readLocked()?.takeIf { it.monitorId == monitorId }
        val value = MonitoringHeartbeat(
            monitorId = monitorId,
            startedAtEpochMillis = existing?.startedAtEpochMillis ?: nowEpochMillis,
            lastHeartbeatAtEpochMillis = maxOf(existing?.lastHeartbeatAtEpochMillis ?: nowEpochMillis, nowEpochMillis),
        )
        writeLocked(value)
    }

    fun heartbeat(monitorId: String, nowEpochMillis: Long): Boolean = synchronized(FILE_LOCK) {
        val existing = readLocked() ?: return false
        if (existing.monitorId != monitorId) return false
        writeLocked(
            existing.copy(
                lastHeartbeatAtEpochMillis = maxOf(
                    existing.lastHeartbeatAtEpochMillis,
                    nowEpochMillis,
                ),
            ),
        )
    }

    fun clear(monitorId: String? = null): Boolean = synchronized(FILE_LOCK) {
        val existing = readLocked()
        if (monitorId != null && existing?.monitorId != monitorId) return false
        runCatching { Files.deleteIfExists(marker.toPath()) }.getOrDefault(false)
    }

    /** Reads the marker without removing it, so database recovery can win before acknowledgement. */
    fun interruptedRun(): MonitoringHeartbeat? = synchronized(FILE_LOCK) {
        val value = readLocked()
        if (value == null) {
            // A corrupt or replaced marker cannot identify a task safely. Remove it instead of
            // retrying the same unreadable recovery on every process start.
            runCatching { Files.deleteIfExists(marker.toPath()) }
        }
        value
    }

    /** Removes exactly the marker that was recovered, never a newer session's marker. */
    fun acknowledgeInterruptedRun(expected: MonitoringHeartbeat): Boolean = synchronized(FILE_LOCK) {
        if (readLocked() != expected) return false
        runCatching { Files.deleteIfExists(marker.toPath()) }.getOrDefault(false)
    }

    private fun readLocked(): MonitoringHeartbeat? = runCatching {
        require(marker.isFile && !Files.isSymbolicLink(marker.toPath()))
        require(marker.length() in 1..MAX_FILE_BYTES)
        val lines = marker.readLines(StandardCharsets.UTF_8)
        require(lines.size == 4 && lines.first() == SCHEMA)
        val entries = lines.drop(1).associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0)
            line.substring(0, separator) to line.substring(separator + 1)
        }
        require(
            entries.keys == setOf(
                "monitor_id",
                "started_at_epoch_millis",
                "last_heartbeat_at_epoch_millis",
            ),
        )
        MonitoringHeartbeat(
            monitorId = entries.getValue("monitor_id"),
            startedAtEpochMillis = entries.getValue("started_at_epoch_millis").toLong(),
            lastHeartbeatAtEpochMillis = entries.getValue("last_heartbeat_at_epoch_millis").toLong(),
        )
    }.getOrNull()

    private fun writeLocked(value: MonitoringHeartbeat): Boolean = runCatching {
        require(directory.isDirectory || directory.mkdirs())
        val temporary = directory.resolve("$FILE_NAME.tmp")
        val bytes = buildString {
            appendLine(SCHEMA)
            append("monitor_id=").appendLine(value.monitorId)
            append("started_at_epoch_millis=").appendLine(value.startedAtEpochMillis)
            append("last_heartbeat_at_epoch_millis=").appendLine(value.lastHeartbeatAtEpochMillis)
        }.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            temporary.delete()
            return false
        }
        true
    }.getOrElse {
        directory.resolve("$FILE_NAME.tmp").delete()
        false
    }

    private companion object {
        val FILE_LOCK = Any()
        const val DIRECTORY_NAME = "monitoring-runtime"
        const val FILE_NAME = "active-heartbeat.txt"
        const val SCHEMA = "be-your-eyes-monitoring-heartbeat-v1"
        const val MAX_FILE_BYTES = 2_048L
    }
}
