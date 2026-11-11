package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.constants.ImeColors
import kotlin.math.roundToInt

/**
 * A panel in place of the keys, as tall as the clipboard's: a header of back, a title and the
 * clipboard / phrases / help tabs, then its content.
 */
abstract class SavedTextPanel(context: Context, tab: SavedTextTab) : LinearLayout(context) {
    interface Callback {
        fun onSavedTextPanelClosed()
        fun onSavedTextTabSelected(tab: SavedTextTab)
    }

    var panelCallback: Callback? = null
    private var heightScale = 1f
    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) { ImeLayout.bottomSystemControlGapPx(context) }
    protected val content = FrameLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(ImeColors.BACKGROUND)
        setPadding(
            context.dp(ImeLayout.HORIZONTAL_PADDING_DP),
            context.dp(ImeLayout.TOP_PADDING_DP),
            context.dp(ImeLayout.HORIZONTAL_PADDING_DP),
            context.dp(ImeLayout.BASE_BOTTOM_PADDING_DP) + bottomSystemControlGapPx.roundToInt(),
        )
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        isClickable = true
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImePanelChrome.backButton(
                    context = context,
                    contentDescription = context.getString(R.string.ime_back_to_keyboard),
                    backgroundColor = ImeColors.FUNCTION_DARK,
                    textColor = ImeColors.MUTED_TEXT,
                    onClick = { panelCallback?.onSavedTextPanelClosed() },
                ),
                LayoutParams(context.dp(58), LayoutParams.MATCH_PARENT),
            )
            addView(
                TextView(context).apply {
                    setText(tab.description)
                    setTextColor(ImeColors.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create(Typeface.DEFAULT, 500, false)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(context.dp(12), 0, 0, 0)
                },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                SavedTextPanelChrome.tabs(context, tab, ImeColors.FUNCTION, ImeColors.TEXT) { selected ->
                    if (selected == tab) panelCallback?.onSavedTextPanelClosed() else panelCallback?.onSavedTextTabSelected(selected)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(42)).apply { bottomMargin = context.dp(6) })
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = ((ImeLayout.BASE_HEIGHT_DP + ImeLayout.EXTENDED_PANEL_EXTRA_HEIGHT_DP) *
            heightScale * resources.displayMetrics.density + bottomSystemControlGapPx).roundToInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY))
    }

    protected fun card(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = context.dp(10f)
        setColor(ImeColors.FUNCTION_DARK)
    }
}

/** The user's phrases: a tap types one, a long press offers to delete it. */
class PhrasesPanelView(context: Context) : SavedTextPanel(context, SavedTextTab.Phrases) {
    interface Callback {
        fun onPhraseSelected(text: String)
        fun onPhraseDeleted(text: String)
    }

    var callback: Callback? = null
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val empty = TextView(context).apply {
        setText(R.string.ime_phrases_empty)
        setTextColor(ImeColors.MUTED_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(context.dp(24), 0, context.dp(24), 0)
    }
    private var phrases = emptyList<String>()
    private var opened: String? = null

    init {
        content.addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        content.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    fun submitPhrases(newPhrases: List<String>) {
        if (opened !in newPhrases) opened = null
        phrases = newPhrases
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        empty.visibility = if (phrases.isEmpty()) VISIBLE else GONE
        for (phrase in phrases) list.addView(row(phrase), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(6) })
    }

    private fun row(phrase: String): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(46)
        background = card()
        setPadding(context.dp(12), context.dp(10), context.dp(8), context.dp(10))
        addView(
            TextView(context).apply {
                text = phrase.replace('\n', ' ')
                setTextColor(ImeColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        if (opened == phrase) {
            addView(
                TextView(context).apply {
                    setText(R.string.ime_phrase_delete)
                    setTextColor(android.graphics.Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    setPadding(context.dp(12), 0, context.dp(12), 0)
                    background = GradientDrawable().apply {
                        cornerRadius = context.dp(7f)
                        setColor(DELETE_RED)
                    }
                    setOnClickListener {
                        opened = null
                        callback?.onPhraseDeleted(phrase)
                    }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(32)).apply { marginStart = context.dp(8) },
            )
        }
        setOnClickListener {
            if (opened != null) {
                opened = null
                rebuild()
            } else {
                callback?.onPhraseSelected(phrase)
            }
        }
        setOnLongClickListener {
            opened = phrase
            rebuild()
            true
        }
    }

    private companion object {
        val DELETE_RED = android.graphics.Color.rgb(255, 59, 48)
    }
}

/** How the AI tools work, translating what was copied first, with its button right under it. */
class HelpPanelView(context: Context) : SavedTextPanel(context, SavedTextTab.Help) {
    interface Callback {
        fun onTranslateClipboardRequested()
    }

    var callback: Callback? = null

    init {
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        val cards = listOf(
            Triple(R.drawable.ic_toolbar_clipboard_24, R.string.ime_help_read_title, R.string.ime_help_read_body),
            Triple(R.drawable.ic_auto_awesome_24, R.string.ime_help_polish_title, R.string.ime_help_polish_body),
            Triple(R.drawable.ic_translate_24, R.string.ime_help_translate_title, R.string.ime_help_translate_body),
        )
        cards.forEachIndexed { index, (icon, title, body) ->
            column.addView(helpCard(icon, title, body), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(6) })
            if (index == 0) {
                column.addView(
                    TextView(context).apply {
                        setText(R.string.ime_help_read_button)
                        setTextColor(ImeColors.ACCENT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            cornerRadius = context.dp(10f)
                            setColor(ImeColors.ACCENT and 0x00FFFFFF or 0x26000000)
                        }
                        setOnClickListener { callback?.onTranslateClipboardRequested() }
                    },
                    LayoutParams(LayoutParams.MATCH_PARENT, context.dp(40)).apply { bottomMargin = context.dp(12) },
                )
            }
        }
        content.addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    /** The icon and name on the first line, how to use it below, all of it shown. */
    private fun helpCard(icon: Int, title: Int, body: Int): View = TextView(context).apply {
        val text = SpannableStringBuilder()
        context.tintedDrawable(icon, ImeColors.ACCENT)?.let { drawable ->
            val size = context.dp(17)
            drawable.setBounds(0, 0, size, size)
            text.append(" ", ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(" ")
        }
        val titleStart = text.length
        text.append(context.getString(title))
        text.setSpan(StyleSpan(Typeface.BOLD), titleStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(15f / 14f), titleStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append("\n")
        val bodyStart = text.length
        text.append(context.getString(body))
        text.setSpan(ForegroundColorSpan(ImeColors.MUTED_TEXT), bodyStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        this.text = text
        setTextColor(ImeColors.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(context.dp(3f), 1f)
        background = card()
        setPadding(context.dp(12), context.dp(11), context.dp(12), context.dp(11))
    }
}
