package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityLiveGuideBinding
import com.redtv.app.model.Channel
import com.redtv.app.net.Http
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cable / YouTube TV style live guide: browse channels with a live corner preview. */
@UnstableApi
class LiveGuideActivity : AppCompatActivity() {

    private lateinit var b: ActivityLiveGuideBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: GuideAdapter
    private var player: ExoPlayer? = null

    private var channels: List<Channel> = emptyList()
    private var focusedChannel: Channel? = null
    private var pending: Channel? = null
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private val previewRunnable = Runnable { pending?.let { startPreview(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLiveGuideBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        b.previewIdle.visibility = View.VISIBLE

        val hidden = prefs.hidden()
        channels = ContentRepository.live
            .filterNot { hidden.contains(it.id) }
            .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.name.lowercase() }))

        adapter = GuideAdapter(
            prefs,
            onFocused = { _, ch -> onFocusChannel(ch) },
            onSelected = { ch -> openFullscreen(ch) },
            onLongPress = { }
        )
        b.guideList.layoutManager = LinearLayoutManager(this)
        b.guideList.adapter = adapter
        b.guideList.setHasFixedSize(true)

        if (channels.isEmpty()) {
            b.guideEmpty.text = "No live channels here.\nAdd a source, or unhide channels in the editor."
            b.guideEmpty.visibility = View.VISIBLE
            b.previewIdle.visibility = View.GONE
        } else {
            adapter.submit(channels)
            b.guideList.post {
                b.guideList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun onFocusChannel(ch: Channel) {
        focusedChannel = ch
        updateInfo(ch)
        pending = ch
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, 900)
    }

    private fun updateInfo(ch: Channel) {
        b.previewName.text = ch.name
        val (now, next) = ContentRepository.nowAndNext(ch.epgChannelId)
        b.previewNow.text = if (now != null) "Now: ${now.title}" else ("Channel ${ch.number ?: ""}").trim()
        if (next != null) {
            b.previewNext.visibility = View.VISIBLE
            b.previewNext.text = "Next: ${next.title}  (${timeFmt.format(Date(next.startMillis))})"
        } else b.previewNext.visibility = View.GONE
    }

    private fun startPreview(ch: Channel) {
        val p = player ?: return
        b.previewIdle.visibility = View.GONE
        p.setMediaItem(MediaItem.fromUri(ch.streamUrl))
        p.prepare()
        p.playWhenReady = true
    }

    private fun openFullscreen(ch: Channel) {
        val ids = ArrayList(channels.map { it.id })
        val idx = channels.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putStringArrayListExtra(PlayerActivity.EXTRA_IDS, ids)
                .putExtra(PlayerActivity.EXTRA_INDEX, idx)
        )
    }

    private fun initPlayer() {
        if (player != null) return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                b.previewBuffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
        })
        b.preview.player = p
        player = p
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
        focusedChannel?.let { onFocusChannel(it) }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(previewRunnable)
        player?.release()
        player = null
    }
}
