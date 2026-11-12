package dev.zain.znkeyboard.ime

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import dev.zain.znkeyboard.constants.ImeColors

/**
 * 翻译 and its language as two layers, as on iOS: a quiet blue capsule is the lower layer (the
 * language), and 翻译 lies on it in a stronger blue whose right edge slants like "/", casting a
 * soft shadow onto the language. The capsule's round ends follow the keyboard's corners. The blue
 * is fixed, not the theme's accent, so both apps look the same.
 */
class TranslateLayers(private val density: Float) {
    private val capsule = Path()
    private val top = Path()
    private val lowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val upperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 翻译's "/" edge, from [bounds]' left to [split], where the language begins. */
    fun draw(canvas: Canvas, bounds: RectF, split: Float, pressed: Boolean = false, alpha: Int = 255, dimmed: Boolean = false) {
        val light = isLight()
        val radius = bounds.height() / 2f
        val lower = ColorUtils.blendARGB(ImeColors.BACKGROUND, blue(), if (light) 0.10f else 0.16f)
        var upper = ColorUtils.blendARGB(ImeColors.BACKGROUND, blue(), if (light) 0.22f else 0.36f)
        if (pressed) upper = ColorUtils.blendARGB(upper, ink(), 0.2f)
        // Dimmed (nothing to translate), the top layer fades toward the lower one.
        if (dimmed) upper = ColorUtils.blendARGB(upper, lower, 0.6f)
        capsule.reset()
        capsule.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        lowerPaint.color = lower
        lowerPaint.alpha = alpha
        canvas.drawPath(capsule, lowerPaint)
        val slant = SLANT_DP * density
        top.reset()
        top.moveTo(bounds.left, bounds.top)
        top.lineTo(split + slant / 2f, bounds.top)
        top.lineTo(split - slant / 2f, bounds.bottom)
        top.lineTo(bounds.left, bounds.bottom)
        top.close()
        canvas.save()
        canvas.clipPath(capsule)
        upperPaint.color = upper
        upperPaint.alpha = alpha
        val shadow = (if (light) 0.25f else 0.5f) * (if (dimmed) 0.5f else 1f) * alpha
        upperPaint.setShadowLayer(3f * density, 2f * density, 0f, Color.argb(shadow.toInt(), 0, 0, 0))
        canvas.drawPath(top, upperPaint)
        canvas.restore()
    }

    companion object {
        /** How far 翻译's "/" edge leans, top to bottom. */
        const val SLANT_DP = 10f

        private fun isLight() = ColorUtils.calculateLuminance(ImeColors.BACKGROUND) > 0.5

        /** The blue both layers are washed with: iOS's system blue. */
        fun blue(): Int = if (isLight()) Color.rgb(0, 122, 255) else Color.rgb(10, 132, 255)

        /** Text and icons on the capsule. */
        fun ink(): Int = if (isLight()) Color.rgb(0, 110, 230) else Color.rgb(64, 156, 255)
    }
}
