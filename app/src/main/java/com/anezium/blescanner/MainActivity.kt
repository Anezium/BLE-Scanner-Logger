package com.anezium.blescanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.anezium.blescanner.ble.BleScanService
import com.anezium.blescanner.cell.CellularScanService
import com.anezium.blescanner.ui.*
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import java.io.File
import java.util.Locale

/**
 * État applicatif, navigation entre les 3 écrans, permissions et réception des
 * évènements des services. La construction des écrans vit dans `ui/`.
 */
class MainActivity : android.app.Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mainViews: MainScreenViews? = null
    private var viewerViews: ViewerScreenViews? = null

    private var selectedLogFile: File? = null
    private var currentFilePage = 0

    @Volatile
    private var currentScreen = Screen.MAIN
    private var isScanning = false
    private var scanMode = ScanMode.BLE
    private var scanStatus = ScanStatus.READY
    private var startAfterPermissionGrant = false
    private var pendingScanMode: ScanMode? = null
    private var backCallback: Any? = null

    private val liveFeed = LiveFeedBuffer(
        handler = mainHandler,
        maxLines = MAX_VISIBLE_LINES,
        throttleMs = LIVE_RENDER_INTERVAL_MS,
        isActive = { currentScreen == Screen.MAIN },
        onFlush = {
            mainViews?.adapter?.notifyDataSetChanged()
            renderCounters()
        }
    )

    private val filesController = FilesController(
        activity = this,
        isScanning = { isScanning },
        onBack = { showMainPage() },
        onOpenFile = { file -> showFileViewerPage(file) }
    )

    private val chronometerTick = object : Runnable {
        override fun run() {
            renderChronometer()
            if (currentScreen == Screen.MAIN && activeScanStartedAtElapsedMs() != 0L) {
                mainHandler.postDelayed(this, CHRONOMETER_INTERVAL_MS)
            }
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleLiveIntent(intent)
        }
    }

    // --- Cycle de vie ------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Colors.surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = BackNavigationApi33.register(this) { handleBackNavigation() }
        }
        showMainPage()
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        syncScanStateFromService()
        BleScanService.liveListener = { intent -> handleLiveIntent(intent) }
        CellularScanService.liveListener = { intent -> handleLiveIntent(intent) }
        val filter = IntentFilter().apply {
            addAction(BleScanService.ACTION_SCAN_RESULT)
            addAction(BleScanService.ACTION_SCAN_STATUS)
            addAction(CellularScanService.ACTION_CELL_RESULT)
            addAction(CellularScanService.ACTION_CELL_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }
    }

    override fun onStop() {
        BleScanService.liveListener = null
        CellularScanService.liveListener = null
        mainHandler.removeCallbacks(chronometerTick)
        liveFeed.cancelPendingFlush()
        unregisterReceiver(scanReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BackNavigationApi33.unregister(this, backCallback)
            backCallback = null
        }
        mainViews = null
        viewerViews = null
        filesController.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    private fun handleBackNavigation() {
        when (currentScreen) {
            Screen.MAIN -> finish()
            Screen.FILE_LIST -> showMainPage()
            Screen.FILE_VIEWER -> showFileListPage()
        }
    }

    // --- Navigation --------------------------------------------------------

    private fun showMainPage() {
        currentScreen = Screen.MAIN
        selectedLogFile = null
        viewerViews = null
        filesController.release()
        liveFeed.copyToVisible()
        val views = buildMainScreen(
            liveLines = liveFeed.visible,
            onModeSelected = ::selectMode,
            onToggleScan = ::toggleScan,
            onClear = ::clearVisibleLogs,
            onOpenFiles = ::showFileListPage,
            onCopyLine = ::copyLine
        )
        mainViews = views
        setContentView(views.root)
        views.renderStatus(scanStatus)
        views.renderMode(scanMode, !isScanning)
        views.renderAction(isScanning)
        views.setEmptyText(emptyFeedText())
        views.renderFileCount(filesController.csvCount())
        renderCounters()
        renderChronometer()
        scheduleChronometerTick()
    }

    private fun showFileListPage() {
        currentScreen = Screen.FILE_LIST
        selectedLogFile = null
        mainViews = null
        viewerViews = null
        setContentView(filesController.buildScreen())
        scheduleChronometerTick()
        filesController.reload()
    }

    private fun showFileViewerPage(file: File) {
        currentScreen = Screen.FILE_VIEWER
        selectedLogFile = file
        currentFilePage = 0
        mainViews = null
        filesController.release()
        val views = buildViewerScreen(
            fileName = file.name,
            onBack = ::showFileListPage,
            onFiltersChanged = {
                currentFilePage = 0
                renderFilePage()
            },
            onPrevious = {
                if (currentFilePage > 0) {
                    currentFilePage -= 1
                    renderFilePage()
                }
            },
            onNext = {
                currentFilePage += 1
                renderFilePage()
            }
        )
        viewerViews = views
        setContentView(views.root)
        scheduleChronometerTick()
        renderFilePage()
    }

    // --- Flux live ---------------------------------------------------------

    private fun handleLiveIntent(intent: Intent?) {
        when (intent?.action) {
            BleScanService.ACTION_SCAN_RESULT -> {
                val line = intent.getStringExtra(BleScanService.EXTRA_PREVIEW_LINE) ?: return
                val address = intent.getStringExtra(BleScanService.EXTRA_PREVIEW_ADDRESS).orEmpty()
                val category = intent.getStringExtra(BleScanService.EXTRA_PREVIEW_CATEGORY) ?: "ble"
                liveFeed.countFrame(address)
                liveFeed.add(line, category)
            }
            BleScanService.ACTION_SCAN_STATUS -> {
                val message = intent.getStringExtra(BleScanService.EXTRA_STATUS_MESSAGE) ?: return
                liveFeed.add("SYSTEM  $message", "system")
            }
            CellularScanService.ACTION_CELL_RESULT -> {
                val line = intent.getStringExtra(CellularScanService.EXTRA_PREVIEW_LINE) ?: return
                val key = intent.getStringExtra(CellularScanService.EXTRA_PREVIEW_KEY).orEmpty()
                liveFeed.countFrame(key)
                liveFeed.add(line, "cell")
            }
            CellularScanService.ACTION_CELL_STATUS -> {
                val message = intent.getStringExtra(CellularScanService.EXTRA_STATUS_MESSAGE) ?: return
                liveFeed.add("SYSTEM  $message", "system")
            }
        }
    }

    /** N'efface que l'affichage: les CSV déjà écrits ne sont pas touchés. */
    private fun clearVisibleLogs() {
        liveFeed.clear()
        mainViews?.setEmptyText(emptyFeedText())
        mainViews?.adapter?.notifyDataSetChanged()
        renderCounters()
    }

    private fun renderCounters() {
        mainViews?.renderCounters(liveFeed.frameCount(), liveFeed.deviceCount())
    }

    private fun emptyFeedText(): String =
        if (isScanning) {
            "En attente de trames..."
        } else {
            "Appuie sur Démarrer pour voir les trames en direct."
        }

    private fun copyLine(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("trame", text))
        Toast.makeText(this, "Ligne copiée", Toast.LENGTH_SHORT).show()
    }

    // --- Scan --------------------------------------------------------------

    private fun selectMode(mode: ScanMode) {
        if (isScanning) return
        scanMode = mode
        mainViews?.renderMode(mode, true)
    }

    private fun toggleScan() {
        if (isScanning || activeScanStartedAtElapsedMs() != 0L) {
            stopActiveScan()
        } else {
            startScan(scanMode)
        }
    }

    private fun startScan(mode: ScanMode) {
        Log.i(TAG, "Start requested mode=$mode")
        if (activeScanStartedAtElapsedMs() != 0L) {
            liveFeed.add("SYSTEM  Stoppe le scan actif avant d'en lancer un autre", "system")
            return
        }
        if (!hasRequiredPermissions()) {
            startAfterPermissionGrant = true
            pendingScanMode = mode
            liveFeed.add("SYSTEM  Permissions manquantes, ouverture de la demande Android", "system")
            requestNeededPermissions()
            return
        }
        if (mode == ScanMode.BLE && !isBluetoothEnabled()) {
            pendingScanMode = mode
            liveFeed.add("SYSTEM  Bluetooth désactivé, demande d'activation", "system")
            requestEnableBluetooth()
            return
        }
        if (!isLocationEnabled()) {
            pendingScanMode = mode
            liveFeed.add("SYSTEM  Localisation désactivée, demande d'activation (exigée par Android pour scanner)", "system")
            requestEnableLocation()
            return
        }
        if (mode == ScanMode.BLE) {
            if (BleScanService.scanStartedAtElapsedMs == 0L) {
                BleScanService.scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
            }
            ContextCompat.startForegroundService(this, Intent(this, BleScanService::class.java))
        } else {
            if (CellularScanService.scanStartedAtElapsedMs == 0L) {
                CellularScanService.scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
            }
            ContextCompat.startForegroundService(this, Intent(this, CellularScanService::class.java))
        }
        scanMode = mode
        updateScanState(true)
        clearVisibleLogs()
        liveFeed.add(
            if (mode == ScanMode.BLE) {
                "SYSTEM  Demande de démarrage du scan Bluetooth"
            } else {
                "SYSTEM  Demande de démarrage du scan réseau mobile"
            },
            "system"
        )
    }

    private fun stopActiveScan() {
        val mode = when {
            BleScanService.scanStartedAtElapsedMs != 0L -> ScanMode.BLE
            CellularScanService.scanStartedAtElapsedMs != 0L -> ScanMode.CELL
            else -> scanMode
        }
        Log.i(TAG, "Stop requested mode=$mode")
        liveFeed.add("SYSTEM  Arrêt demandé", "system")
        if (mode == ScanMode.BLE) {
            startService(Intent(this, BleScanService::class.java).setAction(BleScanService.ACTION_STOP))
            BleScanService.scanStartedAtElapsedMs = 0L
        } else {
            startService(
                Intent(this, CellularScanService::class.java)
                    .setAction(CellularScanService.ACTION_STOP)
            )
            CellularScanService.scanStartedAtElapsedMs = 0L
        }
        updateScanState(false)
    }

    private fun updateScanState(scanning: Boolean) {
        isScanning = scanning
        scanStatus = when {
            scanning -> ScanStatus.RUNNING
            scanStatus == ScanStatus.READY -> ScanStatus.READY
            else -> ScanStatus.STOPPED
        }
        mainViews?.let { views ->
            views.renderStatus(scanStatus)
            views.renderAction(scanning)
            views.renderMode(scanMode, !scanning)
            views.setEmptyText(emptyFeedText())
        }
        filesController.renderSelection()
        renderChronometer()
        scheduleChronometerTick()
    }

    private fun syncScanStateFromService() {
        val bleActive = BleScanService.scanStartedAtElapsedMs != 0L
        val cellActive = CellularScanService.scanStartedAtElapsedMs != 0L
        val serviceScanning = bleActive || cellActive
        if (serviceScanning) scanMode = if (bleActive) ScanMode.BLE else ScanMode.CELL
        if (serviceScanning != isScanning) {
            updateScanState(serviceScanning)
        } else {
            mainViews?.renderMode(scanMode, !isScanning)
        }
        renderChronometer()
        scheduleChronometerTick()
    }

    private fun activeScanStartedAtElapsedMs(): Long =
        when {
            BleScanService.scanStartedAtElapsedMs != 0L -> BleScanService.scanStartedAtElapsedMs
            CellularScanService.scanStartedAtElapsedMs != 0L -> CellularScanService.scanStartedAtElapsedMs
            else -> 0L
        }

    private fun renderChronometer() {
        val views = mainViews ?: return
        val startedAt = activeScanStartedAtElapsedMs()
        val elapsedMs = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt
        views.renderElapsed(formatElapsed(elapsedMs.coerceAtLeast(0L)), startedAt != 0L)
    }

    private fun scheduleChronometerTick() {
        mainHandler.removeCallbacks(chronometerTick)
        if (currentScreen == Screen.MAIN && activeScanStartedAtElapsedMs() != 0L) {
            mainHandler.postDelayed(chronometerTick, CHRONOMETER_INTERVAL_MS)
        }
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            totalSeconds / 3600L,
            (totalSeconds % 3600L) / 60L,
            totalSeconds % 60L
        )
    }

    // --- Lecteur CSV -------------------------------------------------------

    private fun renderFilePage() {
        val views = viewerViews ?: return
        val file = selectedLogFile ?: return
        val page = CsvPageSource.readPage(file, currentFilePage, views.filters())
        if (page.rows.isEmpty() && currentFilePage > 0) {
            currentFilePage -= 1
            renderFilePage()
            return
        }
        views.renderPage(
            currentFilePage,
            page.rows.size,
            if (page.rows.isEmpty()) {
                "Aucune ligne pour ces filtres."
            } else {
                page.rows.joinToString("\n\n")
            }
        )
    }

    // --- Permissions -------------------------------------------------------

    private fun requestNeededPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasRequiredPermissions()) {
            liveFeed.add("SYSTEM  Permissions accordées", "system")
            if (startAfterPermissionGrant) {
                val mode = pendingScanMode ?: scanMode
                startAfterPermissionGrant = false
                pendingScanMode = null
                startScan(mode)
            }
        } else {
            startAfterPermissionGrant = false
            pendingScanMode = null
            liveFeed.add(
                "SYSTEM  Autorisations incomplètes: active Bluetooth et Localisation précise",
                "system"
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        permissions += Manifest.permission.READ_PHONE_STATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    // --- Activation Bluetooth / localisation --------------------------------

    private fun isBluetoothEnabled(): Boolean =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun requestEnableBluetooth() {
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_REQUEST_ENABLE failed", e)
            openSettingsFallback(
                Settings.ACTION_BLUETOOTH_SETTINGS,
                REQUEST_ENABLE_BLUETOOTH,
                "Active le Bluetooth puis reviens dans l'app"
            )
        }
    }

    private fun requestEnableLocation() {
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build()
            )
            .build()
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                if (isLocationEnabled()) {
                    resumePendingScan()
                } else {
                    openLocationSettingsFallback()
                }
            }
            .addOnFailureListener { error ->
                if (error is ResolvableApiException) {
                    try {
                        error.startResolutionForResult(this, REQUEST_ENABLE_LOCATION)
                    } catch (e: Exception) {
                        Log.w(TAG, "Location resolution failed", e)
                        openLocationSettingsFallback()
                    }
                } else {
                    // Pas de Play Services: on renvoie vers l'écran de réglages.
                    openLocationSettingsFallback()
                }
            }
    }

    private fun openLocationSettingsFallback() {
        openSettingsFallback(
            Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            REQUEST_ENABLE_LOCATION,
            "Active la localisation puis reviens dans l'app"
        )
    }

    private fun openSettingsFallback(action: String, requestCode: Int, message: String) {
        liveFeed.add("SYSTEM  $message", "system")
        try {
            startActivityForResult(Intent(action), requestCode)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open settings $action", e)
            liveFeed.add("SYSTEM  Impossible d'ouvrir les réglages", "system")
        }
    }

    private fun resumePendingScan() {
        val mode = pendingScanMode ?: return
        pendingScanMode = null
        startScan(mode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_ENABLE_BLUETOOTH -> {
                if (resultCode == RESULT_OK || isBluetoothEnabled()) {
                    liveFeed.add("SYSTEM  Bluetooth activé", "system")
                    resumePendingScan()
                } else {
                    pendingScanMode = null
                    liveFeed.add("SYSTEM  Scan annulé: le Bluetooth est nécessaire", "system")
                }
            }
            REQUEST_ENABLE_LOCATION -> {
                if (isLocationEnabled()) {
                    liveFeed.add("SYSTEM  Localisation activée", "system")
                    resumePendingScan()
                } else {
                    pendingScanMode = null
                    liveFeed.add("SYSTEM  Scan annulé: Android exige la localisation pour scanner", "system")
                }
            }
        }
    }

    private enum class Screen { MAIN, FILE_LIST, FILE_VIEWER }

    companion object {
        private const val TAG = "BLE_SCANNER_APP"
        private const val REQUEST_PERMISSIONS = 10
        private const val REQUEST_ENABLE_BLUETOOTH = 11
        private const val REQUEST_ENABLE_LOCATION = 12
        private const val MAX_VISIBLE_LINES = 200
        private const val LIVE_RENDER_INTERVAL_MS = 500L
        private const val CHRONOMETER_INTERVAL_MS = 1_000L
    }
}
