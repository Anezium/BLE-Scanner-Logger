package com.anezium.blescanner.ui

import android.os.Handler

/**
 * Source du flux live: ajout en tête, taille bornée, compteurs et rendu
 * throttlé.
 *
 * Alimenté depuis les threads des services (`liveListener`) et lu sur le thread
 * principal. [visible] est la liste que consulte l'adapter: elle n'est remplie
 * que sur le thread principal, juste avant `notifyDataSetChanged`.
 */
class LiveFeedBuffer(
    private val handler: Handler,
    private val maxLines: Int,
    private val throttleMs: Long,
    private val isActive: () -> Boolean,
    private val onFlush: () -> Unit
) {
    private val lock = Any()
    private val recent = ArrayDeque<LiveLine>()
    private val keys = linkedSetOf<String>()
    private var frames = 0

    @Volatile
    private var flushScheduled = false

    val visible = ArrayList<LiveLine>(maxLines)

    private val flushTask = Runnable {
        flushScheduled = false
        if (isActive()) {
            copyToVisible()
            onFlush()
        }
    }

    /** Comptabilise une trame reçue et son émetteur (adresse BLE ou clé cellule). */
    fun countFrame(key: String) {
        synchronized(lock) {
            frames += 1
            if (key.isNotBlank()) keys += key
        }
    }

    fun add(line: String, category: String) {
        synchronized(lock) {
            recent.addFirst(LiveLine(line, category))
            while (recent.size > maxLines) recent.removeLast()
        }
        scheduleFlush()
    }

    /** Vide l'affichage et les compteurs (les CSV ne sont pas touchés). */
    fun clear() {
        synchronized(lock) {
            frames = 0
            keys.clear()
            recent.clear()
        }
        copyToVisible()
    }

    /** Thread principal uniquement. */
    fun copyToVisible() {
        synchronized(lock) {
            visible.clear()
            visible.addAll(recent)
        }
    }

    fun frameCount(): Int = synchronized(lock) { frames }

    fun deviceCount(): Int = synchronized(lock) { keys.size }

    fun cancelPendingFlush() {
        handler.removeCallbacks(flushTask)
        flushScheduled = false
    }

    private fun scheduleFlush() {
        if (!isActive()) return
        if (flushScheduled) return
        flushScheduled = true
        handler.postDelayed(flushTask, throttleMs)
    }
}
