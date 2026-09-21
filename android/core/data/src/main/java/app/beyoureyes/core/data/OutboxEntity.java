package app.beyoureyes.core.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "event_outbox",
        foreignKeys = @ForeignKey(
                entity = EventEntity.class,
                parentColumns = "event_id",
                childColumns = "event_id",
                onDelete = ForeignKey.CASCADE
        )
)
public final class OutboxEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "event_id")
    public final String eventId;

    @ColumnInfo(name = "attempt_count")
    public final int attemptCount;

    @ColumnInfo(name = "next_attempt_at_epoch_millis")
    public final long nextAttemptAtEpochMillis;

    public OutboxEntity(
            @NonNull String eventId,
            int attemptCount,
            long nextAttemptAtEpochMillis
    ) {
        this.eventId = eventId;
        this.attemptCount = attemptCount;
        this.nextAttemptAtEpochMillis = nextAttemptAtEpochMillis;
    }
}
