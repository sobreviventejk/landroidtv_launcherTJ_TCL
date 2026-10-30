package com.tvlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.Normalizer
import java.util.Locale

/**
 * Results of the search box in the header.
 *
 * The filter ignores case, accents and extra spaces, so "netflix", "NETFLIX" or "aplic" find
 * the same apps on a TV remote made only of numbers and arrows. When the query is empty the
 * list shows the installed apps as suggestions, so the user can simply navigate with the D-pad.
 */
class SearchAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val iconSizePx: () -> Int
) : ListAdapter<AppInfo, SearchAdapter.ViewHolder>(DIFF_CALLBACK) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.searchRowIcon)
        val name: TextView = itemView.findViewById(R.id.searchRowName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        holder.name.text = app.getDisplayName()
        holder.icon.setImageDrawable(
            app.getIcon(holder.itemView.context.packageManager, iconSizePx())
        )
        holder.itemView.setOnClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onClick(getItem(holder.bindingAdapterPosition))
            }
        }
    }

    companion object {
        /** Maximum number of rows shown at once, to keep the TV list fast to scroll. */
        const val MAX_RESULTS = 80

        private fun rank(haystack: String, needle: String): Int {
            if (haystack.startsWith(needle)) return 0
            if (haystack.split(' ').any { it.startsWith(needle) }) return 1
            return 2
        }

        /**
         * Removes accents and case so the comparison never depends on how the app name was
         * typed. "Configurações" and "configuracoes" become the same text.
         */
        fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
            return decomposed
                .replace(ACCENT_MARKS, "")
                .lowercase(Locale.ROOT)
                .replace(MULTIPLE_SPACES, " ")
                .trim()
        }

        /** Installed apps matching every word of [query], best matches first. */
        fun filter(apps: List<AppInfo>, query: String, limit: Int = MAX_RESULTS): List<AppInfo> {
            val needle = normalize(query)
            if (needle.isEmpty()) {
                return apps.sortedBy { normalize(it.getDisplayName()) }.take(limit)
            }
            val words = needle.split(' ').filter { it.isNotEmpty() }
            return apps.asSequence()
                .map { app -> app to normalize(app.getDisplayName()) }
                .filter { (_, haystack) -> words.all { haystack.contains(it) } }
                .sortedWith(
                    compareBy({ (_, haystack) -> rank(haystack, needle) }, { (_, haystack) -> haystack })
                )
                .map { (app, _) -> app }
                .take(limit)
                .toList()
        }

        private val ACCENT_MARKS = Regex("\\p{M}+")
        private val MULTIPLE_SPACES = Regex("\\s+")

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.packageName == newItem.packageName &&
                    oldItem.shortcutId == newItem.shortcutId

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.getDisplayName() == newItem.getDisplayName()
        }
    }
}
