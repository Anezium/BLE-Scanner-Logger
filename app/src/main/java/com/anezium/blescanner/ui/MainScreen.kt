package com.anezium.blescanner.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.anezium.blescanner.capture.CaptureSource

/** État affiché par la chip du header. */
enum class ScanStatus { READY, RUNNING, STOPPED }

/**
 * Ligne cochable d'une source de capture. Le fond et la case sont des
 * drawables mutables: l'état est appliqué sans réallocation.
 */
class SourceRow(
    val root: LinearLayout,
    private val checkView: TextView,
    private val titleView: TextView
) {
    fun setState(active: Boolean, enabled: Boolean) {
        (root.background as? GradientDrawable)?.apply {
            setColor(if (active) Colors.accentSoft else Colors.control)
            setStroke(root.context.strokeWidthPx(), if (active) Colors.accent else Colors.line)
        }
        (checkView.background as? GradientDrawable)?.apply {
            setColor(if (active) Colors.accent else Colors.panel)
            setStroke(root.context.strokeWidthPx(), if (active) Colors.accent else Colors.line)
        }
        checkView.setTextColor(if (active) Colors.onAccent else Color.TRANSPARENT)
        titleView.setTextColor(if (active) Colors.accent else Colors.text)
        root.setEnabledWithAlpha(enabled)
    }
}

/**
 * Références de vues de l'écran principal. Toutes les mises à jour se font
 * en place (setText / setEnabled / mutation de drawable), jamais par
 * reconstruction de l'écran.
 */
class MainScreenViews(
    val root: View,
    val liveList: ListView,
    val adapter: LiveFeedAdapter,
    private val statusChip: TextView,
    private val sourceRows: Map<CaptureSource, SourceRow>,
    private val actionButton: Button,
    private val startBackground: Drawable,
    private val stopBackground: Drawable,
    private val outputCaption: TextView,
    private val framesMetric: MetricCard,
    private val devicesMetric: MetricCard,
    private val elapsedMetric: MetricCard,
    private val emptyView: TextView,
    private val filesButton: Button
) {
    fun renderStatus(status: ScanStatus) {
        when (status) {
            ScanStatus.READY -> {
                statusChip.text = "Prêt"
                statusChip.setChipColors(Colors.successSoft, Colors.successLine, Colors.success)
            }
            ScanStatus.RUNNING -> {
                statusChip.text = "Capture en cours"
                statusChip.setChipColors(Colors.accentSoft, Colors.accentLine, Colors.accent)
            }
            ScanStatus.STOPPED -> {
                statusChip.text = "Arrêtée"
                statusChip.setChipColors(Colors.control, Colors.line, Colors.muted)
            }
        }
    }

    /**
     * Coche les sources sélectionnées et adapte la légende de sortie. Les
     * lignes sont verrouillées pendant une capture ([enabled] = false).
     */
    fun renderSources(selected: Set<CaptureSource>, enabled: Boolean) {
        sourceRows.forEach { (source, row) -> row.setState(source in selected, enabled) }
        outputCaption.text = outputCaption(selected)
    }

    fun renderAction(scanning: Boolean, canStart: Boolean) {
        if (scanning) {
            actionButton.text = "Arrêter la capture"
            actionButton.background = stopBackground
        } else {
            actionButton.text = "Démarrer la capture"
            actionButton.background = startBackground
        }
        actionButton.setTextColor(Colors.onAccent)
        actionButton.setEnabledWithAlpha(scanning || canStart)
    }

    fun renderCounters(frames: Int, devices: Int) {
        framesMetric.setValue(frames.toString())
        devicesMetric.setValue(devices.toString())
    }

    fun renderElapsed(label: String, active: Boolean) {
        elapsedMetric.setValue(label)
        elapsedMetric.setValueColor(if (active) Colors.accent else Colors.muted)
    }

    fun setEmptyText(label: String) {
        emptyView.text = label
    }

    fun renderFileCount(count: Int) {
        filesButton.text = if (count > 0) "Fichiers CSV · $count" else "Fichiers CSV"
    }

    private fun outputCaption(selected: Set<CaptureSource>): String {
        val events = CaptureSource.BLE in selected || CaptureSource.IMU in selected
        val cell = CaptureSource.CELL in selected
        return when {
            selected.isEmpty() -> "Sélectionne au moins une source."
            events && cell -> "Deux CSV par session, même identifiant: événements BLE/IMU et réseau mobile."
            cell -> "Un CSV réseau mobile par session."
            CaptureSource.BLE in selected && CaptureSource.IMU in selected ->
                "Un CSV événementiel par session, BLE et IMU dans le même fichier."
            else -> "Un CSV événementiel par session."
        }
    }
}

/**
 * Construit l'écran principal. Tient sans scroll sur ~360x800dp: seul le
 * panneau « Dernières trames » est extensible.
 */
