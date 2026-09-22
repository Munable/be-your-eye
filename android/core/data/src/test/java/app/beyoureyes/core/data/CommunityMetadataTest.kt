package app.beyoureyes.core.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CommunityMetadataTest {
    private val root = File(System.getProperty("beYourEyes.repoRoot"),
        "android/app/src/community/assets/community-models")
    private fun current() = root.resolve("catalog.json").readBytes()
    private fun issuedAt(bytes: ByteArray): Long = java.time.Instant.parse(
        com.google.gson.JsonParser.parseString(bytes.decodeToString()).asJsonObject["issued_at"].asString
    ).toEpochMilli()

    @Test fun `Community release remains installable years later without a maintainer service`() {
        val bytes = current()
        val now = issuedAt(bytes) + 3650L * 24 * 60 * 60 * 1000
        val catalog = SignedMetadataCodec.decodeAndVerifyCatalog(bytes, nowEpochMillis = now)
        assertEquals(Long.MAX_VALUE, catalog.validUntilEpochMillis)
        catalog.catalog.packages.forEach { entry ->
            val manifest = SignedMetadataCodec.decodeAndVerifyManifest(
                root.resolve("manifests/${entry.packageId}.json").readBytes(), catalog,
                entry.packageId, nowEpochMillis = now)
            assertEquals(Long.MAX_VALUE, manifest.licenseRunValidUntilEpochMillis)
            assertEquals(Long.MAX_VALUE, manifest.catalogFreshUntilEpochMillis)
            assertTrue(manifest.manifest.license.redistributionAllowed)
            assertEquals(entry.manifestSha256, manifest.documentSha256)
            assertTrue(manifest.manifest.artifacts.all { it.url.startsWith("https://github.com/Munable/be-your-eye/releases/download/models-v1/") })
        }
    }

    @Test fun `offline allowance does not weaken Community signatures`() {
        val bytes = current()
        val changed = bytes.decodeToString().replace("2026.09.22.1", "2026.09.22.2").encodeToByteArray()
        assertFalse(bytes.contentEquals(changed))
        assertThrows(MetadataVerificationException::class.java) {
            SignedMetadataCodec.decodeAndVerifyCatalog(changed,
                nowEpochMillis = issuedAt(bytes) + 30L * 24 * 60 * 60 * 1000)
        }
    }
}
