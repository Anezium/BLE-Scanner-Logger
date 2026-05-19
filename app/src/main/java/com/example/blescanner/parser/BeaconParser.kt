package com.example.blescanner.parser

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.Locale

object BeaconParser {
    private val eddystoneUuid: ParcelUuid = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")

    fun parse(record: ScanRecord?, address: String): ParsedAdvertising {
        if (record == null) return ParsedAdvertising(beaconKey = address)

        val manufacturerText = manufacturerDataToText(record)
        val serviceUuidText = record.serviceUuids.orEmpty().joinToString("|") { it.uuid.toString() }
        val serviceDataText = record.serviceData.orEmpty().entries.joinToString("|") {
            "${it.key.uuid}=${HexUtils.bytesToHex(it.value)}"
        }

        var parsed = ParsedAdvertising(
            beaconKey = address,
            manufacturerData = manufacturerText,
            serviceUuids = serviceUuidText,
            serviceData = serviceDataText
        )

        parseIBeacon(record)?.let {
            parsed = parsed.copy(
                beaconKey = "ibeacon:${it.uuid}:${it.major}:${it.minor}",
                iBeaconUuid = it.uuid,
                iBeaconMajor = it.major,
                iBeaconMinor = it.minor,
                iBeaconTxPower = it.txPower
            )
        }

        parseEddystoneUid(record)?.let {
            parsed = parsed.copy(
                beaconKey = "eddystone_uid:${it.namespace}:${it.instance}",
                eddystoneUidNamespace = it.namespace,
                eddystoneUidInstance = it.instance,
                eddystoneUidTxPower = it.txPower
            )
        }

        parseEddystoneTlm(record)?.let {
            parsed = parsed.copy(
                beaconKey = if (parsed.beaconKey == address) "eddystone_tlm:$address" else parsed.beaconKey,
                eddystoneTlmBatteryMv = it.batteryMv,
                eddystoneTlmTemperatureC = it.temperatureC,
                eddystoneTlmAdvCount = it.advCount,
                eddystoneTlmSecCount = it.secCount
            )
        }

        parseDatiInvirtus(record)?.let {
            parsed = parsed.copy(
                beaconKey = "dati:${it.room}:${it.major}:${it.minor}",
                datiRoom = it.room,
                datiAutonomy = it.autonomy,
                datiTemperatureC = it.temperatureC,
                datiFlags = it.flags,
                datiFirmwareVersion = it.firmwareVersion
            )
        }

        return parsed
    }

    private fun manufacturerDataToText(record: ScanRecord): String {
        val data = record.manufacturerSpecificData
        return (0 until data.size()).joinToString("|") { i ->
            val id = data.keyAt(i)
            "0x${id.toString(16).uppercase(Locale.US).padStart(4, '0')}=${HexUtils.bytesToHex(data.valueAt(i))}"
        }
    }

    private fun parseIBeacon(record: ScanRecord): IBeacon? {
        val data = manufacturerPayloads(record).firstOrNull {
            it.data.size >= 23 &&
                (it.data[0].toInt() and 0xFF) == 0x02 &&
                (it.data[1].toInt() and 0xFF) == 0x15
        }?.data ?: return null
        if (data.size < 23 || data[0].toInt() != 0x02 || data[1].toInt() != 0x15) return null
        val uuidText = "%02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X%02X%02X%02X%02X".format(
            Locale.US,
            data[2].toInt() and 0xFF, data[3].toInt() and 0xFF, data[4].toInt() and 0xFF, data[5].toInt() and 0xFF,
            data[6].toInt() and 0xFF, data[7].toInt() and 0xFF,
            data[8].toInt() and 0xFF, data[9].toInt() and 0xFF,
            data[10].toInt() and 0xFF, data[11].toInt() and 0xFF,
            data[12].toInt() and 0xFF, data[13].toInt() and 0xFF, data[14].toInt() and 0xFF, data[15].toInt() and 0xFF,
            data[16].toInt() and 0xFF, data[17].toInt() and 0xFF
        ).lowercase(Locale.US)
        return IBeacon(
            uuid = uuidText,
            major = u16(data, 18),
            minor = u16(data, 20),
            txPower = data[22].toInt()
        )
    }

    private fun parseEddystoneUid(record: ScanRecord): EddystoneUid? {
        val data = record.getServiceData(eddystoneUuid) ?: return null
        if (data.size < 18 || (data[0].toInt() and 0xFF) != 0x00) return null
        return EddystoneUid(
            txPower = data[1].toInt(),
            namespace = HexUtils.bytesToHex(data.copyOfRange(2, 12)),
            instance = HexUtils.bytesToHex(data.copyOfRange(12, 18))
        )
    }

    private fun parseEddystoneTlm(record: ScanRecord): EddystoneTlm? {
        val data = record.getServiceData(eddystoneUuid) ?: return null
        if (data.size < 14 || (data[0].toInt() and 0xFF) != 0x20) return null
        val rawTemp = ((data[4].toInt() shl 8) or (data[5].toInt() and 0xFF)).toShort().toInt()
        return EddystoneTlm(
            batteryMv = u16(data, 2),
            temperatureC = rawTemp / 256.0,
            advCount = u32(data, 6),
            secCount = u32(data, 10)
        )
    }

    private fun parseDatiInvirtus(record: ScanRecord): DatiInvirtus? {
        val payload = manufacturerPayloads(record).firstOrNull {
            it.companyId == 0xFFFF &&
                it.data.size >= 23 &&
                (it.data[0].toInt() and 0xFF) == 0x02 &&
                (it.data[1].toInt() and 0xFF) == 0x15 &&
                it.data.copyOfRange(2, 14).all(::isPrintableAscii)
        }?.data ?: return null

        val room = String(payload.copyOfRange(2, 14), StandardCharsets.US_ASCII).trim()
        return DatiInvirtus(
            room = room,
            autonomy = payload[14].toInt() and 0xFF,
            temperatureC = payload[15].toInt(),
            flags = payload[16].toInt() and 0xFF,
            firmwareVersion = (payload[17].toInt() and 0xFF).toString(),
            major = u16(payload, 18),
            minor = u16(payload, 20),
            txPower = payload[22].toInt()
        )
    }

    private fun manufacturerPayloads(record: ScanRecord): List<ManufacturerPayload> {
        val data = record.manufacturerSpecificData
        return (0 until data.size()).map { i ->
            ManufacturerPayload(data.keyAt(i), data.valueAt(i))
        }
    }

    private fun isPrintableAscii(byte: Byte): Boolean {
        val v = byte.toInt() and 0xFF
        return v in 0x20..0x7E
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private data class IBeacon(val uuid: String, val major: Int, val minor: Int, val txPower: Int)
    private data class EddystoneUid(val namespace: String, val instance: String, val txPower: Int)
    private data class EddystoneTlm(val batteryMv: Int, val temperatureC: Double, val advCount: Long, val secCount: Long)
    private data class DatiInvirtus(
        val room: String,
        val autonomy: Int,
        val temperatureC: Int,
        val flags: Int,
        val firmwareVersion: String,
        val major: Int,
        val minor: Int,
        val txPower: Int
    )
    private data class ManufacturerPayload(val companyId: Int, val data: ByteArray)
}
