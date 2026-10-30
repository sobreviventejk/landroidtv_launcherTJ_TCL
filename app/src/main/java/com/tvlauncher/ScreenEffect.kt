package com.tvlauncher

import androidx.annotation.StringRes

/**
 * Background effects that can be drawn behind the home screen content.
 *
 * All of them are pure canvas drawing (no images and no extra libraries), so they cost almost
 * nothing on TV hardware and work on top of any wallpaper. The colours always come from the
 * selected [Palette], which keeps the look consistent with the rest of the launcher.
 */
object ScreenEffect {

    const val NONE = 0
    const val AURORA = 1
    const val VIGNETTE = 2
    const val COLORED_VIGNETTE = 3
    const val MIST = 4
    const val STARS = 5
    const val WAVES = 6
    const val NEON = 7
    const val GRID = 8
    const val BOKEH = 9

    data class Entry(val id: Int, @StringRes val nameRes: Int)

    /** Order shown in the settings dialog. */
    val ALL: List<Entry> = listOf(
        Entry(NONE, R.string.effect_none),
        Entry(AURORA, R.string.effect_aurora),
        Entry(VIGNETTE, R.string.effect_vignette),
        Entry(COLORED_VIGNETTE, R.string.effect_colored_vignette),
        Entry(MIST, R.string.effect_mist),
        Entry(STARS, R.string.effect_stars),
        Entry(WAVES, R.string.effect_waves),
        Entry(NEON, R.string.effect_neon),
        Entry(GRID, R.string.effect_grid),
        Entry(BOKEH, R.string.effect_bokeh)
    )

    fun of(id: Int): Entry = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == AURORA }

    fun isVignette(id: Int): Boolean = id == VIGNETTE || id == COLORED_VIGNETTE
}
