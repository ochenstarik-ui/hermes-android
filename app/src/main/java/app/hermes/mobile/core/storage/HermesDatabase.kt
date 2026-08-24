package app.hermes.mobile.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HostEntity::class,
        UnifiedSessionEntity::class,
        HostBindingEntity::class,
        UnifiedMessageEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun unifiedSessionDao(): UnifiedSessionDao

    companion object {
        @Volatile
        private var INSTANCE: HermesDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN certificateFingerprint TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): HermesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HermesDatabase::class.java,
                    "hermes_unified.db"
                )
                    .addMigrations(MIGRATION_1_2)
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
                .addMigrations(MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()
        }
    }
}
