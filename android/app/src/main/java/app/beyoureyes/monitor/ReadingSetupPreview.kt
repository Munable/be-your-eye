package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ModelPackageRuntimeLease
import app.beyoureyes.core.data.ModelPackageRuntimeLeaseResult
import app.beyoureyes.core.data.ModelPackageStores
import app.beyoureyes.core.data.SignedMetadataCodec
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.parseStructuredReading
import app.beyoureyes.core.vision.BuildChannel
import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.ManifestFrameQualityGates
import app.beyoureyes.core.vision.ManifestRuntimeComponents
import app.beyoureyes.core.vision.ModelPackageRuntime
import app.beyoureyes.core.vision.ModelPackageRuntimeFactory
import app.beyoureyes.core.vision.ModelRuntimeRequest
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.RuntimeCreationResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.UprightRgbFrameNormalizer
import app.beyoureyes.core.vision.VerifiedModelPackage
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.uiText
import java.io.File

internal data class ReadingPreviewIdentity(
    val taskId: String,
    val taskRevision: Long,
    val packagePointer: ModelPackagePointer,
    val roi: NormalizedRect,
)

internal data class ReadingPreviewValue(
    val text: String,
    val valueDecimal: String,
    val confidence: Float,
    val format: ConfirmedReadingFormat,
    val anchorBox: NormalizedRect? = null,
)

/** Current camera result. The confirmed baseline is stored separately from this live signal. */
internal sealed interface ReadingPreviewLiveState {
    data object Waiting : ReadingPreviewLiveState

    data class Candidate(val value: ReadingPreviewValue) : ReadingPreviewLiveState

    data class Stable(val value: ReadingPreviewValue) : ReadingPreviewLiveState

    data class Unavailable(
        val reason: UnavailableReason,
        val diagnosticCode: String?,
    ) : ReadingPreviewLiveState
}

internal sealed interface ReadingPreviewStatus {
    val expectedIdentity: ReadingPreviewIdentity?

    data object Idle : ReadingPreviewStatus {
        override val expectedIdentity: ReadingPreviewIdentity? = null
    }

    data class Opening(
        override val expectedIdentity: ReadingPreviewIdentity,
    ) : ReadingPreviewStatus

    /** The session pauses inference while editing a stable sample and after confirmation. */
    sealed class Checking : ReadingPreviewStatus {
        abstract override val expectedIdentity: ReadingPreviewIdentity
        abstract val processedFrames: Int
        abstract val unavailableFrames: Int
        abstract val live: ReadingPreviewLiveState
    }

    data class Scanning(
        override val expectedIdentity: ReadingPreviewIdentity,
        override val processedFrames: Int,
        override val unavailableFrames: Int,
        override val live: ReadingPreviewLiveState,
    ) : Checking()

    class AwaitingConfirmation internal constructor(
        override val expectedIdentity: ReadingPreviewIdentity,
        override val processedFrames: Int,
        override val unavailableFrames: Int,
        override val live: ReadingPreviewLiveState.Stable,
        internal val baselineDraft: ReadingPreviewBaselineDraft,
        val editingFormat: Boolean = false,
    ) : Checking() {
        /** Latest recognized value, or the user's canonical manual correction. */
        val value: ReadingPreviewValue get() = baselineDraft.current()
        val confirmedFormat: ConfirmedReadingFormat get() = baselineDraft.format()

        /** Applies only to the confirmation baseline; it never rewrites the live observation. */
        fun correctBaseline(raw: String): Boolean = baselineDraft.correct(raw)
    }

    data class Confirmed(
        override val expectedIdentity: ReadingPreviewIdentity,
        /** User-confirmed baseline. This remains fixed while [live] continues changing. */
        val value: ReadingPreviewValue,
        val confirmedFormat: ConfirmedReadingFormat,
        override val processedFrames: Int,
        override val unavailableFrames: Int,
        override val live: ReadingPreviewLiveState,
    ) : Checking()

