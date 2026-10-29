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

    fun canWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.WRITE_SECURE_SETTINGS"
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Returns true when the service is (now) listed as enabled. Never throws. */
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
            true
        } catch (_: Exception) {
            false
        }
    }
}
