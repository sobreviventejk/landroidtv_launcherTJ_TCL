package com.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-applies the Home-key setup after the TV reboots.
 *
 * Some TVs disable the accessibility service when they turn on, and without the service the
 * Home key never reaches this launcher, so the app itself has no chance to fix it. This receiver
 * is the only moment where the launcher can turn the service back on automatically (when the
 * one-time ADB permission was granted).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        if (TvHomeOverrideService.isAccessibilityServiceEnabled(context)) return
        AccessibilityEnabler.tryEnable(context)
    }
}
