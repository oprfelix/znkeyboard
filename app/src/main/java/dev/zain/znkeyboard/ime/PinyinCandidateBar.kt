package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.constants.ImeColors

/**
 * Strip laid over the keyboard's toolbar row showing, while composing, the typed pinyin in small
 * type on top and below it a scrollable row of candidates only (or predictions after a commit). It also hosts the target-language
 * choices opened from the round language key. Hidden when it has nothing to show, so the toolbar
 * is usable and the keyboard never changes height. While it shows predictions it pins 润色 | 翻译
 * on the right, since it covers the toolbar keys. Candidate views are pooled so typing does not
 * allocate.
 */
class PinyinCandidateBar(context: Context) : LinearLayout(context) {
    interface Callback {
        fun onPinyinCandidateSelected(index: Int)
        /** Long press: forget what was learned about this candidate. */
        fun onPinyinCandidateLongPressed(index: Int) {}
        /** The 改词 button: pick a word of the sentence to swap, or leave that. */
        fun onPinyinFixRequested() {}
        /** The ▾ button: show every candidate over the keys, or close that. */
        fun onPinyinExpandRequested() {}
        fun onTranslateLanguageSelected(index: Int)
        fun onAgentPolishOnlyRequested()
        fun onAgentRewriteRequested()
        fun onTranslateLanguageMenuRequested()
    }

    /** The pinned 润色 | 翻译 (code) buttons. */
    class Tools(val polishLabel: String, val translateLabel: String, val languageCode: String, val enabled: Boolean)

    var callback: Callback? = null

    // The pinyin being typed, small, above the candidates; a long sentence keeps only its end in view.
    private val composingLabel = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textSize = COMPOSING_TEXT_SP
        includeFontPadding = false
        setPadding(context.dp(14), context.dp(3), context.dp(14), 0)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
    }
    // Thin lines between candidates mark where each one's tap area ends, as on Gboard.
    private val candidateRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        showDividers = SHOW_DIVIDER_MIDDLE
        dividerPadding = context.dp(12)
    }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(candidateRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val fixButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setPadding(context.dp(14), 0, context.dp(14), 0)
        visibility = View.GONE
        setOnClickListener { callback?.onPinyinFixRequested() }
    }
    private val expandButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = EXPAND_TEXT_SP
        visibility = View.GONE
        setOnClickListener { callback?.onPinyinExpandRequested() }
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
    /** Candidates (or predictions) and, after a commit, the pinned 润色 | 翻译. */
    private val mainRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val candidateViews = ArrayList<TextView>()
    private var choosingLanguage = false
    // Highlighted item: the first candidate (what space commits) or the current language.
    private var highlightedIndex = 0

    init {
        orientation = VERTICAL
        addView(composingLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(mainRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        mainRow.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        mainRow.addView(
            fixButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(34)).apply { marginStart = context.dp(4) },
        )
        mainRow.addView(
            expandButton,
            LayoutParams(context.dp(EXPAND_WIDTH_DP), context.dp(34)).apply {
                marginStart = context.dp(6)
                marginEnd = context.dp(8)
            },
        )
        mainRow.addView(toolsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        toolsRow.visibility = View.GONE
        applyColors()
    }

    fun applyColors() {
        setBackgroundColor(ImeColors.BACKGROUND)
        expandButton.setTextColor(ImeColors.TEXT)
        expandButton.background = pill()
        fixButton.setTextColor(ImeColors.TEXT)
        fixButton.background = pill()
        candidateRow.dividerDrawable = GradientDrawable().apply {
            setColor(ImeColors.DIVIDER)
            setSize(context.dp(1), 0)
        }
        composingLabel.setTextColor(ImeColors.DISABLED_TEXT)
        polishButton.setTextColor(ImeColors.TEXT)
        translateLabel.setTextColor(ImeColors.TEXT)
        polishButton.background = pill()
        translateButton.background = pill()
        candidateViews.forEachIndexed { index, view -> styleCandidate(view, index == highlightedIndex) }
    }

    val isChoosingLanguage: Boolean get() = choosingLanguage

    /**
     * [tools] pins 润色 | 翻译 on the right; pass it only while showing predictions. [fixLabel] shows
     * the 改词 button and [expandLabel] the ▾ one; [highlighted] is the item in bold, -1 for none
     * while choosing what to fix.
     */
    fun render(
        composing: String,
        candidates: List<String>,
        tools: Tools? = null,
        fixLabel: String? = null,
        expandLabel: String? = null,
        highlighted: Int = 0,
    ) {
        choosingLanguage = false
        highlightedIndex = highlighted
        showTools(tools)
        fixButton.text = fixLabel
        fixButton.visibility = if (fixLabel == null) View.GONE else View.VISIBLE
        expandButton.text = expandLabel
        expandButton.visibility = if (expandLabel == null) View.GONE else View.VISIBLE
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
        fixButton.visibility = View.GONE
        expandButton.visibility = View.GONE
        show("翻译为", codes)
    }

    private fun show(label: String, items: List<String>) {
        visibility = if (label.isEmpty() && items.isEmpty()) View.GONE else View.VISIBLE
        composingLabel.text = label
        composingLabel.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        while (candidateViews.size < items.size) {
            val index = candidateViews.size
            val view = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = CANDIDATE_TEXT_SP
                includeFontPadding = false
                minWidth = context.dp(CANDIDATE_MIN_WIDTH_DP)
                setPadding(context.dp(14), 0, context.dp(14), 0)
                setOnClickListener {
                    if (choosingLanguage) {
                        callback?.onTranslateLanguageSelected(index)
                    } else {
                        callback?.onPinyinCandidateSelected(index)
                    }
                }
                setOnLongClickListener {
                    if (!choosingLanguage) callback?.onPinyinCandidateLongPressed(index)
                    !choosingLanguage
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

    /**
     * The first candidate (what space commits) sits on a key-colored pill and is heavier, so it is
     * clear what space will type; while choosing a translation target, the current language does.
     * A pill rather than the accent color: in some themes the accent is a quiet grey.
     */
    private fun styleCandidate(view: TextView, highlighted: Boolean) {
        view.setTextColor(ImeColors.TEXT)
        view.typeface = if (highlighted) FIRST_TYPEFACE else REGULAR_TYPEFACE
        // Setting a background resets padding to the drawable's; keep the candidate's own.
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.background = if (highlighted) {
            val pill = GradientDrawable().apply {
                cornerRadius = context.dp(HIGHLIGHT_RADIUS_DP).toFloat()
                setColor(ImeColors.KEY)
            }
            InsetDrawable(pill, 0, context.dp(HIGHLIGHT_INSET_DP), 0, context.dp(HIGHLIGHT_INSET_DP))
        } else {
            null
        }
        view.setPadding(left, top, right, bottom)
    }

    private companion object {
        const val DISABLED_ALPHA = 0.45f
        const val CANDIDATE_TEXT_SP = 22f
        const val COMPOSING_TEXT_SP = 12f
        const val EXPAND_WIDTH_DP = 56
        const val EXPAND_TEXT_SP = 18f
        const val CANDIDATE_MIN_WIDTH_DP = 52
        const val HIGHLIGHT_RADIUS_DP = 10
        const val HIGHLIGHT_INSET_DP = 3
        val FIRST_TYPEFACE: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        val REGULAR_TYPEFACE: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    }
}
