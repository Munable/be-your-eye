package app.beyoureyes.core.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "events",
        foreignKeys = @ForeignKey(
                entity = TaskEntity.class,
                parentColumns = "task_id",
                childColumns = "task_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("task_id"),
                @Index(value = {"task_id", "task_revision", "episode_id"}, unique = true)
        }
)
public final class EventEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "event_id")
    public final String eventId;

    @NonNull
    @ColumnInfo(name = "task_id")
    public final String taskId;

    @ColumnInfo(name = "task_revision")
    public final long taskRevision;

    @NonNull
    @ColumnInfo(name = "episode_id")
    public final String episodeId;

    @ColumnInfo(name = "source_sequence")
    public final long sourceSequence;

    @ColumnInfo(name = "occurred_at_epoch_millis")
    public final long occurredAtEpochMillis;

    @NonNull
    @ColumnInfo(name = "payload_json")
    public final String payloadJson;

    @NonNull
    @ColumnInfo(name = "notification_text")
    public final String notificationText;

    @ColumnInfo(name = "notification_attempt_count")
    public final int notificationAttemptCount;

    @ColumnInfo(name = "notification_delivered_at_epoch_millis")
    public final Long notificationDeliveredAtEpochMillis;

    public EventEntity(
            @NonNull String eventId,
            @NonNull String taskId,
            long taskRevision,
            @NonNull String episodeId,
            long sourceSequence,
            long occurredAtEpochMillis,
            @NonNull String payloadJson,
            @NonNull String notificationText,
            int notificationAttemptCount,
            Long notificationDeliveredAtEpochMillis
    ) {
        this.eventId = eventId;
        this.taskId = taskId;
        this.taskRevision = taskRevision;
        this.episodeId = episodeId;
        this.sourceSequence = sourceSequence;
        this.occurredAtEpochMillis = occurredAtEpochMillis;
        this.payloadJson = payloadJson;
        this.notificationText = notificationText;
        this.notificationAttemptCount = notificationAttemptCount;
        this.notificationDeliveredAtEpochMillis = notificationDeliveredAtEpochMillis;
    }
}
