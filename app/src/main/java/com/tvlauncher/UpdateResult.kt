package com.tvlauncher

/**
 * Result of an in-app update check. Lives in the main source set so UI code can
 * reference it from the shared code and the updater.
 */
enum class UpdateResult {
    NoUpdate,
    InstallStarted,
    InstallPermissionNeeded,
    DownloadFailed,
    InvalidRelease,
    Skipped
}
