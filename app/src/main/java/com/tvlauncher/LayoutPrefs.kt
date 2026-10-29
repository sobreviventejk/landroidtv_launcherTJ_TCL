package com.tvlauncher

import android.content.Context
import java.util.UUID

/**
 * Persists the home layout settings: categories, which app belongs to which category,
 * and the icon size (number of grid columns). The app order itself lives in [AppManager].
 */
class LayoutPrefs(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCategories(): List<Category> =
        HomeLayout.decodeCategories(prefs.getString(KEY_CATEGORIES, null))

    /** App identifier -> category id. */
    fun getAssignments(): Map<String, String> =
        HomeLayout.decodeAssignments(prefs.getString(KEY_ASSIGNMENTS, null))

    /** Creates a category at the end of the list. Returns null if the name is blank. */
    fun addCategory(rawName: String): Category? {
        val name = HomeLayout.sanitizeName(rawName)
        if (name.isEmpty()) return null
        val category = Category(UUID.randomUUID().toString().take(8), name)
        saveCategories(getCategories() + category)
        return category
    }

    fun renameCategory(id: String, rawName: String): Boolean {
        val name = HomeLayout.sanitizeName(rawName)
        if (name.isEmpty()) return false
        val categories = getCategories()
        if (categories.none { it.id == id }) return false
        saveCategories(categories.map { if (it.id == id) it.copy(name = name) else it })
        return true
    }

    /** Deletes a category; its apps go back to the main (uncategorized) group. */
    fun deleteCategory(id: String) {
        saveCategories(getCategories().filter { it.id != id })
        saveAssignments(getAssignments().filterValues { it != id })
    }

    /** Moves a category earlier (negative offset) or later (positive offset). */
    fun moveCategory(id: String, offset: Int): Boolean {
        val categories = getCategories().toMutableList()
        val from = categories.indexOfFirst { it.id == id }
        if (from < 0) return false
        val to = (from + offset).coerceIn(0, categories.size - 1)
        if (to == from) return false
        val moved = categories.removeAt(from)
        categories.add(to, moved)
        saveCategories(categories)
        return true
    }

    /** Assigns an app to a category, or to none when [categoryId] is null. */
    fun assign(appId: String, categoryId: String?) {
        val assignments = LinkedHashMap(getAssignments())
        if (categoryId == null) {
            assignments.remove(appId)
        } else {
            assignments[appId] = categoryId
        }
        saveAssignments(assignments)
    }

    /** Drops assignments of apps that are no longer on the home screen. */
    fun pruneAssignments(validAppIds: Set<String>) {
        val current = getAssignments()
        val pruned = current.filterKeys { it in validAppIds }
        if (pruned.size != current.size) {
            saveAssignments(pruned)
        }
    }

    /** Icons per row: more columns = smaller icons. */
    fun getColumns(): Int =
        prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun setColumns(columns: Int) {
        prefs.edit().putInt(KEY_COLUMNS, columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)).apply()
    }

    // ---- Greeting (short text at the top of the home screen) --------------------------------

    /** The greeting text. First line is the title, the rest is the body. */
    fun getGreeting(): String =
        prefs.getString(KEY_GREETING, null) ?: appContext.getString(R.string.greeting_default)

    /** Saves the greeting. An empty text goes back to the default one. */
    fun setGreeting(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove(KEY_GREETING).apply()
        } else {
            prefs.edit().putString(KEY_GREETING, clean.take(MAX_GREETING_LENGTH)).apply()
        }
    }

    /** 0 = hidden, 1..5 = from very small to very large. */
    fun getGreetingSize(): Int =
        prefs.getInt(KEY_GREETING_SIZE, DEFAULT_GREETING_SIZE).coerceIn(GREETING_HIDDEN, GREETING_MAX)

    fun setGreetingSize(level: Int) {
        prefs.edit().putInt(KEY_GREETING_SIZE, level.coerceIn(GREETING_HIDDEN, GREETING_MAX)).apply()
    }

    /** When on, a leading "Olá" in the greeting becomes Bom dia / Boa tarde / Boa noite. */
    fun isGreetingByTime(): Boolean = prefs.getBoolean(KEY_GREETING_BY_TIME, false)

    fun setGreetingByTime(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GREETING_BY_TIME, enabled).apply()
    }

    /** Whether the "Mais usados" row is shown on the first tab. */
    fun isMostUsedEnabled(): Boolean = prefs.getBoolean(KEY_MOST_USED, true)

    fun setMostUsedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MOST_USED, enabled).apply()
    }

    /** Tab that was open last (home, a category id or the "all apps" tab). */
    fun getLastTab(): String? = prefs.getString(KEY_LAST_TAB, null)

    fun setLastTab(tab: String) {
        prefs.edit().putString(KEY_LAST_TAB, tab).apply()
    }

    private fun saveCategories(categories: List<Category>) {
        prefs.edit().putString(KEY_CATEGORIES, HomeLayout.encodeCategories(categories)).apply()
    }

    private fun saveAssignments(assignments: Map<String, String>) {
        prefs.edit().putString(KEY_ASSIGNMENTS, HomeLayout.encodeAssignments(assignments)).apply()
    }

    companion object {
        const val DEFAULT_COLUMNS = 5
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 10

        const val GREETING_HIDDEN = 0
        const val GREETING_MAX = 5
        const val DEFAULT_GREETING_SIZE = 2
        const val MAX_GREETING_LENGTH = 200

        /** Text size (sp) of the greeting title for a size [level]. */
        fun greetingTitleSp(level: Int): Float = when (level.coerceIn(GREETING_HIDDEN, GREETING_MAX)) {
            1 -> 12f
            2 -> 14f
            3 -> 18f
            4 -> 22f
            5 -> 28f
            else -> 0f
        }

        /** Text size (sp) of the greeting body for a size [level]. */
        fun greetingBodySp(level: Int): Float = when (level.coerceIn(GREETING_HIDDEN, GREETING_MAX)) {
            1 -> 10f
            2 -> 11f
            3 -> 14f
            4 -> 17f
            5 -> 21f
            else -> 0f
        }

        private const val PREFS_NAME = "launcher_layout"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_COLUMNS = "columns"
        private const val KEY_GREETING = "greeting_text"
        private const val KEY_GREETING_SIZE = "greeting_size"
        private const val KEY_LAST_TAB = "last_tab"
        private const val KEY_GREETING_BY_TIME = "greeting_by_time"
        private const val KEY_MOST_USED = "most_used_enabled"
    }
}
