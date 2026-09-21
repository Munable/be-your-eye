package app.beyoureyes.monitor

import app.beyoureyes.core.domain.RuntimeMonitorRule
import app.beyoureyes.core.data.ModelPackageArtifactDescriptor
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.TargetProfile

internal data class RuntimePackageSnapshot(
    val pointer: ModelPackagePointer,
    val runtimeFamily: RecipeFamily,
    val preprocessId: String,
    val adapterId: String,
    val artifacts: List<ModelPackageArtifactDescriptor>,
    val artifactIdentitySha256: String,
)

/** Immutable, model-neutral event evidence. Tensor names and vendor/private knobs are excluded. */
internal fun monitoringRuntimeSnapshotJson(
    config: RuntimeCameraConfig,
    task: RestoredMonitoringTask,
    runtimePackage: RuntimePackageSnapshot,
): String {
    require(config.taskId == task.taskId && config.taskRevision == task.revision)
    require(task.runtimePackagePointer == runtimePackage.pointer) {
        "runtime package must match the exact pointer bound to the task"
    }
    require(config.resolvedSamplingConfig.packagePointer == runtimePackage.pointer)
    require(
        config.resolvedSamplingConfig.artifactIdentitySha256 ==
            runtimePackage.artifactIdentitySha256,
    )
    return buildString {
        append("{\"schema_version\":\"local_runtime_snapshot_v3\"")
        append(",\"task_id\":").appendJson(task.taskId)
        append(",\"task_revision\":").append(task.revision)
        append(",\"target\":")
        appendTarget(task)
        append(",\"rule\":")
        appendRule(task.rule)
        append(",\"roi\":{\"left\":").append(config.roi.left)
        append(",\"top\":").append(config.roi.top)
        append(",\"right\":").append(config.roi.right)
        append(",\"bottom\":").append(config.roi.bottom).append('}')
        append(",\"sampling\":{\"interval_ms\":")
            .append(config.analysisIntervalMillis)
        append(",\"min_interval_ms\":")
            .append(config.resolvedSamplingConfig.manifestMinimumIntervalMillis)
        append(",\"max_interval_ms\":")
            .append(config.resolvedSamplingConfig.manifestMaximumIntervalMillis)
        append(",\"artifact_identity_sha256\":")
            .appendJson(config.resolvedSamplingConfig.artifactIdentitySha256)
        append(",\"device_fingerprint_sha256\":")
            .appendJson(config.resolvedSamplingConfig.deviceFingerprintSha256)
        append(",\"adaptive_enabled\":")
            .append(config.resolvedSamplingConfig.adaptiveEnabled)
        append('}')
        append(",\"model_package\":{\"package_id\":")
            .appendJson(runtimePackage.pointer.identity.packageId)
        append(",\"package_version\":")
            .appendJson(runtimePackage.pointer.identity.packageVersion)
        append(",\"manifest_sha256\":")
            .appendJson(runtimePackage.pointer.canonicalManifestSha256)
        append(",\"artifact_identity_sha256\":")
            .appendJson(runtimePackage.artifactIdentitySha256)
        append(",\"runtime_family\":").appendJson(runtimePackage.runtimeFamily.wireValue)
        append(",\"preprocess_id\":").appendJson(runtimePackage.preprocessId)
        append(",\"adapter_id\":").appendJson(runtimePackage.adapterId)
        append(",\"artifacts\":[")
        runtimePackage.artifacts.sortedBy(ModelPackageArtifactDescriptor::role)
            .forEachIndexed { index, artifact ->
                if (index > 0) append(',')
                append("{\"role\":").appendJson(artifact.role)
                append(",\"sha256\":").appendJson(artifact.sha256)
                append(",\"size_bytes\":").append(artifact.sizeBytes).append('}')
            }
        append("]}}")
    }
}

