package app.beyoureyes.monitor

import android.os.PowerManager
import app.beyoureyes.core.data.ModelPackageRejection
import app.beyoureyes.core.vision.ManifestKnownAnswerSelfTestResult
import app.beyoureyes.core.vision.RuntimeActivationError

internal class MonitoringRuntimeInitializationException(
    val error: MonitoringRuntimeInitializationError,
    val packageRejections: Set<ModelPackageRejection> = emptySet(),
    val activationErrors: Set<RuntimeActivationError> = emptySet(),
    cause: Throwable? = null,
) : IllegalStateException(
    "monitoring runtime initialization failed: $error; " +
        "package=$packageRejections; runtime=$activationErrors",
    cause,
)

internal enum class MonitoringRuntimeInitializationError {
    TASK_NOT_FOUND,
    TASK_REVISION_CHANGED,
    TASK_INVALID,
    TASK_PACKAGE_UNBOUND,
    SAMPLING_CONFIG_INVALID,
    PACKAGE_UNAVAILABLE,
    MANIFEST_INVALID,
    BUILD_CHANNEL_INVALID,
    RULE_FAMILY_MISMATCH,
    RUNTIME_INCOMPATIBLE,
}

/** A stored preparation flag never substitutes for a fresh lease-bound reading KAT. */
internal fun requireMonitoringKnownAnswerSelfTest(
    result: ManifestKnownAnswerSelfTestResult,
) {
    if (result is ManifestKnownAnswerSelfTestResult.Failed) {
        throw MonitoringRuntimeInitializationException(
            MonitoringRuntimeInitializationError.RUNTIME_INCOMPATIBLE,
            activationErrors = result.errors,
        )
    }
}

/** Testable, package-neutral mapping for the Android 10+ PowerManager thermal contract. */
internal fun runtimeThermalLevelFromAndroidStatus(status: Int?): RuntimeThermalLevel = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> RuntimeThermalLevel.NONE
    PowerManager.THERMAL_STATUS_LIGHT -> RuntimeThermalLevel.LIGHT
    PowerManager.THERMAL_STATUS_MODERATE -> RuntimeThermalLevel.MODERATE
    PowerManager.THERMAL_STATUS_SEVERE -> RuntimeThermalLevel.SEVERE
    PowerManager.THERMAL_STATUS_CRITICAL -> RuntimeThermalLevel.CRITICAL
    PowerManager.THERMAL_STATUS_EMERGENCY -> RuntimeThermalLevel.EMERGENCY
    PowerManager.THERMAL_STATUS_SHUTDOWN -> RuntimeThermalLevel.SHUTDOWN
    else -> RuntimeThermalLevel.UNKNOWN
}

internal fun elapsedNanosToCeilingMillis(nanos: Long): Long {
    require(nanos >= 0)
    return if (nanos == 0L) {
        0L
    } else {
        (nanos - 1L) / ELAPSED_NANOS_PER_MILLISECOND + 1L
    }
}

private const val ELAPSED_NANOS_PER_MILLISECOND = 1_000_000L

internal fun resumedSourceSequenceBase(current: Long, persisted: Long?): Long {
    require(current >= 0)
    require(persisted == null || persisted in 0 until Long.MAX_VALUE) {
        "latest reading source sequence is exhausted"
    }
    return maxOf(current, persisted ?: 0)
}
