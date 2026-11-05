package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.zain.znkeyboard.constants.ImeColors

/**
 * All the candidates, laid over the keyboard's keys when the bar's ▾ is tapped: rows fill the width
 * and the list scrolls. Built each time it opens, which happens on a tap, not while typing.
 */
class PinyinCandidatePanel(context: Context) : ScrollView(context) {
    interface Callback {
        fun onPinyinCandidateSelected(index: Int)
        fun onPinyinCandidateLongPressed(index: Int)
    }

    var callback: Callback? = null

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(8))
    }
    private val paint = Paint().apply { textSize = context.resources.displayMetrics.scaledDensity * TEXT_SP }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        visibility = View.GONE
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun show(candidates: List<String>) {
        setBackgroundColor(ImeColors.BACKGROUND)
        rows.removeAllViews()
        val available = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - rows.paddingLeft - rows.paddingRight
        var row: LinearLayout? = null
        var used = 0
        candidates.forEachIndexed { index, text ->
            val cell = (paint.measureText(text).toInt() + context.dp(CELL_PADDING_DP) * 2)
                .coerceAtLeast(context.dp(CELL_MIN_WIDTH_DP))
            val current = row?.takeIf { used + cell <= available } ?: newRow().also {
                row = it
                used = 0
            }
            current.addView(cell(text, index), LinearLayout.LayoutParams(cell, LayoutParams.MATCH_PARENT, cell.toFloat()))
            used += cell
        }
        scrollTo(0, 0)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun newRow() = LinearLayout(context).also {
        it.orientation = LinearLayout.HORIZONTAL
        rows.addView(it, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(ROW_HEIGHT_DP)))
    }

    private fun cell(text: String, index: Int) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = TEXT_SP
        includeFontPadding = false
        maxLines = 1
        setTextColor(ImeColors.TEXT)
        typeface = if (index == 0) FIRST_TYPEFACE else REGULAR_TYPEFACE
        setOnClickListener { callback?.onPinyinCandidateSelected(index) }
        setOnLongClickListener {
            callback?.onPinyinCandidateLongPressed(index)
            true
        }
    }

    private companion object {
        const val TEXT_SP = 22f
        const val ROW_HEIGHT_DP = 52
        const val CELL_PADDING_DP = 12
        const val CELL_MIN_WIDTH_DP = 56
        val FIRST_TYPEFACE: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        val REGULAR_TYPEFACE: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    }
}