private fun StringBuilder.appendTarget(task: RestoredMonitoringTask) {
    val target = task.targetProfile
    when (target) {
        null -> {
            require(task.capabilityId == READING_CAPABILITY_ID)
            append("{\"mode\":\"structured_reading\",\"target_id\":")
                .appendJson(task.targetId)
            append(",\"source_kind\":")
                .appendJson(checkNotNull(task.readingSourceKind).wireValue)
            val format = task.confirmedReadingFormat
            if (format == null) {
                // A pending-baseline reading monitor starts before the user confirms a reading
                // format; its rule never produces events until confirmation rewrites the snapshot.
                append(",\"confirmed_format\":null")
                append(",\"baseline_pending\":true")
            } else {
                append(",\"confirmed_format\":{\"profile_id\":")
                    .appendJson(app.beyoureyes.core.domain.ConfirmedReadingFormat.PROFILE_ID)
                append(",\"kind\":").appendJson(format.kind.wireValue)
                append(",\"fractional_digits\":")
                    .append(format.fractionalDigits)
                append(",\"time_segments\":")
                format.timeSegments?.let(::append) ?: append("null")
                append(",\"unit\":")
                format.unit?.let(::appendJson) ?: append("null")
                append('}')
            }
            append(",\"manual_roi\":")
            task.manualReadingScanRegion?.let { roi ->
                append("{\"left\":").append(roi.left)
                append(",\"top\":").append(roi.top)
                append(",\"right\":").append(roi.right)
                append(",\"bottom\":").append(roi.bottom).append('}')
            } ?: append("null")
            append('}')
        }
        is TargetProfile.ObjectClass -> {
            append("{\"mode\":\"object_detection\",\"target_id\":")
                .appendJson(target.targetId)
            append(",\"label_zh_cn\":").appendJson(target.labelZhCn)
            append(",\"label_en\":").appendJson(target.labelEn).append('}')
        }
        is TargetProfile.ReferenceImages -> {
            append("{\"mode\":\"reference_images\",\"target_id\":")
                .appendJson(target.targetId)
            append(",\"references\":[")
            target.images.forEachIndexed { index, image ->
                if (index > 0) append(',')
                append("{\"reference_id\":").appendJson(image.referenceId)
                append(",\"local_asset_id\":").appendJson(image.localAssetId)
                append(",\"sha256\":").appendJson(image.contentSha256)
                append(",\"width\":").append(image.width)
                append(",\"height\":").append(image.height).append('}')
            }
            append("]}")
        }
    }
}

private fun StringBuilder.appendRule(rule: RuntimeMonitorRule) {
    when (rule) {
        is RuntimeMonitorRule.PresenceEpisode -> {
            append("{\"type\":\"presence_duration\",\"condition\":\"appears\",\"duration_ms\":")
                .append(rule.appearanceConfirmMillis)
            append(",\"min_positive_count\":").append(rule.minimumObservationCount)
            append(",\"max_positive_gap_ms\":").append(rule.maximumObservationGapMillis)
            append(",\"rearm_absence_ms\":")
                .append(rule.disappearanceConfirmMillis).append('}')
        }
        is RuntimeMonitorRule.Presence -> {
            append("{\"type\":\"presence_duration\",\"condition\":\"remains\",\"duration_ms\":")
                .append(rule.durationMillis)
            append(",\"min_positive_count\":").append(rule.minimumPositiveCount)
            append(",\"max_positive_gap_ms\":").append(rule.maximumPositiveGapMillis)
            append(",\"rearm_absence_ms\":").append(rule.rearmAbsenceMillis).append('}')
        }
        is RuntimeMonitorRule.Absence -> {
            append("{\"type\":\"absence_duration\",\"condition\":\"disappears\",\"duration_ms\":")
                .append(rule.durationMillis)
            append(",\"min_negative_count\":").append(rule.minimumNegativeCount)
            append(",\"max_observation_gap_ms\":").append(rule.maximumObservationGapMillis)
            append(",\"rearm_presence_ms\":").append(rule.rearmPresenceMillis).append('}')
        }
        is RuntimeMonitorRule.ObjectCount -> {
            append("{\"type\":\"object_count\",\"operator\":")
                .appendJson(rule.operator.name.lowercase())
            append(",\"count\":").append(rule.count)
            append(",\"duration_ms\":").append(rule.durationMillis)
            append(",\"cooldown_ms\":").append(rule.cooldownMillis).append('}')
        }
        is RuntimeMonitorRule.ReadingThreshold.Single -> {
            append("{\"type\":\"reading_threshold\",\"operator\":")
                .appendJson(rule.operator.wireValue)
            append(",\"threshold_decimal\":").appendJson(rule.thresholdDecimal)
            append(",\"duration_ms\":").append(rule.durationMillis)
            append(",\"hysteresis_decimal\":").appendJson(rule.hysteresisDecimal)
            append(",\"cooldown_ms\":").append(rule.cooldownMillis)
            append(",\"source_kind\":").appendJson(rule.sourceKind.wireValue).append('}')
        }
        is RuntimeMonitorRule.ReadingThreshold.Outside -> {
            append("{\"type\":\"reading_threshold\",\"operator\":\"outside\"")
            append(",\"lower_threshold_decimal\":")
                .appendJson(rule.lowerThresholdDecimal)
            append(",\"upper_threshold_decimal\":")
                .appendJson(rule.upperThresholdDecimal)
            append(",\"duration_ms\":").append(rule.durationMillis)
            append(",\"hysteresis_decimal\":").appendJson(rule.hysteresisDecimal)
            append(",\"cooldown_ms\":").append(rule.cooldownMillis)
            append(",\"source_kind\":").appendJson(rule.sourceKind.wireValue).append('}')
        }
        is RuntimeMonitorRule.StateTransition -> {
            append("{\"type\":\"state_transition\",\"from_state\":")
                .appendJson(rule.fromState)
            append(",\"to_state\":").appendJson(rule.toState)
            append(",\"stable_frames\":").append(rule.stableFrames)
            append(",\"cooldown_ms\":").append(rule.cooldownMillis).append('}')
        }
    }
}

private fun StringBuilder.appendJson(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    return append('"')
}
