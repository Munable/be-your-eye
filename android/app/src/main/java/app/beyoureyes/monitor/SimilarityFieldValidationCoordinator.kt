package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ModelPackageRuntimeLease
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntime
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.ReferenceEmbeddingCaches
import app.beyoureyes.core.vision.ReferenceImageProvider
import app.beyoureyes.core.vision.RuntimeActivationError
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.VerifiedModelPackage
import java.io.File

/**
 * A live frame already cropped to the exact production ROI, made upright, and converted to RGB.
 *
 * The wrapper does not retain or persist pixels beyond the synchronous [accept] call. A future
 * CameraX UI adapter must create it from the production ROI-normalization path; reference material
 * is never accepted as a field-check frame.
 */
internal data class NormalizedRoiSourceFrame(
    val roi: NormalizedRect,
    val sourceFrame: SourceFrame,
) {
    fun contractFailureOrNull(): SimilarityFieldValidationFailureReason? {
        val pixels = sourceFrame.pixels as? FramePixels.Rgb888
            ?: return SimilarityFieldValidationFailureReason.FRAME_NOT_NORMALIZED_RGB
        if (sourceFrame.rotationDegrees != 0 ||
            sourceFrame.cropRect != PixelRect(0, 0, sourceFrame.width, sourceFrame.height)
        ) {
            return SimilarityFieldValidationFailureReason.FRAME_NOT_UPRIGHT_ROI
        }
        val expectedRowStride = sourceFrame.width * RGB_CHANNELS
        if (pixels.rowStride != expectedRowStride ||
            pixels.bytes.size != expectedRowStride * sourceFrame.height
        ) {
            return SimilarityFieldValidationFailureReason.FRAME_NOT_TIGHTLY_PACKED
        }
        return null
    }

    private companion object {
        const val RGB_CHANNELS = 3
    }
}

internal enum class SimilarityFieldValidationFailureReason {
    PACKAGE_UNAVAILABLE,
    MANIFEST_INVALID,
    BUILD_CHANNEL_INVALID,
    RUNTIME_FAMILY_MISMATCH,
    TARGET_PROFILE_MISMATCH,
    RUNTIME_INCOMPATIBLE,
    RUNTIME_INITIALIZATION_FAILED,
    FRAME_NOT_NORMALIZED_RGB,
    FRAME_NOT_UPRIGHT_ROI,
    FRAME_NOT_TIGHTLY_PACKED,
    RUNTIME_PROCESSING_FAILED,
    INCOMPATIBLE_OBSERVATION,
    SOURCE_SEQUENCE_INVALID,
    SESSION_CLOSED,
}

internal enum class SimilarityFieldValidationInvalidationReason {
    TASK_IDENTITY_CHANGED,
    LEASE_PACKAGE_CHANGED,
    FRAME_ROI_CHANGED,
}

/** Media-free state intended to be mapped directly onto setup UI copy and progress. */
internal sealed interface SimilarityFieldValidationUiState {
    val expectedIdentity: SimilarityFieldValidationIdentity
    val summary: SimilarityFieldValidationSummary

    data class Checking(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
    ) : SimilarityFieldValidationUiState

    data class Passed(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
        val stableEpisodeId: Long,
    ) : SimilarityFieldValidationUiState {
        init {
            require(stableEpisodeId > 0)
        }
    }

    data class NotFound(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
    ) : SimilarityFieldValidationUiState

    data class Failed(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
        val reason: SimilarityFieldValidationFailureReason,
        val diagnosticCodes: Set<String> = emptySet(),
    ) : SimilarityFieldValidationUiState {
        init {
            require(diagnosticCodes.all(String::isNotBlank))
        }
    }

    data class Invalidated(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        override val summary: SimilarityFieldValidationSummary,
        val currentIdentity: SimilarityFieldValidationIdentity,
        val changes: Set<SimilarityFieldValidationIdentityChange>,
        val reason: SimilarityFieldValidationInvalidationReason,
    ) : SimilarityFieldValidationUiState {
        init {
            require(changes.isNotEmpty())
        }
    }
}

