package com.tvlauncher

import android.content.Context

/**
 * Small helper that points the banner actions to the player app chosen by the user.
 *
 * The full banner manager (YouTube chart and safe movies) keeps its own cache and state; this
 * object only answers which player should open a suggested video.
 */
object ContentBannerManager {

    private const val PREFS_NAME = "launcher_banners"
    private const val KEY_PLAYER = "player_package"

    fun playerPackage(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PLAYER, DEFAULT_PLAYER).orEmpty()
    }

    fun setPlayerPackage(context: Context, packageName: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYER, packageName)
            .apply()
    }

    fun clearPlayer(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PLAYER)
            .apply()
    }

    companion object {
        const val DEFAULT_PLAYER = "com.google.android.youtube"
    }
}
