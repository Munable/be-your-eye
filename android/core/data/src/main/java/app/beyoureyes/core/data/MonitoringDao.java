package app.beyoureyes.core.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;

import java.util.List;

import kotlinx.coroutines.flow.Flow;

@Dao
public interface MonitoringDao {
    @Query("SELECT * FROM product_tasks " +
            "ORDER BY created_at_epoch_millis DESC, task_id DESC")
    Flow<List<LocalTaskEntity>> observeProductTasks();

    @Query("SELECT tasks.* FROM tasks " +
            "WHERE tasks.runtime_snapshot_json LIKE " +
            "'{\"schema_version\":\"remote_event_cache_v3\"%' " +
            "AND NOT EXISTS (SELECT 1 FROM product_tasks " +
            "WHERE product_tasks.task_id = tasks.task_id) " +
            "ORDER BY tasks.task_id DESC")
    Flow<List<TaskEntity>> observeRemoteTaskSnapshots();

    @Query("SELECT * FROM product_tasks WHERE task_id = :taskId LIMIT 1")
    LocalTaskEntity findProductTask(String taskId);

    @Query("SELECT COUNT(*) FROM product_tasks")
    int productTaskCount();

    @Query("SELECT task_id FROM product_tasks")
    List<String> productTaskIds();

    @Query("UPDATE product_tasks SET runtime_package_id = :packageId, " +
            "runtime_package_version = :packageVersion, " +
            "runtime_manifest_sha256 = :manifestSha256 " +
            "WHERE task_id = :taskId AND revision = :revision")
    int updateRuntimePackageBinding(
            String taskId,
            long revision,
            String packageId,
            String packageVersion,
            String manifestSha256
    );

    @Query("UPDATE product_tasks SET revision = :nextRevision, target_json = :targetJson " +
            "WHERE task_id = :taskId AND revision = :expectedRevision")
    int updateProductTaskTargetRevision(
            String taskId,
            long expectedRevision,
            long nextRevision,
            String targetJson
    );

    @Query("UPDATE product_tasks SET revision = :nextRevision, rule_json = :ruleJson " +
            "WHERE task_id = :taskId AND revision = :expectedRevision")
    int updateMonitorRuleRevision(
            String taskId,
            long expectedRevision,
            long nextRevision,
            String ruleJson
    );

    @Query("UPDATE product_tasks SET revision = :nextRevision, target_json = :targetJson, " +
            "rule_json = :ruleJson WHERE task_id = :taskId AND revision = :expectedRevision")
    int updateReadingTargetAndRuleRevision(
            String taskId,
            long expectedRevision,
            long nextRevision,
            String targetJson,
            String ruleJson
    );

    @Query("UPDATE product_tasks SET revision = :nextRevision, " +
            "material_refs_json = :materialRefsJson " +
            "WHERE task_id = :taskId AND revision = :expectedRevision")
    int updateReferenceMaterialRevision(
            String taskId,
            long expectedRevision,
            long nextRevision,
            String materialRefsJson
    );

    @Query("UPDATE product_tasks SET title = :name WHERE task_id = :taskId")
    int renameMonitor(String taskId, String name);

    @Query("UPDATE resolved_sampling_configs SET task_revision = :nextRevision " +
            "WHERE task_id = :taskId AND task_revision = :expectedRevision")
    int updateResolvedSamplingTaskRevision(
            String taskId,
            long expectedRevision,
            long nextRevision
    );

    @Query("DELETE FROM resolved_sampling_configs WHERE task_id = :taskId")
    int deleteResolvedSamplingConfig(String taskId);

    @Query("SELECT * FROM resolved_sampling_configs WHERE task_id = :taskId LIMIT 1")
    ResolvedSamplingConfigEntity findResolvedSamplingConfig(String taskId);

    @Upsert
    void upsertResolvedSamplingConfig(ResolvedSamplingConfigEntity config);

    /**
     * Persists one bounded current-interval transition only when the complete task/package/device
     * binding is still the value inspected by the caller. This prevents a late diagnostic from
     * mutating a newly prepared task or package.
     */
    @Transaction
    default boolean compareAndSetResolvedSamplingConfig(
            ResolvedSamplingConfigEntity expected,
            ResolvedSamplingConfigEntity replacement
    ) {
        ResolvedSamplingConfigEntity current = findResolvedSamplingConfig(expected.taskId);
        LocalTaskEntity task = findProductTask(expected.taskId);
        if (current == null || task == null || !sameSamplingConfig(current, expected) ||
                !sameSamplingIdentity(expected, replacement) ||
                task.revision != expected.taskRevision ||
                !expected.packageId.equals(task.runtimePackageId) ||
                !expected.packageVersion.equals(task.runtimePackageVersion) ||
                !expected.manifestSha256.equals(task.runtimeManifestSha256)) {
            return false;
        }
        upsertResolvedSamplingConfig(replacement);
        return true;
    }

