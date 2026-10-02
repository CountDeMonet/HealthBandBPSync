package dev.erban.humebridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HrvHistoryDao {
    @Query("SELECT * FROM hrv_history_record ORDER BY deviceTimeLocal DESC")
    fun recordsFlow(): Flow<List<HrvHistoryEntity>>

    @Query("SELECT * FROM hrv_history_record ORDER BY deviceTimeLocal DESC LIMIT 1")
    fun latestFlow(): Flow<HrvHistoryEntity?>

    @Query("SELECT COUNT(*) FROM hrv_history_record")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM hrv_history_record WHERE bpSystolic > 0 AND bpDiastolic > 0")
    fun eligibleCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM hrv_history_record WHERE bpSystolic > 0 AND bpDiastolic > 0 AND healthConnectStatus = 'written'")
    fun writtenCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM hrv_history_record WHERE bpSystolic > 0 AND bpDiastolic > 0 AND healthConnectStatus IN ('pending', 'failed')")
    fun pendingOrFailedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM hrv_history_record WHERE bpSystolic > 0 AND bpDiastolic > 0 AND healthConnectStatus = 'failed'")
    fun failedCountFlow(): Flow<Int>

    @Query("SELECT * FROM hrv_history_record WHERE bpSystolic > 0 AND bpDiastolic > 0 AND healthConnectStatus IN ('pending', 'failed') ORDER BY deviceTimeLocal ASC")
    suspend fun pendingEligibleForHealthConnect(): List<HrvHistoryEntity>

    @Query("SELECT * FROM hrv_history_record ORDER BY deviceTimeLocal DESC")
    suspend fun allForExport(): List<HrvHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: HrvHistoryEntity): Long

    @Update
    suspend fun update(record: HrvHistoryEntity)
}
