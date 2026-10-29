package com.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object RamOptimizationHelper {

    // Common OEM / Stock TV launcher package names (Google TV, Android TV, Fire TV, Xiaomi, etc.)
    private val KNOWN_STOCK_LAUNCHERS = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx", // Google TV launcher
        "com.amazon.tv.launcher", // Fire TV launcher
        "com.amazon.firehomestarter",
        "com.mitv.tvhome", // Xiaomi PatchWall
        "com.xiaomi.mitv.tvhome",
        "com.tcl.tvlauncher"
    )

    fun suppressStockLauncherRam(context: Context) {
        if (!TvHomeOverrideService.isSuppressStockLauncherEnabled(context)) return

        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            val pm = context.packageManager

            pickTargets(context, pm).forEach { packageName ->
                try {
                    activityManager.killBackgroundProcesses(packageName)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * One-tap memory cleaning: stops the background processes of the pre-installed TV launchers
     * and of every app the user installed (except this launcher). Returns how many were asked to stop.
     */
    fun freeMemoryNow(context: Context): Int {
        var count = 0
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0
            val pm = context.packageManager
            val targets = linkedSetOf<String>()
            targets.addAll(pickTargets(context, pm))
            pm.getInstalledApplications(0).forEach { info ->
                val isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!isSystemApp &&
                    info.packageName != context.packageName &&
                    !TvHomeOverrideService.isWhitelistedPackage(info.packageName)
                ) {
                    targets.add(info.packageName)
                }
            }
            targets.forEach { packageName ->
                try {
                    activityManager.killBackgroundProcesses(packageName)
                    count++
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return count
    }

    /** Free memory in megabytes, as reported by the system. */
    fun availableMemoryMb(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.availMem / (1024L * 1024L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Chooses which packages may be killed. Only pre-installed (system) launchers
     * are ever targeted — user-installed launchers (e.g. a third-party launcher the
     * user picked) are never touched, and settings/installer packages are whitelisted.
     */
    private fun pickTargets(context: Context, pm: PackageManager): Set<String> {
        val ownPackage = context.packageName
        val targets = mutableSetOf<String>()

        KNOWN_STOCK_LAUNCHERS.forEach { pkg ->
            if (pkg != ownPackage &&
                !TvHomeOverrideService.isWhitelistedPackage(pkg) &&
                isInstalled(pm, pkg)
            ) {
                targets.add(pkg)
            }
        }

        // Dynamically discovered HOME-capable apps: only system / updated-system apps.
        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL).forEach { resolveInfo ->
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg == ownPackage || TvHomeOverrideService.isWhitelistedPackage(pkg)) {
                return@forEach
            }
            if (pkg in targets) {
                return@forEach
            }
            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                null
            } ?: return@forEach
            val isSystemApp = (appInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            if (isSystemApp) {
                targets.add(pkg)
            }
        }
        return targets
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getApplicationInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
