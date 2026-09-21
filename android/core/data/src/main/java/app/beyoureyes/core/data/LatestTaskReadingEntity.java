package app.beyoureyes.core.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

/** Latest typed reading for one editable task. It contains no image or model-private field. */
@Entity(
        tableName = "latest_task_readings",
        foreignKeys = @ForeignKey(
                entity = LocalTaskEntity.class,
                parentColumns = "task_id",
                childColumns = "task_id",
                onDelete = ForeignKey.CASCADE
        )
)
public final class LatestTaskReadingEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "task_id")
    public final String taskId;

    @ColumnInfo(name = "task_revision")
    public final long taskRevision;

    @NonNull
    @ColumnInfo(name = "status")
    public final String status;

    @ColumnInfo(name = "value_decimal")
    public final String valueDecimal;

    @ColumnInfo(name = "unit")
    public final String unit;

    @ColumnInfo(name = "confidence")
    public final Float confidence;

    @ColumnInfo(name = "source_kind")
    public final String sourceKind;

    @ColumnInfo(name = "source_sequence")
    public final long sourceSequence;

    @ColumnInfo(name = "observed_at_epoch_millis")
    public final long observedAtEpochMillis;

    public LatestTaskReadingEntity(
            @NonNull String taskId,
            long taskRevision,
            @NonNull String status,
            String valueDecimal,
            String unit,
            Float confidence,
            String sourceKind,
            long sourceSequence,
            long observedAtEpochMillis
    ) {
        this.taskId = taskId;
        this.taskRevision = taskRevision;
        this.status = status;
        this.valueDecimal = valueDecimal;
        this.unit = unit;
        this.confidence = confidence;
        this.sourceKind = sourceKind;
        this.sourceSequence = sourceSequence;
        this.observedAtEpochMillis = observedAtEpochMillis;
    }
}
