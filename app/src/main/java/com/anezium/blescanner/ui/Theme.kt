package com.anezium.blescanner.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * Palette unique de l'application. Thème clair uniquement.
 */
object Colors {
    // Surfaces
    val surface = Color.rgb(246, 247, 244) // #F6F7F4
    val panel = Color.rgb(253, 253, 250) // #FDFDFA
    val listSurface = Color.rgb(250, 251, 248)
    val control = Color.rgb(237, 240, 235)
    val controlPressed = Color.rgb(226, 231, 224)
    val line = Color.rgb(214, 220, 211) // #D6DCD3

    // Texte
    val text = Color.rgb(28, 35, 31) // #1C231F
    val muted = Color.rgb(99, 111, 103) // #636F67

    // Accent
    val accent = Color.rgb(20, 123, 108) // #147B6C
    val accentPressed = Color.rgb(16, 104, 92)
    val accentSoft = Color.rgb(223, 242, 237)
    val accentLine = Color.rgb(150, 211, 199)
    val onAccent = Color.rgb(246, 252, 249)

    // Arrêter (rouge réservé à cette seule action)
    val danger = Color.rgb(179, 64, 47) // #B3402F
    val dangerPressed = Color.rgb(150, 52, 38)

    // État prêt
    val success = Color.rgb(60, 113, 78)
    val successSoft = Color.rgb(231, 242, 231)
    val successLine = Color.rgb(174, 211, 180)

    // Catégories de trames
    val bleSoft = Color.rgb(239, 242, 239)
    val bleLine = Color.rgb(194, 202, 194)
    val iBeaconSoft = Color.rgb(235, 242, 252)
    val iBeaconLine = Color.rgb(140, 175, 224)
    val eddystoneSoft = Color.rgb(252, 245, 229)
    val eddystoneLine = Color.rgb(222, 183, 112)
    val telemetrySoft = Color.rgb(240, 237, 251)
    val telemetryLine = Color.rgb(170, 154, 220)
    val datiSoft = Color.rgb(232, 244, 236)
    val datiLine = Color.rgb(133, 193, 153)
    val cellSoft = Color.rgb(230, 241, 245)
    val cellLine = Color.rgb(146, 186, 201)
    val systemSoft = Color.rgb(246, 241, 235)
    val systemLine = Color.rgb(214, 200, 182)
}

/**
 * Grille d'espacement 4dp, rayons, hauteurs tactiles et tailles de texte.
 */
object Dimens {
    const val SPACE_4 = 4
    const val SPACE_8 = 8
    const val SPACE_12 = 12
    const val SPACE_16 = 16
    const val SPACE_20 = 20
    const val SPACE_24 = 24

    const val RADIUS_PANEL = 12
    const val RADIUS_ROW = 10
    const val RADIUS_PILL = 999

    /** Hauteur tactile minimale. */
    const val TOUCH = 48

    /** Gros bouton d'action de l'écran principal. */
    const val PRIMARY_ACTION = 64

    /** Bouton secondaire pleine largeur (bas d'écran). */
    const val SECONDARY_ACTION = 52

    /** Carte de métrique. */
    const val METRIC = 74

    /** Chip toggle du lecteur CSV. */
    const val CHIP = 40

    /** Ligne de la liste de fichiers. */
    const val FILE_ROW = 56

    const val TITLE_SP = 22f
    const val ACTION_SP = 18f
    const val BODY_SP = 14f
    const val CAPTION_SP = 12f
    const val MONO_SP = 12f
    const val PATH_SP = 11f
}

/** Alpha appliqué aux éléments désactivés. */
const val DISABLED_ALPHA = 0.45f

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** Épaisseur de contour: toujours au moins 1 pixel physique. */
fun Context.strokeWidthPx(): Int = dp(1).coerceAtLeast(1)

fun Context.rounded(fill: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(strokeWidthPx(), strokeColor)
    }

fun Context.buttonBackground(
    fill: Int,
    pressed: Int,
    strokeColor: Int?,
    radiusDp: Int = Dimens.RADIUS_ROW
): StateListDrawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radiusDp, strokeColor))
        addState(IntArray(0), rounded(fill, radiusDp, strokeColor))
    }

/**
 * Couleurs par catégorie de trame. Retourne des `Int`: aucune allocation,
 * utilisable dans les chemins chauds (bind de ligne du flux live).
 */
object CategoryPalette {
    fun fill(category: String): Int = when (category) {
        "ibeacon" -> Colors.iBeaconSoft
        "eddystone_uid" -> Colors.eddystoneSoft
        "eddystone_tlm" -> Colors.telemetrySoft
        "dati" -> Colors.datiSoft
        "cell" -> Colors.cellSoft
        "system" -> Colors.systemSoft
        else -> Colors.bleSoft
    }

    fun stroke(category: String): Int = when (category) {
        "ibeacon" -> Colors.iBeaconLine
        "eddystone_uid" -> Colors.eddystoneLine
        "eddystone_tlm" -> Colors.telemetryLine
        "dati" -> Colors.datiLine
        "cell" -> Colors.cellLine
        "system" -> Colors.systemLine
        else -> Colors.bleLine
    }
}
