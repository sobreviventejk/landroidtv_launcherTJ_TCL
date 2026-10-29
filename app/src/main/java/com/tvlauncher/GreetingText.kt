package com.tvlauncher

/** Pure helpers for the home greeting: time-of-day greeting and phrases that take turns. */
object GreetingText {

    /** A new phrase is picked every 10 minutes. */
    const val ROTATE_MS = 10 * 60 * 1000L

    fun timeGreeting(hour: Int): String = when (hour) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }

    /** First line of [raw]. With [byTime], a leading "Olá" becomes the greeting for [hour]. */
    fun title(raw: String, hour: Int, byTime: Boolean): String {
        val first = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        if (byTime && first.startsWith("Olá", ignoreCase = true)) {
            return timeGreeting(hour) + first.substring(3)
        }
        return first
    }

    /** Every non-blank line after the first one; one of them is shown at a time. */
    fun phrases(raw: String): List<String> =
        raw.lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() }

    fun pick(phrases: List<String>, nowMs: Long): String? {
        if (phrases.isEmpty()) return null
        val slot = (nowMs / ROTATE_MS).toInt()
        val size = phrases.size
        return phrases[((slot % size) + size) % size]
    }

    /** Text shown on screen: the title, then one phrase on the next line. */
    fun compose(raw: String, hour: Int, nowMs: Long, byTime: Boolean): String {
        val title = title(raw, hour, byTime)
        val phrase = pick(phrases(raw), nowMs)
        return if (phrase == null) title else title + "\n" + phrase
    }
}
