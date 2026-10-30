package com.tvlauncher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for the home grid. It shows three kinds of items:
 * app tiles, the "+" tile (add apps) and category headers (full-width rows).
 */
class AppSlotAdapter(
    private val onPlusClick: () -> Unit,
    private val onAppLongClick: (Int) -> Unit,
    private val onHeaderClick: (Int) -> Unit,
    private val onItemFocused: (String) -> Unit,
    sizing: AppSlotAdapter.Sizing,
    private val onAppLaunched: (AppInfo) -> Unit = {}
) : ListAdapter<AppSlotAdapter.AppSlot, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    /**
     * One item of the grid.
     * - app tile: [appInfo] set, [isEmpty] false
     * - "+" tile: [isEmpty] true
     * - category header: [category] set
     */
    data class AppSlot(
        val appInfo: AppInfo? = null,
        val isEmpty: Boolean = true,
        val category: Category? = null,
        val categoryAppCount: Int = 0
    ) {
        val isHeader: Boolean
            get() = category != null

        /** Stable identity; also used to restore focus after the list changes. */
        val key: String
            get() = when {
                category != null -> categoryKey(category.id)
                appInfo != null && !isEmpty -> AppIdentifier.encode(appInfo)
                else -> KEY_PLUS
            }
    }

    /** Sizes (in pixels / sp) for the tiles, all derived from the slot size. */
    data class Sizing(
        val slotPx: Int,
        val iconPx: Int,
        val iconTopPx: Int,
        val textSp: Float,
        val nameLines: Int,
        val plusPx: Int,
        val plusTopPx: Int,
        val plusTextSp: Float
    ) {
        companion object {
            private const val BASE_SLOT_DP = 130f

            /**
             * Google TV style: the icon takes most of the tile (~78%) and the name only appears
             * while the tile is focused, so the grid looks like a wall of icons.
             */
            fun forSlot(slotPx: Int, density: Float): Sizing {
                val scale = slotPx / (BASE_SLOT_DP * density)
                return Sizing(
                    slotPx = slotPx,
                    iconPx = (slotPx * 0.78f).toInt(),
                    iconTopPx = (slotPx * 0.04f).toInt(),
                    textSp = (11f * scale).coerceIn(10f, 16f),
                    nameLines = 1,
                    plusPx = (slotPx * 56f / BASE_SLOT_DP).toInt(),
                    plusTopPx = (slotPx * 20f / BASE_SLOT_DP).toInt(),
                    plusTextSp = (30f * scale).coerceIn(22f, 44f)
                )
            }
        }
    }

    var sizing: Sizing = sizing
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** Focus colour, taken from the chosen palette (see [Palette]). */
    var accentColor: Int = Palette.accent(Palette.DEFAULT)
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Shape the icons are clipped to (see [IconShapes]). */
    var iconShape: Int = IconShapes.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Key of the tile being moved in reorder mode (highlighted), or null. */
    var movingKey: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { index, slot ->
                if (slot.key == old || slot.key == value) {
                    notifyItemChanged(index)
                }
            }
        }

    class AppSlotViewHolder(
        itemView: View,
        private val onFocused: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val appName: TextView = itemView.findViewById(R.id.appName)
        val plusButton: TextView = itemView.findViewById(R.id.plusButton)
        val addText: TextView = itemView.findViewById(R.id.addText)
        var boundKey: String? = null
    }

    class HeaderViewHolder(
        itemView: View,
        private val onFocused: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val categoryName: TextView = itemView.findViewById(R.id.categoryName)
        val categoryCount: TextView = itemView.findViewById(R.id.categoryCount)
        var boundKey: String? = null

        init {
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    boundKey?.let(onFocused)
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isHeader) VIEW_TYPE_HEADER else VIEW_TYPE_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(
                inflater.inflate(R.layout.item_category_header, parent, false),
                onItemFocused
            )
        } else {
            AppSlotViewHolder(
                inflater.inflate(R.layout.item_app_slot, parent, false),
                onItemFocused
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val slot = getItem(position)
        when (holder) {
            is HeaderViewHolder -> bindHeader(holder, slot)
            is AppSlotViewHolder -> bindSlot(holder, slot)
        }
    }

    private fun bindHeader(holder: HeaderViewHolder, slot: AppSlot) {
        val category = slot.category ?: return
        holder.boundKey = slot.key
        holder.categoryName.text = category.name
        holder.categoryCount.text = slot.categoryAppCount.toString()

        val openMenu = {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onHeaderClick(pos)
            }
        }
        holder.itemView.setOnClickListener { openMenu() }
        holder.itemView.setOnLongClickListener {
            openMenu()
            true
        }
    }

    private fun bindSlot(holder: AppSlotViewHolder, slot: AppSlot) {
        holder.boundKey = slot.key
        holder.itemView.setOnLongClickListener(null)
        applySizing(holder)

        val moving = slot.key == movingKey
        holder.itemView.isActivated = moving
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            applyFocusLook(holder, slot, hasFocus, moving)
            if (hasFocus) {
                holder.boundKey?.let(onItemFocused)
            }
        }
        applyFocusLook(holder, slot, holder.itemView.isFocused, moving)

        if (slot.isEmpty) {
            holder.appIcon.visibility = View.GONE
            holder.appName.visibility = View.INVISIBLE
            holder.plusButton.visibility = View.VISIBLE
            holder.addText.visibility = View.VISIBLE

            holder.itemView.setOnClickListener { onPlusClick() }
        } else {
            slot.appInfo?.let { app ->
                holder.appIcon.setImageDrawable(
                    app.getIcon(holder.itemView.context.packageManager, sizing.iconPx)
                )
                holder.appName.text = app.getDisplayName()

                holder.appIcon.visibility = View.VISIBLE
                holder.appName.visibility =
                    if (holder.itemView.isFocused || moving) View.VISIBLE else View.INVISIBLE
                holder.plusButton.visibility = View.GONE
                holder.addText.visibility = View.GONE

                holder.itemView.setOnClickListener {
                    onAppLaunched(app)
                    if (app.isShortcut) {
                        ShortcutHelper.launchShortcut(
                            holder.itemView.context,
                            app.packageName,
                            app.shortcutId!!
                        )
                    } else {
                        ShortcutHelper.launchApp(holder.itemView.context, app.packageName)
                    }
                }
                holder.itemView.setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onAppLongClick(pos)
                    }
                    true
                }
            }
        }
    }

    /**
     * Focus look without a card: a soft rounded glow behind the icon (no border, no zoom and no
     * shadow). The name of the app only shows up while the tile is focused.
     */
    private fun applyFocusLook(
        holder: AppSlotViewHolder,
        slot: AppSlot,
        focused: Boolean,
        moving: Boolean
    ) {
        val density = holder.itemView.resources.displayMetrics.density
        val accent = accentColor
        val highlighted = focused || moving
        val alpha = when {
            moving -> 0x66
            focused -> 0x3D
            else -> 0x00
        }
        val background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(
                Color.argb(
                    alpha,
                    Color.red(accent),
                    Color.green(accent),
                    Color.blue(accent)
                )
            )
            if (moving) {
                setStroke((2 * density).toInt(), accent)
            }
        }
        holder.itemView.background = background
        holder.itemView.elevation = 0f
        if (!slot.isEmpty) {
            holder.appName.visibility = if (highlighted) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Applies the current tile/icon/text sizes. Only touches views whose size changed. */
    private fun applySizing(holder: AppSlotViewHolder) {
        val s = sizing

        val itemParams = holder.itemView.layoutParams
        if (itemParams.width != s.slotPx || itemParams.height != s.slotPx) {
            itemParams.width = s.slotPx
            itemParams.height = s.slotPx
            holder.itemView.layoutParams = itemParams
        }

        val iconParams = holder.appIcon.layoutParams as ConstraintLayout.LayoutParams
        if (iconParams.width != s.iconPx || iconParams.height != s.iconPx ||
            iconParams.topMargin != s.iconTopPx
        ) {
            iconParams.width = s.iconPx
            iconParams.height = s.iconPx
            iconParams.topMargin = s.iconTopPx
            holder.appIcon.layoutParams = iconParams
        }

        val plusParams = holder.plusButton.layoutParams as ConstraintLayout.LayoutParams
        if (plusParams.width != s.plusPx || plusParams.height != s.plusPx ||
            plusParams.topMargin != s.plusTopPx
        ) {
            plusParams.width = s.plusPx
            plusParams.height = s.plusPx
            plusParams.topMargin = s.plusTopPx
            holder.plusButton.layoutParams = plusParams
        }

        holder.appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.textSp)
        holder.appName.maxLines = s.nameLines
        holder.addText.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.textSp)
        holder.plusButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.plusTextSp)
        IconShapes.applyTo(holder.appIcon, iconShape)
    }

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_HEADER = 1
        const val KEY_PLUS = "plus"

        fun categoryKey(categoryId: String): String = "cat:$categoryId"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppSlot>() {
            override fun areItemsTheSame(oldItem: AppSlot, newItem: AppSlot): Boolean {
                return oldItem.key == newItem.key
            }

            override fun areContentsTheSame(oldItem: AppSlot, newItem: AppSlot): Boolean {
                if (oldItem.isHeader || newItem.isHeader) {
                    return oldItem.category == newItem.category &&
                        oldItem.categoryAppCount == newItem.categoryAppCount
                }
                if (oldItem.isEmpty && newItem.isEmpty) return true
                val oldApp = oldItem.appInfo ?: return false
                val newApp = newItem.appInfo ?: return false
                return oldApp.packageName == newApp.packageName &&
                    oldApp.appName == newApp.appName &&
                    oldApp.shortcutId == newApp.shortcutId &&
                    oldApp.shortcutLabel == newApp.shortcutLabel
            }
        }
    }
}
