package com.anezium.blescanner.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.telephony.CellIdentity
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CellularCsvLogger(
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
            baseName = "cell_scan_$sessionStamp",
            header = RAW_HEADER
        )
    }

    fun logSignalStrength(source: String, subId: Int, signalStrength: SignalStrength): CellularPreview? {
        val strength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signalStrength.cellSignalStrengths
                .filter { it.dbm != CellInfo.UNAVAILABLE }
                .sortedBy { if (it is CellSignalStrengthNr) 0 else 1 }
                .firstOrNull()
        } else {
            null
        } ?: return null

        val sample = strengthSample(strength)
        writeRow(source, subId, sample.rat, null, null, null, null, sample, signalStrength.toString())
        return CellularPreview(
            key = "sub=$subId",
            rat = sample.rat,
            source = source,
            rsrpDbm = sample.rsrpDbm,
            rsrqDb = sample.rsrqDb,
            sinrDb = sample.sinrDb,
            registered = null
        )
    }

    fun logCellInfo(source: String, subId: Int, cells: List<CellInfo>): List<CellularPreview> =
        cells.mapNotNull { cell ->
            val sample = cellSample(cell) ?: return@mapNotNull null
            val identity = identitySample(cell.cellIdentity)
            writeRow(
                source = source,
                subId = subId,
                rat = sample.rat,
                registered = cell.isRegistered,
                connectionStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cell.cellConnectionStatus else null,
                timestampNanos = cell.timeStamp,
                identity = identity,
                signal = sample,
                raw = cell.toString()
            )
            CellularPreview(
                key = identity.key.ifBlank { "sub=$subId" },
                rat = sample.rat,
                source = source,
                rsrpDbm = sample.rsrpDbm,
                rsrqDb = sample.rsrqDb,
                sinrDb = sample.sinrDb,
                registered = cell.isRegistered
            )
        }

    fun close() {
        rawWriter.flush()
        rawWriter.close()
    }

    private fun writeRow(
        source: String,
        subId: Int,
        rat: String,
        registered: Boolean?,
        connectionStatus: Int?,
        timestampNanos: Long?,
        identity: IdentitySample?,
        signal: SignalSample,
        raw: String
    ) {
        val nowMs = System.currentTimeMillis()
        rawWriter.write(
            listOf(
                iso(nowMs),
                localIso(nowMs),
                nowMs,
                android.os.SystemClock.elapsedRealtimeNanos(),
                source,
                subId,
                rat,
                registered,
                connectionStatus,
                timestampNanos,
                identity?.mcc.orEmpty(),
                identity?.mnc.orEmpty(),
                identity?.ci.orEmpty(),
                identity?.nci.orEmpty(),
                identity?.pci.orEmpty(),
                identity?.tac.orEmpty(),
                identity?.arfcn.orEmpty(),
                identity?.bands.orEmpty(),
                identity?.bandwidthKhz.orEmpty(),
                signal.rsrpDbm,
                signal.rsrqDb,
                signal.sinrDb,
                signal.rssiDbm,
                signal.rssnrDb,
                signal.cqi,
                signal.timingAdvance,
                signal.level,
                signal.asuLevel,
                signal.dbm,
                raw
            )
        )
    }

    private fun cellSample(cell: CellInfo): SignalSample? =
        when (cell) {
            is CellInfoLte -> strengthSample(cell.cellSignalStrength)
            is CellInfoNr -> strengthSample(cell.cellSignalStrength)
            else -> null
        }

    private fun strengthSample(strength: CellSignalStrength): SignalSample =
        when (strength) {
            is CellSignalStrengthLte -> SignalSample(
                rat = "LTE",
                rsrpDbm = unavailableToNull(strength.rsrp),
                rsrqDb = unavailableToNull(strength.rsrq),
                sinrDb = unavailableToNull(strength.rssnr),
                rssiDbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) unavailableToNull(strength.rssi) else null,
                rssnrDb = unavailableToNull(strength.rssnr),
                cqi = unavailableToNull(strength.cqi),
                timingAdvance = unavailableToNull(strength.timingAdvance),
                level = strength.level,
                asuLevel = strength.asuLevel,
                dbm = strength.dbm
            )
            is CellSignalStrengthNr -> SignalSample(
                rat = "NR",
                rsrpDbm = unavailableToNull(strength.ssRsrp),
                rsrqDb = unavailableToNull(strength.ssRsrq),
                sinrDb = unavailableToNull(strength.ssSinr),
                rssiDbm = null,
                rssnrDb = null,
                cqi = null,
                timingAdvance = null,
                level = strength.level,
                asuLevel = strength.asuLevel,
                dbm = strength.dbm
            )
            else -> SignalSample(
                rat = strength.javaClass.simpleName.removePrefix("CellSignalStrength").uppercase(),
                rsrpDbm = null,
                rsrqDb = null,
                sinrDb = null,
                rssiDbm = null,
                rssnrDb = null,
                cqi = null,
                timingAdvance = null,
                level = strength.level,
                asuLevel = strength.asuLevel,
                dbm = strength.dbm
            )
        }

    private fun identitySample(identity: CellIdentity): IdentitySample =
        when (identity) {
            is CellIdentityLte -> IdentitySample(
                mcc = identity.mccString.orEmpty(),
                mnc = identity.mncString.orEmpty(),
                ci = unavailableToBlank(identity.ci),
                nci = "",
                pci = unavailableToBlank(identity.pci),
                tac = unavailableToBlank(identity.tac),
                arfcn = unavailableToBlank(identity.earfcn),
                bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) identity.bands.joinToString("|") else "",
                bandwidthKhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) unavailableToBlank(identity.bandwidth) else ""
            )
            is CellIdentityNr -> IdentitySample(
                mcc = identity.mccString.orEmpty(),
                mnc = identity.mncString.orEmpty(),
                ci = "",
                nci = unavailableToBlank(identity.nci),
                pci = unavailableToBlank(identity.pci),
                tac = unavailableToBlank(identity.tac),
                arfcn = unavailableToBlank(identity.nrarfcn),
                bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) identity.bands.joinToString("|") else "",
                bandwidthKhz = ""
            )
            else -> IdentitySample("", "", "", "", "", "", "", "", "")
        }

    private fun unavailableToNull(value: Int): Int? =
        if (value == CellInfo.UNAVAILABLE || value == Int.MAX_VALUE) null else value

    private fun unavailableToBlank(value: Int): String =
        unavailableToNull(value)?.toString().orEmpty()

    private fun unavailableToBlank(value: Long): String =
        if (value == Long.MAX_VALUE) "" else value.toString()

    private fun iso(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))

    private fun localIso(epochMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private data class SignalSample(
        val rat: String,
        val rsrpDbm: Int?,
        val rsrqDb: Int?,
        val sinrDb: Int?,
        val rssiDbm: Int?,
        val rssnrDb: Int?,
        val cqi: Int?,
        val timingAdvance: Int?,
        val level: Int,
        val asuLevel: Int,
        val dbm: Int
    )

    private data class IdentitySample(
        val mcc: String,
        val mnc: String,
        val ci: String,
        val nci: String,
        val pci: String,
        val tac: String,
        val arfcn: String,
        val bands: String,
        val bandwidthKhz: String
    ) {
        val key: String
            get() = listOfNotNull(
                ci.ifBlank { null }?.let { "ci=$it" },
                nci.ifBlank { null }?.let { "nci=$it" },
                pci.ifBlank { null }?.let { "pci=$it" },
                arfcn.ifBlank { null }?.let { "arfcn=$it" }
            ).joinToString(" ")
    }

    companion object {
        private val RAW_HEADER = listOf(
            "wall_time_iso",
            "wall_time_local",
            "wall_time_ms_epoch",
            "elapsed_realtime_nanos",
            "source",
            "subscription_id",
            "rat",
            "registered",
            "connection_status",
            "timestamp_nanos_android",
            "mcc",
            "mnc",
            "ci",
            "nci",
            "pci",
            "tac",
            "arfcn",
            "bands",
            "bandwidth_khz",
            "rsrp_dbm",
            "rsrq_db",
            "sinr_db",
            "rssi_dbm",
            "rssnr_db",
            "cqi",
            "timing_advance",
            "level",
            "asu_level",
            "dbm",
            "raw_android"
        )
    }
}