    data class Failed(
        override val expectedIdentity: ReadingPreviewIdentity,
        val diagnosticCode: String,
    ) : ReadingPreviewStatus

    data class Invalidated(
        override val expectedIdentity: ReadingPreviewIdentity,
        val currentIdentity: ReadingPreviewIdentity?,
    ) : ReadingPreviewStatus
}

internal class ReadingPreviewSpec(
    val taskId: String,
    val taskRevision: Long,
    val packagePointer: ModelPackagePointer,
    private val opener: (ReadingPreviewIdentity) -> ReadingPreviewCoordinator,
) {
    fun identity(roi: NormalizedRect) = ReadingPreviewIdentity(
        taskId = taskId,
        taskRevision = taskRevision,
        packagePointer = packagePointer,
        roi = roi,
    )

    fun currentIdentityOrNull(
        currentTaskId: String,
        currentTaskRevision: Long,
        currentPackagePointer: ModelPackagePointer?,
        roi: NormalizedRect,
    ): ReadingPreviewIdentity? = currentPackagePointer?.let {
        ReadingPreviewIdentity(currentTaskId, currentTaskRevision, it, roi)
    }

    fun open(identity: ReadingPreviewIdentity): ReadingPreviewCoordinator {
        require(identity.taskId == taskId)
        require(identity.taskRevision == taskRevision)
        require(identity.packagePointer == packagePointer)
        return opener(identity)
    }

    /**
     * Rebinds the preview contract to the next Room revision without acquiring a second package
     * lease. A condition/ROI edit advances the persisted task revision; the live setup session
     * must then confirm a value against that exact revision before handing the camera to FGS.
     */
    fun rebindTaskRevision(nextTaskRevision: Long): ReadingPreviewSpec {
        require(nextTaskRevision >= 1L)
        return ReadingPreviewSpec(taskId, nextTaskRevision, packagePointer, opener)
    }

    companion object {
        fun production(filesDir: File, task: RestoredMonitoringTask): ReadingPreviewSpec {
            require(task.capabilityId == READING_CAPABILITY_ID)
            val pointer = requireNotNull(task.runtimePackagePointer)
            return ReadingPreviewSpec(task.taskId, task.revision, pointer) { identity ->
                ReadingPreviewCoordinator.openAppPrivate(filesDir, identity)
            }
        }
    }
}

internal interface ReadingPreviewRuntimeSession : AutoCloseable {
    val packagePointer: ModelPackagePointer
    fun process(frame: SourceFrame): RuntimeFrameResult
    fun updateManualScanRegion(region: NormalizedRect?) = Unit
    fun resetAutomaticAnchor() = Unit
}

internal sealed interface ReadingPreviewOpenResult {
    data class Ready(val session: ReadingPreviewRuntimeSession) : ReadingPreviewOpenResult
    data class Failed(val diagnosticCode: String) : ReadingPreviewOpenResult
}

/**
 * A small thread-safe bridge between the analyzer-owned status and the Compose confirmation click.
 * The UI mutates only the pending baseline; the actual live value remains analyzer-owned.
 */
internal class ReadingPreviewBaselineDraft(initial: ReadingPreviewValue) {
    private var current = initial
    private var recognized = initial
    private var confirmedFormat = checkNotNull(
        ConfirmedReadingFormat.calibrate(initial.text, initial.text),
    )
    private var manuallyCorrected = false

    @Synchronized
    fun current(): ReadingPreviewValue = current

    @Synchronized
    fun format(): ConfirmedReadingFormat = confirmedFormat

    @Synchronized
    fun updateRecognized(value: ReadingPreviewValue) {
        recognized = value
        if (!manuallyCorrected) {
            current = value
            confirmedFormat = checkNotNull(
                ConfirmedReadingFormat.calibrate(value.text, value.text),
            )
        }
    }

