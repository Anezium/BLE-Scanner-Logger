package com.anezium.blescanner.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "BLE_SCANNER_APP"

/** Références de vues de l'écran Fichiers. */
class FilesScreenViews(
    val root: View,
    val listView: ListView,
    val adapter: FileListAdapter,
    private val summaryView: TextView,
    private val pathView: TextView,
    private val selectionChip: TextView,
    private val selectAllButton: Button,
    private val exportButton: Button,
    private val deleteButton: Button
) {
    fun renderSummary(count: Int, totalBytes: Long, newestMs: Long) {
        val last = if (newestMs == 0L) "-" else formatClock(newestMs)
        val noun = if (count > 1) "fichiers" else "fichier"
        summaryView.text = "$count $noun · ${formatBytes(totalBytes)} · dernier : $last"
    }

    fun renderPath(path: String) {
        pathView.text = path
    }

    fun renderSelection(selected: Int, total: Int, scanning: Boolean) {
        selectionChip.text = if (total == 0) "Aucun fichier" else "$selected/$total sélectionnés"
        val hasSelection = selected > 0
        selectAllButton.text = if (total > 0 && selected == total) "Aucun" else "Tout"
        selectAllButton.setEnabledWithAlpha(total > 0)
        exportButton.setEnabledWithAlpha(hasSelection)
        deleteButton.text = if (scanning && hasSelection) "Stop requis" else "Supprimer"
        deleteButton.setEnabledWithAlpha(hasSelection && !scanning)
    }

    /** Rafraîchit les lignes en place, sans reconstruire l'écran. */
    fun refreshRows() {
        adapter.notifyDataSetChanged()
    }
}

fun Context.buildFilesScreen(
    entries: List<FileEntry>,
    isSelected: (String) -> Boolean,
    onToggleSelection: (String) -> Unit,
    onBack: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onSelectAll: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
): FilesScreenViews {
    val summaryView = TextView(this).apply {
        textSize = Dimens.BODY_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Colors.text)
    }
    val pathView = pathText("")
    val summaryCard = paddedPanel().apply {
        addView(summaryView, lpMatchWrap())
        addView(pathView, lpMatchWrap(dp(Dimens.SPACE_8)))
        addView(
            captionText("Les fichiers sont supprimés si l'application est désinstallée."),
            lpMatchWrap(dp(Dimens.SPACE_4))
        )
    }

    val selectionChip = chip("").apply {
        setChipColors(Colors.accentSoft, Colors.accentLine, Colors.accent)
    }
    val selectAllButton = secondaryButton("Tout").apply { setOnClickListener { onSelectAll() } }
    val exportButton = secondaryButton("Exporter").apply { setOnClickListener { onExport() } }
    val deleteButton = quietButton("Supprimer").apply { setOnClickListener { onDelete() } }
    val actionsCard = paddedPanel().apply {
        addView(
            row().apply { addView(selectionChip) },
            lpMatchWrap()
        )
        addView(
            row().apply {
                addView(selectAllButton, lpWeight(dp(Dimens.TOUCH)))
                addView(exportButton, lpWeight(dp(Dimens.TOUCH), dp(Dimens.SPACE_8)))
                addView(deleteButton, lpWeight(dp(Dimens.TOUCH), dp(Dimens.SPACE_8)))
            },
            lpMatchWrap(dp(Dimens.SPACE_8))
        )
    }

    val emptyView = emptyState("Aucun CSV pour le moment. Lance un scan : le prochain fichier apparaîtra ici.")
    val filesAdapter = FileListAdapter(this, entries, isSelected, onToggleSelection)
    val listView = ListView(this).apply {
        adapter = filesAdapter
        divider = ColorDrawable(Color.TRANSPARENT)
        dividerHeight = dp(Dimens.SPACE_8)
        selector = ColorDrawable(Color.TRANSPARENT)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, dp(Dimens.SPACE_8), 0, dp(Dimens.SPACE_8))
        setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let(onOpenFile)
        }
        setOnItemLongClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { entry -> onToggleSelection(entry.path) }
            true
        }
    }
    val listContainer = FrameLayout(this).apply {
        addView(
            listView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            emptyView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
    }
    listView.emptyView = emptyView

    val root = screenRoot().apply {
        addView(pageHeader("Fichiers CSV", "Retour", onBack), lpMatchWrap())
        addView(summaryCard, lpMatchWrap(dp(Dimens.SPACE_16)))
        addView(actionsCard, lpMatchWrap(dp(Dimens.SPACE_12)))
        addView(
            listContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    return FilesScreenViews(
        root = root,
        listView = listView,
        adapter = filesAdapter,
        summaryView = summaryView,
        pathView = pathView,
        selectionChip = selectionChip,
        selectAllButton = selectAllButton,
        exportButton = exportButton,
        deleteButton = deleteButton
    )
}

// ---------------------------------------------------------------------------
// Accès disque et mise en forme
// ---------------------------------------------------------------------------

fun Context.logDirectory(): File =
    File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ble_logs").apply { mkdirs() }

fun listCsvFiles(directory: File): List<File> =
    directory.listFiles { file -> file.extension.equals("csv", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

/** Construit les entrées d'affichage: libellés calculés une fois pour toutes. */
fun toFileEntries(files: List<File>): List<FileEntry> =
    files.map { file ->
        FileEntry(
            file = file,
            path = file.absolutePath,
            name = file.name,
            meta = "${formatBytes(file.length())} · ${formatTime(file.lastModified())}"
        )
    }

/**
 * Ne garde que les fichiers CSV encore présents et situés directement dans le
 * dossier de logs (garde-fou repris de l'implémentation d'origine).
 */
fun resolveSelectedFiles(directory: File, selectedPaths: Collection<String>): List<File> {
    val canonicalDirectory = runCatching { directory.canonicalFile }.getOrElse { directory }
    return selectedPaths
        .mapNotNull { path -> runCatching { File(path).canonicalFile }.getOrNull() }
        .filter { file ->
            file.exists() &&
                file.extension.equals("csv", ignoreCase = true) &&
                file.parentFile == canonicalDirectory
        }
        .sortedByDescending { it.lastModified() }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}

fun formatTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

fun formatClock(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

// ---------------------------------------------------------------------------
// Actions fichiers
// ---------------------------------------------------------------------------

object FileActions {

    fun export(activity: Activity, files: List<File>) {
        if (files.isEmpty()) {
            Toast.makeText(activity, "Aucun fichier sélectionné", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uris = ArrayList<Uri>(files.map {
                FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", it)
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
                clipData = ClipData.newUri(activity.contentResolver, files.first().name, uris.first())
                    .apply { uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) } }
            }
            activity.startActivity(Intent.createChooser(exportIntent, "Exporter les CSV"))
        }.onFailure {
            Log.w(TAG, "CSV export failed", it)
            Toast.makeText(activity, "Export impossible pour ces fichiers", Toast.LENGTH_SHORT).show()
        }
    }

    fun confirmDelete(activity: Activity, files: List<File>, onDeleted: (List<String>) -> Unit) {
        if (files.isEmpty()) {
            Toast.makeText(activity, "Aucun fichier sélectionné", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Supprimer les CSV ?")
            .setMessage("${files.size} fichier(s) seront supprimés du stockage local de l'app.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                val deleted = files.count { file -> runCatching { file.delete() }.getOrDefault(false) }
                val failed = files.size - deleted
                val message = if (failed == 0) {
                    "$deleted fichier(s) supprimé(s)"
                } else {
                    "$deleted supprimé(s), $failed échec(s)"
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                onDeleted(files.map { it.absolutePath })
            }
            .show()
    }
}
