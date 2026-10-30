package com.tvlauncher

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * "Apps abertos" window, shown when the Home key is held for more than
 * [MainActivity.HOME_LONG_PRESS_MS]. It only lists apps the user installed: the system, the
 * stock launcher and this launcher itself are never offered, so the home screen cannot be
 * closed by accident.
 */
class RecentsActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private val adapter = RecentAdapter { entry -> closeApp(entry) }
    private var entries: List<RecentApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recents)
        list = findViewById(R.id.recentsList)
        hint = findViewById(R.id.recentsHint)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.recentsEndAll).setOnClickListener { closeAll() }
        findViewById<View>(R.id.recentsClose).setOnClickListener { finish() }

        hint.text = getString(R.string.recents_loading)
        loadRecents()
    }

    private fun loadRecents() {
        Thread {
            val found = recentApps(this)
            handler.post {
                if (isFinishing || isDestroyed) return@post
                entries = found
                adapter.submit(found)
                hint.text = if (found.isEmpty()) {
                    getString(R.string.recents_none)
                } else {
                    getString(R.string.recents_hint)
                }
            }
        }.start()
    }

    private fun closeApp(entry: RecentApp) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            am.killBackgroundProcesses(entry.packageName)
        } catch (_: Exception) {
        }
        CrashLog.log(this, "Apps abertos: encerrado ${entry.packageName}")
        Toast.makeText(this, getString(R.string.recents_ended, entry.label), Toast.LENGTH_SHORT)
            .show()
        entries = entries.filterNot { it.packageName == entry.packageName }
        adapter.submit(entries)
        if (entries.isEmpty()) hint.text = getString(R.string.recents_none)
    }

    private fun closeAll() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        entries.forEach { entry ->
            try {
                am.killBackgroundProcesses(entry.packageName)
            } catch (_: Exception) {
            }
        }
        CrashLog.log(this, "Apps abertos: encerrados ${entries.size} apps")
        entries = emptyList()
        adapter.submit(entries)
        hint.text = getString(R.string.recents_none)
        Toast.makeText(this, R.string.recents_all_ended, Toast.LENGTH_SHORT).show()
    }

    data class RecentApp(val label: String, val packageName: String, val lastUsed: Long)

    private fun recentApps(context: Context): List<RecentApp> {
        val pm = context.packageManager
        val homePackages = homePackages(pm)
        val found = LinkedHashMap<String, RecentApp>()

        // Preferred source: real "last used" order.
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 12 * 3600_000L, now)
                ?: emptyList()
            stats.sortedByDescending { it.lastTimeUsed }
                .filter { now - it.lastTimeUsed < 6 * 3600_000L }
                .forEach { stat ->
                    val pkg = stat.packageName ?: return@forEach
                    if (pkg in homePackages || found.containsKey(pkg)) return@forEach
                    if (!isUserApp(pm, pkg)) return@forEach
                    val label = appLabel(pm, pkg) ?: return@forEach
                    found[pkg] = RecentApp(label, pkg, stat.lastTimeUsed)
                }
        } catch (_: Exception) {
        }

        // Fallback for TVs without usage access: currently running processes.
        if (found.isEmpty()) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses.orEmpty().forEach { process ->
                    val pkg = process.pkgList?.firstOrNull() ?: return@forEach
                    if (pkg in homePackages || found.containsKey(pkg)) return@forEach
                    if (!isUserApp(pm, pkg)) return@forEach
                    val label = appLabel(pm, pkg) ?: return@forEach
                    found[pkg] = RecentApp(label, pkg, 0L)
                }
            } catch (_: Exception) {
            }
        }
        return found.values.take(20)
    }

    private fun homePackages(pm: PackageManager): Set<String> {
        val result = mutableSetOf(packageName)
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach {
                result.add(it.activityInfo.packageName)
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun isUserApp(pm: PackageManager, packageName: String): Boolean {
        if (packageName == this.packageName) return false
        if (TvHomeOverrideService.isWhitelistedPackage(packageName)) return false
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                pm.getLaunchIntentForPackage(packageName) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun appLabel(pm: PackageManager, packageName: String): String? {
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private class RecentAdapter(
        private val onClose: (RecentApp) -> Unit
    ) : RecyclerView.Adapter<RecentAdapter.Holder>() {

        private val items = mutableListOf<RecentApp>()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.recentIcon)
            val name: TextView = view.findViewById(R.id.recentName)
        }

        fun submit(newItems: List<RecentApp>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_recent_app, parent, false)
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.name.text = entry.label
            holder.icon.setImageDrawable(
                try {
                    holder.itemView.context.packageManager.getApplicationIcon(entry.packageName)
                } catch (_: Exception) {
                    null
                }
            )
            holder.itemView.setOnClickListener { onClose(entry) }
        }
    }
}
