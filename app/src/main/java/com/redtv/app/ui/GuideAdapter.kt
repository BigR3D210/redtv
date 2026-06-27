package com.redtv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.redtv.app.R
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.model.Channel

/** List rows for the home browser: number, logo, name, what's-on / category, fav star. */
class GuideAdapter(
    private val prefs: Prefs,
    private val onFocused: (Int, Channel) -> Unit,
    private val onSelected: (Channel) -> Unit,
    private val onLongPress: (Channel) -> Unit
) : RecyclerView.Adapter<GuideAdapter.VH>() {

    private val items = ArrayList<Channel>()

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.guideNum)
        val logo: ImageView = v.findViewById(R.id.guideLogo)
        val name: TextView = v.findViewById(R.id.guideName)
        val now: TextView = v.findViewById(R.id.guideNow)
        val star: ImageView = v.findViewById(R.id.guideStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_guide, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.num.text = ch.number?.toString() ?: ""
        holder.name.text = ch.name
        holder.star.visibility = if (prefs.isFavorite(ch.id)) View.VISIBLE else View.GONE

        if (!ch.logoUrl.isNullOrBlank()) {
            holder.logo.load(ch.logoUrl) {
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
            }
        } else {
            holder.logo.setImageDrawable(null)
            holder.logo.setBackgroundResource(R.drawable.logo_placeholder)
        }

        val (nowProg, _) = ContentRepository.nowNextForChannel(ch)
        holder.now.text = nowProg?.title ?: ch.category

        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocused(holder.bindingAdapterPosition, ch)
        }
        holder.itemView.setOnClickListener { onSelected(ch) }
        holder.itemView.setOnLongClickListener { onLongPress(ch); true }
    }
}