/** Minimal lease-bound runtime seam; production owns a real runtime and exact store lease. */
internal interface SimilarityFieldValidationRuntimeSession : AutoCloseable {
    val packagePointer: ModelPackagePointer
    fun process(frame: SourceFrame): RuntimeFrameResult
}

internal sealed interface SimilarityFieldValidationSessionOpenResult {
    data class Ready(
        val session: SimilarityFieldValidationRuntimeSession,
    ) : SimilarityFieldValidationSessionOpenResult

    data class Failed(
        val reason: SimilarityFieldValidationFailureReason,
        val diagnosticCodes: Set<String> = emptySet(),
    ) : SimilarityFieldValidationSessionOpenResult
}

/** Foreground setup uses the fastest cadence explicitly authorized by the signed package. */
internal fun setupFieldValidationSamplingPolicy(
    signedMinimumIntervalMillis: Long?,
): FrameSamplingPolicy = FrameSamplingPolicy(
    checkNotNull(signedMinimumIntervalMillis) {
        "signed minimum setup sampling interval is unavailable"
    },
)

/**
 * Owns one exact package lease and one live-only runtime for an elapsed-time field check.
 *
 * The coordinator never loads a reference image as a field frame and never writes live frame
 * bytes. A pass keeps the session alive so live setup feedback can revoke stale confirmation when
 * the target leaves or the frame becomes unavailable. Identity changes and failures remain
 * terminal, so a result cannot be reused across task edits, package replacement, or ROI movement.
 */
