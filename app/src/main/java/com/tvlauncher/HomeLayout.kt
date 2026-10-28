package com.tvlauncher

/** A named group of apps on the home screen. */
data class Category(val id: String, val name: String)

/**
 * Pure (Android-free) logic for the home layout: categories, grouping and reordering.
 * Kept separate from SharedPreferences and views so it can be unit tested on the JVM.
 *
 * The home screen keeps ONE flat, ordered list of app ids (see [AppManager]). Each app may
 * additionally be assigned to a category. A group is simply the apps of one category, in the
 * order they appear in the flat list.
 */
object HomeLayout {

    const val MAX_CATEGORY_NAME = 40

    private const val RECORD = '\u001E'
    private const val FIELD = '\u001D'

    // ---- Persistence format (plain strings, so no JSON dependency) -------------------------

    fun encodeCategories(categories: List<Category>): String =
        categories.joinToString(RECORD.toString()) { it.id + FIELD + it.name }

    fun decodeCategories(raw: String?): List<Category> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RECORD).mapNotNull { record ->
            val sep = record.indexOf(FIELD)
            if (sep <= 0) {
                null
            } else {
                val name = record.substring(sep + 1)
                if (name.isBlank()) null else Category(record.substring(0, sep), name)
            }
        }.distinctBy { it.id }
    }

    fun encodeAssignments(assignments: Map<String, String>): String =
        assignments.entries.joinToString(RECORD.toString()) { it.key + FIELD + it.value }

    fun decodeAssignments(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split(RECORD).forEach { record ->
            val sep = record.indexOf(FIELD)
            if (sep > 0 && sep < record.length - 1) {
                result[record.substring(0, sep)] = record.substring(sep + 1)
            }
        }
        return result
    }

    /** Removes control characters (used as delimiters), trims and caps the length. */
    fun sanitizeName(raw: String): String =
        raw.filter { !it.isISOControl() }.trim().take(MAX_CATEGORY_NAME)

    // ---- Grouping --------------------------------------------------------------------------

    /**
     * @property uncategorized apps without a (valid) category, in flat order. Shown first.
     * @property byCategory category id -> its apps in flat order, in category order.
     */
    data class Grouped(
        val uncategorized: List<String>,
        val byCategory: Map<String, List<String>>
    ) {
        /** The group (uncategorized or one category) that contains [appId]. */
        fun groupOf(appId: String): List<String> =
            byCategory.values.firstOrNull { appId in it } ?: uncategorized
    }

    fun group(
        orderedIds: List<String>,
        categories: List<Category>,
        assignments: Map<String, String>
    ): Grouped {
        val uncategorized = mutableListOf<String>()
        val byCategory = LinkedHashMap<String, MutableList<String>>()
        categories.forEach { byCategory[it.id] = mutableListOf() }
        orderedIds.forEach { id ->
            val target = assignments[id]?.let { byCategory[it] }
            if (target != null) target.add(id) else uncategorized.add(id)
        }
        return Grouped(uncategorized, byCategory)
    }

    // ---- Reordering ------------------------------------------------------------------------

    /**
     * Moves [appId] by [offset] positions inside its group ([groupIds], in flat order).
     * Apps outside the group keep their place in the flat list.
     * Returns the new flat order, or null when nothing changes.
     */
    fun moveInGroup(
        flat: List<String>,
        groupIds: List<String>,
        appId: String,
        offset: Int
    ): List<String>? {
        val from = groupIds.indexOf(appId)
        if (from < 0 || offset == 0) return null
        val to = (from + offset).coerceIn(0, groupIds.size - 1)
        if (to == from) return null

        val newGroup = groupIds.toMutableList()
        newGroup.removeAt(from)
        newGroup.add(to, appId)

        val members = groupIds.toSet()
        val slots = flat.indices.filter { flat[it] in members }
        if (slots.size != newGroup.size) return null

        val result = flat.toMutableList()
        slots.forEachIndexed { i, position -> result[position] = newGroup[i] }
        return result
    }

    /**
     * Offset that moves the app at [index] one grid row up ([direction] < 0) or down (> 0)
     * inside a group of [groupSize] apps laid out in rows of [columns]. Returns 0 when the app
     * is already in the first/last row. A short last row is handled by stopping at the last app.
     */
    fun rowOffset(index: Int, groupSize: Int, columns: Int, direction: Int): Int {
        if (columns <= 0 || index < 0 || index >= groupSize || direction == 0) return 0
        val last = groupSize - 1
        return if (direction < 0) {
            if (index < columns) 0 else -columns
        } else {
            if (index / columns == last / columns) 0 else minOf(columns, last - index)
        }
    }

    /** Puts [appId] right after the last member of [targetGroupIds] (or at the end if empty). */
    fun moveToGroupEnd(
        flat: List<String>,
        appId: String,
        targetGroupIds: List<String>
    ): List<String> {
        val result = flat.filter { it != appId }.toMutableList()
        val members = targetGroupIds.filter { it != appId }.toSet()
        val last = result.indexOfLast { it in members }
        if (last < 0) result.add(appId) else result.add(last + 1, appId)
        return result
    }
}
