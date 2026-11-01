package com.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Reads the numbers shown in the profile dashboard: memory, processor, the connection
 * (type, network name, IP, the totals of the current month) and the internal storage.
 *
 * Everything is read-only and stays on the device. The "apps that use the most memory" list was
 * removed: TV system builds hide per-app usage from normal apps, so that ranking was always
 * incomplete and misleading. The processor section shows the total load of the TV.
 */
object SystemStats {

    data class NetworkInfo(
        val connected: Boolean,
        val type: String,
        val name: String,
        val ip: String,
        /** Counters since the TV boot; used to compute the instantaneous speed in the dialog. */
        val rxBytes: Long,
        val txBytes: Long,
        /** Totals already used in the current month (baseline reset at every month and reboot). */
        val monthRxBytes: Long,
        val monthTxBytes: Long
    )

    data class StorageInfo(val totalBytes: Long, val usedBytes: Long, val freeBytes: Long)

    data class Snapshot(
        val ramTotalBytes: Long,
        val ramUsedBytes: Long,
        val ramAvailableBytes: Long,
        val cpuPercent: Int,
        val network: NetworkInfo,
        val storage: StorageInfo
    )

    /** Raw TrafficStats counters, used to sample the instantaneous speed in the dialog. */
    fun rawTrafficBytes(): Pair<Long, Long> {
        return TrafficStats.getTotalRxBytes().coerceAtLeast(0L) to
            TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
    }

    /** Reads everything; call it from a background thread because the CPU sample waits. */
    fun snapshot(context: Context): Snapshot {
        val appContext = context.applicationContext
        val memory = memoryInfo(appContext)
        return Snapshot(
            ramTotalBytes = memory.first,
            ramUsedBytes = memory.second,
            ramAvailableBytes = memory.third,
            cpuPercent = cpuUsagePercent(),
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
        val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        return NetworkInfo(
            connected = connected,
            type = type,
            name = name,
            ip = localIpAddress(),
            rxBytes = rx,
            txBytes = tx,
            monthRxBytes = monthBytes(context, rx, true),
            monthTxBytes = monthBytes(context, tx, false)
        )
    }

    /**
     * Monthly counter: TrafficStats only knows the totals since the connection went up, so the
     * baseline of the month is stored locally and subtracted. When the system counter restarts
     * (TV rebooted before the month ends) the baseline is re-centred so the number never goes
     * negative, and a new month resets the countdown automatically.
     */
    private fun monthBytes(context: Context, current: Long, isRx: Boolean): Long {
        val monthKey = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
            .format(java.util.Date())
        return try {
            val prefs = context.getSharedPreferences("traffic_month", Context.MODE_PRIVATE)
            val key = if (isRx) "base_rx" else "base_tx"
            if (prefs.getString("month", null) != monthKey) {
                prefs.edit()
                    .putString("month", monthKey)
                    .putLong("base_rx", TrafficStats.getTotalRxBytes().coerceAtLeast(0L))
                    .putLong("base_tx", TrafficStats.getTotalTxBytes().coerceAtLeast(0L))
                    .apply()
            } else if (!prefs.contains(key)) {
                prefs.edit().putLong(key, current).apply()
            }
            val base = prefs.getLong(key, current)
            if (current < base) {
                // The system counter restarted (reboot): re-centre so the month total stays sane.
                prefs.edit().putLong(key, current).apply()
                0L
            } else {
                current - base
            }
        } catch (_: Exception) {
            0L
        }
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
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
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
