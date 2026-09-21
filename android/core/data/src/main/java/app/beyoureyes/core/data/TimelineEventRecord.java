package app.beyoureyes.core.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Embedded;

/** Event plus the immutable runtime row used only to recover a peer-task display summary. */
public final class TimelineEventRecord {
    @Embedded
    @NonNull
    public final EventEntity event;

    @ColumnInfo(name = "task_runtime_snapshot_json")
    @Nullable
    public final String taskRuntimeSnapshotJson;

    public TimelineEventRecord(
            @NonNull EventEntity event,
            @Nullable String taskRuntimeSnapshotJson
    ) {
        this.event = event;
        this.taskRuntimeSnapshotJson = taskRuntimeSnapshotJson;
    }
}
