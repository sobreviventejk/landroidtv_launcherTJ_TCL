package com.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Turns the Home-key accessibility service on by itself when the app was allowed (once, by ADB)
 * to write secure settings. This also brings the service back if the TV switched it off.
 *
 * One-time command, run from a computer or from Termux with wireless debugging:
 *   adb shell pm grant com.tvlauncher android.permission.WRITE_SECURE_SETTINGS
 */
object AccessibilityEnabler {

    const val ADB_COMMAND =
        "adb shell pm grant com.tvlauncher android.permission.WRITE_SECURE_SETTINGS"

    /** Command that makes this launcher the default Home app on TVs without the picker screen. */
    const val HOME_ADB_COMMAND =
        "adb shell cmd package set-home-activity com.tvlauncher/.MainActivity"

    private const val PROMPT_PREFS = "accessibility_enabler"
    private const val KEY_LAST_ASKED = "last_asked_ms"
    private const val KEY_LAST_VERSION = "last_version_code"

    /** How long to wait before reminding the user again (the service may still be off). */
    private const val ASK_COOLDOWN_MS = 3L * 24 * 60 * 60 * 1000

    fun canWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.WRITE_SECURE_SETTINGS"
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns true when the service is (now) listed as enabled. Never throws.
     *
     * Writing [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] is not always enough: some TVs
     * immediately turn the service off again. That is why the value is written and then read
     * back, so the caller knows whether the automatic activation really worked.
     */
    fun tryEnable(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        return try {
            val component = ComponentName(context, TvHomeOverrideService::class.java)
                .flattenToString()
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val entries = current.split(':').filter { it.isNotEmpty() }
            if (entries.none { it.equals(component, ignoreCase = true) }) {
                val updated = (entries + component).joinToString(":")
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated
                )
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            TvHomeOverrideService.isAccessibilityServiceEnabled(context)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when the launcher should remind the user about the accessibility service. It asks on
     * the first start, right after an app update (Android turns accessibility services off when
     * an app is updated) and then at most once every [ASK_COOLDOWN_MS]. This keeps the Home
     * button working without a popup on every return.
     */
    fun shouldAskToEnable(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PROMPT_PREFS, Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt(KEY_LAST_VERSION, 0)
        if (lastVersion != BuildConfig.VERSION_CODE) return true
        val lastAsked = prefs.getLong(KEY_LAST_ASKED, 0L)
        return System.currentTimeMillis() - lastAsked >= ASK_COOLDOWN_MS
    }

    fun markAskedToEnable(context: Context) {
        context.getSharedPreferences(PROMPT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ASKED, System.currentTimeMillis())
            .putInt(KEY_LAST_VERSION, BuildConfig.VERSION_CODE)
            .apply()
    }
}
