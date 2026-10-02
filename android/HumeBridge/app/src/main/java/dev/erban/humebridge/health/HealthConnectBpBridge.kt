package dev.erban.humebridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Pressure
import dev.erban.humebridge.data.HrvHistoryEntity
import java.time.Instant
import java.time.ZoneOffset

data class HealthConnectPermissionState(
    val availability: HealthConnectAvailability,
    val hasBloodPressurePermissions: Boolean,
)

enum class HealthConnectAvailability {
    Available,
    Unavailable,
    UpdateRequired,
}

data class HealthConnectWriteResult(
    val attempted: Int,
    val written: Int,
    val failed: Int,
    val verified: Int,
)

class HealthConnectBpBridge(private val context: Context) {
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
    )

    fun requestPermissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun permissionState(): HealthConnectPermissionState {
        val availability = availability()
        if (availability != HealthConnectAvailability.Available) {
            return HealthConnectPermissionState(availability, false)
        }
        val granted = client().permissionController.getGrantedPermissions()
        return HealthConnectPermissionState(availability, granted.containsAll(permissions))
    }

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
            else -> HealthConnectAvailability.Unavailable
        }

    suspend fun writeBloodPressure(record: HrvHistoryEntity): String {
        val clientRecordId = record.stableHealthConnectClientRecordId()
        client().insertRecords(listOf(record.toHealthConnectRecord(clientRecordId)))
        return clientRecordId
    }

    suspend fun verifyWritten(records: List<HrvHistoryEntity>): Set<String> {
        if (records.isEmpty()) return emptySet()
        val instants = records.map { Instant.parse(it.instantUtc) }
        val start = instants.minOrNull()!!.minusSeconds(60)
        val end = instants.maxOrNull()!!.plusSeconds(60)
        val response = client().readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                pageSize = 5000,
            )
        )
        return response.records.mapNotNull { it.metadata.clientRecordId }.toSet()
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)
}

fun HrvHistoryEntity.isValidBpEstimate(): Boolean = bpSystolic > 0 && bpDiastolic > 0

fun HrvHistoryEntity.stableHealthConnectClientRecordId(): String =
    "hume-j2208-bp:$bandAddress:$deviceTimeLocal:$rawSha256"

private fun HrvHistoryEntity.toHealthConnectRecord(clientRecordId: String): BloodPressureRecord {
    val device = Device(
        type = Device.TYPE_FITNESS_BAND,
        manufacturer = "Hume/J2208",
        model = "J2208-family band",
    )
    return BloodPressureRecord(
        time = Instant.parse(instantUtc),
        zoneOffset = ZoneOffset.ofTotalSeconds(zoneOffsetSeconds),
        metadata = Metadata.autoRecorded(
            device = device,
            clientRecordId = clientRecordId,
            clientRecordVersion = 0,
        ),
        systolic = Pressure.millimetersOfMercury(bpSystolic.toDouble()),
        diastolic = Pressure.millimetersOfMercury(bpDiastolic.toDouble()),
        bodyPosition = BloodPressureRecord.BODY_POSITION_UNKNOWN,
        measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN,
    )
}
