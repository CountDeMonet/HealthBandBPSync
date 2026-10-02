package dev.erban.humebridge.ble

data class ScannedBand(
    val address: String,
    val name: String?,
    val rssi: Int,
    val advertisesJ2208Service: Boolean,
)
