package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.constants.ImeDimensions

/**
 * The leaf menu's keyboard choice, over the keys: the three Chinese keyboards as cards, each a
 * small keyboard drawn in true proportion with its name, the current one outlined. A tap picks.
 */
class KeyboardPickerView(context: Context) : View(context) {
    /** Called with the index of the card tapped. */
    var onPick: ((Int) -> Unit)? = null

    private var names: List<String> = emptyList()
    private var selected = 0
    private var footer = ""

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val cards = Array(CARDS) { RectF() }
    private val thumbs = Array(CARDS) { RectF() }
    private val key = RectF()
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radioPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2f) }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(14.5f)
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(10f)
    }
    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
    }
    private var pressed = -1

    fun show(names: List<String>, selected: Int, footer: String) {
        this.names = names
        this.selected = selected
        this.footer = footer
        visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = dp(SIDE_DP)
        val gap = dp(GAP_DP)
        val cardWidth = (w - 2 * side - (CARDS - 1) * gap) / CARDS
        val pad = dp(CARD_PAD_DP)
        val thumbHeight = (cardWidth - 2 * pad) / THUMB_ASPECT
        val cardHeight = pad + thumbHeight + dp(LABEL_ROW_DP)
        // Just under the header, as a sheet of choices; the footer line follows the cards.
        val top = dp(TOP_DP)
        for (i in 0 until CARDS) {
            val left = side + i * (cardWidth + gap)
            cards[i].set(left, top, left + cardWidth, top + cardHeight)
            thumbs[i].set(left + pad, top + pad, left + cardWidth - pad, top + pad + thumbHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(ImeColors.BACKGROUND)
        val radius = dp(ImeDimensions.KEY_RADIUS_DP.toFloat() + 8f)
        for (i in 0 until CARDS) {
            val card = cards[i]
            val chosen = i == selected
            cardPaint.color = if (i == pressed) ImeColors.SELECTED else ImeColors.KEY
            canvas.drawRoundRect(card, radius, radius, cardPaint)
            if (chosen) {
                borderPaint.color = ImeColors.ACCENT
                val inset = borderPaint.strokeWidth / 2f
                canvas.drawRoundRect(card.left + inset, card.top + inset, card.right - inset, card.bottom - inset, radius - inset, radius - inset, borderPaint)
            }
            val thumb = thumbs[i]
            thumbPaint.color = ImeColors.BACKGROUND
            canvas.drawRoundRect(thumb, dp(10f), dp(10f), thumbPaint)
            keyPaint.color = if (chosen) ColorUtils.setAlphaComponent(ImeColors.ACCENT, 150) else ImeColors.FUNCTION
            when (i) {
                0 -> drawQwerty(canvas, thumb)
                else -> drawNineKey(canvas, thumb, strokes = i == 2)
            }
            drawLabel(canvas, card, thumb.bottom, names.getOrNull(i).orEmpty(), chosen)
        }
        if (footer.isNotEmpty()) {
            footerPaint.color = ImeColors.DISABLED_TEXT
            canvas.drawText(footer, width / 2f, cards[0].bottom + dp(FOOTER_DP) * 0.72f, footerPaint)
        }
    }

    /** Ten, nine and nine keys, then a bottom row around a wide space bar. */
    private fun drawQwerty(canvas: Canvas, area: RectF) {
        val inset = dp(7f)
        val left = area.left + inset
        val width = area.width() - 2 * inset
        val rowGap = dp(3f)
        val rowHeight = (area.height() - 2 * inset - 3 * rowGap) / 4
        val keyGap = dp(2f)
        val keyWidth = (width - 9 * keyGap) / 10
        for (row in 0 until 3) {
            val count = if (row == 0) 10 else 9
            val start = left + (width - (count * keyWidth + (count - 1) * keyGap)) / 2
            val top = area.top + inset + row * (rowHeight + rowGap)
            for (k in 0 until count) {
                val x = start + k * (keyWidth + keyGap)
                drawKey(canvas, x, top, x + keyWidth, top + rowHeight)
            }
        }
        val top = area.top + inset + 3 * (rowHeight + rowGap)
        var x = left
        for (weight in QWERTY_BOTTOM) {
            val w = keyWidth * weight + keyGap * (weight - 1)
            drawKey(canvas, x, top, x + w, top + rowHeight)
            x += w + keyGap
        }
    }

    /** A 3 × 3 grid and a bottom row with a wide key; the stroke keyboard labels its first five. */
    private fun drawNineKey(canvas: Canvas, area: RectF, strokes: Boolean) {
        val inset = dp(7f)
        val gap = dp(3f)
        val rowHeight = (area.height() - 2 * inset - 3 * gap) / 4
        val keyWidth = (area.width() - 2 * inset - 2 * gap) / 3
        strokePaint.color = ImeColors.TEXT
        for (row in 0 until 3) {
            val top = area.top + inset + row * (rowHeight + gap)
            for (col in 0 until 3) {
                val x = area.left + inset + col * (keyWidth + gap)
                drawKey(canvas, x, top, x + keyWidth, top + rowHeight)
                val label = if (strokes) STROKES.getOrNull(row * 3 + col) else null
                if (label != null) {
                    canvas.drawText(label, x + keyWidth / 2, top + rowHeight / 2 - (strokePaint.ascent() + strokePaint.descent()) / 2, strokePaint)
                }
            }
        }
        val top = area.top + inset + 3 * (rowHeight + gap)
        val left = area.left + inset
        drawKey(canvas, left, top, left + keyWidth, top + rowHeight)
        drawKey(canvas, left + keyWidth + gap, top, area.right - inset, top + rowHeight)
    }

    private fun drawKey(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        key.set(left, top, right, bottom)
        canvas.drawRoundRect(key, dp(2.5f), dp(2.5f), keyPaint)
    }

    /** A radio and the name, centred under the thumbnail. */
    private fun drawLabel(canvas: Canvas, card: RectF, thumbBottom: Float, name: String, chosen: Boolean) {
        val centerY = (thumbBottom + card.bottom) / 2f
        val radioRadius = dp(8f)
        val textWidth = namePaint.measureText(name)
        val total = radioRadius * 2 + dp(6f) + textWidth
        val startX = card.centerX() - total / 2f
        val cx = startX + radioRadius
        if (chosen) {
            radioPaint.style = Paint.Style.FILL
            radioPaint.color = ImeColors.ACCENT
            canvas.drawCircle(cx, centerY, radioRadius, radioPaint)
            radioPaint.color = ImeColors.ON_ACTION
            canvas.drawCircle(cx, centerY, dp(3f), radioPaint)
        } else {
            radioPaint.style = Paint.Style.STROKE
            radioPaint.color = ImeColors.DISABLED_TEXT
            canvas.drawCircle(cx, centerY, radioRadius - radioPaint.strokeWidth / 2f, radioPaint)
        }
        namePaint.color = ImeColors.TEXT
        canvas.drawText(name, startX + radioRadius * 2 + dp(6f), centerY - (namePaint.ascent() + namePaint.descent()) / 2, namePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = cardAt(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pressed >= 0 && cardAt(event.x, event.y) != pressed) {
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val picked = pressed.takeIf { it >= 0 && cardAt(event.x, event.y) == it }
                pressed = -1
                invalidate()
                if (picked != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    selected = picked
                    onPick?.invoke(picked)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    private fun cardAt(x: Float, y: Float): Int = cards.indexOfFirst { it.contains(x, y) }

    private companion object {
        const val CARDS = 3
        const val SIDE_DP = 10f
        const val TOP_DP = 14f
        const val GAP_DP = 8f
        const val CARD_PAD_DP = 8f
        const val LABEL_ROW_DP = 38f
        const val FOOTER_DP = 34f
        /** A phone keyboard is about 1.5 times as wide as its key rows are tall. */
        const val THUMB_ASPECT = 1.45f
        /** Bottom row of the full keyboard in key widths: symbols, comma, space, period, enter. */
        val QWERTY_BOTTOM = floatArrayOf(1.5f, 1f, 5f, 1f, 1.5f)
        val STROKES = arrayOf("一", "丨", "丿", "丶", "乛", "＊")
    }
}
