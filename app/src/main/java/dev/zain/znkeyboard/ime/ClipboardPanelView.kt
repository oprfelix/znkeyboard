package dev.zain.znkeyboard.ime

import android.content.Context
import dev.zain.znkeyboard.R
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.constants.ImeColors
import kotlin.math.roundToInt

/** Clipboard history panel, opened by long-pressing space. */
class ClipboardPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs),
    ClipboardHistoryListView.Callback {
    interface Callback {
        fun onClipboardSearchRequested()
        fun onClipboardPanelClosed()
        fun onClipboardHistoryItemSelected(text: String)
        fun onClipboardHistoryItemDeleted(text: String)
        fun onClipboardClearRequested()
        fun onClipboardClearUndone()
    }

    var callback: Callback? = null

    private val clipboardList = ClipboardHistoryListView(context).apply {
        callback = this@ClipboardPanelView
    }
    private val clipboardEmptyText = TextView(context).apply {
        setText(R.string.ime_clipboard_empty)
        gravity = Gravity.CENTER
        setTextColor(PALETTE.mutedText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        includeFontPadding = false
        visibility = GONE
    }
    private val clearButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_delete_24)
        scaleType = ImageView.ScaleType.CENTER
        setColorFilter(PALETTE.mutedText)
        contentDescription = context.getString(R.string.clipboard_clear)
        setOnClickListener { callback?.onClipboardClearRequested() }
    }
    private val undoMessage = TextView(context).apply {
        setTextColor(ImeColors.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER_VERTICAL
    }
    private val undoRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        setPadding(dp(14), 0, dp(6), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(PALETTE.function)
        }
        addView(undoMessage, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(
            TextView(context).apply {
                text = context.getString(R.string.action_undo)
                setTextColor(ImeColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.DEFAULT, 500, false)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { callback?.onClipboardClearUndone() }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
    }
    private val contentFrame = FrameLayout(context).apply {
        addView(
            clipboardList,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        addView(
            clipboardEmptyText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        addView(
            undoRow,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(44), Gravity.BOTTOM).apply {
                val margin = dp(6)
                setMargins(margin, 0, margin, margin)
            },
        )
    }
    private val searchBar = SavedTextPanelChrome.searchKey(
        context = context,
        label = context.getString(R.string.ime_clipboard_search),
        backgroundColor = PALETTE.function,
        textColor = PALETTE.mutedText,
        onClick = { callback?.onClipboardSearchRequested() },
    )

    private var clipboardEntries = emptyList<ClipboardHistoryStore.Entry>()
    private var heightScale = 1f

    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) {
        ImeLayout.bottomSystemControlGapPx(context)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PALETTE.background)
        setPadding(
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.TOP_PADDING_DP),
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.BASE_BOTTOM_PADDING_DP) + bottomSystemControlGapPx.roundToInt(),
        )
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        isClickable = true

        addView(
            buildHeaderRow(),
            LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).withMargins(bottom = 6),
        )
        addView(
            contentFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        updateClipboardContent()
    }

    /** A moment to take the clearing back; [hideClearUndo] ends it. */
    fun showClearUndo(message: String) {
        undoMessage.text = message
        undoRow.visibility = VISIBLE
    }

    fun hideClearUndo() {
        undoRow.visibility = GONE
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    fun submitClipboardHistory(entries: List<ClipboardHistoryStore.Entry>) {
        val normalized = entries.take(ClipboardHistoryStore.MAX_HISTORY)
        if (clipboardEntries == normalized) return

        clipboardEntries = normalized
        updateClipboardContent()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = ((ImeLayout.BASE_HEIGHT_DP + ImeLayout.EXTENDED_PANEL_EXTRA_HEIGHT_DP) *
            heightScale *
            resources.displayMetrics.density +
            bottomSystemControlGapPx)
            .roundToInt()
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
    }

    override fun onClipboardHistoryItemSelected(text: String) {
        callback?.onClipboardHistoryItemSelected(text)
    }

    override fun onClipboardHistoryItemDeleted(text: String) {
        callback?.onClipboardHistoryItemDeleted(text)
    }

    private fun updateClipboardContent() {
        clipboardList.submitHistory(clipboardEntries)
        val empty = clipboardEntries.isEmpty()
        clipboardEmptyText.visibility = if (empty) VISIBLE else GONE
        clipboardList.visibility = if (empty) GONE else VISIBLE
        clearButton.visibility = if (empty) GONE else VISIBLE
    }

    private fun buildHeaderRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImePanelChrome.backButton(
                    context = context,
                    contentDescription = context.getString(dev.zain.znkeyboard.R.string.ime_back_to_keyboard),
                    backgroundColor = PALETTE.function,
                    textColor = PALETTE.mutedText,
                    onClick = { callback?.onClipboardPanelClosed() },
                ),
                LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(end = 4),
            )
            addView(
                searchBar,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(start = 2),
            )
            addView(clearButton, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
        }
    }

    private fun LayoutParams.withMargins(
        start: Int = 0,
        top: Int = 0,
        end: Int = 0,
        bottom: Int = 0,
    ): LayoutParams {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private object PALETTE {
        val background get() = ImeColors.BACKGROUND
        val function get() = ImeColors.FUNCTION_DARK
        val mutedText get() = ImeColors.MUTED_TEXT
    }
}
