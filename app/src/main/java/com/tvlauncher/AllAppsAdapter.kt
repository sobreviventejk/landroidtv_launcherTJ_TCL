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

        // Tile height follows the row arrangement; the card is a square that fits the cell.
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

        // Uniform Google TV card: rounded square, translucent fill, icon centered inside.
        val cardSide = iconSizePx().coerceAtLeast(1)
        holder.icon.layoutParams = holder.icon.layoutParams.apply {
            width = cardSide
            this.height = cardSide
        }
        val shape = iconShape()
        val padding = (cardSide * 0.09f).toInt()
        holder.icon.setPadding(padding, padding, padding, padding)
        holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        IconShapes.applyTo(holder.icon, shape)
        holder.icon.setImageDrawable(
            row.app.getIcon(holder.itemView.context.packageManager, cardSide)
        )

        holder.itemView.setOnFocusChangeListener { _, focused ->
            styleCard(holder.icon, cardSide, focused)
            holder.name.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            holder.status.visibility = if (focused) View.VISIBLE else View.INVISIBLE
        }
        styleCard(holder.icon, cardSide, holder.itemView.isFocused)
        holder.name.visibility = if (holder.itemView.isFocused) View.VISIBLE else View.INVISIBLE
        holder.status.visibility = if (holder.itemView.isFocused) View.VISIBLE else View.INVISIBLE

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

    /**
     * The exact same tile look as the home grid: always a rounded card, with a soft fill and a
     * hairline border; on focus the fill warms up, the border grows (proportional to the card)
     * and the card pops forward. The stroke scale follows the icon size setting.
     */
    private fun styleCard(icon: ImageView, sidePx: Int, focused: Boolean) {
        val density = icon.resources.displayMetrics.density
        val radius = sidePx * 0.17f
        icon.background = GradientDrawable().apply {
            cornerRadius = radius
            if (focused) {
                setColor(
                    Color.argb(
                        0x40,
                        Color.red(accentColor),
                        Color.green(accentColor),
                        Color.blue(accentColor)
                    )
                )
                setStroke(
                    (sidePx * 0.035f).toInt().coerceAtLeast((3 * density).toInt()),
                    accentColor
                )
            } else {
                setColor(0x26FFFFFF)
                setStroke(
                    (sidePx * 0.014f).toInt().coerceAtLeast(1),
                    0x38FFFFFF
                )
            }
        }
        icon.elevation = if (focused) 8f * density else 2f * density
        val target = if (focused) 1.05f else 1f
        if (kotlin.math.abs(icon.scaleX - target) > 0.001f) {
            icon.animate().scaleX(target).scaleY(target).setDuration(90L).start()
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
