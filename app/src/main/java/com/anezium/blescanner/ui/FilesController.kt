package com.anezium.blescanner.ui

import android.app.Activity
import android.view.View
import android.widget.Toast
import java.io.File

/**
 * État et comportements de l'écran Fichiers: liste, sélection multiple, export
 * et suppression. Toutes les mises à jour se font en place (rafraîchissement de
 * l'adapter + `setText`/`setEnabled`), jamais par reconstruction de l'écran.
 */
class FilesController(
    private val activity: Activity,
    private val isScanning: () -> Boolean,
    private val onBack: () -> Unit,
    private val onOpenFile: (File) -> Unit
) {
    /** Liste partagée avec l'adapter. */
    private val entries = ArrayList<FileEntry>()
    private val selected = linkedSetOf<String>()
    private var views: FilesScreenViews? = null

    fun csvCount(): Int = listCsvFiles(activity.logDirectory()).size

    fun buildScreen(): View {
        val built = activity.buildFilesScreen(
            entries = entries,
            isSelected = { path -> selected.contains(path) },
            onToggleSelection = ::toggleSelection,
            onBack = onBack,
            onOpenFile = { entry -> onOpenFile(entry.file) },
            onSelectAll = ::toggleAll,
            onExport = ::export,
            onDelete = ::delete
        )
        views = built
        return built.root
    }

    /** Libère les références de vues quand l'écran n'est plus affiché. */
    fun release() {
        views = null
    }

    fun reload() {
        val target = views ?: return
        val directory = activity.logDirectory()
        val files = listCsvFiles(directory)
        entries.clear()
        entries.addAll(toFileEntries(files))
        selected.retainAll(entries.mapTo(HashSet<String>()) { it.path })
        target.renderPath(directory.absolutePath)
        target.renderSummary(
            files.size,
            files.sumOf { it.length() },
            files.maxOfOrNull { it.lastModified() } ?: 0L
        )
        target.refreshRows()
        renderSelection()
    }

    fun renderSelection() {
        views?.renderSelection(selected.size, entries.size, isScanning())
    }

    private fun toggleSelection(path: String) {
        if (!selected.add(path)) selected.remove(path)
        views?.refreshRows()
        renderSelection()
    }

    private fun toggleAll() {
        if (entries.isEmpty()) return
        val selectAll = selected.size != entries.size
        selected.clear()
        if (selectAll) entries.forEach { entry -> selected.add(entry.path) }
        views?.refreshRows()
        renderSelection()
    }

    private fun export() {
        val files = resolveSelectedFiles(activity.logDirectory(), selected)
        if (files.isEmpty()) renderSelection()
        FileActions.export(activity, files)
    }

    private fun delete() {
        if (isScanning()) {
            Toast.makeText(activity, "Stoppe le scan avant de supprimer des CSV", Toast.LENGTH_SHORT)
                .show()
            renderSelection()
            return
        }
        val files = resolveSelectedFiles(activity.logDirectory(), selected)
        if (files.isEmpty()) {
            renderSelection()
            return
        }
        FileActions.confirmDelete(activity, files) { deletedPaths ->
            selected.removeAll(deletedPaths.toSet())
            reload()
        }
    }
}
