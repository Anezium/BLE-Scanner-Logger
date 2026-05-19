package com.example.blescanner.data

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

class RotatingCsvWriter(
    private val directory: File,
    private val baseName: String,
    private val header: List<String>,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxRows: Int = DEFAULT_MAX_ROWS
) {
    private var part = 1
    private var rowsInPart = 0
    private var bytesInPart = 0L
    private var rowsSinceFlush = 0
    private var lastFlushMs = System.currentTimeMillis()
    private var writer: PrintWriter? = null
    var currentFile: File = fileForPart(part)
        private set

    init {
        directory.mkdirs()
        openPart()
    }

    @Synchronized
    fun write(values: List<Any?>) {
        rotateIfNeeded()
        val row = Csv.row(values)
        writer?.println(row)
        rowsInPart += 1
        rowsSinceFlush += 1
        bytesInPart += row.toByteArray(StandardCharsets.UTF_8).size + 1L
        flushIfNeeded()
    }

    @Synchronized
    fun flush() {
        writer?.flush()
    }

    @Synchronized
    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    private fun rotateIfNeeded() {
        if (rowsInPart < maxRows && bytesInPart < maxBytes) return
        writer?.flush()
        writer?.close()
        part += 1
        rowsInPart = 0
        bytesInPart = 0L
        rowsSinceFlush = 0
        openPart()
    }

    private fun openPart() {
        currentFile = fileForPart(part)
        writer = PrintWriter(FileOutputStream(currentFile, true), false)
        bytesInPart = currentFile.length()
        if (currentFile.length() == 0L) {
            val headerRow = Csv.row(header)
            writer?.println(headerRow)
            bytesInPart += headerRow.toByteArray(StandardCharsets.UTF_8).size + 1L
            writer?.flush()
            lastFlushMs = System.currentTimeMillis()
        }
    }

    private fun flushIfNeeded() {
        val now = System.currentTimeMillis()
        if (rowsSinceFlush < FLUSH_EVERY_ROWS && now - lastFlushMs < FLUSH_EVERY_MS) return
        writer?.flush()
        rowsSinceFlush = 0
        lastFlushMs = now
    }

    private fun fileForPart(part: Int): File =
        File(directory, "${baseName}_part${part.toString().padStart(3, '0')}.csv")

    companion object {
        private const val DEFAULT_MAX_BYTES = 10L * 1024L * 1024L
        private const val DEFAULT_MAX_ROWS = 100_000
        private const val FLUSH_EVERY_ROWS = 100
        private const val FLUSH_EVERY_MS = 1_000L
    }
}
