package app.beyoureyes.core.data

import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.SamplingPolicySpec

private val SAMPLING_SHA256 = Regex("^[0-9a-f]{64}$")
private val SAMPLING_IDENTIFIER = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
private val SAMPLING_INTENT_KEY =
    Regex("^[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)*$")

/**
 * Persisted sampling decision for one exact task revision, immutable package descriptor and
 * device compatibility fingerprint. [artifactIdentitySha256] is the descriptor SHA-256 covering
 * the canonical Manifest plus the complete sorted artifact set.
 */
data class ResolvedSamplingConfig(
    val taskId: String,
    val taskRevision: Long,
    val catalogVersion: String,
    val capabilityId: String,
    val modelProfileKey: String,
    val recipeId: String,
    val intentKey: String,
    val packagePointer: ModelPackagePointer,
    val artifactIdentitySha256: String,
    val deviceFingerprintSha256: String,
    val intervalMillis: Long,
    val manifestMinimumIntervalMillis: Long,
    val manifestMaximumIntervalMillis: Long,
    val adaptiveEnabled: Boolean,
) {
    init {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        require(taskRevision >= 1)
        require(SAMPLING_IDENTIFIER.matches(catalogVersion)) { "catalogVersion is invalid" }
        require(SAMPLING_IDENTIFIER.matches(capabilityId)) { "capabilityId is invalid" }
        require(SAMPLING_IDENTIFIER.matches(modelProfileKey)) { "modelProfileKey is invalid" }
        require(SAMPLING_IDENTIFIER.matches(recipeId)) { "recipeId is invalid" }
        require(SAMPLING_INTENT_KEY.matches(intentKey)) { "intentKey is invalid" }
        require(SAMPLING_SHA256.matches(artifactIdentitySha256)) {
            "artifactIdentitySha256 must be lowercase hex"
        }
        require(SAMPLING_SHA256.matches(deviceFingerprintSha256)) {
            "deviceFingerprintSha256 must be lowercase hex"
        }
        require(
            manifestMinimumIntervalMillis in
                FrameSamplingPolicy.MIN_INTERVAL_MILLIS..
                FrameSamplingPolicy.MAX_INTERVAL_MILLIS,
        )
        require(
            manifestMaximumIntervalMillis in
                FrameSamplingPolicy.MIN_INTERVAL_MILLIS..
                FrameSamplingPolicy.MAX_INTERVAL_MILLIS,
        )
        require(manifestMinimumIntervalMillis <= manifestMaximumIntervalMillis)
        require(intervalMillis in manifestMinimumIntervalMillis..manifestMaximumIntervalMillis) {
            "intervalMillis must stay inside the signed Manifest bounds"
        }
    }

    /**
     * Resolves a caller-provided cadence suggestion without embedding a thermal or backlog policy.
     * The caller owns the evidence that produced [request]; this contract only enforces direction,
     * signed bounds and the signed adaptive flag. The returned config is not active until its owner
     * persists it with compare-and-set semantics and applies it to the runtime.
     */
    fun resolveAdjustment(request: SamplingIntervalAdjustmentRequest): SamplingIntervalAdjustment =
        when {
            !adaptiveEnabled -> SamplingIntervalAdjustment.Rejected(
                diagnostic = diagnostic(
                    request = request,
                    appliedIntervalMillis = intervalMillis,
                    disposition = SamplingAdjustmentDisposition.REJECTED_ADAPTATION_DISABLED,
                ),
            )
            !request.reason.accepts(intervalMillis, request.suggestedIntervalMillis) ->
                SamplingIntervalAdjustment.Rejected(
                    diagnostic = diagnostic(
                        request = request,
                        appliedIntervalMillis = intervalMillis,
                        disposition = SamplingAdjustmentDisposition.REJECTED_DIRECTION,
                    ),
                )
            else -> {
                val bounded = request.suggestedIntervalMillis.coerceIn(
                    manifestMinimumIntervalMillis,
                    manifestMaximumIntervalMillis,
                )
                val disposition = when {
                    bounded == intervalMillis -> SamplingAdjustmentDisposition.UNCHANGED
                    bounded != request.suggestedIntervalMillis ->
                        SamplingAdjustmentDisposition.APPLIED_CLAMPED_TO_SIGNED_BOUNDS
                    else -> SamplingAdjustmentDisposition.APPLIED
                }
                val next = copy(intervalMillis = bounded)
                val diagnostic = diagnostic(request, bounded, disposition)
                if (next == this) {
                    SamplingIntervalAdjustment.Unchanged(this, diagnostic)
                } else {
                    SamplingIntervalAdjustment.Ready(next, diagnostic)
                }
            }
        }

    private fun diagnostic(
        request: SamplingIntervalAdjustmentRequest,
        appliedIntervalMillis: Long,
        disposition: SamplingAdjustmentDisposition,
    ) = SamplingIntervalAdjustmentDiagnostic(
        reason = request.reason,
        observedAtMonotonicMillis = request.observedAtMonotonicMillis,
        previousIntervalMillis = intervalMillis,
        suggestedIntervalMillis = request.suggestedIntervalMillis,
        appliedIntervalMillis = appliedIntervalMillis,
        signedMinimumIntervalMillis = manifestMinimumIntervalMillis,
        signedMaximumIntervalMillis = manifestMaximumIntervalMillis,
        disposition = disposition,
    )

    fun exactMismatchReasons(
        expectedTaskId: String,
        expectedTaskRevision: Long,
        expectedCatalogVersion: String,
        expectedCapabilityId: String,
        expectedModelProfileKey: String,
        expectedRecipeId: String,
        expectedIntentKey: String,
        expectedPackagePointer: ModelPackagePointer,
        expectedArtifactIdentitySha256: String,
        expectedDeviceFingerprintSha256: String,
        signedSamplingPolicy: SamplingPolicySpec,
    ): Set<ResolvedSamplingMismatch> = buildSet {
        if (taskId != expectedTaskId) add(ResolvedSamplingMismatch.TASK_ID)
        if (taskRevision != expectedTaskRevision) add(ResolvedSamplingMismatch.TASK_REVISION)
        if (catalogVersion != expectedCatalogVersion) add(ResolvedSamplingMismatch.CATALOG_VERSION)
        if (capabilityId != expectedCapabilityId) add(ResolvedSamplingMismatch.CAPABILITY_ID)
        if (modelProfileKey != expectedModelProfileKey) {
            add(ResolvedSamplingMismatch.MODEL_PROFILE_KEY)
        }
        if (recipeId != expectedRecipeId) add(ResolvedSamplingMismatch.RECIPE_ID)
        if (intentKey != expectedIntentKey) add(ResolvedSamplingMismatch.INTENT_KEY)
        if (packagePointer != expectedPackagePointer) add(ResolvedSamplingMismatch.PACKAGE_POINTER)
        if (artifactIdentitySha256 != expectedArtifactIdentitySha256) {
            add(ResolvedSamplingMismatch.ARTIFACT_IDENTITY)
        }
        if (deviceFingerprintSha256 != expectedDeviceFingerprintSha256) {
            add(ResolvedSamplingMismatch.DEVICE_FINGERPRINT)
        }
        val minimum = signedSamplingPolicy.minimumIntervalMillis
        val maximum = signedSamplingPolicy.maximumIntervalMillis
        val default = signedSamplingPolicy.defaultIntervalMillis
        if (minimum == null || maximum == null || default == null) {
            add(ResolvedSamplingMismatch.MANIFEST_POLICY_UNAVAILABLE)
        } else {
            if (manifestMinimumIntervalMillis != minimum ||
                manifestMaximumIntervalMillis != maximum
            ) {
                add(ResolvedSamplingMismatch.MANIFEST_BOUNDS)
            }
            if (intervalMillis !in minimum..maximum) {
                add(ResolvedSamplingMismatch.INTERVAL_OUT_OF_MANIFEST_RANGE)
            }
            if (!signedSamplingPolicy.adaptiveAllowed && intervalMillis != default) {
                add(ResolvedSamplingMismatch.INTERVAL_NOT_SIGNED_DEFAULT)
            }
            if (adaptiveEnabled != signedSamplingPolicy.adaptiveAllowed) {
                add(ResolvedSamplingMismatch.ADAPTIVE_POLICY_MISMATCH)
            }
        }
    }

    companion object {
        /**
         * Starts at the signed package default. No benchmark, thermal or backlog heuristic is
         * invented here; later suggestions must pass [resolveAdjustment].
         */
        fun fromSignedManifestDefault(
            taskId: String,
            taskRevision: Long,
            catalogVersion: String,
            capabilityId: String,
            modelProfileKey: String,
            recipeId: String,
            intentKey: String,
            packagePointer: ModelPackagePointer,
            artifactIdentitySha256: String,
            deviceFingerprintSha256: String,
            samplingPolicy: SamplingPolicySpec,
        ): ResolvedSamplingConfig {
            val interval = requireNotNull(samplingPolicy.defaultIntervalMillis) {
                "signed Manifest sampling default is unavailable"
            }
            val minimum = requireNotNull(samplingPolicy.minimumIntervalMillis) {
                "signed Manifest sampling minimum is unavailable"
            }
            val maximum = requireNotNull(samplingPolicy.maximumIntervalMillis) {
                "signed Manifest sampling maximum is unavailable"
            }
            return ResolvedSamplingConfig(
                taskId = taskId,
                taskRevision = taskRevision,
                catalogVersion = catalogVersion,
                capabilityId = capabilityId,
                modelProfileKey = modelProfileKey,
                recipeId = recipeId,
                intentKey = intentKey,
                packagePointer = packagePointer,
                artifactIdentitySha256 = artifactIdentitySha256,
                deviceFingerprintSha256 = deviceFingerprintSha256,
                intervalMillis = interval,
                manifestMinimumIntervalMillis = minimum,
                manifestMaximumIntervalMillis = maximum,
                adaptiveEnabled = samplingPolicy.adaptiveAllowed,
            )
        }
    }
}

