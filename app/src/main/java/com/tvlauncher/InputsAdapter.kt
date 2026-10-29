package com.tvlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** List for the "Entradas" tab. Reuses the row layout of the "Todos os apps" tab. */
class InputsAdapter(
    private val onClick: (TvInputEntry) -> Unit
) : RecyclerView.Adapter<InputsAdapter.ViewHolder>() {

    private var items: List<TvInputEntry> = emptyList()

    fun submit(list: List<TvInputEntry>) {
        items = list
        notifyDataSetChanged()
    }

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

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val context = holder.itemView.context
        holder.name.text = entry.label
        holder.status.text = context.getString(
            if (entry.id == null) R.string.inputs_live_tv_hint else R.string.inputs_hdmi_hint
        )
        holder.check.visibility = View.GONE
        holder.icon.setImageResource(R.drawable.ic_input)
        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener(null)
    }
}