    @Query("SELECT * FROM latest_task_readings ORDER BY observed_at_epoch_millis DESC, task_id DESC")
    Flow<List<LatestTaskReadingEntity>> observeLatestTaskReadings();

    @Query("SELECT * FROM latest_task_readings WHERE task_id = :taskId LIMIT 1")
    LatestTaskReadingEntity findLatestTaskReading(String taskId);

    @Upsert
    void upsertLatestTaskReading(LatestTaskReadingEntity reading);

    @Query("DELETE FROM latest_task_readings WHERE task_id = :taskId")
    int deleteLatestTaskReading(String taskId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertProductTask(LocalTaskEntity task);

    @Query("DELETE FROM product_tasks WHERE task_id = :taskId")
    int deleteProductTask(String taskId);

    @Query("SELECT events.*, tasks.runtime_snapshot_json AS task_runtime_snapshot_json " +
            "FROM events LEFT JOIN tasks ON tasks.task_id = events.task_id " +
            "ORDER BY events.occurred_at_epoch_millis DESC, events.event_id DESC LIMIT :limit")
    Flow<List<TimelineEventRecord>> observeLatestEvents(int limit);

    @Query("SELECT events.*, tasks.runtime_snapshot_json AS task_runtime_snapshot_json " +
            "FROM events LEFT JOIN tasks ON tasks.task_id = events.task_id " +
            "WHERE events.task_id = :taskId " +
            "ORDER BY events.occurred_at_epoch_millis DESC, events.event_id DESC LIMIT :limit")
    Flow<List<TimelineEventRecord>> observeLatestEventsForTask(String taskId, int limit);

    // SQL REPLACE performs a delete+insert and would cascade-delete this task's events/outbox.
    @Upsert
    void upsertTask(TaskEntity task);

    @Query("SELECT * FROM tasks WHERE task_id = :taskId LIMIT 1")
    TaskEntity findTask(String taskId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertEvent(EventEntity event);

    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE event_id = :eventId)")
    boolean eventExists(String eventId);

    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE task_id = :taskId)")
    boolean hasAnyEventForTask(String taskId);

    @Query("SELECT EXISTS(SELECT 1 FROM events " +
            "WHERE task_id = :taskId AND task_revision = :taskRevision AND episode_id = :episodeId)")
    boolean episodeExists(String taskId, long taskRevision, String episodeId);

    @Query("SELECT * FROM events WHERE event_id = :eventId LIMIT 1")
    EventEntity findEvent(String eventId);

    @Query("DELETE FROM events WHERE event_id = :eventId")
    int deleteEvent(String eventId);

    @Query("SELECT * FROM events WHERE task_id = :taskId AND task_revision = :taskRevision " +
            "AND episode_id = :episodeId LIMIT 1")
    EventEntity findEventByEpisode(String taskId, long taskRevision, String episodeId);

    @Query("SELECT * FROM events ORDER BY occurred_at_epoch_millis DESC, event_id DESC LIMIT :limit")
    List<EventEntity> latestEvents(int limit);

    @Query("SELECT * FROM events WHERE task_id = :taskId " +
            "ORDER BY occurred_at_epoch_millis DESC, event_id DESC LIMIT :limit")
    List<EventEntity> latestEventsForTask(String taskId, int limit);

    @Query("SELECT * FROM events WHERE task_id = :taskId " +
            "AND occurred_at_epoch_millis <= :latestOccurredAtEpochMillis " +
            "ORDER BY occurred_at_epoch_millis DESC, event_id DESC LIMIT :limit")
    List<EventEntity> latestEventsForTaskBefore(
            String taskId,
            long latestOccurredAtEpochMillis,
            int limit
    );

    @Query("SELECT EXISTS(SELECT 1 FROM events " +
            "INNER JOIN product_tasks ON product_tasks.task_id = events.task_id " +
            "WHERE events.task_id = :taskId AND events.event_id = :eventId)")
    boolean localEventSnapshotOwnerExists(String taskId, String eventId);

