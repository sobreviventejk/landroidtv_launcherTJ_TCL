package com.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Avatar cropper with a circular preview, touch dragging and D-pad friendly SeekBars.
 *
 * The crop is always square. [setCenter] uses normalized offsets: 0 is centered and -1/1
 * select the furthest available position on that axis. The scale is relative to the square
 * that fits the source bitmap and must be between [MIN_SCALE] and [MAX_SCALE].
 */
class ProfileCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private var scale = MIN_SCALE
    private var centerX = 0f
    private var centerY = 0f
    private var updatingControls = false

    private val preview = CropPreviewView(context)
    private val zoomSeek = createSeekBar(R.string.profile_crop_zoom_control, ZOOM_STEPS)
    private val horizontalSeek = createSeekBar(
        R.string.profile_crop_horizontal_control,
        POSITION_STEPS
    )
    private val verticalSeek = createSeekBar(R.string.profile_crop_vertical_control, POSITION_STEPS)
    private val zoomLabel = createLabel(zoomSeek)
    private val horizontalLabel = createLabel(horizontalSeek)
    private val verticalLabel = createLabel(verticalSeek)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val padding = dp(8)
        setPadding(padding, padding, padding, padding)

        addView(preview, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addControl(zoomLabel, zoomSeek)
        addControl(horizontalLabel, horizontalSeek)
        addControl(verticalLabel, verticalSeek)

        bindSeekBar(zoomSeek) { progress ->
            scale = MIN_SCALE + progress.toFloat() / ZOOM_STEPS * (MAX_SCALE - MIN_SCALE)
        }
        bindSeekBar(horizontalSeek) { progress ->
            centerX = progress.toFloat() / POSITION_STEPS * 2f - 1f
        }
        bindSeekBar(verticalSeek) { progress ->
            centerY = progress.toFloat() / POSITION_STEPS * 2f - 1f
        }
        updateControls()
    }

    /** Sets a new source and resets zoom and position. The caller retains ownership of [value]. */
    fun setBitmap(value: Bitmap?) {
        bitmap = value
        scale = MIN_SCALE
        centerX = 0f
        centerY = 0f
        updateControls()
    }

    fun getScale(): Float = scale

    /** Values outside 1x..4x are clamped; non-finite values are ignored. */
    fun setScale(value: Float) {
        if (!value.isFinite()) return
        scale = value.coerceIn(MIN_SCALE, MAX_SCALE)
        updateControls()
    }

    fun getCenterX(): Float = centerX

    fun getCenterY(): Float = centerY

    /** Sets normalized horizontal/vertical offsets, each clamped to -1..1. */
    fun setCenter(x: Float, y: Float) {
        if (x.isFinite()) centerX = x.coerceIn(-1f, 1f)
        if (y.isFinite()) centerY = y.coerceIn(-1f, 1f)
        updateControls()
    }

    fun resetCrop() {
        scale = MIN_SCALE
        centerX = 0f
        centerY = 0f
        updateControls()
    }

    /**
     * Returns the visible square crop scaled uniformly to 512x512, or null without a source.
     * The returned bitmap is new and is owned by the caller.
     */
    fun createCrop512(): Bitmap? {
        val source = bitmap ?: return null
        val output = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        output.density = source.density
        val canvas = Canvas(output)
        canvas.drawBitmap(
            source,
            sourceCropRect(source),
            RectF(0f, 0f, OUTPUT_SIZE.toFloat(), OUTPUT_SIZE.toFloat()),
            preview.bitmapPaint
        )
        return output
    }

    private fun addControl(label: TextView, seekBar: SeekBar) {
        val labelParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
        addView(label, labelParams)
        addView(seekBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun createLabel(seekBar: SeekBar): TextView = TextView(context).apply {
        textSize = 14f
        setLabelFor(seekBar.id)
    }

    private fun createSeekBar(descriptionRes: Int, steps: Int): SeekBar = SeekBar(context).apply {
        id = View.generateViewId()
        max = steps
        keyProgressIncrement = if (steps == ZOOM_STEPS) 10 else 5
        contentDescription = context.getString(descriptionRes)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    private fun bindSeekBar(seekBar: SeekBar, applyProgress: (Int) -> Unit) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingControls) return
                applyProgress(progress)
                updateControls()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun updateControls() {
        updatingControls = true
        try {
            zoomSeek.progress = ((scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE) * ZOOM_STEPS)
                .roundToInt()
                .coerceIn(0, ZOOM_STEPS)
            horizontalSeek.progress = ((centerX + 1f) * 0.5f * POSITION_STEPS)
                .roundToInt()
                .coerceIn(0, POSITION_STEPS)
            verticalSeek.progress = ((centerY + 1f) * 0.5f * POSITION_STEPS)
                .roundToInt()
                .coerceIn(0, POSITION_STEPS)
        } finally {
            updatingControls = false
        }

        val source = bitmap
        val cropSide = source?.let { min(it.width, it.height) / scale } ?: 0f
        zoomSeek.isEnabled = source != null
        horizontalSeek.isEnabled = source != null && source.width > cropSide
        verticalSeek.isEnabled = source != null && source.height > cropSide

        zoomLabel.text = context.getString(
            R.string.profile_crop_zoom_value,
            (scale * 100f).roundToInt()
        )
        horizontalLabel.text = context.getString(
            R.string.profile_crop_horizontal_value,
            (horizontalSeek.progress * 100f / POSITION_STEPS).roundToInt()
        )
        verticalLabel.text = context.getString(
            R.string.profile_crop_vertical_value,
            (verticalSeek.progress * 100f / POSITION_STEPS).roundToInt()
        )
        zoomSeek.contentDescription = zoomLabel.text
        horizontalSeek.contentDescription = horizontalLabel.text
        verticalSeek.contentDescription = verticalLabel.text
        preview.contentDescription = context.getString(
            if (source == null) R.string.profile_crop_empty else R.string.profile_crop_preview
        )
        preview.invalidate()
    }

    private fun sourceCropRect(source: Bitmap): Rect {
        val side = min(source.width, source.height) / scale
        val left = (centerX + 1f) * 0.5f * (source.width - side)
        val top = (centerY + 1f) * 0.5f * (source.height - side)
        return Rect(left, top, left + side, top + side)
    }

    private fun panBy(deltaX: Float, deltaY: Float) {
        val source = bitmap ?: return
        val previewSide = preview.cropBounds.width()
        if (previewSide <= 0f) return

        val cropSide = min(source.width, source.height) / scale
        val horizontalRoom = source.width - cropSide
        val verticalRoom = source.height - cropSide
        var newX = centerX
        var newY = centerY
        if (horizontalRoom > 0f) {
            newX -= 2f * deltaX * cropSide / (previewSide * horizontalRoom)
        }
        if (verticalRoom > 0f) {
            newY -= 2f * deltaY * cropSide / (previewSide * verticalRoom)
        }
        setCenter(newX, newY)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private inner class CropPreviewView(context: Context) : View(context) {
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val cropBounds = RectF()
        private val circlePath = Path()
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 45, 45)
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(65, 255, 255, 255)
            strokeWidth = resources.displayMetrics.density
            style = Paint.Style.STROKE
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            strokeWidth = 1.5f * resources.displayMetrics.density
            style = Paint.Style.STROKE
        }
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        init {
            isClickable = true
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desiredSize = dp(280)
            val availableWidth = resolveSize(desiredSize, widthMeasureSpec)
            val availableHeight = resolveSize(desiredSize, heightMeasureSpec)
            val side = min(availableWidth, availableHeight)
            setMeasuredDimension(side, side)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateCropBounds()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (cropBounds.isEmpty) updateCropBounds()
            canvas.drawColor(Color.argb(120, 0, 0, 0))

            val centerX = cropBounds.centerX()
            val centerY = cropBounds.centerY()
            val radius = cropBounds.width() / 2f
            circlePath.reset()
            circlePath.addCircle(centerX, centerY, radius, Path.Direction.CW)

            val saveCount = canvas.save()
            canvas.clipPath(circlePath)
            canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
            bitmap?.let { source ->
                canvas.drawBitmap(source, sourceCropRect(source), cropBounds, bitmapPaint)
            }
            drawGrid(canvas)
            canvas.restoreToCount(saveCount)
            canvas.drawCircle(centerX, centerY, radius, borderPaint)
        }

        private fun drawGrid(canvas: Canvas) {
            for (index in 1..2) {
                val fraction = index / 3f
                val x = cropBounds.left + cropBounds.width() * fraction
                val y = cropBounds.top + cropBounds.height() * fraction
                canvas.drawLine(x, cropBounds.top, x, cropBounds.bottom, gridPaint)
                canvas.drawLine(cropBounds.left, y, cropBounds.right, y, gridPaint)
            }
        }

        private fun updateCropBounds() {
            val inset = dp(4).toFloat()
            val side = min(width, height) - inset * 2f
            if (side <= 0f) {
                cropBounds.setEmpty()
                return
            }
            val left = (width - side) / 2f
            val top = (height - side) / 2f
            cropBounds.set(left, top, left + side, top + side)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (bitmap == null) return false
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    panBy(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return false
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    companion object {
        const val OUTPUT_SIZE = 512
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        private const val ZOOM_STEPS = 300
        private const val POSITION_STEPS = 200
    }
}
