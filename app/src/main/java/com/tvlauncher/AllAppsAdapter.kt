package com.tvlauncher

import android.graphics.Color
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
 * "Todos os apps" in the Google TV tile style: every app is a rounded card with the icon taking
 * most of it and NO name below — the name (and where the app goes) only appears on focus.
 * The grid arrangement follows the same columns/rows selected for the home grid.
 */
class AllAppsAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Unit,
    private val iconSizePx: () -> Int,
    /** Height of one tile: follows the row arrangement of the grid. */
    private val rowHeightPx: () -> Int = { 0 },
    private val iconShape: () -> Int = { IconShapes.DEFAULT }
) : ListAdapter<AllAppsAdapter.Row, AllAppsAdapter.ViewHolder>(DIFF_CALLBACK) {

    /** Focus colour, taken from the chosen palette (see [Palette]). */
    var accentColor: Int = Palette.accent(Palette.DEFAULT)
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /**
     * @property app the installed app
     * @property id identifier used on the home screen (see [AppIdentifier])
     * @property added true when the app is on the home screen
     * @property status shown under the name while the tile is focused
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_all_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val height = rowHeightPx().coerceAtLeast(1)
        if (height > 0) {
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                this.height = height
            }
            val scale = (height / (140f * holder.itemView.resources.displayMetrics.density))
                .coerceIn(0.8f, 1.8f)
            holder.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, (13f * scale).coerceIn(10f, 20f))
            holder.status.setTextSize(TypedValue.COMPLEX_UNIT_SP, (10f * scale).coerceIn(9f, 15f))
        }
        holder.name.text = row.app.getDisplayName()
        holder.status.text = row.status

        val iconPx = iconSizePx().coerceAtLeast(1)
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

        // Same focus look as the home grid: accent border card, name appears on focus.
        val density = holder.itemView.resources.displayMetrics.density
        fun applyFocus() {
            val focused = holder.itemView.isFocused
            holder.itemView.background = GradientDrawable().apply {
                cornerRadius = 22 * density
                if (focused) {
                    setColor(0x2E000000)
                    setStroke((3 * density).toInt(), accentColor)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
            holder.name.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            holder.status.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            holder.itemView.elevation = if (focused) 8f * density else 0f
        }
        holder.itemView.setOnFocusChangeListener { _, _ -> applyFocus() }
        applyFocus()

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
