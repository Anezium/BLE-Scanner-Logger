package com.anezium.blescanner.capture

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Identifiant UTC partagé par tous les fichiers d'une même session. */
object SessionId {
    private val format = DateTimeFormatter
        .ofPattern("yyyyMMdd_HHmmss_SSS")
        .withZone(ZoneOffset.UTC)

    fun now(): String = format.format(Instant.now())
}
