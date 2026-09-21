package app.beyoureyes.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val PACKAGE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
private val PACKAGE_VERSION_PATTERN =
    Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")
private val ARTIFACT_ROLE_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
private val ARTIFACT_PATH_SEGMENT_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val MODEL_PACKAGE_SLOT_PATTERN = Regex("^[a-z][a-z0-9_]{1,127}$")
private const val PACKAGE_DESCRIPTOR_FINGERPRINT_SCHEMA =
    "be-your-eyes-model-package-descriptor-v2"

data class ModelPackageIdentity(
    val packageId: String,
    val packageVersion: String,
) {
    init {
        require(PACKAGE_ID_PATTERN.matches(packageId)) { "invalid packageId" }
        require(PACKAGE_VERSION_PATTERN.matches(packageVersion)) { "invalid packageVersion" }
    }
}

data class ModelPackageArtifactDescriptor(
    val role: String,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        require(ARTIFACT_ROLE_PATTERN.matches(role)) { "invalid artifact role" }
        require(isSafeArtifactPath(relativePath)) { "invalid artifact relativePath" }
        require(SHA256_PATTERN.matches(sha256)) { "sha256 must be lowercase hex" }
        require(sizeBytes > 0) { "sizeBytes must be positive" }
        require(sizeBytes <= MAX_MODEL_ARTIFACT_BYTES) {
            "sizeBytes exceeds the per-artifact limit"
        }
    }
}

/**
 * Immutable storage descriptor derived by the already-validated Catalog/Manifest layer.
 *
 * This class deliberately does not parse the canonical Manifest or make licensing decisions. The
 * caller supplies its exact canonical bytes, their expected hash, and the complete artifact set.
 */
