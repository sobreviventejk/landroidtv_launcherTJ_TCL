package com.tvlauncher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
    sizing: AppSlotAdapter.Sizing
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

            /** Scales the original layout (130dp tile, 72dp icon, 12sp label) to [slotPx]. */
            fun forSlot(slotPx: Int, density: Float): Sizing {
                val scale = slotPx / (BASE_SLOT_DP * density)
                val slotDp = slotPx / density
                return Sizing(
                    slotPx = slotPx,
                    iconPx = (slotPx * 72f / BASE_SLOT_DP).toInt(),
                    iconTopPx = (slotPx * 16f / BASE_SLOT_DP).toInt(),
                    textSp = (12f * scale).coerceIn(10f, 18f),
                    nameLines = if (slotDp < 110f) 1 else 2,
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

        init {
            itemView.setOnFocusChangeListener { view, hasFocus ->
                animateFocus(view, hasFocus)
                if (hasFocus) {
                    boundKey?.let(onFocused)
                }
            }
        }

        private fun animateFocus(view: View, hasFocus: Boolean) {
            val scale = if (hasFocus) 1.08f else 1.0f
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, scale)
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, scale)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 150
                interpolator = DecelerateInterpolator()
                start()
            }
            if (hasFocus) {
                view.elevation = 8f
            } else {
                view.elevation = 0f
            }
        }
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

        if (slot.isEmpty) {
            holder.itemView.isActivated = false
            holder.appIcon.visibility = View.GONE
            holder.appName.visibility = View.GONE
            holder.plusButton.visibility = View.VISIBLE
            holder.addText.visibility = View.VISIBLE

            holder.itemView.setOnClickListener { onPlusClick() }
        } else {
            slot.appInfo?.let { app ->
                holder.itemView.isActivated = slot.key == movingKey

                holder.appIcon.setImageDrawable(
                    app.getIcon(holder.itemView.context.packageManager, sizing.iconPx)
                )
                holder.appName.text = app.getDisplayName()

                holder.appIcon.visibility = View.VISIBLE
                holder.appName.visibility = View.VISIBLE
                holder.plusButton.visibility = View.GONE
                holder.addText.visibility = View.GONE

                holder.itemView.setOnClickListener {
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
