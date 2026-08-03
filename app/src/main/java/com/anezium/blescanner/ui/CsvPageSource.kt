package com.anezium.blescanner.ui

import java.io.File
import java.util.Locale

/** Page de lecture CSV: [PAGE_SIZE] lignes formatées au maximum. */
class CsvPage(val rows: List<String>)

class ParserFilters(
    val iBeacon: Boolean,
    val dati: Boolean,
    val eddystone: Boolean,
    val macAddress: String
) {
    fun parserEnabled(): Boolean = iBeacon || dati || eddystone
}

/**
 * Lecture, filtrage et mise en forme des CSV pour le lecteur.
 * Logique reprise telle quelle de l'implémentation d'origine.
 */
object CsvPageSource {

    const val PAGE_SIZE = 200

    fun readPage(file: File, page: Int, filters: ParserFilters): CsvPage {
        val offset = page * PAGE_SIZE
        val rows = mutableListOf<String>()
        var matched = 0
        file.bufferedReader().use { reader ->
            val header = parseCsvLine(reader.readLine().orEmpty())
            val indexes = header.withIndex().associate { it.value to it.index }
            var line = reader.readLine()
            while (line != null) {
                val values = parseCsvLine(line)
                if (matchesParserFilters(values, indexes, filters)) {
                    if (matched >= offset && rows.size < PAGE_SIZE) {
                        rows += formatCsvPreview(values, indexes)
                    }
                    matched += 1
                    if (rows.size >= PAGE_SIZE && matched > offset + PAGE_SIZE) break
                }
                line = reader.readLine()
            }
        }
        return CsvPage(rows)
    }

    private fun matchesParserFilters(
        values: List<String>,
        indexes: Map<String, Int>,
        filters: ParserFilters
    ): Boolean {
        if (!matchesMacFilter(values, indexes, filters.macAddress)) return false
        if (!filters.parserEnabled()) return true
        val classification = classifyCsvRow(values, indexes)
        return (filters.iBeacon && classification == "iBeacon") ||
            (filters.dati && classification == "DATI") ||
            (filters.eddystone && classification.startsWith("Eddystone"))
    }

    private fun matchesMacFilter(
        values: List<String>,
        indexes: Map<String, Int>,
        query: String
    ): Boolean {
        val normalizedQuery = normalizeMacFilter(query)
        if (normalizedQuery.isBlank()) return true
        val normalizedAddress = normalizeMacFilter(value(values, indexes, "address"))
        return normalizedAddress.contains(normalizedQuery)
    }

    private fun normalizeMacFilter(value: String): String =
        value.filter { it.isLetterOrDigit() }.uppercase(Locale.US)

    fun formatCsvPreview(values: List<String>, indexes: Map<String, Int>): String {
        if (indexes.containsKey("rat") && indexes.containsKey("rsrp_dbm") && indexes.containsKey("source")) {
            val time = value(values, indexes, "wall_time_local")
                .ifBlank { value(values, indexes, "wall_time_iso") }
            val rat = value(values, indexes, "rat")
            val source = value(values, indexes, "source")
            val registered = value(values, indexes, "registered")
            val rsrp = value(values, indexes, "rsrp_dbm")
            val rsrq = value(values, indexes, "rsrq_db")
            val sinr = value(values, indexes, "sinr_db")
            val pci = value(values, indexes, "pci")
            val ci = value(values, indexes, "ci").ifBlank { value(values, indexes, "nci") }
            val arfcn = value(values, indexes, "arfcn")
            val id = listOfNotNull(
                ci.ifBlank { null }?.let { "ci=$it" },
                pci.ifBlank { null }?.let { "pci=$it" },
                arfcn.ifBlank { null }?.let { "arfcn=$it" }
            ).joinToString(" ")
            return "$time  $rat  registered=$registered  RSRP $rsrp dBm  RSRQ $rsrq  SINR $sinr\n$source  $id"
        }
        val type = classifyCsvRow(values, indexes)
        val time = value(values, indexes, "wall_time_local")
            .ifBlank { value(values, indexes, "wall_time_iso") }
        val address = value(values, indexes, "address")
        val rssi = value(values, indexes, "rssi_dbm")
        val name = value(values, indexes, "device_name")
        val payload = value(values, indexes, "raw_scan_record_hex")
        val label = when (type) {
            "DATI" -> "DATI room=${value(values, indexes, "dati_room")} bat=${value(values, indexes, "dati_autonomy")} temp=${value(values, indexes, "dati_temperature_c")} flags=${value(values, indexes, "dati_flags")}"
            "iBeacon" -> "iBeacon uuid=${value(values, indexes, "ibeacon_uuid")} major=${value(values, indexes, "ibeacon_major")} minor=${value(values, indexes, "ibeacon_minor")}"
            "Eddystone UID" -> "Eddystone UID ns=${value(values, indexes, "eddystone_uid_namespace")} inst=${value(values, indexes, "eddystone_uid_instance")}"
            "Eddystone TLM" -> "Eddystone TLM batt=${value(values, indexes, "eddystone_tlm_battery_mv")} temp=${value(values, indexes, "eddystone_tlm_temperature_c")}"
            else -> "BLE non parse"
        }
        val namePart = if (name.isBlank()) "" else " name=$name"
        return "$time  $address  RSSI $rssi dBm$namePart\n$label\npayload=$payload"
    }

    private fun classifyCsvRow(values: List<String>, indexes: Map<String, Int>): String {
        val dati = value(values, indexes, "dati_room").isNotBlank()
        val eddystoneUid = value(values, indexes, "eddystone_uid_namespace").isNotBlank()
        val eddystoneTlm = value(values, indexes, "eddystone_tlm_battery_mv").isNotBlank()
        val iBeacon = value(values, indexes, "ibeacon_uuid").isNotBlank()
        return when {
            dati -> "DATI"
            eddystoneUid -> "Eddystone UID"
            eddystoneTlm -> "Eddystone TLM"
            iBeacon -> "iBeacon"
            else -> "BLE"
        }
    }

    private fun value(values: List<String>, indexes: Map<String, Int>, key: String): String {
        val index = indexes[key] ?: return ""
        return values.getOrNull(index).orEmpty()
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i += 1
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i += 1
        }
        out += current.toString()
        return out
    }
}
