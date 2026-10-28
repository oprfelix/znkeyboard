package dev.zain.znkeyboard.ime

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.constants.ImeColors
import java.util.Locale
import kotlin.math.roundToInt

/** Searches clipboard history while the keyboard types into the query field. */
class ClipboardSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs),
    ClipboardHistoryListView.Callback {
    interface Callback {
        fun onClipboardSearchClosed()
        fun onClipboardSearchItemSelected(text: String)
        fun onClipboardSearchItemDeleted(text: String)
    }

    var callback: Callback? = null

    private var clipboardEntries = emptyList<ClipboardHistoryStore.Entry>()
    private var query = ""

    private val queryField = ImeSearchField(context).configure(
        backgroundColor = PALETTE.function,
        textColor = PALETTE.text,
        placeholderColor = PALETTE.placeholderText,
        clearIconColor = PALETTE.mutedText,
        clearContentDescription = "Clear clipboard search",
        onClear = { setQuery("") },
    )
    private val clipboardResultList = ClipboardHistoryListView(context).apply {
        callback = this@ClipboardSearchView
    }
    private val emptyText = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(PALETTE.mutedText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        visibility = GONE
    }
    private val resultsFrame = FrameLayout(context).apply {
        addView(
            clipboardResultList,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        addView(
            emptyText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PALETTE.background)
        setPadding(
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.TOP_PADDING_DP),
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(6),
        )
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        visibility = GONE

        addView(resultsFrame, LayoutParams(LayoutParams.MATCH_PARENT, dp(156)))
        addView(searchRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).withMargins(top = 6))
        updateResults()
    }

    fun setClipboardEntries(entries: List<ClipboardHistoryStore.Entry>) {
        val normalized = entries.take(ClipboardHistoryStore.MAX_HISTORY)
        if (clipboardEntries != normalized) {
            clipboardEntries = normalized
            updateResults()
        }
    }

    fun appendQueryText(value: String) {
        if (value.isEmpty()) return
        setQuery(query + value)
    }

    fun deleteQueryCharacter() {
        if (query.isNotEmpty()) {
            setQuery(query.dropLast(1))
        }
    }

    fun clearSearch() {
        setQuery("")
    }

    override fun onClipboardHistoryItemSelected(text: String) {
        callback?.onClipboardSearchItemSelected(text)
    }

    override fun onClipboardHistoryItemDeleted(text: String) {
        callback?.onClipboardSearchItemDeleted(text)
    }

    private fun setQuery(value: String) {
        val normalized = value.take(MAX_QUERY_LENGTH)
        if (query != normalized) {
            query = normalized
            updateResults()
        } else {
            updateQueryText()
        }
    }

    private fun searchRow(): LinearLayout {
        return ImeSearchHeader.create(
            context = context,
            backContentDescription = "Back to clipboard",
            backgroundColor = PALETTE.function,
            textColor = PALETTE.mutedText,
            queryField = queryField,
            onBack = { callback?.onClipboardSearchClosed() },
        )
    }

    private fun updateResults() {
        updateQueryText()
        val entries = visibleClipboardEntries()
        clipboardResultList.submitHistory(entries)
        if (entries.isEmpty()) {
            clipboardResultList.visibility = GONE
            emptyText.text = if (query.isBlank()) "No clipboard history yet" else "No matching clipboard items"
            emptyText.visibility = VISIBLE
            return
        }

        emptyText.visibility = GONE
        clipboardResultList.visibility = VISIBLE
    }

    private fun updateQueryText() {
        queryField.setSearchText(query = query, placeholder = "Search clipboard")
    }

    private fun visibleClipboardEntries(): List<ClipboardHistoryStore.Entry> {
        val needle = query.trim().lowercase(Locale.US)
        if (needle.isBlank()) return clipboardEntries.take(MAX_RESULTS)
        return clipboardEntries
            .filter { entry -> entry.text.lowercase(Locale.US).contains(needle) }
            .take(MAX_RESULTS)
    }

    private fun LayoutParams.withMargins(top: Int = 0): LayoutParams {
        setMargins(0, dp(top), 0, 0)
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private object PALETTE {
        val background get() = ImeColors.BACKGROUND
        val function get() = ImeColors.FUNCTION_DARK
        val text get() = ImeColors.TEXT
        val mutedText get() = ImeColors.MUTED_TEXT
        val placeholderText get() = ImeColors.PLACEHOLDER_TEXT
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 80
        const val MAX_RESULTS = 24
    }
}
