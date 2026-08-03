package com.anezium.blescanner.ui

import android.app.Activity
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Navigation arrière Android 13+ (`OnBackInvokedCallback`).
 * Isolé dans sa propre classe: n'est chargé que derrière un garde
 * `Build.VERSION.SDK_INT >= TIRAMISU` côté appelant.
 */
object BackNavigationApi33 {

    fun register(activity: Activity, onBack: () -> Unit): Any {
        val callback = OnBackInvokedCallback { onBack() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
        return callback
    }

    fun unregister(activity: Activity, callback: Any?) {
        (callback as? OnBackInvokedCallback)?.let {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
    }
}
