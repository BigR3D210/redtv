package com.redtv.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.redtv.app.R

/** Reusable selectable text list. Used vertically (categories) and horizontally (sections). */
class CategoryAdapter(
    private val items: List<String>,
    private val layoutRes: Int = R.layout.item_category,
    private val onSelected: (Int) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var selectedIndex = 0
        private set

    fun setSelected(i: Int) {
        if (i == selectedIndex) return
        val old = selectedIndex
        selectedIndex = i
        notifyItemChanged(old)
        notifyItemChanged(i)
    }

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false) as TextView
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position]
        holder.text.isSelected = position == selectedIndex
        holder.text.setOnClickListener { select(position) }
        holder.text.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) select(position) }
    }

    private fun select(position: Int) {
        if (position != selectedIndex) {
            val old = selectedIndex
            selectedIndex = position
            notifyItemChanged(old)
            notifyItemChanged(position)
        }
        onSelected(position)
    }
}
