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
        UnifiedMessageEntity::class,
        UsedNonceEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun unifiedSessionDao(): UnifiedSessionDao
    abstract fun usedNonceDao(): UsedNonceDao

    companion object {
        @Volatile
        private var INSTANCE: HermesDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN certificateFingerprint TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `used_nonces` (`nonce` TEXT NOT NULL, `expiresAt` INTEGER NOT NULL, `usedAt` INTEGER NOT NULL, PRIMARY KEY(`nonce`))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("SELECT id, baseUrl FROM hosts")
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val oldUrl = cursor.getString(1)
                    if (!oldUrl.startsWith("http://") && !oldUrl.startsWith("https://")) {
                        val newUrl = "https://$oldUrl"
                        db.execSQL("UPDATE hosts SET baseUrl = ? WHERE id = ?", arrayOf(newUrl, id))
                    }
                }
                cursor.close()
            }
        }

        fun getInstance(context: Context): HermesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HermesDatabase::class.java,
                    "hermes_unified.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
