package dev.erban.humebridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_run")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val status: String,
    val recordsFetched: Int,
    val recordsNew: Int,
    val errorMessage: String?,
)
