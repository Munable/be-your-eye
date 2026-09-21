package app.beyoureyes.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class SignedMetadataCryptoInstrumentedTest {
    @Test
    fun rfc8785PayloadAndEd25519VerifyOnAndroidRuntime() {
        val pair = Ed25519Sign.KeyPair.newKeyPair()
        val root = JsonObject().apply {
            addProperty("z", 1)
            addProperty("a", "device")
        }
        val payload = StrictSignedJson.canonicalize(root)
        root.add(
            "signature",
            JsonObject().apply {
                addProperty("canonicalization", "RFC8785")
                addProperty("algorithm", "Ed25519")
                addProperty("signing_key_id", "instrumentation-current")
                addProperty(
                    "value",
                    Base64.getEncoder().encodeToString(Ed25519Sign(pair.privateKey).sign(payload)),
                )
            },
        )
        val parsed = StrictSignedJson.parseAndVerify(
            root.toString().toByteArray(),
            4_096,
            PinnedEd25519KeyRegistry(
                PinnedEd25519PublicKey(
                    "instrumentation-current",
                    Base64.getEncoder().encodeToString(pair.publicKey),
                ),
                null,
            ),
        )

        assertEquals("{\"a\":\"device\",\"z\":1}", parsed.canonicalSignedPayload.toString(Charsets.UTF_8))
    }
}
