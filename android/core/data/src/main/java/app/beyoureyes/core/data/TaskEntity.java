package app.beyoureyes.core.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "tasks")
public final class TaskEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "task_id")
    public final String taskId;

    @ColumnInfo(name = "revision")
    public final long revision;

    @NonNull
    @ColumnInfo(name = "runtime_snapshot_json")
    public final String runtimeSnapshotJson;

    @ColumnInfo(name = "is_active")
    public final boolean isActive;

    public TaskEntity(
            @NonNull String taskId,
            long revision,
            @NonNull String runtimeSnapshotJson,
            boolean isActive
    ) {
        this.taskId = taskId;
        this.revision = revision;
        this.runtimeSnapshotJson = runtimeSnapshotJson;
        this.isActive = isActive;
    }
}
