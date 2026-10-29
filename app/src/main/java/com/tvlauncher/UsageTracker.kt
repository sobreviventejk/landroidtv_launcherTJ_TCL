package com.tvlauncher

import android.content.Context

/** Counts how many times each app is opened from this launcher (for the "Mais usados" row). */
object UsageTracker {

    private const val PREFS_NAME = "usage_counts"

    fun record(context: Context, appId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(appId, prefs.getInt(appId, 0) + 1).apply()
    }

    /** The most used apps among [valid], most used first. */
    fun top(context: Context, valid: Set<String>, limit: Int): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val counts = HashMap<String, Int>()
        for ((key, value) in prefs.all) {
            if (value is Int) counts[key] = value
        }
        return rank(counts, valid, limit)
    }

    fun rank(counts: Map<String, Int>, valid: Set<String>, limit: Int): List<String> {
        return counts.entries
            .filter { it.key in valid && it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }
}
