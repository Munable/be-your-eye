package app.beyoureyes.monitor

import app.beyoureyes.core.vision.FrameSamplingPolicy

/** Android thermal levels kept independent from any model or runtime family. */
internal enum class RuntimeThermalLevel {
    UNKNOWN,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

internal enum class RuntimeThermalMode {
    NORMAL,
    LIMITED,
    PAUSED,
}

internal fun nextSetupThermalMode(
    current: RuntimeThermalMode,
    thermalLevel: RuntimeThermalLevel,
): RuntimeThermalMode = when (thermalLevel) {
    RuntimeThermalLevel.CRITICAL,
    RuntimeThermalLevel.EMERGENCY,
    RuntimeThermalLevel.SHUTDOWN,
    -> RuntimeThermalMode.PAUSED
    RuntimeThermalLevel.SEVERE -> if (current == RuntimeThermalMode.PAUSED) {
        RuntimeThermalMode.PAUSED
    } else {
        RuntimeThermalMode.LIMITED
    }
    RuntimeThermalLevel.NONE,
    RuntimeThermalLevel.LIGHT,
    RuntimeThermalLevel.MODERATE,
    -> RuntimeThermalMode.NORMAL
    RuntimeThermalLevel.UNKNOWN -> current
}

internal fun setupThermalSamplingIntervalMillis(
    mode: RuntimeThermalMode,
    fastestIntervalMillis: Long,
    signedDefaultIntervalMillis: Long,
    signedMaximumIntervalMillis: Long,
    adaptationAllowed: Boolean,
    processingDurationMillis: Long? = null,
): Long {
    require(
        fastestIntervalMillis in
            FrameSamplingPolicy.MIN_INTERVAL_MILLIS..FrameSamplingPolicy.MAX_INTERVAL_MILLIS,
    )
    require(signedMaximumIntervalMillis in
        fastestIntervalMillis..FrameSamplingPolicy.MAX_INTERVAL_MILLIS)
    require(signedDefaultIntervalMillis in fastestIntervalMillis..signedMaximumIntervalMillis)
    // The setup reducer needs two live observations no more than 1.5 seconds apart. Leave enough
    // CameraX scheduling margin while still reducing sustained setup work on slower devices.
    val setupAdaptiveMaximumIntervalMillis = maxOf(
        signedDefaultIntervalMillis,
        minOf(signedMaximumIntervalMillis, MAX_SETUP_ADAPTIVE_INTERVAL_MILLIS),
    )
    return if (mode == RuntimeThermalMode.NORMAL) {
        workloadAwareIntervalMillis(
            signedDefaultIntervalMillis = signedDefaultIntervalMillis,
            signedMinimumIntervalMillis = fastestIntervalMillis,
            signedMaximumIntervalMillis = setupAdaptiveMaximumIntervalMillis,
            adaptationAllowed = adaptationAllowed,
            processingDurationMillis = processingDurationMillis,
            processingMultiplier = NORMAL_PROCESSING_MULTIPLIER,
        )
    } else {
        thermallyLimitedIntervalMillis(
            signedDefaultIntervalMillis = signedDefaultIntervalMillis,
            signedMinimumIntervalMillis = fastestIntervalMillis,
            signedMaximumIntervalMillis = setupAdaptiveMaximumIntervalMillis,
            adaptationAllowed = adaptationAllowed,
            processingDurationMillis = processingDurationMillis,
        )
    }
}

private const val NORMAL_PROCESSING_MULTIPLIER = 8L
private const val LIMITED_PROCESSING_MULTIPLIER = 12L
private const val MAX_SETUP_ADAPTIVE_INTERVAL_MILLIS = 1_400L

private fun multipliedWithoutOverflow(value: Long, multiplier: Long): Long =
    if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier

private fun workloadAwareIntervalMillis(
    signedDefaultIntervalMillis: Long,
    signedMinimumIntervalMillis: Long,
    signedMaximumIntervalMillis: Long,
    adaptationAllowed: Boolean,
    processingDurationMillis: Long?,
    processingMultiplier: Long,
): Long {
    if (!adaptationAllowed || processingDurationMillis == null) {
        return signedDefaultIntervalMillis
    }
    return maxOf(
        signedDefaultIntervalMillis,
        multipliedWithoutOverflow(processingDurationMillis, processingMultiplier),
    ).coerceIn(signedMinimumIntervalMillis, signedMaximumIntervalMillis)
}

private fun thermallyLimitedIntervalMillis(
    signedDefaultIntervalMillis: Long,
    signedMinimumIntervalMillis: Long,
    signedMaximumIntervalMillis: Long,
    adaptationAllowed: Boolean,
    processingDurationMillis: Long? = null,
): Long {
    if (!adaptationAllowed) return signedDefaultIntervalMillis
    return maxOf(
        multipliedWithoutOverflow(signedDefaultIntervalMillis, 2L),
        processingDurationMillis?.let {
            multipliedWithoutOverflow(it, LIMITED_PROCESSING_MULTIPLIER)
        } ?: 0L,
    ).coerceIn(signedMinimumIntervalMillis, signedMaximumIntervalMillis)
}

internal data class RuntimeThermalCadenceDecision(
    val intervalMillis: Long,
    val mode: RuntimeThermalMode,
    val intervalChanged: Boolean,
    val modeChanged: Boolean,
)

/**
 * Transient thermal protection for one active monitoring session.
 *
 * KEEP_ONLY_LATEST prevents a backlog but not continuous CPU load. For adaptive Manifests, normal
 * cadence keeps measured processing at or below roughly 12.5% duty cycle inside the signed bounds.
 * SEVERE becomes more conservative, and CRITICAL or above pauses inference. Nothing is persisted
 * to the task. The first completed inference fixes the normal cadence for the session, so ordinary
 * latency jitter cannot repeatedly interrupt a user's partial rule evidence. Thirty continuous
 * safe seconds leave the thermal mode while retaining that fixed normal cadence.
 */
internal class RuntimeThermalCadenceController(
    private val signedDefaultIntervalMillis: Long,
    private val signedMinimumIntervalMillis: Long,
    private val signedMaximumIntervalMillis: Long,
    private val adaptationAllowed: Boolean,
    initialIntervalMillis: Long = signedDefaultIntervalMillis,
    private val recoveryWindowMillis: Long = DEFAULT_RECOVERY_WINDOW_MILLIS,
) {
    private var currentIntervalMillis = initialIntervalMillis
    private var currentMode = RuntimeThermalMode.NORMAL
    private var normalSinceMillis: Long? = null
    private var lastObservedAtMillis: Long? = null
    private var lastThermalLevel = RuntimeThermalLevel.UNKNOWN
    private var latestProcessingDurationMillis: Long? = null
    private var normalBaselineProcessingMillis: Long? = null
    private var severeBaselineProcessingMillis: Long? = null

    init {
        require(
            signedMinimumIntervalMillis in
                FrameSamplingPolicy.MIN_INTERVAL_MILLIS..FrameSamplingPolicy.MAX_INTERVAL_MILLIS,
        )
        require(signedMaximumIntervalMillis in
            signedMinimumIntervalMillis..FrameSamplingPolicy.MAX_INTERVAL_MILLIS)
        require(signedDefaultIntervalMillis in
            signedMinimumIntervalMillis..signedMaximumIntervalMillis)
        require(initialIntervalMillis in signedMinimumIntervalMillis..signedMaximumIntervalMillis)
        require(recoveryWindowMillis >= 0)
    }

    fun update(
        observedAtMonotonicMillis: Long,
        thermalLevel: RuntimeThermalLevel,
    ): RuntimeThermalCadenceDecision {
        require(observedAtMonotonicMillis >= 0)
        val previousTime = lastObservedAtMillis
        lastObservedAtMillis = observedAtMonotonicMillis
        if (previousTime != null && observedAtMonotonicMillis < previousTime) {
            normalSinceMillis = null
            return unchanged()
        }

        val previousInterval = currentIntervalMillis
        val previousMode = currentMode
        when (thermalLevel) {
            RuntimeThermalLevel.SEVERE -> {
                normalSinceMillis = null
                if (lastThermalLevel != RuntimeThermalLevel.SEVERE) {
                    severeBaselineProcessingMillis = latestProcessingDurationMillis
                } else if (severeBaselineProcessingMillis == null) {
                    severeBaselineProcessingMillis = latestProcessingDurationMillis
                }
                currentMode = RuntimeThermalMode.LIMITED
                currentIntervalMillis = limitedIntervalMillis(severeBaselineProcessingMillis)
            }
            RuntimeThermalLevel.CRITICAL,
            RuntimeThermalLevel.EMERGENCY,
            RuntimeThermalLevel.SHUTDOWN,
            -> {
                normalSinceMillis = null
                currentMode = RuntimeThermalMode.PAUSED
                currentIntervalMillis = limitedIntervalMillis()
            }
            RuntimeThermalLevel.NONE,
            RuntimeThermalLevel.LIGHT,
            RuntimeThermalLevel.MODERATE,
            -> {
                if (currentMode == RuntimeThermalMode.NORMAL) {
                    normalSinceMillis = null
                    currentIntervalMillis = normalIntervalMillis()
                } else {
                    val since = normalSinceMillis ?: observedAtMonotonicMillis.also {
                        normalSinceMillis = it
                    }
                    currentMode = RuntimeThermalMode.LIMITED
                    if (observedAtMonotonicMillis - since >= recoveryWindowMillis) {
                        currentMode = RuntimeThermalMode.NORMAL
                        currentIntervalMillis = normalIntervalMillis()
                        normalSinceMillis = null
                        severeBaselineProcessingMillis = null
                    }
                }
            }
            RuntimeThermalLevel.UNKNOWN -> Unit
        }
        if (thermalLevel != RuntimeThermalLevel.UNKNOWN) lastThermalLevel = thermalLevel
        return RuntimeThermalCadenceDecision(
            intervalMillis = currentIntervalMillis,
            mode = currentMode,
            intervalChanged = currentIntervalMillis != previousInterval,
            modeChanged = currentMode != previousMode,
        )
    }

    /** Records work while latching one stable normal-session baseline. */
    fun recordProcessingDuration(durationMillis: Long) {
        require(durationMillis >= 0)
        val measured = durationMillis.coerceAtLeast(1L)
        latestProcessingDurationMillis = measured
        when (currentMode) {
            RuntimeThermalMode.NORMAL -> if (normalBaselineProcessingMillis == null) {
                normalBaselineProcessingMillis = measured
            }
            RuntimeThermalMode.LIMITED -> if (severeBaselineProcessingMillis == null) {
                severeBaselineProcessingMillis = measured
            }
            RuntimeThermalMode.PAUSED -> Unit
        }
    }

    private fun limitedIntervalMillis(processingDurationMillis: Long? = severeBaselineProcessingMillis): Long {
        return thermallyLimitedIntervalMillis(
            signedDefaultIntervalMillis = signedDefaultIntervalMillis,
            signedMinimumIntervalMillis = signedMinimumIntervalMillis,
            signedMaximumIntervalMillis = signedMaximumIntervalMillis,
            adaptationAllowed = adaptationAllowed,
            processingDurationMillis = processingDurationMillis,
        )
    }

    private fun normalIntervalMillis(): Long = workloadAwareIntervalMillis(
        signedDefaultIntervalMillis = signedDefaultIntervalMillis,
        signedMinimumIntervalMillis = signedMinimumIntervalMillis,
        signedMaximumIntervalMillis = signedMaximumIntervalMillis,
        adaptationAllowed = adaptationAllowed,
        processingDurationMillis = normalBaselineProcessingMillis,
        processingMultiplier = NORMAL_PROCESSING_MULTIPLIER,
    )

    private fun unchanged() = RuntimeThermalCadenceDecision(
        intervalMillis = currentIntervalMillis,
        mode = currentMode,
        intervalChanged = false,
        modeChanged = false,
    )

    companion object {
        const val DEFAULT_RECOVERY_WINDOW_MILLIS = 30_000L
    }
}