fun Context.buildMainScreen(
    liveLines: List<LiveLine>,
    onSourceToggled: (CaptureSource) -> Unit,
    onToggleScan: () -> Unit,
    onClear: () -> Unit,
    onOpenFiles: () -> Unit,
    onCopyLine: (String) -> Unit
): MainScreenViews {
    val statusChip = chip("Prêt")

    val header = row().apply {
        addView(
            pageTitle("BLE Scanner"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(statusChip)
    }

    // --- Carte Sources -----------------------------------------------------
    val sourceRows = linkedMapOf(
        CaptureSource.BLE to sourceRow(
            CaptureSource.BLE.label,
            "Advertising BLE, iBeacon, Eddystone, DATI"
        ) { onSourceToggled(CaptureSource.BLE) },
        CaptureSource.CELL to sourceRow(
            CaptureSource.CELL.label,
            "Cellules LTE et NR, niveaux de signal"
        ) { onSourceToggled(CaptureSource.CELL) },
        CaptureSource.IMU to sourceRow(
            CaptureSource.IMU.label,
            "Accéléro, gyro, magnéto, baromètre, pas"
        ) { onSourceToggled(CaptureSource.IMU) }
    )
    val startBackground = buttonBackground(Colors.accent, Colors.accentPressed, null)
    val stopBackground = buttonBackground(Colors.danger, Colors.dangerPressed, null)
    val actionButton = primaryButton("Démarrer la capture").apply {
        textSize = Dimens.ACTION_SP
        background = startBackground
        setOnClickListener { onToggleScan() }
    }
    val outputCaption = captionText("").apply {
        gravity = Gravity.CENTER
    }

    val sourcesCard = paddedPanel().apply {
        addView(panelLabel("Sources"), lpMatchWrap())
        sourceRows.values.forEachIndexed { index, row ->
            addView(
                row.root,
                lpMatchHeight(dp(Dimens.TOUCH), dp(if (index == 0) Dimens.SPACE_8 else 6))
            )
        }
        addView(actionButton, lpMatchHeight(dp(Dimens.PRIMARY_ACTION), dp(Dimens.SPACE_12)))
        addView(outputCaption, lpMatchWrap(dp(Dimens.SPACE_8)))
    }

    // --- Métriques ---------------------------------------------------------
    val framesMetric = metricCard("trames")
    val devicesMetric = metricCard("appareils")
    val elapsedMetric = metricCard("durée", monospace = true).apply {
        setValue("00:00:00")
        setValueColor(Colors.muted)
    }
    val metricsRow = row().apply {
        addView(framesMetric.root, lpWeight(dp(Dimens.METRIC)))
        addView(devicesMetric.root, lpWeight(dp(Dimens.METRIC), dp(Dimens.SPACE_8)))
        addView(elapsedMetric.root, lpWeight(dp(Dimens.METRIC), dp(Dimens.SPACE_8)))
    }

    // --- Panneau flux live -------------------------------------------------
    val emptyView = emptyState("Appuie sur Démarrer pour voir les trames en direct.")
    val feedAdapter = LiveFeedAdapter(this, liveLines) { line -> onCopyLine(line.text) }
    val liveList = ListView(this).apply {
        adapter = feedAdapter
        divider = ColorDrawable(Color.TRANSPARENT)
        dividerHeight = dp(Dimens.SPACE_8)
        selector = ColorDrawable(Color.TRANSPARENT)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        setPadding(dp(Dimens.SPACE_12), dp(Dimens.SPACE_12), dp(Dimens.SPACE_12), dp(Dimens.SPACE_12))
        clipToPadding = false
    }
    val liveContainer = FrameLayout(this).apply {
        addView(
            liveList,
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
    liveList.emptyView = emptyView

    val livePanel = panel().apply {
        addView(
            row().apply {
                setPadding(dp(Dimens.SPACE_16), dp(Dimens.SPACE_4), dp(Dimens.SPACE_8), dp(Dimens.SPACE_4))
                addView(
                    panelTitle("Dernières trames"),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    textButton("Effacer").apply { setOnClickListener { onClear() } },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(Dimens.TOUCH)
                    )
                )
            },
            lpMatchWrap()
        )
        addView(separator(), lpMatchHeight(strokeWidthPx()))
        addView(
            liveContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    // --- Bouton fichiers ---------------------------------------------------
    val filesButton = secondaryButton("Fichiers CSV").apply {
        setOnClickListener { onOpenFiles() }
    }

    val root = screenRoot().apply {
        addView(header, lpMatchWrap())
        addView(sourcesCard, lpMatchWrap(dp(Dimens.SPACE_16)))
        addView(metricsRow, lpMatchWrap(dp(Dimens.SPACE_12)))
        addView(livePanel, lpFill(dp(Dimens.SPACE_12)))
        addView(
            filesButton,
            lpMatchHeight(dp(Dimens.SECONDARY_ACTION), dp(Dimens.SPACE_12))
        )
    }

    return MainScreenViews(
        root = root,
        liveList = liveList,
        adapter = feedAdapter,
        statusChip = statusChip,
        sourceRows = sourceRows,
        actionButton = actionButton,
        startBackground = startBackground,
        stopBackground = stopBackground,
        outputCaption = outputCaption,
        framesMetric = framesMetric,
        devicesMetric = devicesMetric,
        elapsedMetric = elapsedMetric,
        emptyView = emptyView,
        filesButton = filesButton
    )
}

/** Ligne « case + titre + description » cochable sur toute sa largeur. */
private fun Context.sourceRow(title: String, description: String, onClick: () -> Unit): SourceRow {
    val checkView = TextView(this).apply {
        text = "✓"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = rounded(Colors.panel, 6, Colors.line)
    }
    val titleView = TextView(this).apply {
        text = title
        textSize = Dimens.BODY_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Colors.text)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val descriptionView = TextView(this).apply {
        text = description
        textSize = Dimens.PATH_SP
        setTextColor(Colors.muted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(titleView, lpMatchWrap())
        addView(descriptionView, lpMatchWrap())
    }
    val root = row().apply {
        isClickable = true
        isFocusable = true
        setPadding(dp(Dimens.SPACE_12), 0, dp(Dimens.SPACE_12), 0)
        background = rounded(Colors.control, Dimens.RADIUS_ROW, Colors.line)
        addView(checkView, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(Dimens.SPACE_12)
            }
        )
        setOnClickListener { onClick() }
    }
    return SourceRow(root, checkView, titleView)
}
