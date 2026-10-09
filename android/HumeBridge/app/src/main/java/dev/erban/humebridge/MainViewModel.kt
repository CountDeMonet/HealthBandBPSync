package dev.erban.humebridge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.erban.humebridge.background.BackgroundSyncScheduler
import dev.erban.humebridge.ble.BandScanner
import dev.erban.humebridge.ble.J2208GattClient
import dev.erban.humebridge.ble.ScannedBand
import dev.erban.humebridge.data.BandDeviceEntity
import dev.erban.humebridge.data.HrvHistoryEntity
import dev.erban.humebridge.data.HumeBridgeDatabase
import dev.erban.humebridge.data.SyncRunEntity
import dev.erban.humebridge.health.HealthConnectAvailability
import dev.erban.humebridge.health.HealthConnectBpBridge
import dev.erban.humebridge.health.HealthConnectPermissionState
import dev.erban.humebridge.settings.AppMode
import dev.erban.humebridge.settings.AppSettingsState
import dev.erban.humebridge.settings.AppSettingsStore
import dev.erban.humebridge.settings.BackgroundSyncSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val selectedBand: BandDeviceEntity? = null,
    val appMode: AppMode = AppMode.COMPANION,
    val backgroundSync: BackgroundSyncSettings = BackgroundSyncSettings(),
    val latestRecord: HrvHistoryEntity? = null,
    val recentRecords: List<HrvHistoryEntity> = emptyList(),
    val recordCount: Int = 0,
    val eligibleBpCount: Int = 0,
    val writtenBpCount: Int = 0,
    val pendingOrFailedBpCount: Int = 0,
    val failedBpCount: Int = 0,
    val healthConnect: HealthConnectPermissionState = HealthConnectPermissionState(
        HealthConnectAvailability.Unavailable,
        false,
    ),
    val latestSync: SyncRunEntity? = null,
    val scannedBands: List<ScannedBand> = emptyList(),
    val busy: Boolean = false,
    val status: String = "Ready",
    val error: String? = null,
)

private data class BpCounts(
    val eligible: Int,
    val written: Int,
    val pendingOrFailed: Int,
    val failed: Int,
)

