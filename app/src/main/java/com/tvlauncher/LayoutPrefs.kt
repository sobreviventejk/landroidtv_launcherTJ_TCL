package com.tvlauncher

import android.content.Context
import java.util.UUID

/**
 * Persists the home layout settings: categories, which app belongs to which category,
 * and the icon size (number of grid columns). The app order itself lives in [AppManager].
 */
class LayoutPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private fun saveCategories(categories: List<Category>) {
        prefs.edit().putString(KEY_CATEGORIES, HomeLayout.encodeCategories(categories)).apply()
    }

    private fun saveAssignments(assignments: Map<String, String>) {
        prefs.edit().putString(KEY_ASSIGNMENTS, HomeLayout.encodeAssignments(assignments)).apply()
    }

    companion object {
        const val DEFAULT_COLUMNS = 5
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 8

        private const val PREFS_NAME = "launcher_layout"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_COLUMNS = "columns"
    }
}
