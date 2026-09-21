package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.core.vision.Yuv420FrameNormalizer
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.uiText
import java.io.File

/** Opens one exact field-check coordinator without exposing package-specific behavior to CameraX. */
internal fun interface SimilarityFieldValidationCoordinatorOpener {
    fun open(identity: SimilarityFieldValidationIdentity): SimilarityFieldValidationCoordinator
}

/**
 * Immutable, media-free setup requirement restored with the exact task-bound sampling decision.
 *
 * Reference bytes remain behind the app-private reference provider and are only read by the
 * package runtime while the coordinator opens. They are never substituted for a live field frame.
 */
internal class SimilarityFieldValidationSpec(
    val taskId: String,
    val taskRevision: Long,
    val packagePointer: ModelPackagePointer,
    val targetId: String,
    private val coordinatorOpener: SimilarityFieldValidationCoordinatorOpener,
) {
    fun identity(roi: NormalizedRect) = SimilarityFieldValidationIdentity(
        taskId = taskId,
        taskRevision = taskRevision,
        packagePointer = packagePointer,
        roi = roi,
        targetId = targetId,
    )

    fun currentIdentityOrNull(
        currentTaskId: String,
        currentTaskRevision: Long,
        currentPackagePointer: ModelPackagePointer?,
        roi: NormalizedRect,
    ): SimilarityFieldValidationIdentity? = currentPackagePointer?.let { pointer ->
        SimilarityFieldValidationIdentity(
            taskId = currentTaskId,
            taskRevision = currentTaskRevision,
            packagePointer = pointer,
            roi = roi,
            targetId = targetId,
        )
    }

    fun open(identity: SimilarityFieldValidationIdentity): SimilarityFieldValidationCoordinator {
        require(identity.taskId == taskId)
        require(identity.taskRevision == taskRevision)
        require(identity.packagePointer == packagePointer)
        require(identity.targetId == targetId)
        return coordinatorOpener.open(identity)
    }

    /** ROI-only task revisions keep the same immutable package and private reference material. */
    fun rebindTaskRevision(nextTaskRevision: Long): SimilarityFieldValidationSpec {
        require(nextTaskRevision >= taskRevision)
        return if (nextTaskRevision == taskRevision) {
            this
        } else {
            SimilarityFieldValidationSpec(
                taskId = taskId,
                taskRevision = nextTaskRevision,
                packagePointer = packagePointer,
                targetId = targetId,
                coordinatorOpener = coordinatorOpener,
            )
        }
    }

    companion object {
        fun production(
            filesDir: File,
            task: RestoredMonitoringTask,
            targetProfile: TargetProfile,
        ): SimilarityFieldValidationSpec {
            val pointer = requireNotNull(task.runtimePackagePointer)
            require(task.targetId == targetProfile.targetId)
            return SimilarityFieldValidationSpec(
                taskId = task.taskId,
                taskRevision = task.revision,
                packagePointer = pointer,
                targetId = task.targetId,
                coordinatorOpener = SimilarityFieldValidationCoordinatorOpener { identity ->
                    SimilarityFieldValidationCoordinator.openAppPrivate(
                        filesDir = filesDir,
                        identity = identity,
                        targetProfile = targetProfile,
                        referenceImageProvider = task.referenceImageProvider,
                    )
                },
            )
        }

        fun productionObject(
            filesDir: File,
            taskId: String,
            taskRevision: Long,
            packagePointer: ModelPackagePointer,
            targetProfile: TargetProfile.ObjectClass,
        ): SimilarityFieldValidationSpec = SimilarityFieldValidationSpec(
            taskId = taskId,
            taskRevision = taskRevision,
            packagePointer = packagePointer,
            targetId = targetProfile.targetId,
            coordinatorOpener = SimilarityFieldValidationCoordinatorOpener { identity ->
                SimilarityFieldValidationCoordinator.openAppPrivate(
                    filesDir = filesDir,
                    identity = identity,
                    targetProfile = targetProfile,
                )
            },
        )
    }
}

internal fun requiresSimilarityFieldValidation(
    targetProfile: TargetProfile?,
    runtimeFamily: RecipeFamily,
): Boolean = when (targetProfile) {
    is TargetProfile.ReferenceImages -> runtimeFamily == RecipeFamily.SIMILARITY_MATCH_V1
    is TargetProfile.ObjectClass -> runtimeFamily == RecipeFamily.OBJECT_DETECTION_V1
    null -> false
}

/** Setup-only live presentation state. It contains signals, counters, and identities, never pixels. */
internal sealed interface SetupFieldValidationStatus {
    val expectedIdentity: SimilarityFieldValidationIdentity?

    data object Idle : SetupFieldValidationStatus {
        override val expectedIdentity: SimilarityFieldValidationIdentity? = null
    }

    data class Opening(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
    ) : SetupFieldValidationStatus

    data class Checking(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        val summary: SimilarityFieldValidationSummary,
    ) : SetupFieldValidationStatus

    data class Passed(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        val summary: SimilarityFieldValidationSummary,
        val stableEpisodeId: Long,
    ) : SetupFieldValidationStatus {
        init {
            require(stableEpisodeId > 0)
        }
    }

    data class NotFound(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        val summary: SimilarityFieldValidationSummary,
    ) : SetupFieldValidationStatus

    data class Failed(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        val summary: SimilarityFieldValidationSummary,
    ) : SetupFieldValidationStatus

    data class Invalidated(
        override val expectedIdentity: SimilarityFieldValidationIdentity,
        val currentIdentity: SimilarityFieldValidationIdentity?,
        val summary: SimilarityFieldValidationSummary,
    ) : SetupFieldValidationStatus
}

