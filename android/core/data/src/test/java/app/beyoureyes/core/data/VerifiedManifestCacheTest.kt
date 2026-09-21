package app.beyoureyes.core.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedManifestCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cache reuses only bytes bound to the exact Catalog URL and SHA`() {
        val root = temporaryFolder.newFolder("manifest-cache")
        val fixture = verifiedManifest()
        val cache = VerifiedManifestCache(root, fixture.catalogEntry)

        assertTrue(cache.write(fixture))
        assertArrayEquals(fixture.copyDocumentBytes(), cache.readBytesOrNull())

        assertNull(
            VerifiedManifestCache(
                root,
                fixture.catalogEntry.copy(manifestSha256 = "f".repeat(64)),
            ).readBytesOrNull(),
        )
        assertNull(
            VerifiedManifestCache(
                root,
                fixture.catalogEntry.copy(manifestUrl = "https://models.example.test/other.json"),
            ).readBytesOrNull(),
        )
    }

    @Test
    fun `tampered cached bytes are rejected before signature verification`() {
        val root = temporaryFolder.newFolder("tampered-manifest-cache")
        val fixture = verifiedManifest()
        val cache = VerifiedManifestCache(root, fixture.catalogEntry)
        assertTrue(cache.write(fixture))

        root.resolve("signed-manifest-cache-v1").listFiles()!!.single().writeText("tampered")

        assertNull(cache.readBytesOrNull())
    }

    @Test
    fun `write rejects a verified document from a different Catalog entry`() {
        val root = temporaryFolder.newFolder("mismatched-manifest-cache")
        val fixture = verifiedManifest()
        val otherEntry = fixture.catalogEntry.copy(
            manifestUrl = "https://models.example.test/other.json",
        )

        assertFalse(VerifiedManifestCache(root, otherEntry).write(fixture))
    }

    private fun verifiedManifest(): VerifiedManifestDocument {
        val root = JsonParser.parseString(
            File(
                System.getProperty("beYourEyes.repoRoot"),
                "model-tools/v3/releases/current-internal/templates/object.manifest.template.json",
            ).readText(),
        ).asJsonObject
        root.add(
            "signature",
            JsonObject().apply {
                addProperty("canonicalization", "RFC8785")
                addProperty("algorithm", "Ed25519")
                addProperty("signing_key_id", "manifest-cache-test-key")
                addProperty(
                    "value",
                    Base64.getEncoder().encodeToString(ByteArray(64) { 1 }),
                )
            },
        )
        val bytes = root.toString().encodeToByteArray()
        val manifest = SignedMetadataCodec.decodeStoredManifestDocument(bytes)
        val documentSha256 = sha256Hex(bytes)
        val entry = CatalogPackageEntry(
            packageId = manifest.packageId,
            packageVersion = manifest.packageVersion,
            manifestUrl = "https://models.example.test/object.json",
            manifestSha256 = documentSha256,
            active = true,
        )
        return VerifiedManifestDocument(
            manifest = manifest,
            catalogEntry = entry,
            documentSha256 = documentSha256,
            signedPayloadSha256 = "a".repeat(64),
            catalogFreshUntilEpochMillis = Long.MAX_VALUE,
            licenseRunValidUntilEpochMillis = Long.MAX_VALUE,
            documentBytes = bytes,
        )
    }
}