private data class DbUiState(
    val selectedBand: BandDeviceEntity?,
    val recordCount: Int,
    val latestSync: SyncRunEntity?,
    val records: List<HrvHistoryEntity>,
    val bpCounts: BpCounts,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = AppSettingsStore(application)

    private val repository = SyncRepository(
        database = HumeBridgeDatabase.get(application),
        scanner = BandScanner(application),
        gattClient = J2208GattClient(application),
        healthConnect = HealthConnectBpBridge(application),
    )

    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(
        MainUiState(
            status = if (repository.isBluetoothEnabled()) "Ready" else "Bluetooth is disabled",
        )
    )

    private val bpCounts = combine(
        repository.eligibleBpCount,
        repository.writtenBpCount,
        repository.pendingOrFailedBpCount,
        repository.failedBpCount,
    ) { eligible, written, pendingOrFailed, failed ->
        BpCounts(eligible, written, pendingOrFailed, failed)
    }

    private val dbUiState = combine(
        repository.selectedBand,
        repository.recordCount,
        repository.latestSync,
        repository.records,
        bpCounts,
    ) { selectedBand, count, latestSync, records, counts ->
        DbUiState(selectedBand, count, latestSync, records, counts)
    }

    val uiState: StateFlow<MainUiState> = combine(
        dbUiState,
        settingsStore.settings,
        mutableState,
    ) { db, settings, local ->
        val validBpRecords = db.records.filter { it.bpSystolic > 0 && it.bpDiastolic > 0 }
        local.copy(
            selectedBand = db.selectedBand,
            appMode = settings.appMode,
            backgroundSync = settings.backgroundSync,
            latestRecord = validBpRecords.firstOrNull(),
            recentRecords = validBpRecords.take(30),
            recordCount = db.recordCount,
            eligibleBpCount = db.bpCounts.eligible,
            writtenBpCount = db.bpCounts.written,
            pendingOrFailedBpCount = db.bpCounts.pendingOrFailed,
            failedBpCount = db.bpCounts.failed,
            latestSync = db.latestSync,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mutableState.value)

    fun hasBlePermissions(): Boolean =
        repository.hasScanPermission() && repository.hasConnectPermission()

    val healthPermissions: Set<String> get() = repository.healthPermissions

    fun setAppMode(mode: AppMode) {
        settingsStore.setAppMode(mode)
        mutableState.value = mutableState.value.copy(
            status = "${mode.displayName} mode selected",
            error = null,
        )
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        BackgroundSyncScheduler.setEnabled(getApplication(), enabled)
        settingsStore.refresh()
        mutableState.value = mutableState.value.copy(
            status = if (enabled) "Background sync enabled" else "Background sync disabled",
            error = null,
        )
    }

    fun refreshHealthConnect() {
        viewModelScope.launch {
            runCatching { repository.healthConnectPermissionState() }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(healthConnect = it)
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        healthConnect = HealthConnectPermissionState(
                            HealthConnectAvailability.Unavailable,
                            false,
                        )
                    )
                }
        }
    }

    fun scan() {
        viewModelScope.launch {
            setBusy("Scanning for J2208-family bands...")
            runCatching { repository.scan() }
                .onSuccess { bands ->
                    mutableState.value = mutableState.value.copy(
                        scannedBands = bands,
                        busy = false,
                        status = if (bands.isEmpty()) "No matching bands found" else "Found ${bands.size} band(s)",
                        error = null,
                    )
                }
                .onFailure { fail("Scan failed", it) }
        }
    }

    fun selectBand(band: ScannedBand) {
        viewModelScope.launch {
            runCatching { repository.selectBand(band) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        status = "Selected ${band.name ?: band.address}",
                        error = null,
                    )
                }
                .onFailure { fail("Could not select band", it) }
        }
    }

    fun sync() {
        viewModelScope.launch {
            setBusy("Syncing 0x56 HRV/BP history...")
            runCatching { repository.syncSelectedBand() }
                .onSuccess { result ->
                    refreshHealthConnect()
                    mutableState.value = mutableState.value.copy(
                        busy = false,
                        status = "Sync complete: ${result.parsedRecords} parsed, ${result.newRecords} new",
                        error = null,
                    )
                }
                .onFailure { fail("Sync failed", it) }
        }
    }

    fun syncAndWriteBpToHealthConnect() {
        viewModelScope.launch {
            setBusy("Syncing band, then writing BP to Health Connect...")
            var syncResult: SyncResult? = null
            runCatching {
                syncResult = repository.syncSelectedBand()
                repository.writePendingBpToHealthConnect()
            }.onSuccess { writeResult ->
                refreshHealthConnect()
                val sync = syncResult
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    status = "Sync + write complete: ${sync?.newRecords ?: 0} new records, " +
                        "${writeResult.written} HC writes, ${writeResult.verified} verified",
                    error = if (writeResult.failed > 0) {
                        "${writeResult.failed} records failed; retained locally"
                    } else {
                        null
                    },
                )
            }.onFailure {
                val sync = syncResult
                val prefix = if (sync != null) {
                    "Band sync completed (${sync.newRecords} new), HC write failed"
                } else {
                    "Sync + write failed"
                }
                fail(prefix, it)
            }
        }
    }

    fun writeBpToHealthConnect() {
        viewModelScope.launch {
            setBusy("Writing BP estimates to Health Connect...")
            runCatching { repository.writePendingBpToHealthConnect() }
                .onSuccess { result ->
                    refreshHealthConnect()
                    mutableState.value = mutableState.value.copy(
                        busy = false,
                        status = "Health Connect write: ${result.written}/${result.attempted} written, ${result.verified} verified",
                        error = if (result.failed > 0) "${result.failed} records failed; retained locally" else null,
                    )
                }
                .onFailure { fail("Health Connect write failed", it) }
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            setBusy("Exporting CSV...")
            runCatching {
                repository.exportCsv(getApplication<Application>().contentResolver, uri)
            }.onSuccess {
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    status = "CSV export complete",
                    error = null,
                )
            }.onFailure { fail("CSV export failed", it) }
        }
    }

    init {
        refreshHealthConnect()
    }

    private fun setBusy(status: String) {
        mutableState.value = mutableState.value.copy(busy = true, status = status, error = null)
    }

    private fun fail(prefix: String, throwable: Throwable) {
        mutableState.value = mutableState.value.copy(
            busy = false,
            status = prefix,
            error = throwable.message ?: throwable::class.java.simpleName,
        )
    }
}


