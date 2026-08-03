package com.anezium.blescanner.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Un fichier CSV listé. Les libellés sont calculés une seule fois. */
class FileEntry(
    @JvmField val file: File,
    @JvmField val path: String,
    @JvmField val name: String,
    @JvmField val meta: String
)

/**
 * Adapter de la liste de fichiers. Même principe que le flux live: la liste
 * [entries] est partagée avec l'activité et aucune vue/drawable n'est allouée
 * pendant le bind (le fond de ligne est muté en place selon la sélection).
 */
class FileListAdapter(
    private val context: Context,
    private val entries: List<FileEntry>,
    private val isSelected: (String) -> Boolean,
    private val onToggleSelection: (String) -> Unit
) : BaseAdapter() {

    private val radiusPx = context.dp(Dimens.RADIUS_ROW).toFloat()
    private val strokePx = context.strokeWidthPx()
    private val paddingH = context.dp(Dimens.SPACE_8)
    private val paddingV = context.dp(Dimens.SPACE_8)
    private val rowMinHeight = context.dp(Dimens.FILE_ROW)
    private val checkboxSize = context.dp(Dimens.TOUCH)

    override fun getCount(): Int = entries.size

    override fun getItem(position: Int): FileEntry = entries[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: LinearLayout
        val holder: Holder
        if (convertView == null) {
            val rowBackground = GradientDrawable().apply { cornerRadius = radiusPx }
            val checkBox = CheckBox(context).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = true
            }
            val nameView = TextView(context).apply {
                textSize = Dimens.BODY_SP
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Colors.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val metaView = TextView(context).apply {
                textSize = Dimens.CAPTION_SP
                setTextColor(Colors.muted)
                maxLines = 1
            }
            view = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                minimumHeight = rowMinHeight
                setPadding(paddingH, paddingV, paddingH, paddingV)
                background = rowBackground
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
                addView(checkBox, LinearLayout.LayoutParams(checkboxSize, checkboxSize))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(nameView, lpMatchWrap())
                        addView(metaView, lpMatchWrap(context.dp(Dimens.SPACE_4)))
                    },
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply { leftMargin = context.dp(Dimens.SPACE_8) }
                )
            }
            holder = Holder(rowBackground, checkBox, nameView, metaView)
            view.tag = holder
            // setOnClickListener ne se déclenche que sur action utilisateur:
            // pas besoin de détacher/rattacher le listener au recyclage.
            checkBox.setOnClickListener {
                holder.entry?.let { entry -> onToggleSelection(entry.path) }
            }
        } else {
            view = convertView as LinearLayout
            holder = view.tag as Holder
        }

        val entry = entries[position]
        holder.entry = entry
        holder.nameView.text = entry.name
        holder.metaView.text = entry.meta
        val selected = isSelected(entry.path)
        holder.checkBox.contentDescription = "Sélectionner ${entry.name}"
        holder.checkBox.isChecked = selected
        holder.background.setColor(if (selected) Colors.accentSoft else Colors.panel)
        holder.background.setStroke(strokePx, if (selected) Colors.accentLine else Colors.line)
        return view
    }

    private class Holder(
        @JvmField val background: GradientDrawable,
        @JvmField val checkBox: CheckBox,
        @JvmField val nameView: TextView,
        @JvmField val metaView: TextView
    ) {
        @JvmField
        var entry: FileEntry? = null
    }
}
