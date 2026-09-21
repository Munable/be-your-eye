package app.beyoureyes.monitor

import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.vision.RuntimeFrameMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MonitoringPhase {
    STOPPED,
    STARTING,
    RUNNING,
}

enum class MonitoringHealth {
    IDLE,
    WARMING,
    OBSERVING,
    THERMALLY_LIMITED,
    TEMPORARILY_UNAVAILABLE,
    FATAL,
}

data class MonitoringStatus(
    val phase: MonitoringPhase,
    val message: String? = null,
    val health: MonitoringHealth = phase.defaultHealth(),
    val monitorId: String? = null,
    val startedAtEpochMillis: Long? = null,
    val initialBlackScreenPending: Boolean = false,
    val monitorRevision: Long? = null,
    val stoppedWhenHidden: Boolean = false,
) {
    init {
        require(monitorRevision == null || monitorRevision >= 1)
        require(
            phase == MonitoringPhase.STOPPED && health in setOf(
                MonitoringHealth.IDLE,
                MonitoringHealth.FATAL,
            ) || phase == MonitoringPhase.STARTING && health == MonitoringHealth.WARMING ||
                phase == MonitoringPhase.RUNNING && health in setOf(
                    MonitoringHealth.WARMING,
                    MonitoringHealth.OBSERVING,
                    MonitoringHealth.THERMALLY_LIMITED,
                    MonitoringHealth.TEMPORARILY_UNAVAILABLE,
                ),
        ) { "monitoring phase and health are inconsistent" }
        require(
            (phase == MonitoringPhase.STOPPED && (
                health == MonitoringHealth.FATAL || monitorId == null
            )) ||
                (phase != MonitoringPhase.STOPPED && !monitorId.isNullOrBlank()),
        ) { "an active monitoring phase must identify exactly one monitor" }
    }
}

val MonitoringStatus.activeMonitorId: String?
    get() = monitorId.takeIf {
        phase == MonitoringPhase.STARTING || phase == MonitoringPhase.RUNNING
    }

/** One processed frame and its publication metadata; camera pixels never enter this state. */
data class MonitoringObservationSnapshot(
    val monitorId: String,
    val monitorRevision: Long,
    val observation: Observation,
    /** CameraX source clock, preserved for frame pairing; not assumed to be elapsedRealtime. */
    val capturedMonotonicMillis: Long,
    /** The following three timestamps all use SystemClock.elapsedRealtime. */
    val receivedElapsedMillis: Long,
    val completedElapsedMillis: Long,
    val publishedElapsedMillis: Long,
    val inferenceDurationMillis: Long,
    val pipelineDurationMillis: Long,
) {
    init {
        require(monitorId.isNotBlank() && monitorRevision >= 1)
        require(capturedMonotonicMillis >= 0 && receivedElapsedMillis >= 0)
        require(completedElapsedMillis >= receivedElapsedMillis)
        require(publishedElapsedMillis >= completedElapsedMillis)
        require(inferenceDurationMillis >= 0 && pipelineDurationMillis >= inferenceDurationMillis)
    }

    val sourceSequence: Long get() = observation.sourceSequence

    /** Bounded diagnostics deliberately omit identity, recognized values and retained readings. */
    internal fun diagnosticMetadata(): Map<String, String> = mapOf(
        "source_sequence" to sourceSequence.toString(),
        "captured_mono_ms" to capturedMonotonicMillis.toString(),
        "received_elapsed_ms" to receivedElapsedMillis.toString(),
        "completed_elapsed_ms" to completedElapsedMillis.toString(),
        "published_elapsed_ms" to publishedElapsedMillis.toString(),
        "inference_ms" to inferenceDurationMillis.toString(),
        "pipeline_ms" to pipelineDurationMillis.toString(),
        "observation_state" to when (observation) {
            is Observation.Reading -> if (observation.stable) "reading_stable" else "reading_candidate"
            is Observation.Unavailable -> "unavailable"
            is Observation.State -> "state"
            is Observation.Detections -> "detections"
        },
    )
}

/** In-process truth shared by the foreground service and the visible clock screen. */
object MonitoringRuntimeState {
    private val mutableStatus = MutableStateFlow(MonitoringStatus(MonitoringPhase.STOPPED))
    private val mutableFrameMetadata = MutableStateFlow<RuntimeFrameMetadata?>(null)
    private val mutableObservation = MutableStateFlow<MonitoringObservationSnapshot?>(null)
    private val mutableManualReadingScanRegion = MutableStateFlow<NormalizedRect?>(null)

    val status: StateFlow<MonitoringStatus> = mutableStatus.asStateFlow()
    val frameMetadata: StateFlow<RuntimeFrameMetadata?> = mutableFrameMetadata.asStateFlow()
    val latestObservationSnapshot: StateFlow<MonitoringObservationSnapshot?> = mutableObservation.asStateFlow()
    val manualReadingScanRegion: StateFlow<NormalizedRect?> = mutableManualReadingScanRegion.asStateFlow()