    @Synchronized
    fun correct(raw: String): Boolean {
        val profile = ConfirmedReadingFormat.calibrate(recognized.text, raw) ?: return false
        val value = profile.parseThreshold(raw) ?: parseStructuredReading(raw) ?: return false
        current = current.copy(
            text = value.text,
            valueDecimal = value.valueDecimal,
            format = profile,
        )
        confirmedFormat = profile
        manuallyCorrected = true
        return true
    }
}

/** One bounded, exact-package live preview. It retains only the recognized value, never pixels. */
internal class ReadingPreviewCoordinator private constructor(
    private val identity: ReadingPreviewIdentity,
    openResult: ReadingPreviewOpenResult,
) : AutoCloseable {
    private var session = (openResult as? ReadingPreviewOpenResult.Ready)?.session
    private var closed = false
    private var processedFrames = 0
    private var unavailableFrames = 0
    private var pendingBaselineDraft: ReadingPreviewBaselineDraft? = null
    private var confirmedBaseline: ReadingPreviewValue? = null
    private var confirmedReadingFormat: ConfirmedReadingFormat? = null

    var state: ReadingPreviewStatus = when (openResult) {
        is ReadingPreviewOpenResult.Ready -> ReadingPreviewStatus.Scanning(
            identity,
            processedFrames = 0,
            unavailableFrames = 0,
            live = ReadingPreviewLiveState.Waiting,
        )
        is ReadingPreviewOpenResult.Failed -> ReadingPreviewStatus.Failed(
            identity,
            openResult.diagnosticCode,
        )
    }
        private set

    fun accept(
        currentIdentity: ReadingPreviewIdentity,
        frame: NormalizedRoiSourceFrame,
    ): ReadingPreviewStatus {
        if (closed) return state
        if (currentIdentity != identity || frame.roi != identity.roi) {
            return invalidate(currentIdentity)
        }
        if (state !is ReadingPreviewStatus.Checking) return state
        if ((state as? ReadingPreviewStatus.AwaitingConfirmation)?.editingFormat == true) return state
        val frameFailure = frame.contractFailureOrNull()
        if (frameFailure != null) return fail("frame_${frameFailure.name.lowercase()}")
        val result = try {
            checkNotNull(session).process(frame.sourceFrame)
        } catch (_: Exception) {
            return fail("runtime_processing_failed")
        }
        if (result is RuntimeFrameResult.Skipped) return state
        processedFrames++
        val observation = (result as RuntimeFrameResult.Processed).pipelineResult.observation
        state = when (observation) {
            is Observation.Reading -> observation.toPreviewValue().let { value ->
                if (observation.stable) {
                    liveStable(value)
                } else {
                    liveChanged(ReadingPreviewLiveState.Candidate(value))
                }
            }
            is Observation.Unavailable -> {
                unavailableFrames++
                liveChanged(
                    ReadingPreviewLiveState.Unavailable(
                        observation.reason,
                        observation.diagnosticCode,
                    ),
                )
            }
            else -> {
                unavailableFrames++
                liveChanged(
                    ReadingPreviewLiveState.Unavailable(
                        UnavailableReason.INCOMPATIBLE_OUTPUT,
                        "unexpected_reading_preview_observation",
                    ),
                )
            }
        }
        return state
    }

    fun beginFormatCorrection(sample: ReadingPreviewStatus.AwaitingConfirmation): ReadingPreviewStatus {
        if (closed || sample.expectedIdentity != identity || state !is ReadingPreviewStatus.Checking ||
            state is ReadingPreviewStatus.Confirmed || pendingBaselineDraft !== sample.baselineDraft
        ) return state
        // A newer frame may arrive before the click. Edit exactly the stable value the user chose;
        // a new ROI replaces the draft, so a click from the old region cannot freeze a stale sample.
        val draft = ReadingPreviewBaselineDraft(sample.live.value)
        pendingBaselineDraft = draft
        state = ReadingPreviewStatus.AwaitingConfirmation(
            expectedIdentity = identity,
            processedFrames = processedFrames,
            unavailableFrames = unavailableFrames,
            live = sample.live,
            baselineDraft = draft,
            editingFormat = true,
        )
        return state
    }

    fun cancelFormatCorrection(currentIdentity: ReadingPreviewIdentity): ReadingPreviewStatus {
        if (closed || currentIdentity != identity ||
            (state as? ReadingPreviewStatus.AwaitingConfirmation)?.editingFormat != true
        ) {
            return state
        }
        pendingBaselineDraft = null
        state = ReadingPreviewStatus.Scanning(
            expectedIdentity = identity,
            processedFrames = processedFrames,
            unavailableFrames = unavailableFrames,
            live = ReadingPreviewLiveState.Waiting,
        )
        return state
    }

    fun confirmFormatCorrection(sample: ReadingPreviewStatus.AwaitingConfirmation): ReadingPreviewStatus {
        if (closed || state !== sample || !sample.editingFormat) return state
        return confirm(sample.expectedIdentity)
    }

    fun confirm(currentIdentity: ReadingPreviewIdentity): ReadingPreviewStatus {
        if (currentIdentity != identity) return invalidate(currentIdentity)
        val current = state
        if (current is ReadingPreviewStatus.Checking &&
            current !is ReadingPreviewStatus.Confirmed
        ) {
            // The click and analyzer run on different queues. Preserve the last stable draft that
            // was visibly offered even if a candidate/unavailable frame wins the race immediately
            // before this command executes.
            val draft = pendingBaselineDraft ?: return state
            val baseline = draft.current()
            val format = draft.format()
            confirmedBaseline = baseline
            confirmedReadingFormat = format
            state = ReadingPreviewStatus.Confirmed(
                expectedIdentity = identity,
                value = baseline,
                confirmedFormat = format,
                processedFrames = processedFrames,
                unavailableFrames = unavailableFrames,
                live = formatConfirmedLive(current.live),
            )
        }
        return state
    }

    fun updateManualScanRegion(
        currentIdentity: ReadingPreviewIdentity,
        region: NormalizedRect?,
    ): ReadingPreviewStatus {
        if (currentIdentity != identity) return invalidate(currentIdentity)
        runCatching {
            checkNotNull(session).updateManualScanRegion(region)
            if (region == null) checkNotNull(session).resetAutomaticAnchor()
        }
            .getOrElse { return fail("manual_scan_region_update_failed") }
        pendingBaselineDraft = null
        confirmedBaseline = null
        confirmedReadingFormat = null
        state = ReadingPreviewStatus.Scanning(
            expectedIdentity = identity,
            processedFrames = processedFrames,
            unavailableFrames = unavailableFrames,
            live = ReadingPreviewLiveState.Waiting,
        )
        return state
    }

    fun ensureIdentity(currentIdentity: ReadingPreviewIdentity?): ReadingPreviewStatus =
        if (currentIdentity == identity) state else invalidate(currentIdentity)

    override fun close() {
        if (closed) return
        if (state is ReadingPreviewStatus.Scanning ||
            state is ReadingPreviewStatus.AwaitingConfirmation
        ) {
            state = ReadingPreviewStatus.Failed(identity, "session_closed")
        }
        closeResources()
    }

    private fun liveStable(value: ReadingPreviewValue): ReadingPreviewStatus {
        val baseline = confirmedBaseline
        if (baseline != null) {
            val live = formatConfirmedLive(ReadingPreviewLiveState.Stable(value))
            return ReadingPreviewStatus.Confirmed(
                expectedIdentity = identity,
                value = baseline,
                confirmedFormat = checkNotNull(confirmedReadingFormat),
                processedFrames = processedFrames,
                unavailableFrames = unavailableFrames,
                live = live,
            )
        }
        val draft = pendingBaselineDraft ?: ReadingPreviewBaselineDraft(value).also {
            pendingBaselineDraft = it
        }
        draft.updateRecognized(value)
        return ReadingPreviewStatus.AwaitingConfirmation(
            expectedIdentity = identity,
            processedFrames = processedFrames,
            unavailableFrames = unavailableFrames,
            live = ReadingPreviewLiveState.Stable(value),
            baselineDraft = draft,
        )
    }

    private fun liveChanged(live: ReadingPreviewLiveState): ReadingPreviewStatus {
        val baseline = confirmedBaseline
        return if (baseline == null) {
            ReadingPreviewStatus.Scanning(
                expectedIdentity = identity,
                processedFrames = processedFrames,
                unavailableFrames = unavailableFrames,
                live = live,
            )
        } else {
            ReadingPreviewStatus.Confirmed(
                expectedIdentity = identity,
                value = baseline,
                confirmedFormat = checkNotNull(confirmedReadingFormat),
                processedFrames = processedFrames,
                unavailableFrames = unavailableFrames,
                live = formatConfirmedLive(live),
            )
        }
    }

    private fun formatConfirmedLive(live: ReadingPreviewLiveState): ReadingPreviewLiveState {
        val profile = confirmedReadingFormat ?: return live
        val raw = when (live) {
            is ReadingPreviewLiveState.Candidate -> live.value
            is ReadingPreviewLiveState.Stable -> live.value
            ReadingPreviewLiveState.Waiting,
            is ReadingPreviewLiveState.Unavailable,
            -> return live
        }
        val formatted = profile.apply(raw.text) ?: return ReadingPreviewLiveState.Unavailable(
            UnavailableReason.LOW_QUALITY,
            "confirmed_reading_format_rejected",
        )
        val value = raw.copy(
            text = formatted.text,
            valueDecimal = formatted.valueDecimal,
            format = formatted.format,
        )
        return when (live) {
            is ReadingPreviewLiveState.Candidate -> ReadingPreviewLiveState.Candidate(value)
            is ReadingPreviewLiveState.Stable -> ReadingPreviewLiveState.Stable(value)
        }
    }

    private fun fail(code: String): ReadingPreviewStatus.Failed =
        ReadingPreviewStatus.Failed(identity, code).also {
            state = it
            closeResources()
        }

    private fun invalidate(current: ReadingPreviewIdentity?): ReadingPreviewStatus.Invalidated =
        ReadingPreviewStatus.Invalidated(identity, current).also {
            state = it
            closeResources()
        }

    private fun closeResources() {
        if (closed) return
        closed = true
        val owned = session
        session = null
        runCatching { owned?.close() }
    }

    companion object {
        fun openAppPrivate(
            filesDir: File,
            identity: ReadingPreviewIdentity,
            nowEpochMillis: () -> Long = System::currentTimeMillis,
        ): ReadingPreviewCoordinator {
            val channel = BuildChannel.fromWireValue(BuildConfig.BUILD_CHANNEL)
                ?: return fromOpenResult(identity, ReadingPreviewOpenResult.Failed("build_channel_invalid"))
            return fromOpenResult(
                identity,
                ProductionReadingPreviewSession.open(
                    filesDir,
                    identity,
                    channel,
                    nowEpochMillis,
                ),
            )
        }

        internal fun fromOpenResult(
            identity: ReadingPreviewIdentity,
            result: ReadingPreviewOpenResult,
        ) = ReadingPreviewCoordinator(identity, result)
    }
}

