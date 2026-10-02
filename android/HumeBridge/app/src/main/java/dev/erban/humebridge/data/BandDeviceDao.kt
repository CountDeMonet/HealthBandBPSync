package dev.erban.humebridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BandDeviceDao {
    @Query("SELECT * FROM band_device WHERE selected = 1 LIMIT 1")
    fun selectedBandFlow(): Flow<BandDeviceEntity?>

    @Query("SELECT * FROM band_device WHERE selected = 1 LIMIT 1")
    suspend fun selectedBand(): BandDeviceEntity?

    @Query("UPDATE band_device SET selected = 0")
    suspend fun clearSelected()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: BandDeviceEntity)
}
