package com.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves and restores every setting of the launcher (categories, apps, wallpaper, greeting,
 * PIN...) as one JSON text, so a reinstall does not lose them.
 */
object SettingsBackup {

    private const val APP_ID = "com.tvlauncher"
    private const val FORMAT_VERSION = 1

    /** SharedPreferences files that make up the launcher's settings. */
    private val PREF_FILES = listOf(
        "launcher_layout",
        "selected_apps",
        "wallpaper_settings",
        "launcher_settings",
        "usage_counts",
        "launcher_pin"
    )

    fun export(context: Context): String {
        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("version", FORMAT_VERSION)

        val files = JSONObject()
        PREF_FILES.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONObject()
            for ((key, value) in prefs.all) {
                val entry = JSONObject()
                when (value) {
                    is String -> {
                        entry.put("t", "s")
                        entry.put("v", value)
                    }
                    is Boolean -> {
                        entry.put("t", "b")
                        entry.put("v", value)
                    }
                    is Int -> {
                        entry.put("t", "i")
                        entry.put("v", value)
                    }
                    is Long -> {
                        entry.put("t", "l")
                        entry.put("v", value)
                    }
                    is Float -> {
                        entry.put("t", "f")
                        entry.put("v", value.toDouble())
                    }
                    is Set<*> -> {
                        entry.put("t", "ss")
                        entry.put("v", JSONArray(value.map { it.toString() }))
                    }
                    else -> continue
                }
                entries.put(key, entry)
            }
            files.put(name, entries)
        }
        root.put("prefs", files)
        return root.toString(2)
    }

    /** Returns true when [text] was a valid backup and its settings were restored. */
    fun import(context: Context, text: String): Boolean {
        return try {
            val root = JSONObject(text)
            if (root.optString("app") != APP_ID) return false
            val files = root.getJSONObject("prefs")

            PREF_FILES.forEach { name ->
                val entries = files.optJSONObject(name) ?: return@forEach
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                editor.clear()
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val entry = entries.getJSONObject(key)
                    when (entry.getString("t")) {
                        "s" -> editor.putString(key, entry.getString("v"))
                        "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                        "i" -> editor.putInt(key, entry.getInt("v"))
                        "l" -> editor.putLong(key, entry.getLong("v"))
                        "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                        "ss" -> {
                            val array = entry.getJSONArray("v")
                            val set = HashSet<String>()
                            for (i in 0 until array.length()) set.add(array.getString(i))
                            editor.putStringSet(key, set)
                        }
                    }
                }
                editor.commit()
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
