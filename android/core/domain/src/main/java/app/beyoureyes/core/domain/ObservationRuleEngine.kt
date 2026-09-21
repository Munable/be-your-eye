package app.beyoureyes.core.domain

/**
 * Deterministic, stateful evaluation of an ordered observation stream.
 *
 * [interruptCandidate] is used for a monotonic-clock discontinuity. It cancels only in-flight
 * evidence and never treats the discontinuity as a negative observation. [reset] starts a
 * completely new task runtime.
 */
interface ObservationRuleEngine<out Event> {
    fun evaluate(observation: Observation, monotonicMillis: Long): Event?

    fun interruptCandidate()

    fun reset()
}

internal enum class InputDisposition {
    ACCEPTED,
    CLOCK_ROLLBACK,
    IGNORED_SEQUENCE,
}

/** Shared fail-closed ordering guard used by every rule engine. */
internal class ObservationInputGuard {
    private var lastSourceSequence: Long? = null
    private var lastMonotonicMillis: Long? = null

    fun accept(sourceSequence: Long, monotonicMillis: Long): InputDisposition {
        require(sourceSequence >= 0) { "sourceSequence must be non-negative" }
        require(monotonicMillis >= 0) { "monotonicMillis must be non-negative" }

        val previousSequence = lastSourceSequence
        if (previousSequence != null && sourceSequence <= previousSequence) {
            // Replayed/out-of-order inputs cannot contribute to duration, counts, cooldowns, or
            // recovery. They also cannot move the accepted monotonic clock.
            return InputDisposition.IGNORED_SEQUENCE
        }

        val previousMillis = lastMonotonicMillis
        lastSourceSequence = sourceSequence
        lastMonotonicMillis = monotonicMillis
        return if (previousMillis != null && monotonicMillis < previousMillis) {
            InputDisposition.CLOCK_ROLLBACK
        } else {
            InputDisposition.ACCEPTED
        }
    }

    fun reset() {
        lastSourceSequence = null
        lastMonotonicMillis = null
    }
}

internal fun elapsedAtLeast(now: Long, startedAt: Long, durationMillis: Long): Boolean =
    now >= startedAt && now - startedAt >= durationMillis

internal fun cooldownElapsed(now: Long, lastTriggeredAt: Long?, cooldownMillis: Long): Boolean =
    lastTriggeredAt == null ||
        (now >= lastTriggeredAt && now - lastTriggeredAt >= cooldownMillis)
