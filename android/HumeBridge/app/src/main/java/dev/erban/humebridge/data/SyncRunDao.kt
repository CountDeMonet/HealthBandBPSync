package dev.erban.humebridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRunDao {
    @Query("SELECT * FROM sync_run ORDER BY startedAtEpochMillis DESC LIMIT 1")
    fun latestFlow(): Flow<SyncRunEntity?>

    @Insert
    suspend fun insert(run: SyncRunEntity): Long
}