    @Query("SELECT events.event_id FROM events INNER JOIN tasks ON tasks.task_id = events.task_id " +
            "WHERE tasks.runtime_snapshot_json LIKE " +
            "'{\"schema_version\":\"remote_event_cache_v3\",\"account_id\":\"' || :accountId || '\"%'")
    List<String> remoteEventIdsForAccount(String accountId);

    @Query("SELECT events.* FROM events INNER JOIN tasks ON tasks.task_id = events.task_id " +
            "WHERE events.event_id = :eventId " +
            "AND events.notification_delivered_at_epoch_millis IS NULL " +
            "AND tasks.runtime_snapshot_json NOT LIKE " +
            "'{\"schema_version\":\"remote_event_cache_v3\"%' LIMIT 1")
    EventEntity findPendingLocalNotification(String eventId);

    @Query("SELECT events.* FROM events INNER JOIN tasks ON tasks.task_id = events.task_id " +
            "WHERE events.notification_delivered_at_epoch_millis IS NULL " +
            "AND tasks.runtime_snapshot_json NOT LIKE " +
            "'{\"schema_version\":\"remote_event_cache_v3\"%' " +
            "ORDER BY events.occurred_at_epoch_millis, events.event_id LIMIT :limit")
    List<EventEntity> pendingLocalNotifications(int limit);

    @Query("SELECT EXISTS(SELECT 1 FROM events " +
            "INNER JOIN tasks ON tasks.task_id = events.task_id " +
            "WHERE events.event_id = :eventId " +
            "AND events.notification_delivered_at_epoch_millis IS NULL " +
            "AND tasks.runtime_snapshot_json LIKE :remoteSnapshotPrefix || '%')")
    boolean isPendingRemoteNotification(String eventId, String remoteSnapshotPrefix);

    @Query("SELECT events.* FROM events INNER JOIN tasks ON tasks.task_id = events.task_id " +
            "WHERE events.notification_delivered_at_epoch_millis IS NULL " +
            "AND tasks.runtime_snapshot_json LIKE :remoteSnapshotPrefix || '%' " +
            "ORDER BY events.occurred_at_epoch_millis, events.event_id LIMIT :limit")
    List<EventEntity> pendingRemoteNotifications(String remoteSnapshotPrefix, int limit);

    @Query("UPDATE events SET notification_attempt_count = notification_attempt_count + 1 " +
            "WHERE event_id = :eventId AND notification_delivered_at_epoch_millis IS NULL")
    int recordLocalNotificationAttempt(String eventId);

    @Query("UPDATE events SET notification_delivered_at_epoch_millis = :deliveredAtEpochMillis " +
            "WHERE event_id = :eventId AND notification_delivered_at_epoch_millis IS NULL")
    int markLocalNotificationDelivered(String eventId, long deliveredAtEpochMillis);

    @Query("UPDATE events SET notification_delivered_at_epoch_millis = :deliveredAtEpochMillis " +
            "WHERE event_id = :eventId AND notification_delivered_at_epoch_millis IS NULL " +
            "AND EXISTS (SELECT 1 FROM tasks WHERE tasks.task_id = events.task_id " +
            "AND tasks.runtime_snapshot_json LIKE :remoteSnapshotPrefix || '%')")
    int markRemoteNotificationDelivered(
            String eventId,
            long deliveredAtEpochMillis,
            String remoteSnapshotPrefix
    );

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertOutbox(OutboxEntity outbox);

    @Query("SELECT * FROM event_outbox WHERE event_id = :eventId LIMIT 1")
    OutboxEntity findOutbox(String eventId);

    @Query("SELECT COUNT(*) FROM events")
    int eventCount();

    @Query("SELECT COUNT(*) FROM event_outbox")
    int outboxCount();

    @Query("SELECT * FROM event_outbox WHERE next_attempt_at_epoch_millis <= :now " +
            "ORDER BY next_attempt_at_epoch_millis, event_id LIMIT :limit")
    List<OutboxEntity> readyOutbox(long now, int limit);

    @Query("SELECT event_outbox.* FROM event_outbox " +
            "INNER JOIN events ON events.event_id = event_outbox.event_id " +
            "WHERE event_outbox.next_attempt_at_epoch_millis <= :now " +
            "AND events.task_id IN (:taskIds) " +
            "ORDER BY event_outbox.next_attempt_at_epoch_millis, event_outbox.event_id " +
            "LIMIT :limit")
    List<OutboxEntity> readyOutboxForTasks(long now, int limit, List<String> taskIds);

    @Query("DELETE FROM event_outbox WHERE event_id = :eventId")
    int markEventDelivered(String eventId);

