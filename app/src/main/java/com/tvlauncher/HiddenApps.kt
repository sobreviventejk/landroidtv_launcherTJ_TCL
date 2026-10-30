package com.tvlauncher

import android.content.Context

/** Apps deliberately hidden by the user. This never uninstalls or changes Android app data. */
object HiddenApps {
    private const val PREFS_NAME = "launcher_hidden_apps"
    private const val KEY_IDS = "ids"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ids(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()

    fun isHidden(context: Context, appId: String): Boolean = appId in ids(context)

    fun setHidden(context: Context, appId: String, hidden: Boolean) {
        val updated = ids(context).toMutableSet()
        if (hidden) updated.add(appId) else updated.remove(appId)
        prefs(context).edit().putStringSet(KEY_IDS, updated).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_IDS).apply()
    }
}
