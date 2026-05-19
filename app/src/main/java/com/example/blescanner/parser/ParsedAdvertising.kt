package com.example.blescanner.parser

data class ParsedAdvertising(
    val beaconKey: String,
    val manufacturerData: String = "",
    val serviceUuids: String = "",
    val serviceData: String = "",
    val iBeaconUuid: String = "",
    val iBeaconMajor: Int? = null,
    val iBeaconMinor: Int? = null,
    val iBeaconTxPower: Int? = null,
    val eddystoneUidNamespace: String = "",
    val eddystoneUidInstance: String = "",
    val eddystoneUidTxPower: Int? = null,
    val eddystoneTlmBatteryMv: Int? = null,
    val eddystoneTlmTemperatureC: Double? = null,
    val eddystoneTlmAdvCount: Long? = null,
    val eddystoneTlmSecCount: Long? = null,
    val datiRoom: String = "",
    val datiAutonomy: Int? = null,
    val datiTemperatureC: Int? = null,
    val datiFlags: Int? = null,
    val datiFirmwareVersion: String = ""
)
