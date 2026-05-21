package com.anezium.blescanner.data

data class ScanPreview(
    val address: String,
    val rssi: Int,
    val deviceName: String,
    val beaconKey: String,
    val payloadHex: String,
    val parserLabel: String,
    val category: String
) {
    fun displayLine(): String {
        val name = if (deviceName.isBlank()) "" else " $deviceName"
        val payload = payloadHex.take(48)
        return "$address$name  RSSI ${rssi} dBm  $parserLabel  $payload"
    }
}
