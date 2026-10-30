package com.tvlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the background effect chosen in [ScreenEffect] behind the home screen content.
 *
 * Everything is pure canvas drawing (no bitmaps, no extra libraries), so it costs almost nothing
 * on TV hardware and works over any wallpaper. The colours come from the current [Palette], so
 * every effect keeps the same identity as the rest of the launcher.
 *
 * The view used to draw only the "aurora" glow; it now also draws vignettes, mist, stars, waves,
 * a neon frame, a perspective grid and floating bokeh circles.
 */
class AuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()

    private var colors: IntArray = Palette.of(Palette.DEFAULT).aurora
    private var effect: Int = ScreenEffect.AURORA
    private var animator: ValueAnimator? = null
    private var animationAllowed = true

    /** 0f..1f slow breathing phase (0 -> 1 -> 0). */
    private var phase = 0f

    /** 0f..1f continuous loop, used by the effects that move. */
    private var time = 0f

    /** Layout of the three aurora glows, relative to the view size: x, y and radius. */
    private val glows = arrayOf(
        floatArrayOf(0.12f, 0.08f, 0.72f),
        floatArrayOf(0.88f, 0.22f, 0.62f),
        floatArrayOf(0.55f, 1.02f, 0.80f)
    )

    /** Stars: x, y, radius and a random phase. Generated once per view size. */
    private var stars: Array<FloatArray> = emptyArray()
    private var starsWidth = 0f
    private var starsHeight = 0f

    private var vignetteShader: Shader? = null
    private var vignetteKey = ""

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
        vignetteShader = null
        invalidate()
    }

    /** Selects one of the [ScreenEffect] ids. Unknown ids fall back to the aurora glow. */
    fun setEffect(id: Int) {
        val safe = ScreenEffect.of(id).id
        if (safe == effect) return
        effect = safe
        vignetteShader = null
        stars = emptyArray()
        updateAnimator()
        invalidate()
    }

    fun getEffect(): Int = effect

    /** Turns the animation on/off; static effects never animate even when this is true. */
    fun setAnimated(enabled: Boolean) {
        animationAllowed = enabled
        updateAnimator()
    }

    private fun isAnimatedEffect(): Boolean = when (effect) {
        ScreenEffect.NONE,
        ScreenEffect.VIGNETTE,
        ScreenEffect.COLORED_VIGNETTE -> false
        else -> true
    }

    private fun updateAnimator() {
        val shouldRun = animationAllowed &&
            isAttachedToWindow &&
            visibility == VISIBLE &&
            isAnimatedEffect()
        if (shouldRun) start() else stop()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                time = t
                // A slow, smooth breathing: 0 -> 1 -> 0.
                phase = (sin(t * 2.0 * PI).toFloat() + 1f) / 2f
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
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimator()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vignetteShader = null
        stars = emptyArray()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        when (effect) {
            ScreenEffect.NONE -> return
            ScreenEffect.VIGNETTE -> drawVignette(canvas, w, h, colored = false)
            ScreenEffect.COLORED_VIGNETTE -> drawVignette(canvas, w, h, colored = true)
            ScreenEffect.MIST -> drawMist(canvas, w, h)
            ScreenEffect.STARS -> drawStars(canvas, w, h)
            ScreenEffect.WAVES -> drawWaves(canvas, w, h)
            ScreenEffect.NEON -> drawNeon(canvas, w, h)
            ScreenEffect.GRID -> drawGrid(canvas, w, h)
            ScreenEffect.BOKEH -> drawBokeh(canvas, w, h)
            else -> drawAurora(canvas, w, h)
        }
        paint.shader = null
    }

    // ---- Effects ----------------------------------------------------------------------------

    /** Three large radial gradients floating over the wallpaper. */
    private fun drawAurora(canvas: Canvas, w: Float, h: Float) {
        val baseAlpha = (18 + 30 * phase).toInt()
        for (i in glows.indices) {
            val g = glows[i]
            val color = colors[i % colors.size]
            val cx = w * g[0]
            val cy = h * g[1]
            val radius = max(w, h) * g[2] * (0.92f + 0.08f * phase)
            val alpha = (baseAlpha * (if (i % 2 == 0) 1f else 0.8f)).toInt()
            val center = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            val edge = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            paint.shader = RadialGradient(cx, cy, radius, center, edge, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    /** Classic dark vignette or a version tinted with the palette colour. */
    private fun drawVignette(canvas: Canvas, w: Float, h: Float, colored: Boolean) {
        val color = colors[0]
        val key = "${w.toInt()}x${h.toInt()}:$colored:${colors.contentHashCode()}"
        if (vignetteShader == null || vignetteKey != key) {
            val center = if (colored) {
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            } else {
                Color.TRANSPARENT
            }
            val middle = if (colored) {
                Color.argb(38, Color.red(color), Color.green(color), Color.blue(color))
            } else {
                Color.argb(40, 0, 0, 0)
            }
            val edge = if (colored) {
                Color.argb(175, Color.red(color), Color.green(color), Color.blue(color))
            } else {
                Color.argb(225, 0, 0, 0)
            }
            val radius = hypot(w / 2f, h / 2f)
            vignetteShader = RadialGradient(
                w / 2f,
                h / 2f,
                radius,
                intArrayOf(center, middle, edge),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            vignetteKey = key
        }
        paint.shader = vignetteShader
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    /** Very soft clouds drifting sideways. */
    private fun drawMist(canvas: Canvas, w: Float, h: Float) {
        for (i in 0 until 5) {
            val color = colors[i % colors.size]
            val speed = 0.035f + i * 0.012f
            val raw = 0.08f + i * 0.21f + time * speed
            val cx = (((raw % 1.25f) + 1.25f) % 1.25f - 0.12f) * w
            val cy = h * (0.18f + 0.16f * (i % 4))
            val radius = max(w, h) * (0.34f + 0.10f * (i % 3))
            val alpha = (10 + 8 * phase).toInt()
            val center = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            val edge = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            paint.shader = RadialGradient(cx, cy, radius, center, edge, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    /** Twinkling stars. */
    private fun drawStars(canvas: Canvas, w: Float, h: Float) {
        ensureStars(w, h)
        paint.shader = null
        for (star in stars) {
            val twinkle = (sin(time * 2.0 * PI + star[3] * 2.0 * PI).toFloat() + 1f) / 2f
            val alpha = (35 + 185 * twinkle).toInt()
            paint.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawCircle(star[0], star[1], star[2], paint)
        }
        // A few coloured stars use the palette, so the effect follows the chosen theme.
        for (i in 0 until min(8, colors.size * 3)) {
            val color = colors[i % colors.size]
            val x = ((i * 0.137f + 0.05f) % 1f) * w
            val y = ((i * 0.239f + 0.11f) % 0.8f) * h
            val twinkle = (sin(time * 2.0 * PI + i).toFloat() + 1f) / 2f
            paint.color = Color.argb((30 + 90 * twinkle).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(x, y, 3f + 2f * twinkle, paint)
        }
    }

    private fun ensureStars(w: Float, h: Float) {
        if (stars.isNotEmpty() && starsWidth == w && starsHeight == h) return
        starsWidth = w
        starsHeight = h
        val random = Random(42)
        stars = Array(70) {
            floatArrayOf(
                random.nextFloat() * w,
                random.nextFloat() * h,
                0.8f + random.nextFloat() * 2.2f,
                random.nextFloat()
            )
        }
    }

    /** Soft waves rising from the bottom of the screen. */
    private fun drawWaves(canvas: Canvas, w: Float, h: Float) {
        val bands = 3
        for (b in 0 until bands) {
            val color = colors[b % colors.size]
            val baseY = h * (0.70f + b * 0.09f)
            val amplitude = h * (0.045f + b * 0.01f)
            path.reset()
            path.moveTo(0f, h)
            path.lineTo(0f, baseY)
            var x = 0f
            val step = w / 48f
            while (x <= w) {
                val angle = (x / w * 2.1f + time * 2f + b * 1.7f) * PI
                path.lineTo(x, baseY + sin(angle).toFloat() * amplitude)
                x += step
            }
            path.lineTo(w, h)
            path.close()
            paint.shader = LinearGradient(
                0f,
                baseY - amplitude,
                0f,
                h,
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(85, Color.red(color), Color.green(color), Color.blue(color)),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, paint)
        }
    }

    /** Glowing frame around the screen, pulsing slowly. */
    private fun drawNeon(canvas: Canvas, w: Float, h: Float) {
        val inset = min(w, h) * 0.022f
        val radius = min(w, h) * 0.07f
        rect.set(inset, inset, w - inset, h - inset)
        val color = colors[0]
        paint.shader = null
        val pulse = 0.65f + 0.35f * phase
        for (i in 0 until 4) {
            stroke.color = Color.argb(
                ((52 - i * 11) * pulse).toInt().coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
            stroke.strokeWidth = min(w, h) * (0.0035f + i * 0.0035f)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    /** Perspective grid, like a retro floor. */
    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val horizon = h * 0.60f
        val color = colors[0]
        paint.shader = null
        stroke.strokeWidth = max(1f, min(w, h) * 0.0016f)

        // Horizontal lines get closer to each other near the horizon.
        for (i in 0..10) {
            val t = i / 10f
            val y = horizon + (h - horizon) * (t * t)
            stroke.color = Color.argb((25 + 70 * t).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawLine(0f, y, w, y, stroke)
        }
        // Vertical lines converge to the centre of the horizon.
        for (i in -9..9) {
            stroke.color = Color.argb(55, Color.red(color), Color.green(color), Color.blue(color))
            val bottomX = w / 2f + i * (w / 9f)
            val topX = w / 2f + i * (w / 90f)
            canvas.drawLine(topX, horizon, bottomX, h, stroke)
        }
        // A soft glow right above the horizon.
        paint.shader = LinearGradient(
            0f,
            horizon - h * 0.10f,
            0f,
            horizon,
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(45, Color.red(color), Color.green(color), Color.blue(color)),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizon - h * 0.10f, w, horizon, paint)
    }

    /** Soft floating circles (bokeh). */
    private fun drawBokeh(canvas: Canvas, w: Float, h: Float) {
        for (i in 0 until 14) {
            val color = colors[i % colors.size]
            val seed = i * 0.173f
            val speed = 0.020f + (i % 4) * 0.008f
            val rawX = seed + time * speed
            val rawY = seed * 3f - time * speed * 0.8f
            val cx = (((rawX % 1.2f) + 1.2f) % 1.2f - 0.1f) * w
            val cy = (((rawY % 1.2f) + 1.2f) % 1.2f - 0.1f) * h
            val radius = min(w, h) * (0.025f + 0.035f * ((i * 7) % 5) / 5f)
            val alpha = (18 + 22 * phase).toInt()
            val center = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            val edge = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            paint.shader = RadialGradient(cx, cy, radius, center, edge, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    private companion object {
        /** One full breath takes a bit over half a minute. */
        const val BREATH_MS = 16_000L
    }
}