class ModelPackageDescriptor(
    val identity: ModelPackageIdentity,
    val canonicalManifestSha256: String,
    val canonicalManifestSizeBytes: Long,
    artifacts: List<ModelPackageArtifactDescriptor>,
) {
    val artifacts: List<ModelPackageArtifactDescriptor> = Collections.unmodifiableList(
        artifacts.sortedBy(ModelPackageArtifactDescriptor::role).toList(),
    )
    val descriptorSha256: String

    init {
        require(SHA256_PATTERN.matches(canonicalManifestSha256)) {
            "canonicalManifestSha256 must be lowercase hex"
        }
        require(canonicalManifestSizeBytes in 1..MAX_CANONICAL_MANIFEST_BYTES) {
            "canonical Manifest size is outside the supported range"
        }
        require(this.artifacts.isNotEmpty()) { "a package must contain at least one artifact" }
        require(this.artifacts.map { it.role }.distinct().size == this.artifacts.size) {
            "artifact roles must be unique"
        }
        require(this.artifacts.map { it.relativePath }.distinct().size == this.artifacts.size) {
            "artifact paths must be unique"
        }
        var totalBytes = canonicalManifestSizeBytes
        this.artifacts.forEach { artifact ->
            totalBytes = Math.addExact(totalBytes, artifact.sizeBytes)
        }
        require(totalBytes <= MAX_MODEL_PACKAGE_BYTES) { "package exceeds the package size limit" }
        descriptorSha256 = sha256(descriptorFingerprintBytes())
    }

    val artifactRoles: Set<String>
        get() = artifacts.mapTo(linkedSetOf()) { it.role }

    val contentSizeBytes: Long
        get() = artifacts.fold(canonicalManifestSizeBytes) { total, artifact ->
            Math.addExact(total, artifact.sizeBytes)
        }

    fun artifact(role: String): ModelPackageArtifactDescriptor? = artifacts.firstOrNull {
        it.role == role
    }

    internal fun pointer(): ModelPackagePointer = ModelPackagePointer(
        identity = identity,
        canonicalManifestSha256 = canonicalManifestSha256,
    )

    private fun descriptorFingerprintBytes(): ByteArray = buildString {
        appendLine(PACKAGE_DESCRIPTOR_FINGERPRINT_SCHEMA)
        append(identity.packageId).append('\t').appendLine(identity.packageVersion)
        append(canonicalManifestSha256).append('\t').appendLine(canonicalManifestSizeBytes)
        artifacts.forEach { artifact ->
            append(artifact.role).append('\t')
            append(artifact.relativePath).append('\t')
            append(artifact.sha256).append('\t')
            appendLine(artifact.sizeBytes)
        }
    }.toByteArray(StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is ModelPackageDescriptor &&
        identity == other.identity &&
        canonicalManifestSha256 == other.canonicalManifestSha256 &&
        canonicalManifestSizeBytes == other.canonicalManifestSizeBytes &&
        artifacts == other.artifacts

    override fun hashCode(): Int {
        var result = identity.hashCode()
        result = 31 * result + canonicalManifestSha256.hashCode()
        result = 31 * result + canonicalManifestSizeBytes.hashCode()
        result = 31 * result + artifacts.hashCode()
        return result
    }

    override fun toString(): String =
        "ModelPackageDescriptor(identity=$identity, manifestSha256=$canonicalManifestSha256, " +
            "manifestSize=$canonicalManifestSizeBytes, artifacts=$artifacts)"
}

/**
 * Persisted result from upper-layer signature, licensing, compatibility and self-test gates.
 *
 * The store only verifies that every decision is present, true, fresh and bound to the exact
 * package descriptor. It does not inspect license text or reinterpret any decision.
 */
data class ModelPackageGateReport(
    val reportId: String,
    val evidenceRef: String,
    val packageDescriptorSha256: String,
    val catalogEntryActive: Boolean,
    val catalogSignatureValid: Boolean,
    val catalogManifestHashValid: Boolean,
    val manifestSignatureValid: Boolean,
    val licenseGatePassed: Boolean,
    val metadataCompatible: Boolean,
    val deviceCompatible: Boolean,
    val selfTestPassed: Boolean,
    val validatedAtEpochMillis: Long,
    /** Freshness of the Catalog that authorized installation; checked only while staging. */
    val catalogFreshUntilEpochMillis: Long,
    /** Signed license/runtime upper bound; checked every time a runtime lease is acquired. */
    val runValidUntilEpochMillis: Long,
) {
    init {
        require(isSafeRecordValue(reportId)) { "invalid reportId" }
        require(isSafeRecordValue(evidenceRef)) { "invalid evidenceRef" }
        require(SHA256_PATTERN.matches(packageDescriptorSha256)) {
            "packageDescriptorSha256 must be lowercase hex"
        }
    }

    internal fun runtimeRejectionReasons(
        descriptor: ModelPackageDescriptor,
        nowEpochMillis: Long,
    ): Set<ModelPackageRejection> = buildSet {
        if (packageDescriptorSha256 != descriptor.descriptorSha256) {
            add(ModelPackageRejection.GATE_REPORT_PACKAGE_MISMATCH)
        }
        if (!catalogEntryActive) add(ModelPackageRejection.CATALOG_ENTRY_INACTIVE)
        if (!catalogSignatureValid) add(ModelPackageRejection.CATALOG_SIGNATURE_INVALID)
        if (!catalogManifestHashValid) {
            add(ModelPackageRejection.CATALOG_MANIFEST_HASH_INVALID)
        }
        if (!manifestSignatureValid) add(ModelPackageRejection.MANIFEST_SIGNATURE_INVALID)
        if (!licenseGatePassed) add(ModelPackageRejection.LICENSE_GATE_FAILED)
        if (!metadataCompatible) add(ModelPackageRejection.METADATA_INCOMPATIBLE)
        if (!deviceCompatible) add(ModelPackageRejection.DEVICE_INCOMPATIBLE)
        if (!selfTestPassed) add(ModelPackageRejection.SELF_TEST_FAILED)
        if (validatedAtEpochMillis < 0 || validatedAtEpochMillis > nowEpochMillis) {
            add(ModelPackageRejection.GATE_REPORT_TIME_INVALID)
        }
        if (catalogFreshUntilEpochMillis < validatedAtEpochMillis ||
            runValidUntilEpochMillis < validatedAtEpochMillis
        ) {
            add(ModelPackageRejection.GATE_REPORT_TIME_INVALID)
        }
        if (nowEpochMillis >= runValidUntilEpochMillis
        ) {
            add(ModelPackageRejection.GATE_REPORT_EXPIRED)
        }
    }

    internal fun stagingRejectionReasons(
        descriptor: ModelPackageDescriptor,
        nowEpochMillis: Long,
    ): Set<ModelPackageRejection> = buildSet {
        addAll(runtimeRejectionReasons(descriptor, nowEpochMillis))
        if (nowEpochMillis >= catalogFreshUntilEpochMillis) {
            add(ModelPackageRejection.CATALOG_FRESHNESS_EXPIRED)
        }
    }
}

data class ModelPackagePointer(
    val identity: ModelPackageIdentity,
    val canonicalManifestSha256: String,
) {
    init {
        require(SHA256_PATTERN.matches(canonicalManifestSha256)) {
            "canonicalManifestSha256 must be lowercase hex"
        }
    }
}

/**
 * Stable product/runtime direction whose activation history is independent from every other
 * direction. A slot is not a package selector and is never supplied by a model package.
 */
data class ModelPackageSlot(val slotId: String) {
    init {
        require(MODEL_PACKAGE_SLOT_PATTERN.matches(slotId)) { "invalid model package slotId" }
    }

    companion object {
        fun forCapability(capabilityId: String, targetMode: String): ModelPackageSlot {
            require(MODEL_PACKAGE_SLOT_PATTERN.matches(capabilityId)) {
                "invalid capabilityId for model package slot"
            }
            require(MODEL_PACKAGE_SLOT_PATTERN.matches(targetMode)) {
                "invalid targetMode for model package slot"
            }
            return ModelPackageSlot("${capabilityId}_${targetMode}")
        }
    }
}

data class ModelPackageActivationState(
    val current: ModelPackagePointer?,
    val previous: ModelPackagePointer?,
)

/** Defensive immutable wrapper; [copyBytes] never exposes the stored backing array. */
class CanonicalManifestSnapshot internal constructor(
    val sha256: String,
    bytes: ByteArray,
) {
    private val value = bytes.copyOf()

    val sizeBytes: Long
        get() = value.size.toLong()

    fun copyBytes(): ByteArray = value.copyOf()
}

enum class ModelPackageRejection {
    MONITORING_ACTIVE,
    MANIFEST_EMPTY,
    MANIFEST_TOO_LARGE,
    MANIFEST_SIZE_MISMATCH,
    MANIFEST_SHA256_MISMATCH,
    MISSING_ARTIFACT_ROLE,
    UNEXPECTED_ARTIFACT_ROLE,
    ARTIFACT_SOURCE_SIZE_MISMATCH,
    ARTIFACT_SOURCE_SHA256_MISMATCH,
    INSUFFICIENT_STORAGE,
    IMMUTABLE_VERSION_CONFLICT,
    PACKAGE_NOT_INSTALLED,
    MANIFEST_NOT_INSTALLED,
    ARTIFACT_NOT_INSTALLED,
    ARTIFACT_SIZE_MISMATCH,
    ARTIFACT_SHA256_MISMATCH,
    UNEXPECTED_INSTALLED_ARTIFACT,
    GATE_REPORT_PACKAGE_MISMATCH,
    CATALOG_ENTRY_INACTIVE,
    CATALOG_SIGNATURE_INVALID,
    CATALOG_MANIFEST_HASH_INVALID,
    MANIFEST_SIGNATURE_INVALID,
    LICENSE_GATE_FAILED,
    METADATA_INCOMPATIBLE,
    DEVICE_INCOMPATIBLE,
    SELF_TEST_FAILED,
    GATE_REPORT_TIME_INVALID,
    CATALOG_FRESHNESS_EXPIRED,
    GATE_REPORT_EXPIRED,
    GATE_REPORT_CORRUPT,
    PACKAGE_RECORD_CORRUPT,
    POINTER_PACKAGE_MISMATCH,
    NO_ROLLBACK_PACKAGE,
    STATE_CORRUPT,
    IO_FAILURE,
    ATOMIC_MOVE_UNAVAILABLE,
}

sealed interface ModelPackageStageResult {
    data class Staged(
        val descriptor: ModelPackageDescriptor,
        val gateReport: ModelPackageGateReport,
        val alreadyPresent: Boolean,
    ) : ModelPackageStageResult

    data class Rejected(val reasons: Set<ModelPackageRejection>) : ModelPackageStageResult {
        init {
            require(reasons.isNotEmpty())
        }
    }
}

sealed interface ModelPackageActivationResult {
    data class Activated(
        val state: ModelPackageActivationState,
        val changed: Boolean,
    ) : ModelPackageActivationResult

    data class Rejected(val reasons: Set<ModelPackageRejection>) : ModelPackageActivationResult {
        init {
            require(reasons.isNotEmpty())
        }
    }
}

sealed interface ModelPackageRuntimeLeaseResult {
    data class Acquired(val lease: ModelPackageRuntimeLease) : ModelPackageRuntimeLeaseResult

    data class Rejected(val reasons: Set<ModelPackageRejection>) : ModelPackageRuntimeLeaseResult {
        init {
            require(reasons.isNotEmpty())
        }
    }
}

/**
 * App-private package store with whole-package staging and atomic per-slot current/previous
 * pointers.
 *
 * A runtime holds [ModelPackageRuntimeLease] for its complete lifetime. Activation and rollback
 * fail while that lease exists, while staging another complete immutable package remains safe.
 */
class ModelArtifactStore private constructor(
    private val rootDirectory: File,
    private val usableSpaceBytes: () -> Long,
    private val beforeAtomicMove: (source: File, destination: File) -> Unit,
) {
    constructor(rootDirectory: File) : this(
        rootDirectory = rootDirectory,
        usableSpaceBytes = { rootDirectory.usableSpace },
        beforeAtomicMove = { _, _ -> },
    )

    internal constructor(
        rootDirectory: File,
        usableSpaceBytes: () -> Long,
        beforeAtomicMove: (source: File, destination: File) -> Unit = { _, _ -> },
        @Suppress("UNUSED_PARAMETER") testingOnly: Unit = Unit,
    ) : this(rootDirectory, usableSpaceBytes, beforeAtomicMove)

    private val packagesDirectory = rootDirectory.resolve("packages")
    private val stagingDirectory = rootDirectory.resolve("staging")
    private val stateDirectory = rootDirectory.resolve("state")
    private val stateFile = stateDirectory.resolve("activation-state.tsv")
    private val activationLockFile = stateDirectory.resolve("runtime-activation.lock")
    private val mutationLockFile = stateDirectory.resolve("store-mutation.lock")
    private val canonicalRoot = rootDirectory.canonicalFile.path
    private val processActivationPermit = ProcessStoreLocks.activationPermit(canonicalRoot)
    private val processMutationLock = ProcessStoreLocks.mutationLock(canonicalRoot)

    init {
        packagesDirectory.mkdirsOrThrow()
        stagingDirectory.mkdirsOrThrow()
        stateDirectory.mkdirsOrThrow()
        processMutationLock.withLock {
            withMutationFileLock {
                stagingDirectory.listFiles().orEmpty().forEach(::deleteRecursivelyOrThrow)
                stateDirectory.listFiles()
                    .orEmpty()
                    .filter { it.name.startsWith(stateFile.name) && it.name.endsWith(".tmp") }
                    .forEach(::deleteRecursivelyOrThrow)
            }
        }
    }

    /**
     * Takes ownership of every stream in [artifactSourcesByRole] and closes all of them on every
     * result path. Nothing becomes installed until Manifest, gate report and every artifact role
     * have been written, hashed, reread and atomically moved as one directory.
     */
    fun stagePackage(
        descriptor: ModelPackageDescriptor,
        canonicalManifestBytes: ByteArray,
        artifactSourcesByRole: Map<String, InputStream>,
        gateReport: ModelPackageGateReport,
        nowEpochMillis: Long,
    ): ModelPackageStageResult {
        val sources = artifactSourcesByRole.toMap()
        try {
            if (canonicalManifestBytes.size.toLong() > MAX_CANONICAL_MANIFEST_BYTES) {
                return ModelPackageStageResult.Rejected(
                    setOf(ModelPackageRejection.MANIFEST_TOO_LARGE),
                )
            }
            val manifestCopy = canonicalManifestBytes.copyOf()
            val inputReasons = validateStageInputs(
                descriptor = descriptor,
                canonicalManifestBytes = manifestCopy,
                artifactSourcesByRole = sources,
                gateReport = gateReport,
                nowEpochMillis = nowEpochMillis,
            )
            if (inputReasons.isNotEmpty()) return ModelPackageStageResult.Rejected(inputReasons)

            return processMutationLock.withLock {
                withMutationFileLock {
                    stagePackageLocked(
                        descriptor = descriptor,
                        canonicalManifestBytes = manifestCopy,
                        artifactSourcesByRole = sources,
                        gateReport = gateReport,
                        nowEpochMillis = nowEpochMillis,
                    )
                }
            }
        } catch (_: Exception) {
            return ModelPackageStageResult.Rejected(setOf(ModelPackageRejection.IO_FAILURE))
        } finally {
            sources.values.forEach { source -> runCatching { source.close() } }
        }
    }

    private fun validateStageInputs(
        descriptor: ModelPackageDescriptor,
        canonicalManifestBytes: ByteArray,
        artifactSourcesByRole: Map<String, InputStream>,
        gateReport: ModelPackageGateReport,
        nowEpochMillis: Long,
    ): Set<ModelPackageRejection> = buildSet {
        if (canonicalManifestBytes.isEmpty()) add(ModelPackageRejection.MANIFEST_EMPTY)
        if (canonicalManifestBytes.size.toLong() > MAX_CANONICAL_MANIFEST_BYTES) {
            add(ModelPackageRejection.MANIFEST_TOO_LARGE)
        }
        if (canonicalManifestBytes.size.toLong() != descriptor.canonicalManifestSizeBytes) {
            add(ModelPackageRejection.MANIFEST_SIZE_MISMATCH)
        }
        if (sha256(canonicalManifestBytes) != descriptor.canonicalManifestSha256) {
            add(ModelPackageRejection.MANIFEST_SHA256_MISMATCH)
        }
        val suppliedRoles = artifactSourcesByRole.keys
        if (!suppliedRoles.containsAll(descriptor.artifactRoles)) {
            add(ModelPackageRejection.MISSING_ARTIFACT_ROLE)
        }
        if (!descriptor.artifactRoles.containsAll(suppliedRoles)) {
            add(ModelPackageRejection.UNEXPECTED_ARTIFACT_ROLE)
        }
        addAll(gateReport.stagingRejectionReasons(descriptor, nowEpochMillis))
    }

    private fun stagePackageLocked(
        descriptor: ModelPackageDescriptor,
        canonicalManifestBytes: ByteArray,
        artifactSourcesByRole: Map<String, InputStream>,
        gateReport: ModelPackageGateReport,
        nowEpochMillis: Long,
    ): ModelPackageStageResult {
        val destination = packageDirectory(descriptor.identity)
        if (destination.exists()) {
            return existingStageResult(
                destination,
                descriptor,
                canonicalManifestBytes,
                artifactSourcesByRole,
                nowEpochMillis,
            )
        }
        if (!hasCapacityFor(descriptor)) {
            return ModelPackageStageResult.Rejected(
                setOf(ModelPackageRejection.INSUFFICIENT_STORAGE),
            )
        }

        val stagedDirectory = stagingDirectory.resolve(UUID.randomUUID().toString())
        return try {
            stagedDirectory.mkdirsOrThrow()
            writeBytesAndForce(
                stagedDirectory.resolve(CANONICAL_MANIFEST_FILE),
                canonicalManifestBytes,
            )
            descriptor.artifacts.forEach { artifact ->
                val source = checkNotNull(artifactSourcesByRole[artifact.role])
                val destinationFile = stagedArtifactFile(stagedDirectory, artifact)
                destinationFile.parentFile?.mkdirsOrThrow()
                when (writeAndVerifySource(destinationFile, source, artifact)) {
                    SourceWriteResult.OK -> Unit
                    SourceWriteResult.SIZE_MISMATCH -> return ModelPackageStageResult.Rejected(
                        setOf(ModelPackageRejection.ARTIFACT_SOURCE_SIZE_MISMATCH),
                    )
                    SourceWriteResult.SHA256_MISMATCH -> return ModelPackageStageResult.Rejected(
                        setOf(ModelPackageRejection.ARTIFACT_SOURCE_SHA256_MISMATCH),
                    )
                    SourceWriteResult.IO_FAILURE -> return ModelPackageStageResult.Rejected(
                        setOf(ModelPackageRejection.IO_FAILURE),
                    )
                }
            }
            writeTextAndForce(
                stagedDirectory.resolve(PACKAGE_RECORD_FILE),
                encodePackageRecord(descriptor),
            )
            writeTextAndForce(
                stagedDirectory.resolve(GATE_REPORT_FILE),
                encodeGateReport(gateReport),
            )

            when (
                val verification = readPackageSnapshot(
                    directory = stagedDirectory,
                    expectedPointer = descriptor.pointer(),
                    nowEpochMillis = nowEpochMillis,
                )
            ) {
                is PackageRead.Rejected -> ModelPackageStageResult.Rejected(verification.reasons)
                is PackageRead.Ready -> {
                    destination.parentFile?.mkdirsOrThrow()
                    atomicMove(stagedDirectory, destination, replaceExisting = false)
                    ModelPackageStageResult.Staged(
                        descriptor = descriptor,
                        gateReport = gateReport,
                        alreadyPresent = false,
                    )
                }
            }
        } catch (_: AtomicMoveNotSupportedException) {
            ModelPackageStageResult.Rejected(
                setOf(ModelPackageRejection.ATOMIC_MOVE_UNAVAILABLE),
            )
        } catch (_: Exception) {
            ModelPackageStageResult.Rejected(setOf(ModelPackageRejection.IO_FAILURE))
        } finally {
            if (stagedDirectory.exists()) stagedDirectory.deleteRecursively()
        }
    }

    private fun existingStageResult(
        destination: File,
        descriptor: ModelPackageDescriptor,
        canonicalManifestBytes: ByteArray,
        artifactSourcesByRole: Map<String, InputStream>,
        nowEpochMillis: Long,
    ): ModelPackageStageResult {
        val existing = readPackageSnapshot(
            directory = destination,
            expectedPointer = descriptor.pointer(),
            nowEpochMillis = nowEpochMillis,
        )
        if (existing is PackageRead.Rejected) {
            // In particular, an expired persisted report must not be refreshed implicitly by a
            // prepare retry. Refresh needs its own atomic, auditable store operation.
            return ModelPackageStageResult.Rejected(
                setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
            )
        }
        existing as PackageRead.Ready
        val packageMetadataMatches = existing.snapshot.descriptor == descriptor &&
            existing.snapshot.canonicalManifest.copyBytes().contentEquals(
                canonicalManifestBytes,
            )
        if (!packageMetadataMatches) {
            return ModelPackageStageResult.Rejected(
                setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
            )
        }
        descriptor.artifacts.forEach { artifact ->
            when (verifySource(checkNotNull(artifactSourcesByRole[artifact.role]), artifact)) {
                SourceWriteResult.OK -> Unit
                SourceWriteResult.SIZE_MISMATCH,
                SourceWriteResult.SHA256_MISMATCH,
                -> return ModelPackageStageResult.Rejected(
                    setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
                )
                SourceWriteResult.IO_FAILURE -> return ModelPackageStageResult.Rejected(
                    setOf(ModelPackageRejection.IO_FAILURE),
                )
            }
        }

        // Gate reports are transient validation evidence, not immutable package content. Keep the
        // still-valid persisted report; accepting a newer report here must not mutate the package.
        return ModelPackageStageResult.Staged(
            descriptor = descriptor,
            gateReport = existing.snapshot.gateReport,
            alreadyPresent = true,
        )
    }

    fun activatePackage(
        slot: ModelPackageSlot,
        pointer: ModelPackagePointer,
        nowEpochMillis: Long,
    ): ModelPackageActivationResult {
        val activationGuard = tryAcquireActivationGuard()
            ?: return ModelPackageActivationResult.Rejected(
                setOf(ModelPackageRejection.MONITORING_ACTIVE),
            )
        activationGuard.use {
            return processMutationLock.withLock {
                withMutationFileLock {
                    val packageRead = readInstalledPackage(
                        pointer.identity,
                        pointer,
                        nowEpochMillis,
                    )
                    if (packageRead is PackageRead.Rejected) {
                        return@withMutationFileLock ModelPackageActivationResult.Rejected(
                            packageRead.reasons,
                        )
                    }
                    val descriptor = (packageRead as PackageRead.Ready).snapshot.descriptor
                    val stateRead = readStateOrRejection()
                    if (stateRead is StateRead.Rejected) {
                        return@withMutationFileLock ModelPackageActivationResult.Rejected(
                            setOf(stateRead.reason),
                        )
                    }
                    val states = (stateRead as StateRead.Ready).states
                    val state = states[slot] ?: ModelPackageActivationState(null, null)
                    check(descriptor.pointer() == pointer)
                    if (state.current == pointer) {
                        ModelPackageActivationResult.Activated(state, changed = false)
                    } else {
                        writeStates(
                            states + (slot to ModelPackageActivationState(
                                current = pointer,
                                previous = state.current,
                            )),
                            slot,
                        )
                    }
                }
            }
        }
    }

    fun rollback(
        slot: ModelPackageSlot,
        nowEpochMillis: Long,
    ): ModelPackageActivationResult {
        val activationGuard = tryAcquireActivationGuard()
            ?: return ModelPackageActivationResult.Rejected(
                setOf(ModelPackageRejection.MONITORING_ACTIVE),
            )
        activationGuard.use {
            return processMutationLock.withLock {
                withMutationFileLock {
                    val stateRead = readStateOrRejection()
                    if (stateRead is StateRead.Rejected) {
                        return@withMutationFileLock ModelPackageActivationResult.Rejected(
                            setOf(stateRead.reason),
                        )
                    }
                    val states = (stateRead as StateRead.Ready).states
                    val state = states[slot] ?: ModelPackageActivationState(null, null)
                    val previous = state.previous
                        ?: return@withMutationFileLock ModelPackageActivationResult.Rejected(
                            setOf(ModelPackageRejection.NO_ROLLBACK_PACKAGE),
                        )
                    val packageRead = readInstalledPackage(
                        identity = previous.identity,
                        expectedPointer = previous,
                        nowEpochMillis = nowEpochMillis,
                    )
                    if (packageRead is PackageRead.Rejected) {
                        return@withMutationFileLock ModelPackageActivationResult.Rejected(
                            packageRead.reasons,
                        )
                    }
                    writeStates(
                        states + (slot to ModelPackageActivationState(
                            current = previous,
                            previous = state.current,
                        )),
                        slot,
                    )
                }
            }
        }
    }

    fun activationState(slot: ModelPackageSlot): ModelPackageActivationState =
        processMutationLock.withLock {
            withMutationFileLock {
                when (val result = readStateOrRejection()) {
                    is StateRead.Ready ->
                        result.states[slot] ?: ModelPackageActivationState(null, null)
                    is StateRead.Rejected -> throw IllegalStateException(
                        "model package activation state is corrupt",
                    )
                }
            }
        }

    /**
     * Restores the exact state captured immediately before an activation whose task binding
     * failed. The compare-before-write guard prevents this compensating action from overwriting
     * a newer activation performed by another process.
     */
    fun restoreActivationStateAfterFailedBinding(
        slot: ModelPackageSlot,
        expectedActivatedState: ModelPackageActivationState,
        stateToRestore: ModelPackageActivationState,
    ): ModelPackageActivationResult {
        val activationGuard = tryAcquireActivationGuard()
            ?: return ModelPackageActivationResult.Rejected(
                setOf(ModelPackageRejection.MONITORING_ACTIVE),
            )
        activationGuard.use {
            return processMutationLock.withLock {
                withMutationFileLock {
                    val stateRead = readStateOrRejection()
                    if (stateRead is StateRead.Rejected) {
                        return@withMutationFileLock ModelPackageActivationResult.Rejected(
                            setOf(stateRead.reason),
                        )
                    }
                    val states = (stateRead as StateRead.Ready).states
                    val current = states[slot] ?: ModelPackageActivationState(null, null)
                    if (current != expectedActivatedState) {
                        return@withMutationFileLock ModelPackageActivationResult.Rejected(
                            setOf(ModelPackageRejection.STATE_CORRUPT),
                        )
                    }
                    if (current == stateToRestore) {
                        ModelPackageActivationResult.Activated(current, changed = false)
                    } else {
                        writeStates(states + (slot to stateToRestore), slot)
                    }
                }
            }
        }
    }

    /** Leases exactly the immutable package persisted in a task RuntimeSnapshot. */
    fun acquireRuntimeLease(
        pointer: ModelPackagePointer,
        nowEpochMillis: Long,
    ): ModelPackageRuntimeLeaseResult {
        val guard = tryAcquireActivationGuard()
            ?: return ModelPackageRuntimeLeaseResult.Rejected(
                setOf(ModelPackageRejection.MONITORING_ACTIVE),
            )
        return try {
            val result = processMutationLock.withLock {
                withMutationFileLock {
                    when (
                        val packageRead = readInstalledPackage(
                            identity = pointer.identity,
                            expectedPointer = pointer,
                            nowEpochMillis = nowEpochMillis,
                        )
                    ) {
                        is PackageRead.Rejected -> ModelPackageRuntimeLeaseResult.Rejected(
                            packageRead.reasons,
                        )
                        is PackageRead.Ready -> ModelPackageRuntimeLeaseResult.Acquired(
                            ModelPackageRuntimeLease(packageRead.snapshot, guard::close),
                        )
                    }
                }
            }
            if (result is ModelPackageRuntimeLeaseResult.Rejected) guard.close()
            result
        } catch (_: Exception) {
            guard.close()
            ModelPackageRuntimeLeaseResult.Rejected(setOf(ModelPackageRejection.IO_FAILURE))
        }
    }

    private fun readInstalledPackage(
        identity: ModelPackageIdentity,
        expectedPointer: ModelPackagePointer?,
        nowEpochMillis: Long,
    ): PackageRead {
        val directory = packageDirectory(identity)
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
            return PackageRead.Rejected(setOf(ModelPackageRejection.PACKAGE_NOT_INSTALLED))
        }
        val recordedDescriptor = readPackageRecord(directory.resolve(PACKAGE_RECORD_FILE))
            ?: return PackageRead.Rejected(
                setOf(ModelPackageRejection.PACKAGE_RECORD_CORRUPT),
            )
        if (recordedDescriptor.identity != identity) {
            return PackageRead.Rejected(setOf(ModelPackageRejection.PACKAGE_RECORD_CORRUPT))
        }
        return readPackageSnapshot(directory, expectedPointer, nowEpochMillis)
    }

    private fun readPackageSnapshot(
        directory: File,
        expectedPointer: ModelPackagePointer?,
        nowEpochMillis: Long,
    ): PackageRead {
        val descriptor = readPackageRecord(directory.resolve(PACKAGE_RECORD_FILE))
            ?: return PackageRead.Rejected(
                setOf(ModelPackageRejection.PACKAGE_RECORD_CORRUPT),
            )
        val reasons = linkedSetOf<ModelPackageRejection>()
        if (expectedPointer != null && descriptor.pointer() != expectedPointer) {
            reasons += ModelPackageRejection.POINTER_PACKAGE_MISMATCH
        }

        val manifestFile = directory.resolve(CANONICAL_MANIFEST_FILE)
        val manifestBytes = if (!manifestFile.isFile || Files.isSymbolicLink(manifestFile.toPath())) {
            reasons += ModelPackageRejection.MANIFEST_NOT_INSTALLED
            null
        } else {
            val manifestLength = manifestFile.length()
            if (manifestLength != descriptor.canonicalManifestSizeBytes ||
                manifestLength > MAX_CANONICAL_MANIFEST_BYTES
            ) {
                reasons += ModelPackageRejection.MANIFEST_SIZE_MISMATCH
            }
            val bytes = if (manifestLength in 0..MAX_CANONICAL_MANIFEST_BYTES) {
                runCatching { manifestFile.readBytes() }.getOrNull()
            } else {
                null
            }
            if (bytes == null) {
                if (manifestLength <= MAX_CANONICAL_MANIFEST_BYTES) {
                    reasons += ModelPackageRejection.IO_FAILURE
                }
            } else if (sha256(bytes) != descriptor.canonicalManifestSha256) {
                reasons += ModelPackageRejection.MANIFEST_SHA256_MISMATCH
            }
            bytes
        }

        val artifactsRoot = directory.resolve(ARTIFACTS_DIRECTORY)
        val expectedPaths = descriptor.artifacts.mapTo(linkedSetOf()) { it.relativePath }
        val actualPaths = if (
            artifactsRoot.isDirectory && !Files.isSymbolicLink(artifactsRoot.toPath())
        ) {
            artifactsRoot.walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(artifactsRoot).invariantSeparatorsPath }
                .toSet()
        } else {
            emptySet()
        }
        if (!actualPaths.containsAll(expectedPaths)) {
            reasons += ModelPackageRejection.ARTIFACT_NOT_INSTALLED
        }
        if (!expectedPaths.containsAll(actualPaths)) {
            reasons += ModelPackageRejection.UNEXPECTED_INSTALLED_ARTIFACT
        }

        val artifactFilesByRole = linkedMapOf<String, File>()
        descriptor.artifacts.forEach { artifact ->
            val file = installedArtifactFile(directory, artifact)
            artifactFilesByRole[artifact.role] = file
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
                reasons += ModelPackageRejection.ARTIFACT_NOT_INSTALLED
            } else {
                if (file.length() != artifact.sizeBytes) {
                    reasons += ModelPackageRejection.ARTIFACT_SIZE_MISMATCH
                }
                val actualSha256 = runCatching { file.inputStream().use(::sha256) }.getOrNull()
                if (actualSha256 == null || actualSha256 != artifact.sha256) {
                    reasons += ModelPackageRejection.ARTIFACT_SHA256_MISMATCH
                }
            }
        }

        val gateReport = readGateReport(directory.resolve(GATE_REPORT_FILE))
        if (gateReport == null) {
            reasons += ModelPackageRejection.GATE_REPORT_CORRUPT
        } else {
            reasons += gateReport.runtimeRejectionReasons(descriptor, nowEpochMillis)
        }
        if (reasons.isNotEmpty() || manifestBytes == null || gateReport == null) {
            return PackageRead.Rejected(reasons.ifEmpty { setOf(ModelPackageRejection.IO_FAILURE) })
        }

        return PackageRead.Ready(
            StoredPackageSnapshot(
                descriptor = descriptor,
                canonicalManifest = CanonicalManifestSnapshot(
                    descriptor.canonicalManifestSha256,
                    manifestBytes,
                ),
                artifactFilesByRole = Collections.unmodifiableMap(
                    LinkedHashMap(artifactFilesByRole),
                ),
                gateReport = gateReport,
            ),
        )
    }

    private fun hasCapacityFor(descriptor: ModelPackageDescriptor): Boolean {
        val installedBytes = directorySizeBytes(packagesDirectory) ?: return false
        val packageBytes = runCatching {
            Math.addExact(descriptor.contentSizeBytes, PACKAGE_METADATA_RESERVE_BYTES)
        }.getOrNull() ?: return false
        val totalAfterInstall = runCatching { Math.addExact(installedBytes, packageBytes) }
            .getOrNull() ?: return false
        val requiredFree = runCatching {
            Math.addExact(packageBytes, MIN_FREE_BYTES_AFTER_STAGING)
        }.getOrNull() ?: return false
        return totalAfterInstall <= MAX_MODEL_STORE_BYTES && usableSpaceBytes() >= requiredFree
    }

    private fun packageDirectory(identity: ModelPackageIdentity): File = packagesDirectory
        .resolve(identity.packageId)
        .resolve(identity.packageVersion)

    private fun stagedArtifactFile(
        stagedPackageDirectory: File,
        descriptor: ModelPackageArtifactDescriptor,
    ): File = stagedPackageDirectory.resolve(ARTIFACTS_DIRECTORY).resolve(descriptor.relativePath)

    private fun installedArtifactFile(
        packageDirectory: File,
        descriptor: ModelPackageArtifactDescriptor,
    ): File = packageDirectory.resolve(ARTIFACTS_DIRECTORY).resolve(descriptor.relativePath)

    private fun readStateOrRejection(): StateRead {
        if (!stateFile.exists()) {
            return StateRead.Ready(emptyMap())
        }
        return runCatching {
            require(stateFile.isFile && !Files.isSymbolicLink(stateFile.toPath()))
            require(stateFile.length() <= MAX_RECORD_FILE_BYTES)
            val lines = stateFile.readLines(StandardCharsets.UTF_8)
            when (lines.firstOrNull()) {
                STATE_SCHEMA -> lines.drop(1).associate { line ->
                    val fields = line.split('\t')
                    require(fields.size == 8 && fields[0] == "slot")
                    val slot = ModelPackageSlot(fields[1])
                    slot to ModelPackageActivationState(
                        current = decodePointerFields(fields.subList(2, 5)),
                        previous = decodePointerFields(fields.subList(5, 8)),
                    )
                }.also { states ->
                    require(states.size == lines.size - 1) { "duplicate model package slot" }
                }
                else -> error("unknown activation state schema")
            }
        }.fold(
            onSuccess = StateRead::Ready,
            onFailure = { StateRead.Rejected(ModelPackageRejection.STATE_CORRUPT) },
        )
    }

    private fun writeStates(
        states: Map<ModelPackageSlot, ModelPackageActivationState>,
        changedSlot: ModelPackageSlot,
    ): ModelPackageActivationResult {
        val temporary = stateDirectory.resolve("${stateFile.name}.${UUID.randomUUID()}.tmp")
        return try {
            writeTextAndForce(
                temporary,
                buildString {
                    appendLine(STATE_SCHEMA)
                    states.entries.sortedBy { it.key.slotId }.forEach { (slot, state) ->
                        append("slot\t").append(slot.slotId).append('\t')
                        append(encodePointerFields(state.current)).append('\t')
                        appendLine(encodePointerFields(state.previous))
                    }
                },
            )
            atomicMove(temporary, stateFile, replaceExisting = true)
            ModelPackageActivationResult.Activated(
                checkNotNull(states[changedSlot]),
                changed = true,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            ModelPackageActivationResult.Rejected(
                setOf(ModelPackageRejection.ATOMIC_MOVE_UNAVAILABLE),
            )
        } catch (_: Exception) {
            ModelPackageActivationResult.Rejected(setOf(ModelPackageRejection.IO_FAILURE))
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun encodePointerFields(pointer: ModelPackagePointer?): String = pointer?.let {
        listOf(
            it.identity.packageId,
            it.identity.packageVersion,
            it.canonicalManifestSha256,
        ).joinToString("\t")
    } ?: "-\t-\t-"

    private fun decodePointerFields(fields: List<String>): ModelPackagePointer? {
        require(fields.size == 3)
        if (fields == listOf("-", "-", "-")) return null
        require("-" !in fields)
        return ModelPackagePointer(
            identity = ModelPackageIdentity(fields[0], fields[1]),
            canonicalManifestSha256 = fields[2],
        )
    }

    private fun encodePackageRecord(descriptor: ModelPackageDescriptor): String = buildString {
        appendLine(PACKAGE_RECORD_SCHEMA)
        append("package_id=").appendLine(descriptor.identity.packageId)
        append("package_version=").appendLine(descriptor.identity.packageVersion)
        append("manifest_sha256=").appendLine(descriptor.canonicalManifestSha256)
        append("manifest_size_bytes=").appendLine(descriptor.canonicalManifestSizeBytes)
        append("artifact_count=").appendLine(descriptor.artifacts.size)
        descriptor.artifacts.forEach { artifact ->
            append("artifact=")
            append(artifact.role).append('\t')
            append(artifact.relativePath).append('\t')
            append(artifact.sha256).append('\t')
            appendLine(artifact.sizeBytes)
        }
    }

    private fun readPackageRecord(file: File): ModelPackageDescriptor? = runCatching {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()))
        require(file.length() <= MAX_RECORD_FILE_BYTES)
        val lines = file.readLines(StandardCharsets.UTF_8)
        require(lines.firstOrNull() == PACKAGE_RECORD_SCHEMA)
        require(lines.size >= 6)
        val fixedEntries = lines.subList(1, 6).associateStrictly()
        require(
            fixedEntries.keys == setOf(
                "package_id",
                "package_version",
                "manifest_sha256",
                "manifest_size_bytes",
                "artifact_count",
            ),
        )
        val artifactCount = fixedEntries.getValue("artifact_count").toInt()
        require(artifactCount > 0)
        require(lines.size == 6 + artifactCount)
        val artifacts = lines.drop(6).map { line ->
            require(line.startsWith("artifact="))
            val fields = line.removePrefix("artifact=").split('\t')
            require(fields.size == 4)
            ModelPackageArtifactDescriptor(
                role = fields[0],
                relativePath = fields[1],
                sha256 = fields[2],
                sizeBytes = fields[3].toLong(),
            )
        }
        ModelPackageDescriptor(
            identity = ModelPackageIdentity(
                fixedEntries.getValue("package_id"),
                fixedEntries.getValue("package_version"),
            ),
            canonicalManifestSha256 = fixedEntries.getValue("manifest_sha256"),
            canonicalManifestSizeBytes = fixedEntries.getValue("manifest_size_bytes").toLong(),
            artifacts = artifacts,
        )
    }.getOrNull()

    private fun encodeGateReport(report: ModelPackageGateReport): String = buildString {
        appendLine(GATE_REPORT_SCHEMA)
        append("report_id=").appendLine(report.reportId)
        append("evidence_ref=").appendLine(report.evidenceRef)
        append("package_descriptor_sha256=").appendLine(report.packageDescriptorSha256)
        append("catalog_entry_active=").appendLine(report.catalogEntryActive)
        append("catalog_signature_valid=").appendLine(report.catalogSignatureValid)
        append("catalog_manifest_hash_valid=").appendLine(report.catalogManifestHashValid)
        append("manifest_signature_valid=").appendLine(report.manifestSignatureValid)
        append("license_gate_passed=").appendLine(report.licenseGatePassed)
        append("metadata_compatible=").appendLine(report.metadataCompatible)
        append("device_compatible=").appendLine(report.deviceCompatible)
        append("self_test_passed=").appendLine(report.selfTestPassed)
        append("validated_at_epoch_millis=").appendLine(report.validatedAtEpochMillis)
        append("catalog_fresh_until_epoch_millis=")
            .appendLine(report.catalogFreshUntilEpochMillis)
        append("run_valid_until_epoch_millis=").appendLine(report.runValidUntilEpochMillis)
    }

    private fun readGateReport(file: File): ModelPackageGateReport? = runCatching {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()))
        require(file.length() <= MAX_RECORD_FILE_BYTES)
        val lines = file.readLines(StandardCharsets.UTF_8)
        require(lines.firstOrNull() == GATE_REPORT_SCHEMA)
        require(lines.size == 15)
        val entries = lines.drop(1).associateStrictly()
        require(
            entries.keys == setOf(
                "report_id",
                "evidence_ref",
                "package_descriptor_sha256",
                "catalog_entry_active",
                "catalog_signature_valid",
                "catalog_manifest_hash_valid",
                "manifest_signature_valid",
                "license_gate_passed",
                "metadata_compatible",
                "device_compatible",
                "self_test_passed",
                "validated_at_epoch_millis",
                "catalog_fresh_until_epoch_millis",
                "run_valid_until_epoch_millis",
            ),
        )
        ModelPackageGateReport(
            reportId = entries.getValue("report_id"),
            evidenceRef = entries.getValue("evidence_ref"),
            packageDescriptorSha256 = entries.getValue("package_descriptor_sha256"),
            catalogEntryActive = entries.getValue("catalog_entry_active").toStrictBoolean(),
            catalogSignatureValid = entries.getValue("catalog_signature_valid").toStrictBoolean(),
            catalogManifestHashValid = entries.getValue(
                "catalog_manifest_hash_valid",
            ).toStrictBoolean(),
            manifestSignatureValid = entries.getValue(
                "manifest_signature_valid",
            ).toStrictBoolean(),
            licenseGatePassed = entries.getValue("license_gate_passed").toStrictBoolean(),
            metadataCompatible = entries.getValue("metadata_compatible").toStrictBoolean(),
            deviceCompatible = entries.getValue("device_compatible").toStrictBoolean(),
            selfTestPassed = entries.getValue("self_test_passed").toStrictBoolean(),
            validatedAtEpochMillis = entries.getValue(
                "validated_at_epoch_millis",
            ).toLong(),
            catalogFreshUntilEpochMillis = entries.getValue(
                "catalog_fresh_until_epoch_millis",
            ).toLong(),
            runValidUntilEpochMillis = entries.getValue("run_valid_until_epoch_millis").toLong(),
        )
    }.getOrNull()

    private fun writeAndVerifySource(
        destination: File,
        source: InputStream,
        descriptor: ModelPackageArtifactDescriptor,
    ): SourceWriteResult = try {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val remainingPlusSentinel = descriptor.sizeBytes - written + 1
                val maximumRead = minOf(buffer.size.toLong(), remainingPlusSentinel)
                    .coerceAtLeast(1)
                    .toInt()
                val count = source.read(buffer, 0, maximumRead)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                written += count
                if (written > descriptor.sizeBytes) return SourceWriteResult.SIZE_MISMATCH
            }
            output.flush()
            output.channel.force(true)
        }
        when {
            written != descriptor.sizeBytes -> SourceWriteResult.SIZE_MISMATCH
            digest.digest().toHex() != descriptor.sha256 -> SourceWriteResult.SHA256_MISMATCH
            else -> SourceWriteResult.OK
        }
    } catch (_: Exception) {
        SourceWriteResult.IO_FAILURE
    }

    private fun verifySource(
        source: InputStream,
        descriptor: ModelPackageArtifactDescriptor,
    ): SourceWriteResult = try {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val remainingPlusSentinel = descriptor.sizeBytes - read + 1
            val maximumRead = minOf(buffer.size.toLong(), remainingPlusSentinel)
                .coerceAtLeast(1)
                .toInt()
            val count = source.read(buffer, 0, maximumRead)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            read += count
            if (read > descriptor.sizeBytes) return SourceWriteResult.SIZE_MISMATCH
        }
        when {
            read != descriptor.sizeBytes -> SourceWriteResult.SIZE_MISMATCH
            digest.digest().toHex() != descriptor.sha256 -> SourceWriteResult.SHA256_MISMATCH
            else -> SourceWriteResult.OK
        }
    } catch (_: Exception) {
        SourceWriteResult.IO_FAILURE
    }

    private fun atomicMove(
        source: File,
        destination: File,
        replaceExisting: Boolean,
    ) {
        beforeAtomicMove(source, destination)
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(source.toPath(), destination.toPath(), *options)
    }

    private fun <T> withMutationFileLock(block: () -> T): T {
        RandomAccessFile(mutationLockFile, "rw").channel.use { channel ->
            channel.lock().use { return block() }
        }
    }

    private fun tryAcquireActivationGuard(): ActivationGuard? {
        if (!processActivationPermit.tryAcquire()) return null
        val channel = runCatching { RandomAccessFile(activationLockFile, "rw").channel }
            .getOrElse {
                processActivationPermit.release()
                return null
            }
        val fileLock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Exception) {
            null
        }
        if (fileLock == null) {
            channel.close()
            processActivationPermit.release()
            return null
        }
        return ActivationGuard(fileLock, channel, processActivationPermit)
    }

    private sealed interface StateRead {
        data class Ready(val states: Map<ModelPackageSlot, ModelPackageActivationState>) : StateRead
        data class Rejected(val reason: ModelPackageRejection) : StateRead
    }

    private sealed interface PackageRead {
        data class Ready(val snapshot: StoredPackageSnapshot) : PackageRead
        data class Rejected(val reasons: Set<ModelPackageRejection>) : PackageRead
    }

    private enum class SourceWriteResult {
        OK,
        SIZE_MISMATCH,
        SHA256_MISMATCH,
        IO_FAILURE,
    }

    companion object {
        private const val STATE_SCHEMA = "be-your-eyes-model-state-v3"
        private const val PACKAGE_RECORD_SCHEMA = "be-your-eyes-model-package-v2"
        private const val GATE_REPORT_SCHEMA = "be-your-eyes-model-gate-report-v3"
        private const val CANONICAL_MANIFEST_FILE = "manifest.canonical.json"
        private const val PACKAGE_RECORD_FILE = "package-record.tsv"
        private const val GATE_REPORT_FILE = "gate-report.tsv"
        private const val ARTIFACTS_DIRECTORY = "artifacts"
    }
}

