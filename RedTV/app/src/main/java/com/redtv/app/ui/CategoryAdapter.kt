package com.redtv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.redtv.app.R

/** Vertical sidebar list. Selecting (focus or click) a row filters the grid. */
class CategoryAdapter(
    private val items: List<String>,
    private val onSelected: (Int) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var selectedIndex = 0
        private set

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false) as TextView
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position]
        holder.text.isSelected = position == selectedIndex

        holder.text.setOnClickListener { select(position) }
        holder.text.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) select(position)
        }
    }

    private fun select(position: Int) {
        if (position == selectedIndex) {
            onSelected(position)
            return
        }
        val old = selectedIndex
        selectedIndex = position
        notifyItemChanged(old)
        notifyItemChanged(position)
        onSelected(position)
    }
}
