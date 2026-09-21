package app.beyoureyes.core.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Editable local product task. Runtime snapshots remain in {@link TaskEntity}; keeping the two
 * tables separate lets task configuration evolve without rewriting immutable event evidence.
 */
@Entity(tableName = "product_tasks")
public final class LocalTaskEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "task_id")
    public final String taskId;

    @ColumnInfo(name = "revision")
    public final long revision;

    @NonNull
    @ColumnInfo(name = "title")
    public final String title;

    @NonNull
    @ColumnInfo(name = "target_mode")
    public final String targetMode;

    @NonNull
    @ColumnInfo(name = "target_json")
    public final String targetJson;

    @NonNull
    @ColumnInfo(name = "rule_json")
    public final String ruleJson;

    @NonNull
    @ColumnInfo(name = "material_refs_json")
    public final String materialRefsJson;

    @ColumnInfo(name = "created_at_epoch_millis")
    public final long createdAtEpochMillis;

    @Nullable
    @ColumnInfo(name = "runtime_package_id")
    public final String runtimePackageId;

    @Nullable
    @ColumnInfo(name = "runtime_package_version")
    public final String runtimePackageVersion;

    @Nullable
    @ColumnInfo(name = "runtime_manifest_sha256")
    public final String runtimeManifestSha256;

    public LocalTaskEntity(
            @NonNull String taskId,
            long revision,
            @NonNull String title,
            @NonNull String targetMode,
            @NonNull String targetJson,
            @NonNull String ruleJson,
            @NonNull String materialRefsJson,
            long createdAtEpochMillis,
            @Nullable String runtimePackageId,
            @Nullable String runtimePackageVersion,
            @Nullable String runtimeManifestSha256
    ) {
        this.taskId = taskId;
        this.revision = revision;
        this.title = title;
        this.targetMode = targetMode;
        this.targetJson = targetJson;
        this.ruleJson = ruleJson;
        this.materialRefsJson = materialRefsJson;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.runtimePackageId = runtimePackageId;
        this.runtimePackageVersion = runtimePackageVersion;
        this.runtimeManifestSha256 = runtimeManifestSha256;
    }
}