internal data class StoredPackageSnapshot(
    val descriptor: ModelPackageDescriptor,
    val canonicalManifest: CanonicalManifestSnapshot,
    val artifactFilesByRole: Map<String, File>,
    val gateReport: ModelPackageGateReport,
)

class ModelPackageRuntimeLease internal constructor(
    snapshot: StoredPackageSnapshot,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val descriptor: ModelPackageDescriptor = snapshot.descriptor
    val canonicalManifest: CanonicalManifestSnapshot = snapshot.canonicalManifest
    val artifactFilesByRole: Map<String, File> = snapshot.artifactFilesByRole
    val gateReport: ModelPackageGateReport = snapshot.gateReport

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

private class ActivationGuard(
    private val fileLock: FileLock,
    private val channel: FileChannel,
    private val processPermit: Semaphore,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { fileLock.release() }
        runCatching { channel.close() }
        processPermit.release()
    }
}

private object ProcessStoreLocks {
    private val mutationLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val activationPermits = ConcurrentHashMap<String, Semaphore>()

    fun mutationLock(root: String): ReentrantLock = mutationLocks.computeIfAbsent(root) {
        ReentrantLock()
    }

    fun activationPermit(root: String): Semaphore = activationPermits.computeIfAbsent(root) {
        Semaphore(1, true)
    }
}

