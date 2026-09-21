package app.beyoureyes.core.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArtifactStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesWholePackagesAndAtomicallyLeasesActivatesAndRollsBack() {
        val root = temporaryFolder.newFolder("models")
        val firstStore = ModelArtifactStore(root)
        val secondStore = ModelArtifactStore(root)
        val first = fixture(
            packageId = "object_detector",
            version = "1.0.0",
            artifactContents = linkedMapOf(
                "vision_encoder" to "vision-v1",
                "text_encoder" to "text-v1",
            ),
        )
        val second = fixture(
            packageId = "object_detector",
            version = "1.1.0",
            artifactContents = linkedMapOf(
                "vision_encoder" to "vision-v2",
                "text_encoder" to "text-v2",
            ),
        )

        assertStaged(stage(firstStore, first))
        assertStaged(stage(secondStore, second))
        assertActivated(firstStore.activatePackage(SLOT, first.descriptor.pointer(), NOW))

        val lease = (
            firstStore.acquireRuntimeLease(
                first.descriptor.pointer(),
                NOW,
            ) as ModelPackageRuntimeLeaseResult.Acquired
            ).lease
        assertEquals(first.descriptor, lease.descriptor)
        assertEquals(first.report, lease.gateReport)
        assertEquals(setOf("text_encoder", "vision_encoder"), lease.artifactFilesByRole.keys)
        assertEquals("vision-v1", lease.artifactFilesByRole.getValue("vision_encoder").readText())
        assertArrayEquals(first.manifest, lease.canonicalManifest.copyBytes())
        lease.canonicalManifest.copyBytes()[0] = '!'.code.toByte()
        assertArrayEquals(first.manifest, lease.canonicalManifest.copyBytes())
        try {
            @Suppress("UNCHECKED_CAST")
            (lease.artifactFilesByRole as MutableMap<String, File>)["unexpected"] = File("x")
            fail("artifactFilesByRole must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }

        assertEquals(
            setOf(ModelPackageRejection.MONITORING_ACTIVE),
            (
                secondStore.activatePackage(SLOT, second.descriptor.pointer(), NOW)
                    as ModelPackageActivationResult.Rejected
                ).reasons,
        )
        assertEquals(
            setOf(ModelPackageRejection.MONITORING_ACTIVE),
            (secondStore.rollback(SLOT, NOW) as ModelPackageActivationResult.Rejected).reasons,
        )
        lease.close()
        lease.close()

        assertActivated(secondStore.activatePackage(SLOT, second.descriptor.pointer(), NOW))
        assertEquals(first.descriptor.pointer(), secondStore.activationState(SLOT).previous)
        assertActivated(secondStore.rollback(SLOT, NOW + 1))
        assertEquals(first.descriptor.pointer(), secondStore.activationState(SLOT).current)
        assertEquals(second.descriptor.pointer(), secondStore.activationState(SLOT).previous)

        val rolledBackLease = (
            firstStore.acquireRuntimeLease(
                first.descriptor.pointer(),
                NOW + 1,
            ) as ModelPackageRuntimeLeaseResult.Acquired
            ).lease
        assertEquals("vision-v1", rolledBackLease.artifactFilesByRole.getValue("vision_encoder").readText())
        assertEquals("text-v1", rolledBackLease.artifactFilesByRole.getValue("text_encoder").readText())
        rolledBackLease.close()
    }

    @Test
    fun independentSlotsKeepMultipleFamiliesAndRollbackHistoriesIsolated() {
        val store = ModelArtifactStore(temporaryFolder.newFolder("slot-models"))
        val promptV1 = fixture("prompt", "1.0.0", linkedMapOf("primary" to "prompt-v1"))
        val promptV2 = fixture("prompt", "2.0.0", linkedMapOf("primary" to "prompt-v2"))
        val reader = fixture("reader", "1.0.0", linkedMapOf("primary" to "reader-v1"))
        listOf(promptV1, promptV2, reader).forEach { assertStaged(stage(store, it)) }

        assertActivated(store.activatePackage(SLOT, promptV1.descriptor.pointer(), NOW))
        assertActivated(store.activatePackage(READING_SLOT, reader.descriptor.pointer(), NOW))
        assertActivated(store.activatePackage(SLOT, promptV2.descriptor.pointer(), NOW))

        assertEquals(promptV2.descriptor.pointer(), store.activationState(SLOT).current)
        assertEquals(promptV1.descriptor.pointer(), store.activationState(SLOT).previous)
        assertEquals(reader.descriptor.pointer(), store.activationState(READING_SLOT).current)
        assertNull(store.activationState(READING_SLOT).previous)
        assertEquals(
            setOf(ModelPackageRejection.NO_ROLLBACK_PACKAGE),
            (store.rollback(READING_SLOT, NOW) as ModelPackageActivationResult.Rejected).reasons,
        )

        val oldTaskLease = store.acquireRuntimeLease(
            promptV1.descriptor.pointer(),
            NOW,
        ) as ModelPackageRuntimeLeaseResult.Acquired
        oldTaskLease.lease.use { lease ->
            assertEquals("prompt-v1", lease.artifactFilesByRole.getValue("primary").readText())
        }
        val readerLease = store.acquireRuntimeLease(
            reader.descriptor.pointer(),
            NOW,
        ) as ModelPackageRuntimeLeaseResult.Acquired
        readerLease.lease.use { lease ->
            assertEquals("reader-v1", lease.artifactFilesByRole.getValue("primary").readText())
        }
    }

    @Test
    fun failedTaskBindingRestoresTheExactPriorActivationState() {
        val store = ModelArtifactStore(temporaryFolder.newFolder("binding-restore-models"))
        val first = fixture("detector", "1.0.0", linkedMapOf("primary" to "first"))
        val second = fixture("detector", "2.0.0", linkedMapOf("primary" to "second"))
        val third = fixture("detector", "3.0.0", linkedMapOf("primary" to "third"))
        listOf(first, second, third).forEach { assertStaged(stage(store, it)) }
        assertActivated(store.activatePackage(SLOT, first.descriptor.pointer(), NOW))
        assertActivated(store.activatePackage(SLOT, second.descriptor.pointer(), NOW))
        val before = store.activationState(SLOT)

        val activated = store.activatePackage(SLOT, third.descriptor.pointer(), NOW)
            as ModelPackageActivationResult.Activated
        assertTrue(activated.changed)
        val restored = store.restoreActivationStateAfterFailedBinding(
            slot = SLOT,
            expectedActivatedState = activated.state,
            stateToRestore = before,
        )

        assertActivated(restored)
        assertEquals(before, store.activationState(SLOT))
    }

    @Test
    fun failedTaskBindingRestoreCannotOverwriteANewerActivation() {
        val store = ModelArtifactStore(temporaryFolder.newFolder("binding-race-models"))
        val first = fixture("detector", "1.0.0", linkedMapOf("primary" to "first"))
        val second = fixture("detector", "2.0.0", linkedMapOf("primary" to "second"))
        val third = fixture("detector", "3.0.0", linkedMapOf("primary" to "third"))
        listOf(first, second, third).forEach { assertStaged(stage(store, it)) }
        assertActivated(store.activatePackage(SLOT, first.descriptor.pointer(), NOW))
        val before = store.activationState(SLOT)
        val secondActivation = store.activatePackage(SLOT, second.descriptor.pointer(), NOW)
            as ModelPackageActivationResult.Activated
        assertActivated(store.activatePackage(SLOT, third.descriptor.pointer(), NOW))

        val rejected = store.restoreActivationStateAfterFailedBinding(
            slot = SLOT,
            expectedActivatedState = secondActivation.state,
            stateToRestore = before,
        ) as ModelPackageActivationResult.Rejected

        assertEquals(setOf(ModelPackageRejection.STATE_CORRUPT), rejected.reasons)
        assertEquals(third.descriptor.pointer(), store.activationState(SLOT).current)
    }

    @Test
    fun runtimeLeaseRejectsAHashMismatchedPointerForAnInstalledIdentity() {
        val store = ModelArtifactStore(temporaryFolder.newFolder("pointer-mismatch-models"))
        val fixture = fixture("detector", "1.0.0", linkedMapOf("primary" to "model"))
        assertStaged(stage(store, fixture))
        val wrongPointer = ModelPackagePointer(
            fixture.descriptor.identity,
            "f".repeat(64),
        )

        val rejected = store.acquireRuntimeLease(wrongPointer, NOW)
            as ModelPackageRuntimeLeaseResult.Rejected
        assertEquals(setOf(ModelPackageRejection.POINTER_PACKAGE_MISMATCH), rejected.reasons)
    }

    @Test
    fun missingAndUnexpectedRolesRejectBeforeAnyPackageIsVisible() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val fixture = fixture(
            "object_detector",
            "1.0.0",
            linkedMapOf("vision" to "vision", "text" to "text"),
        )

        val missing = store.stagePackage(
            fixture.descriptor,
            fixture.manifest,
            mapOf("vision" to bytes("vision")),
            fixture.report,
            NOW,
        ) as ModelPackageStageResult.Rejected
        assertEquals(setOf(ModelPackageRejection.MISSING_ARTIFACT_ROLE), missing.reasons)

        val unexpected = store.stagePackage(
            fixture.descriptor,
            fixture.manifest,
            fixture.sources() + ("extra" to bytes("extra")),
            fixture.report,
            NOW,
        ) as ModelPackageStageResult.Rejected
        assertEquals(setOf(ModelPackageRejection.UNEXPECTED_ARTIFACT_ROLE), unexpected.reasons)
        assertFalse(packageDirectory(root, fixture.descriptor.identity).exists())
    }

    @Test
    fun badManifestAndArtifactBytesRejectAndCleanTheWholeStagingDirectory() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val correct = fixture("reader", "2.0.0", linkedMapOf("reader" to "correct"))

        val badManifest = store.stagePackage(
            correct.descriptor,
            "wrong".toByteArray(),
            correct.sources(),
            correct.report,
            NOW,
        ) as ModelPackageStageResult.Rejected
        assertTrue(ModelPackageRejection.MANIFEST_SIZE_MISMATCH in badManifest.reasons)
        assertTrue(ModelPackageRejection.MANIFEST_SHA256_MISMATCH in badManifest.reasons)

        val badHashDescriptor = descriptor(
            correct.descriptor.identity,
            correct.manifest,
            listOf(
                ModelPackageArtifactDescriptor(
                    role = "reader",
                    relativePath = "reader.bin",
                    sha256 = "0".repeat(64),
                    sizeBytes = "correct".length.toLong(),
                ),
            ),
        )
        val badHash = store.stagePackage(
            badHashDescriptor,
            correct.manifest,
            mapOf("reader" to bytes("correct")),
            report(badHashDescriptor),
            NOW,
        ) as ModelPackageStageResult.Rejected
        assertEquals(
            setOf(ModelPackageRejection.ARTIFACT_SOURCE_SHA256_MISMATCH),
            badHash.reasons,
        )

        val badSizeDescriptor = descriptor(
            ModelPackageIdentity("reader", "2.1.0"),
            correct.manifest,
            listOf(
                ModelPackageArtifactDescriptor(
                    role = "reader",
                    relativePath = "reader.bin",
                    sha256 = sha256("correct".toByteArray()),
                    sizeBytes = "correct".length.toLong() + 1,
                ),
            ),
        )
        val badSize = store.stagePackage(
            badSizeDescriptor,
            correct.manifest,
            mapOf("reader" to bytes("correct")),
            report(badSizeDescriptor),
            NOW,
        ) as ModelPackageStageResult.Rejected
        assertEquals(
            setOf(ModelPackageRejection.ARTIFACT_SOURCE_SIZE_MISMATCH),
            badSize.reasons,
        )
        assertTrue(root.resolve("staging").listFiles().orEmpty().isEmpty())
        assertFalse(packageDirectory(root, correct.descriptor.identity).exists())
    }

    @Test
    fun everyUpperGateFailsClosedWhileManifestContentsRemainOpaqueToTheStore() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val opaque = fixture(
            "opaque_package",
            "1.0.0",
            linkedMapOf("primary" to "opaque-model"),
            manifest = "not-json-store-must-not-parse-license-or-manifest".toByteArray(),
        )
        assertStaged(stage(store, opaque))

        val rejectedFixture = fixture(
            "rejected_package",
            "1.0.0",
            linkedMapOf("primary" to "model"),
        )
        val rejectedReport = rejectedFixture.report.copy(
            catalogEntryActive = false,
            catalogSignatureValid = false,
            catalogManifestHashValid = false,
            manifestSignatureValid = false,
            licenseGatePassed = false,
            metadataCompatible = false,
            deviceCompatible = false,
            selfTestPassed = false,
            runValidUntilEpochMillis = NOW,
        )
        val rejected = stage(store, rejectedFixture, rejectedReport)
            as ModelPackageStageResult.Rejected
        assertEquals(
            setOf(
                ModelPackageRejection.CATALOG_ENTRY_INACTIVE,
                ModelPackageRejection.CATALOG_SIGNATURE_INVALID,
                ModelPackageRejection.CATALOG_MANIFEST_HASH_INVALID,
                ModelPackageRejection.MANIFEST_SIGNATURE_INVALID,
                ModelPackageRejection.LICENSE_GATE_FAILED,
                ModelPackageRejection.METADATA_INCOMPATIBLE,
                ModelPackageRejection.DEVICE_INCOMPATIBLE,
                ModelPackageRejection.SELF_TEST_FAILED,
                ModelPackageRejection.GATE_REPORT_EXPIRED,
            ),
            rejected.reasons,
        )
        assertFalse(packageDirectory(root, rejectedFixture.descriptor.identity).exists())
    }

    @Test
    fun gateReportMustMatchIsPersistedAndIsRecheckedForEveryLease() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val fixture = fixture("detector", "1.0.0", linkedMapOf("primary" to "model"))
        val mismatched = fixture.report.copy(packageDescriptorSha256 = "0".repeat(64))
        val mismatchResult = stage(store, fixture, mismatched) as ModelPackageStageResult.Rejected
        assertEquals(
            setOf(ModelPackageRejection.GATE_REPORT_PACKAGE_MISMATCH),
            mismatchResult.reasons,
        )

        assertStaged(stage(store, fixture))
        assertActivated(store.activatePackage(SLOT, fixture.descriptor.pointer(), NOW))
        val lease = (
            store.acquireRuntimeLease(
                fixture.descriptor.pointer(),
                NOW,
            ) as ModelPackageRuntimeLeaseResult.Acquired
            ).lease
        assertEquals(fixture.report, lease.gateReport)
        lease.close()

        packageDirectory(root, fixture.descriptor.identity)
            .resolve("gate-report.tsv")
            .writeText("corrupt")
        val corrupt = store.acquireRuntimeLease(
            fixture.descriptor.pointer(),
            NOW,
        ) as ModelPackageRuntimeLeaseResult.Rejected
        assertEquals(setOf(ModelPackageRejection.GATE_REPORT_CORRUPT), corrupt.reasons)
    }

    @Test
    fun lowStorageClosesEverySourceAndLeavesTheActivePackageUntouched() {
        val root = temporaryFolder.newFolder("models")
        val normalStore = ModelArtifactStore(root)
        val first = fixture("detector", "1.0.0", linkedMapOf("primary" to "first"))
        val second = fixture(
            "detector",
            "2.0.0",
            linkedMapOf("vision" to "second-vision", "text" to "second-text"),
        )
        assertStaged(stage(normalStore, first))
        assertActivated(normalStore.activatePackage(SLOT, first.descriptor.pointer(), NOW))

        val vision = TrackingInputStream("second-vision".toByteArray())
        val text = TrackingInputStream("second-text".toByteArray())
        val lowSpaceStore = ModelArtifactStore(root, usableSpaceBytes = { 0L })
        val result = lowSpaceStore.stagePackage(
            second.descriptor,
            second.manifest,
            mapOf("vision" to vision, "text" to text),
            second.report,
            NOW,
        ) as ModelPackageStageResult.Rejected

        assertEquals(setOf(ModelPackageRejection.INSUFFICIENT_STORAGE), result.reasons)
        assertTrue(vision.closed)
        assertTrue(text.closed)
        assertEquals(first.descriptor.pointer(), normalStore.activationState(SLOT).current)
        assertFalse(packageDirectory(root, second.descriptor.identity).exists())
    }

    @Test
    fun exactRestageIgnoresFreshTransientGateReportButKeepsStoredReport() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val first = fixture("detector", "1.0.0", linkedMapOf("primary" to "same"))
        val initial = stage(store, first) as ModelPackageStageResult.Staged
        val refreshedReport = first.report.copy(
            reportId = "fresh-prepare-report",
            evidenceRef = "evidence/gates/VM-010/fresh-prepare.json",
            validatedAtEpochMillis = NOW + 1,
        )
        val repeated = stage(
            store = store,
            fixture = first,
            gateReport = refreshedReport,
            nowEpochMillis = NOW + 1,
        ) as ModelPackageStageResult.Staged

        assertFalse(initial.alreadyPresent)
        assertTrue(repeated.alreadyPresent)
        assertEquals(first.report, repeated.gateReport)

        val lease = (
            store.acquireRuntimeLease(first.descriptor.pointer(), NOW + 1)
                as ModelPackageRuntimeLeaseResult.Acquired
            ).lease
        lease.use { assertEquals(first.report, it.gateReport) }
    }

    @Test
    fun sameIdentityRejectsDescriptorManifestAndActualIncomingContentConflicts() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val first = fixture("detector", "1.0.0", linkedMapOf("primary" to "same"))
        assertStaged(stage(store, first))

        val conflict = fixture(
            "detector",
            "1.0.0",
            linkedMapOf("primary" to "different"),
        )
        val rejected = stage(store, conflict) as ModelPackageStageResult.Rejected
        assertEquals(
            setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
            rejected.reasons,
        )

        val manifestConflict = fixture(
            packageId = "detector",
            version = "1.0.0",
            artifactContents = linkedMapOf("primary" to "same"),
            manifest = "{\"package_id\":\"detector\",\"version\":\"1.0.0\",\"changed\":true}"
                .toByteArray(),
        )
        val manifestRejected = stage(store, manifestConflict) as ModelPackageStageResult.Rejected
        assertEquals(
            setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
            manifestRejected.reasons,
        )

        val changedBytesWithOriginalDescriptor = store.stagePackage(
            descriptor = first.descriptor,
            canonicalManifestBytes = first.manifest,
            artifactSourcesByRole = mapOf("primary" to bytes("evil")),
            gateReport = first.report.copy(
                reportId = "content-conflict-report",
                validatedAtEpochMillis = NOW + 1,
            ),
            nowEpochMillis = NOW + 1,
        ) as ModelPackageStageResult.Rejected
        assertEquals(
            setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
            changedBytesWithOriginalDescriptor.reasons,
        )
    }

    @Test
    fun expiredStoredGateReportCannotBeRefreshedByRestaging() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val first = fixture("detector", "1.0.0", linkedMapOf("primary" to "same"))
        val expiringReport = first.report.copy(runValidUntilEpochMillis = NOW + 1)
        assertStaged(stage(store, first, expiringReport))

        val rejected = stage(
            store = store,
            fixture = first,
            gateReport = first.report.copy(
                reportId = "attempted-refresh",
                validatedAtEpochMillis = NOW + 1,
            ),
            nowEpochMillis = NOW + 1,
        ) as ModelPackageStageResult.Rejected

        assertEquals(
            setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
            rejected.reasons,
        )
    }

    @Test
    fun installedPackageRunsAfterCatalogFreshnessExpiresButCannotBeNewlyStaged() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val fixture = fixture("offline_detector", "1.0.0", linkedMapOf("primary" to "model"))
        val catalogFreshUntil = NOW + 7 * 24 * 60 * 60 * 1_000L
        val report = fixture.report.copy(
            catalogFreshUntilEpochMillis = catalogFreshUntil,
            runValidUntilEpochMillis = Long.MAX_VALUE,
        )
        assertStaged(stage(store, fixture, report, nowEpochMillis = NOW))

        val dayEight = NOW + 8 * 24 * 60 * 60 * 1_000L
        val lease = store.acquireRuntimeLease(fixture.descriptor.pointer(), dayEight)
        assertTrue(lease is ModelPackageRuntimeLeaseResult.Acquired)
        (lease as ModelPackageRuntimeLeaseResult.Acquired).lease.close()

        val newRoot = temporaryFolder.newFolder("models-after-day-eight")
        val rejected = stage(
            ModelArtifactStore(newRoot),
            fixture,
            report,
            nowEpochMillis = dayEight,
        ) as ModelPackageStageResult.Rejected
        assertEquals(setOf(ModelPackageRejection.CATALOG_FRESHNESS_EXPIRED), rejected.reasons)
    }

    @Test
    fun concurrentSameIdentityStageCommitsExactlyOneCompletePackage() {
        val root = temporaryFolder.newFolder("models")
        val firstStore = ModelArtifactStore(root)
        val secondStore = ModelArtifactStore(root)
        val first = fixture(
            "race_package",
            "1.0.0",
            linkedMapOf("vision" to "first-vision", "text" to "first-text"),
        )
        val second = fixture(
            "race_package",
            "1.0.0",
            linkedMapOf("vision" to "second-vision", "text" to "second-text"),
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstFuture = executor.submit<ModelPackageStageResult> {
                start.await()
                stage(firstStore, first)
            }
            val secondFuture = executor.submit<ModelPackageStageResult> {
                start.await()
                stage(secondStore, second)
            }
            start.countDown()
            val results = listOf(
                firstFuture.get(10, TimeUnit.SECONDS),
                secondFuture.get(10, TimeUnit.SECONDS),
            )
            val staged = results.filterIsInstance<ModelPackageStageResult.Staged>()
            val rejected = results.filterIsInstance<ModelPackageStageResult.Rejected>()
            assertEquals(1, staged.size)
            assertEquals(1, rejected.size)
            assertEquals(
                setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
                rejected.single().reasons,
            )

            val winner = if (staged.single().descriptor == first.descriptor) first else second
            assertActivated(firstStore.activatePackage(SLOT, winner.descriptor.pointer(), NOW))
            val lease = (
                secondStore.acquireRuntimeLease(
                    winner.descriptor.pointer(),
                    NOW,
                ) as ModelPackageRuntimeLeaseResult.Acquired
                ).lease
            winner.artifacts.forEach { (role, content) ->
                assertArrayEquals(content, lease.artifactFilesByRole.getValue(role).readBytes())
            }
            lease.close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun tamperedPreviousPackageAndExpiredReportBothFailClosed() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val first = fixture("detector", "1.0.0", linkedMapOf("primary" to "first"))
        val second = fixture("detector", "2.0.0", linkedMapOf("primary" to "second"))
        assertStaged(stage(store, first))
        assertStaged(stage(store, second))
        assertActivated(store.activatePackage(SLOT, first.descriptor.pointer(), NOW))
        assertActivated(store.activatePackage(SLOT, second.descriptor.pointer(), NOW))

        artifactFile(root, first, "primary").writeText("tampered")
        val rollback = store.rollback(SLOT, NOW + 1) as ModelPackageActivationResult.Rejected
        assertTrue(ModelPackageRejection.ARTIFACT_SIZE_MISMATCH in rollback.reasons)
        assertTrue(ModelPackageRejection.ARTIFACT_SHA256_MISMATCH in rollback.reasons)
        assertEquals(second.descriptor.pointer(), store.activationState(SLOT).current)

        val expired = store.acquireRuntimeLease(second.descriptor.pointer(), VALID_UNTIL)
            as ModelPackageRuntimeLeaseResult.Rejected
        assertEquals(setOf(ModelPackageRejection.GATE_REPORT_EXPIRED), expired.reasons)
    }

    @Test
    fun crashResidueIsRemovedAndAtomicStateFailureKeepsTheOldPointer() {
        val root = temporaryFolder.newFolder("models")
        root.resolve("staging/orphan/artifacts").mkdirs()
        root.resolve("staging/orphan/artifacts/partial.bin").writeText("partial")
        root.resolve("state").mkdirs()
        root.resolve("state/activation-state.tsv.crash.tmp").writeText("partial")
        val store = ModelArtifactStore(root)
        assertTrue(root.resolve("staging").listFiles().orEmpty().isEmpty())
        assertFalse(root.resolve("state/activation-state.tsv.crash.tmp").exists())

        val first = fixture("detector", "1.0.0", linkedMapOf("primary" to "first"))
        val second = fixture("detector", "2.0.0", linkedMapOf("primary" to "second"))
        assertStaged(stage(store, first))
        assertStaged(stage(store, second))
        assertActivated(store.activatePackage(SLOT, first.descriptor.pointer(), NOW))

        val failingStore = ModelArtifactStore(
            rootDirectory = root,
            usableSpaceBytes = { Long.MAX_VALUE },
            beforeAtomicMove = { source, destination ->
                if (destination.name == "activation-state.tsv") {
                    throw AtomicMoveNotSupportedException(
                        source.path,
                        destination.path,
                        "simulated crash-safe failure",
                    )
                }
            },
        )
        val failed = failingStore.activatePackage(SLOT, second.descriptor.pointer(), NOW)
            as ModelPackageActivationResult.Rejected
        assertEquals(setOf(ModelPackageRejection.ATOMIC_MOVE_UNAVAILABLE), failed.reasons)
        assertEquals(first.descriptor.pointer(), store.activationState(SLOT).current)
        assertNull(store.activationState(SLOT).previous)
    }

    @Test
    fun corruptStateAndTamperedCurrentArtifactCannotProduceALease() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val fixture = fixture("detector", "1.0.0", linkedMapOf("primary" to "model"))
        assertStaged(stage(store, fixture))
        assertActivated(store.activatePackage(SLOT, fixture.descriptor.pointer(), NOW))

        artifactFile(root, fixture, "primary").writeText("tampered")
        val tampered = store.acquireRuntimeLease(
            fixture.descriptor.pointer(),
            NOW,
        ) as ModelPackageRuntimeLeaseResult.Rejected
        assertTrue(ModelPackageRejection.ARTIFACT_SIZE_MISMATCH in tampered.reasons)
        assertTrue(ModelPackageRejection.ARTIFACT_SHA256_MISMATCH in tampered.reasons)

        root.resolve("state/activation-state.tsv").writeText("corrupt")
        val stateIndependentLease = store.acquireRuntimeLease(
            fixture.descriptor.pointer(),
            NOW,
        ) as ModelPackageRuntimeLeaseResult.Rejected
        assertTrue(ModelPackageRejection.ARTIFACT_SIZE_MISMATCH in stateIndependentLease.reasons)
        assertTrue(ModelPackageRejection.ARTIFACT_SHA256_MISMATCH in stateIndependentLease.reasons)
    }

    @Test
    fun packageRecordCannotRedirectAnInstalledDirectoryToAnotherIdentity() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val fixture = fixture("detector", "1.0.0", linkedMapOf("primary" to "model"))
        assertStaged(stage(store, fixture))
        val record = packageDirectory(root, fixture.descriptor.identity).resolve("package-record.tsv")
        record.writeText(record.readText().replace("package_id=detector", "package_id=other"))

        val result = store.activatePackage(SLOT, fixture.descriptor.pointer(), NOW)
            as ModelPackageActivationResult.Rejected
        assertEquals(setOf(ModelPackageRejection.PACKAGE_RECORD_CORRUPT), result.reasons)
        assertNull(store.activationState(SLOT).current)
    }

    @Test
    fun oversizedArtifactStreamStopsAfterOneSentinelByteAndCloses() {
        val root = temporaryFolder.newFolder("models")
        val store = ModelArtifactStore(root)
        val manifest = "manifest".toByteArray()
        val descriptor = descriptor(
            ModelPackageIdentity("detector", "1.0.0"),
            manifest,
            listOf(
                ModelPackageArtifactDescriptor(
                    role = "primary",
                    relativePath = "model.bin",
                    sha256 = sha256(ByteArray(4)),
                    sizeBytes = 4,
                ),
            ),
        )
        val endless = CountingEndlessInputStream()
        val result = store.stagePackage(
            descriptor,
            manifest,
            mapOf("primary" to endless),
            report(descriptor),
            NOW,
        ) as ModelPackageStageResult.Rejected

        assertEquals(
            setOf(ModelPackageRejection.ARTIFACT_SOURCE_SIZE_MISMATCH),
            result.reasons,
        )
        assertEquals(5, endless.bytesRead)
        assertTrue(endless.closed)
    }

    @Test
    fun packageArtifactPathsRejectTraversalAndUnsafeRoles() {
        try {
            ModelPackageArtifactDescriptor("primary", "../model.bin", "0".repeat(64), 1)
            fail("path traversal must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            ModelPackageArtifactDescriptor("bad-role", "model.bin", "0".repeat(64), 1)
            fail("roles must match the Manifest contract")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun stage(
        store: ModelArtifactStore,
        fixture: PackageFixture,
        gateReport: ModelPackageGateReport = fixture.report,
        nowEpochMillis: Long = NOW,
    ): ModelPackageStageResult = store.stagePackage(
        descriptor = fixture.descriptor,
        canonicalManifestBytes = fixture.manifest,
        artifactSourcesByRole = fixture.sources(),
        gateReport = gateReport,
        nowEpochMillis = nowEpochMillis,
    )

    private fun fixture(
        packageId: String,
        version: String,
        artifactContents: LinkedHashMap<String, String>,
        manifest: ByteArray = "{\"package_id\":\"$packageId\",\"version\":\"$version\"}"
            .toByteArray(),
    ): PackageFixture {
        val identity = ModelPackageIdentity(packageId, version)
        val descriptors = artifactContents.entries.map { (role, content) ->
            ModelPackageArtifactDescriptor(
                role = role,
                relativePath = "$role.bin",
                sha256 = sha256(content.toByteArray()),
                sizeBytes = content.toByteArray().size.toLong(),
            )
        }
        val descriptor = descriptor(identity, manifest, descriptors)
        return PackageFixture(
            descriptor = descriptor,
            manifest = manifest,
            artifacts = artifactContents.mapValuesTo(linkedMapOf()) { it.value.toByteArray() },
            report = report(descriptor),
        )
    }

    private fun descriptor(
        identity: ModelPackageIdentity,
        manifest: ByteArray,
        artifacts: List<ModelPackageArtifactDescriptor>,
    ) = ModelPackageDescriptor(
        identity = identity,
        canonicalManifestSha256 = sha256(manifest),
        canonicalManifestSizeBytes = manifest.size.toLong(),
        artifacts = artifacts,
    )

    private fun report(descriptor: ModelPackageDescriptor) = ModelPackageGateReport(
        reportId = "gate-${descriptor.identity.packageId}-${descriptor.identity.packageVersion}",
        evidenceRef = "evidence/gates/VM-010/model-package-store.json",
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
        catalogFreshUntilEpochMillis = VALID_UNTIL,
        runValidUntilEpochMillis = VALID_UNTIL,
    )

    private fun packageDirectory(root: File, identity: ModelPackageIdentity): File = root
        .resolve("packages")
        .resolve(identity.packageId)
        .resolve(identity.packageVersion)

    private fun artifactFile(root: File, fixture: PackageFixture, role: String): File {
        val artifact = checkNotNull(fixture.descriptor.artifact(role))
        return packageDirectory(root, fixture.descriptor.identity)
            .resolve("artifacts")
            .resolve(artifact.relativePath)
    }

    private fun bytes(value: String) = ByteArrayInputStream(value.toByteArray())

    private fun sha256(value: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun assertStaged(result: ModelPackageStageResult) {
        assertTrue(result is ModelPackageStageResult.Staged)
    }

    private fun assertActivated(result: ModelPackageActivationResult) {
        assertTrue(result is ModelPackageActivationResult.Activated)
    }

    private data class PackageFixture(
        val descriptor: ModelPackageDescriptor,
        val manifest: ByteArray,
        val artifacts: LinkedHashMap<String, ByteArray>,
        val report: ModelPackageGateReport,
    ) {
        fun sources(): Map<String, InputStream> = artifacts.mapValues {
            ByteArrayInputStream(it.value)
        }
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class CountingEndlessInputStream : InputStream() {
        var bytesRead = 0
        var closed = false

        override fun read(): Int {
            bytesRead++
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            repeat(length) { buffer[offset + it] = 0 }
            bytesRead += length
            return length
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val SLOT = ModelPackageSlot("visual_target_text")
        val READING_SLOT = ModelPackageSlot("structured_reading_none")
        const val NOW = 10L
        const val VALID_UNTIL = 1_000L
    }
}
