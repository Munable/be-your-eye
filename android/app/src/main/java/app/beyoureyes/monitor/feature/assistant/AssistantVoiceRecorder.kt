package app.beyoureyes.monitor.feature.assistant

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Removes readable audio immediately. If the filesystem cannot unlink the entry, truncate it and
 * leave the empty entry for the next recorder instance to remove.
 */
internal fun deleteEphemeralVoiceFile(file: File): Boolean {
    if (!file.exists() || file.delete()) return true
    val scrubbed = file.isFile && runCatching {
        FileOutputStream(file, false).use { output -> output.fd.sync() }
        file.length() == 0L
    }.getOrDefault(false)
    if (!file.delete()) {
        file.setLastModified(0L)
        file.deleteOnExit()
    }
    return !file.exists() || scrubbed
}

internal fun clearOrphanedVoiceRecordings(directory: File): Boolean {
    if (!directory.exists()) return true
    if (!directory.isDirectory) return false
    val candidates = directory.listFiles() ?: return false
    var cleared = true
    candidates.forEach { candidate ->
        cleared = candidate.isFile && deleteEphemeralVoiceFile(candidate) && cleared
    }
    return cleared
}

/** One ephemeral AAC/MPEG-4 capture. The file stays in cache and is deleted after transcription. */
internal class AssistantVoiceRecorder(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val directory = File(appContext.cacheDir, "assistant-voice")
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAtMillis: Long = 0L

    private val cacheReady = (directory.isDirectory || directory.mkdirs()) &&
        clearOrphanedVoiceRecordings(directory)

    fun start(): Boolean {
        if (!cacheReady || recorder != null) return false
        val file = File(directory, "voice-${UUID.randomUUID()}.m4a")
        val candidate = createRecorder()
        return try {
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setAudioEncodingBitRate(64_000)
            candidate.setAudioSamplingRate(16_000)
            candidate.setMaxFileSize(MAX_VOICE_AUDIO_BYTES.toLong())
            candidate.setOutputFile(file.absolutePath)
            candidate.prepare()
            candidate.start()
            recorder = candidate
            output = file
            startedAtMillis = SystemClock.elapsedRealtime()
            true
        } catch (_: Throwable) {
            runCatching { candidate.reset() }
            candidate.release()
            deleteEphemeralVoiceFile(file)
            false
        }
    }

    fun stop(): CapturedVoiceRecording? {
        val active = recorder ?: return null
        val file = output
        val duration = (SystemClock.elapsedRealtime() - startedAtMillis)
            .coerceAtMost(MAX_VOICE_DURATION_MILLIS)
        recorder = null
        output = null
        startedAtMillis = 0L
        val stopped = runCatching { active.stop() }.isSuccess
        runCatching { active.reset() }
        active.release()
        if (!stopped || file == null || duration < MIN_VOICE_DURATION_MILLIS ||
            !file.isFile || file.length() !in 32..MAX_VOICE_AUDIO_BYTES.toLong()
        ) {
            file?.let(::deleteEphemeralVoiceFile)
            return null
        }
        return CapturedVoiceRecording(file, duration)
    }

    fun cancel() {
        stop()?.delete()
    }

    override fun close() = cancel()

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(appContext)
    } else {
        MediaRecorder()
    }
}
