package app.beyoureyes.monitor

import app.beyoureyes.core.data.EventTaskSnapshot
import app.beyoureyes.core.data.EventWriteRequest
import app.beyoureyes.core.data.EventWriteResult
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.domain.GenericObservationRuleEngine
import app.beyoureyes.core.vision.FrameSamplingPolicy
import app.beyoureyes.core.vision.ModelPackageRuntime
import app.beyoureyes.core.vision.MonotonicFrameSampler
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.core.vision.RuntimeFrameResult
import app.beyoureyes.core.vision.SourceFrame

internal fun interface MonitoringFrameRuntime {
    fun process(frame: SourceFrame): RuntimeFrameResult

    /** Resets any sampling gate owned by the underlying runtime. */
    fun resetSampling() = Unit

    /** Applies one already policy-validated generic cadence. */
    fun updateSamplingPolicy(policy: FrameSamplingPolicy) = Unit
}

internal data class GenericMonitoringRuntimeResult(
    val runtimeResult: RuntimeFrameResult,
    val eventRequest: EventWriteRequest? = null,
    val eventWriteResult: EventWriteResult? = null,
    val ruleEvaluationNanos: Long = 0L,
    val eventWriteNanos: Long = 0L,
) {
    init {
        require((eventRequest == null) == (eventWriteResult == null))
        require(ruleEvaluationNanos >= 0L)
        require(eventWriteNanos >= 0L)
    }
}

/** Shared runtime/rule/durable-event boundary for CameraX and future replay callers. */
internal class GenericMonitoringRuntimeBridge internal constructor(
    private val runtime: MonitoringFrameRuntime,
    private val taskSnapshot: EventTaskSnapshot,
    private val coordinator: EventNotificationCoordinator,
    private val ruleEngine: GenericObservationRuleEngine,
    samplingPolicy: FrameSamplingPolicy,
    private val beforeEventPersist: suspend (
        EventWriteRequest,
        SourceFrame,
    ) -> (() -> Unit)? = { _, _ -> null },
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val uuidV7: (Long) -> String = UuidV7::generate,
) {
    private var samplingPolicy: FrameSamplingPolicy = samplingPolicy
    private val sampler = MonotonicFrameSampler(samplingPolicy)

    constructor(
        runtime: ModelPackageRuntime,
        taskSnapshot: EventTaskSnapshot,
        coordinator: EventNotificationCoordinator,
        ruleEngine: GenericObservationRuleEngine,
        samplingPolicy: FrameSamplingPolicy = runtime.samplingPolicy,
        beforeEventPersist: suspend (
            EventWriteRequest,
            SourceFrame,
        ) -> (() -> Unit)? = { _, _ -> null },
        epochMillis: () -> Long = System::currentTimeMillis,
        uuidV7: (Long) -> String = UuidV7::generate,
    ) : this(
        runtime = object : MonitoringFrameRuntime {
            override fun process(frame: SourceFrame): RuntimeFrameResult = runtime.process(frame)

            override fun resetSampling() = runtime.resetSampling()

            override fun updateSamplingPolicy(policy: FrameSamplingPolicy) =
                runtime.updateSamplingPolicy(policy)
        },
        taskSnapshot = taskSnapshot,
        coordinator = coordinator,
        ruleEngine = ruleEngine,
        samplingPolicy = samplingPolicy,
        beforeEventPersist = beforeEventPersist,
        epochMillis = epochMillis,
        uuidV7 = uuidV7,
    )

    suspend fun acceptLazy(
        sourceSequence: Long,
        monotonicTimeMillis: Long,
        frameFactory: () -> SourceFrame,
    ): GenericMonitoringRuntimeResult {
        if (!sampler.shouldProcess(monotonicTimeMillis)) {
            return GenericMonitoringRuntimeResult(RuntimeFrameResult.Skipped(sourceSequence))
        }
        return accept(frameFactory())
    }

    suspend fun accept(frame: SourceFrame): GenericMonitoringRuntimeResult {
        val runtimeResult = runtime.process(frame)
        val processed = runtimeResult as? RuntimeFrameResult.Processed
            ?: return GenericMonitoringRuntimeResult(runtimeResult)
        val pipelineResult = processed.pipelineResult
        val ruleStartedNanos = System.nanoTime()
        var ruleEvaluationNanos = 0L
        val candidate = try {
            ruleEngine.evaluate(
                observation = pipelineResult.observation,
                monotonicMillis = pipelineResult.monotonicTimeMillis,
                capturedAtEpochMillis = pipelineResult.capturedAtEpochMillis,
            )
        } finally {
            // The runtime diagnostics layer records this duration without the observation.
            ruleEvaluationNanos = (System.nanoTime() - ruleStartedNanos).coerceAtLeast(0L)
        }
            ?: return GenericMonitoringRuntimeResult(
                runtimeResult,
                ruleEvaluationNanos = ruleEvaluationNanos,
            )
        val occurredAt = processed.pipelineResult.capturedAtEpochMillis ?: epochMillis()
        val request = candidate.toEventWriteRequest(occurredAt)
        val rollbackPreparedEvent = beforeEventPersist(request, frame)
        val eventStartedNanos = System.nanoTime()
        var eventWriteNanos = 0L
        val writeResult = try {
            coordinator.persistThenNotify(request)
        } catch (failure: Exception) {
            runCatching { rollbackPreparedEvent?.invoke() }
            throw failure
        } finally {
            eventWriteNanos = (System.nanoTime() - eventStartedNanos).coerceAtLeast(0L)
        }
        if (writeResult.eventId != request.eventId) {
            runCatching { rollbackPreparedEvent?.invoke() }
        }
        return GenericMonitoringRuntimeResult(
            runtimeResult = runtimeResult,
            eventRequest = request,
            eventWriteResult = writeResult,
            ruleEvaluationNanos = ruleEvaluationNanos,
            eventWriteNanos = eventWriteNanos,
        )
    }

    suspend fun closeOpenReferenceEpisodeForMonitoringStop(
        sourceSequence: Long,
        monotonicTimeMillis: Long,
        occurredAtEpochMillis: Long = epochMillis(),
    ): EventWriteResult? {
        require(occurredAtEpochMillis >= 0)
        val candidate = ruleEngine.closeOpenReferenceEpisodeForMonitoringStop(
            sourceSequence = sourceSequence,
            monotonicMillis = monotonicTimeMillis,
        ) ?: return null
        return coordinator.persistSilently(candidate.toEventWriteRequest(occurredAtEpochMillis))
    }

    /** Updates both sampling gates and resets only partial cadence-dependent rule evidence. */
    fun updateSamplingPolicy(policy: FrameSamplingPolicy) {
        runtime.updateSamplingPolicy(policy)
        sampler.updatePolicy(policy)
        samplingPolicy = policy
        ruleEngine.updateSamplingInterval(policy.analysisIntervalMillis)
    }

    fun currentSamplingPolicy(): FrameSamplingPolicy = samplingPolicy

    fun reset() {
        runtime.resetSampling()
        ruleEngine.reset()
        sampler.reset()
    }

    private fun app.beyoureyes.core.domain.RuntimeEventCandidate.toEventWriteRequest(
        occurredAtEpochMillis: Long,
    ): EventWriteRequest {
        val episodeId = uuidV7(occurredAtEpochMillis)
        return EventWriteRequest(
            eventId = uuidV7(occurredAtEpochMillis),
            task = taskSnapshot,
            episodeId = episodeId,
            sourceSequence = sourceSequence,
            occurredAtEpochMillis = occurredAtEpochMillis,
            payload = payload,
            notificationText = notificationText,
        )
    }
}
