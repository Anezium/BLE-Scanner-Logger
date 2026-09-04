package com.anezium.blescanner.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.anezium.blescanner.data.EventCsvLogger
import com.anezium.blescanner.imu.ImuRecorder

class CaptureService : Service() {
    private var logger: EventCsvLogger? = null
    private var imuRecorder: ImuRecorder? = null
    private var scanActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanner by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner
    }

    private val watchdogRestart = object : Runnable {
        override fun run() {
            restartScanForWatchdog()
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            logger?.log(result)?.let(::publishPreview)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result -> logger?.log(result)?.let(::publishPreview) }
        }

        override fun onScanFailed(errorCode: Int) {
            publishStatus("Erreur scan BLE: code $errorCode")
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
            else -> startScanning(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        stopScanning()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission", "InlinedApi")
    private fun startScanning(intent: Intent?) {
        Log.i(TAG, "startScanning")
        if (logger != null) {
            // Tout startForegroundService() doit être suivi d'un startForeground(),
            // même si la session tourne déjà, sinon Android tue le service.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("Capture ${activeSources.label()} en cours"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            return
        }

        val requestedSources = buildSet {
            if (intent?.getBooleanExtra(EXTRA_ENABLE_BLE, false) == true) add(CaptureSource.BLE)
            if (intent?.getBooleanExtra(EXTRA_ENABLE_IMU, false) == true) add(CaptureSource.IMU)
        }
        if (requestedSources.isEmpty()) {
            publishStatus("Aucune source événementielle sélectionnée")
            activeSources = emptySet()
            scanStartedAtElapsedMs = 0L
            stopSelf()
            return
        }

        val sources = requestedSources.toMutableSet()
        if (CaptureSource.BLE in sources) {
            bluetoothPrerequisiteFailure()?.let { message ->
                publishStatus(message)
                sources -= CaptureSource.BLE
                if (CaptureSource.IMU !in sources) {
                    activeSources = emptySet()
                    scanStartedAtElapsedMs = 0L
                    stopSelf()
                    return
                }
            }
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: SessionId.now()
        val startedSources = sources.toSet()
        if (scanStartedAtElapsedMs == 0L) scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
        activeSources = startedSources
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Capture ${startedSources.label()} en cours"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        val sessionLogger = EventCsvLogger(applicationContext, sessionId, startedSources)
        logger = sessionLogger

        if (CaptureSource.IMU in startedSources) {
            val recorder = ImuRecorder(applicationContext, sessionLogger)
            imuRecorder = recorder
            runCatching { recorder.start() }
                .onSuccess { result ->
                    Log.i(TAG, result.statusMessage())
                    publishStatus(result.statusMessage())
                }
                .onFailure { error ->
                    Log.e(TAG, "IMU registration failed", error)
                    publishStatus("IMU indisponible: ${error.javaClass.simpleName}")
                }
        }

        if (CaptureSource.BLE in startedSources) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            runCatching {
                scanner.startScan(null, settings, callback)
                scanActive = true
                scheduleWatchdogRestart()
                Log.i(TAG, "BluetoothLeScanner.startScan called")
                publishStatus("Scan BLE démarré")
            }.onFailure { error ->
                Log.e(TAG, "startScan failed", error)
                publishStatus("Impossible de démarrer le scan: ${error.javaClass.simpleName}")
                disableBleOrStop()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        Log.i(TAG, "stopScanning")
        cancelWatchdogRestart()
        runCatching {
            if (scanActive && hasScanPermission()) scanner.stopScan(callback)
        }.onFailure {
            Log.w(TAG, "stopScan failed", it)
        }
        scanActive = false
        imuRecorder?.stop()
        imuRecorder = null
        logger?.close()
        logger = null
        scanStartedAtElapsedMs = 0L
        activeSources = emptySet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun restartScanForWatchdog() {
        if (logger == null || !scanActive) return
        if (!hasScanPermission()) {
            publishStatus("Relance anti-throttle annulee: permission Bluetooth manquante")
            disableBleOrStop()
            return
        }

        Log.i(TAG, "Watchdog restarting BLE scan before Android timeout")
        publishStatus("Relance anti-throttle BLE")
        runCatching {
            scanner.stopScan(callback)
        }.onFailure {
            Log.w(TAG, "watchdog stopScan failed", it)
        }
        scanActive = false

        mainHandler.postDelayed({
            if (logger != null) {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build()
                runCatching {
                    scanner.startScan(null, settings, callback)
                    scanActive = true
                    scheduleWatchdogRestart()
                    Log.i(TAG, "Watchdog BluetoothLeScanner.startScan called")
                    publishStatus("Scan BLE relance")
                }.onFailure {
                    Log.e(TAG, "watchdog startScan failed", it)
                    publishStatus("Relance anti-throttle impossible: ${it.javaClass.simpleName}")
                    disableBleOrStop()
                }
            }
        }, WATCHDOG_RESTART_GAP_MS)
    }

    private fun scheduleWatchdogRestart() {
        mainHandler.removeCallbacks(watchdogRestart)
        mainHandler.postDelayed(watchdogRestart, WATCHDOG_RESTART_INTERVAL_MS)
    }

    private fun cancelWatchdogRestart() {
        mainHandler.removeCallbacks(watchdogRestart)
    }

    private fun bluetoothPrerequisiteFailure(): String? {
        if (!hasScanPermission() || !hasConnectPermission()) {
            Log.w(TAG, "Missing Bluetooth permission scan=${hasScanPermission()} connect=${hasConnectPermission()}")
            return "Permission Bluetooth incomplète"
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing fine location permission")
            return "Permission localisation précise manquante"
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth disabled or adapter null")
            return "Bluetooth désactivé"
        }
        return null
    }

    private fun disableBleOrStop() {
        cancelWatchdogRestart()
        scanActive = false
        val remainingSources = activeSources - CaptureSource.BLE
        if (CaptureSource.IMU !in remainingSources) {
            stopScanning()
            return
        }
        activeSources = remainingSources
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Capture ${remainingSources.label()} en cours"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "BLE scan", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BLE Scanner Logger")
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

    private fun publishPreview(preview: com.anezium.blescanner.data.ScanPreview) {
        val intent = Intent(ACTION_SCAN_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_PREVIEW_LINE, preview.displayLine())
            .putExtra(EXTRA_PREVIEW_ADDRESS, preview.address)
            .putExtra(EXTRA_PREVIEW_RSSI, preview.rssi)
            .putExtra(EXTRA_PREVIEW_CATEGORY, preview.category)
        if (liveListener != null) liveListener?.invoke(intent) else sendBroadcast(intent)
    }

    private fun publishStatus(message: String) {
        val intent = Intent(ACTION_SCAN_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS_MESSAGE, message)
        if (liveListener != null) liveListener?.invoke(intent) else sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "BLE_SCANNER_APP"
        @Volatile
        var liveListener: ((Intent) -> Unit)? = null
        @Volatile
        var scanStartedAtElapsedMs: Long = 0L
        @Volatile
        var activeSources: Set<CaptureSource> = emptySet()
        const val ACTION_STOP = "com.anezium.blescanner.STOP"
        const val ACTION_SCAN_RESULT = "com.anezium.blescanner.SCAN_RESULT"
        const val ACTION_SCAN_STATUS = "com.anezium.blescanner.SCAN_STATUS"
        const val EXTRA_PREVIEW_LINE = "preview_line"
        const val EXTRA_PREVIEW_ADDRESS = "preview_address"
        const val EXTRA_PREVIEW_RSSI = "preview_rssi"
        const val EXTRA_PREVIEW_CATEGORY = "preview_category"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_ENABLE_BLE = "enable_ble"
        const val EXTRA_ENABLE_IMU = "enable_imu"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "ble_scan"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_RESTART_INTERVAL_MS = 270_000L
        private const val WATCHDOG_RESTART_GAP_MS = 250L
    }
}
