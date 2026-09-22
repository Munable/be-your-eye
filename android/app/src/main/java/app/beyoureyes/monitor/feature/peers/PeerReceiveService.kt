package app.beyoureyes.monitor.feature.peers

import android.Manifest
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.beyoureyes.monitor.MainActivity
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.appContainer
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** User-started text-message receiving only. This service never owns or starts a camera. */
class PeerReceiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var connection: HttpsURLConnection? = null
    private var started = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        val pair = appContainer.peerAlerts.state.value.pairing ?: run { stopSelf(); return START_NOT_STICKY }
        val localized = ContextCompat.getContextForLanguage(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, localized.getString(R.string.peer_title), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, localized.getString(R.string.peer_alert_channel), NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getService(this, 1801, Intent(this, PeerReceiveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(localized.getString(R.string.peer_receiving))
            .setContentText(localized.getString(R.string.peer_receiving_detail))
            .setOngoing(true).setContentIntent(openIntent())
            .addAction(0, localized.getString(R.string.peer_stop), stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1800, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(1800, notification)
        started = true
        scope.launch {
            var backoff = 5_000L
            while (isActive && appContainer.peerAlerts.state.value.pairing == pair) {
                appContainer.peerAlerts.connection(PeerConnection.CONNECTING)
                try {
                    val channel = (URL("${pair.relay}/${pair.topic}/json?since=24h").openConnection() as HttpsURLConnection).apply {
                        instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 70_000
                        setRequestProperty("Accept", "application/x-ndjson")
                    }
                    connection = channel
                    try {
                        check(channel.responseCode == 200)
                        appContainer.peerAlerts.connection(PeerConnection.LISTENING)
                        backoff = 5_000L
                        channel.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            while (isActive && appContainer.peerAlerts.state.value.pairing == pair) {
                                val line = readBoundedLine(reader) ?: break
                                val alert = runCatching {
                                    val message = JsonParser.parseString(line).asJsonObject
                                    if (message["event"]?.asString != "message") return@runCatching null
                                    PeerCipher.open(pair, message["message"].asString, System.currentTimeMillis())
                                }.getOrNull() ?: continue
                                if (appContainer.peerAlerts.accept(pair, alert)) notifyAlert(alert)
                            }
                        }
                    } finally { channel.disconnect(); connection = null }
                } catch (_: Exception) {
                    // Raw relay responses and pairing material must never enter logs or user copy.
                }
                if (!isActive) break
                appContainer.peerAlerts.connection(PeerConnection.RETRYING)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notifyAlert(alert: PeerAlert) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val localized = ContextCompat.getContextForLanguage(this)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(localized.getString(R.string.peer_alert_channel))
            .setContentText(alert.text(localized)).setStyle(NotificationCompat.BigTextStyle().bigText(alert.text(localized)))
            .setWhen(alert.at).setAutoCancel(true).setContentIntent(openIntent()).build()
        getSystemService(NotificationManager::class.java).notify("peer-${alert.id}", 1802, notification)
    }
    private fun openIntent() = PendingIntent.getActivity(this, 1803,
        Intent(this, MainActivity::class.java).putExtra("open_paired_alerts", true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    override fun onDestroy() {
        scope.cancel(); connection?.disconnect()
        appContainer.peerAlerts.connection(PeerConnection.OFF)
        super.onDestroy()
    }
    companion object {
        const val STOP = "stop-paired-receiver"
        private const val SERVICE_CHANNEL = "paired-receiver"
        private const val ALERT_CHANNEL = "paired-alerts"
        internal fun readBoundedLine(reader: BufferedReader): String? {
            val line = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c == -1) return line.toString().takeIf { it.isNotEmpty() }
                if (c == 10) return line.toString()
                require(line.length < 8192)
                if (c != 13) line.append(c.toChar())
            }
        }
    }
}

internal fun PeerAlert.text(context: android.content.Context): String = when (kind) {
    "test" -> context.getString(R.string.peer_test_received)
    "reading" -> context.getString(R.string.peer_reading, monitor, value)
    "appeared" -> context.getString(R.string.peer_appeared, monitor)
    "left" -> context.getString(R.string.peer_left, monitor)
    else -> context.getString(R.string.peer_matched, monitor)
}
