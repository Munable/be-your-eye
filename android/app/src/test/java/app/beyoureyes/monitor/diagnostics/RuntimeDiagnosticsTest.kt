package app.beyoureyes.monitor.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDiagnosticsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `writes bounded media-free metadata as json lines`() {
        val directory = temporary.newFolder("diagnostics")
        val log = BoundedRuntimeDiagnostics(
            directory = directory,
            maxFileBytes = 1_024,
            nowEpochMillis = { 123L },
        )

        log.append("reading_live_checking", mapOf("diagnostic" to "runtime_processing_failed"))

        val line = File(directory, "runtime.jsonl").readLines().single()
        assertTrue(line.contains("\"recorded_at_epoch_ms\":123"))
        assertTrue(line.contains("\"event\":\"reading_live_checking\""))
        assertTrue(line.contains("\"diagnostic\":\"runtime_processing_failed\""))
        assertFalse(line.contains("image"))
        assertFalse(line.contains("task_title"))
        assertFalse(line.contains("reading_value"))
    }

    @Test
    fun `rotates once before the current file exceeds its byte bound`() {
        val directory = temporary.newFolder("rotating")
        var time = 1L
        val log = BoundedRuntimeDiagnostics(
            directory = directory,
            maxFileBytes = 150,
            nowEpochMillis = { time++ },
        )

        repeat(8) { index -> log.append("event_$index", emptyMap()) }

        val current = File(directory, "runtime.jsonl")
        val previous = File(directory, "runtime.previous.jsonl")
        assertTrue(current.exists())
        assertTrue(previous.exists())
        assertTrue(current.length() <= 150)
        assertTrue(previous.length() <= 150)
        assertTrue(current.readLines().isNotEmpty())
        assertTrue(previous.readLines().isNotEmpty())
    }

    @Test
    fun `scrubs credential shaped detail values before diagnostics persistence`() {
        val providerPrefix = listOf("s", "k-").joinToString("")
        val providerKey = providerPrefix + "abcdefghijklmnopqrstuvwxyz0123456789"
        val bearer = "Bearer " + "abcdefghijklmnopqrstuvwxyz0123456789"

        val safe = sanitizeRuntimeDiagnosticValue(
            "https://example.test/?api_key=$providerKey $bearer eyJabcdefghijklmnopqrstuvwxyz0123456789",
        )

        assertFalse(safe.contains(providerKey))
        assertFalse(safe.contains(bearer))
        assertFalse(safe.contains("eyJabcdefghijklmnopqrstuvwxyz0123456789"))
        assertTrue(safe.contains("[redacted]"))
    }
}
