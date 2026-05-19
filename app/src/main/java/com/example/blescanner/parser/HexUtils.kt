package com.example.blescanner.parser

object HexUtils {
    private val hex = "0123456789ABCDEF".toCharArray()

    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, value ->
            val v = value.toInt() and 0xFF
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(out)
    }
}