    @Query("DELETE FROM tasks WHERE task_id = :taskId")
    int deleteTask(String taskId);

    @Query("DELETE FROM tasks WHERE runtime_snapshot_json LIKE " +
            "'{\"schema_version\":\"remote_event_cache_v3\"%' " +
            "AND NOT EXISTS (SELECT 1 FROM product_tasks " +
            "WHERE product_tasks.task_id = tasks.task_id)")
    int deleteAllRemoteCacheTasks();

    /** Result: 1 inserted, 0 capacity reached, 2 identity already exists. */
    @Transaction
    default int insertProductTaskIfCapacity(LocalTaskEntity task, int maximumTasks) {
        if (maximumTasks <= 0) {
            throw new IllegalArgumentException("maximumTasks must be positive");
        }
        if (findProductTask(task.taskId) != null) {
            return 2;
        }
        if (productTaskCount() >= maximumTasks) {
            return 0;
        }
        return insertProductTask(task) == -1L ? 2 : 1;
    }

    /**
     * Atomically creates one complete product task with its exact immutable package pointer and
     * task/package/device sampling decision. Result: 1 inserted, 0 capacity reached, 2 identity
     * already exists. Any exception after the product insert rolls the transaction back.
     */
    @Transaction
    default int insertProductTaskAndSamplingIfCapacity(
            LocalTaskEntity task,
            ResolvedSamplingConfigEntity sampling,
            int maximumTasks
    ) {
        if (maximumTasks <= 0) {
            throw new IllegalArgumentException("maximumTasks must be positive");
        }
        if (!("structured_reading".equals(task.targetMode) ||
                "reference_images".equals(task.targetMode) ||
                "object_detection".equals(task.targetMode)) ||
                task.revision != 1L || sampling.taskRevision != 1L ||
                !task.taskId.equals(sampling.taskId) ||
                task.runtimePackageId == null || task.runtimePackageVersion == null ||
                task.runtimeManifestSha256 == null ||
                !task.runtimePackageId.equals(sampling.packageId) ||
                !task.runtimePackageVersion.equals(sampling.packageVersion) ||
                !task.runtimeManifestSha256.equals(sampling.manifestSha256)) {
            throw new IllegalArgumentException(
                    "configured task and sampling binding must match exactly"
            );
        }
        if (findProductTask(task.taskId) != null ||
                findResolvedSamplingConfig(task.taskId) != null) {
            return 2;
        }
        if (productTaskCount() >= maximumTasks) {
            return 0;
        }
        if (insertProductTask(task) == -1L) {
            return 2;
        }

        LocalTaskEntity inserted = findProductTask(task.taskId);
        if (inserted == null || inserted.revision != sampling.taskRevision ||
                !sampling.packageId.equals(inserted.runtimePackageId) ||
                !sampling.packageVersion.equals(inserted.runtimePackageVersion) ||
                !sampling.manifestSha256.equals(inserted.runtimeManifestSha256)) {
            throw new IllegalStateException(
                    "configured task insert lost its exact runtime package pointer"
            );
        }
        upsertResolvedSamplingConfig(sampling);
        ResolvedSamplingConfigEntity persisted = findResolvedSamplingConfig(task.taskId);
        if (persisted == null || !sameSamplingConfig(persisted, sampling)) {
            throw new IllegalStateException(
                    "configured task insert lost its exact sampling decision"
            );
        }
        return 1;
    }

    /** Atomically binds a package and the exact task/package/device sampling decision. */
    @Transaction
    default boolean bindRuntimePackageAndSampling(ResolvedSamplingConfigEntity config) {
        LocalTaskEntity task = findProductTask(config.taskId);
        if (task == null || task.revision != config.taskRevision) {
            return false;
        }
        int updated = updateRuntimePackageBinding(
                config.taskId,
                config.taskRevision,
                config.packageId,
                config.packageVersion,
                config.manifestSha256
        );
        if (updated != 1) {
            return false;
        }
        upsertResolvedSamplingConfig(config);
        return true;
    }

