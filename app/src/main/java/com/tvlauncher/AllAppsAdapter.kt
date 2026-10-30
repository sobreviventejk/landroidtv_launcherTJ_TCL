package com.tvlauncher

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Minimal list of every installed app: icon, name and where the app is on the home screen
 * (category name, or "not added"). Selecting a row opens the actions for that app.
 */
class AllAppsAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Unit,
    private val iconSizePx: () -> Int,
    /** Height of one row: the "All apps" tab has its own icon size, chosen with a bar. */
    private val rowHeightPx: () -> Int = { 0 },
    private val iconShape: () -> Int = { IconShapes.DEFAULT }
) : ListAdapter<AllAppsAdapter.Row, AllAppsAdapter.ViewHolder>(DIFF_CALLBACK) {

    /**
     * @property app the installed app
     * @property id identifier used on the home screen (see [AppIdentifier])
     * @property added true when the app is on the home screen
     * @property status text under the name (category name or "not added")
     */
    data class Row(
        val app: AppInfo,
        val id: String,
        val added: Boolean,
        val status: String
    )

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.rowIcon)
        val name: TextView = itemView.findViewById(R.id.rowName)
        val status: TextView = itemView.findViewById(R.id.rowStatus)
        val check: TextView = itemView.findViewById(R.id.rowCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_all_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val height = rowHeightPx()
        if (height > 0) {
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                this.height = height
            }
            val scale = height / (56f * holder.itemView.resources.displayMetrics.density)
            holder.name.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                (13f * scale).coerceIn(10f, 18f)
            )
            holder.status.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                (10f * scale).coerceIn(9f, 14f)
            )
        }
        holder.name.text = row.app.getDisplayName()
        holder.status.text = row.status
        holder.check.visibility = if (row.added) View.VISIBLE else View.GONE
        val iconPx = iconSizePx()
        holder.icon.layoutParams = holder.icon.layoutParams.apply {
            width = iconPx
            this.height = iconPx
        }
        val shape = iconShape()
        val padding = if (shape == IconShapes.DEFAULT) (iconPx * 0.06f).toInt() else 0
        holder.icon.setPadding(padding, padding, padding, padding)
        holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        holder.icon.background = if (shape == IconShapes.DEFAULT) {
            GradientDrawable().apply {
                cornerRadius = iconPx * 0.16f
                setColor(0x24FFFFFF)
                setStroke(1, 0x30FFFFFF)
            }
        } else {
            null
        }
        IconShapes.applyTo(holder.icon, shape)
        holder.icon.setImageDrawable(
            row.app.getIcon(holder.itemView.context.packageManager, iconPx)
        )
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onClick(getItem(pos))
            }
        }
        holder.itemView.setOnLongClickListener {
            holder.itemView.postDelayed({
                val pos = holder.bindingAdapterPosition
                if (holder.itemView.isPressed && pos != RecyclerView.NO_POSITION) {
                    onLongClick(getItem(pos))
                }
            }, 200L)
            true
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.added == newItem.added &&
                    oldItem.status == newItem.status &&
                    oldItem.app.appName == newItem.app.appName
        }
    }
}
