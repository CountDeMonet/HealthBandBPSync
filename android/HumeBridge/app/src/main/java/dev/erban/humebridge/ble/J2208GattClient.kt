package dev.erban.humebridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.erban.humebridge.protocol.J2208Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID

class J2208GattClient(private val context: Context) {
    private val serviceUuid = UUID.fromString(J2208Protocol.SERVICE_UUID)
    private val writeUuid = UUID.fromString(J2208Protocol.WRITE_UUID)
    private val notifyUuid = UUID.fromString(J2208Protocol.NOTIFY_UUID)
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun hasRequiredPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun downloadHrvHistory(address: String): List<ByteArray> {
        check(hasRequiredPermission()) { "Missing Bluetooth connect permission" }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        check(adapter?.isEnabled == true) { "Bluetooth is disabled" }
        val device = adapter.getRemoteDevice(address)

        val connected = CompletableDeferred<Unit>()
        val servicesReady = CompletableDeferred<Unit>()
        val notifyReady = CompletableDeferred<Unit>()
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        var writeChar: BluetoothGattCharacteristic? = null
        var gattRef: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connected.completeExceptionally(IllegalStateException("GATT status $status"))
                    servicesReady.completeExceptionally(IllegalStateException("GATT status $status"))
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected.complete(Unit)
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("Disconnected"))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    servicesReady.completeExceptionally(IllegalStateException("Service discovery failed: $status"))
                    return
                }
                val service = gatt.getService(serviceUuid)
                writeChar = service?.getCharacteristic(writeUuid)
                if (service == null || writeChar == null || service.getCharacteristic(notifyUuid) == null) {
                    servicesReady.completeExceptionally(IllegalStateException("J2208 fff0/fff6/fff7 profile not found"))
                } else {
                    servicesReady.complete(Unit)
                }
            }

            @Deprecated("Kept for Android 12 and lower callbacks")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == notifyUuid) inbox.trySend(characteristic.value.copyOf())
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == notifyUuid) inbox.trySend(value.copyOf())
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == cccdUuid && status == BluetoothGatt.GATT_SUCCESS) {
                    notifyReady.complete(Unit)
                } else if (descriptor.uuid == cccdUuid) {
                    notifyReady.completeExceptionally(IllegalStateException("Notify descriptor write failed: $status"))
                }
            }
        }

        try {
            gattRef = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
            withTimeout(20_000) { connected.await() }
            withTimeout(20_000) { servicesReady.await() }
            enableNotifications(gattRef!!, notifyReady)
            withTimeout(10_000) { notifyReady.await() }
            return collectHrvPackets(gattRef!!, writeChar!!, inbox)
        } finally {
            inbox.close()
            runCatching { gattRef?.disconnect() }
            runCatching { gattRef?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, notifyReady: CompletableDeferred<Unit>) {
        val notifyChar = gatt.getService(serviceUuid).getCharacteristic(notifyUuid)
        check(gatt.setCharacteristicNotification(notifyChar, true)) { "setCharacteristicNotification failed" }
        val descriptor = notifyChar.getDescriptor(cccdUuid)
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor) && !notifyReady.isCompleted) {
            notifyReady.completeExceptionally(IllegalStateException("writeDescriptor returned false"))
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectHrvPackets(
        gatt: BluetoothGatt,
        writeChar: BluetoothGattCharacteristic,
        inbox: Channel<ByteArray>,
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var lastRx = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + 120_000

        write(gatt, writeChar, J2208Protocol.hrvHistoryStartFrame())

        while (System.currentTimeMillis() < deadline) {
            val packet = runCatching { withTimeout(300) { inbox.receive() } }.getOrNull()
            if (packet == null) {
                if (System.currentTimeMillis() - lastRx > 1_500) {
                    write(gatt, writeChar, J2208Protocol.hrvHistoryContinueFrame())
                    lastRx = System.currentTimeMillis()
                }
                continue
            }
            if (packet.firstOrNull()?.toUByte()?.toInt() != J2208Protocol.OP_HRV_HISTORY) continue
            lastRx = System.currentTimeMillis()
            if (J2208Protocol.isHrvHistoryTerminator(packet)) {
                val stripped = J2208Protocol.stripHrvHistoryTerminator(packet)
                if (stripped.isNotEmpty()) packets += stripped
                return packets
            }
            packets += packet
        }
        throw IllegalStateException("Timed out waiting for 0x56 history terminator")
    }

    @SuppressLint("MissingPermission")
    private fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, packet: ByteArray) {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = packet
        check(gatt.writeCharacteristic(characteristic)) { "writeCharacteristic returned false" }
    }
}
