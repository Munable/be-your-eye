package app.beyoureyes.core.domain

import java.math.BigDecimal

data class RuntimeEventCandidate(
    val episode: Long,
    val sourceSequence: Long,
    val triggeredAtMonotonicMillis: Long,
    val payload: RestrictedEventPayload,
    val notificationText: String,
) {
    init {
        require(episode >= 1 && sourceSequence >= 0 && triggeredAtMonotonicMillis >= 0)
        require(notificationText.isNotBlank())
    }
}

fun interface RuntimeEventNotificationTextFormatter {
    fun format(displayName: String, payload: RestrictedEventPayload): String
}

object EnglishRuntimeEventNotificationTextFormatter : RuntimeEventNotificationTextFormatter {
    override fun format(displayName: String, payload: RestrictedEventPayload): String = when (payload) {
        is RestrictedEventPayload.ObjectEpisode -> when (payload.condition) {
            ObjectEventCondition.APPEARED -> "$displayName appeared; recording started"
            ObjectEventCondition.DISAPPEARED -> "$displayName left"
            ObjectEventCondition.COUNT_MATCHED ->
                "$displayName count condition met (${payload.count})"
        }
        is RestrictedEventPayload.VisualConditionMet -> when (payload.condition) {
            VisualEventCondition.PRESENT_FOR_DURATION ->
                "$displayName remained visible for ${payload.durationMillis.englishDurationLabel()}"
            VisualEventCondition.ABSENT_FOR_DURATION ->
                "$displayName remained absent for ${payload.durationMillis.englishDurationLabel()}"
        }
        is RestrictedEventPayload.ReadingThresholdCrossed ->
            "$displayName reading crossed the condition: ${payload.displayText}"
        is RestrictedEventPayload.StateTransition -> if (
            payload.fromState == MonitoringSessionTransition.REFERENCE_EPISODE_OPEN &&
            payload.toState == MonitoringSessionTransition.MONITORING_STOPPED
        ) {
            "$displayName monitoring stopped; episode ended"
        } else {
            "$displayName changed from ${payload.fromState} to ${payload.toState}"
        }
    }
}

