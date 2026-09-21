package app.beyoureyes.monitor

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.monitor.feature.assistant.CapturedVoiceRecording
import app.beyoureyes.monitor.feature.assistant.SupabaseVoiceTranscriptionGateway
import app.beyoureyes.monitor.feature.assistant.VoiceAccessTokenProvider
import app.beyoureyes.monitor.feature.assistant.VoiceTranscriptionResult
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live probes bound to the current phone network. No account, IP or response body logs. */
@RunWith(AndroidJUnit4::class)
class LiveConnectivityInstrumentedTest {
    @Test
    fun authenticatedPhoneTranscribesBoundedTestAudio() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveVoiceConnectivity") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokenFile = File(context.cacheDir, "network-test-token.json")
        val audioFile = File(context.cacheDir, "network-test-voice.m4a")
        val report = JSONObject()
        val started = System.nanoTime()
        try {
            val input = JSONObject(tokenFile.readText())
            val gateway = SupabaseVoiceTranscriptionGateway(
                endpointUrl = "${BuildConfig.SUPABASE_URL}/functions/v1/voice-transcription",
                publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                tokenProvider = VoiceAccessTokenProvider { input.getString("access_token") },
            )
            val recording = CapturedVoiceRecording(audioFile, input.getLong("duration_ms"))
            report.put("audio_bytes", audioFile.length()).put("audio_duration_ms", recording.durationMillis)
            val result = runBlocking { gateway.transcribe(recording, "en-US") }
            report.put("result_type", result.javaClass.simpleName)
            val matches = result is VoiceTranscriptionResult.Completed &&
                Regex("(?i)\\b(thirty|30)\\b").containsMatchIn(result.text)
            report.put("expected_threshold_recognized", matches)
            assertTrue("phone voice gateway must return the expected test threshold", matches)
        } finally {
            tokenFile.delete()
            audioFile.delete()
            report.put("duration_ms", (System.nanoTime() - started) / 1_000_000)
            File(context.filesDir, "live-voice-connectivity-result.json").writeText(report.toString(2))
        }
    }

    @Test
    fun directNetworkReachesPackagedServicesAndSignedModelBytes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveConnectivity") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(manager.activeNetwork)
        val capabilities = requireNotNull(manager.getNetworkCapabilities(network))
        val checks = JSONArray()
        val report = JSONObject()
            .put("transport_wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            .put("transport_cellular", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            .put("transport_vpn", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            .put("system_http_proxy_configured", manager.defaultProxy != null)
            .put("proxy_mode", "explicit_NO_PROXY_bound_to_active_network")
            .put("checks", checks)
        fun probe(label: String, address: String, post: Boolean = false, limit: Int = 262_144): ByteArray? {
            val started = System.nanoTime()
            val result = JSONObject().put("service", label).put("host", URL(address).host)
            checks.put(result)
            var connection: HttpURLConnection? = null
            return try {
                connection = network.openConnection(URL(address), Proxy.NO_PROXY) as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                if (address.startsWith(BuildConfig.SUPABASE_URL)) {
                    connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                }
                if (post) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write("{}".toByteArray()) }
                }
                val status = connection.responseCode
                result.put("http_status", status)
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytesBounded(limit) }
                result.put("bytes_read", bytes?.size ?: 0)
                bytes.takeIf { status in 200..299 }
            } catch (error: Exception) {
                result.put("error_type", error.javaClass.simpleName)
                null
            } finally {
                result.put("duration_ms", (System.nanoTime() - started) / 1_000_000)
                connection?.disconnect()
            }
        }
        try {
            probe("egress_region", "https://www.cloudflare.com/cdn-cgi/trace")?.decodeToString()?.lineSequence()
                ?.firstOrNull { it.startsWith("loc=") }?.substringAfter('=')?.let { report.put("egress_country", it) }
            probe("auth_health", "${BuildConfig.SUPABASE_URL}/auth/v1/health")
            probe("assistant_auth_gate", "${BuildConfig.SUPABASE_URL}/functions/v1/monitor-assistant", post = true)
            probe("voice_auth_gate", "${BuildConfig.SUPABASE_URL}/functions/v1/voice-transcription", post = true)
            probe("entitlement_auth_gate", "${BuildConfig.SUPABASE_URL}/functions/v1/play-entitlement", post = true)
            probe("privacy", BuildConfig.PRIVACY_POLICY_URL)
            probe("terms", URL(URL(BuildConfig.PRIVACY_POLICY_URL), "/terms").toString())
            val catalogBytes = probe("signed_catalog", BuildConfig.MODEL_CATALOG_URL)
            if (catalogBytes != null) {
                val catalog = SignedMetadataCodec.decodeAndVerifyCatalog(catalogBytes, nowEpochMillis = System.currentTimeMillis())
                val entry = catalog.catalog.packages.first { it.active && it.packageId == "efficientdet_lite2_object_v1" }
                val manifestBytes = probe("signed_object_manifest", entry.manifestUrl)
                if (manifestBytes != null) {
                    val verified = SignedMetadataCodec.decodeAndVerifyManifest(
                        manifestBytes, catalog, entry.packageId, nowEpochMillis = System.currentTimeMillis(),
                    )
                    val artifact = verified.manifest.artifacts.single()
                    val bytes = probe("object_model", artifact.url, limit = artifact.sizeBytes.toInt() + 1)
                    report.put("model_download_sha256_matches", bytes != null &&
                        bytes.size.toLong() == artifact.sizeBytes && MessageDigest.getInstance("SHA-256")
                            .digest(bytes).joinToString("") { "%02x".format(it) } == artifact.sha256)
                }
            }
            // Google reachability is separate from local monitoring and the app's own API.
            probe("google_connectivity", "https://www.google.com/generate_204")
        } finally {
            File(context.filesDir, "live-connectivity-result.json").writeText(report.toString(2))
        }
        assertTrue("live probes saved; model reachability failed", report.optBoolean("model_download_sha256_matches"))
        assertTrue("active network must not be a VPN", !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
