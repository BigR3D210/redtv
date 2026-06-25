package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivitySeriesBinding
import com.redtv.app.model.Channel
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {

    private lateinit var b: ActivitySeriesBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: ChannelAdapter
    private var episodes: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.seriesTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "Series"
        adapter = ChannelAdapter(prefs, { ch, _ -> playEpisode(ch) }, { })
        b.episodeGrid.layoutManager = GridLayoutManager(this, 4)
        b.episodeGrid.adapter = adapter
        b.episodeGrid.setHasFixedSize(true)

        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: run { finish(); return }
        load(seriesId)
    }

    private fun load(seriesId: String) {
        b.progress.visibility = View.VISIBLE
        b.emptyState.visibility = View.GONE
        lifecycleScope.launch {
            episodes = try { ContentRepository.loadEpisodes(seriesId) } catch (e: Exception) { emptyList() }
            b.progress.visibility = View.GONE
            if (episodes.isEmpty()) {
                b.emptyState.text = "No episodes found."
                b.emptyState.visibility = View.VISIBLE
            } else adapter.submit(episodes)
        }
    }

    private fun playEpisode(ch: Channel) {
        prefs.pushRecent(ch.id)
        val ids = ArrayList(episodes.map { it.id })
        val idx = episodes.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_IDS, ids)
            .putExtra(PlayerActivity.EXTRA_INDEX, idx))
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "title"
    }
}
