package app.beyoureyes.monitor

import app.beyoureyes.core.data.MonitorStorageCodec
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.ReadingOperator
import app.beyoureyes.core.domain.ReadingSourceKind
import app.beyoureyes.core.domain.resolveForSampling
import app.beyoureyes.core.domain.RuntimeMonitorRule
import app.beyoureyes.core.data.StoredLocalTask
import app.beyoureyes.core.data.reference.PrivateReferenceImageProvider
import app.beyoureyes.core.data.reference.PrivateReferenceRecord
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.vision.ReferenceImageMetadata
import app.beyoureyes.core.vision.SupportedTask
import app.beyoureyes.core.vision.TargetProfile
import java.io.File

internal data class RestoredMonitoringTask(
    val taskId: String,
    val revision: Long,
    val displayName: String,
    val kind: MonitorKind,
    val capabilityId: String,
    val supportedTask: SupportedTask,
    val targetId: String,
    val targetProfile: TargetProfile?,
    val rule: RuntimeMonitorRule,
    val cameraRegion: NormalizedRect,
    val readingSourceKind: ReadingSourceKind?,
    val confirmedReadingFormat: ConfirmedReadingFormat?,
    val manualReadingScanRegion: NormalizedRect?,
    /** True while a reading task waits for its first user-confirmed baseline. */
    val readingBaselinePending: Boolean,
    val referenceImageProvider: PrivateReferenceImageProvider,
    val runtimePackagePointer: ModelPackagePointer?,
)

/** Converts only the current clean-install persistence format into an immutable runtime input. */
internal class StoredTaskRuntimeResolver(private val filesDir: File) {
    fun resolve(
        stored: StoredLocalTask,
        samplingIntervalMillis: Long? = null,
    ): RestoredMonitoringTask {
        val decoded = MonitorStorageCodec.decode(stored)
        val monitor = decoded.monitor
        val records = decoded.references.mapIndexed { index, reference ->
            require(isMonitorReferencePath(stored.taskId, reference.relativePath))
            PrivateReferenceRecord(
                localAssetId = "${stored.taskId}:reference_${index + 1}",
                relativePath = reference.relativePath,
                contentSha256 = reference.exactSha256,
                storedWidth = reference.width,
                storedHeight = reference.height,
            )
        }
        val provider = PrivateReferenceImageProvider(filesDir, records)
        val profile = when (val target = monitor.target) {
            is MonitorTarget.ReferenceImages -> TargetProfile.ReferenceImages(
                targetId = runtimeTargetId(stored.taskId),
                images = records.mapIndexed { index, record ->
                    provider.metadataFor(record, "reference_${index + 1}")
                },
            )
            is MonitorTarget.ObjectClass -> TargetProfile.ObjectClass(
                targetId = target.targetId,
                labelZhCn = target.labelZhCn,
                labelEn = target.labelEn,
            )
            is MonitorTarget.NumericReading -> null
        }
        val rule = monitor.rule.toRuntimeRule().let { decodedRule ->
            if (samplingIntervalMillis == null) decodedRule
            else decodedRule.resolveForSampling(samplingIntervalMillis)
        }
        if (monitor.kind == MonitorKind.READING) {
            // A pending-baseline reading task (unconfirmed rule, no confirmed format) is a
            // complete product state: monitoring runs and records latest readings while the
            // unconfirmed rule stays event-silent in the rule engine.
            val reading = monitor.rule as MonitorRule.ReadingThreshold
            val target = monitor.target as MonitorTarget.NumericReading
            require(reading.configured == (target.confirmedFormat != null)) {
                "reading rule confirmation and confirmed format must agree"
            }
        }
        return RestoredMonitoringTask(
            taskId = monitor.id,
            revision = monitor.revision,
            displayName = monitor.name,
            kind = monitor.kind,
            capabilityId = when (monitor.kind) {
                MonitorKind.REFERENCE -> REFERENCE_CAPABILITY_ID
                MonitorKind.READING -> READING_CAPABILITY_ID
                MonitorKind.OBJECT_DETECTION -> OBJECT_CAPABILITY_ID
            },
            supportedTask = when (monitor.kind) {
                MonitorKind.REFERENCE -> SupportedTask.VISUAL_TARGET
                MonitorKind.READING -> SupportedTask.STRUCTURED_READING
                MonitorKind.OBJECT_DETECTION -> SupportedTask.VISUAL_TARGET
            },
            targetId = when (val target = monitor.target) {
                is MonitorTarget.ObjectClass -> target.targetId
                else -> runtimeTargetId(stored.taskId)
            },
            targetProfile = profile,
            rule = rule,
            cameraRegion = when (monitor.kind) {
                MonitorKind.REFERENCE -> FULL_FRAME_MONITOR_REGION
                MonitorKind.READING -> FULL_FRAME_MONITOR_REGION
                MonitorKind.OBJECT_DETECTION -> FULL_FRAME_MONITOR_REGION
            },
            readingSourceKind = if (monitor.kind == MonitorKind.READING) {
                ReadingSourceKind.DIGITAL_DISPLAY
            } else {
                null
            },
            confirmedReadingFormat = (monitor.target as? MonitorTarget.NumericReading)
                ?.confirmedFormat,
            manualReadingScanRegion = (monitor.target as? MonitorTarget.NumericReading)
                ?.manualRoi,
            readingBaselinePending = monitor.kind == MonitorKind.READING &&
                (monitor.rule as MonitorRule.ReadingThreshold).configured.not(),
            referenceImageProvider = provider,
            runtimePackagePointer = stored.runtimePackagePointer,
        )
    }