internal fun SimilarityFieldValidationUiState.toSetupStatus(): SetupFieldValidationStatus = when (
    this
) {
    is SimilarityFieldValidationUiState.Checking -> SetupFieldValidationStatus.Checking(
        expectedIdentity = expectedIdentity,
        summary = summary,
    )
    is SimilarityFieldValidationUiState.Passed -> SetupFieldValidationStatus.Passed(
        expectedIdentity,
        summary,
        stableEpisodeId,
    )
    is SimilarityFieldValidationUiState.NotFound -> SetupFieldValidationStatus.NotFound(
        expectedIdentity,
        summary,
    )
    is SimilarityFieldValidationUiState.Failed -> SetupFieldValidationStatus.Failed(
        expectedIdentity,
        summary,
    )
    is SimilarityFieldValidationUiState.Invalidated -> SetupFieldValidationStatus.Invalidated(
        expectedIdentity,
        currentIdentity,
        summary,
    )
}

internal fun SetupFieldValidationStatus.userMessage(): UiText = when (this) {
    SetupFieldValidationStatus.Idle -> uiText(R.string.field_validation_aim_camera)
    is SetupFieldValidationStatus.Opening -> uiText(R.string.field_validation_aim_camera)
    is SetupFieldValidationStatus.Checking -> uiText(R.string.field_validation_checking)
    is SetupFieldValidationStatus.Passed -> uiText(R.string.field_validation_passed)
    is SetupFieldValidationStatus.NotFound -> uiText(R.string.field_validation_not_found)
    is SetupFieldValidationStatus.Failed -> uiText(R.string.field_validation_failed)
    is SetupFieldValidationStatus.Invalidated -> uiText(R.string.field_validation_invalidated)
}

internal fun SetupFieldValidationStatus.isCheckInProgress(): Boolean = when (this) {
    SetupFieldValidationStatus.Idle,
    is SetupFieldValidationStatus.Opening,
    is SetupFieldValidationStatus.Checking,
    is SetupFieldValidationStatus.Passed,
    is SetupFieldValidationStatus.NotFound,
    -> true
    is SetupFieldValidationStatus.Failed,
    is SetupFieldValidationStatus.Invalidated,
    -> false
}

internal fun SetupFieldValidationStatus.canRetryCheck(): Boolean =
    this is SetupFieldValidationStatus.Failed || this is SetupFieldValidationStatus.Invalidated

/** Both the Compose button and camera handoff use this exact identity gate. */
internal fun fieldValidationPassedFor(
    status: SetupFieldValidationStatus,
    currentIdentity: SimilarityFieldValidationIdentity?,
): Boolean = status is SetupFieldValidationStatus.Passed &&
    currentIdentity != null &&
    status.expectedIdentity == currentIdentity

/** A start request is valid only for the uninterrupted stable episode the user confirmed. */
internal fun fieldValidationPassedForEpisode(
    status: SetupFieldValidationStatus,
    currentIdentity: SimilarityFieldValidationIdentity?,
    stableEpisodeId: Long?,
): Boolean = fieldValidationPassedFor(status, currentIdentity) &&
    stableEpisodeId != null &&
    (status as SetupFieldValidationStatus.Passed).stableEpisodeId == stableEpisodeId

internal fun SetupFieldValidationStatus.summaryOrEmpty(): SimilarityFieldValidationSummary = when (
    this
) {
    SetupFieldValidationStatus.Idle,
    is SetupFieldValidationStatus.Opening,
    -> emptyFieldValidationSummary()
    is SetupFieldValidationStatus.Checking -> summary
    is SetupFieldValidationStatus.Passed -> summary
    is SetupFieldValidationStatus.NotFound -> summary
    is SetupFieldValidationStatus.Failed -> summary
    is SetupFieldValidationStatus.Invalidated -> summary
}

internal fun emptyFieldValidationSummary() = SimilarityFieldValidationSummary(
    processedFrames = 0,
    presentFrames = 0,
    absentFrames = 0,
    unavailableFrames = 0,
    unavailableReasons = emptyMap(),
    unavailableDiagnosticCodes = emptyMap(),
    latestSignal = null,
)

/**
 * The same CameraX YUV -> upright ViewPort RGB -> task ROI path used by monitoring.
 * The returned bytes are owned by this one synchronous call and are never persisted.
 */
internal object SetupFieldValidationFrameNormalizer {
    fun normalize(
        cameraFrame: SourceFrame,
        roi: NormalizedRect,
    ): NormalizedRoiSourceFrame {
        val canonical = RuntimeRoiFrameNormalizer(
            delegate = Yuv420FrameNormalizer,
            roi = roi,
            cropToRoi = true,
        ).normalize(cameraFrame)
        return NormalizedRoiSourceFrame(
            roi = roi,
            sourceFrame = SourceFrame(
                sourceSequence = canonical.sourceSequence,
                monotonicTimeMillis = canonical.monotonicTimeMillis,
                capturedAtEpochMillis = canonical.capturedAtEpochMillis,
                width = canonical.width,
                height = canonical.height,
                rotationDegrees = 0,
                cropRect = PixelRect(0, 0, canonical.width, canonical.height),
                pixels = FramePixels.Rgb888(
                    bytes = canonical.rgb888,
                    rowStride = canonical.width * RGB_CHANNEL_COUNT,
                ),
            ),
        )
    }

    private const val RGB_CHANNEL_COUNT = 3
}