private fun isSafeArtifactPath(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_ARTIFACT_RELATIVE_PATH_LENGTH) return false
    if (value.startsWith('/') || value.startsWith('\\') || '\\' in value) return false
    val segments = value.split('/')
    return segments.all { segment -> ARTIFACT_PATH_SEGMENT_PATTERN.matches(segment) }
}

private fun isSafeRecordValue(value: String): Boolean =
    value.isNotEmpty() && value.length <= MAX_RECORD_VALUE_LENGTH &&
        value.none { it == '\t' || it == '\r' || it == '\n' || it.code == 0 }

private fun List<String>.associateStrictly(): Map<String, String> {
    val entries = map { line ->
        val separator = line.indexOf('=')
        require(separator > 0)
        line.substring(0, separator) to line.substring(separator + 1)
    }
    require(entries.map { it.first }.distinct().size == entries.size)
    return entries.toMap()
}

private fun String.toStrictBoolean(): Boolean = when (this) {
    "true" -> true
    "false" -> false
    else -> error("invalid boolean")
}

private fun File.mkdirsOrThrow() {
    check(isDirectory || mkdirs()) { "failed to create directory $this" }
}

private fun deleteRecursivelyOrThrow(file: File) {
    check(!file.exists() || file.deleteRecursively()) { "failed to remove stale path $file" }
}

