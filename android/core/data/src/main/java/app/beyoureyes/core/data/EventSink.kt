package app.beyoureyes.core.data

import androidx.room.withTransaction
import app.beyoureyes.core.domain.ObjectEventCondition
import app.beyoureyes.core.domain.ReadingOperator
import app.beyoureyes.core.domain.ReadingSourceKind
import app.beyoureyes.core.domain.MonitorEventFact
import app.beyoureyes.core.domain.MonitorObjectEventCondition
import app.beyoureyes.core.domain.MonitoringSessionTransition
import app.beyoureyes.core.domain.RestrictedEventPayload
import java.time.Instant

/**
 * The immutable runtime snapshot needed to interpret an event after process recovery.
 *
 * Database schema v1 deliberately keeps only the latest revision for a task. Events retain their
 * own revision, while this row holds the current complete runtime snapshot.
 */
data class EventTaskSnapshot(
    val taskId: String,
    val revision: Long,
    val runtimeSnapshotJson: String,
    val isActive: Boolean = true,
) {
    init {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        require(revision >= 1) { "revision must be at least 1" }
        require(runtimeSnapshotJson.isNotBlank()) { "runtimeSnapshotJson must not be blank" }
    }
}

data class EventWriteRequest(
    val eventId: String,
    val task: EventTaskSnapshot,
    val episodeId: String,
    val sourceSequence: Long,
    val occurredAtEpochMillis: Long,
    val payload: RestrictedEventPayload,
    val notificationText: String,
    val nextUploadAttemptAtEpochMillis: Long = occurredAtEpochMillis,
) {
    init {
        require(UuidV7.isValid(eventId)) { "eventId must be UUIDv7" }
        require(UuidV7.isValid(episodeId)) { "episodeId must be UUIDv7" }
        require(sourceSequence >= 0) { "sourceSequence must not be negative" }
        require(occurredAtEpochMillis >= 0) { "occurredAtEpochMillis must not be negative" }
        require(notificationText.isNotBlank()) { "notificationText must not be blank" }
        require(nextUploadAttemptAtEpochMillis >= 0) {
            "nextUploadAttemptAtEpochMillis must not be negative"
        }
    }
}

sealed interface EventWriteResult {
    val eventId: String

    /** The Event and matching Outbox row were committed atomically. */
    data class Inserted(override val eventId: String) : EventWriteResult

    /** Event identity or episode already existed; [eventId] is the durable existing row. */
    data class Duplicate(override val eventId: String) : EventWriteResult
}

fun interface EventSink {
    suspend fun persist(request: EventWriteRequest): EventWriteResult
}

data class PendingLocalNotification(
    val eventId: String,
    val text: String,
    val attemptCount: Int,
)

interface LocalNotificationStore {
    suspend fun findPending(eventId: String): PendingLocalNotification?

    suspend fun listPending(limit: Int): List<PendingLocalNotification>

    suspend fun recordAttempt(eventId: String): Boolean

    suspend fun markDelivered(eventId: String, deliveredAtEpochMillis: Long): Boolean
}

data class TimelineEvent(
    val eventId: String,
    val taskId: String,
    val taskRevision: Long,
    val occurredAtEpochMillis: Long,
    val notificationText: String,
    val payloadJson: String,
    val remoteTaskTitle: String? = null,
    val remoteCapabilityId: String? = null,
    val remoteAccountId: String? = null,
    val isRemoteTask: Boolean = false,
)

interface EventTimelineStore {
    suspend fun latest(limit: Int = 100): List<TimelineEvent>

    suspend fun latestForTask(taskId: String, limit: Int = 100): List<TimelineEvent>
}

enum class InterruptedMonitoringRecoveryResult {
    CLOSED_OPEN_EPISODE,
    NO_OPEN_EPISODE,
    TASK_DELETED,
}

class EventTaskNoLongerRunnableException(
    taskId: String,
) : IllegalStateException("event task is no longer runnable: $taskId")

