package com.anezium.blescanner.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// ---------------------------------------------------------------------------
// Paramètres de layout (les valeurs passées sont déjà en pixels)
// ---------------------------------------------------------------------------

fun lpMatchWrap(topMarginPx: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = topMarginPx }

fun lpMatchHeight(heightPx: Int, topMarginPx: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
        .apply { topMargin = topMarginPx }

/** Prend tout l'espace vertical restant. */
fun lpFill(topMarginPx: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        .apply { topMargin = topMarginPx }

fun lpWeight(heightPx: Int, leftMarginPx: Int = 0, weight: Float = 1f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, heightPx, weight).apply { leftMargin = leftMarginPx }

// ---------------------------------------------------------------------------
// Texte
// ---------------------------------------------------------------------------

fun Context.pageTitle(label: String): TextView =
    TextView(this).apply {
        text = label
        textSize = Dimens.TITLE_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Colors.text)
    }

/** Petit titre de section, au-dessus du contenu d'un panneau. */
fun Context.panelLabel(label: String): TextView =
    TextView(this).apply {
        text = label
        textSize = Dimens.CAPTION_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Colors.muted)
    }

fun Context.panelTitle(label: String): TextView =
    TextView(this).apply {
        text = label
        textSize = Dimens.BODY_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Colors.text)
    }

fun Context.captionText(label: String): TextView =
    TextView(this).apply {
        text = label
        textSize = Dimens.CAPTION_SP
        setTextColor(Colors.muted)
    }

/** Chemin ou nom de fichier: monospace, tronqué au milieu. */
fun Context.pathText(label: String, sizeSp: Float = Dimens.PATH_SP): TextView =
    TextView(this).apply {
        text = label
        textSize = sizeSp
        typeface = Typeface.MONOSPACE
        setTextColor(Colors.text)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }

fun Context.emptyState(label: String): TextView =
    TextView(this).apply {
        text = label
        textSize = Dimens.BODY_SP
        setTextColor(Colors.muted)
        gravity = Gravity.CENTER
        setPadding(dp(Dimens.SPACE_20), dp(Dimens.SPACE_24), dp(Dimens.SPACE_20), dp(Dimens.SPACE_24))
    }

// ---------------------------------------------------------------------------
// Conteneurs
// ---------------------------------------------------------------------------

fun Context.panel(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Colors.panel, Dimens.RADIUS_PANEL, Colors.line)
    }

fun Context.paddedPanel(): LinearLayout =
    panel().apply {
        val h = dp(Dimens.SPACE_16)
        val v = dp(Dimens.SPACE_12)
        setPadding(h, v, h, v)
    }

fun Context.row(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

fun Context.separator(): View =
    View(this).apply {
        setBackgroundColor(Colors.line)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            strokeWidthPx()
        )
    }

// ---------------------------------------------------------------------------
// Boutons
// ---------------------------------------------------------------------------

fun Context.styledButton(
    label: String,
    fill: Int,
    pressed: Int,
    textColor: Int,
    strokeColor: Int?,
    textSizeSp: Float = Dimens.BODY_SP
): Button =
    Button(this).apply {
        text = label
        textSize = textSizeSp
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        setTextColor(textColor)
        background = buttonBackground(fill, pressed, strokeColor)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(Dimens.SPACE_8), 0, dp(Dimens.SPACE_8), 0)
    }

fun Context.primaryButton(label: String): Button =
    styledButton(label, Colors.accent, Colors.accentPressed, Colors.onAccent, null)

fun Context.secondaryButton(label: String): Button =
    styledButton(label, Colors.control, Colors.controlPressed, Colors.text, Colors.line)

/** Action la moins prononcée d'une barre (texte atténué). */
fun Context.quietButton(label: String): Button =
    styledButton(label, Colors.control, Colors.controlPressed, Colors.muted, Colors.line)

/** Bouton plat posé sur un panneau (ex. « Effacer »). */
fun Context.textButton(label: String): Button =
    styledButton(label, Colors.panel, Colors.controlPressed, Colors.accent, null, Dimens.CAPTION_SP)

fun View.setEnabledWithAlpha(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else DISABLED_ALPHA
}

// ---------------------------------------------------------------------------
// Chips
// ---------------------------------------------------------------------------

/** Chip d'état non interactive (header de l'écran principal, sélection fichiers). */
fun Context.chip(label: String): TextView =
    TextView(this).apply {
        text = label
        textSize = Dimens.CAPTION_SP
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Colors.success)
        background = rounded(Colors.successSoft, Dimens.RADIUS_PILL, Colors.successLine)
        setPadding(dp(Dimens.SPACE_12), dp(Dimens.SPACE_4), dp(Dimens.SPACE_12), dp(Dimens.SPACE_4))
    }

/**
 * Recolore une chip en mutant son drawable existant (aucune allocation).
 * À n'utiliser que sur une vue créée par [chip].
 */
