package com.anezium.blescanner.data

object Csv {
    fun row(values: List<Any?>): String = values.joinToString(",") { escape(it?.toString().orEmpty()) }

    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