    private fun runtimeTargetId(taskId: String) = "target_${taskId.replace("-", "")}"
}

internal fun MonitorRule.toRuntimeRule(): RuntimeMonitorRule = when (this) {
    is MonitorRule.TargetPresence -> when (kind) {
        PresenceRuleKind.APPEARS -> RuntimeMonitorRule.PresenceEpisode(
            appearanceConfirmMillis = durationSeconds * 1_000L,
            disappearanceConfirmMillis = RuntimeMonitorRule.PresenceEpisode
                .PRODUCT_DISAPPEARANCE_CONFIRM_MILLIS,
        )
        PresenceRuleKind.REMAINS -> RuntimeMonitorRule.Presence(durationSeconds * 1_000L)
        PresenceRuleKind.DISAPPEARS -> RuntimeMonitorRule.Absence(durationSeconds * 1_000L)
    }
    is MonitorRule.ReadingThreshold.Single -> RuntimeMonitorRule.ReadingThreshold.Single(
        operator = when (comparison) {
            ReadingComparison.GT -> ReadingOperator.GT
            ReadingComparison.GTE -> ReadingOperator.GTE
            ReadingComparison.LT -> ReadingOperator.LT
            ReadingComparison.LTE -> ReadingOperator.LTE
        },
        thresholdDecimal = thresholdDecimal,
        durationMillis = Math.multiplyExact(durationSeconds.toLong(), 1_000L),
        configured = configured,
    )
    is MonitorRule.ReadingThreshold.Outside -> RuntimeMonitorRule.ReadingThreshold.Outside(
        lowerThresholdDecimal = lowerThresholdDecimal,
        upperThresholdDecimal = upperThresholdDecimal,
        durationMillis = Math.multiplyExact(durationSeconds.toLong(), 1_000L),
    )
}

internal fun isMonitorReferencePath(monitorId: String, relativePath: String): Boolean {
    if (monitorId.isBlank() || relativePath.isBlank()) return false
    val file = File(relativePath)
    if (file.isAbsolute) return false
    val segments = relativePath.split('/')
    return segments.size == 4 &&
        segments[0] == "reference-images" &&
        segments[1] == monitorId &&
        REFERENCE_GENERATION_DIRECTORY.matches(segments[2]) &&
        REFERENCE_FILE_NAME.matches(segments[3])
}

internal val FULL_FRAME_MONITOR_REGION = NormalizedRect(0f, 0f, 1f, 1f)
internal const val REFERENCE_CAPABILITY_ID = "visual_target"
internal const val READING_CAPABILITY_ID = "structured_reading"
internal const val OBJECT_CAPABILITY_ID = REFERENCE_CAPABILITY_ID

private val REFERENCE_FILE_NAME = Regex("^reference-[1-9][0-9]*\\.jpg$")
private val REFERENCE_GENERATION_DIRECTORY = Regex(
    "^generation-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
