package com.tvlauncher

import android.content.Context
import java.util.UUID

/**
 * Persists the home layout settings: categories, which app belongs to which category,
 * the grid arrangement (columns x rows) and the global icon size. The app order itself lives
 * in [AppManager].
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

    /** Items per row of the home grid. The same value is used by every tab. */
    fun getColumns(): Int =
        prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun setColumns(columns: Int) {
        prefs.edit().putInt(KEY_COLUMNS, columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)).apply()
    }

    /**
     * Size of every icon, in percent of the base size (100 = default look). It is global: the
     * home grid, the categories and the "All apps" tab all follow it, and it changes only the
     * icon itself — never how many items fit per row.
     */
    fun getIconScalePercent(): Int =
        prefs.getInt(KEY_ICON_SCALE, DEFAULT_ICON_SCALE)
            .coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE)

    fun setIconScalePercent(percent: Int) {
        prefs.edit()
            .putInt(KEY_ICON_SCALE, percent.coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE))
            .apply()
    }

    /**
     * Visible rows of the home grid. Together with the columns this is the "N x N"
     * arrangement the user can choose (both limited to 15).
     */
    fun getRows(): Int = prefs.getInt(KEY_ROWS, DEFAULT_ROWS).coerceIn(MIN_ROWS, MAX_ROWS)

    fun setRows(rows: Int) {
        prefs.edit().putInt(KEY_ROWS, rows.coerceIn(MIN_ROWS, MAX_ROWS)).apply()
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

    // ---- Visual identity (palette and aurora glow) ------------------------------------------

    /** Index of the colour palette in [Palette.ALL]. */
    fun getPalette(): Int =
        prefs.getInt(KEY_PALETTE, Palette.DEFAULT).coerceIn(0, Palette.ALL.size - 1)

    fun setPalette(index: Int) {
        prefs.edit().putInt(KEY_PALETTE, index.coerceIn(0, Palette.ALL.size - 1)).apply()
    }

    /**
     * Background effect drawn behind the home screen, chosen in [ScreenEffect.ALL].
     *
     * Older versions only had the aurora glow on/off switch. When that old value exists it is
     * converted here, so nobody loses the configuration after an update.
     */
    fun getScreenEffect(): Int {
        val saved = prefs.getInt(KEY_SCREEN_EFFECT, -1)
        if (saved >= 0) return ScreenEffect.of(saved).id
        val oldAurora = prefs.getBoolean(KEY_AURORA, true)
        return if (oldAurora) ScreenEffect.AURORA else ScreenEffect.NONE
    }

    fun setScreenEffect(id: Int) {
        prefs.edit().putInt(KEY_SCREEN_EFFECT, ScreenEffect.of(id).id).apply()
    }

    /** True when the launcher should remind the user to set it as the default Home app. */
    fun shouldAskDefaultLauncher(): Boolean {
        val lastAsked = prefs.getLong(KEY_ASKED_DEFAULT_LAUNCHER, 0L)
        return System.currentTimeMillis() - lastAsked >= ASK_DEFAULT_COOLDOWN_MS
    }

    fun markDefaultLauncherAsked() {
        prefs.edit().putLong(KEY_ASKED_DEFAULT_LAUNCHER, System.currentTimeMillis()).apply()
    }

    /** Index of the icon shape in [IconShapes.ALL]. */
    fun getIconShape(): Int =
        prefs.getInt(KEY_ICON_SHAPE, IconShapes.DEFAULT).coerceIn(0, IconShapes.ALL.size - 1)

    fun setIconShape(index: Int) {
        prefs.edit()
            .putInt(KEY_ICON_SHAPE, index.coerceIn(0, IconShapes.ALL.size - 1))
            .apply()
    }

    // ---- Automatic messages under the greeting ----------------------------------------------

    /** When on, the phrase under the title comes from [RandomMessages] instead of the greeting. */
    fun isAutoMessagesEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_MESSAGES, false)

    fun setAutoMessagesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MESSAGES, enabled).apply()
    }

    /** Rotation interval of the automatic phrases, chosen by the user. */
    fun getMessageIntervalMs(): Long =
        prefs.getLong(KEY_MESSAGE_INTERVAL_MS, GreetingText.ROTATE_MS)
            .coerceIn(MIN_MESSAGE_INTERVAL_MS, MAX_MESSAGE_INTERVAL_MS)

    fun setMessageIntervalMs(intervalMs: Long) {
        prefs.edit().putLong(
            KEY_MESSAGE_INTERVAL_MS,
            intervalMs.coerceIn(MIN_MESSAGE_INTERVAL_MS, MAX_MESSAGE_INTERVAL_MS)
        ).apply()
    }

    /** Bit mask of the enabled [RandomMessages.Kind] values. Never zero. */
    fun getMessageKindsMask(): Int {
        val saved = prefs.getInt(KEY_MESSAGE_KINDS, RandomMessages.ALL_KINDS_MASK)
        val valid = saved and RandomMessages.ALL_KINDS_MASK
        return if (valid == 0) RandomMessages.ALL_KINDS_MASK else valid
    }

    fun setMessageKindsMask(mask: Int) {
        val valid = mask and RandomMessages.ALL_KINDS_MASK
        prefs.edit()
            .putInt(
                KEY_MESSAGE_KINDS,
                if (valid == 0) RandomMessages.ALL_KINDS_MASK else valid
            )
            .apply()
    }

    /** Who the automatic messages talk to. */
    fun getMessageTarget(): RandomMessages.Target {
        val name = prefs.getString(KEY_MESSAGE_TARGET, null)
        return RandomMessages.Target.values().firstOrNull { it.name == name }
            ?: RandomMessages.Target.ALTERNATE
    }

    fun setMessageTarget(target: RandomMessages.Target) {
        prefs.edit().putString(KEY_MESSAGE_TARGET, target.name).apply()
    }

    // ---- Weather ----------------------------------------------------------------------------

    /** Whether the weather next to the clock is shown. */
    fun isWeatherEnabled(): Boolean = prefs.getBoolean(KEY_WEATHER, true)

    fun setWeatherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEATHER, enabled).apply()
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
        const val MAX_COLUMNS = 15

        /** Range of the global icon size, in percent of the base size. */
        const val DEFAULT_ICON_SCALE = 100
        const val MIN_ICON_SCALE = 60
        const val MAX_ICON_SCALE = 130

        const val DEFAULT_ROWS = 3
        const val MIN_ROWS = 1
        const val MAX_ROWS = 15

        const val GREETING_HIDDEN = 0
        const val GREETING_MAX = 5
        const val DEFAULT_GREETING_SIZE = 2
        const val MAX_GREETING_LENGTH = 200
        const val MIN_MESSAGE_INTERVAL_MS = 60_000L
        const val MAX_MESSAGE_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** How long to wait before reminding the user about the default Home app again. */
        private const val ASK_DEFAULT_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

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
        private const val KEY_ICON_SCALE = "icon_scale_percent"
        private const val KEY_ROWS = "rows"
        private const val KEY_GREETING = "greeting_text"
        private const val KEY_GREETING_SIZE = "greeting_size"
        private const val KEY_LAST_TAB = "last_tab"
        private const val KEY_GREETING_BY_TIME = "greeting_by_time"
        private const val KEY_MOST_USED = "most_used_enabled"
        private const val KEY_PALETTE = "palette"
        private const val KEY_AURORA = "aurora_enabled"
        private const val KEY_SCREEN_EFFECT = "screen_effect"
        private const val KEY_ASKED_DEFAULT_LAUNCHER = "asked_default_launcher"
        private const val KEY_ICON_SHAPE = "icon_shape"
        private const val KEY_AUTO_MESSAGES = "auto_messages_enabled"
        private const val KEY_MESSAGE_INTERVAL_MS = "message_interval_ms"
        private const val KEY_MESSAGE_KINDS = "message_kinds"
        private const val KEY_MESSAGE_TARGET = "message_target"
        private const val KEY_WEATHER = "weather_enabled"
    }
}
