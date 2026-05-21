package com.anezium.blescanner

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.anezium.blescanner.ble.BleScanService
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : android.app.Activity() {
    private lateinit var statusView: TextView
    private lateinit var countView: TextView
    private lateinit var uniqueView: TextView
    private lateinit var elapsedView: TextView
    private lateinit var emptyLogView: TextView
    private lateinit var logList: LinearLayout
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var fileContentView: TextView
    private lateinit var filePageInfoView: TextView
    private lateinit var iBeaconFilter: CheckBox
    private lateinit var datiFilter: CheckBox
    private lateinit var eddystoneFilter: CheckBox
    private lateinit var macFilterInput: EditText
    private lateinit var fileSelectionInfoView: TextView
    private lateinit var selectAllFilesButton: Button
    private lateinit var exportFilesButton: Button
    private lateinit var deleteFilesButton: Button
    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveLock = Any()
    private val recentLines = ArrayDeque<LiveLine>()
    private val uniqueAddresses = linkedSetOf<String>()
    private val selectedLogFilePaths = linkedSetOf<String>()
    private var selectedLogFile: File? = null
    private var fileListSnapshot: List<File> = emptyList()
    private var currentFilePage = 0
    @Volatile
    private var currentScreen = Screen.MAIN
    private var scanCount = 0
    private var isScanning = false
    private var startAfterPermissionGrant = false
    private var backCallback: Any? = null
    @Volatile
    private var liveRenderScheduled = false

    private val chronometerTick = object : Runnable {
        override fun run() {
            renderChronometer()
            if (currentScreen == Screen.MAIN && BleScanService.scanStartedAtElapsedMs != 0L) {
                mainHandler.postDelayed(this, CHRONOMETER_INTERVAL_MS)
            }
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleLiveIntent(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Colors.surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        registerBackHandler()
        showMainPage()
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        syncScanStateFromService()
        BleScanService.liveListener = { intent ->
            handleLiveIntent(intent)
        }
        val filter = IntentFilter().apply {
            addAction(BleScanService.ACTION_SCAN_RESULT)
            addAction(BleScanService.ACTION_SCAN_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }
    }

    override fun onStop() {
        BleScanService.liveListener = null
        mainHandler.removeCallbacks(chronometerTick)
        unregisterReceiver(scanReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BackNavigationApi33.unregister(this, backCallback)
            backCallback = null
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = BackNavigationApi33.register(this) { handleBackNavigation() }
        }
    }

    private fun handleBackNavigation() {
        when (currentScreen) {
            Screen.MAIN -> finish()
            Screen.FILE_LIST -> showMainPage()
            Screen.FILE_VIEWER -> showFileListPage()
        }
    }

    private fun handleLiveIntent(intent: Intent?) {
        when (intent?.action) {
            BleScanService.ACTION_SCAN_RESULT -> {
                val line = intent.getStringExtra(BleScanService.EXTRA_PREVIEW_LINE) ?: return
                val address = intent.getStringExtra(BleScanService.EXTRA_PREVIEW_ADDRESS).orEmpty()
                val category = intent.getStringExtra(BleScanService.EXTRA_PREVIEW_CATEGORY) ?: "ble"
                synchronized(liveLock) {
                    scanCount += 1
                    if (address.isNotBlank()) uniqueAddresses += address
                }
                addLiveLine(line, category)
            }
            BleScanService.ACTION_SCAN_STATUS -> {
                val message = intent.getStringExtra(BleScanService.EXTRA_STATUS_MESSAGE) ?: return
                addLiveLine("SYSTEM  $message", "system")
            }
        }
    }

    private fun showMainPage() {
        currentScreen = Screen.MAIN
        selectedLogFile = null
        setContentView(buildMainUi())
    }

    private fun buildMainUi(): View {
        statusView = TextView(this).apply {
            text = "Prêt"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Colors.success)
            background = rounded(Colors.successSoft, dp(999), Colors.successLine)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        countView = metric("0", "trames")
        uniqueView = metric("0", "appareils")
        emptyLogView = TextView(this).apply {
            text = "Appuie sur Start pour afficher les advertising BLE reçus."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Colors.text)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        logList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(emptyLogView)
        }

        startButton = primaryButton("Start").apply { setOnClickListener { startScan() } }
        stopButton = secondaryButton("Stop").apply { setOnClickListener { stopScan() } }
        val files = secondaryButton("Fichiers").apply { setOnClickListener { showFileListPage() } }
        val clear = quietButton("Clear").apply { setOnClickListener { clearVisibleLogs() } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.surface)
            applyScreenPadding(this)
        }

        root.addView(header(), matchWrap())
        root.addView(controlPanel(startButton, stopButton, files, clear), matchWrap(top = 20))
        root.addView(metricsRow(), matchWrap(top = 16))
        root.addView(logPanel(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(16) })

        updateScanState(isScanning, if (isScanning) "Scan actif" else "Prêt")
        renderCounters()
        renderLogRows()
        return root
    }

    private fun header(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "BLE Scanner"
                    textSize = 25f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Colors.text)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusView)
        }

    private fun controlPanel(vararg buttons: Button): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Colors.panel, dp(14), Colors.line)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(context).apply {
                text = "Archive brute"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Colors.muted)
            })
            addView(TextView(context).apply {
                text = "Direct: une ligne CSV par advertising reçu"
                textSize = 13f
                setTextColor(Colors.text)
            }, matchWrap(top = 6))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEachIndexed { index, button ->
                    addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        if (index > 0) leftMargin = dp(8)
                    })
                }
            }, matchWrap(top = 14))
        }

    private fun metricsRow(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(countView, LinearLayout.LayoutParams(0, dp(74), 1f))
            addView(uniqueView, LinearLayout.LayoutParams(0, dp(74), 1f).apply { leftMargin = dp(10) })
        }

    private fun logPanel(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Colors.panel, dp(14), Colors.line)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(10))
                addView(TextView(context).apply {
                    text = "Dernières trames"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Colors.text)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                elapsedView = TextView(context).apply {
                    text = "00:00:00"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Colors.accent)
                    background = rounded(Colors.accentSoft, dp(999), Colors.accentLine)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                }
                addView(elapsedView)
            })
            addView(separator())
            addView(ScrollView(context).apply {
                setBackgroundColor(Colors.logSurface)
                addView(logList)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }

    private fun showFileListPage() {
        currentScreen = Screen.FILE_LIST
        selectedLogFile = null
        setContentView(buildFileListUi())
    }

    private fun buildFileListUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.surface)
            applyScreenPadding(this)
        }
        root.addView(pageHeader("Fichiers logs", "Retour") { showMainPage() }, matchWrap())
        val directory = logDirectory()
        val files = directory.listFiles { file -> file.extension.equals("csv", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        fileListSnapshot = files
        selectedLogFilePaths.retainAll(files.mapTo(linkedSetOf()) { it.absolutePath })
        root.addView(storagePanel(directory), matchWrap(top = 12))
        root.addView(fileSummaryPanel(files), matchWrap(top = 12))
        root.addView(fileActionsPanel(), matchWrap(top = 12))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        if (files.isEmpty()) {
            list.addView(emptyState("Aucun CSV pour le moment. Lance un scan: le prochain fichier apparaîtra ici."))
        } else {
            files.forEach { file ->
                list.addView(fileRow(file), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
            }
        }

        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(16) })
        updateFileSelectionActions()
        return root
    }

    private fun storagePanel(directory: File): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Colors.control, dp(12), Colors.line)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Emplacement CSV"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Colors.muted)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(TextView(context).apply {
                text = directory.absolutePath
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Colors.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }, matchWrap(top = 7))
            addView(TextView(context).apply {
                text = "Les exports passent par Android. Les fichiers sont supprimés si l'app est désinstallée."
                textSize = 12f
                setTextColor(Colors.muted)
            }, matchWrap(top = 5))
        }

    private fun fileSummaryPanel(files: List<File>): View {
        val totalBytes = files.sumOf { it.length() }
        val newest = files.maxOfOrNull { it.lastModified() } ?: 0L
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Colors.panel, dp(12), Colors.line)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(fileSummaryCell(files.size.toString(), "fichiers"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(fileSummaryCell(formatBytes(totalBytes), "total"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(fileSummaryCell(if (newest == 0L) "-" else formatTime(newest), "dernier"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f))
        }
    }

    private fun fileSummaryCell(value: String, label: String): TextView =
        TextView(this).apply {
            text = "$value\n$label"
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Colors.text)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }

    private fun fileActionsPanel(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Colors.panel, dp(12), Colors.line)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Sélection"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Colors.muted)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                fileSelectionInfoView = TextView(context).apply {
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Colors.accent)
                    background = rounded(Colors.accentSoft, dp(999), Colors.accentLine)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                }
                addView(fileSelectionInfoView)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                selectAllFilesButton = secondaryButton("Tout").apply {
                    setOnClickListener { toggleAllLogFiles() }
                }
                exportFilesButton = secondaryButton("Exporter").apply {
                    setOnClickListener { exportSelectedLogFiles() }
                }
                deleteFilesButton = quietButton("Supprimer").apply {
                    setOnClickListener { confirmDeleteSelectedLogFiles() }
                }
                addView(selectAllFilesButton, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(exportFilesButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
                addView(deleteFilesButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
            }, matchWrap(top = 8))
        }

    private fun fileRow(file: File): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(8), dp(9))
            background = fileRowBackground(selectedLogFilePaths.contains(file.absolutePath))
        }
        val checkBox = CheckBox(this).apply {
            contentDescription = "Sélectionner ${file.name}"
            isChecked = selectedLogFilePaths.contains(file.absolutePath)
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedLogFilePaths += file.absolutePath else selectedLogFilePaths -= file.absolutePath
                row.background = fileRowBackground(checked)
                updateFileSelectionActions()
            }
        }
        row.addView(checkBox, LinearLayout.LayoutParams(dp(42), dp(46)))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = file.name
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Colors.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(TextView(context).apply {
                text = "${formatBytes(file.length())}  ${formatTime(file.lastModified())}"
                textSize = 12f
                setTextColor(Colors.muted)
            }, matchWrap(top = 3))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(secondaryButton("Ouvrir").apply {
            setOnClickListener { showFileViewerPage(file) }
        }, LinearLayout.LayoutParams(dp(82), dp(42)).apply { leftMargin = dp(8) })
        row.setOnClickListener { showFileViewerPage(file) }
        row.setOnLongClickListener {
            checkBox.isChecked = !checkBox.isChecked
            true
        }
        return row
    }

    private fun fileRowBackground(selected: Boolean): GradientDrawable =
        rounded(
            if (selected) Colors.accentSoft else Colors.panel,
            dp(10),
            if (selected) Colors.accentLine else Colors.line
        )

    private fun toggleAllLogFiles() {
        if (fileListSnapshot.isEmpty()) return
        if (selectedLogFilePaths.size == fileListSnapshot.size) {
            selectedLogFilePaths.clear()
        } else {
            selectedLogFilePaths.clear()
            selectedLogFilePaths.addAll(fileListSnapshot.map { it.absolutePath })
        }
        showFileListPage()
    }

    private fun updateFileSelectionActions() {
        if (!::fileSelectionInfoView.isInitialized) return
        selectedLogFilePaths.retainAll(fileListSnapshot.mapTo(linkedSetOf()) { it.absolutePath })
        val selectedCount = selectedLogFilePaths.size
        val totalCount = fileListSnapshot.size
        fileSelectionInfoView.text = if (totalCount == 0) "0 fichier" else "$selectedCount/$totalCount"
        val hasSelection = selectedCount > 0
        selectAllFilesButton.isEnabled = totalCount > 0
        selectAllFilesButton.alpha = if (totalCount > 0) 1f else 0.45f
        selectAllFilesButton.text = if (totalCount > 0 && selectedCount == totalCount) "Aucun" else "Tout"
        exportFilesButton.isEnabled = hasSelection
        exportFilesButton.alpha = if (hasSelection) 1f else 0.45f
        deleteFilesButton.isEnabled = hasSelection && !isScanning
        deleteFilesButton.alpha = if (hasSelection && !isScanning) 1f else 0.45f
        deleteFilesButton.text = if (isScanning && hasSelection) "Stop requis" else "Supprimer"
    }

    private fun selectedExistingLogFiles(): List<File> {
        val directory = runCatching { logDirectory().canonicalFile }.getOrElse { logDirectory() }
        return selectedLogFilePaths
            .mapNotNull { path -> runCatching { File(path).canonicalFile }.getOrNull() }
            .filter { file ->
                file.exists() &&
                    file.extension.equals("csv", ignoreCase = true) &&
                    file.parentFile == directory
            }
            .sortedByDescending { it.lastModified() }
    }

    private fun confirmDeleteSelectedLogFiles() {
        if (isScanning) {
            Toast.makeText(this, "Stoppe le scan avant de supprimer des CSV", Toast.LENGTH_SHORT).show()
            updateFileSelectionActions()
            return
        }
        val files = selectedExistingLogFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "Aucun fichier sélectionné", Toast.LENGTH_SHORT).show()
            updateFileSelectionActions()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Supprimer les CSV ?")
            .setMessage("${files.size} fichier(s) seront supprimés du stockage local de l'app.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                val deleted = files.count { file -> runCatching { file.delete() }.getOrDefault(false) }
                val failed = files.size - deleted
                selectedLogFilePaths.removeAll(files.map { it.absolutePath }.toSet())
                val message = if (failed == 0) {
                    "$deleted fichier(s) supprimé(s)"
                } else {
                    "$deleted supprimé(s), $failed échec(s)"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                showFileListPage()
            }
            .show()
    }

    private fun exportSelectedLogFiles() {
        val files = selectedExistingLogFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "Aucun fichier sélectionné", Toast.LENGTH_SHORT).show()
            updateFileSelectionActions()
            return
        }
        runCatching {
            val uris = ArrayList<Uri>(files.map {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
            })
            val exportIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/csv"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }.apply {
                putExtra(Intent.EXTRA_SUBJECT, "BLE Scanner Logger CSV")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, files.first().name, uris.first()).apply {
                    uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
                }
            }
            startActivity(Intent.createChooser(exportIntent, "Exporter les CSV"))
        }.onFailure {
            Log.w(TAG, "CSV export failed", it)
            Toast.makeText(this, "Export impossible pour ces fichiers", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFileViewerPage(file: File) {
        currentScreen = Screen.FILE_VIEWER
        selectedLogFile = file
        currentFilePage = 0
        setContentView(buildFileViewerUi(file))
        renderFilePage()
    }

    private fun buildFileViewerUi(file: File): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.surface)
            applyScreenPadding(this)
        }
        root.addView(pageHeader("Lecture CSV", "Fichiers") { showFileListPage() }, matchWrap())
        root.addView(TextView(this).apply {
            text = file.name
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Colors.muted)
        }, matchWrap(top = 8))

        root.addView(filterPanel(), matchWrap(top = 14))
        root.addView(pagerPanel(), matchWrap(top = 12))

        fileContentView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Colors.text)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(ScrollView(this).apply {
            background = rounded(Colors.logSurface, dp(12), Colors.line)
            addView(fileContentView)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(12) })
        return root
    }

    private fun filterPanel(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Colors.panel, dp(12), Colors.line)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(TextView(context).apply {
                text = "Filtres parseurs"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Colors.muted)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                iBeaconFilter = parserCheckBox("iBeacon")
                datiFilter = parserCheckBox("DATI")
                eddystoneFilter = parserCheckBox("Eddystone")
                addView(iBeaconFilter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(datiFilter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(eddystoneFilter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, matchWrap(top = 6))
            addView(TextView(context).apply {
                text = "Adresse MAC"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Colors.muted)
            }, matchWrap(top = 10))
            macFilterInput = EditText(context).apply {
                hint = "AA:BB:CC:DD:EE:FF"
                setSingleLine(true)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(Colors.text)
                setHintTextColor(Colors.muted)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                background = rounded(Colors.control, dp(10), Colors.line)
                setPadding(dp(10), 0, dp(10), 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        currentFilePage = 0
                        renderFilePage()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            addView(macFilterInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(6) })
        }

    private fun parserCheckBox(label: String): CheckBox =
        CheckBox(this).apply {
            text = label
            textSize = 13f
            setTextColor(Colors.text)
            setOnCheckedChangeListener { _, _ ->
                currentFilePage = 0
                renderFilePage()
            }
        }

    private fun pagerPanel(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            filePageInfoView = TextView(context).apply {
                textSize = 12f
                setTextColor(Colors.muted)
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(filePageInfoView, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(secondaryButton("Préc.").apply {
                setOnClickListener {
                    if (currentFilePage > 0) {
                        currentFilePage -= 1
                        renderFilePage()
                    }
                }
            }, LinearLayout.LayoutParams(dp(92), dp(42)))
            addView(secondaryButton("Suiv.").apply {
                setOnClickListener {
                    currentFilePage += 1
                    renderFilePage()
                }
            }, LinearLayout.LayoutParams(dp(92), dp(42)).apply { leftMargin = dp(8) })
        }

    private fun renderFilePage() {
        if (!::fileContentView.isInitialized || !::filePageInfoView.isInitialized) return
        val file = selectedLogFile ?: return
        val page = readCsvPage(file, currentFilePage, selectedParserFilters())
        if (page.rows.isEmpty() && currentFilePage > 0) {
            currentFilePage -= 1
            renderFilePage()
            return
        }
        filePageInfoView.text = "Page ${currentFilePage + 1}  ${page.rows.size} lignes"
        fileContentView.text = if (page.rows.isEmpty()) {
            "Aucune ligne pour ces filtres."
        } else {
            page.rows.joinToString("\n\n")
        }
    }

    private fun readCsvPage(file: File, page: Int, filters: ParserFilters): FilePage {
        val offset = page * FILE_PAGE_SIZE
        val rows = mutableListOf<String>()
        var matched = 0
        file.bufferedReader().use { reader ->
            val header = parseCsvLine(reader.readLine().orEmpty())
            val indexes = header.withIndex().associate { it.value to it.index }
            var line = reader.readLine()
            while (line != null) {
                val values = parseCsvLine(line)
                if (matchesParserFilters(values, indexes, filters)) {
                    if (matched >= offset && rows.size < FILE_PAGE_SIZE) {
                        rows += formatCsvPreview(values, indexes)
                    }
                    matched += 1
                    if (rows.size >= FILE_PAGE_SIZE && matched > offset + FILE_PAGE_SIZE) break
                }
                line = reader.readLine()
            }
        }
        return FilePage(rows)
    }

    private fun selectedParserFilters(): ParserFilters =
        ParserFilters(
            iBeacon = ::iBeaconFilter.isInitialized && iBeaconFilter.isChecked,
            dati = ::datiFilter.isInitialized && datiFilter.isChecked,
            eddystone = ::eddystoneFilter.isInitialized && eddystoneFilter.isChecked,
            macAddress = if (::macFilterInput.isInitialized) macFilterInput.text?.toString().orEmpty() else ""
        )

    private fun matchesParserFilters(values: List<String>, indexes: Map<String, Int>, filters: ParserFilters): Boolean {
        if (!matchesMacFilter(values, indexes, filters.macAddress)) return false
        if (!filters.parserEnabled()) return true
        val classification = classifyCsvRow(values, indexes)
        return (filters.iBeacon && classification == "iBeacon") ||
            (filters.dati && classification == "DATI") ||
            (filters.eddystone && classification.startsWith("Eddystone"))
    }

    private fun matchesMacFilter(values: List<String>, indexes: Map<String, Int>, query: String): Boolean {
        val normalizedQuery = normalizeMacFilter(query)
        if (normalizedQuery.isBlank()) return true
        val normalizedAddress = normalizeMacFilter(value(values, indexes, "address"))
        return normalizedAddress.contains(normalizedQuery)
    }

    private fun normalizeMacFilter(value: String): String =
        value.filter { it.isLetterOrDigit() }.uppercase(Locale.US)

    private fun formatCsvPreview(values: List<String>, indexes: Map<String, Int>): String {
        val type = classifyCsvRow(values, indexes)
        val time = value(values, indexes, "wall_time_local")
            .ifBlank { value(values, indexes, "wall_time_iso") }
        val address = value(values, indexes, "address")
        val rssi = value(values, indexes, "rssi_dbm")
        val name = value(values, indexes, "device_name")
        val payload = value(values, indexes, "raw_scan_record_hex")
        val label = when (type) {
            "DATI" -> "DATI room=${value(values, indexes, "dati_room")} bat=${value(values, indexes, "dati_autonomy")} temp=${value(values, indexes, "dati_temperature_c")} flags=${value(values, indexes, "dati_flags")}"
            "iBeacon" -> "iBeacon uuid=${value(values, indexes, "ibeacon_uuid")} major=${value(values, indexes, "ibeacon_major")} minor=${value(values, indexes, "ibeacon_minor")}"
            "Eddystone UID" -> "Eddystone UID ns=${value(values, indexes, "eddystone_uid_namespace")} inst=${value(values, indexes, "eddystone_uid_instance")}"
            "Eddystone TLM" -> "Eddystone TLM batt=${value(values, indexes, "eddystone_tlm_battery_mv")} temp=${value(values, indexes, "eddystone_tlm_temperature_c")}"
            else -> "BLE non parse"
        }
        val namePart = if (name.isBlank()) "" else " name=$name"
        return "$time  $address  RSSI $rssi dBm$namePart\n$label\npayload=$payload"
    }

    private fun classifyCsvRow(values: List<String>, indexes: Map<String, Int>): String {
        val dati = value(values, indexes, "dati_room").isNotBlank()
        val eddystoneUid = value(values, indexes, "eddystone_uid_namespace").isNotBlank()
        val eddystoneTlm = value(values, indexes, "eddystone_tlm_battery_mv").isNotBlank()
        val iBeacon = value(values, indexes, "ibeacon_uuid").isNotBlank()
        return when {
            dati -> "DATI"
            eddystoneUid -> "Eddystone UID"
            eddystoneTlm -> "Eddystone TLM"
            iBeacon -> "iBeacon"
            else -> "BLE"
        }
    }

    private fun value(values: List<String>, indexes: Map<String, Int>, key: String): String {
        val index = indexes[key] ?: return ""
        return values.getOrNull(index).orEmpty()
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i += 1
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i += 1
        }
        out += current.toString()
        return out
    }

    private fun pageHeader(title: String, backLabel: String, onBack: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 25f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Colors.text)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(secondaryButton(backLabel).apply { setOnClickListener { onBack() } }, LinearLayout.LayoutParams(dp(108), dp(42)))
        }

    private fun emptyState(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Colors.muted)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
            background = rounded(Colors.panel, dp(12), Colors.line)
        }

    private fun metric(value: String, label: String): TextView =
        TextView(this).apply {
            text = "$value\n$label"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Colors.text)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Colors.panel, dp(14), Colors.line)
        }

    private fun startScan() {
        Log.i(TAG, "Start button pressed")
        if (!hasRequiredPermissions()) {
            startAfterPermissionGrant = true
            addLiveLine("SYSTEM  Permissions manquantes, ouverture de la demande Android", "system")
            requestNeededPermissions()
            return
        }
        val intent = Intent(this, BleScanService::class.java)
        if (BleScanService.scanStartedAtElapsedMs == 0L) {
            BleScanService.scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        ContextCompat.startForegroundService(this, intent)
        updateScanState(true, "Scan actif")
        clearVisibleLogs()
        addLiveLine("SYSTEM  Demande de démarrage du scan", "system")
    }

    private fun stopScan() {
        Log.i(TAG, "Stop button pressed")
        addLiveLine("SYSTEM  Arrêt demandé", "system")
        val intent = Intent(this, BleScanService::class.java).setAction(BleScanService.ACTION_STOP)
        startService(intent)
        BleScanService.scanStartedAtElapsedMs = 0L
        updateScanState(false, "Arrêté")
    }

    private fun clearVisibleLogs() {
        synchronized(liveLock) {
            scanCount = 0
            uniqueAddresses.clear()
            recentLines.clear()
        }
        renderCounters()
        emptyLogView.text = if (isScanning) "En attente de trames BLE..." else "Appuie sur Start pour afficher les advertising BLE reçus."
        renderLogRows()
    }

    private fun renderCounters() {
        val snapshot = synchronized(liveLock) { scanCount to uniqueAddresses.size }
        countView.text = "${snapshot.first}\ntrames"
        uniqueView.text = "${snapshot.second}\nappareils"
    }

    private fun syncScanStateFromService() {
        val serviceScanning = BleScanService.scanStartedAtElapsedMs != 0L
        if (serviceScanning != isScanning && ::statusView.isInitialized) {
            updateScanState(serviceScanning, if (serviceScanning) "Scan actif" else "Prêt")
        }
        renderChronometer()
        scheduleChronometerTick()
    }

    private fun renderChronometer() {
        if (!::elapsedView.isInitialized) return
        val startedAt = BleScanService.scanStartedAtElapsedMs
        val elapsedMs = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt
        elapsedView.text = formatElapsed(elapsedMs.coerceAtLeast(0L))
        elapsedView.setTextColor(if (startedAt == 0L) Colors.muted else Colors.accent)
        elapsedView.background = rounded(
            if (startedAt == 0L) Colors.control else Colors.accentSoft,
            dp(999),
            if (startedAt == 0L) Colors.line else Colors.accentLine
        )
    }

    private fun scheduleChronometerTick() {
        mainHandler.removeCallbacks(chronometerTick)
        if (currentScreen == Screen.MAIN && BleScanService.scanStartedAtElapsedMs != 0L) {
            mainHandler.postDelayed(chronometerTick, CHRONOMETER_INTERVAL_MS)
        }
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun updateScanState(scanning: Boolean, label: String) {
        isScanning = scanning
        renderChronometer()
        scheduleChronometerTick()
        statusView.text = label
        statusView.setTextColor(if (scanning) Colors.accent else Colors.success)
        statusView.background = rounded(
            if (scanning) Colors.accentSoft else Colors.successSoft,
            dp(999),
            if (scanning) Colors.accentLine else Colors.successLine
        )
        startButton.isEnabled = !scanning
        stopButton.isEnabled = scanning
        startButton.alpha = if (scanning) 0.5f else 1f
        stopButton.alpha = if (scanning) 1f else 0.55f
    }

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
            addLiveLine("SYSTEM  Permissions accordées", "system")
            if (startAfterPermissionGrant) {
                startAfterPermissionGrant = false
                startScan()
            }
        } else {
            startAfterPermissionGrant = false
            addLiveLine("SYSTEM  Autorisations incomplètes: active Bluetooth et Localisation précise", "system")
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private fun primaryButton(label: String): Button =
        button(label, Colors.accent, Colors.accentPressed, Colors.onAccent)

    private fun secondaryButton(label: String): Button =
        button(label, Colors.control, Colors.controlPressed, Colors.text, Colors.line)

    private fun quietButton(label: String): Button =
        button(label, Colors.panel, Colors.controlPressed, Colors.muted, Colors.line)

    private fun button(
        label: String,
        normal: Int,
        pressed: Int,
        textColor: Int,
        stroke: Int? = null
    ): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(textColor)
            background = buttonBackground(normal, pressed, stroke)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
        }

    private fun buttonBackground(normal: Int, pressed: Int, stroke: Int?): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, dp(10), stroke))
            addState(intArrayOf(), rounded(normal, dp(10), stroke))
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun separator(): View =
        View(this).apply {
            setBackgroundColor(Colors.line)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        }

    private fun applyScreenPadding(view: View) {
        val horizontal = dp(18)
        val fallbackTop = dp(24)
        val fallbackBottom = dp(18)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.setOnApplyWindowInsetsListener { target, insets ->
                val systemBars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Insets.of(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                }
                target.setPadding(
                    horizontal + systemBars.left,
                    dp(12) + systemBars.top,
                    horizontal + systemBars.right,
                    fallbackBottom + systemBars.bottom
                )
                insets
            }
            view.requestApplyInsets()
        } else {
            view.setPadding(horizontal, fallbackTop, horizontal, fallbackBottom)
        }
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun logDirectory(): File =
        File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ble_logs").apply { mkdirs() }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        return String.format(Locale.US, "%.1f MB", kb / 1024.0)
    }

    private fun formatTime(epochMs: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    private fun addLiveLine(line: String, category: String) {
        synchronized(liveLock) {
            recentLines.addFirst(LiveLine(line, category))
            while (recentLines.size > MAX_VISIBLE_LINES) recentLines.removeLast()
        }
        scheduleLiveRender()
    }

    private fun scheduleLiveRender() {
        if (currentScreen != Screen.MAIN) return
        if (liveRenderScheduled) return
        liveRenderScheduled = true
        mainHandler.postDelayed({
            liveRenderScheduled = false
            if (currentScreen == Screen.MAIN) {
                renderCounters()
                renderLogRows()
            }
        }, LIVE_RENDER_INTERVAL_MS)
    }

    private fun renderLiveNow() {
        liveRenderScheduled = false
        if (currentScreen != Screen.MAIN) return
        renderCounters()
        renderLogRows()
        scheduleChronometerTick()
    }

    private fun renderLogRows() {
        val snapshot = synchronized(liveLock) { recentLines.toList() }
        logList.removeAllViews()
        if (snapshot.isEmpty()) {
            emptyLogView.visibility = View.VISIBLE
            logList.addView(emptyLogView)
            return
        }
        emptyLogView.visibility = View.GONE
        snapshot.forEach { entry ->
            logList.addView(logRow(entry), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun logRow(entry: LiveLine): TextView {
        val colors = categoryColors(entry.category)
        return TextView(this).apply {
            text = entry.text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Colors.text)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(colors.background, dp(10), colors.stroke)
        }
    }

    private fun categoryColors(category: String): CategoryColors =
        when (category) {
            "ibeacon" -> CategoryColors(Colors.iBeaconSoft, Colors.iBeaconLine)
            "eddystone_uid" -> CategoryColors(Colors.eddystoneSoft, Colors.eddystoneLine)
            "eddystone_tlm" -> CategoryColors(Colors.telemetrySoft, Colors.telemetryLine)
            "dati" -> CategoryColors(Colors.datiSoft, Colors.datiLine)
            "system" -> CategoryColors(Colors.systemSoft, Colors.systemLine)
            else -> CategoryColors(Colors.bleSoft, Colors.bleLine)
        }

    private data class LiveLine(val text: String, val category: String)

    private data class CategoryColors(val background: Int, val stroke: Int)

    private data class FilePage(val rows: List<String>)

    private data class ParserFilters(
        val iBeacon: Boolean,
        val dati: Boolean,
        val eddystone: Boolean,
        val macAddress: String
    ) {
        fun parserEnabled(): Boolean = iBeacon || dati || eddystone
    }

    private enum class Screen {
        MAIN,
        FILE_LIST,
        FILE_VIEWER
    }

    private object BackNavigationApi33 {
        fun register(activity: MainActivity, onBack: () -> Unit): OnBackInvokedCallback {
            val callback = OnBackInvokedCallback { onBack() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            return callback
        }

        fun unregister(activity: MainActivity, callback: Any?) {
            (callback as? OnBackInvokedCallback)?.let {
                activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
    }

    private object Colors {
        val surface = Color.rgb(246, 247, 244)
        val panel = Color.rgb(253, 253, 250)
        val logSurface = Color.rgb(250, 251, 248)
        val control = Color.rgb(237, 240, 235)
        val controlPressed = Color.rgb(226, 231, 224)
        val line = Color.rgb(214, 220, 211)
        val text = Color.rgb(28, 35, 31)
        val muted = Color.rgb(99, 111, 103)
        val accent = Color.rgb(20, 123, 108)
        val accentPressed = Color.rgb(16, 104, 92)
        val accentSoft = Color.rgb(223, 242, 237)
        val accentLine = Color.rgb(150, 211, 199)
        val success = Color.rgb(60, 113, 78)
        val successSoft = Color.rgb(231, 242, 231)
        val successLine = Color.rgb(174, 211, 180)
        val onAccent = Color.rgb(246, 252, 249)
        val iBeaconSoft = Color.rgb(235, 242, 252)
        val iBeaconLine = Color.rgb(140, 175, 224)
        val eddystoneSoft = Color.rgb(252, 245, 229)
        val eddystoneLine = Color.rgb(222, 183, 112)
        val telemetrySoft = Color.rgb(240, 237, 251)
        val telemetryLine = Color.rgb(170, 154, 220)
        val datiSoft = Color.rgb(232, 244, 236)
        val datiLine = Color.rgb(133, 193, 153)
        val bleSoft = Color.rgb(239, 242, 239)
        val bleLine = Color.rgb(194, 202, 194)
        val systemSoft = Color.rgb(246, 241, 235)
        val systemLine = Color.rgb(214, 200, 182)
    }

    companion object {
        private const val TAG = "BLE_SCANNER_APP"
        private const val REQUEST_PERMISSIONS = 10
        private const val MAX_VISIBLE_LINES = 200
        private const val FILE_PAGE_SIZE = 200
        private const val LIVE_RENDER_INTERVAL_MS = 500L
        private const val CHRONOMETER_INTERVAL_MS = 1_000L
    }
}