    /**
     * Carries an already verified immutable package/sampling decision across an ROI-only task
     * revision. The package, artifact, device fingerprint and cadence are unchanged; any stale
     * task/config pair fails before either row is mutated.
     */
    @Transaction
    default boolean reviseProductTaskTargetAndCarrySampling(
            ResolvedSamplingConfigEntity expected,
            long nextRevision,
            String targetJson
    ) {
        if (nextRevision != Math.addExact(expected.taskRevision, 1L)) {
            throw new IllegalArgumentException("next task revision must increment exactly once");
        }
        LocalTaskEntity task = findProductTask(expected.taskId);
        ResolvedSamplingConfigEntity sampling = findResolvedSamplingConfig(expected.taskId);
        if (task == null || sampling == null || task.revision != expected.taskRevision ||
                !sameSamplingConfig(sampling, expected) ||
                !expected.packageId.equals(task.runtimePackageId) ||
                !expected.packageVersion.equals(task.runtimePackageVersion) ||
                !expected.manifestSha256.equals(task.runtimeManifestSha256)) {
            return false;
        }
        if (updateProductTaskTargetRevision(
                expected.taskId,
                expected.taskRevision,
                nextRevision,
                targetJson
        ) != 1) {
            return false;
        }
        if (updateResolvedSamplingTaskRevision(
                expected.taskId,
                expected.taskRevision,
                nextRevision
        ) != 1) {
            throw new IllegalStateException("task revision changed without its sampling binding");
        }
        return true;
    }

    /** Carries the immutable package/sampling binding across a product-rule revision. */
    @Transaction
    default boolean reviseMonitorRuleAndCarrySampling(
            ResolvedSamplingConfigEntity expected,
            long nextRevision,
            String ruleJson
    ) {
        if (nextRevision != Math.addExact(expected.taskRevision, 1L)) {
            throw new IllegalArgumentException("next monitor revision must increment exactly once");
        }
        LocalTaskEntity task = findProductTask(expected.taskId);
        ResolvedSamplingConfigEntity sampling = findResolvedSamplingConfig(expected.taskId);
        if (task == null || sampling == null || task.revision != expected.taskRevision ||
                !sameSamplingConfig(sampling, expected) ||
                !expected.packageId.equals(task.runtimePackageId) ||
                !expected.packageVersion.equals(task.runtimePackageVersion) ||
                !expected.manifestSha256.equals(task.runtimeManifestSha256)) {
            return false;
        }
        if (updateMonitorRuleRevision(
                expected.taskId,
                expected.taskRevision,
                nextRevision,
                ruleJson
        ) != 1) {
            return false;
        }
        if (updateResolvedSamplingTaskRevision(
                expected.taskId,
                expected.taskRevision,
                nextRevision
        ) != 1) {
            throw new IllegalStateException("monitor rule changed without sampling revision");
        }
        return true;
    }

    /** Atomically confirms the reader format/manual ROI and threshold under one task revision. */
    @Transaction
    default boolean reviseReadingTargetAndRuleAndCarrySampling(
            ResolvedSamplingConfigEntity expected,
            long nextRevision,
            String targetJson,
            String ruleJson
    ) {
        if (nextRevision != Math.addExact(expected.taskRevision, 1L)) {
            throw new IllegalArgumentException("next monitor revision must increment exactly once");
        }
        LocalTaskEntity task = findProductTask(expected.taskId);
        ResolvedSamplingConfigEntity sampling = findResolvedSamplingConfig(expected.taskId);
        if (task == null || sampling == null ||
                !"structured_reading".equals(task.targetMode) ||
                task.revision != expected.taskRevision ||
                !sameSamplingConfig(sampling, expected) ||
                !expected.packageId.equals(task.runtimePackageId) ||
                !expected.packageVersion.equals(task.runtimePackageVersion) ||
                !expected.manifestSha256.equals(task.runtimeManifestSha256)) {
            return false;
        }
        if (updateReadingTargetAndRuleRevision(
                expected.taskId,
                expected.taskRevision,
                nextRevision,
                targetJson,
                ruleJson
        ) != 1) {
            return false;
        }
        if (updateResolvedSamplingTaskRevision(
                expected.taskId,
                expected.taskRevision,
                nextRevision
        ) != 1) {
            throw new IllegalStateException("reader target changed without sampling revision");
        }
        return true;
    }

