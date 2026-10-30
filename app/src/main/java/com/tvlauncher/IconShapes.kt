package com.tvlauncher

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Masks applied to app-icon views. DEFAULT is the modern rounded card from the reference layout;
 * the image itself keeps its original aspect ratio and is fitted by the ImageView.
 */
object IconShapes {

    const val DEFAULT = 0
    const val ROUNDED = 1
    const val CIRCLE = 2
    const val RECTANGLE = 3
    const val SOFT = 4

    data class Entry(val name: String)

    val ALL: List<Entry> = listOf(
        Entry("Padrão (cartão moderno)"),
        Entry("Arredondado"),
        Entry("Redondo"),
        Entry("Retângulo"),
        Entry("Super arredondado")
    )

    fun of(index: Int): Entry = ALL[index.coerceIn(0, ALL.size - 1)]

    /** Applies the mask of [shape] to [view], including the rounded DEFAULT card. */
    fun applyTo(view: View, shape: Int) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val width = v.width
                val height = v.height
                if (width <= 0 || height <= 0) {
                    outline.setRect(0, 0, 0, 0)
                    return
                }
                val smallest = minOf(width, height).toFloat()
                when (shape) {
                    DEFAULT -> outline.setRoundRect(0, 0, width, height, smallest * 0.16f)
                    ROUNDED -> outline.setRoundRect(0, 0, width, height, smallest * 0.20f)
                    CIRCLE -> outline.setOval(0, 0, width, height)
                    RECTANGLE -> outline.setRect(0, 0, width, height)
                    SOFT -> outline.setRoundRect(0, 0, width, height, smallest * 0.30f)
                    else -> outline.setRoundRect(0, 0, width, height, smallest * 0.16f)
                }
            }
        }
        // The outline is only known after layout; invalidate again when dimensions are available.
        view.invalidateOutline()
        view.post { view.invalidateOutline() }
    }
}