enum class SamplingAdjustmentReason {
    INFERENCE_LATENCY,
    ANALYSIS_BACKLOG,
    THERMAL_PRESSURE,
    RECOVERY;

    internal fun accepts(current: Long, suggested: Long): Boolean = when (this) {
        INFERENCE_LATENCY, ANALYSIS_BACKLOG, THERMAL_PRESSURE -> suggested >= current
        RECOVERY -> suggested <= current
    }
}

data class SamplingIntervalAdjustmentRequest(
    val suggestedIntervalMillis: Long,
    val reason: SamplingAdjustmentReason,
    val observedAtMonotonicMillis: Long,
) {
    init {
        require(suggestedIntervalMillis in FrameSamplingPolicy.MIN_INTERVAL_MILLIS..
            FrameSamplingPolicy.MAX_INTERVAL_MILLIS) {
            "suggestedIntervalMillis must be a valid generic runtime interval"
        }
        require(observedAtMonotonicMillis >= 0)
    }
}

enum class SamplingAdjustmentDisposition {
    APPLIED,
    APPLIED_CLAMPED_TO_SIGNED_BOUNDS,
    UNCHANGED,
    REJECTED_ADAPTATION_DISABLED,
    REJECTED_DIRECTION,
}

data class SamplingIntervalAdjustmentDiagnostic(
    val reason: SamplingAdjustmentReason,
    val observedAtMonotonicMillis: Long,
    val previousIntervalMillis: Long,
    val suggestedIntervalMillis: Long,
    val appliedIntervalMillis: Long,
    val signedMinimumIntervalMillis: Long,
    val signedMaximumIntervalMillis: Long,
    val disposition: SamplingAdjustmentDisposition,
)

