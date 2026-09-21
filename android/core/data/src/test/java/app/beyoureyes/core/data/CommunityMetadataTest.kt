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

    @Test fun `old Community catalog cannot admit downloads but still authenticates installed identities`() {
        val bytes = current()
        val now = issuedAt(bytes) + 30L * 24 * 60 * 60 * 1000
        assertThrows(MetadataVerificationException::class.java) {
            SignedMetadataCodec.decodeAndVerifyCatalog(bytes, nowEpochMillis = now)
        }
        val installed = SignedMetadataCodec.decodeAndVerifyCatalog(bytes, nowEpochMillis = now,
            allowInstalledCommunity = true)
        assertTrue(installed.installedCommunityOnly)
        assertTrue(installed.validUntilEpochMillis < now)
        installed.catalog.packages.forEach { entry ->
            val manifest = SignedMetadataCodec.decodeAndVerifyManifest(
                root.resolve("manifests/${entry.packageId}.json").readBytes(), installed,
                entry.packageId, nowEpochMillis = now)
            assertEquals(Long.MAX_VALUE, manifest.licenseRunValidUntilEpochMillis)
            assertTrue(manifest.catalogFreshUntilEpochMillis < now)
            assertTrue(manifest.manifest.license.redistributionAllowed)
            assertEquals(entry.manifestSha256, manifest.documentSha256)
        }
    }

    @Test fun `expired Community metadata cannot fetch or stage an uninstalled package`() {
        val bytes = current()
        val now = issuedAt(bytes) + 30L * 24 * 60 * 60 * 1000
        val catalog = SignedMetadataCodec.decodeAndVerifyCatalog(bytes,
            nowEpochMillis = now, allowInstalledCommunity = true)
        val entry = catalog.catalog.packages.first()
        val manifest = SignedMetadataCodec.decodeAndVerifyManifest(
            root.resolve("manifests/${entry.packageId}.json").readBytes(), catalog,
            entry.packageId, nowEpochMillis = now)
        val descriptor = manifest.toStoreDescriptor()
        val files = kotlin.io.path.createTempDirectory("community-expired-test").toFile()
        try {
            val delivery = ModelPackageDeliveryCoordinator(ModelPackageStores.open(files),
                files.resolve("downloads"), FixedHttpsTransport { error("expired metadata must never download") })
            val result = delivery.stageVerifiedPackage(manifest, ModelPackageGateReport(
                reportId = "community-test", evidenceRef = "signed-community-test",
                packageDescriptorSha256 = descriptor.descriptorSha256,
                catalogEntryActive = true, catalogSignatureValid = true,
                catalogManifestHashValid = true, manifestSignatureValid = true,
                licenseGatePassed = true, metadataCompatible = true, deviceCompatible = true,
                selfTestPassed = false, validatedAtEpochMillis = now,
                catalogFreshUntilEpochMillis = manifest.catalogFreshUntilEpochMillis,
                runValidUntilEpochMillis = manifest.licenseRunValidUntilEpochMillis), now,
                artifactSelfTest = ModelPackageArtifactSelfTest { _, _ -> error("no new self-test") })
            assertEquals(ModelPackageDeliveryResult.Rejected(ModelDeliveryFailure.METADATA_GATE_INVALID), result)
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun `offline allowance does not weaken Community signatures`() {
        val bytes = current()
        val changed = bytes.decodeToString().replace("2026.09.21.1", "2026.09.21.2").encodeToByteArray()
        assertFalse(bytes.contentEquals(changed))
        assertThrows(MetadataVerificationException::class.java) {
            SignedMetadataCodec.decodeAndVerifyCatalog(changed,
                nowEpochMillis = issuedAt(bytes) + 30L * 24 * 60 * 60 * 1000,
                allowInstalledCommunity = true)
        }
    }
}