    /** A new process never restores STARTING/RUNNING; an interrupted run becomes explicit FATAL. */
    fun resetForProcessStart(
        interruptedMonitorId: String? = null,
        interruptedMessage: String? = null,
    ) {
        mutableStatus.value = if (interruptedMonitorId == null) {
            MonitoringStatus(MonitoringPhase.STOPPED)
        } else {
            MonitoringStatus(
                phase = MonitoringPhase.STOPPED,
                message = requireNotNull(interruptedMessage),
                health = MonitoringHealth.FATAL,
                monitorId = interruptedMonitorId,
            )
        }
        mutableFrameMetadata.value = null
        mutableObservation.value = null
        mutableManualReadingScanRegion.value = null
    }

    fun acknowledgeHiddenStop() {
        if (mutableStatus.value.stoppedWhenHidden) {
            mutableStatus.value = mutableStatus.value.copy(stoppedWhenHidden = false)
        }
    }

    fun update(
        next: MonitoringPhase,
        message: String? = null,
        health: MonitoringHealth = next.defaultHealth(),
        monitorId: String? = when (next) {
            MonitoringPhase.STOPPED -> if (health == MonitoringHealth.FATAL) {
                mutableStatus.value.monitorId
            } else {
                null
            }
            MonitoringPhase.STARTING,
            MonitoringPhase.RUNNING,
            -> mutableStatus.value.monitorId
        },
        initialBlackScreenPending: Boolean = mutableStatus.value.initialBlackScreenPending,
        monitorRevision: Long? = mutableStatus.value.monitorRevision.takeIf {
            next != MonitoringPhase.STOPPED && monitorId == mutableStatus.value.monitorId
        },
        stoppedWhenHidden: Boolean = false,
    ) {
        val current = mutableStatus.value
        val nextRevision = monitorRevision.takeUnless { next == MonitoringPhase.STOPPED }
        if (next == MonitoringPhase.STOPPED || next == MonitoringPhase.STARTING ||
            current.monitorId != monitorId || current.monitorRevision != nextRevision
        ) {
            mutableObservation.value = null
        }
        val startedAt = when (next) {
            MonitoringPhase.STOPPED -> null
            MonitoringPhase.STARTING,
            MonitoringPhase.RUNNING,
            -> current.startedAtEpochMillis.takeIf {
                current.monitorId == monitorId && current.phase != MonitoringPhase.STOPPED
            } ?: System.currentTimeMillis()
        }
        mutableStatus.value = MonitoringStatus(
            phase = next,
            message = message,
            health = health,
            monitorId = monitorId,
            startedAtEpochMillis = startedAt,
            initialBlackScreenPending = if (next == MonitoringPhase.STOPPED) {
                false
            } else {
                initialBlackScreenPending
            },
            monitorRevision = nextRevision,
            stoppedWhenHidden = next == MonitoringPhase.STOPPED && stoppedWhenHidden,
        )
        if (next == MonitoringPhase.STOPPED) mutableManualReadingScanRegion.value = null
    }

    fun recordFrame(metadata: RuntimeFrameMetadata) {
        mutableFrameMetadata.value = metadata
    }

    fun recordObservation(snapshot: MonitoringObservationSnapshot): Boolean {
        val current = mutableStatus.value
        if (current.activeMonitorId != snapshot.monitorId ||
            current.monitorRevision != snapshot.monitorRevision
        ) return false
        val previous = mutableObservation.value
        if (previous != null && (snapshot.sourceSequence <= previous.sourceSequence ||
                snapshot.publishedElapsedMillis < previous.publishedElapsedMillis)
        ) return false
        mutableObservation.value = snapshot
        return true
    }

    fun clearFrameMetadata() {
        mutableFrameMetadata.value = null
    }

    fun clearObservation() {
        mutableObservation.value = null
    }

    /** Immutable scene box chosen during setup; active monitoring never retargets it. */
    fun setManualReadingScanRegion(box: NormalizedRect?) {
        mutableManualReadingScanRegion.value = box
    }

    fun consumeInitialBlackScreen(monitorId: String?): Boolean {
        val current = mutableStatus.value
        if (monitorId == null || current.monitorId != monitorId ||
            !current.initialBlackScreenPending
        ) return false
        mutableStatus.value = current.copy(initialBlackScreenPending = false)
        return true
    }

    /** Clears the retained fatal card only when its monitor was actually deleted. */
    fun clearFatalForDeletedMonitor(monitorId: String): Boolean {
        val current = mutableStatus.value
        if (current.phase != MonitoringPhase.STOPPED ||
            current.health != MonitoringHealth.FATAL ||
            current.monitorId != monitorId
        ) {
            return false
        }
        mutableStatus.value = MonitoringStatus(MonitoringPhase.STOPPED)
        mutableFrameMetadata.value = null
        mutableObservation.value = null
        mutableManualReadingScanRegion.value = null
        return true
    }
}

private fun MonitoringPhase.defaultHealth(): MonitoringHealth = when (this) {
    MonitoringPhase.STOPPED -> MonitoringHealth.IDLE
    MonitoringPhase.STARTING -> MonitoringHealth.WARMING
    MonitoringPhase.RUNNING -> MonitoringHealth.OBSERVING
}
