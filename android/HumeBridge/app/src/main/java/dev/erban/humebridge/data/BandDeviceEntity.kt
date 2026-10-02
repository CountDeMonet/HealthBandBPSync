package dev.erban.humebridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "band_device")
data class BandDeviceEntity(
    @PrimaryKey val address: String,
    val name: String?,
    val lastSeenAtEpochMillis: Long,
    val selected: Boolean = true,
)
