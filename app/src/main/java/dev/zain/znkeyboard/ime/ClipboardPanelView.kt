package dev.zain.znkeyboard.ime

import android.content.Context
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
    }

    var callback: Callback? = null

    private val clipboardList = ClipboardHistoryListView(context).apply {
        callback = this@ClipboardPanelView
    }
    private val clipboardEmptyText = TextView(context).apply {
        text = "No clipboard history yet"
        gravity = Gravity.CENTER
        setTextColor(PALETTE.mutedText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        includeFontPadding = false
        visibility = GONE
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
    }
    private val searchBar = SavedTextPanelChrome.searchKey(
        context = context,
        label = "Search clipboard",
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
    }

    private fun buildHeaderRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImePanelChrome.backButton(
                    context = context,
                    contentDescription = "Back to keyboard",
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
