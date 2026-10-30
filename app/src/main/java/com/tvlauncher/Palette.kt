package com.tvlauncher

/**
 * Colour sets that give the launcher its own identity: the focus glow of the app tiles,
 * the selected tab and the "aurora" glow in the background all follow the chosen palette.
 */
object Palette {

    /**
     * @property name label shown in the settings dialog
     * @property accent colour of focus/highlights
     * @property aurora three colours used by the background glow
     */
    data class Entry(val name: String, val accent: Int, val aurora: IntArray)

    val ALL: List<Entry> = listOf(
        Entry(
            "Índigo",
            0xFF818CF8.toInt(),
            intArrayOf(0xFF6366F1.toInt(), 0xFFA855F7.toInt(), 0xFF22D3EE.toInt())
        ),
        Entry(
            "Âmbar",
            0xFFFBBF24.toInt(),
            intArrayOf(0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFFFDE68A.toInt())
        ),
        Entry(
            "Esmeralda",
            0xFF34D399.toInt(),
            intArrayOf(0xFF10B981.toInt(), 0xFF14B8A6.toInt(), 0xFFA7F3D0.toInt())
        ),
        Entry(
            "Rosa",
            0xFFF472B6.toInt(),
            intArrayOf(0xFFEC4899.toInt(), 0xFF8B5CF6.toInt(), 0xFFFBCFE8.toInt())
        ),
        Entry(
            "Gelo",
            0xFF7DD3FC.toInt(),
            intArrayOf(0xFF0EA5E9.toInt(), 0xFF6366F1.toInt(), 0xFFE0F2FE.toInt())
        ),
        Entry(
            "Violeta",
            0xFFC084FC.toInt(),
            intArrayOf(0xFF9333EA.toInt(), 0xFF6D28D9.toInt(), 0xFFF0ABFC.toInt())
        ),
        Entry(
            "Turquesa",
            0xFF2DD4BF.toInt(),
            intArrayOf(0xFF0D9488.toInt(), 0xFF06B6D4.toInt(), 0xFF99F6E4.toInt())
        ),
        Entry(
            "Coral",
            0xFFFB7185.toInt(),
            intArrayOf(0xFFF43F5E.toInt(), 0xFFFB923C.toInt(), 0xFFFECDD3.toInt())
        ),
        Entry(
            "Dourado",
            0xFFFDE047.toInt(),
            intArrayOf(0xFFEAB308.toInt(), 0xFFF97316.toInt(), 0xFFFEF08A.toInt())
        ),
        Entry(
            "Azul noite",
            0xFF60A5FA.toInt(),
            intArrayOf(0xFF1D4ED8.toInt(), 0xFF3730A3.toInt(), 0xFF38BDF8.toInt())
        ),
        Entry(
            "Lima",
            0xFFA3E635.toInt(),
            intArrayOf(0xFF65A30D.toInt(), 0xFF16A34A.toInt(), 0xFFD9F99D.toInt())
        )
    )

    const val DEFAULT = 0

    fun of(index: Int): Entry = ALL[index.coerceIn(0, ALL.size - 1)]

    fun accent(index: Int): Int = of(index).accent
}
