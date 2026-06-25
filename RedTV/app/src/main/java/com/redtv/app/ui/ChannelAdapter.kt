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

class ChannelAdapter(
    private val prefs: Prefs,
    private val onClick: (Channel, Int) -> Unit,
    private val onLongPress: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = ArrayList<Channel>()

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun currentIds(): ArrayList<String> = ArrayList(items.map { it.id })

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val star: ImageView = v.findViewById(R.id.favStar)
        val name: TextView = v.findViewById(R.id.name)
        val epgNow: TextView = v.findViewById(R.id.epgNow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
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

        val (now, _) = ContentRepository.nowAndNext(ch.epgChannelId)
        when {
            now != null -> {
                holder.epgNow.visibility = View.VISIBLE
                holder.epgNow.text = now.title
            }
            ch.number != null -> {
                holder.epgNow.visibility = View.VISIBLE
                holder.epgNow.text = "Ch ${ch.number}"
            }
            else -> holder.epgNow.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(ch, holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongPress(ch)
            true
        }
    }
}