/** Room-backed local Event/Outbox transaction boundary. */
class RoomEventRepository(
    private val database: MonitorDatabase,
) : EventSink, LocalNotificationStore, EventTimelineStore {
    /** Persists the immutable runtime revision before CameraX begins producing observations. */
    suspend fun persistTaskSnapshot(snapshot: EventTaskSnapshot) = database.withTransaction {
        val dao = database.monitoringDao()
        requireCurrentProductTask(dao, snapshot)
        ensureTaskSnapshot(dao, snapshot)
    }

    override suspend fun persist(request: EventWriteRequest): EventWriteResult =
        database.withTransaction {
            val dao = database.monitoringDao()

            // A retry must be a no-op, including for a stale task revision. Check both idempotency
            // keys before touching the current task snapshot.
            dao.findEvent(request.eventId)?.let {
                return@withTransaction EventWriteResult.Duplicate(it.eventId)
            }
            dao.findEventByEpisode(
                request.task.taskId,
                request.task.revision,
                request.episodeId,
            )?.let {
                return@withTransaction EventWriteResult.Duplicate(it.eventId)
            }

            // A queued analyzer write must not recreate the runtime task after the user deleted
            // the product task. Product deletion and this check are serialized by Room.
            requireCurrentProductTask(dao, request.task)
            ensureTaskSnapshot(dao, request.task)

            val event = EventEntity(
                request.eventId,
                request.task.taskId,
                request.task.revision,
                request.episodeId,
                request.sourceSequence,
                request.occurredAtEpochMillis,
                RestrictedEventPayloadJson.encode(request.payload),
                request.notificationText,
                0,
                null,
            )
            val outbox = OutboxEntity(
                request.eventId,
                0,
                request.nextUploadAttemptAtEpochMillis,
            )

            if (dao.insertEventAndOutbox(event, outbox)) {
                EventWriteResult.Inserted(request.eventId)
            } else {
                val existing = dao.findEvent(request.eventId) ?: dao.findEventByEpisode(
                    request.task.taskId,
                    request.task.revision,
                    request.episodeId,
                ) ?: error("event insert conflicted without a durable idempotency row")
                EventWriteResult.Duplicate(existing.eventId)
            }
        }

    /**
     * Closes a reference episode left open by process death.
     *
     * Recovery is bounded to the most recent local facts and is atomic with the silent Event and
     * Outbox insert. A missing product task is a terminal no-op: recovery never recreates it.
     */
    suspend fun recoverInterruptedMonitoringStop(
        taskId: String,
        lastKnownActiveAtEpochMillis: Long,
        eventsCreatedBeforeOrAtEpochMillis: Long,
        eventId: String = UuidV7.generate(lastKnownActiveAtEpochMillis),
        episodeId: String = UuidV7.generate(lastKnownActiveAtEpochMillis),
    ): InterruptedMonitoringRecoveryResult {
        return database.withTransaction {
            require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
            require(lastKnownActiveAtEpochMillis >= 0)
            require(eventsCreatedBeforeOrAtEpochMillis >= 0)
            require(UuidV7.isValid(eventId) && UuidV7.isValid(episodeId))
            val dao = database.monitoringDao()
            val productTask = dao.findProductTask(taskId)
                ?: return@withTransaction InterruptedMonitoringRecoveryResult.TASK_DELETED
            val runtimeTask = dao.findTask(taskId)
                ?: return@withTransaction InterruptedMonitoringRecoveryResult.NO_OPEN_EPISODE
            val recent = dao.latestEventsForTaskBefore(
                taskId,
                eventsCreatedBeforeOrAtEpochMillis,
                INTERRUPTED_RECOVERY_EVENT_LIMIT,
            )
            var open: EventEntity? = null
            for (event in recent) {
                when (val fact = MonitorEventFactJson.decode(event.payloadJson)) {
                    is MonitorEventFact.ObjectEpisode -> when (fact.condition) {
                        MonitorObjectEventCondition.APPEARED -> {
                            open = event
                            break
                        }
                        MonitorObjectEventCondition.DISAPPEARED -> {
                            return@withTransaction InterruptedMonitoringRecoveryResult.NO_OPEN_EPISODE
                        }
                        MonitorObjectEventCondition.COUNT_MATCHED -> Unit
                    }
                    is MonitorEventFact.StateTransition -> if (
                        fact.fromState == MonitoringSessionTransition.REFERENCE_EPISODE_OPEN &&
                        fact.toState == MonitoringSessionTransition.MONITORING_STOPPED
                    ) {
                        return@withTransaction InterruptedMonitoringRecoveryResult.NO_OPEN_EPISODE
                    }
                    is MonitorEventFact.ReadingThresholdCrossed,
                    is MonitorEventFact.VisualConditionMet,
                    is MonitorEventFact.Unknown,
                    -> Unit
                }
            }
            val openEvent = open
                ?: return@withTransaction InterruptedMonitoringRecoveryResult.NO_OPEN_EPISODE

            // The two task rows must still describe the exact revision that owned the appeared
            // fact. Recovery never attaches an old episode to a newly edited product task.
            if (runtimeTask.revision != openEvent.taskRevision ||
                productTask.revision != openEvent.taskRevision
            ) {
                return@withTransaction InterruptedMonitoringRecoveryResult.NO_OPEN_EPISODE
            }
            val occurredAt = maxOf(
                lastKnownActiveAtEpochMillis,
                openEvent.occurredAtEpochMillis,
            )
            val sourceSequence = recent.maxOfOrNull(EventEntity::sourceSequence)
                ?.coerceAtMost(Long.MAX_VALUE - 1)
                ?.plus(1)
                ?: 0L
            val payload = RestrictedEventPayload.StateTransition(
                fromState = MonitoringSessionTransition.REFERENCE_EPISODE_OPEN,
                toState = MonitoringSessionTransition.MONITORING_STOPPED,
            )
            val encodedPayload = RestrictedEventPayloadJson.encode(payload)
            val event = EventEntity(
                eventId,
                taskId,
                openEvent.taskRevision,
                episodeId,
                sourceSequence,
                occurredAt,
                encodedPayload,
                "${productTask.title} monitoring stopped; episode ended",
                0,
                occurredAt,
            )
            if (!dao.insertEventAndOutbox(event, OutboxEntity(eventId, 0, occurredAt))) {
                // IDs are injectable for deterministic tests. Only the exact same recovery fact
                // is idempotent; an unrelated UUID collision remains a fail-closed conflict.
                val existing = dao.findEvent(eventId)
                if (existing?.taskId == taskId &&
                    existing.taskRevision == openEvent.taskRevision &&
                    existing.payloadJson == encodedPayload
                ) {
                    return@withTransaction InterruptedMonitoringRecoveryResult.CLOSED_OPEN_EPISODE
                }
                error("interrupted monitoring close conflicted without its event")
            }
            InterruptedMonitoringRecoveryResult.CLOSED_OPEN_EPISODE
        }
    }

    suspend fun reconcileReferenceEventSnapshots(
        store: ReferenceEventSnapshotStore,
        createdBeforeOrAtEpochMillis: Long,
        maximumFiles: Int = DEFAULT_SNAPSHOT_RECONCILE_LIMIT,
    ): ReferenceEventSnapshotReconcileResult {
        require(createdBeforeOrAtEpochMillis >= 0)
        return store.reconcile(
            createdBeforeOrAtEpochMillis = createdBeforeOrAtEpochMillis,
            maximumFiles = maximumFiles,
        ) { taskId, eventId ->
            database.monitoringDao().localEventSnapshotOwnerExists(taskId, eventId)
        }
    }

    override suspend fun findPending(eventId: String): PendingLocalNotification? =
        database.withTransaction {
            database.monitoringDao().findPendingLocalNotification(eventId)?.toPendingNotification()
        }

    override suspend fun listPending(limit: Int): List<PendingLocalNotification> {
        require(limit in 1..1_000) { "limit must be between 1 and 1000" }
        return database.withTransaction {
            database.monitoringDao().pendingLocalNotifications(limit).map {
                it.toPendingNotification()
            }
        }
    }

    override suspend fun recordAttempt(eventId: String): Boolean = database.withTransaction {
        database.monitoringDao().recordLocalNotificationAttempt(eventId) == 1
    }

    override suspend fun markDelivered(eventId: String, deliveredAtEpochMillis: Long): Boolean {
        require(deliveredAtEpochMillis >= 0)
        return database.withTransaction {
            database.monitoringDao().markLocalNotificationDelivered(
                eventId,
                deliveredAtEpochMillis,
            ) == 1
        }
    }

    override suspend fun latest(limit: Int): List<TimelineEvent> {
        requireTimelineLimit(limit)
        return database.withTransaction {
            database.monitoringDao().latestEvents(limit).map { it.toTimelineEvent() }
        }
    }

    override suspend fun latestForTask(taskId: String, limit: Int): List<TimelineEvent> {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        requireTimelineLimit(limit)
        return database.withTransaction {
            database.monitoringDao().latestEventsForTask(taskId, limit)
                .map { it.toTimelineEvent() }
        }
    }

    private fun ensureTaskSnapshot(dao: MonitoringDao, requested: EventTaskSnapshot) {
        val current = dao.findTask(requested.taskId)
        when {
            current == null -> dao.upsertTask(requested.toEntity())
            current.revision > requested.revision -> error(
                "stale task revision ${requested.revision}; current revision is ${current.revision}",
            )
            current.revision == requested.revision &&
                current.runtimeSnapshotJson != requested.runtimeSnapshotJson -> error(
                "runtime snapshot conflict for ${requested.taskId} revision ${requested.revision}",
            )
            current.revision < requested.revision || current.isActive != requested.isActive ->
                dao.upsertTask(requested.toEntity())
        }
    }

    private fun requireCurrentProductTask(dao: MonitoringDao, requested: EventTaskSnapshot) {
        val productTask = dao.findProductTask(requested.taskId)
            ?: throw EventTaskNoLongerRunnableException(requested.taskId)
        if (productTask.revision != requested.revision) {
            throw EventTaskNoLongerRunnableException(requested.taskId)
        }
    }

    private fun EventTaskSnapshot.toEntity() = TaskEntity(
        taskId,
        revision,
        runtimeSnapshotJson,
        isActive,
    )

    private fun EventEntity.toPendingNotification() = PendingLocalNotification(
        eventId = eventId,
        text = notificationText,
        attemptCount = notificationAttemptCount,
    )

    private fun EventEntity.toTimelineEvent() = TimelineEvent(
        eventId = eventId,
        taskId = taskId,
        taskRevision = taskRevision,
        occurredAtEpochMillis = occurredAtEpochMillis,
        notificationText = notificationText,
        payloadJson = payloadJson,
    )

    private fun requireTimelineLimit(limit: Int) {
        require(limit in 1..500) { "timeline limit must be between 1 and 500" }
    }

    private companion object {
        const val INTERRUPTED_RECOVERY_EVENT_LIMIT = 500
        const val DEFAULT_SNAPSHOT_RECONCILE_LIMIT = 500
    }
}

