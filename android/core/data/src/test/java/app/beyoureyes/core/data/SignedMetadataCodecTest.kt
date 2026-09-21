package app.beyoureyes.core.data

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedMetadataCodecTest {
    @Test
    fun `intent routing accepts exact keys and terminal namespace wildcard only`() {
        val exact = operationalCapability(setOf("object.common.apple"))
        val namespace = operationalCapability(setOf("object.common.*"))

        assertTrue(exact.matchesIntentKey("object.common.apple"))
        assertFalse(exact.matchesIntentKey("object.common.banana"))
        assertTrue(namespace.matchesIntentKey("object.common.apple"))
        assertFalse(namespace.matchesIntentKey("object.common"))
        assertFalse(namespace.matchesIntentKey("object.common.*"))
        assertFalse(namespace.matchesIntentKey("object.*.apple"))
        assertEquals(setOf("apple"), namespace.targetIds)
    }

    @Test
    fun `strict Android decoder lists only the three executable launch profiles`() {
        val (bytes, registry) = signedCurrentCatalog()

        val catalog = SignedMetadataCodec.decodeAndVerifyCatalog(
            documentBytes = bytes,
            keyRegistry = registry,
            nowEpochMillis = catalogIssuedAtEpochMillis(bytes),
        ).catalog

        assertEquals(3, catalog.operationalCapabilities.size)
        assertEquals(
            setOf(
                "similarity_mediapipe_mobilenet_v3_large_v1",
                "efficientdet_lite2_object_v1",
                "numeric_reader_ppocrv6_medium_v1",
            ),
            catalog.packages.mapTo(linkedSetOf()) { it.packageId },
        )
        assertTrue(catalog.operationalCapabilities.all { it.packageIds.isNotEmpty() })
        assertEquals(
            setOf(
                "reference_object_matching",
                "common_objects_tensorflow_efficientdet_lite2",
                "numeric_display_reading",
            ),
            catalog.operationalCapabilities.mapTo(linkedSetOf()) { it.capabilityKey },
        )
    }

    @Test
    fun `strict Android decoder rejects non executable placeholder rows`() {
        val (bytes, registry) = signedCurrentCatalog { root ->
            root.getAsJsonArray("operational_capabilities")[0]
                .asJsonObject
                .addProperty("status", "unsupported")
        }

        val failure = assertThrows(MetadataVerificationException::class.java) {
            SignedMetadataCodec.decodeAndVerifyCatalog(
                documentBytes = bytes,
                keyRegistry = registry,
                nowEpochMillis = catalogIssuedAtEpochMillis(bytes),
            )
        }

        assertEquals("unknown_enum", failure.code)
    }

    @Test
    fun `installed Community allowance never admits an expired connected catalog`() {
        val (bytes, registry) = signedCurrentCatalog()
        val failure = assertThrows(MetadataVerificationException::class.java) {
            SignedMetadataCodec.decodeAndVerifyCatalog(bytes, keyRegistry = registry,
                nowEpochMillis = catalogIssuedAtEpochMillis(bytes) + 30L * 24 * 60 * 60 * 1000,
                allowInstalledCommunity = true)
        }
        assertEquals("catalog_expired", failure.code)
    }

    private fun operationalCapability(intentPatterns: Set<String>) =
        CatalogOperationalCapability(
            capabilityKey = "common_objects_tensorflow_efficientdet_lite2",
            capabilityId = "visual_target",
            recipeId = "object_detection_general_v1",
            modelCard = CatalogModelCard(
                providerId = "tensorflow",
                providerName = "TensorFlow",
                modelName = "EfficientDet-Lite2",
                modelHomeUrl = "https://www.tensorflow.org/lite/examples/object_detection/overview",
                modelKind = "general",
            ),
            intentPatterns = intentPatterns,
            targetIds = setOf("apple"),
            professionalDomain = "general",
            displayName = "常见物体检测",
            status = "internal-evaluation",
            applicableScenarios = setOf("fixed camera"),
            inapplicableScenarios = setOf("unsupported targets"),
            inputRequirements = setOf("stable view"),
            deviceProfileIds = setOf("android_arm64_8gb_launch_v1"),
            packageIds = setOf("efficientdet_lite2_object_v1"),
            humanReview = CatalogHumanReview(
                reviewedAt = "2026-08-21T00:00:00Z",
                licenseConclusion = "approved-for-internal-evaluation",
                evidenceRefs = setOf("model-tools/v3/reviews/example.md"),
            ),
        )

    private fun signedCurrentCatalog(
        mutate: (JsonObject) -> Unit = {},
    ): Pair<ByteArray, PinnedEd25519KeyRegistry> {
        val root = JsonParser.parseString(
            File(
                System.getProperty("beYourEyes.repoRoot"),
                "model-tools/v3/releases/current-internal/templates/catalog.template.json",
            ).readText(),
        ).asJsonObject
        mutate(root)
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val signature = Ed25519Sign(keyPair.privateKey).sign(StrictSignedJson.canonicalize(root))
        root.add(
            "signature",
            JsonObject().apply {
                addProperty("canonicalization", "RFC8785")
                addProperty("algorithm", "Ed25519")
                addProperty("signing_key_id", "catalog-test-key")
                addProperty("value", Base64.getEncoder().encodeToString(signature))
            },
        )
        val registry = PinnedEd25519KeyRegistry(
            current = PinnedEd25519PublicKey(
                keyId = "catalog-test-key",
                rawPublicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.publicKey),
            ),
            next = null,
        )
        return root.toString().encodeToByteArray() to registry
    }

    private fun catalogIssuedAtEpochMillis(bytes: ByteArray): Long =
        Instant.parse(
            JsonParser.parseString(bytes.decodeToString())
                .asJsonObject
                .get("issued_at")
                .asString,
        ).toEpochMilli()
}
