package app.beyoureyes.monitor.feature.peers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.monitor.appContainer
import com.google.gson.JsonParser
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

internal object PeerAlertSender {
    fun event(context: Context, eventId: String) {
        val store = context.appContainer.peerAlerts
        // The local notification worker calls this on its worker thread.
        runBlocking { store.awaitReady() }
        if (store.state.value.pairing == null || store.hasSent(eventId)) return
        val dao = MonitorDatabaseFactory.open(context).monitoringDao()
        val event = dao.findEvent(eventId) ?: return
        if (event.occurredAtEpochMillis < System.currentTimeMillis() - 86_400_000L) return
        val payload = JsonParser.parseString(event.payloadJson).asJsonObject
        val type = payload["type"]?.asString
        val kind = when (type) {
            "reading_threshold_crossed" -> "reading"
            "object_episode" -> when (payload["condition"]?.asString) {
                "appeared" -> "appeared"
                "disappeared" -> "left"
                else -> "matched"
            }
            "visual_condition_met" -> "matched"
            else -> return
        }
        val value = if (kind == "reading") payload.getAsJsonObject("reading")?.get("display_text")?.asString.orEmpty() else ""
        val title = dao.findProductTask(event.taskId)?.title.orEmpty()
        enqueue(context, PeerAlert(eventId, store.deviceId, event.occurredAtEpochMillis, kind,
            title.filter { it.code >= 32 && it.code != 127 }.take(160), value.take(64)))
    }
    fun test(context: Context) {
        enqueue(context, PeerAlert(UUID.randomUUID().toString(), context.appContainer.peerAlerts.deviceId,
            System.currentTimeMillis(), "test", ""))
    }
    private fun enqueue(context: Context, alert: PeerAlert) {
        val store = context.appContainer.peerAlerts
        val pair = store.state.value.pairing ?: return
        if (store.hasSent(alert.id)) return
        val request = OneTimeWorkRequestBuilder<PeerAlertSendWorker>()
            .setInputData(workDataOf("pair" to pair.fingerprint, "id" to alert.id, "at" to alert.at,
                "ciphertext" to PeerCipher.seal(pair, alert)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .addTag("paired-alerts")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("peer-${pair.fingerprint}-${alert.id}", ExistingWorkPolicy.KEEP, request)
        store.sent(PeerSendState.QUEUED)
    }
}

internal class PeerAlertSendWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = applicationContext.appContainer.peerAlerts
        store.awaitReady()
        val pair = store.state.value.pairing ?: return@withContext Result.success()
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        if (pair.fingerprint != inputData.getString("pair") || store.hasSent(id)) return@withContext Result.success()
        if (inputData.getLong("at", 0) < System.currentTimeMillis() - 86_400_000L) return@withContext Result.failure()
        val ciphertext = inputData.getString("ciphertext") ?: return@withContext Result.failure()
        val connection = (URL("${pair.relay}/${pair.topic}").openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false; requestMethod = "POST"; doOutput = true
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setFixedLengthStreamingMode(ciphertext.toByteArray(Charsets.UTF_8).size)
        }
        try {
            connection.outputStream.use { it.write(ciphertext.toByteArray(Charsets.UTF_8)) }
            when (val status = connection.responseCode) {
                in 200..299 -> { store.markSent(pair, id); Result.success() }
                in 500..599 -> if (runAttemptCount < 3) Result.retry() else failed(store)
                else -> { if (status == 429) store.sent(PeerSendState.FAILED); failed(store) }
            }
        } catch (_: java.io.IOException) {
            if (runAttemptCount < 3) Result.retry() else failed(store)
        } finally { connection.disconnect() }
    }
    private fun failed(store: PeerAlertStore): Result { store.sent(PeerSendState.FAILED); return Result.failure() }
}
