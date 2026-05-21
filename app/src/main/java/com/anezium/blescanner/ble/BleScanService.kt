package com.anezium.blescanner.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anezium.blescanner.data.BleCsvLogger

class BleScanService : Service() {
    private var logger: BleCsvLogger? = null
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
            else -> startScanning()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        stopScanning()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        Log.i(TAG, "startScanning")
        if (!hasScanPermission() || !hasConnectPermission()) {
            Log.w(TAG, "Missing Bluetooth permission scan=${hasScanPermission()} connect=${hasConnectPermission()}")
            publishStatus("Permission Bluetooth incomplète")
            stopSelf()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing fine location permission")
            publishStatus("Permission localisation précise manquante")
            stopSelf()
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth disabled or adapter null")
            publishStatus("Bluetooth désactivé")
            stopSelf()
            return
        }

        if (scanStartedAtElapsedMs == 0L) scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
        startForeground(NOTIFICATION_ID, notification("Scan BLE en cours"))
        if (logger != null) return

        logger = BleCsvLogger(applicationContext)
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
        }.onFailure {
            Log.e(TAG, "startScan failed", it)
            publishStatus("Impossible de démarrer le scan: ${it.javaClass.simpleName}")
            stopScanning()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        Log.i(TAG, "stopScanning")
        cancelWatchdogRestart()
        runCatching {
            if (hasScanPermission()) scanner.stopScan(callback)
        }.onFailure {
            Log.w(TAG, "stopScan failed", it)
        }
        scanActive = false
        logger?.close()
        logger = null
        scanStartedAtElapsedMs = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun restartScanForWatchdog() {
        if (logger == null || !scanActive) return
        if (!hasScanPermission()) {
            publishStatus("Relance anti-throttle annulee: permission Bluetooth manquante")
            stopScanning()
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
                    stopScanning()
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
        const val ACTION_STOP = "com.anezium.blescanner.STOP"
        const val ACTION_SCAN_RESULT = "com.anezium.blescanner.SCAN_RESULT"
        const val ACTION_SCAN_STATUS = "com.anezium.blescanner.SCAN_STATUS"
        const val EXTRA_PREVIEW_LINE = "preview_line"
        const val EXTRA_PREVIEW_ADDRESS = "preview_address"
        const val EXTRA_PREVIEW_RSSI = "preview_rssi"
        const val EXTRA_PREVIEW_CATEGORY = "preview_category"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        private const val CHANNEL_ID = "ble_scan"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_RESTART_INTERVAL_MS = 270_000L
        private const val WATCHDOG_RESTART_GAP_MS = 250L
    }
}