fun TextView.setChipColors(fill: Int, strokeColor: Int, textColor: Int) {
    setTextColor(textColor)
    val drawable = background as? GradientDrawable ?: return
    drawable.setColor(fill)
    drawable.setStroke(context.strokeWidthPx(), strokeColor)
}

/** Chip cochable du lecteur CSV. */
class ToggleChip(val view: TextView) {
    var checked: Boolean = false
        private set

    fun setState(value: Boolean) {
        checked = value
        view.setChipColors(
            if (value) Colors.accentSoft else Colors.control,
            if (value) Colors.accent else Colors.line,
            if (value) Colors.accent else Colors.muted
        )
    }
}

fun Context.toggleChip(label: String, onToggle: (Boolean) -> Unit): ToggleChip {
    val view = chip(label).apply {
        textSize = Dimens.BODY_SP
        isClickable = true
        isFocusable = true
        setPadding(dp(Dimens.SPACE_8), 0, dp(Dimens.SPACE_8), 0)
    }
    val toggle = ToggleChip(view)
    toggle.setState(false)
    view.setOnClickListener {
        toggle.setState(!toggle.checked)
        onToggle(toggle.checked)
    }
    return toggle
}

// ---------------------------------------------------------------------------
// Segments (sélecteur de mode)
// ---------------------------------------------------------------------------

fun Context.segmentButton(label: String): Button =
    Button(this).apply {
        text = label
        textSize = Dimens.BODY_SP
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(Dimens.SPACE_4), 0, dp(Dimens.SPACE_4), 0)
        // Fond mutable: l'état actif est appliqué sans réallouer de drawable.
        background = rounded(Colors.control, Dimens.RADIUS_ROW, Colors.line)
        setTextColor(Colors.muted)
    }

fun Button.setSegmentActive(active: Boolean) {
    val drawable = background as? GradientDrawable ?: return
    drawable.setColor(if (active) Colors.accentSoft else Colors.control)
    drawable.setStroke(context.strokeWidthPx(), if (active) Colors.accent else Colors.line)
    setTextColor(if (active) Colors.accent else Colors.muted)
}

// ---------------------------------------------------------------------------
// Carte de métrique
// ---------------------------------------------------------------------------

class MetricCard(val root: LinearLayout, private val valueView: TextView) {
    fun setValue(value: String) {
        valueView.text = value
    }

    fun setValueColor(color: Int) {
        valueView.setTextColor(color)
    }
}

fun Context.metricCard(label: String, monospace: Boolean = false): MetricCard {
    val valueView = TextView(this).apply {
        text = "0"
        textSize = if (monospace) 16f else 20f
        typeface = if (monospace) {
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        } else {
            Typeface.DEFAULT_BOLD
        }
        setTextColor(Colors.text)
        gravity = Gravity.CENTER
        maxLines = 1
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(Colors.panel, Dimens.RADIUS_PANEL, Colors.line)
        setPadding(dp(Dimens.SPACE_4), 0, dp(Dimens.SPACE_4), 0)
        addView(valueView, lpMatchWrap())
        addView(
            captionText(label).apply { gravity = Gravity.CENTER },
            lpMatchWrap(dp(Dimens.SPACE_4))
        )
    }
    return MetricCard(root, valueView)
}

// ---------------------------------------------------------------------------
// Header de page
// ---------------------------------------------------------------------------

fun Context.pageHeader(title: String, backLabel: String, onBack: () -> Unit): View =
    row().apply {
        addView(
            pageTitle(title),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            secondaryButton(backLabel).apply { setOnClickListener { onBack() } },
            LinearLayout.LayoutParams(dp(104), dp(Dimens.TOUCH))
        )
    }

// ---------------------------------------------------------------------------
// Racine d'écran + insets système
// ---------------------------------------------------------------------------

fun Context.screenRoot(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Colors.surface)
        applyScreenPadding()
    }

fun View.applyScreenPadding() {
    val horizontal = context.dp(Dimens.SPACE_16)
    val top = context.dp(Dimens.SPACE_12)
    val bottom = context.dp(Dimens.SPACE_16)
    val fallbackTop = context.dp(Dimens.SPACE_24)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        setOnApplyWindowInsetsListener { target, insets ->
            // [gauche, haut, droite, bas] — jamais `android.graphics.Insets` ici:
            // cette classe n'existe qu'à partir d'Android 10 alors que minSdk = 26.
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SystemBarsApi30.of(insets)
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            target.setPadding(
                horizontal + bars[0],
                top + bars[1],
                horizontal + bars[2],
                bottom + bars[3]
            )
            insets
        }
        requestApplyInsets()
    } else {
        setPadding(horizontal, fallbackTop, horizontal, bottom)
    }
}

/** Isole l'API `WindowInsets.getInsets` (Android 11+) dans sa propre classe. */
private object SystemBarsApi30 {
    fun of(insets: WindowInsets): IntArray {
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        return intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
    }
}
