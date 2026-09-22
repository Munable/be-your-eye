package app.beyoureyes.monitor

import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.beyoureyes.monitor.feature.peers.PeerConnection
import app.beyoureyes.monitor.feature.peers.PeerSendState
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in, two owned test installations, real public relay and the real pairing UI. */
class LivePeerPairingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun pairAndDeliverBetweenTwoInstallations() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runLivePairing") == "true")
        val role = requireNotNull(args.getString("pairingRole"))
        require(role in setOf("create", "receive", "send"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.appContainer.peerAlerts
        compose.waitUntil(120_000) { store.state.value.ready }
        compose.onNodeWithText(context.getString(R.string.community_tab)).performClick()
        compose.onNodeWithText(context.getString(R.string.peer_title)).performClick()
        if (role == "create") {
            if (args.getString("replaceTestPairing") == "true" && store.state.value.pairing != null) {
                compose.onNodeWithText(context.getString(R.string.peer_unpair)).performScrollTo().performClick()
                compose.waitUntil(120_000) { store.state.value.pairing == null }
            }
            check(store.state.value.pairing == null) { "Use an unpaired test installation" }
            compose.onNodeWithTag("peer_create").performClick()
            compose.onNodeWithTag("peer_confirm").performClick()
            waitForConfirmationToSave(context.getString(R.string.peer_done))
            compose.onNodeWithText(context.getString(R.string.peer_done)).performClick()
            assertNotNull(store.state.value.pairing)
            // A private transfer fixture, never printed, logged or copied into public evidence.
            File(context.filesDir, "live-pairing-code.private.txt").writeText(checkNotNull(store.state.value.pairing).code())
        } else if (role == "receive") {
            check(store.state.value.pairing == null) { "Use an unpaired test installation" }
            val code = File(context.filesDir, "incoming-pairing-code.private.txt").readText().trim()
            compose.onNodeWithTag("peer_code").performScrollTo().performTextInput(code)
            compose.onNodeWithTag("peer_join").performScrollTo().performClick()
            compose.onNodeWithTag("peer_confirm").performClick()
            waitForConfirmationToSave(context.getString(R.string.peer_done))
            compose.onNodeWithText(context.getString(R.string.peer_done)).performClick()
            compose.onNodeWithTag("peer_receive").performScrollTo().performClick()
            compose.waitUntil(30_000) { store.state.value.connection == PeerConnection.LISTENING }
            compose.waitUntil(180_000) { store.state.value.inbox.any { it.kind == "test" } }
            val alert = store.state.value.inbox.first { it.kind == "test" }
            assertNotEquals(store.deviceId, alert.sender)
            compose.waitUntil(10_000) {
                context.getSystemService(NotificationManager::class.java).activeNotifications
                    .any { it.tag == "peer-${alert.id}" }
            }
            compose.onNodeWithTag("peer_received").performScrollTo()
            File(context.filesDir, "live-pairing-received.json").writeText(alert.json().toString())
        } else {
            assertNotNull(store.state.value.pairing)
            compose.onNodeWithTag("peer_test").performScrollTo().performClick()
            compose.waitUntil(60_000) { store.state.value.send in setOf(PeerSendState.SENT, PeerSendState.FAILED) }
            assertEquals(PeerSendState.SENT, store.state.value.send)
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, "live-pairing-$role.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun waitForConfirmationToSave(done: String) {
        compose.waitUntil(120_000) { compose.onAllNodesWithText(done).fetchSemanticsNodes().isNotEmpty() }
    }
}
