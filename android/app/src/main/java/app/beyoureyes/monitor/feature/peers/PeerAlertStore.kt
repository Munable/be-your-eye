package app.beyoureyes.monitor.feature.peers

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class PeerConnection { OFF, CONNECTING, LISTENING, RETRYING }
internal enum class PeerSendState { NONE, QUEUED, SENT, FAILED }
internal data class PeerState(val pairing: PeerPairing? = null, val inbox: List<PeerAlert> = emptyList(),
    val connection: PeerConnection = PeerConnection.OFF, val send: PeerSendState = PeerSendState.NONE,
    val ready: Boolean = false)

/** Pairing secrets and a bounded inbox stay in app-private, backup-excluded storage. */
internal class PeerAlertStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "paired-alerts-v1.json"))
    private val mutable = MutableStateFlow(PeerState())
    private val loaded = CompletableDeferred<Unit>()
    val state = mutable.asStateFlow()
    var deviceId: String = UUID.randomUUID().toString()
        private set
    private data class SeenAlert(val id: String, val at: Long)
    private var seen: List<SeenAlert> = emptyList()
    private var replayFloor: Long = 0
    @Volatile private var published: List<String> = emptyList()
    init {
        // Startup and Compose must never wait for private-storage reads or fsync.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val obj = JsonParser.parseString(file.readFully().toString(Charsets.UTF_8)).asJsonObject
                deviceId = UUID.fromString(obj["device"].asString).toString()
                val pair = obj["pair"]?.takeUnless { it.isJsonNull }?.asString?.let(PeerPairing::parse)
                val inbox = obj["inbox"].asJsonArray.take(200).map { PeerAlert.parse(it.asJsonObject) }
                published = obj["published"]?.asJsonArray?.take(2000)?.map { it.asString }.orEmpty()
                replayFloor = obj["replay_floor"]?.asLong ?: 0
                seen = obj["seen"].asJsonArray.take(2000).map {
                    SeenAlert(it.asJsonObject["id"].asString, it.asJsonObject["at"].asLong)
                }
                mutable.value = PeerState(pair, inbox)
            }
            mutable.update { it.copy(ready = true) }
            loaded.complete(Unit)
        }
    }
    suspend fun awaitReady() = loaded.await()
    @Synchronized fun pair(value: PeerPairing?) {
        check(mutable.value.ready)
        val next = PeerState(pairing = value, ready = true)
        save(next, emptyList(), emptyList(), 0)
        seen = emptyList()
        replayFloor = 0
        published = emptyList()
        mutable.value = next
    }
    fun hasSent(id: String): Boolean = id in published
    @Synchronized fun markSent(pair: PeerPairing, id: String) {
        if (mutable.value.pairing != pair) return
        val nextPublished = (listOf(id) + published).distinct().take(2000)
        val next = mutable.value.copy(send = PeerSendState.SENT)
        save(next, seen, nextPublished)
        published = nextPublished
        mutable.update { it.copy(send = PeerSendState.SENT) }
    }
    fun connection(value: PeerConnection) { mutable.update { it.copy(connection = value) } }
    fun sent(value: PeerSendState) { mutable.update { it.copy(send = value) } }
    @Synchronized fun accept(pair: PeerPairing, alert: PeerAlert): Boolean {
        if (mutable.value.pairing != pair || alert.sender == deviceId || seen.any { it.id == alert.id } || alert.at <= replayFloor) return false
        val ordered = (listOf(SeenAlert(alert.id, alert.at)) + seen).sortedByDescending { it.at }
        // Never forget an accepted ID while its timestamp can still be admitted.
        // At capacity, delayed messages at/below this durable floor are discarded.
        val nextFloor = maxOf(replayFloor, ordered.drop(2000).maxOfOrNull { it.at } ?: 0)
        val nextSeen = ordered.take(2000)
        val next = mutable.value.copy(inbox = (listOf(alert) + mutable.value.inbox).sortedByDescending { it.at }.take(200))
        save(next, nextSeen, published, nextFloor)
        seen = nextSeen
        replayFloor = nextFloor
        mutable.update { it.copy(inbox = next.inbox) }
        return true
    }
    @Synchronized private fun save(next: PeerState = mutable.value, nextSeen: List<SeenAlert> = seen, nextPublished: List<String> = published, nextFloor: Long = replayFloor) {
        val obj = JsonObject().apply {
            addProperty("device", deviceId); addProperty("pair", next.pairing?.code())
            add("inbox", JsonArray().apply { next.inbox.forEach { add(it.json()) } })
            add("published", JsonArray().apply { nextPublished.forEach(::add) })
            addProperty("replay_floor", nextFloor)
            add("seen", JsonArray().apply { nextSeen.forEach { entry ->
                add(JsonObject().apply { addProperty("id", entry.id); addProperty("at", entry.at) })
            } })
        }
        val stream = file.startWrite()
        try { stream.write(obj.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
