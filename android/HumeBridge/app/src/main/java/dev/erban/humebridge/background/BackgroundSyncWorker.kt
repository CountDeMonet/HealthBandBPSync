package dev.erban.humebridge.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.erban.humebridge.SyncRepository
import dev.erban.humebridge.ble.BandScanner
import dev.erban.humebridge.ble.J2208GattClient
import dev.erban.humebridge.data.HumeBridgeDatabase
import dev.erban.humebridge.health.HealthConnectBpBridge
import dev.erban.humebridge.settings.AppSettingsStore

class BackgroundSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settingsStore = AppSettingsStore(applicationContext)
        val settings = settingsStore.settings.value
        if (!settings.backgroundSync.enabled) return Result.success()

        val repository = SyncRepository(
            database = HumeBridgeDatabase.get(applicationContext),
            scanner = BandScanner(applicationContext),
            gattClient = J2208GattClient(applicationContext),
            healthConnect = HealthConnectBpBridge(applicationContext),
        )

        val status = runCatching {
            if (!repository.hasConnectPermission()) error("Missing Bluetooth connect permission")
            if (!repository.isBluetoothEnabled()) error("Bluetooth is disabled")
            repository.syncSelectedBand()
            val permissionState = repository.healthConnectPermissionState()
            if (permissionState.hasBloodPressurePermissions) {
                val write = repository.writePendingBpToHealthConnect()
                "Background sync complete; ${write.written} BP records written"
            } else {
                "Background sync complete; Health Connect BP permission not granted"
            }
        }.fold(
            onSuccess = { it },
            onFailure = { "Background sync failed: ${it.message ?: it::class.java.simpleName}" },
        )

        settingsStore.recordBackgroundSyncAttempt(status)
        if (settingsStore.settings.value.backgroundSync.enabled) {
            BackgroundSyncScheduler.scheduleNext(applicationContext)
        }
        return Result.success()
    }
}
