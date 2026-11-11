package dev.zain.znkeyboard.ime

import android.content.Context
import android.util.TypedValue
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.R

/** The three panels that stand in for the keys, reached from the toolbar's icons as on iOS. */
enum class SavedTextTab(val icon: Int, val description: Int) {
    Clipboard(R.drawable.ic_toolbar_clipboard_24, R.string.ime_tab_clipboard),
    Phrases(R.drawable.ic_toolbar_phrases_24, R.string.ime_tab_phrases),
    Help(R.drawable.ic_toolbar_help_24, R.string.ime_tab_help),
}

internal object SavedTextPanelChrome {
    /**
     * Clipboard, phrases and help, the open one on a key-coloured tile. Its icon closes the panel;
     * the others switch to theirs.
     */
    fun tabs(
        context: Context,
        selected: SavedTextTab,
        selectedColor: Int,
        iconColor: Int,
        onSelect: (SavedTextTab) -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (tab in SavedTextTab.entries) {
            val button = ImePanelChrome.iconButton(
                context = context,
                iconResId = tab.icon,
                contentDescription = context.getString(tab.description),
                backgroundColor = if (tab == selected) selectedColor else Color.TRANSPARENT,
                textColor = iconColor,
                onClick = { onSelect(tab) },
            )
            addView(button, LinearLayout.LayoutParams(context.dp(TAB_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private const val TAB_WIDTH_DP = 42

    fun searchKey(
        context: Context,
        label: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            contentDescription = label
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isClickable = true
            isFocusable = false
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(context.dp(14), 0, context.dp(14), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(context.tintedDrawable(R.drawable.ic_search_24, textColor), null, null, null)
            compoundDrawablePadding = context.dp(8)
            background = ImePressFeedback.roundedBackground(context, backgroundColor, textColor)
            setOnClickListener { onClick() }
        }
    }
}