    /** Carries the exact package/sampling binding across a reference-material revision. */
    @Transaction
    default boolean reviseReferenceMaterialsAndCarrySampling(
            ResolvedSamplingConfigEntity expected,
            long nextRevision,
            String materialRefsJson
    ) {
        if (nextRevision != Math.addExact(expected.taskRevision, 1L)) {
            throw new IllegalArgumentException("next monitor revision must increment exactly once");
        }
        LocalTaskEntity task = findProductTask(expected.taskId);
        ResolvedSamplingConfigEntity sampling = findResolvedSamplingConfig(expected.taskId);
        if (task == null || sampling == null ||
                !"reference_images".equals(task.targetMode) ||
                task.revision != expected.taskRevision ||
                !sameSamplingConfig(sampling, expected) ||
                !expected.packageId.equals(task.runtimePackageId) ||
                !expected.packageVersion.equals(task.runtimePackageVersion) ||
                !expected.manifestSha256.equals(task.runtimeManifestSha256)) {
            return false;
        }
        if (updateReferenceMaterialRevision(
                expected.taskId,
                expected.taskRevision,
                nextRevision,
                materialRefsJson
        ) != 1) {
            return false;
        }
        if (updateResolvedSamplingTaskRevision(
                expected.taskId,
                expected.taskRevision,
                nextRevision
        ) != 1) {
            throw new IllegalStateException("reference revision changed without sampling binding");
        }
        return true;
    }

    /** Revises an unprepared reference monitor only when no runtime binding exists. */
    @Transaction
    default boolean reviseReferenceMaterialsWithoutRuntime(
            String taskId,
            long expectedRevision,
            long nextRevision,
            String materialRefsJson
    ) {
        if (nextRevision != Math.addExact(expectedRevision, 1L)) {
            throw new IllegalArgumentException("next monitor revision must increment exactly once");
        }
        LocalTaskEntity task = findProductTask(taskId);
        if (task == null || !"reference_images".equals(task.targetMode) ||
                task.revision != expectedRevision || task.runtimePackageId != null ||
                task.runtimePackageVersion != null || task.runtimeManifestSha256 != null ||
                findResolvedSamplingConfig(taskId) != null) {
            return false;
        }
        return updateReferenceMaterialRevision(
                taskId,
                expectedRevision,
                nextRevision,
                materialRefsJson
        ) == 1;
    }

    static boolean sameSamplingIdentity(
            ResolvedSamplingConfigEntity left,
            ResolvedSamplingConfigEntity right
    ) {
        return left.taskId.equals(right.taskId) &&
                left.taskRevision == right.taskRevision &&
                left.catalogVersion.equals(right.catalogVersion) &&
                left.capabilityId.equals(right.capabilityId) &&
                left.modelProfileKey.equals(right.modelProfileKey) &&
                left.recipeId.equals(right.recipeId) &&
                left.intentKey.equals(right.intentKey) &&
                left.packageId.equals(right.packageId) &&
                left.packageVersion.equals(right.packageVersion) &&
                left.manifestSha256.equals(right.manifestSha256) &&
                left.artifactIdentitySha256.equals(right.artifactIdentitySha256) &&
                left.deviceFingerprintSha256.equals(right.deviceFingerprintSha256) &&
                left.manifestMinimumIntervalMillis == right.manifestMinimumIntervalMillis &&
                left.manifestMaximumIntervalMillis == right.manifestMaximumIntervalMillis &&
                left.adaptiveEnabled == right.adaptiveEnabled;
    }

    static boolean sameSamplingConfig(
            ResolvedSamplingConfigEntity left,
            ResolvedSamplingConfigEntity right
    ) {
        return sameSamplingIdentity(left, right) && left.intervalMillis == right.intervalMillis;
    }

    /** Ignores stale revisions and out-of-order observations. */
    @Transaction
    default boolean upsertLatestTaskReadingIfNewer(LatestTaskReadingEntity reading) {
        LocalTaskEntity task = findProductTask(reading.taskId);
        if (task == null || task.revision != reading.taskRevision) {
            return false;
        }
        LatestTaskReadingEntity current = findLatestTaskReading(reading.taskId);
        if (current != null && (
                current.taskRevision > reading.taskRevision ||
                (current.taskRevision == reading.taskRevision &&
                        current.sourceSequence >= reading.sourceSequence)
        )) {
            return false;
        }
        upsertLatestTaskReading(reading);
        return true;
    }

    /** Product deletion also removes any runtime snapshot and its Event/Outbox descendants. */
    @Transaction
    default boolean deleteProductTaskAndRuntime(String taskId) {
        if (findProductTask(taskId) == null) {
            return false;
        }
        deleteTask(taskId);
        return deleteProductTask(taskId) == 1;
    }

    /** Event insertion wins first; only a newly inserted event gets an outbox row. */
    @Transaction
    default boolean insertEventAndOutbox(EventEntity event, OutboxEntity outbox) {
        long rowId = insertEvent(event);
        if (rowId == -1L) {
            return false;
        }
        insertOutbox(outbox);
        return true;
    }
}
