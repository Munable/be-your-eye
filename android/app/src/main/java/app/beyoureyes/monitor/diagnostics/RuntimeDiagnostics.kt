package app.beyoureyes.monitor.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Bounded, app-private product diagnostics for setup and monitoring failures.
 *
 * Entries deliberately exclude task titles, reference images, frames, prompts and recognized
 * values. Upload is not implemented here; a future opt-in export may read these metadata only.
 */
internal object RuntimeDiagnostics {
    private const val LOG_TAG = "BeYourEyeRuntime"
    private const val DIRECTORY_NAME = "diagnostics"
    private const val CURRENT_FILE_NAME = "runtime.jsonl"
    private const val PREVIOUS_FILE_NAME = "runtime.previous.jsonl"
    private const val MAX_FILE_BYTES = 256L * 1024L
    private val writeLock = Any()

    fun record(
        context: Context,
        event: String,
        details: Map<String, String> = emptyMap(),
    ) {
        val safeEvent = sanitizeEvent(event)
        val safeDetails = sanitizeDetails(details)
        Log.i(LOG_TAG, safeEvent)
        runCatching {
            synchronized(writeLock) {
                BoundedRuntimeDiagnostics(
                    directory = File(context.applicationContext.filesDir, DIRECTORY_NAME),
                    currentFileName = CURRENT_FILE_NAME,
                    previousFileName = PREVIOUS_FILE_NAME,
                    maxFileBytes = MAX_FILE_BYTES,
                ).append(safeEvent, safeDetails)
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "diagnostic_write_failed type=${error.javaClass.simpleName}")
        }
    }

    internal fun currentFile(context: Context): File =
        File(File(context.applicationContext.filesDir, DIRECTORY_NAME), CURRENT_FILE_NAME)

    internal fun previousFile(context: Context): File =
        File(File(context.applicationContext.filesDir, DIRECTORY_NAME), PREVIOUS_FILE_NAME)

    private fun sanitizeEvent(value: String): String = value
        .lowercase()
        .map { character ->
            if (character in 'a'..'z' || character in '0'..'9' ||
                character == '_' || character == '-' || character == '.'
            ) character else '_'
        }
        .joinToString("")
        .trim('_')
        .take(MAX_EVENT_LENGTH)
        .ifEmpty { "invalid_event" }

    private fun sanitizeDetails(values: Map<String, String>): Map<String, String> = values.entries
        .asSequence()
        .sortedBy(Map.Entry<String, String>::key)
        .mapNotNull { (key, value) ->
            val safeKey = sanitizeEvent(key).take(MAX_DETAIL_KEY_LENGTH)
            val safeValue = sanitizeRuntimeDiagnosticValue(value)
            safeKey.takeIf(String::isNotEmpty)?.let { it to safeValue }
        }
        .take(MAX_DETAIL_COUNT)
        .toMap(linkedMapOf())

    private const val MAX_EVENT_LENGTH = 64
    private const val MAX_DETAIL_KEY_LENGTH = 48
    private const val MAX_DETAIL_VALUE_LENGTH = 160
    private const val MAX_DETAIL_COUNT = 8
}

/**
 * Details are metadata only, but callers may eventually pass transport diagnostics. Scrub
 * credential-shaped values before they reach Logcat or the app-private diagnostics file.
 */
internal fun sanitizeRuntimeDiagnosticValue(value: String): String {
    val normalized = value
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .trim()
    return normalized
        .replace(QUERY_SECRET_PATTERN, "$1[redacted]")
        .replace(CREDENTIAL_PATTERN, "[redacted]")
        .take(MAX_DETAIL_VALUE_LENGTH)
}

private val QUERY_SECRET_PATTERN = Regex(
    "(?i)([?&](?:api[_-]?key|access[_-]?token|authorization|token)=)[^&\\s]+",
)

private val CREDENTIAL_PATTERN = Regex(
    "(?i)(?:sk-|sb_(?:publishable|secret)_|bearer\\s+|eyJ)[A-Za-z0-9._~+/=-]{16,}",
)

private const val MAX_DETAIL_VALUE_LENGTH = 160

internal class BoundedRuntimeDiagnostics(
    private val directory: File,
    private val currentFileName: String = "runtime.jsonl",
    private val previousFileName: String = "runtime.previous.jsonl",
    private val maxFileBytes: Long = 256L * 1024L,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxFileBytes > 0)
        require('/' !in currentFileName && '/' !in previousFileName)
        require(currentFileName != previousFileName)
    }

    fun append(event: String, details: Map<String, String>) {
        directory.mkdirs()
        check(directory.isDirectory) { "diagnostic directory is unavailable" }
        val detailJson = details.entries.joinToString(",") { (key, value) ->
            "${key.toJsonString()}:${value.toJsonString()}"
        }
        val line = "{" +
            "\"recorded_at_epoch_ms\":${nowEpochMillis()}," +
            "\"event\":${event.toJsonString()}," +
            "\"details\":{$detailJson}" +
            "}\n"
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxFileBytes) { "single diagnostic entry exceeds the file bound" }

        val current = File(directory, currentFileName)
        if (current.exists() && current.length() + bytes.size > maxFileBytes) rotate(current)
        FileOutputStream(current, true).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun String.toJsonString(): String = buildString(length + 2) {
        append('"')
        this@toJsonString.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun rotate(current: File) {
        val previous = File(directory, previousFileName)
        if (previous.exists() && !previous.delete()) {
            error("previous diagnostic log cannot be replaced")
        }
        if (!current.renameTo(previous)) {
            if (!current.delete()) error("current diagnostic log cannot be rotated")
        }
    }
}
