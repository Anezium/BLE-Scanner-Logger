package com.anezium.blescanner.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Références de vues du lecteur CSV. */
class ViewerScreenViews(
    val root: View,
    private val iBeaconChip: ToggleChip,
    private val datiChip: ToggleChip,
    private val eddystoneChip: ToggleChip,
    private val macInput: EditText,
    private val pageInfoView: TextView,
    private val prevButton: Button,
    private val contentView: TextView,
    private val contentScroll: ScrollView
) {
    fun filters(): ParserFilters =
        ParserFilters(
            iBeacon = iBeaconChip.checked,
            dati = datiChip.checked,
            eddystone = eddystoneChip.checked,
            macAddress = macInput.text?.toString().orEmpty()
        )

    fun renderPage(pageIndex: Int, rowCount: Int, content: String) {
        pageInfoView.text = "Page ${pageIndex + 1} · $rowCount lignes"
        contentView.text = content
        prevButton.setEnabledWithAlpha(pageIndex > 0)
        contentScroll.scrollTo(0, 0)
    }
}

fun Context.buildViewerScreen(
    fileName: String,
    onBack: () -> Unit,
    onFiltersChanged: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
): ViewerScreenViews {
    val iBeaconChip = toggleChip("iBeacon") { onFiltersChanged() }
    val datiChip = toggleChip("DATI") { onFiltersChanged() }
    val eddystoneChip = toggleChip("Eddystone") { onFiltersChanged() }

    val macInput = EditText(this).apply {
        hint = "AA:BB:CC:DD:EE:FF"
        setSingleLine(true)
        textSize = Dimens.BODY_SP
        typeface = Typeface.MONOSPACE
        setTextColor(Colors.text)
        setHintTextColor(Colors.muted)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        background = rounded(Colors.control, Dimens.RADIUS_ROW, Colors.line)
        setPadding(dp(Dimens.SPACE_12), 0, dp(Dimens.SPACE_12), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onFiltersChanged()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    val filterCard = paddedPanel().apply {
        addView(panelLabel("Filtres"), lpMatchWrap())
        addView(
            row().apply {
                addView(iBeaconChip.view, lpWeight(dp(Dimens.CHIP)))
                addView(datiChip.view, lpWeight(dp(Dimens.CHIP), dp(Dimens.SPACE_8)))
                addView(eddystoneChip.view, lpWeight(dp(Dimens.CHIP), dp(Dimens.SPACE_8)))
            },
            lpMatchWrap(dp(Dimens.SPACE_8))
        )
        addView(panelLabel("Adresse MAC"), lpMatchWrap(dp(Dimens.SPACE_12)))
        addView(macInput, lpMatchHeight(dp(Dimens.TOUCH), dp(Dimens.SPACE_8)))
    }

    val pageInfoView = TextView(this).apply {
        textSize = Dimens.CAPTION_SP
        setTextColor(Colors.muted)
        gravity = Gravity.CENTER_VERTICAL
    }
    val prevButton = secondaryButton("Précédent").apply { setOnClickListener { onPrevious() } }
    val nextButton = secondaryButton("Suivant").apply { setOnClickListener { onNext() } }
    val pagerRow = row().apply {
        addView(
            pageInfoView,
            LinearLayout.LayoutParams(0, dp(Dimens.TOUCH), 1f)
        )
        addView(prevButton, LinearLayout.LayoutParams(dp(92), dp(Dimens.TOUCH)))
        addView(
            nextButton,
            LinearLayout.LayoutParams(dp(92), dp(Dimens.TOUCH)).apply {
                leftMargin = dp(Dimens.SPACE_8)
            }
        )
    }

    val contentView = TextView(this).apply {
        textSize = Dimens.MONO_SP
        typeface = Typeface.MONOSPACE
        setTextColor(Colors.text)
        setLineSpacing(dp(Dimens.SPACE_4).toFloat(), 1.0f)
        setTextIsSelectable(true)
        setPadding(dp(Dimens.SPACE_12), dp(Dimens.SPACE_12), dp(Dimens.SPACE_12), dp(Dimens.SPACE_12))
    }
    val contentScroll = ScrollView(this).apply {
        background = rounded(Colors.listSurface, Dimens.RADIUS_PANEL, Colors.line)
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(contentView)
    }

    val root = screenRoot().apply {
        addView(pageHeader("Lecture CSV", "Fichiers", onBack), lpMatchWrap())
        addView(pathText(fileName, Dimens.MONO_SP), lpMatchWrap(dp(Dimens.SPACE_8)))
        addView(filterCard, lpMatchWrap(dp(Dimens.SPACE_12)))
        addView(pagerRow, lpMatchWrap(dp(Dimens.SPACE_12)))
        addView(
            contentScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = dp(Dimens.SPACE_12) }
        )
    }

    return ViewerScreenViews(
        root = root,
        iBeaconChip = iBeaconChip,
        datiChip = datiChip,
        eddystoneChip = eddystoneChip,
        macInput = macInput,
        pageInfoView = pageInfoView,
        prevButton = prevButton,
        contentView = contentView,
        contentScroll = contentScroll
    )
}