/** Closed Observation -> product rule engine. Each rule defines fail-closed unavailable semantics. */
class GenericObservationRuleEngine(
    private val targetId: String,
    private val displayName: String,
    private val roi: NormalizedRect,
    rule: RuntimeMonitorRule,
    private val notificationTextFormatter: RuntimeEventNotificationTextFormatter =
        EnglishRuntimeEventNotificationTextFormatter,
    private val epochMillis: () -> Long = System::currentTimeMillis,
) : ObservationRuleEngine<RuntimeEventCandidate> {
    private var rule: RuntimeMonitorRule = rule
    private sealed interface Phase {
        data object Armed : Phase
        data class Candidate(
            val startedAt: Long,
            val lastMatchedAt: Long,
            val count: Int,
            val valueKey: String? = null,
        ) : Phase
        data class Latched(
            val episode: Long,
            val rearmStartedAt: Long? = null,
        ) : Phase
    }

    private data class TimedEvidence(
        val accumulatedMillis: Long,
        val lastMatchedAt: Long?,
        val pausedAt: Long?,
        val count: Int,
    ) {
        init {
            require(accumulatedMillis >= 0 && count >= 1)
            require(lastMatchedAt != null || pausedAt != null)
        }

        fun pause(now: Long, maximumGapMillis: Long): TimedEvidence {
            val prior = lastMatchedAt ?: return copy(pausedAt = pausedAt ?: now)
            val elapsed = now - prior
            require(elapsed >= 0)
            if (elapsed > maximumGapMillis) {
                return copy(
                    accumulatedMillis = 0,
                    lastMatchedAt = null,
                    pausedAt = prior,
                    count = 1,
                )
            }
            return copy(
                accumulatedMillis = accumulatedMillis + elapsed,
                lastMatchedAt = null,
                pausedAt = now,
            )
        }

        fun advance(now: Long, maximumGapMillis: Long): TimedEvidence {
            val prior = lastMatchedAt
            val pause = pausedAt
            if (prior == null) {
                if (pause == null || now - pause > maximumGapMillis) {
                    return TimedEvidence(0, now, null, 1)
                }
                return copy(lastMatchedAt = now, pausedAt = null, count = count + 1)
            }
            if (now - prior > maximumGapMillis) {
                return TimedEvidence(0, now, null, 1)
            }
            return copy(
                accumulatedMillis = accumulatedMillis + (now - prior),
                lastMatchedAt = now,
                pausedAt = null,
                count = count + 1,
            )
        }

        companion object {
            fun start(now: Long) = TimedEvidence(0, now, null, 1)
        }
    }

    private sealed interface PresenceEpisodePhase {
        data object Absent : PresenceEpisodePhase
        data class Entering(val evidence: TimedEvidence) : PresenceEpisodePhase
        data class Present(val confirmedAt: Long) : PresenceEpisodePhase
        data class Leaving(
            val confirmedAt: Long,
            val evidence: TimedEvidence,
        ) : PresenceEpisodePhase
    }

    private var phase: Phase = Phase.Armed
    private var presenceEpisodePhase: PresenceEpisodePhase = PresenceEpisodePhase.Absent
    private var nextEpisode = 1L
    private val inputGuard = ObservationInputGuard()
    private var lastTriggeredAt: Long? = null
    private var stateTransitionArmed = false
    private var absenceSawPresent = false

    init {
        require(targetId.isNotBlank())
        require(displayName.isNotBlank())
        requireVisualSamplingResolved(rule)
    }

    override fun evaluate(observation: Observation, monotonicMillis: Long): RuntimeEventCandidate? =
        evaluate(observation, monotonicMillis, capturedAtEpochMillis = null)

    fun evaluate(
        observation: Observation,
        monotonicMillis: Long,
        capturedAtEpochMillis: Long?,
    ): RuntimeEventCandidate? {
        require(capturedAtEpochMillis == null || capturedAtEpochMillis >= 0)
        when (inputGuard.accept(observation.sourceSequence, monotonicMillis)) {
            InputDisposition.IGNORED_SEQUENCE -> return null
            InputDisposition.CLOCK_ROLLBACK -> {
                interruptCandidate()
                return null
            }
            InputDisposition.ACCEPTED -> Unit
        }
        return when (val configured = rule) {
            is RuntimeMonitorRule.PresenceEpisode -> evaluatePresenceEpisode(
                observation,
                monotonicMillis,
                configured,
            )
            is RuntimeMonitorRule.Presence -> evaluateVisualDuration(
                observation,
                monotonicMillis,
                desiredPresent = true,
                durationMillis = configured.durationMillis,
                minimumCount = configured.minimumPositiveCount,
                maximumGapMillis = configured.maximumPositiveGapMillis,
                rearmMillis = configured.rearmAbsenceMillis,
            )
            is RuntimeMonitorRule.Absence -> evaluateAbsence(
                observation,
                monotonicMillis,
                configured,
            )
            is RuntimeMonitorRule.ObjectCount -> evaluateObjectCount(
                observation,
                monotonicMillis,
                configured,
            )
            is RuntimeMonitorRule.ReadingThreshold -> evaluateReading(
                observation,
                monotonicMillis,
                capturedAtEpochMillis,
                configured,
            )
            is RuntimeMonitorRule.StateTransition -> evaluateStateTransition(
                observation,
                monotonicMillis,
                configured,
            )
        }
    }

    override fun interruptCandidate() {
        phase = when (val current = phase) {
            Phase.Armed, is Phase.Candidate -> Phase.Armed
            is Phase.Latched -> current.copy(rearmStartedAt = null)
        }
        presenceEpisodePhase = when (val current = presenceEpisodePhase) {
            PresenceEpisodePhase.Absent,
            is PresenceEpisodePhase.Present,
            -> current
            is PresenceEpisodePhase.Entering -> PresenceEpisodePhase.Absent
            is PresenceEpisodePhase.Leaving -> PresenceEpisodePhase.Present(current.confirmedAt)
        }
    }

    /**
     * Re-derives cadence-dependent evidence counts/gaps and interrupts any partial candidate. A
     * cadence transition can therefore never complete an episode using mixed sampling semantics.
     */
    fun updateSamplingInterval(samplingIntervalMillis: Long) {
        val resolved = rule.resolveForSampling(samplingIntervalMillis)
        requireVisualSamplingResolved(resolved)
        rule = resolved
        interruptCandidate()
    }

    override fun reset() {
        phase = Phase.Armed
        presenceEpisodePhase = PresenceEpisodePhase.Absent
        nextEpisode = 1
        lastTriggeredAt = null
        stateTransitionArmed = false
        absenceSawPresent = false
        inputGuard.reset()
    }

    /**
     * Closes an observed reference episode when monitoring itself ends. This is deliberately a
     * state transition rather than a synthetic disappearance: stopping the camera does not prove
     * that the target left the scene.
     */
    fun closeOpenReferenceEpisodeForMonitoringStop(
        sourceSequence: Long,
        monotonicMillis: Long,
    ): RuntimeEventCandidate? {
        require(sourceSequence >= 0)
        require(monotonicMillis >= 0)
        val open = when (presenceEpisodePhase) {
            is PresenceEpisodePhase.Present,
            is PresenceEpisodePhase.Leaving,
            -> true
            PresenceEpisodePhase.Absent,
            is PresenceEpisodePhase.Entering,
            -> false
        }
        presenceEpisodePhase = PresenceEpisodePhase.Absent
        if (!open) return null
        val episode = nextEpisode++
        val payload = RestrictedEventPayload.StateTransition(
            fromState = MonitoringSessionTransition.REFERENCE_EPISODE_OPEN,
            toState = MonitoringSessionTransition.MONITORING_STOPPED,
        )
        return RuntimeEventCandidate(
            episode = episode,
            sourceSequence = sourceSequence,
            triggeredAtMonotonicMillis = monotonicMillis,
            payload = payload,
            notificationText = notificationTextFormatter.format(displayName, payload),
        )
    }

    private fun requireVisualSamplingResolved(value: RuntimeMonitorRule) {
        when (value) {
            is RuntimeMonitorRule.PresenceEpisode -> checkNotNull(value.samplingIntervalMillis) {
                "presence episode rule must be resolved against task-bound sampling"
            }
            is RuntimeMonitorRule.Presence -> checkNotNull(value.samplingIntervalMillis) {
                "presence rule must be resolved against task-bound sampling"
            }
            is RuntimeMonitorRule.Absence -> checkNotNull(value.samplingIntervalMillis) {
                "absence rule must be resolved against task-bound sampling"
            }
            is RuntimeMonitorRule.ObjectCount -> checkNotNull(value.samplingIntervalMillis) {
                "object count rule must be resolved against task-bound sampling"
            }
            is RuntimeMonitorRule.ReadingThreshold,
            is RuntimeMonitorRule.StateTransition,
            -> Unit
        }
    }

    private fun evaluatePresenceEpisode(
        observation: Observation,
        now: Long,
        configured: RuntimeMonitorRule.PresenceEpisode,
    ): RuntimeEventCandidate? {
        val signal = observation.visualPresenceOrNull()
        if (signal == null) {
            presenceEpisodePhase = when (val current = presenceEpisodePhase) {
                is PresenceEpisodePhase.Entering -> current.copy(
                    evidence = current.evidence.pause(
                        now,
                        configured.maximumObservationGapMillis,
                    ),
                )
                is PresenceEpisodePhase.Leaving -> current.copy(
                    evidence = current.evidence.pause(
                        now,
                        configured.maximumObservationGapMillis,
                    ),
                )
                PresenceEpisodePhase.Absent,
                is PresenceEpisodePhase.Present,
                -> current
            }
            return null
        }
        return when (val current = presenceEpisodePhase) {
            PresenceEpisodePhase.Absent -> {
                if (signal) {
                    presenceEpisodePhase = PresenceEpisodePhase.Entering(TimedEvidence.start(now))
                }
                null
            }
            is PresenceEpisodePhase.Entering -> {
                if (!signal) {
                    presenceEpisodePhase = PresenceEpisodePhase.Absent
                    null
                } else {
                    val evidence = current.evidence.advance(
                        now,
                        configured.maximumObservationGapMillis,
                    )
                    if (evidence.count >= configured.minimumObservationCount &&
                        evidence.accumulatedMillis >= configured.appearanceConfirmMillis
                    ) {
                        presenceEpisodePhase = PresenceEpisodePhase.Present(now)
                        presenceEpisodeCandidate(
                            observation = observation,
                            condition = ObjectEventCondition.APPEARED,
                            durationMillis = 0,
                            now = now,
                        )
                    } else {
                        presenceEpisodePhase = current.copy(evidence = evidence)
                        null
                    }
                }
            }
            is PresenceEpisodePhase.Present -> {
                if (!signal) {
                    presenceEpisodePhase = PresenceEpisodePhase.Leaving(
                        current.confirmedAt,
                        TimedEvidence.start(now),
                    )
                }
                null
            }
            is PresenceEpisodePhase.Leaving -> {
                if (signal) {
                    presenceEpisodePhase = PresenceEpisodePhase.Present(current.confirmedAt)
                    null
                } else {
                    val evidence = current.evidence.advance(
                        now,
                        configured.maximumObservationGapMillis,
                    )
                    if (evidence.count >= configured.minimumObservationCount &&
                        evidence.accumulatedMillis >= configured.disappearanceConfirmMillis
                    ) {
                        presenceEpisodePhase = PresenceEpisodePhase.Absent
                        presenceEpisodeCandidate(
                            observation = observation,
                            condition = ObjectEventCondition.DISAPPEARED,
                            durationMillis = (now - current.confirmedAt).coerceAtLeast(0),
                            now = now,
                        )
                    } else {
                        presenceEpisodePhase = current.copy(evidence = evidence)
                        null
                    }
                }
            }
        }
    }

    private fun presenceEpisodeCandidate(
        observation: Observation,
        condition: ObjectEventCondition,
        durationMillis: Long,
        now: Long,
    ): RuntimeEventCandidate {
        val episode = nextEpisode++
        val payload = RestrictedEventPayload.ObjectEpisode(
            targetId = targetId,
            condition = condition,
            durationMillis = durationMillis,
            count = if (condition == ObjectEventCondition.APPEARED) 1 else 0,
        )
        return RuntimeEventCandidate(
            episode = episode,
            sourceSequence = observation.sourceSequence,
            triggeredAtMonotonicMillis = now,
            payload = payload,
            notificationText = notificationTextFormatter.format(displayName, payload),
        )
    }

    private fun evaluateVisualDuration(
        observation: Observation,
        now: Long,
        desiredPresent: Boolean,
        durationMillis: Long,
        minimumCount: Int,
        maximumGapMillis: Long,
        rearmMillis: Long,
    ): RuntimeEventCandidate? {
        val signal = observation.visualPresenceOrNull()
        if (signal == null) {
            interruptCandidate()
            return null
        }
        val matches = signal == desiredPresent
        return when (val current = phase) {
            Phase.Armed -> {
                if (matches) phase = Phase.Candidate(now, now, 1)
                null
            }
            is Phase.Candidate -> {
                if (!matches) {
                    phase = Phase.Armed
                    null
                } else if (now - current.lastMatchedAt > maximumGapMillis) {
                    phase = Phase.Candidate(now, now, 1)
                    null
                } else {
                    val updated = current.copy(lastMatchedAt = now, count = current.count + 1)
                    if (now - updated.startedAt >= durationMillis && updated.count >= minimumCount) {
                        visualCandidate(observation, desiredPresent, durationMillis, now)
                    } else {
                        phase = updated
                        null
                    }
                }
            }
            is Phase.Latched -> {
                if (matches) {
                    phase = current.copy(rearmStartedAt = null)
                } else {
                    val started = current.rearmStartedAt
                    phase = when {
                        started == null -> current.copy(rearmStartedAt = now)
                        now - started >= rearmMillis -> Phase.Armed
                        else -> current
                    }
                }
                null
            }
        }
    }

    /** A disappearance condition is impossible until this task has observed the target. */
    private fun evaluateAbsence(
        observation: Observation,
        now: Long,
        configured: RuntimeMonitorRule.Absence,
    ): RuntimeEventCandidate? {
        val signal = observation.visualPresenceOrNull()
        if (signal == null) {
            // Unknown interrupts timing evidence but does not fabricate or erase a known state.
            interruptCandidate()
            return null
        }
        if (signal) absenceSawPresent = true
        if (!signal && phase !is Phase.Latched && !absenceSawPresent) {
            interruptCandidate()
            return null
        }
        return evaluateVisualDuration(
            observation = observation,
            now = now,
            desiredPresent = false,
            durationMillis = configured.durationMillis,
            minimumCount = configured.minimumNegativeCount,
            maximumGapMillis = configured.maximumObservationGapMillis,
            rearmMillis = configured.rearmPresenceMillis,
        )?.also {
            // The next disappearance condition must earn a new observed-present fact while rearming.
            absenceSawPresent = false
        }
    }

    private fun visualCandidate(
        observation: Observation,
        present: Boolean,
        durationMillis: Long,
        now: Long,
    ): RuntimeEventCandidate {
        val episode = nextEpisode++
        phase = Phase.Latched(episode)
        val payload = RestrictedEventPayload.VisualConditionMet(
            targetId = targetId,
            condition = if (present) {
                VisualEventCondition.PRESENT_FOR_DURATION
            } else {
                VisualEventCondition.ABSENT_FOR_DURATION
            },
            durationMillis = durationMillis,
        )
        return RuntimeEventCandidate(
            episode = episode,
            sourceSequence = observation.sourceSequence,
            triggeredAtMonotonicMillis = now,
            payload = payload,
            notificationText = notificationTextFormatter.format(displayName, payload),
        )
    }

    private fun evaluateObjectCount(
        observation: Observation,
        now: Long,
        configured: RuntimeMonitorRule.ObjectCount,
    ): RuntimeEventCandidate? {
        val detections = observation as? Observation.Detections
        if (detections == null) {
            interruptCandidate()
            return null
        }
        val count = detections.items.count { detection ->
            detection.label == targetId && roi.containsCenterOf(detection.box)
        }
        val matches = when (configured.operator) {
            CountOperator.EQ -> count == configured.count
            CountOperator.GTE -> count >= configured.count
            CountOperator.LTE -> count <= configured.count
        }
        return evaluateCooldownDuration(
            observation = observation,
            now = now,
            matches = matches,
            valueKey = "count_condition_matched",
            durationMillis = configured.durationMillis,
            minimumCount = configured.minimumMatchCount,
            maximumGapMillis = configured.maximumObservationGapMillis,
            cooldownMillis = configured.cooldownMillis,
        ) {
            RestrictedEventPayload.ObjectEpisode(
                targetId = targetId,
                condition = ObjectEventCondition.COUNT_MATCHED,
                durationMillis = configured.durationMillis,
                count = count,
            )
        }
    }

    private fun evaluateReading(
        observation: Observation,
        now: Long,
        capturedAtEpochMillis: Long?,
        configured: RuntimeMonitorRule.ReadingThreshold,
    ): RuntimeEventCandidate? {
        if (!configured.configured) {
            // Pending-baseline task: readings are observed and recorded, but an unconfirmed
            // baseline must never produce a condition event.
            interruptCandidate()
            return null
        }
        val reading = observation as? Observation.Reading
        if (reading == null) {
            interruptCandidate()
            return null
        }
        val threshold = configured.thresholdDecimal?.let(::canonicalDecimal)
        val lowerThreshold = configured.lowerThresholdDecimal?.let(::canonicalDecimal)
        val upperThreshold = configured.upperThresholdDecimal?.let(::canonicalDecimal)
        val hysteresis = canonicalDecimal(configured.hysteresisDecimal).also { require(it.signum() >= 0) }
        val value = reading.decimalValue
        val matches = value.matches(
            configured.operator,
            threshold,
            lowerThreshold,
            upperThreshold,
            hysteresis,
        )
        val current = phase
        if (current is Phase.Latched) {
            if (value.isReadingRearmed(
                    configured.operator,
                    threshold,
                    lowerThreshold,
                    upperThreshold,
                    hysteresis,
                )
            ) {
                phase = Phase.Armed
            }
            return null
        }
        if (!matches) {
            phase = Phase.Armed
            return null
        }
        if (!cooldownElapsed(now, configured.cooldownMillis)) return null
        val evidenceKey = buildString {
            append("reading:").append(reading.targetTrackId).append(':')
            append(reading.format.kind.wireValue).append(':')
            append(reading.format.fractionalDigits).append(':')
            append(reading.format.timeSegments ?: 0).append(':')
            append(reading.format.unit.orEmpty()).append(':')
            append(configured.operator.wireValue)
        }
        // The task-scoped tracker owns the gap/format/position window. A non-stable reading is
        // therefore the first observation of a new condition window, never the continuation of
        // an older rule candidate.
        if (!reading.stable) {
            phase = Phase.Candidate(now, now, 1, evidenceKey)
            return null
        }
        val candidate = phase as? Phase.Candidate
        if (candidate == null || candidate.valueKey != evidenceKey) {
            phase = Phase.Candidate(now, now, 1, evidenceKey)
            return null
        }
        val confirmed = candidate.copy(lastMatchedAt = now, count = candidate.count + 1)
        if (confirmed.count < MINIMUM_READING_CONDITION_OBSERVATIONS ||
            now - confirmed.startedAt < configured.durationMillis
        ) {
            phase = confirmed
            return null
        }
        return trigger(
            observation.sourceSequence,
            RestrictedEventPayload.ReadingThresholdCrossed(
                displayText = reading.text,
                valueDecimal = reading.valueDecimal,
                format = reading.format,
                confidence = reading.confidence,
                sourceKind = configured.sourceKind,
                observedAtEpochMillis = capturedAtEpochMillis ?: epochMillis(),
                operator = configured.operator,
                thresholdDecimal = configured.thresholdDecimal,
                lowerThresholdDecimal = configured.lowerThresholdDecimal,
                upperThresholdDecimal = configured.upperThresholdDecimal,
                unit = reading.unit,
            ),
            now,
        )
    }

    private fun evaluateStateTransition(
        observation: Observation,
        now: Long,
        configured: RuntimeMonitorRule.StateTransition,
    ): RuntimeEventCandidate? {
        val state = observation as? Observation.State
        if (state == null) {
            interruptCandidate()
            return null
        }
        if (state.stateId == configured.fromState) {
            stateTransitionArmed = true
            phase = Phase.Armed
            return null
        }
        if (!stateTransitionArmed || state.stateId != configured.toState ||
            !cooldownElapsed(now, configured.cooldownMillis)
        ) {
            if (state.stateId != configured.toState) phase = Phase.Armed
            return null
        }
        val current = phase
        val count = if (current is Phase.Candidate && current.valueKey == state.stateId) {
            current.count + 1
        } else {
            1
        }
        if (count < configured.stableFrames) {
            phase = Phase.Candidate(now, now, count, state.stateId)
            return null
        }
        stateTransitionArmed = false
        return trigger(
            observation.sourceSequence,
            RestrictedEventPayload.StateTransition(configured.fromState, configured.toState),
            now,
        )
    }

    private fun evaluateCooldownDuration(
        observation: Observation,
        now: Long,
        matches: Boolean,
        valueKey: String,
        durationMillis: Long,
        minimumCount: Int,
        maximumGapMillis: Long,
        cooldownMillis: Long,
        event: () -> RestrictedEventPayload,
    ): RuntimeEventCandidate? {
        val current = phase
        if (!matches) {
            phase = Phase.Armed
            return null
        }
        if (current is Phase.Latched) return null
        if (!cooldownElapsed(now, cooldownMillis)) return null
        if (current !is Phase.Candidate || current.valueKey != valueKey) {
            phase = Phase.Candidate(now, now, 1, valueKey)
            return null
        }
        if (now - current.lastMatchedAt > maximumGapMillis) {
            phase = Phase.Candidate(now, now, 1, valueKey)
            return null
        }
        val updated = current.copy(lastMatchedAt = now, count = current.count + 1)
        if (now - updated.startedAt < durationMillis || updated.count < minimumCount) {
            phase = updated
            return null
        }
        return trigger(observation.sourceSequence, event(), now)
    }

    private fun trigger(
        sourceSequence: Long,
        payload: RestrictedEventPayload,
        now: Long,
    ): RuntimeEventCandidate {
        val episode = nextEpisode++
        phase = Phase.Latched(episode)
        lastTriggeredAt = now
        return RuntimeEventCandidate(
            episode,
            sourceSequence,
            now,
            payload,
            notificationTextFormatter.format(displayName, payload),
        )
    }

    private fun cooldownElapsed(now: Long, cooldownMillis: Long): Boolean =
        lastTriggeredAt == null || now - checkNotNull(lastTriggeredAt) >= cooldownMillis

    private fun Observation.visualPresenceOrNull(): Boolean? = when (this) {
        is Observation.Detections -> items.any { detection ->
            detection.label == targetId && roi.containsCenterOf(detection.box)
        }
        is Observation.State -> when (stateId) {
            "$targetId:present" -> true
            "$targetId:absent" -> false
            else -> null
        }
        is Observation.Reading, is Observation.Unavailable -> null
    }

    private companion object {
        const val MINIMUM_READING_CONDITION_OBSERVATIONS = 2
    }
}

