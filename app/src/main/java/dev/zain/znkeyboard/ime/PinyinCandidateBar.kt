package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.constants.ImeColors

/**
 * Strip laid over the keyboard's toolbar row showing, while composing, the typed pinyin and a
 * scrollable row of candidates (or predictions after a commit). It also hosts the target-language
 * choices opened from the round language key. Hidden when it has nothing to show, so the toolbar
 * is usable and the keyboard never changes height. While it shows predictions it pins 润色 | 翻译
 * on the right, since it covers the toolbar keys. Candidate views are pooled so typing does not
 * allocate.
 */
class PinyinCandidateBar(context: Context) : LinearLayout(context) {
    interface Callback {
        fun onPinyinCandidateSelected(index: Int)
        fun onTranslateLanguageSelected(index: Int)
        fun onAgentPolishOnlyRequested()
        fun onAgentRewriteRequested()
        fun onTranslateLanguageMenuRequested()
    }

    /** The pinned 润色 | 翻译 (code) buttons. */
    class Tools(val polishLabel: String, val translateLabel: String, val languageCode: String, val enabled: Boolean)

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
    private val polishButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setPadding(context.dp(14), 0, context.dp(14), 0)
        setOnClickListener { callback?.onAgentPolishOnlyRequested() }
    }
    private val translateLabel = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setPadding(context.dp(14), 0, context.dp(6), 0)
    }
    private val languageBadge = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        setTextColor(ImeColors.ON_TRANSLATE_BADGE)
        setPadding(context.dp(9), 0, context.dp(8), 0)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(13f)
            setColor(ImeColors.TRANSLATE_BADGE)
        }
        setOnClickListener { callback?.onTranslateLanguageMenuRequested() }
    }
    private val translateButton = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, context.dp(4), 0)
        addView(translateLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(languageBadge, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(26)))
        setOnClickListener { callback?.onAgentRewriteRequested() }
        setOnLongClickListener {
            callback?.onTranslateLanguageMenuRequested()
            true
        }
    }
    private val toolsRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(6), 0, context.dp(8), 0)
        addView(polishButton, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(34)))
        addView(
            translateButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(34)).apply { marginStart = context.dp(6) },
        )
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
        addView(toolsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        toolsRow.visibility = View.GONE
        applyColors()
    }

    fun applyColors() {
        setBackgroundColor(ImeColors.BACKGROUND)
        composingLabel.setTextColor(ImeColors.DISABLED_TEXT)
        polishButton.setTextColor(ImeColors.TEXT)
        translateLabel.setTextColor(ImeColors.TEXT)
        polishButton.background = pill()
        translateButton.background = pill()
        candidateViews.forEachIndexed { index, view -> styleCandidate(view, index == highlightedIndex) }
    }

    val isChoosingLanguage: Boolean get() = choosingLanguage

    /** [tools] pins 润色 | 翻译 on the right; pass it only while showing predictions. */
    fun render(composing: String, candidates: List<String>, tools: Tools? = null) {
        choosingLanguage = false
        highlightedIndex = 0
        showTools(tools)
        show(composing, candidates)
    }

    private fun showTools(tools: Tools?) {
        if (tools == null) {
            toolsRow.visibility = View.GONE
            return
        }
        toolsRow.visibility = View.VISIBLE
        polishButton.text = tools.polishLabel
        translateLabel.text = tools.translateLabel
        languageBadge.text = "${tools.languageCode} ▾"
        val alpha = if (tools.enabled) 1f else DISABLED_ALPHA
        polishButton.isEnabled = tools.enabled
        translateButton.isEnabled = tools.enabled
        polishButton.alpha = alpha
        translateButton.alpha = alpha
    }

    private fun pill() = GradientDrawable().apply {
        cornerRadius = context.dp(17f)
        setColor(ImeColors.FUNCTION)
    }

    /** Lists the target languages (as codes) in place of candidates until one is picked. */
    fun renderLanguageChoices(codes: List<String>, selectedIndex: Int) {
        choosingLanguage = true
        highlightedIndex = selectedIndex
        showTools(null)
        show("翻译成", codes)
    }

    private fun show(label: String, items: List<String>) {
        visibility = if (label.isEmpty() && items.isEmpty()) View.GONE else View.VISIBLE
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

    private companion object {
        const val DISABLED_ALPHA = 0.45f
    }
}
