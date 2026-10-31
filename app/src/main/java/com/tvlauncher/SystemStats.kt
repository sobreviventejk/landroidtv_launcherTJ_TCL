package com.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Reads the numbers shown in the profile dashboard: memory, processor, the apps using the most
 * memory, the network (Wi-Fi or cable, name, IP and traffic) and the internal storage.
 *
 * Everything is read-only and stays on the device. Per-app CPU time is not available to a normal
 * app on recent Android versions (the system hides it), so the per-app section ranks apps by
 * memory and the processor section shows the total load of the TV.
 */
object SystemStats {

    data class AppUsage(
        val label: String,
        val packageName: String,
        val memoryBytes: Long,
        /** 0..100 share of the used memory (or of the recent usage when memory is hidden). */
        val percent: Int
    )

    data class NetworkInfo(
        val connected: Boolean,
        val type: String,
        val name: String,
        val ip: String,
        val rxBytes: Long,
        val txBytes: Long
    )

    data class StorageInfo(val totalBytes: Long, val usedBytes: Long, val freeBytes: Long)

    data class Snapshot(
        val ramTotalBytes: Long,
        val ramUsedBytes: Long,
        val ramAvailableBytes: Long,
        val cpuPercent: Int,
        val apps: List<AppUsage>,
        val network: NetworkInfo,
        val storage: StorageInfo
    )

    /** Reads everything; call it from a background thread because the CPU sample waits. */
    fun snapshot(context: Context): Snapshot {
        val appContext = context.applicationContext
        val memory = memoryInfo(appContext)
        return Snapshot(
            ramTotalBytes = memory.first,
            ramUsedBytes = memory.second,
            ramAvailableBytes = memory.third,
            cpuPercent = cpuUsagePercent(),
            apps = topApps(appContext, memory.second),
            network = networkInfo(appContext),
            storage = storageInfo()
        )
    }

    private fun memoryInfo(context: Context): Triple<Long, Long, Long> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            Triple(info.totalMem, info.totalMem - info.availMem, info.availMem)
        } catch (_: Exception) {
            Triple(0L, 0L, 0L)
        }
    }

    /** Total processor load of the device, sampled from /proc/stat. */
    private fun cpuUsagePercent(): Int {
        return try {
            val first = readCpuTicks() ?: return 0
            Thread.sleep(250)
            val second = readCpuTicks() ?: return 0
            val totalDiff = second.first - first.first
            val idleDiff = second.second - first.second
            if (totalDiff <= 0) 0 else
                (((totalDiff - idleDiff) * 100) / totalDiff).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }

    /** Returns (total ticks, idle ticks) from the first line of /proc/stat. */
    private fun readCpuTicks(): Pair<Long, Long>? {
        return try {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
                ?: return null
            val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return null
            val total = values.sum()
            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            total to idle
        } catch (_: Exception) {
            null
        }
    }

    private fun topApps(context: Context, usedBytes: Long): List<AppUsage> {
        val byProcess = topAppsByProcess(context, usedBytes)
        if (byProcess.isNotEmpty()) return byProcess
        // Some TVs hide the process list from normal apps. Fall back to the launcher's own
        // usage counter, which still tells which apps were opened the most.
        return topAppsByUsage(context)
    }

    private fun topAppsByProcess(context: Context, usedBytes: Long): List<AppUsage> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = context.packageManager
            val processes = am.runningAppProcesses ?: return emptyList()
            val perPackage = LinkedHashMap<String, Long>()
            processes.forEach { process ->
                if (process.pid == Process.myPid()) return@forEach
                if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                    return@forEach
                }
                val pkg = process.pkgList?.firstOrNull() ?: return@forEach
                if (pkg == context.packageName) return@forEach
                val memory = try {
                    am.getProcessMemoryInfo(intArrayOf(process.pid))
                        .firstOrNull()?.totalPss?.toLong() ?: 0L
                } catch (_: Exception) {
                    0L
                }
                perPackage[pkg] = (perPackage[pkg] ?: 0L) + memory * 1024L
            }
            val base = if (usedBytes > 0) usedBytes else perPackage.values.sum().coerceAtLeast(1L)
            perPackage.entries
                .sortedByDescending { it.value }
                .take(4)
                .map { (pkg, bytes) ->
                    AppUsage(
                        label = appLabel(pm, pkg),
                        packageName = pkg,
                        memoryBytes = bytes,
                        percent = ((bytes * 100) / base).toInt().coerceIn(0, 100)
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun topAppsByUsage(context: Context): List<AppUsage> {
        return try {
            val installed = AppManager(context).getInstalledApps().associateBy { it.packageName }
            val counts = context.getSharedPreferences("usage_counts", Context.MODE_PRIVATE)
            val ranked = counts.all.entries
                .mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }
                .sortedByDescending { it.second }
                .take(4)
            val max = ranked.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: return emptyList()
            ranked.mapNotNull { (key, count) ->
                val pkg = AppIdentifier.decode(key)?.packageName ?: return@mapNotNull null
                AppUsage(
                    label = installed[pkg]?.getDisplayName() ?: appLabel(context.packageManager, pkg),
                    packageName = pkg,
                    memoryBytes = 0L,
                    percent = ((count * 100) / max).coerceIn(0, 100)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appLabel(pm: PackageManager, packageName: String): String {
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun networkInfo(context: Context): NetworkInfo {
        var connected = false
        var type = "offline"
        var name = ""
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // getActiveNetwork/getNetworkCapabilities need API 23; older Androids stay offline.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                connected = caps != null
                if (caps != null) {
                    when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> type = "ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> type = "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> type = "mobile"
                        else -> type = "other"
                    }
                }
                if (connected) {
                    name = when (type) {
                        "wifi" -> wifiName(context).ifEmpty { "Wi-Fi" }
                        "ethernet" -> "Cabo"
                        "mobile" -> "Dados móveis"
                        else -> "Conectado"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return NetworkInfo(
            connected = connected,
            type = type,
            name = name,
            ip = localIpAddress(),
            rxBytes = TrafficStats.getTotalRxBytes().coerceAtLeast(0L),
            txBytes = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        )
    }

    @Suppress("DEPRECATION")
    private fun wifiName(context: Context): String {
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifi.connectionInfo?.ssid
                ?.trim('"')
                ?.takeUnless { it.isEmpty() || it == "<unknown ssid>" }
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun localIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun storageInfo(): StorageInfo {
        return try {
            val stat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                StatFs(Environment.getDataDirectory().absolutePath)
            } else {
                StatFs(Environment.getDataDirectory().absolutePath)
            }
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            StorageInfo(total, total - free, free)
        } catch (_: Exception) {
            StorageInfo(0L, 0L, 0L)
        }
    }

    /** Formats bytes as MB or GB, using the same separator as the rest of the launcher. */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format("%.1f GB", gb) else
            String.format("%.0f MB", bytes / (1024.0 * 1024.0))
    }

    /** Green for normal use, yellow for medium, red for high. */
    fun colorForPercent(context: Context, percent: Int): Int {
        val color = when {
            percent >= 60 -> android.R.color.holo_red_light
            percent >= 30 -> android.R.color.holo_orange_light
            else -> android.R.color.holo_green_light
        }
        return androidx.core.content.ContextCompat.getColor(context, color)
    }
}
