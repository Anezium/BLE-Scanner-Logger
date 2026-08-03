package com.anezium.blescanner.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Mode de scan sélectionné par l'utilisateur. */
enum class ScanMode { BLE, CELL }

/** État affiché par la chip du header. */
enum class ScanStatus { READY, RUNNING, STOPPED }

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
    private val bleSegment: Button,
    private val cellSegment: Button,
    private val actionButton: Button,
    private val startBackground: Drawable,
    private val stopBackground: Drawable,
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
                statusChip.text = "Scan en cours"
                statusChip.setChipColors(Colors.accentSoft, Colors.accentLine, Colors.accent)
            }
            ScanStatus.STOPPED -> {
                statusChip.text = "Arrêté"
                statusChip.setChipColors(Colors.control, Colors.line, Colors.muted)
            }
        }
    }

    fun renderMode(mode: ScanMode, enabled: Boolean) {
        bleSegment.setSegmentActive(mode == ScanMode.BLE)
        cellSegment.setSegmentActive(mode == ScanMode.CELL)
        bleSegment.setEnabledWithAlpha(enabled)
        cellSegment.setEnabledWithAlpha(enabled)
    }

    fun renderAction(scanning: Boolean) {
        if (scanning) {
            actionButton.text = "Arrêter le scan"
            actionButton.background = stopBackground
        } else {
            actionButton.text = "Démarrer le scan"
            actionButton.background = startBackground
        }
        actionButton.setTextColor(Colors.onAccent)
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
}

/**
 * Construit l'écran principal. Tient sans scroll sur ~360x800dp: seul le
 * panneau « Dernières trames » est extensible.
 */
fun Context.buildMainScreen(
    liveLines: List<LiveLine>,
    onModeSelected: (ScanMode) -> Unit,
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

    // --- Carte Scanner -----------------------------------------------------
    val bleSegment = segmentButton("Bluetooth").apply {
        setOnClickListener { onModeSelected(ScanMode.BLE) }
    }
    val cellSegment = segmentButton("Réseau mobile").apply {
        setOnClickListener { onModeSelected(ScanMode.CELL) }
    }
    val startBackground = buttonBackground(Colors.accent, Colors.accentPressed, null)
    val stopBackground = buttonBackground(Colors.danger, Colors.dangerPressed, null)
    val actionButton = primaryButton("Démarrer le scan").apply {
        textSize = Dimens.ACTION_SP
        background = startBackground
        setOnClickListener { onToggleScan() }
    }

    val scannerCard = paddedPanel().apply {
        addView(panelLabel("Scanner"), lpMatchWrap())
        addView(
            row().apply {
                addView(bleSegment, lpWeight(dp(Dimens.TOUCH)))
                addView(cellSegment, lpWeight(dp(Dimens.TOUCH), dp(Dimens.SPACE_8)))
            },
            lpMatchWrap(dp(Dimens.SPACE_8))
        )
        addView(actionButton, lpMatchHeight(dp(Dimens.PRIMARY_ACTION), dp(Dimens.SPACE_12)))
        addView(
            captionText("Chaque trame reçue est enregistrée en CSV.").apply {
                gravity = Gravity.CENTER
            },
            lpMatchWrap(dp(Dimens.SPACE_8))
        )
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
        addView(scannerCard, lpMatchWrap(dp(Dimens.SPACE_16)))
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
        bleSegment = bleSegment,
        cellSegment = cellSegment,
        actionButton = actionButton,
        startBackground = startBackground,
        stopBackground = stopBackground,
        framesMetric = framesMetric,
        devicesMetric = devicesMetric,
        elapsedMetric = elapsedMetric,
        emptyView = emptyView,
        filesButton = filesButton
    )
}
