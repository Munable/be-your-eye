package app.beyoureyes.core.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                TaskEntity.class,
                EventEntity.class,
                OutboxEntity.class,
                LocalTaskEntity.class,
                LatestTaskReadingEntity.class,
                ResolvedSamplingConfigEntity.class
        },
        version = 1,
        exportSchema = true
)
public abstract class MonitorDatabase extends RoomDatabase {
    public abstract MonitoringDao monitoringDao();
}
