package dev.erban.humebridge.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hrv_history_record",
    indices = [
        Index(value = ["bandAddress", "deviceTimeLocal"], unique = true),
        Index(value = ["rawSha256"], unique = true),
    ],
)
data class HrvHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bandAddress: String,
    val deviceTimeLocal: String,
    val instantUtc: String,
    val zoneOffsetSeconds: Int,
    val rawHex: String,
    val rawSha256: String,
    val hrv: Int,
    val vascularAging: Int,
    val heartRate: Int,
    val stress: Int,
    val bpSystolic: Int,
    val bpDiastolic: Int,
    val bpProvenance: String = BP_PROVENANCE,
    val healthConnectClientRecordId: String? = null,
    val healthConnectStatus: String = HEALTH_CONNECT_PENDING,
    val healthConnectWrittenAtEpochMillis: Long? = null,
    val healthConnectLastError: String? = null,
    val firstFetchedAtEpochMillis: Long,
    val lastFetchedAtEpochMillis: Long,
) {
    companion object {
        const val BP_PROVENANCE = "Hume/J2208-derived BP estimate from stored 0x56 HRV history; not a cuff measurement"
        const val HEALTH_CONNECT_PENDING = "pending"
        const val HEALTH_CONNECT_WRITTEN = "written"
        const val HEALTH_CONNECT_FAILED = "failed"
        const val HEALTH_CONNECT_SKIPPED = "skipped"
    }
}
