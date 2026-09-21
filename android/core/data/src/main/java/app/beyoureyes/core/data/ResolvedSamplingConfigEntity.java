package app.beyoureyes.core.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

/**
 * Exact task/package/device-bound sampling decision. The package descriptor hash identifies the
 * canonical Manifest and its complete immutable artifact set. interval_millis is the current
 * fail-closed cadence: it starts at the signed default and may change only through the signed
 * adaptive flag, signed bounds and compare-and-set persistence.
 */
@Entity(
        tableName = "resolved_sampling_configs",
        foreignKeys = @ForeignKey(
                entity = LocalTaskEntity.class,
                parentColumns = "task_id",
                childColumns = "task_id",
                onDelete = ForeignKey.CASCADE
        )
)
public final class ResolvedSamplingConfigEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "task_id")
    public final String taskId;

    @ColumnInfo(name = "task_revision")
    public final long taskRevision;

    @NonNull
    @ColumnInfo(name = "catalog_version")
    public final String catalogVersion;

    @NonNull
    @ColumnInfo(name = "capability_id")
    public final String capabilityId;

    @NonNull
    @ColumnInfo(name = "model_profile_key")
    public final String modelProfileKey;

    @NonNull
    @ColumnInfo(name = "recipe_id")
    public final String recipeId;

    @NonNull
    @ColumnInfo(name = "intent_key")
    public final String intentKey;

    @NonNull
    @ColumnInfo(name = "package_id")
    public final String packageId;

    @NonNull
    @ColumnInfo(name = "package_version")
    public final String packageVersion;

    @NonNull
    @ColumnInfo(name = "manifest_sha256")
    public final String manifestSha256;

    @NonNull
    @ColumnInfo(name = "artifact_identity_sha256")
    public final String artifactIdentitySha256;

    @NonNull
    @ColumnInfo(name = "device_fingerprint_sha256")
    public final String deviceFingerprintSha256;

    @ColumnInfo(name = "interval_millis")
    public final long intervalMillis;

    @ColumnInfo(name = "manifest_min_interval_millis")
    public final long manifestMinimumIntervalMillis;

    @ColumnInfo(name = "manifest_max_interval_millis")
    public final long manifestMaximumIntervalMillis;

    @ColumnInfo(name = "adaptive_enabled")
    public final boolean adaptiveEnabled;

    public ResolvedSamplingConfigEntity(
            @NonNull String taskId,
            long taskRevision,
            @NonNull String catalogVersion,
            @NonNull String capabilityId,
            @NonNull String modelProfileKey,
            @NonNull String recipeId,
            @NonNull String intentKey,
            @NonNull String packageId,
            @NonNull String packageVersion,
            @NonNull String manifestSha256,
            @NonNull String artifactIdentitySha256,
            @NonNull String deviceFingerprintSha256,
            long intervalMillis,
            long manifestMinimumIntervalMillis,
            long manifestMaximumIntervalMillis,
            boolean adaptiveEnabled
    ) {
        this.taskId = taskId;
        this.taskRevision = taskRevision;
        this.catalogVersion = catalogVersion;
        this.capabilityId = capabilityId;
        this.modelProfileKey = modelProfileKey;
        this.recipeId = recipeId;
        this.intentKey = intentKey;
        this.packageId = packageId;
        this.packageVersion = packageVersion;
        this.manifestSha256 = manifestSha256;
        this.artifactIdentitySha256 = artifactIdentitySha256;
        this.deviceFingerprintSha256 = deviceFingerprintSha256;
        this.intervalMillis = intervalMillis;
        this.manifestMinimumIntervalMillis = manifestMinimumIntervalMillis;
        this.manifestMaximumIntervalMillis = manifestMaximumIntervalMillis;
        this.adaptiveEnabled = adaptiveEnabled;
    }
}
