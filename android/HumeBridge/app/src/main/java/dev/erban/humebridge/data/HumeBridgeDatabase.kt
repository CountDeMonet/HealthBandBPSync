package dev.erban.humebridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BandDeviceEntity::class, HrvHistoryEntity::class, SyncRunEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class HumeBridgeDatabase : RoomDatabase() {
    abstract fun bandDeviceDao(): BandDeviceDao
    abstract fun hrvHistoryDao(): HrvHistoryDao
    abstract fun syncRunDao(): SyncRunDao

    companion object {
        @Volatile private var instance: HumeBridgeDatabase? = null

        fun get(context: Context): HumeBridgeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HumeBridgeDatabase::class.java,
                    "humebridge.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE hrv_history_record ADD COLUMN bpProvenance TEXT NOT NULL DEFAULT '${HrvHistoryEntity.BP_PROVENANCE}'"
                )
                db.execSQL("ALTER TABLE hrv_history_record ADD COLUMN healthConnectClientRecordId TEXT")
                db.execSQL(
                    "ALTER TABLE hrv_history_record ADD COLUMN healthConnectStatus TEXT NOT NULL DEFAULT '${HrvHistoryEntity.HEALTH_CONNECT_PENDING}'"
                )
                db.execSQL("ALTER TABLE hrv_history_record ADD COLUMN healthConnectWrittenAtEpochMillis INTEGER")
                db.execSQL("ALTER TABLE hrv_history_record ADD COLUMN healthConnectLastError TEXT")
            }
        }
    }
}
