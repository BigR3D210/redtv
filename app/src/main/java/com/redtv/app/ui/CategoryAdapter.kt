package com.redtv.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.redtv.app.R

/** Reusable selectable text list. Selection happens on CLICK (keeps D-pad scrolling smooth). */
class CategoryAdapter(
    private val items: List<String>,
    private val layoutRes: Int = R.layout.item_category,
    private val onSelected: (Int) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var selectedIndex = 0
        private set

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false) as TextView
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position]
        holder.text.isSelected = position == selectedIndex
        holder.text.setOnClickListener {
            if (position != selectedIndex) {
                val old = selectedIndex
                selectedIndex = position
                notifyItemChanged(old)
                notifyItemChanged(position)
            }
            onSelected(position)
        }
    }
}