private class ProductionReadingPreviewSession private constructor(
    private val runtime: ModelPackageRuntime,
    private val lease: ModelPackageRuntimeLease,
    override val packagePointer: ModelPackagePointer,
) : ReadingPreviewRuntimeSession {
    private var closed = false

    override fun process(frame: SourceFrame): RuntimeFrameResult {
        check(!closed)
        return runtime.process(frame)
    }

    override fun updateManualScanRegion(region: NormalizedRect?) {
        check(!closed)
        runtime.updateManualReadingScanRegion(region)
    }

    override fun resetAutomaticAnchor() {
        check(!closed)
        runtime.resetAutomaticReadingAnchor()
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
            identity: ReadingPreviewIdentity,
            buildChannel: BuildChannel,
            nowEpochMillis: () -> Long,
        ): ReadingPreviewOpenResult {
            if (buildChannel == BuildChannel.DEVELOPMENT_NO_MODEL) {
                return ReadingPreviewOpenResult.Failed("build_channel_invalid")
            }
            val lease = when (val acquired = runCatching {
                ModelPackageStores.open(filesDir).acquireRuntimeLease(
                    identity.packagePointer,
                    nowEpochMillis(),
                )
            }.getOrNull()) {
                is ModelPackageRuntimeLeaseResult.Acquired -> acquired.lease
                else -> return ReadingPreviewOpenResult.Failed("package_unavailable")
            }
            var runtime: ModelPackageRuntime? = null
            try {
                val leasePointer = ModelPackagePointer(
                    lease.descriptor.identity,
                    lease.descriptor.canonicalManifestSha256,
                )
                if (leasePointer != identity.packagePointer) {
                    lease.close()
                    return ReadingPreviewOpenResult.Failed("package_identity_mismatch")
                }
                val manifest = runCatching {
                    SignedMetadataCodec.decodeStoredManifestDocument(
                        documentBytes = lease.canonicalManifest.copyBytes(),
                        expectedIdentity = lease.descriptor.identity,
                        expectedSha256 = lease.descriptor.canonicalManifestSha256,
                    )
                }.getOrElse {
                    lease.close()
                    return ReadingPreviewOpenResult.Failed("manifest_invalid")
                }
                if (manifest.runtimeFamily != RecipeFamily.READING_PIPELINE_V1) {
                    lease.close()
                    return ReadingPreviewOpenResult.Failed("runtime_family_mismatch")
                }
                val verified = VerifiedModelPackage(
                    manifest = manifest,
                    catalogEntryActive = lease.gateReport.catalogEntryActive,
                    catalogSignatureValid = lease.gateReport.catalogSignatureValid,
                    catalogManifestSha256Matches = lease.gateReport.catalogManifestHashValid,
                    manifestSignatureValid = lease.gateReport.manifestSignatureValid,
                    artifactSha256Valid = true,
                    licenseTextSha256Valid = lease.gateReport.licenseGatePassed,
                )
                val creation = ModelPackageRuntimeFactory(
                    registry = ManifestRuntimeComponents.registry(),
                    normalizer = UprightRgbFrameNormalizer,
                    qualityGate = ManifestFrameQualityGates.forManifest(manifest),
                    nowEpochMillis = nowEpochMillis,
                ).create(
                    ModelRuntimeRequest(
                        verifiedPackage = verified,
                        artifactFile = lease.artifactFilesByRole.getValue("primary"),
                        artifactFilesByRole = lease.artifactFilesByRole,
                        buildChannel = buildChannel,
                        // Setup is foreground and work-conserving: process the latest available
                        // frame at the package-signed minimum cadence without queueing stale frames.
                        samplingPolicyOverride = FrameSamplingPolicy(
                            checkNotNull(
                                manifest.parameterProfile.samplingPolicy.minimumIntervalMillis,
                            ),
                        ),
                    ),
                )
                runtime = (creation as? RuntimeCreationResult.Ready)?.runtime
                    ?: run {
                        lease.close()
                        return ReadingPreviewOpenResult.Failed("runtime_incompatible")
                    }
                return ReadingPreviewOpenResult.Ready(
                    ProductionReadingPreviewSession(runtime, lease, leasePointer),
                )
            } catch (_: Exception) {
                runCatching { runtime?.close() }
                runCatching { lease.close() }
                return ReadingPreviewOpenResult.Failed("runtime_initialization_failed")
            }
        }
    }
}

