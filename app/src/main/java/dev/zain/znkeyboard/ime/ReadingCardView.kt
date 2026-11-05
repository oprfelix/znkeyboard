package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.constants.ImeColors

/**
 * A translation above the keys, so the keyboard stays up and a reply can be typed while reading it.
 * It is part of the keyboard's height, so the app moves up and its text field stays in view.
 */
class ReadingCardView(context: Context) : LinearLayout(context) {
    interface Callback {
        fun onReadingCardClosed()
        fun onReadingLanguageRequested()
        fun onReadingCardActionRequested()
    }

    var callback: Callback? = null

    private val language = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, context.dp(12), 0)
        setOnClickListener { callback?.onReadingLanguageRequested() }
    }
    private val close = ImageView(context).apply {
        setImageResource(R.drawable.ic_close_24)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(R.string.reading_close)
        setOnClickListener { callback?.onReadingCardClosed() }
    }
    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private val body = TextView(context).apply {
        textSize = BODY_TEXT_SP
        setLineSpacing(0f, 1.2f)
    }
    private val scroller = BoundedScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        maxHeightPx = (resources.displayMetrics.heightPixels * MAX_BODY_SCREEN_FRACTION).toInt()
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val action = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
        setOnClickListener { callback?.onReadingCardActionRequested() }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        isClickable = true
        setPadding(context.dp(14), context.dp(4), context.dp(6), context.dp(10))
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(language, LayoutParams(0, context.dp(HEADER_HEIGHT_DP), 1f))
            addView(close, LayoutParams(context.dp(HEADER_HEIGHT_DP), context.dp(HEADER_HEIGHT_DP)))
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(progress, LayoutParams(context.dp(PROGRESS_SIZE_DP), context.dp(PROGRESS_SIZE_DP)))
        addView(
            scroller,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8) },
        )
        addView(
            action,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = context.dp(6)
                marginEnd = context.dp(8)
            },
        )
    }

    fun showWorking(languageLabel: String) {
        render(languageLabel)
        progress.visibility = View.VISIBLE
        scroller.visibility = View.GONE
        action.visibility = View.GONE
    }

    fun showResult(languageLabel: String, text: String, actionLabel: String? = null, failed: Boolean = false) {
        render(languageLabel)
        progress.visibility = View.GONE
        scroller.visibility = View.VISIBLE
        body.text = text
        body.setTextColor(if (failed) ImeColors.MUTED_TEXT else ImeColors.TEXT)
        scroller.scrollTo(0, 0)
        action.text = actionLabel
        action.visibility = if (actionLabel == null) View.GONE else View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun render(languageLabel: String) {
        background = GradientDrawable().apply {
            val radius = context.dp(CORNER_RADIUS_DP.toFloat())
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(ImeColors.BACKGROUND)
        }
        language.text = languageLabel
        language.setTextColor(ImeColors.MUTED_TEXT)
        close.setColorFilter(ImeColors.MUTED_TEXT)
        action.setTextColor(ImeColors.TEXT)
        action.background = GradientDrawable().apply {
            cornerRadius = context.dp(10f)
            setColor(ImeColors.FUNCTION)
        }
        visibility = View.VISIBLE
    }

    private class BoundedScrollView(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
        var maxHeightPx = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        }
    }

    private companion object {
        const val BODY_TEXT_SP = 16f
        const val HEADER_HEIGHT_DP = 34
        const val PROGRESS_SIZE_DP = 24
        const val CORNER_RADIUS_DP = 16
        const val MAX_BODY_SCREEN_FRACTION = 0.3f
    }
}