internal class SimilarityFieldValidationCoordinator private constructor(
    private val identity: SimilarityFieldValidationIdentity,
    openResult: SimilarityFieldValidationSessionOpenResult,
) : AutoCloseable {
    private val reducer = SimilarityFieldValidationReducer(identity)
    private var session = (openResult as? SimilarityFieldValidationSessionOpenResult.Ready)?.session
    private var closed = false

    var state: SimilarityFieldValidationUiState = when (openResult) {
        is SimilarityFieldValidationSessionOpenResult.Ready -> mapReducerState(reducer.state)
        is SimilarityFieldValidationSessionOpenResult.Failed -> SimilarityFieldValidationUiState.Failed(
            expectedIdentity = identity,
            summary = emptySummary(),
            reason = openResult.reason,
            diagnosticCodes = openResult.diagnosticCodes,
        )
    }
        private set

    /** Latest live runtime output for transient setup feedback; pixels are never retained. */
    var latestObservation: Observation? = null
        private set

    /** Checks task/package/ROI identity even when no new camera frame is available. */
    fun ensureIdentity(
        currentIdentity: SimilarityFieldValidationIdentity,
    ): SimilarityFieldValidationUiState {
        if (state is SimilarityFieldValidationUiState.Invalidated) return state
        val taskState = reducer.ensureIdentity(currentIdentity)
        if (taskState is SimilarityFieldValidationState.Invalidated) {
            return invalidate(taskState, SimilarityFieldValidationInvalidationReason.TASK_IDENTITY_CHANGED)
        }
        val currentSession = session ?: return state
        val leasePointer = try {
            currentSession.packagePointer
        } catch (_: Exception) {
            return fail(SimilarityFieldValidationFailureReason.RUNTIME_PROCESSING_FAILED)
        }
        if (leasePointer != identity.packagePointer) {
            val changed = identity.copy(packagePointer = leasePointer)
            val leaseState = reducer.ensureIdentity(changed)
                as SimilarityFieldValidationState.Invalidated
            return invalidate(
                leaseState,
                SimilarityFieldValidationInvalidationReason.LEASE_PACKAGE_CHANGED,
            )
        }
        return state
    }

    /** Synchronous and media-free: the frame is passed through and never retained. */
    fun accept(
        currentIdentity: SimilarityFieldValidationIdentity,
        frame: NormalizedRoiSourceFrame,
    ): SimilarityFieldValidationUiState {
        ensureIdentity(currentIdentity)
        if (closed) return state
        if (state !is SimilarityFieldValidationUiState.Checking &&
            state !is SimilarityFieldValidationUiState.NotFound &&
            state !is SimilarityFieldValidationUiState.Passed
        ) return state
        if (frame.roi != identity.roi) {
            val changed = identity.copy(roi = frame.roi)
            val invalidated = reducer.ensureIdentity(changed)
                as SimilarityFieldValidationState.Invalidated
            return invalidate(
                invalidated,
                SimilarityFieldValidationInvalidationReason.FRAME_ROI_CHANGED,
            )
        }
        frame.contractFailureOrNull()?.let { return fail(it) }
        val runtimeResult = try {
            checkNotNull(session).process(frame.sourceFrame)
        } catch (_: Exception) {
            return fail(SimilarityFieldValidationFailureReason.RUNTIME_PROCESSING_FAILED)
        }
        if (runtimeResult is RuntimeFrameResult.Processed) {
            latestObservation = runtimeResult.pipelineResult.observation
        }
        state = try {
            mapReducerState(reducer.accept(identity, runtimeResult))
        } catch (_: Exception) {
            return fail(SimilarityFieldValidationFailureReason.RUNTIME_PROCESSING_FAILED)
        }
        if (state is SimilarityFieldValidationUiState.Failed ||
            state is SimilarityFieldValidationUiState.Invalidated
        ) closeResources()
        return state
    }

    override fun close() {
        if (closed) return
        if (state is SimilarityFieldValidationUiState.Checking ||
            state is SimilarityFieldValidationUiState.NotFound ||
            state is SimilarityFieldValidationUiState.Passed
        ) {
            state = SimilarityFieldValidationUiState.Failed(
                expectedIdentity = identity,
                summary = state.summary,
                reason = SimilarityFieldValidationFailureReason.SESSION_CLOSED,
            )
        }
        closeResources()
    }

    private fun invalidate(
        reducerState: SimilarityFieldValidationState.Invalidated,
        reason: SimilarityFieldValidationInvalidationReason,
    ): SimilarityFieldValidationUiState.Invalidated = SimilarityFieldValidationUiState.Invalidated(
        expectedIdentity = reducerState.expectedIdentity,
        summary = reducerState.summary,
        currentIdentity = reducerState.currentIdentity,
        changes = reducerState.changes,
        reason = reason,
    ).also {
        state = it
        closeResources()
    }

    private fun fail(
        reason: SimilarityFieldValidationFailureReason,
        diagnosticCodes: Set<String> = emptySet(),
    ): SimilarityFieldValidationUiState.Failed = SimilarityFieldValidationUiState.Failed(
        expectedIdentity = identity,
        summary = state.summary,
        reason = reason,
        diagnosticCodes = diagnosticCodes,
    ).also {
        state = it
        closeResources()
    }

    private fun mapReducerState(
        value: SimilarityFieldValidationState,
    ): SimilarityFieldValidationUiState = when (value) {
        is SimilarityFieldValidationState.Collecting -> SimilarityFieldValidationUiState.Checking(
            value.expectedIdentity,
            value.summary,
        )
        is SimilarityFieldValidationState.Passed -> SimilarityFieldValidationUiState.Passed(
            value.expectedIdentity,
            value.summary,
            value.stableEpisodeId,
        )
        is SimilarityFieldValidationState.NotFound -> SimilarityFieldValidationUiState.NotFound(
            value.expectedIdentity,
            value.summary,
        )
        is SimilarityFieldValidationState.Failed -> SimilarityFieldValidationUiState.Failed(
            expectedIdentity = value.expectedIdentity,
            summary = value.summary,
            reason = when (value.failure) {
                SimilarityFieldValidationFailure.INCOMPATIBLE_OBSERVATION ->
                    SimilarityFieldValidationFailureReason.INCOMPATIBLE_OBSERVATION
                SimilarityFieldValidationFailure.SOURCE_SEQUENCE_INVALID ->
                    SimilarityFieldValidationFailureReason.SOURCE_SEQUENCE_INVALID
            },
            diagnosticCodes = value.summary.unavailableDiagnosticCodes.keys,
        )
        is SimilarityFieldValidationState.Invalidated -> SimilarityFieldValidationUiState.Invalidated(
            expectedIdentity = value.expectedIdentity,
            summary = value.summary,
            currentIdentity = value.currentIdentity,
            changes = value.changes,
            reason = SimilarityFieldValidationInvalidationReason.TASK_IDENTITY_CHANGED,
        )
    }

    private fun closeResources() {
        if (closed) return
        closed = true
        latestObservation = null
        val owned = session
        session = null
        runCatching { owned?.close() }
    }

    companion object {
        fun openAppPrivate(
            filesDir: File,
            identity: SimilarityFieldValidationIdentity,
            targetProfile: TargetProfile,
            referenceImageProvider: ReferenceImageProvider? = null,
            nowEpochMillis: () -> Long = System::currentTimeMillis,
        ): SimilarityFieldValidationCoordinator {
            val buildChannel = BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)
                ?: return fromOpenResult(
                    identity,
                    SimilarityFieldValidationSessionOpenResult.Failed(
                        SimilarityFieldValidationFailureReason.BUILD_CHANNEL_INVALID,
                    ),
                )
            return fromOpenResult(
                identity,
                ProductionSimilarityFieldValidationSession.open(
                    filesDir = filesDir,
                    identity = identity,
                    targetProfile = targetProfile,
                    referenceImageProvider = referenceImageProvider,
                    buildChannel = buildChannel,
                    nowEpochMillis = nowEpochMillis,
                ),
            )
        }

        internal fun fromOpenResult(
            identity: SimilarityFieldValidationIdentity,
            result: SimilarityFieldValidationSessionOpenResult,
        ) = SimilarityFieldValidationCoordinator(identity, result)

        private fun emptySummary() = SimilarityFieldValidationSummary(
            processedFrames = 0,
            presentFrames = 0,
            absentFrames = 0,
            unavailableFrames = 0,
            unavailableReasons = emptyMap(),
            unavailableDiagnosticCodes = emptyMap(),
            latestSignal = null,
        )
    }
}

