package com.anezium.blescanner.data

data class CellularPreview(
    val key: String,
    val rat: String,
    val source: String,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val sinrDb: Int?,
    val registered: Boolean?
) {
    fun displayLine(): String {
        val serving = when (registered) {
            true -> "serving"
            false -> "neighbor"
            null -> "primary"
        }
        val power = rsrpDbm?.let { "RSRP $it dBm" } ?: "RSRP n/a"
        val quality = listOfNotNull(
            rsrqDb?.let { "RSRQ $it" },
            sinrDb?.let { "SINR $it" }
        ).joinToString("  ")
        return "$rat $serving  $power  $quality  $source  $key"
    }
}