internal object RestrictedEventPayloadJson {
    fun encode(payload: RestrictedEventPayload): String = when (payload) {
        is RestrictedEventPayload.ObjectEpisode -> buildString {
            append("{\"type\":\"object_episode\",\"target_id\":")
            appendJsonString(payload.targetId)
            append(",\"condition\":")
            appendJsonString(payload.condition.wireValue)
            append(",\"duration_ms\":${payload.durationMillis},\"count\":${payload.count}}")
        }

        is RestrictedEventPayload.VisualConditionMet -> buildString {
            append("{\"type\":\"visual_condition_met\",\"target_id\":")
            appendJsonString(payload.targetId)
            append(",\"condition\":")
            appendJsonString(payload.condition.wireValue)
            append(",\"duration_ms\":${payload.durationMillis}}")
        }

        is RestrictedEventPayload.ReadingThresholdCrossed -> buildString {
            append("{\"type\":\"reading_threshold_crossed\",\"reading\":{")
            append("\"type\":\"structured_reading\",\"status\":\"stable\",\"display_text\":")
            appendJsonString(payload.displayText)
            append(",\"value_decimal\":")
            appendJsonString(payload.valueDecimal)
            append(",\"format\":{\"profile_id\":")
            appendJsonString(app.beyoureyes.core.domain.ConfirmedReadingFormat.PROFILE_ID)
            append(",\"kind\":")
            appendJsonString(payload.format.kind.wireValue)
            append(",\"fractional_digits\":${payload.format.fractionalDigits}")
            append(",\"time_segments\":")
            payload.format.timeSegments?.let(::append) ?: append("null")
            append(",\"unit\":")
            val formatUnit = payload.format.unit
            if (formatUnit == null) append("null") else appendJsonString(formatUnit)
            append('}')
            append(",\"unit\":")
            val unit = payload.unit
            if (unit == null) append("null") else appendJsonString(unit)
            append(",\"confidence\":${payload.confidence},\"source_kind\":")
            appendJsonString(payload.sourceKind.wireValue)
            append(",\"observed_at\":")
            appendJsonString(Instant.ofEpochMilli(payload.observedAtEpochMillis).toString())
            append('}')
            append(",\"operator\":")
            appendJsonString(payload.operator.wireValue)
            if (payload.operator == ReadingOperator.OUTSIDE) {
                append(",\"lower_threshold_decimal\":")
                appendJsonString(checkNotNull(payload.lowerThresholdDecimal))
                append(",\"upper_threshold_decimal\":")
                appendJsonString(checkNotNull(payload.upperThresholdDecimal))
            } else {
                append(",\"threshold_decimal\":")
                appendJsonString(checkNotNull(payload.thresholdDecimal))
            }
            append('}')
        }

        is RestrictedEventPayload.StateTransition -> buildString {
            append("{\"type\":\"state_transition\",\"from_state\":")
            appendJsonString(payload.fromState)
            append(",\"to_state\":")
            appendJsonString(payload.toState)
            append('}')
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
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
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

private val CANONICAL_DECIMAL = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?$")

private fun requireCanonicalDecimal(value: String, name: String) {
    require(CANONICAL_DECIMAL.matches(value)) {
        "$name must be a canonical decimal string"
    }
}
