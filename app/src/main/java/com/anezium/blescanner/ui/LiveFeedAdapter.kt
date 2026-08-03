package com.anezium.blescanner.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.TextView

/** Une ligne du flux live. */
class LiveLine(@JvmField val text: String, @JvmField val category: String)

/**
 * Adapter du flux live.
 *
 * [lines] est la liste partagée détenue par l'activité: elle est vidée/remplie
 * sur le thread principal puis suivie d'un [notifyDataSetChanged]. L'adapter ne
 * copie donc rien et ne crée aucune vue ni aucun drawable dans [getView]:
 * chaque ligne recyclée possède son propre [GradientDrawable] muté en place.
 */
class LiveFeedAdapter(
    private val context: Context,
    private val lines: List<LiveLine>,
    private val onLongPress: (LiveLine) -> Unit
) : BaseAdapter() {

    private val radiusPx = context.dp(Dimens.RADIUS_ROW).toFloat()
    private val strokePx = context.strokeWidthPx()
    private val paddingH = context.dp(Dimens.SPACE_12)
    private val paddingV = context.dp(Dimens.SPACE_8)
    private val lineSpacingPx = context.dp(Dimens.SPACE_4).toFloat()

    override fun getCount(): Int = lines.size

    override fun getItem(position: Int): LiveLine = lines[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: TextView
        val holder: Holder
        if (convertView == null) {
            val rowBackground = GradientDrawable().apply { cornerRadius = radiusPx }
            view = TextView(context).apply {
                textSize = Dimens.MONO_SP
                typeface = Typeface.MONOSPACE
                setTextColor(Colors.text)
                setLineSpacing(lineSpacingPx, 1.0f)
                setPadding(paddingH, paddingV, paddingH, paddingV)
                background = rowBackground
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
                setOnLongClickListener { target ->
                    (target.tag as? Holder)?.line?.let(onLongPress)
                    true
                }
            }
            holder = Holder(rowBackground)
            view.tag = holder
        } else {
            view = convertView as TextView
            holder = view.tag as Holder
        }

        val line = lines[position]
        holder.line = line
        view.text = line.text
        holder.background.setColor(CategoryPalette.fill(line.category))
        holder.background.setStroke(strokePx, CategoryPalette.stroke(line.category))
        return view
    }

    private class Holder(@JvmField val background: GradientDrawable) {
        @JvmField
        var line: LiveLine? = null
    }
}
