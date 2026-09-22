package app.beyoureyes.monitor

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.monitor.feature.peers.PeerAlert
import app.beyoureyes.monitor.feature.peers.PeerAlertStore
import app.beyoureyes.monitor.feature.peers.PeerPairing
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PeerAlertStoreInstrumentedTest {
    @Test
    fun receivedAndSentMessagesSurviveRestartWithoutDuplicateNotifications() = withStore { context ->
        val pair = PeerPairing.create("https://ntfy.sh")
        val alert = alert(System.currentTimeMillis())
        val first = open(context)
        first.pair(pair)
        assertTrue(first.accept(pair, alert))
        assertFalse(first.accept(pair, alert))
        first.markSent(pair, alert.id)

        val restored = open(context)
        assertEquals(first.deviceId, restored.deviceId)
        assertEquals(listOf(alert), restored.state.value.inbox)
        assertFalse(restored.accept(pair, alert))
        assertTrue(restored.hasSent(alert.id))
        restored.pair(null)
        assertFalse(restored.accept(pair, alert))
        assertTrue(open(context).state.value.inbox.isEmpty())
    }

    @Test
    fun savedReceiptFloorRejectsDelayedMessagesAndRetainsNewerOnes() = withStore { context ->
        val pair = PeerPairing.create("https://ntfy.sh")
        val floor = System.currentTimeMillis() - 1000
        val stored = JsonObject().apply {
            addProperty("device", UUID.randomUUID().toString())
            addProperty("pair", pair.code())
            addProperty("replay_floor", floor)
            add("seen", JsonArray())
            add("inbox", JsonArray())
            add("published", JsonArray())
        }
        File(context.filesDir, "paired-alerts-v1.json").writeText(stored.toString())
        val store = open(context)
        assertFalse(store.accept(pair, alert(floor - 1)))
        assertFalse(store.accept(pair, alert(floor)))
        val current = alert(floor + 1)
        assertTrue(store.accept(pair, current))
        val restarted = open(context)
        assertFalse(restarted.accept(pair, alert(floor)))
        assertFalse(restarted.accept(pair, current))
    }

    private fun alert(at: Long) = PeerAlert(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), at, "reading", "Meter", "12.4",
    )

    private suspend fun open(context: ContextWrapper) = PeerAlertStore(context).also { it.awaitReady() }

    private fun withStore(block: suspend (ContextWrapper) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "paired-store-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
        }
        try { block(isolated) } finally { directory.deleteRecursively() }
    }
}
