package app.beyoureyes.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelArtifactStoreInstrumentedTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.filesDir, "artifact-store-instrumented-test").also {
            it.deleteRecursively()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun appPrivateFilesystemAtomicallyStagesLeasesAndRollsBackCompletePackages() {
        val firstStore = ModelArtifactStore(root)
        val secondStore = ModelArtifactStore(root)
        val first = fixture(
            "object_detector",
            "1.0.0",
            linkedMapOf("vision" to "first-vision", "text" to "first-text"),
        )
        val second = fixture(
            "object_detector",
            "1.1.0",
            linkedMapOf("vision" to "second-vision", "text" to "second-text"),
        )
        assertTrue(stage(firstStore, first) is ModelPackageStageResult.Staged)
        assertTrue(stage(secondStore, second) is ModelPackageStageResult.Staged)
        assertTrue(
            firstStore.activatePackage(SLOT, first.descriptor.pointer(), NOW) is
                ModelPackageActivationResult.Activated,
        )

        val lease = (
            firstStore.acquireRuntimeLease(
                first.descriptor.pointer(),
                NOW,
            ) as ModelPackageRuntimeLeaseResult.Acquired
            ).lease
        assertArrayEquals(first.manifest, lease.canonicalManifest.copyBytes())
        assertEquals(setOf("text", "vision"), lease.artifactFilesByRole.keys)
        assertEquals("first-vision", lease.artifactFilesByRole.getValue("vision").readText())
        val blocked = secondStore.activatePackage(SLOT, second.descriptor.pointer(), NOW)
            as ModelPackageActivationResult.Rejected
        assertEquals(setOf(ModelPackageRejection.MONITORING_ACTIVE), blocked.reasons)
        lease.close()

        assertTrue(
            secondStore.activatePackage(SLOT, second.descriptor.pointer(), NOW) is
                ModelPackageActivationResult.Activated,
        )
        assertEquals(first.descriptor.pointer(), secondStore.activationState(SLOT).previous)
        assertTrue(secondStore.rollback(SLOT, NOW + 1) is ModelPackageActivationResult.Activated)
        assertEquals(first.descriptor.pointer(), firstStore.activationState(SLOT).current)

        val rollbackLease = (
            secondStore.acquireRuntimeLease(
                first.descriptor.pointer(),
                NOW + 1,
            ) as ModelPackageRuntimeLeaseResult.Acquired
            ).lease
        assertEquals("first-text", rollbackLease.artifactFilesByRole.getValue("text").readText())
        rollbackLease.close()
    }

    @Test
    fun appPrivateCrashResidueAndMissingRoleFailClosed() {
        root.resolve("staging/interrupted/artifacts").mkdirs()
        root.resolve("staging/interrupted/artifacts/partial.bin").writeText("partial")
        root.resolve("state").mkdirs()
        root.resolve("state/activation-state.tsv.interrupted.tmp").writeText("partial")
        val store = ModelArtifactStore(root)
        assertTrue(root.resolve("staging").listFiles().orEmpty().isEmpty())
        assertFalse(root.resolve("state/activation-state.tsv.interrupted.tmp").exists())

        val fixture = fixture(
            "object_detector",
            "1.0.0",
            linkedMapOf("vision" to "vision", "text" to "text"),
        )
        val rejected = store.stagePackage(
            descriptor = fixture.descriptor,
            canonicalManifestBytes = fixture.manifest,
            artifactSourcesByRole = mapOf("vision" to bytes("vision")),
            gateReport = fixture.report,
            nowEpochMillis = NOW,
        ) as ModelPackageStageResult.Rejected
        assertEquals(setOf(ModelPackageRejection.MISSING_ARTIFACT_ROLE), rejected.reasons)
        assertFalse(
            root.resolve("packages/object_detector/1.0.0").exists(),
        )
    }

    private fun stage(
        store: ModelArtifactStore,
        fixture: PackageFixture,
    ): ModelPackageStageResult = store.stagePackage(
        descriptor = fixture.descriptor,
        canonicalManifestBytes = fixture.manifest,
        artifactSourcesByRole = fixture.artifacts.mapValues { bytes(it.value) },
        gateReport = fixture.report,
        nowEpochMillis = NOW,
    )

    private fun fixture(
        packageId: String,
        version: String,
        artifactContents: LinkedHashMap<String, String>,
    ): PackageFixture {
        val manifest = "{\"package_id\":\"$packageId\",\"version\":\"$version\"}"
            .toByteArray()
        val descriptor = ModelPackageDescriptor(
            identity = ModelPackageIdentity(packageId, version),
            canonicalManifestSha256 = sha256(manifest),
            canonicalManifestSizeBytes = manifest.size.toLong(),
            artifacts = artifactContents.map { (role, content) ->
                ModelPackageArtifactDescriptor(
                    role = role,
                    relativePath = "$role.bin",
                    sha256 = sha256(content.toByteArray()),
                    sizeBytes = content.toByteArray().size.toLong(),
                )
            },
        )
        return PackageFixture(
            descriptor = descriptor,
            manifest = manifest,
            artifacts = artifactContents,
            report = ModelPackageGateReport(
                reportId = "instrumented-$packageId-$version",
                evidenceRef = "evidence/gates/VM-010/android-package-store.json",
                packageDescriptorSha256 = descriptor.descriptorSha256,
                catalogEntryActive = true,
                catalogSignatureValid = true,
                catalogManifestHashValid = true,
                manifestSignatureValid = true,
                licenseGatePassed = true,
                metadataCompatible = true,
                deviceCompatible = true,
                selfTestPassed = true,
                validatedAtEpochMillis = 1,
                catalogFreshUntilEpochMillis = 1_000,
                runValidUntilEpochMillis = 1_000,
            ),
        )
    }

    private fun bytes(value: String): InputStream = ByteArrayInputStream(value.toByteArray())

    private fun sha256(value: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private data class PackageFixture(
        val descriptor: ModelPackageDescriptor,
        val manifest: ByteArray,
        val artifacts: LinkedHashMap<String, String>,
        val report: ModelPackageGateReport,
    )

    private companion object {
        val SLOT = ModelPackageSlot("visual_target_text")
        const val NOW = 10L
    }
}