internal fun ReadingPreviewStatus.userMessage(): UiText = when (this) {
    ReadingPreviewStatus.Idle -> uiText(R.string.reading_preview_aim_camera)
    is ReadingPreviewStatus.Opening -> uiText(R.string.reading_preview_connecting)
    is ReadingPreviewStatus.Scanning -> when (live) {
        ReadingPreviewLiveState.Waiting -> uiText(R.string.reading_preview_searching)
        is ReadingPreviewLiveState.Candidate ->
            uiText(R.string.reading_preview_candidate, live.value.text)
        is ReadingPreviewLiveState.Stable ->
            uiText(R.string.reading_current_value, live.value.text)
        is ReadingPreviewLiveState.Unavailable -> if (readingPreviewSuggestsManualRegion(this)) {
            uiText(R.string.reading_preview_draw_region)
        } else {
            readingUnavailableHint(live.diagnosticCode)
                ?: uiText(R.string.reading_preview_unavailable_continuing)
        }
    }
    is ReadingPreviewStatus.AwaitingConfirmation ->
        uiText(R.string.reading_current_value, value.text)
    is ReadingPreviewStatus.Confirmed -> when (live) {
        is ReadingPreviewLiveState.Unavailable ->
            uiText(R.string.reading_preview_confirmed_unavailable, value.text)
        else -> uiText(R.string.reading_preview_confirmed, value.text)
    }
    is ReadingPreviewStatus.Failed -> uiText(R.string.reading_preview_connection_failed)
    is ReadingPreviewStatus.Invalidated -> uiText(R.string.reading_preview_invalidated)
}

