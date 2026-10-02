package dev.erban.humebridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.erban.humebridge.protocol.J2208Protocol
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

class BandScanner(private val context: Context) {
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    fun hasRequiredPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    suspend fun scan(seconds: Long = 10): List<ScannedBand> = suspendCancellableCoroutine { cont ->
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !hasRequiredPermission()) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val serviceUuid = UUID.fromString(J2208Protocol.SERVICE_UUID)
        val seen = linkedMapOf<String, ScannedBand>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
                val name = result.scanRecord?.deviceName ?: result.device.name
                val likelyName = name?.lowercase()?.let {
                    it.startsWith("hume band v2") || it.startsWith("2301b") || it.startsWith("x3b")
                } == true
                val advertises = serviceUuid in uuids
                if (advertises || likelyName) {
                    seen[result.device.address] = ScannedBand(
                        address = result.device.address,
                        name = name,
                        rssi = result.rssi,
                        advertisesJ2208Service = advertises,
                    )
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (cont.isActive) cont.resume(seen.values.toList())
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)

        val handler = android.os.Handler(context.mainLooper)
        val stop = Runnable {
            runCatching { scanner.stopScan(callback) }
            if (cont.isActive) cont.resume(seen.values.sortedWith(compareByDescending<ScannedBand> { it.advertisesJ2208Service }.thenByDescending { it.rssi }))
        }
        handler.postDelayed(stop, seconds * 1000)
        cont.invokeOnCancellation {
            handler.removeCallbacks(stop)
            runCatching { scanner.stopScan(callback) }
        }
    }
}
