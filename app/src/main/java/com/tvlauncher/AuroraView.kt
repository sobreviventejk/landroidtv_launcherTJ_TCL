package com.tvlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Soft "aurora" glow drawn behind the home screen content.
 *
 * Three large radial gradients (filled circles with a soft edge) float over the wallpaper using
 * the colours of the current [Palette]. The alpha changes very slowly, so the background feels
 * alive without distracting from the apps. It is pure drawing (no bitmaps), so it costs almost
 * nothing on TV hardware and works over any wallpaper.
 */
class AuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var colors: IntArray = Palette.of(Palette.DEFAULT).aurora
    private var animator: ValueAnimator? = null

    /** 0f..1f phase of the slow breathing animation. */
    private var phase = 0f

    /** Layout of the three glows, relative to the view size: x, y and radius. */
    private val glows = arrayOf(
        floatArrayOf(0.12f, 0.08f, 0.72f),
        floatArrayOf(0.88f, 0.22f, 0.62f),
        floatArrayOf(0.55f, 1.02f, 0.80f)
    )

    init {
        // Nothing here should steal focus or touches from the apps above it.
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        paint.style = Paint.Style.FILL
    }

    /** Applies a palette entry (colours come from [Palette.ALL]). */
    fun setPalette(entry: Palette.Entry) {
        if (colors.contentEquals(entry.aurora)) return
        colors = entry.aurora
        invalidate()
    }

    /** Turns the animation on/off; when off the glow is hidden by the caller. */
    fun setAnimated(enabled: Boolean) {
        if (enabled == (animator != null)) return
        if (enabled) start() else stop()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                // A slow, smooth breathing: 0 -> 1 -> 0.
                val t = it.animatedValue as Float
                phase = (kotlin.math.sin(t * 2.0 * Math.PI).toFloat() + 1f) / 2f
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (isAttachedToWindow) start()
        } else {
            stop()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Breathing alpha, different per glow so they never blink together.
        val baseAlpha = (18 + 30 * phase).toInt()
        for (i in glows.indices) {
            val g = glows[i]
            val color = colors[i % colors.size]
            val cx = w * g[0]
            val cy = h * g[1]
            val radius = maxOf(w, h) * g[2] * (0.92f + 0.08f * phase)
            val alpha = (baseAlpha * (if (i % 2 == 0) 1f else 0.8f)).toInt()
            val center = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            val edge = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            paint.shader = RadialGradient(cx, cy, radius, center, edge, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.shader = null
    }

    private companion object {
        /** One full breath takes a bit over half a minute. */
        const val BREATH_MS = 16_000L
    }
}
