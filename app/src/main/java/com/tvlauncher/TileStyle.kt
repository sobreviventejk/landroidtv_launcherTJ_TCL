package com.tvlauncher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The one visual rule shared by every app tile and video card.
 *
 * Cards are wide rounded rectangles like Google TV. The same calculation is used on the home
 * grid, categories, most-used row and all-apps grid, so a category can never silently produce a
 * different icon format. Focus adds a proportional ring, a small zoom and a real coloured glow
 * (native elevation shadow) around the card itself.
 */
object TileStyle {

    private const val CARD_ASPECT = 1.60f

    data class CardSize(val widthPx: Int, val heightPx: Int)

    /** Fits a wide card in a grid cell, reserving the lower part of the cell for its name. */
    fun size(
        slotWidthPx: Int,
        slotHeightPx: Int,
        iconScalePercent: Int,
        density: Float
    ): CardSize {
        val maxWidth = slotWidthPx.coerceAtLeast(1) * 0.90f
        val maxHeight = slotHeightPx.coerceAtLeast(1) * 0.68f
        val factor = iconScalePercent
            .coerceIn(LayoutPrefs.MIN_ICON_SCALE, LayoutPrefs.MAX_ICON_SCALE) / 100f
        // Fixed physical base: every category gets the same card, not a different percentage
        // of whatever cell that category happens to have. Only unusually small cells shrink it.
        val standardWidth = 184f * density * factor
        val width = min(standardWidth, min(maxWidth, maxHeight * CARD_ASPECT))
            .coerceAtLeast(28f * density)
        val height = width / CARD_ASPECT
        return CardSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
    }

    /** Applies the shared card shape and focus glow. Safe to call on every focus change. */
    fun apply(
        view: View,
        size: CardSize,
        focused: Boolean,
        accent: Int,
        shape: Int = IconShapes.DEFAULT,
        moving: Boolean = false
    ) {
        val density = view.resources.displayMetrics.density
        val smallest = min(size.widthPx, size.heightPx).coerceAtLeast(1)
        val radiusFactor = when (shape) {
            IconShapes.ROUNDED -> 0.20f
            IconShapes.SOFT -> 0.28f
            else -> 0.14f
        }
        val highlighted = focused || moving
        view.background = GradientDrawable().apply {
            cornerRadius = smallest * radiusFactor
            if (highlighted) {
                setColor(
                    Color.argb(
                        0x38,
                        Color.red(accent),
                        Color.green(accent),
                        Color.blue(accent)
                    )
                )
                setStroke(
                    (smallest * 0.028f).roundToInt().coerceAtLeast((2 * density).toInt()),
                    accent
                )
            } else {
                setColor(0xB01B1E24.toInt())
                setStroke(
                    (smallest * 0.009f).roundToInt().coerceAtLeast(1),
                    0x30FFFFFF
                )
            }
        }

        if (shape == IconShapes.CIRCLE) {
            IconShapes.applyTo(view, shape)
        } else {
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            view.clipToOutline = true
            view.invalidateOutline()
        }

        // Android 9+ can colour both shadow components. Together they form the soft halo seen
        // around the selected card in the reference instead of a border detached from the icon.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val glow = if (highlighted) accent else Color.BLACK
            view.outlineAmbientShadowColor = glow
            view.outlineSpotShadowColor = glow
        }
        view.elevation = if (highlighted) 16f * density else 2f * density
        val targetScale = if (highlighted) 1.065f else 1f
        if (kotlin.math.abs(view.scaleX - targetScale) > 0.001f) {
            view.animate().scaleX(targetScale).scaleY(targetScale).setDuration(100L).start()
        }

        (view as? ImageView)?.let { image ->
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = (smallest * 0.075f).roundToInt()
            image.setPadding(padding, padding, padding, padding)
        }
    }
}
