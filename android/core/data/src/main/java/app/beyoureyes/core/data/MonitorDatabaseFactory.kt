package app.beyoureyes.core.data

import android.content.Context
import androidx.room.Room

object MonitorDatabaseFactory {
    const val DATABASE_NAME = "be-your-eyes-product-v1.db"

    @Volatile
    private var instance: MonitorDatabase? = null

    fun open(context: Context): MonitorDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            MonitorDatabase::class.java,
            DATABASE_NAME,
        ).build()
            .also { instance = it }
    }
}
