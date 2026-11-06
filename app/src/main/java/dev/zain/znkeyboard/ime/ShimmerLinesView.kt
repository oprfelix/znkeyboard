package dev.zain.znkeyboard.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/** Placeholder lines with a moving highlight while the AI works, as on the Mac. */
class ShimmerLinesView(context: Context) : View(context) {
    private val lines = Array(LINE_FRACTIONS.size) { RectF() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineMatrix = Matrix()
    private val radius = context.dp(LINE_HEIGHT_DP / 2f)
    private var shineColor = Color.WHITE
    private var phase = START_PHASE
    private val animator = ValueAnimator.ofFloat(START_PHASE, END_PHASE).apply {
        duration = SWEEP_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setColors(line: Int, shine: Int) {
        linePaint.color = line
        shineColor = shine
        buildShine()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = LINE_FRACTIONS.size
        val height = context.dp(LINE_HEIGHT_DP) * count + context.dp(LINE_GAP_DP) * (count - 1) +
            context.dp(VERTICAL_PADDING_DP) * 2
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        var top = context.dp(VERTICAL_PADDING_DP).toFloat()
        val lineHeight = context.dp(LINE_HEIGHT_DP).toFloat()
        lines.forEachIndexed { index, line ->
            line.set(0f, top, w * LINE_FRACTIONS[index], top + lineHeight)
            top += lineHeight + context.dp(LINE_GAP_DP)
        }
        buildShine()
    }

    override fun onDraw(canvas: Canvas) {
        shineMatrix.setTranslate(width * phase, 0f)
        shinePaint.shader?.setLocalMatrix(shineMatrix)
        for (line in lines) {
            canvas.drawRoundRect(line, radius, radius, linePaint)
            canvas.drawRoundRect(line, radius, radius, shinePaint)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun syncAnimation() {
        when {
            !isShown -> animator.cancel()
            !animator.isStarted -> animator.start()
        }
    }

    private fun buildShine() {
        if (width == 0) return
        val clear = shineColor and 0x00FFFFFF
        shinePaint.shader = LinearGradient(
            0f, 0f, width * SHINE_WIDTH_FRACTION, 0f,
            intArrayOf(clear, shineColor, clear), null, Shader.TileMode.CLAMP,
        )
    }

    private companion object {
        val LINE_FRACTIONS = floatArrayOf(1f, 0.92f, 0.6f)
        const val LINE_HEIGHT_DP = 10
        const val LINE_GAP_DP = 8
        const val VERTICAL_PADDING_DP = 4
        const val SHINE_WIDTH_FRACTION = 0.5f
        const val START_PHASE = -0.5f
        const val END_PHASE = 1f
        const val SWEEP_MS = 1_300L
    }
}
