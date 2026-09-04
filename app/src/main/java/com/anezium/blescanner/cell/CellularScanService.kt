package com.anezium.blescanner.cell

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anezium.blescanner.capture.SessionId
import com.anezium.blescanner.data.CellularCsvLogger

class CellularScanService : Service() {
    private var logger: CellularCsvLogger? = null
    private var telephony: TelephonyManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var oemHandlerThread: HandlerThread? = null
    private var oemHandler: Handler? = null
    private val oemSignalReader = OemRilHookSignalReader()
    private var callbackApi31: Any? = null
    private var listenerLegacy: PhoneStateListener? = null
    private var oemHookStatusPublished = false

    private val refreshCellInfo = object : Runnable {
        override fun run() {
            requestFreshCellInfo()
            mainHandler.postDelayed(this, CELL_INFO_REFRESH_MS)
        }
    }

    private val refreshOemSignalStrength = object : Runnable {
        override fun run() {
            val startedAt = SystemClock.elapsedRealtime()
            requestOemSignalStrength()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            oemHandler?.postDelayed(this, (OEM_SIGNAL_REFRESH_MS - elapsed).coerceAtLeast(0L))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> stopScanning()
            else -> startScanning(
                intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: SessionId.now()
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopScanning()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(sessionId: String) {
        if (!hasCellPermissions()) {
            publishStatus("Permissions cellulaire/localisation manquantes")
            stopSelf()
            return
        }
        if (scanStartedAtElapsedMs == 0L) scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
        startForeground(NOTIFICATION_ID, notification("Capture réseau mobile en cours"))
        if (logger != null) return

        val manager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telephony = manager
        logger = CellularCsvLogger(applicationContext, sessionId)
        val thread = HandlerThread("CellularOemSignalPoller").also { it.start() }
        oemHandlerThread = thread
        oemHandler = Handler(thread.looper)
        registerCallbacks(manager)
        requestFreshCellInfo()
        oemHandler?.post(refreshOemSignalStrength)
        mainHandler.postDelayed(refreshCellInfo, CELL_INFO_REFRESH_MS)
        publishStatus("Capture réseau mobile démarrée")
    }

    @SuppressLint("MissingPermission")
    private fun registerCallbacks(manager: TelephonyManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(),
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.CellInfoListener,
                TelephonyCallback.ServiceStateListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    logger?.logSignalStrength("signal_strength_callback", manager.subscriptionId, signalStrength)
                        ?.let(::publishPreview)
                }

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    logger?.logCellInfo("cell_info_callback", manager.subscriptionId, cellInfo)
                        ?.filter { it.registered == true }
                        ?.forEach(::publishPreview)
                }

                override fun onServiceStateChanged(serviceState: ServiceState) {
                    publishStatus("ServiceState ${serviceState.state}")
                }
            }
            callbackApi31 = callback
            manager.registerTelephonyCallback(mainExecutor, callback)
        } else {
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    logger?.logSignalStrength("signal_strength_listener", manager.subscriptionId, signalStrength)
                        ?.let(::publishPreview)
                }

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    logger?.logCellInfo("cell_info_listener", manager.subscriptionId, cellInfo)
                        ?.filter { it.registered == true }
                        ?.forEach(::publishPreview)
                }
            }
            listenerLegacy = listener
            @Suppress("DEPRECATION")
            manager.listen(
                listener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_CELL_INFO
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshCellInfo() {
        val manager = telephony ?: return
        if (!hasCellPermissions()) return
        runCatching {
            manager.allCellInfo.orEmpty()
        }.onSuccess { cells ->
            Log.i(TAG, "allCellInfo cells=${cells.size}")
            logger?.logCellInfo("cached_all_cell_info", manager.subscriptionId, cells)
                ?.filter { it.registered == true }
                ?.forEach(::publishPreview)
        }.onFailure {
            Log.w(TAG, "allCellInfo failed", it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                manager.signalStrength
            }.onSuccess { signalStrength ->
                signalStrength?.let {
                    logger?.logSignalStrength("cached_signal_strength", manager.subscriptionId, it)
                        ?.let(::publishPreview)
                }
            }.onFailure {
                Log.w(TAG, "signalStrength failed", it)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                manager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        logger?.logCellInfo("request_cell_info", manager.subscriptionId, cellInfo)
                            ?.filter { it.registered == true }
                            ?.forEach(::publishPreview)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        Log.w(TAG, "requestCellInfoUpdate error=$errorCode", detail)
                    }
                })
            }.onFailure {
                Log.w(TAG, "requestCellInfoUpdate failed", it)
            }
        } else {
            runCatching {
                manager.allCellInfo.orEmpty()
            }.getOrNull()?.let { cells ->
                logger?.logCellInfo("get_all_cell_info", manager.subscriptionId, cells)
                    ?.filter { it.registered == true }
                    ?.forEach(::publishPreview)
            }
        }
    }

    private fun requestOemSignalStrength() {
        val manager = telephony ?: return
        val signalStrength = oemSignalReader.readSignalStrength(phoneId = OEM_PHONE_ID)
        if (signalStrength == null) {
            if (!oemHookStatusPublished) {
                mainHandler.post { publishStatus("OEM RIL hook indisponible, fallback Android public") }
                oemHookStatusPublished = true
            }
            return
        }
        if (!oemHookStatusPublished) {
            mainHandler.post { publishStatus("OEM RIL hook actif: ${OEM_SIGNAL_REFRESH_MS}ms") }
            oemHookStatusPublished = true
        }
        logger?.logSignalStrength("oem_rilhook_signal_strength_${OEM_SIGNAL_REFRESH_MS}ms", manager.subscriptionId, signalStrength)
            ?.let { preview -> mainHandler.post { publishPreview(preview) } }
    }


    private fun stopScanning() {
        mainHandler.removeCallbacks(refreshCellInfo)
        oemHandler?.removeCallbacksAndMessages(null)
        oemHandlerThread?.quitSafely()
        oemHandler = null
        oemHandlerThread = null
        val manager = telephony
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (callbackApi31 as? TelephonyCallback)?.let { callback ->
                runCatching { manager?.unregisterTelephonyCallback(callback) }
            }
        } else {
            listenerLegacy?.let { listener ->
                @Suppress("DEPRECATION")
                runCatching { manager?.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
        }
        callbackApi31 = null
        listenerLegacy = null
        oemHookStatusPublished = false
        telephony = null
        logger?.close()
        logger = null
        scanStartedAtElapsedMs = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasCellPermissions(): Boolean {
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasPhoneState = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return hasLocation && hasPhoneState
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Cellular scan", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Cellular Scanner Logger")
            .setContentText(text)
            .setWhen(System.currentTimeMillis() - elapsedSinceScanStartMs())
            .setUsesChronometer(scanStartedAtElapsedMs != 0L)
            .setOngoing(true)
            .build()

    private fun elapsedSinceScanStartMs(): Long {
        val startedAt = scanStartedAtElapsedMs
        if (startedAt == 0L) return 0L
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    private fun publishPreview(preview: com.anezium.blescanner.data.CellularPreview) {
        val intent = Intent(ACTION_CELL_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_PREVIEW_LINE, preview.displayLine())
            .putExtra(EXTRA_PREVIEW_KEY, preview.key)
            .putExtra(EXTRA_PREVIEW_CATEGORY, "cell")
        if (liveListener != null) liveListener?.invoke(intent) else sendBroadcast(intent)
    }

    private fun publishStatus(message: String) {
        val intent = Intent(ACTION_CELL_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS_MESSAGE, message)
        if (liveListener != null) liveListener?.invoke(intent) else sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "CELL_SCANNER_APP"
        @Volatile
        var liveListener: ((Intent) -> Unit)? = null
        @Volatile
        var scanStartedAtElapsedMs: Long = 0L
        const val ACTION_STOP = "com.anezium.blescanner.CELL_STOP"
        const val ACTION_CELL_RESULT = "com.anezium.blescanner.CELL_RESULT"
        const val ACTION_CELL_STATUS = "com.anezium.blescanner.CELL_STATUS"
        const val EXTRA_PREVIEW_LINE = "preview_line"
        const val EXTRA_PREVIEW_KEY = "preview_key"
        const val EXTRA_PREVIEW_CATEGORY = "preview_category"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "cellular_scan"
        private const val NOTIFICATION_ID = 1002
        private const val CELL_INFO_REFRESH_MS = 2_000L
        private const val OEM_SIGNAL_REFRESH_MS = 10L
        private const val OEM_PHONE_ID = 0
    }
}
