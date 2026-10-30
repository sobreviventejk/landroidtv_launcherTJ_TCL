package com.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Bulletproof Accessibility Service to intercept HOME button and detect when
 * the default OEM launcher window appears on screen (Google TV, Fire TV, MiTV, etc.)
 */
class TvHomeOverrideService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the Home key is being held long enough to open the recent apps window. */
    private var homeLongPressFired = false

    private var knownStockLauncherPackages = mutableSetOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.katniss",
        "com.amazon.tv.launcher",
        "com.amazon.firehomestarter",
        "com.mitv.tvhome",
        "com.xiaomi.mitv.tvhome",
        "com.tcl.tvlauncher"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // Least privilege: we only need the Home key filter + window-state events.
            // No window-content retrieval is required for this feature.
            info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            info.notificationTimeout = 50
            serviceInfo = info
        } catch (_: Exception) {
        }
        discoverHomePackages()
    }

    private fun discoverHomePackages() {
        try {
            val pm = packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val homeResolvers = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
            homeResolvers.forEach { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                // Only pre-installed (system) HOME apps may be hijacked. A third-party app that
                // merely declares the HOME category is never added, so it cannot make the
                // launcher reopen itself in a loop.
                val flags = resolveInfo.activityInfo.applicationInfo?.flags ?: 0
                val isSystem = (flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                if (pkg != packageName && isSystem && !isWhitelistedPackage(pkg)) {
                    knownStockLauncherPackages.add(pkg)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (!isHomeOverrideEnabled(this)) return false

        val keyCode = event.keyCode
        // Only the Home key is intercepted. Other keys (TV Guide, TV/Input,
        // App Switch / Recents) are left untouched so they keep their meaning.
        val isHomeKey = keyCode == KeyEvent.KEYCODE_HOME

        if (isHomeKey) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        homeLongPressFired = false
                        mainHandler.removeCallbacks(homeLongPressRunnable)
                        mainHandler.postDelayed(homeLongPressRunnable, HOME_LONG_PRESS_MS)
                    }
                }
                KeyEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(homeLongPressRunnable)
                    if (!homeLongPressFired) launchHome()
                    homeLongPressFired = false
                }
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    /**
     * Holding Home for [HOME_LONG_PRESS_MS] opens the "apps abertos" window. A short press still
     * brings this launcher to the front.
     */
    private val homeLongPressRunnable = Runnable {
        homeLongPressFired = true
        openRecents()
    }

    private fun openRecents() {
        try {
            val intent = Intent(this, RecentsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isHomeOverrideEnabled(this)) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return

            // If user or system recently requested settings/system activity, pause window hijack
            if (System.currentTimeMillis() < pauseOverrideUntil) return

            // Whitelist check
            if (isWhitelistedPackage(pkgName)) return

            // Check if class name is an Android system settings dialog/activity
            val className = event.className?.toString()?.lowercase() ?: ""
            if (className.contains("setting") || className.contains("preference") || className.contains("dialog")) {
                return
            }

            if (pkgName != packageName && knownStockLauncherPackages.contains(pkgName)) {
                // Stock OEM Launcher intercepted on screen! Pull our launcher to front.
                mainHandler.removeCallbacksAndMessages(null)
                mainHandler.postDelayed({
                    if (System.currentTimeMillis() >= pauseOverrideUntil) {
                        launchHome()
                    }
                }, 30)
            }
        }
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun launchHome() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
            if (isSuppressStockLauncherEnabled(this)) {
                RamOptimizationHelper.suppressStockLauncherRam(applicationContext)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PREFS_NAME = "launcher_settings"
        private const val PREF_HOME_OVERRIDE = "home_override_enabled"
        private const val PREF_SUPPRESS_STOCK = "suppress_stock_launcher"

        /** Both options come turned on: this launcher is useless without the Home key override. */
        const val DEFAULT_HOME_OVERRIDE = true
        const val DEFAULT_SUPPRESS_STOCK = true

        /** Holding the Home key for this long opens the recent apps window. */
        const val HOME_LONG_PRESS_MS = 700L

        @Volatile
        private var pauseOverrideUntil: Long = 0L

        private val SYSTEM_WHITELIST_PACKAGES = setOf(
            "com.android.settings",
            "com.android.tv.settings",
            "com.google.android.tv.settings",
            "com.amazon.tv.settings",
            "com.amazon.device.settings",
            "com.tcl.settings",
            "com.xiaomi.mitv.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "android"
        )

        fun pauseOverrideFor(durationMs: Long) {
            pauseOverrideUntil = System.currentTimeMillis() + durationMs
        }

        fun isWhitelistedPackage(packageName: String): Boolean {
            if (SYSTEM_WHITELIST_PACKAGES.contains(packageName)) return true
            if (packageName.contains(".settings") || packageName.endsWith(".settings")) return true
            return false
        }

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expectedClass = TvHomeOverrideService::class.java.name
            val shortClass = "." + expectedClass.substringAfterLast('.')
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            // Only an exact "package/class" entry counts. A substring match would accept an
            // unrelated service whose name merely contains this package name.
            return enabledServices.split(':').any { entry ->
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) return@any false
                val pkg = trimmed.substringBefore('/')
                val cls = trimmed.substringAfter('/', "")
                pkg == context.packageName && (cls == expectedClass || cls == shortClass)
            }
        }

        fun isHomeOverrideEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_HOME_OVERRIDE, DEFAULT_HOME_OVERRIDE)
        }

        fun setHomeOverrideEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_HOME_OVERRIDE, enabled).apply()
        }

        fun isSuppressStockLauncherEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_SUPPRESS_STOCK, DEFAULT_SUPPRESS_STOCK)
        }

        fun setSuppressStockLauncherEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_SUPPRESS_STOCK, enabled).apply()
        }
    }
}