private fun writeBytesAndForce(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirsOrThrow()
    FileOutputStream(file).use { output ->
        output.write(bytes)
        output.flush()
        output.channel.force(true)
    }
}

private fun writeTextAndForce(file: File, value: String) {
    writeBytesAndForce(file, value.toByteArray(StandardCharsets.UTF_8))
}

private fun directorySizeBytes(directory: File): Long? = runCatching {
    var total = 0L
    directory.walkTopDown().filter(File::isFile).forEach { file ->
        total = Math.addExact(total, file.length())
    }
    total
}.getOrNull()

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().toHex()
}

private fun sha256(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private const val MAX_CANONICAL_MANIFEST_BYTES = 4L * 1024L * 1024L
private const val MAX_MODEL_ARTIFACT_BYTES = 2L * 1024L * 1024L * 1024L
private const val MAX_MODEL_PACKAGE_BYTES = 4L * 1024L * 1024L * 1024L
private const val MAX_MODEL_STORE_BYTES = 4L * 1024L * 1024L * 1024L
private const val MIN_FREE_BYTES_AFTER_STAGING = 64L * 1024L * 1024L
private const val PACKAGE_METADATA_RESERVE_BYTES = 64L * 1024L
private const val MAX_ARTIFACT_RELATIVE_PATH_LENGTH = 512
private const val MAX_RECORD_VALUE_LENGTH = 1024
private const val MAX_RECORD_FILE_BYTES = 1024L * 1024L
