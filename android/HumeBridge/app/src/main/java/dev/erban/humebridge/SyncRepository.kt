package dev.erban.humebridge

import android.content.ContentResolver
import android.net.Uri
import dev.erban.humebridge.ble.BandScanner
import dev.erban.humebridge.ble.J2208GattClient
import dev.erban.humebridge.ble.ScannedBand
import dev.erban.humebridge.data.BandDeviceEntity
import dev.erban.humebridge.data.HrvHistoryEntity
import dev.erban.humebridge.data.HumeBridgeDatabase
import dev.erban.humebridge.data.SyncRunEntity
import dev.erban.humebridge.health.HealthConnectBpBridge
import dev.erban.humebridge.health.HealthConnectPermissionState
import dev.erban.humebridge.health.HealthConnectWriteResult
import dev.erban.humebridge.health.isValidBpEstimate
import dev.erban.humebridge.health.stableHealthConnectClientRecordId
import dev.erban.humebridge.protocol.HrvHistoryRecord
import dev.erban.humebridge.protocol.J2208Protocol
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SyncResult(
    val packets: Int,
    val parsedRecords: Int,
    val newRecords: Int,
)

class SyncRepository(
    private val database: HumeBridgeDatabase,
    private val scanner: BandScanner,
    private val gattClient: J2208GattClient,
    private val healthConnect: HealthConnectBpBridge,
) {
    val selectedBand = database.bandDeviceDao().selectedBandFlow()
    val records = database.hrvHistoryDao().recordsFlow()
    val latestRecord = database.hrvHistoryDao().latestFlow()
    val recordCount = database.hrvHistoryDao().countFlow()
    val eligibleBpCount = database.hrvHistoryDao().eligibleCountFlow()
    val writtenBpCount = database.hrvHistoryDao().writtenCountFlow()
    val pendingOrFailedBpCount = database.hrvHistoryDao().pendingOrFailedCountFlow()
    val failedBpCount = database.hrvHistoryDao().failedCountFlow()
    val latestSync = database.syncRunDao().latestFlow()

    fun isBluetoothEnabled(): Boolean = scanner.isBluetoothEnabled()
    fun hasScanPermission(): Boolean = scanner.hasRequiredPermission()
    fun hasConnectPermission(): Boolean = gattClient.hasRequiredPermission()
    val healthPermissions: Set<String> get() = healthConnect.permissions

    suspend fun healthConnectPermissionState(): HealthConnectPermissionState =
        healthConnect.permissionState()

    suspend fun scan(): List<ScannedBand> = scanner.scan()

    suspend fun selectBand(band: ScannedBand) {
        database.bandDeviceDao().clearSelected()
        database.bandDeviceDao().upsert(
            BandDeviceEntity(
                address = band.address,
                name = band.name,
                lastSeenAtEpochMillis = System.currentTimeMillis(),
                selected = true,
            )
        )
    }

    suspend fun syncSelectedBand(): SyncResult {
        val band = database.bandDeviceDao().selectedBand()
            ?: error("Select a band before syncing")
        val started = System.currentTimeMillis()
        return try {
            val packets = gattClient.downloadHrvHistory(band.address)
            val parsed = packets.flatMap { J2208Protocol.parseHrvRecords(it) }
            val newCount = saveRecords(band.address, parsed)
            database.syncRunDao().insert(
                SyncRunEntity(
                    startedAtEpochMillis = started,
                    finishedAtEpochMillis = System.currentTimeMillis(),
                    status = "success",
                    recordsFetched = parsed.size,
                    recordsNew = newCount,
                    errorMessage = null,
                )
            )
            SyncResult(packets = packets.size, parsedRecords = parsed.size, newRecords = newCount)
        } catch (t: Throwable) {
            database.syncRunDao().insert(
                SyncRunEntity(
                    startedAtEpochMillis = started,
                    finishedAtEpochMillis = System.currentTimeMillis(),
                    status = "error",
                    recordsFetched = 0,
                    recordsNew = 0,
                    errorMessage = t.message ?: t::class.java.simpleName,
                )
            )
            throw t
        }
    }

    suspend fun exportCsv(contentResolver: ContentResolver, uri: Uri) {
        val rows = database.hrvHistoryDao().allForExport()
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
            out.appendLine("device_time_local,instant_utc,hrv,vascular_aging,heart_rate,stress,bp_systolic,bp_diastolic,raw_hex,raw_sha256,first_fetched_at_epoch_ms,bp_provenance,health_connect_status,health_connect_client_record_id,health_connect_written_at_epoch_ms,health_connect_last_error")
            rows.forEach { row ->
                out.appendCsv(row.deviceTimeLocal)
                out.append(',')
                out.appendCsv(row.instantUtc)
                out.append(',')
                out.append(row.hrv.toString())
                out.append(',')
                out.append(row.vascularAging.toString())
                out.append(',')
                out.append(row.heartRate.toString())
                out.append(',')
                out.append(row.stress.toString())
                out.append(',')
                out.append(row.bpSystolic.toString())
                out.append(',')
                out.append(row.bpDiastolic.toString())
                out.append(',')
                out.appendCsv(row.rawHex)
                out.append(',')
                out.appendCsv(row.rawSha256)
                out.append(',')
                out.append(row.firstFetchedAtEpochMillis.toString())
                out.append(',')
                out.appendCsv(row.bpProvenance)
                out.append(',')
                out.appendCsv(row.healthConnectStatus)
                out.append(',')
                out.appendCsv(row.healthConnectClientRecordId.orEmpty())
                out.append(',')
                out.append(row.healthConnectWrittenAtEpochMillis?.toString().orEmpty())
                out.append(',')
                out.appendCsv(row.healthConnectLastError.orEmpty())
                out.appendLine()
            }
        } ?: error("Unable to open export destination")
    }

    suspend fun writePendingBpToHealthConnect(): HealthConnectWriteResult {
        val permissionState = healthConnect.healthConnectPermissionStateOrThrow()
        if (!permissionState.hasBloodPressurePermissions) {
            error("Health Connect blood-pressure permissions are not granted")
        }

        val pending = database.hrvHistoryDao().pendingEligibleForHealthConnect()
        var attempted = 0
        var written = 0
        var failed = 0
        val writtenRows = mutableListOf<HrvHistoryEntity>()

        for (row in pending) {
            attempted++
            if (!row.isValidBpEstimate()) {
                markHealthConnectFailed(row, "Skipped invalid BP estimate")
                failed++
                continue
            }
            runCatching {
                val clientRecordId = healthConnect.writeBloodPressure(row)
                val updated = row.copy(
                    healthConnectClientRecordId = clientRecordId,
                    healthConnectStatus = HrvHistoryEntity.HEALTH_CONNECT_WRITTEN,
                    healthConnectWrittenAtEpochMillis = System.currentTimeMillis(),
                    healthConnectLastError = null,
                )
                database.hrvHistoryDao().update(updated)
                writtenRows += updated
                written++
            }.onFailure {
                markHealthConnectFailed(row, it.message ?: it::class.java.simpleName)
                failed++
            }
        }

        val verifiedIds = runCatching { healthConnect.verifyWritten(writtenRows) }.getOrDefault(emptySet())
        return HealthConnectWriteResult(
            attempted = attempted,
            written = written,
            failed = failed,
            verified = writtenRows.count { it.stableHealthConnectClientRecordId() in verifiedIds },
        )
    }

    private suspend fun saveRecords(address: String, records: List<HrvHistoryRecord>): Int {
        var newCount = 0
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        records.forEach { record ->
            val zoned = record.deviceTime.atZone(zone)
            val inserted = database.hrvHistoryDao().insertIgnore(
                HrvHistoryEntity(
                    bandAddress = address,
                    deviceTimeLocal = record.deviceTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    instantUtc = zoned.toInstant().toString(),
                    zoneOffsetSeconds = zoned.offset.totalSeconds,
                    rawHex = record.rawHex,
                    rawSha256 = record.rawSha256,
                    hrv = record.hrv,
                    vascularAging = record.vascularAging,
                    heartRate = record.heartRate,
                    stress = record.stress,
                    bpSystolic = record.bpSystolic,
                    bpDiastolic = record.bpDiastolic,
                    bpProvenance = HrvHistoryEntity.BP_PROVENANCE,
                    healthConnectClientRecordId = null,
                    healthConnectStatus = HrvHistoryEntity.HEALTH_CONNECT_PENDING,
                    healthConnectWrittenAtEpochMillis = null,
                    healthConnectLastError = null,
                    firstFetchedAtEpochMillis = now,
                    lastFetchedAtEpochMillis = now,
                )
            )
            if (inserted != -1L) newCount++
        }
        return newCount
    }

    private suspend fun markHealthConnectFailed(row: HrvHistoryEntity, message: String) {
        database.hrvHistoryDao().update(
            row.copy(
                healthConnectClientRecordId = row.healthConnectClientRecordId
                    ?: row.stableHealthConnectClientRecordId(),
                healthConnectStatus = HrvHistoryEntity.HEALTH_CONNECT_FAILED,
                healthConnectLastError = message,
            )
        )
    }
}

private suspend fun HealthConnectBpBridge.healthConnectPermissionStateOrThrow(): HealthConnectPermissionState {
    val state = permissionState()
    if (state.availability != dev.erban.humebridge.health.HealthConnectAvailability.Available) {
        error("Health Connect is ${state.availability}")
    }
    return state
}

private fun Appendable.appendCsv(value: String) {
    append('"')
    append(value.replace("\"", "\"\""))
    append('"')
}
