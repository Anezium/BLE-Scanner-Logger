package com.anezium.blescanner.capture

/**
 * Sources de données qu'une session peut enregistrer. Une session en active
 * une ou plusieurs; chaque source garde sa propre cadence et son propre
 * timestamp dans les CSV.
 */
enum class CaptureSource(val key: String, val label: String) {
    BLE("ble", "Bluetooth"),
    CELL("cell", "Réseau mobile"),
    IMU("imu", "IMU");

    companion object {
        fun fromKey(key: String): CaptureSource? = entries.firstOrNull { it.key == key }
    }
}

/** Libellé lisible d'une sélection, dans l'ordre de l'enum: « Bluetooth + IMU ». */
fun Set<CaptureSource>.label(): String =
    CaptureSource.entries.filter { it in this }.joinToString(" + ") { it.label }

/** Clés stables pour la persistance et les métadonnées CSV: « ble,imu ». */
fun Set<CaptureSource>.keys(): String =
    CaptureSource.entries.filter { it in this }.joinToString(",") { it.key }
