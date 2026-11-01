package com.tvlauncher

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** An installed app (or an app shortcut) shown by the launcher. */
data class AppInfo(
    val packageName: String,
    val appName: String,
    var icon: Drawable? = null,
    private var appManager: AppManager? = null,
    val shortcutId: String? = null,
    val shortcutLabel: String? = null,
    val shortcutIcon: Drawable? = null
) {
    val isShortcut: Boolean
        get() = shortcutId != null

    fun setAppManager(manager: AppManager) {
        appManager = manager
    }

    /**
     * Returns the original icon drawable. Resizing is left to the ImageView (FIT_CENTER), so a
     * rectangular icon is never stretched into a square and no extra bitmap reduces its quality.
     * [iconSizePx] remains in the API because callers also use it to size the destination view.
     */
    @Suppress("UNUSED_PARAMETER")
    fun getIcon(packageManager: PackageManager, iconSizePx: Int = 0): Drawable {
        if (icon == null) {
            val cacheKey = if (isShortcut) {
                "${packageName}_${shortcutId}"
            } else {
                packageName
            }
            val cachedIcon = appManager?.getCachedIcon(cacheKey)
            if (cachedIcon != null) {
                icon = cachedIcon
            } else {
                try {
                    icon = shortcutIcon ?: packageManager.getApplicationIcon(packageName)
                    appManager?.cacheIcon(cacheKey, icon!!)
                } catch (_: Exception) {
                    icon = packageManager.defaultActivityIcon
                }
            }
        }
        return icon!!
    }

    private var tileArtwork: Drawable? = null

    /**
     * Wide TV banner/logo when the app provides one, otherwise the normal app icon. This is what
     * makes Netflix, YouTube and similar apps look like the rectangular cards of Google TV.
     */
    fun getTileArtwork(packageManager: PackageManager, iconSizePx: Int = 0): Drawable {
        if (isShortcut) return getIcon(packageManager, iconSizePx)
        tileArtwork?.let { return it }
        val cacheKey = "tile:$packageName"
        appManager?.getCachedIcon(cacheKey)?.let {
            tileArtwork = it
            return it
        }
        val banner = try {
            packageManager.getApplicationBanner(packageName)
        } catch (_: Exception) {
            null
        }
        val artwork = banner ?: getIcon(packageManager, iconSizePx)
        tileArtwork = artwork
        appManager?.cacheIcon(cacheKey, artwork)
        return artwork
    }

    fun clearIcon() {
        icon = null
        tileArtwork = null
    }

    fun getDisplayName(): String {
        return if (isShortcut && !shortcutLabel.isNullOrEmpty()) {
            shortcutLabel!!
        } else {
            appName
        }
    }
}