sealed interface SamplingIntervalAdjustment {
    val diagnostic: SamplingIntervalAdjustmentDiagnostic

    data class Ready(
        val config: ResolvedSamplingConfig,
        override val diagnostic: SamplingIntervalAdjustmentDiagnostic,
    ) : SamplingIntervalAdjustment

    data class Unchanged(
        val config: ResolvedSamplingConfig,
        override val diagnostic: SamplingIntervalAdjustmentDiagnostic,
    ) : SamplingIntervalAdjustment

    data class Rejected(
        override val diagnostic: SamplingIntervalAdjustmentDiagnostic,
    ) : SamplingIntervalAdjustment
}

enum class ResolvedSamplingMismatch {
    TASK_ID,
    TASK_REVISION,
    CATALOG_VERSION,
    CAPABILITY_ID,
    MODEL_PROFILE_KEY,
    RECIPE_ID,
    INTENT_KEY,
    PACKAGE_POINTER,
    ARTIFACT_IDENTITY,
    DEVICE_FINGERPRINT,
    MANIFEST_POLICY_UNAVAILABLE,
    MANIFEST_BOUNDS,
    INTERVAL_OUT_OF_MANIFEST_RANGE,
    INTERVAL_NOT_SIGNED_DEFAULT,
    ADAPTIVE_POLICY_MISMATCH,
}
