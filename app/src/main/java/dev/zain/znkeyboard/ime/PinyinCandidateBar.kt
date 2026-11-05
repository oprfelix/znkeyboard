package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.constants.ImeDimensions

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
        /** The bar's offer to translate the text just copied: its button, and its ✕. */
        fun onReadingOfferAccepted() {}
        fun onReadingOfferDismissed() {}
        fun onTranslateLanguageSelected(index: Int)
        fun onChineseKeyboardSelected(index: Int) {}
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
    private val candidateRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
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
    private val expandButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_expand_more_24)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(R.string.pinyin_all_candidates)
        visibility = View.GONE
        setOnClickListener { callback?.onPinyinExpandRequested() }
    }
    private val offerButton = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textSize = 14f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setPadding(context.dp(14), 0, context.dp(14), 0)
        setOnClickListener { callback?.onReadingOfferAccepted() }
    }
    private val offerClose = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        text = "✕"
        setOnClickListener { callback?.onReadingOfferDismissed() }
    }
    private val offerRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(context.dp(8), 0, context.dp(8), 0)
        addView(offerButton, LayoutParams(0, context.dp(34), 1f))
        addView(
            offerClose,
            LayoutParams(context.dp(OFFER_CLOSE_WIDTH_DP), context.dp(34)).apply { marginStart = context.dp(6) },
        )
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
    private var choosing: Choosing? = null
    // Highlighted item: the first candidate (what space commits) or the current choice.
    private var highlightedIndex = 0

    init {
        orientation = VERTICAL
        addView(composingLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(mainRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        mainRow.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        mainRow.addView(offerRow, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
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
        expandButton.setColorFilter(ImeColors.TEXT)
        fixButton.setTextColor(ImeColors.TEXT)
        offerButton.setTextColor(ImeColors.TEXT)
        offerButton.background = toolBackground()
        offerClose.setTextColor(ImeColors.TEXT)
        offerClose.background = toolBackground()
        composingLabel.setTextColor(ImeColors.DISABLED_TEXT)
        polishButton.setTextColor(ImeColors.TEXT)
        translateLabel.setTextColor(ImeColors.TEXT)
        polishButton.background = toolBackground()
        translateButton.background = toolBackground()
        candidateViews.forEachIndexed { index, view -> styleCandidate(view, index == highlightedIndex) }
    }

    /** Whether the bar lists translation languages or Chinese keyboards rather than candidates. */
    val isChoosing: Boolean get() = choosing != null

    /**
     * [tools] pins 润色 | 翻译 on the right; pass it only while showing predictions. [fixLabel] shows
     * the 改词 button and [expanded], when not null, the ▾ one; [highlighted] is the item in bold, -1 for none
     * while choosing what to fix.
     */
    fun render(
        composing: String,
        candidates: List<String>,
        tools: Tools? = null,
        fixLabel: String? = null,
        expanded: Boolean? = null,
        highlighted: Int = 0,
    ) {
        choosing = null
        highlightedIndex = highlighted
        showTools(tools)
        fixButton.text = fixLabel
        fixButton.visibility = if (fixLabel == null) View.GONE else View.VISIBLE
        expandButton.rotation = if (expanded == true) 180f else 0f
        expandButton.visibility = if (expanded == null) View.GONE else View.VISIBLE
        show(composing, candidates)
    }

    /** Offers to translate the text just copied, in place of candidates, until something else renders. */
    fun renderReadingOffer(label: String) {
        choosing = null
        showTools(null)
        fixButton.visibility = View.GONE
        expandButton.visibility = View.GONE
        show("", emptyList())
        visibility = View.VISIBLE
        scroller.visibility = View.GONE
        offerRow.visibility = View.VISIBLE
        offerButton.text = label
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

    private fun toolBackground() = GradientDrawable().apply {
        cornerRadius = context.dp(ImeDimensions.KEY_RADIUS_DP.toFloat())
        setColor(ImeColors.FUNCTION)
    }

    /** Lists the target languages (as codes) in place of candidates until one is picked. */
    fun renderLanguageChoices(codes: List<String>, selectedIndex: Int) {
        renderChoices(Choosing.Language, "翻译为", codes, selectedIndex)
    }

    /** Lists the Chinese keyboards in place of candidates until one is picked. */
    fun renderKeyboardChoices(label: String, names: List<String>, selectedIndex: Int) {
        renderChoices(Choosing.Keyboard, label, names, selectedIndex)
    }

    private fun renderChoices(kind: Choosing, label: String, items: List<String>, selectedIndex: Int) {
        choosing = kind
        highlightedIndex = selectedIndex
        showTools(null)
        fixButton.visibility = View.GONE
        expandButton.visibility = View.GONE
        show(label, items)
    }

    private fun show(label: String, items: List<String>) {
        visibility = if (label.isEmpty() && items.isEmpty()) View.GONE else View.VISIBLE
        offerRow.visibility = View.GONE
        scroller.visibility = View.VISIBLE
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
                    when (choosing) {
                        Choosing.Language -> callback?.onTranslateLanguageSelected(index)
                        Choosing.Keyboard -> callback?.onChineseKeyboardSelected(index)
                        null -> callback?.onPinyinCandidateSelected(index)
                    }
                }
                setOnLongClickListener {
                    if (choosing == null) callback?.onPinyinCandidateLongPressed(index)
                    choosing == null
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
     * The first candidate (what space commits) is heavier, so it is clear what space will type. The
     * current choice of a list also sits on a key-colored pill: it is a state, and nothing else in
     * the list says so. A pill rather than the accent color: in some themes that is a quiet grey.
     */
    private fun styleCandidate(view: TextView, highlighted: Boolean) {
        view.setTextColor(ImeColors.TEXT)
        view.typeface = if (highlighted) FIRST_TYPEFACE else REGULAR_TYPEFACE
        // Setting a background resets padding to the drawable's; keep the candidate's own.
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.background = if (highlighted && choosing != null) {
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

    private enum class Choosing { Language, Keyboard }

    private companion object {
        const val DISABLED_ALPHA = 0.45f
        const val CANDIDATE_TEXT_SP = 20f
        const val COMPOSING_TEXT_SP = 12f
        const val EXPAND_WIDTH_DP = 56
        const val OFFER_CLOSE_WIDTH_DP = 44
        const val CANDIDATE_MIN_WIDTH_DP = 52
        const val HIGHLIGHT_RADIUS_DP = 10
        const val HIGHLIGHT_INSET_DP = 3
        val FIRST_TYPEFACE: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        val REGULAR_TYPEFACE: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    }
}
