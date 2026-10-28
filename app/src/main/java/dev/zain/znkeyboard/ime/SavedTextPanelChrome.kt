package dev.zain.znkeyboard.ime

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import dev.zain.znkeyboard.R

internal object SavedTextPanelChrome {
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