private class ProductionSimilarityFieldValidationSession private constructor(
    private val runtime: ModelPackageRuntime,
    private val lease: ModelPackageRuntimeLease,
    override val packagePointer: ModelPackagePointer,
) : SimilarityFieldValidationRuntimeSession {
    private var closed = false

    override fun process(frame: SourceFrame): RuntimeFrameResult {
        check(!closed) { "field validation session is closed" }
        return runtime.process(frame)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            runtime.close()
        } finally {
            lease.close()
        }
    }

    companion object {
        fun open(
            filesDir: File,
            identity: SimilarityFieldValidationIdentity,
            targetProfile: TargetProfile,
            referenceImageProvider: ReferenceImageProvider? = null,
            buildChannel: BuildChannel,
            nowEpochMillis: () -> Long,
        ): SimilarityFieldValidationSessionOpenResult {
            if (targetProfile.targetId != identity.targetId) {
                return failed(SimilarityFieldValidationFailureReason.TARGET_PROFILE_MISMATCH)
            }
            if (buildChannel == BuildChannel.DEVELOPMENT_NO_MODEL) {
                return failed(SimilarityFieldValidationFailureReason.BUILD_CHANNEL_INVALID)
            }
            val lease = when (
                val acquisition = runCatching {
                    ModelPackageStores.open(filesDir).acquireRuntimeLease(
                        identity.packagePointer,
                        nowEpochMillis(),
                    )
                }.getOrNull()
            ) {
                is ModelPackageRuntimeLeaseResult.Acquired -> acquisition.lease
                is ModelPackageRuntimeLeaseResult.Rejected -> return failed(
                    SimilarityFieldValidationFailureReason.PACKAGE_UNAVAILABLE,
                    acquisition.reasons.mapTo(linkedSetOf()) { "package_${it.name.lowercase()}" },
                )
                null -> return failed(SimilarityFieldValidationFailureReason.PACKAGE_UNAVAILABLE)
            }
            var runtime: ModelPackageRuntime? = null
            try {
                val leasePointer = ModelPackagePointer(
                    identity = lease.descriptor.identity,
                    canonicalManifestSha256 = lease.descriptor.canonicalManifestSha256,
                )
                if (leasePointer != identity.packagePointer) {
                    lease.close()
                    return failed(SimilarityFieldValidationFailureReason.PACKAGE_UNAVAILABLE)
                }
                val manifest = try {
                    SignedMetadataCodec.decodeStoredManifestDocument(
                        documentBytes = lease.canonicalManifest.copyBytes(),
                        expectedIdentity = lease.descriptor.identity,
                        expectedSha256 = lease.descriptor.canonicalManifestSha256,
                    )
                } catch (_: Exception) {
                    lease.close()
                    return failed(SimilarityFieldValidationFailureReason.MANIFEST_INVALID)
                }
                val expectedFamily = when (targetProfile) {
                    is TargetProfile.ReferenceImages -> RecipeFamily.SIMILARITY_MATCH_V1
                    is TargetProfile.ObjectClass -> RecipeFamily.OBJECT_DETECTION_V1
                }
                if (manifest.runtimeFamily != expectedFamily) {
                    lease.close()
                    return failed(SimilarityFieldValidationFailureReason.RUNTIME_FAMILY_MISMATCH)
                }
                val verifiedPackage = VerifiedModelPackage(
                    manifest = manifest,
                    catalogEntryActive = lease.gateReport.catalogEntryActive,
                    catalogSignatureValid = lease.gateReport.catalogSignatureValid,
                    catalogManifestSha256Matches = lease.gateReport.catalogManifestHashValid,
                    manifestSignatureValid = lease.gateReport.manifestSignatureValid,
                    artifactSha256Valid = true,
                    licenseTextSha256Valid = lease.gateReport.licenseGatePassed,
                )
                val registry = ManifestRuntimeComponents.registry(
                    referenceImageProvider = referenceImageProvider,
                    referenceEmbeddingCache = ReferenceEmbeddingCaches.openAppPrivate(filesDir),
                )
                val creation = ModelPackageRuntimeFactory(
                    registry = registry,
                    normalizer = UprightRgbFrameNormalizer,
                    qualityGate = ManifestFrameQualityGates.forManifest(manifest),
                    nowEpochMillis = nowEpochMillis,
                ).create(
                    ModelRuntimeRequest(
                        verifiedPackage = verifiedPackage,
                        artifactFile = lease.artifactFilesByRole.getValue("primary"),
                        artifactFilesByRole = lease.artifactFilesByRole,
                        buildChannel = buildChannel,
                        targetProfile = targetProfile,
                        // Setup is foreground and work-conserving: inspect the latest available
                        // frame at the package-signed minimum. Monitoring retains its persisted
                        // long-run cadence and is intentionally unaffected by this override.
                        samplingPolicyOverride = setupFieldValidationSamplingPolicy(
                            manifest.parameterProfile.samplingPolicy.minimumIntervalMillis,
                        ),
                    ),
                )
                val runtimeInstance = when (creation) {
                    is RuntimeCreationResult.Ready -> creation.runtime
                    is RuntimeCreationResult.Unavailable -> {
                        lease.close()
                        return failed(
                            SimilarityFieldValidationFailureReason.RUNTIME_INCOMPATIBLE,
                            creation.errors.toDiagnosticCodes(),
                        )
                    }
                }
                runtime = runtimeInstance
                return SimilarityFieldValidationSessionOpenResult.Ready(
                    ProductionSimilarityFieldValidationSession(
                        runtime = runtimeInstance,
                        lease = lease,
                        packagePointer = leasePointer,
                    ),
                )
            } catch (_: Exception) {
                runCatching { runtime?.close() }
                runCatching { lease.close() }
                return failed(SimilarityFieldValidationFailureReason.RUNTIME_INITIALIZATION_FAILED)
            }
        }

        private fun failed(
            reason: SimilarityFieldValidationFailureReason,
            diagnostics: Set<String> = emptySet(),
        ) = SimilarityFieldValidationSessionOpenResult.Failed(reason, diagnostics)

        private fun Set<RuntimeActivationError>.toDiagnosticCodes(): Set<String> =
            mapTo(linkedSetOf()) { "runtime_${it.name.lowercase()}" }
    }
}
