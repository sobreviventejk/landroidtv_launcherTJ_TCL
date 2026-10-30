package com.tvlauncher

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Shapes the app icons can be clipped to. "Default" keeps the icon exactly as the app ships it;
 * the others apply a mask (rounded square, circle, rectangle...) using the view outline, so no
 * bitmap is copied and the launcher stays light.
 */
object IconShapes {

    const val DEFAULT = 0
    const val ROUNDED = 1
    const val CIRCLE = 2
    const val RECTANGLE = 3
    const val SOFT = 4

    data class Entry(val name: String)

    val ALL: List<Entry> = listOf(
        Entry("Padrão (ícone do app)"),
        Entry("Arredondado"),
        Entry("Redondo"),
        Entry("Retângulo"),
        Entry("Super arredondado")
    )

    fun of(index: Int): Entry = ALL[index.coerceIn(0, ALL.size - 1)]

    /** Applies the mask of [shape] to [view]; [DEFAULT] disables the clipping entirely. */
    fun applyTo(view: View, shape: Int) {
        if (shape == DEFAULT) {
            view.clipToOutline = false
            view.outlineProvider = null
            return
        }
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val width = v.width
                val height = v.height
                when (shape) {
                    ROUNDED -> outline.setRoundRect(0, 0, width, height, width * 0.20f)
                    CIRCLE -> outline.setOval(0, 0, width, height)
                    RECTANGLE -> outline.setRect(0, 0, width, height)
                    SOFT -> outline.setRoundRect(0, 0, width, height, width * 0.30f)
                    else -> outline.setRect(0, 0, width, height)
                }
            }
        }
        // The outline is only known after the view is laid out (width/height may still be 0 here).
        view.invalidateOutline()
        view.post { view.invalidateOutline() }
    }
}
