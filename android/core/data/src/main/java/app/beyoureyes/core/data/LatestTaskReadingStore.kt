package app.beyoureyes.core.data

import app.beyoureyes.core.domain.ReadingSourceKind
import app.beyoureyes.core.domain.Observation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class LatestReadingStatus(val wireValue: String) {
    CANDIDATE("candidate"),
    STABLE("stable"),
    UNAVAILABLE("unavailable");

    companion object {
        fun fromWireValue(value: String): LatestReadingStatus = entries.singleOrNull {
            it.wireValue == value
        } ?: error("unknown latest reading status")
    }
}

data class StoredLatestTaskReading(
    val taskId: String,
    val taskRevision: Long,
    val status: LatestReadingStatus,
    val valueDecimal: String?,
    val unit: String?,
    val confidence: Float?,
    val sourceKind: ReadingSourceKind?,
    val sourceSequence: Long,
    val observedAtEpochMillis: Long,
) {
    init {
        require(UuidV7.isValid(taskId))
        require(taskRevision >= 1)
        require(sourceSequence >= 0)
        require(observedAtEpochMillis >= 0)
        require(unit == null || unit.isNotBlank() && unit.length <= 32)
        if (status == LatestReadingStatus.UNAVAILABLE) {
            require(valueDecimal == null && confidence == null && sourceKind == null)
        } else {
            require(valueDecimal != null && CANONICAL_READING_DECIMAL.matches(valueDecimal))
            require(confidence != null && confidence in 0f..1f)
            require(sourceKind != null)
        }
    }
}

/** Room boundary for the last user-facing Reading state; frames never enter this table. */
class RoomLatestTaskReadingStore(
    database: MonitorDatabase,
) {
    private val dao = database.monitoringDao()

    val readings: Flow<List<StoredLatestTaskReading>> = dao.observeLatestTaskReadings().map {
        rows -> rows.map(LatestTaskReadingEntity::toStored)
    }

    suspend fun record(
        taskId: String,
        taskRevision: Long,
        observation: Observation,
        observedAtEpochMillis: Long,
        configuredUnit: String?,
        sourceKind: ReadingSourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
    ): Boolean = withContext(Dispatchers.IO) {
        val stored = when (observation) {
            is Observation.Reading -> StoredLatestTaskReading(
                taskId = taskId,
                taskRevision = taskRevision,
                status = if (observation.stable) {
                    LatestReadingStatus.STABLE
                } else {
                    LatestReadingStatus.CANDIDATE
                },
                valueDecimal = observation.valueDecimal,
                unit = configuredUnit ?: observation.unit,
                confidence = observation.confidence,
                sourceKind = sourceKind,
                sourceSequence = observation.sourceSequence,
                observedAtEpochMillis = observedAtEpochMillis,
            )
            is Observation.Unavailable -> StoredLatestTaskReading(
                taskId = taskId,
                taskRevision = taskRevision,
                status = LatestReadingStatus.UNAVAILABLE,
                valueDecimal = null,
                unit = configuredUnit,
                confidence = null,
                sourceKind = null,
                sourceSequence = observation.sourceSequence,
                observedAtEpochMillis = observedAtEpochMillis,
            )
            is Observation.Detections, is Observation.State -> return@withContext false
        }
        dao.upsertLatestTaskReadingIfNewer(stored.toEntity())
    }

    suspend fun find(taskId: String): StoredLatestTaskReading? = withContext(Dispatchers.IO) {
        dao.findLatestTaskReading(taskId)?.toStored()
    }
}

private fun StoredLatestTaskReading.toEntity() = LatestTaskReadingEntity(
    taskId,
    taskRevision,
    status.wireValue,
    valueDecimal,
    unit,
    confidence,
    sourceKind?.wireValue,
    sourceSequence,
    observedAtEpochMillis,
)

private fun LatestTaskReadingEntity.toStored() = StoredLatestTaskReading(
    taskId = taskId,
    taskRevision = taskRevision,
    status = LatestReadingStatus.fromWireValue(status),
    valueDecimal = valueDecimal,
    unit = unit,
    confidence = confidence,
    sourceKind = sourceKind?.let { wire ->
        ReadingSourceKind.entries.singleOrNull { it.wireValue == wire }
            ?: error("unknown reading source kind")
    },
    sourceSequence = sourceSequence,
    observedAtEpochMillis = observedAtEpochMillis,
)

private val CANONICAL_READING_DECIMAL = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?$")
