package dev.zain.znkeyboard.ime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.constants.ImeColors

/**
 * Strip above the keys showing, while composing, the typed pinyin and a scrollable row of
 * candidates. It also hosts the target-language choices opened from the round language key.
 * Candidate views are pooled so typing does not allocate.
 */
class PinyinCandidateBar(context: Context) : LinearLayout(context) {
    interface Callback {
        fun onPinyinCandidateSelected(index: Int)
        fun onTranslateLanguageSelected(index: Int)
    }

    var callback: Callback? = null

    private val composingLabel = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = 13f
        setPadding(context.dp(12), 0, context.dp(8), 0)
        maxLines = 1
    }
    private val candidateRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(candidateRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val candidateViews = ArrayList<TextView>()
    private var choosingLanguage = false
    // Highlighted item: the first candidate (what space commits) or the current language.
    private var highlightedIndex = 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(composingLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        applyColors()
    }

    fun applyColors() {
        setBackgroundColor(ImeColors.BACKGROUND)
        composingLabel.setTextColor(ImeColors.DISABLED_TEXT)
        candidateViews.forEachIndexed { index, view -> styleCandidate(view, index == highlightedIndex) }
    }

    val isChoosingLanguage: Boolean get() = choosingLanguage

    fun render(composing: String, candidates: List<String>) {
        choosingLanguage = false
        highlightedIndex = 0
        show(composing, candidates)
    }

    /** Lists the target languages (as codes) in place of candidates until one is picked. */
    fun renderLanguageChoices(codes: List<String>, selectedIndex: Int) {
        choosingLanguage = true
        highlightedIndex = selectedIndex
        show("翻译成", codes)
    }

    private fun show(label: String, items: List<String>) {
        composingLabel.text = label
        composingLabel.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        while (candidateViews.size < items.size) {
            val index = candidateViews.size
            val view = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 19f
                setPadding(context.dp(12), 0, context.dp(12), 0)
                setOnClickListener {
                    if (choosingLanguage) {
                        callback?.onTranslateLanguageSelected(index)
                    } else {
                        callback?.onPinyinCandidateSelected(index)
                    }
                }
            }
            candidateViews += view
            candidateRow.addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        candidateViews.forEachIndexed { index, view ->
            styleCandidate(view, index == highlightedIndex)
            if (index < items.size) {
                view.text = items[index]
                view.visibility = View.VISIBLE
            } else {
                view.visibility = View.GONE
            }
        }
        scroller.scrollTo(0, 0)
    }

    private fun styleCandidate(view: TextView, highlighted: Boolean) {
        view.setTextColor(if (highlighted) ImeColors.ACCENT else ImeColors.TEXT)
    }
}
