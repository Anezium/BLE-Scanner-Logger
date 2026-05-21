package com.anezium.blescanner.data

import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Environment
import com.anezium.blescanner.parser.BeaconParser
import com.anezium.blescanner.parser.HexUtils
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class BleCsvLogger(
    context: Context
) {
    val directory: File = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ble_logs")
    val sessionStamp: String
    private val rawWriter: RotatingCsvWriter

    init {
        directory.mkdirs()
        sessionStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        rawWriter = RotatingCsvWriter(
            directory = directory,
            baseName = "ble_scan_$sessionStamp",
            header = RAW_HEADER
        )
    }

    fun log(result: ScanResult): ScanPreview {
        val nowMs = System.currentTimeMillis()
        val record = result.scanRecord
        val parsed = BeaconParser.parse(record, result.device.address)
        val payloadHex = HexUtils.bytesToHex(record?.bytes)
        rawWriter.write(
            listOf(
                iso(nowMs),
                localIso(nowMs),
                nowMs,
                result.timestampNanos,
                result.device.address,
                result.rssi,
                payloadHex,
                record?.deviceName.orEmpty(),
                parsed.manufacturerData,
                parsed.serviceUuids,
                parsed.serviceData,
                parsed.iBeaconUuid,
                parsed.iBeaconMajor,
                parsed.iBeaconMinor,
                parsed.iBeaconTxPower,
                parsed.eddystoneUidNamespace,
                parsed.eddystoneUidInstance,
                parsed.eddystoneUidTxPower,
                parsed.eddystoneTlmBatteryMv,
                parsed.eddystoneTlmTemperatureC,
                parsed.eddystoneTlmAdvCount,
                parsed.eddystoneTlmSecCount,
                parsed.datiRoom,
                parsed.datiAutonomy,
                parsed.datiTemperatureC,
                parsed.datiFlags,
                parsed.datiFirmwareVersion
            )
        )
        return ScanPreview(
            address = result.device.address,
            rssi = result.rssi,
            deviceName = record?.deviceName.orEmpty(),
            beaconKey = parsed.beaconKey,
            payloadHex = payloadHex,
            parserLabel = parserLabel(parsed),
            category = parserCategory(parsed)
        )
    }

    fun close() {
        rawWriter.flush()
        rawWriter.close()
    }

    private fun iso(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))

    private fun localIso(epochMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private fun parserLabel(parsed: com.anezium.blescanner.parser.ParsedAdvertising): String =
        when {
            parsed.datiRoom.isNotBlank() -> "DATI ${parsed.datiRoom}"
            parsed.iBeaconUuid.isNotBlank() -> "iBeacon ${parsed.iBeaconMajor}/${parsed.iBeaconMinor}"
            parsed.eddystoneUidNamespace.isNotBlank() -> "Eddystone UID"
            parsed.eddystoneTlmBatteryMv != null -> "Eddystone TLM"
            else -> parsed.beaconKey
        }

    private fun parserCategory(parsed: com.anezium.blescanner.parser.ParsedAdvertising): String =
        when {
            parsed.datiRoom.isNotBlank() -> "dati"
            parsed.iBeaconUuid.isNotBlank() -> "ibeacon"
            parsed.eddystoneUidNamespace.isNotBlank() -> "eddystone_uid"
            parsed.eddystoneTlmBatteryMv != null -> "eddystone_tlm"
            else -> "ble"
        }

    companion object {
        private val RAW_HEADER = listOf(
            "wall_time_iso",
            "wall_time_local",
            "wall_time_ms_epoch",
            "timestamp_nanos_android",
            "address",
            "rssi_dbm",
            "raw_scan_record_hex",
            "device_name",
            "manufacturer_data",
            "service_uuids",
            "service_data",
            "ibeacon_uuid",
            "ibeacon_major",
            "ibeacon_minor",
            "ibeacon_tx_power",
            "eddystone_uid_namespace",
            "eddystone_uid_instance",
            "eddystone_uid_tx_power",
            "eddystone_tlm_battery_mv",
            "eddystone_tlm_temperature_c",
            "eddystone_tlm_adv_count",
            "eddystone_tlm_sec_count",
            "dati_room",
            "dati_autonomy",
            "dati_temperature_c",
            "dati_flags",
            "dati_firmware_version"
        )
    }
}