private fun Long.englishDurationLabel(): String =
    if (this % 1_000L == 0L) "${this / 1_000L} s" else "$this ms"

private fun canonicalDecimal(value: String): BigDecimal {
    require(CANONICAL_DECIMAL.matches(value)) { "decimal is not canonical" }
    return BigDecimal(value)
}

private fun BigDecimal.matches(
    operator: ReadingOperator,
    threshold: BigDecimal?,
    lowerThreshold: BigDecimal?,
    upperThreshold: BigDecimal?,
    hysteresis: BigDecimal,
): Boolean = when (operator) {
    ReadingOperator.GT -> this > checkNotNull(threshold)
    ReadingOperator.GTE -> this >= checkNotNull(threshold)
    ReadingOperator.LT -> this < checkNotNull(threshold)
    ReadingOperator.LTE -> this <= checkNotNull(threshold)
    ReadingOperator.EQ -> (this - checkNotNull(threshold)).abs() <= hysteresis
    ReadingOperator.OUTSIDE ->
        this < checkNotNull(lowerThreshold) || this > checkNotNull(upperThreshold)
}

private fun BigDecimal.isReadingRearmed(
    operator: ReadingOperator,
    threshold: BigDecimal?,
    lowerThreshold: BigDecimal?,
    upperThreshold: BigDecimal?,
    hysteresis: BigDecimal,
): Boolean = when (operator) {
    ReadingOperator.GT -> this <= checkNotNull(threshold) - hysteresis
    ReadingOperator.GTE -> this < checkNotNull(threshold) - hysteresis
    ReadingOperator.LT -> this >= checkNotNull(threshold) + hysteresis
    ReadingOperator.LTE -> this > checkNotNull(threshold) + hysteresis
    ReadingOperator.EQ ->
        (this - checkNotNull(threshold)).abs() > hysteresis * BigDecimal(2)
    ReadingOperator.OUTSIDE ->
        this >= checkNotNull(lowerThreshold) + hysteresis &&
            this <= checkNotNull(upperThreshold) - hysteresis
}

private val CANONICAL_DECIMAL = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?$")
