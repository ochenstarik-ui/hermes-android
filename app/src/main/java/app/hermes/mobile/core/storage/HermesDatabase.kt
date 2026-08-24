package app.hermes.mobile.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HostEntity::class,
        UnifiedSessionEntity::class,
        HostBindingEntity::class,
        UnifiedMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun unifiedSessionDao(): UnifiedSessionDao

    companion object {
        @Volatile
        private var INSTANCE: HermesDatabase? = null

        fun getInstance(context: Context): HermesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HermesDatabase::class.java,
                    "hermes_unified.db"
                )
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun createInMemory(context: Context): HermesDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                HermesDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
