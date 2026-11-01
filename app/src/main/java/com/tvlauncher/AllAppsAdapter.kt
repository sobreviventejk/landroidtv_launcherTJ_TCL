package com.tvlauncher

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
 * "Todos os apps" uses exactly the same wide card and focus glow as every other category.
 * The name and status only appear while the tile is focused.
 */
class AllAppsAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Unit,
    private val cardSizePx: () -> TileStyle.CardSize,
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
        holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
            this.height = height
        }
        val scale = (height / (140f * holder.itemView.resources.displayMetrics.density))
            .coerceIn(0.8f, 1.8f)
        holder.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, (12f * scale).coerceIn(10f, 18f))
        holder.status.setTextSize(TypedValue.COMPLEX_UNIT_SP, (10f * scale).coerceIn(9f, 14f))
        holder.name.text = row.app.getDisplayName()
        holder.status.text = row.status

        val cardSize = cardSizePx()
        holder.icon.layoutParams = holder.icon.layoutParams.apply {
            width = cardSize.widthPx
            this.height = cardSize.heightPx
        }
        holder.icon.setImageDrawable(
            row.app.getTileArtwork(holder.itemView.context.packageManager, cardSize.widthPx)
        )

        holder.itemView.setOnFocusChangeListener { _, focused ->
            TileStyle.apply(holder.icon, cardSize, focused, accentColor, iconShape())
            holder.name.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            holder.status.visibility = if (focused) View.VISIBLE else View.INVISIBLE
        }
        val focused = holder.itemView.isFocused
        TileStyle.apply(holder.icon, cardSize, focused, accentColor, iconShape())
        holder.name.visibility = if (focused) View.VISIBLE else View.INVISIBLE
        holder.status.visibility = if (focused) View.VISIBLE else View.INVISIBLE

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
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