internal fun readingPreviewConfirmedFor(
    status: ReadingPreviewStatus,
    currentIdentity: ReadingPreviewIdentity?,
): Boolean = status is ReadingPreviewStatus.Confirmed &&
    currentIdentity != null && status.expectedIdentity == currentIdentity &&
    readingLiveIsStable(status)

/** Starting needs a current stable signal, not equality with an earlier baseline. */
internal fun readingLiveIsStable(status: ReadingPreviewStatus.Confirmed): Boolean =
    status.live is ReadingPreviewLiveState.Stable

/** Locator-stage failures keep retrying, but the setup page should direct the user to the reliable ROI path. */
internal fun readingPreviewSuggestsManualRegion(status: ReadingPreviewStatus): Boolean {
    val scanning = status as? ReadingPreviewStatus.Scanning ?: return false
    val diagnosticCode = (scanning.live as? ReadingPreviewLiveState.Unavailable)?.diagnosticCode
    return diagnosticCode == "numeric_target_shape_unavailable" ||
        diagnosticCode == "structured_reading_candidates_unreadable"
}

internal fun readingPreviewInferencePaused(status: ReadingPreviewStatus): Boolean =
    status is ReadingPreviewStatus.Confirmed ||
        (status as? ReadingPreviewStatus.AwaitingConfirmation)?.editingFormat == true

private fun Observation.Reading.toPreviewValue() = ReadingPreviewValue(
    text = text,
    valueDecimal = valueDecimal,
    confidence = confidence,
    format = format,
    anchorBox = anchorBox,
)

/** Actionable feedback from the current frame; it never describes a retained value as live. */
internal fun readingUnavailableHint(diagnosticCode: String?): UiText? = when (diagnosticCode) {
    "confirmed_reading_format_rejected" -> uiText(R.string.reading_hint_format_changed)
    "frame_underexposed" -> uiText(R.string.reading_hint_too_dark)
    "frame_overexposed" -> uiText(R.string.reading_hint_glare)
    "frame_too_blurry_or_flat", "numeric_confidence_below_threshold" ->
        uiText(R.string.reading_hint_unclear)
    "structured_reading_decode_invalid", "structured_reading_candidates_unreadable",
    "numeric_target_shape_unavailable", ->
        uiText(R.string.reading_hint_single_line)
    else -> null
}
