package com.redtv.app.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityPlayerBinding
import com.redtv.app.model.Channel
import com.redtv.app.net.Http
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private var player: ExoPlayer? = null

    private var ids: List<String> = emptyList()
    private var index = 0
    private var castChannel: Channel? = null  // set when launched as a cast (direct URL)
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val hideInfo = Runnable { b.infoBar.visibility = View.GONE }
    private var nextTimer: CountDownTimer? = null

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeLabels = arrayOf("Fit", "Stretch", "Zoom")
    private var resizeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.playerView.keepScreenOn = true

        b.btnPlayNext.setOnClickListener { gotoNext() }
        b.btnCancelNext.setOnClickListener { hideNextOverlay() }

        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrBlank()) {
            castChannel = Channel(id = "cast", name = intent.getStringExtra(EXTRA_NAME) ?: "Cast", streamUrl = url)
        } else {
            ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: arrayListOf()
            index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        }
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
        playCurrent()
    }

    private fun initPlayer() {
        if (player != null) return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        // Wrap so it also plays local files (uploaded casts), content:// etc.
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            .build()

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                b.buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_ENDED) maybeAutoplayNext()
            }
            override fun onPlayerError(error: PlaybackException) {
                b.errorText.text = "Playback error: ${error.errorCodeName}\n${error.message ?: ""}"
                b.errorText.visibility = View.VISIBLE
            }
        })
        b.playerView.player = p
        b.playerView.resizeMode = resizeModes[resizeIndex]
        player = p
    }

    private fun currentChannel(): Channel? =
        castChannel ?: ids.getOrNull(index)?.let { ContentRepository.channelById(it) }

    private fun playCurrent() {
        hideNextOverlay()
        val ch = currentChannel() ?: run {
            b.errorText.text = "Channel not found."
            b.errorText.visibility = View.VISIBLE
            return
        }
        b.errorText.visibility = View.GONE
        val p = player ?: return

        p.setMediaItem(MediaItem.fromUri(ch.streamUrl))
        p.prepare()

        if (ch.id.startsWith("movie_") || ch.id.startsWith("ep_")) {
            val pos = prefs.resumePosition(ch.id)
            if (pos > 0) p.seekTo(pos)
        }
        p.playWhenReady = true

        if (castChannel == null) {
            prefs.pushRecent(ch.id)
            prefs.lastChannelId = ch.id
        }
        showInfo(ch)
    }

    // ---- Series autoplay ("Up next" with countdown) ----

    private fun maybeAutoplayNext() {
        val ch = currentChannel() ?: return
        if (!ch.id.startsWith("ep_")) return        // only for series episodes
        if (index >= ids.size - 1) return            // no next episode
        val next = ids.getOrNull(index + 1)?.let { ContentRepository.channelById(it) } ?: return
        showNextOverlay(next)
    }

    private fun showNextOverlay(next: Channel) {
        b.nextTitle.text = next.name
        b.nextCountdown.text = "Playing in 10s"
        b.nextOverlay.visibility = View.VISIBLE
        b.btnPlayNext.requestFocus()
        nextTimer?.cancel()
        nextTimer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(ms: Long) { b.nextCountdown.text = "Playing in ${ms / 1000}s" }
            override fun onFinish() { gotoNext() }
        }.start()
    }

    private fun hideNextOverlay() {
        nextTimer?.cancel(); nextTimer = null
        b.nextOverlay.visibility = View.GONE
    }

    private fun gotoNext() {
        hideNextOverlay()
        if (ids.isEmpty() || index >= ids.size - 1) return
        saveResume()
        index += 1
        playCurrent()
    }

    private fun showInfo(ch: Channel) {
        b.infoName.text = ch.name
        val (now, next) = ContentRepository.nowAndNext(ch.epgChannelId)
        if (now != null) {
            b.infoNow.visibility = View.VISIBLE
            b.infoNow.text = "Now: ${now.title}  (${timeFmt.format(Date(now.startMillis))})"
        } else b.infoNow.visibility = View.GONE
        if (next != null) {
            b.infoNext.visibility = View.VISIBLE
            b.infoNext.text = "Next: ${next.title}  (${timeFmt.format(Date(next.startMillis))})"
        } else b.infoNext.visibility = View.GONE

        b.infoBar.visibility = View.VISIBLE
        b.infoBar.removeCallbacks(hideInfo)
        b.infoBar.postDelayed(hideInfo, 3500)
    }

    private fun switchBy(delta: Int) {
        if (ids.isEmpty()) return
        hideNextOverlay()
        saveResume()
        index = (index + delta + ids.size) % ids.size
        playCurrent()
    }

    private fun openOptions() {
        val items = arrayOf(
            "Aspect ratio:  ${resizeLabels[resizeIndex]}",
            "Audio track",
            "Subtitles / CC"
        )
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> cycleAspect()
                    1 -> showTrackDialog(C.TRACK_TYPE_AUDIO, "Audio track")
                    2 -> showTrackDialog(C.TRACK_TYPE_TEXT, "Subtitles / CC")
                }
            }
            .show()
    }

    private fun cycleAspect() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        b.playerView.resizeMode = resizeModes[resizeIndex]
        Toast.makeText(this, "Aspect: ${resizeLabels[resizeIndex]}", Toast.LENGTH_SHORT).show()
    }

    private fun showTrackDialog(trackType: Int, title: String) {
        val p = player ?: return
        if (p.currentTracks.groups.none { it.type == trackType }) {
            Toast.makeText(this, "No ${title.lowercase()} available", Toast.LENGTH_SHORT).show()
            return
        }
        TrackSelectionDialogBuilder(this, title, p, trackType)
            .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
            .build()
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && b.nextOverlay.visibility == View.VISIBLE) {
            hideNextOverlay(); return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { switchBy(-1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> { switchBy(1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { switchBy(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { switchBy(1); true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> { openOptions(); true }
            KeyEvent.KEYCODE_CAPTIONS -> { showTrackDialog(C.TRACK_TYPE_TEXT, "Subtitles / CC"); true }
            KeyEvent.KEYCODE_INFO -> { currentChannel()?.let { showInfo(it) }; true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun saveResume() {
        val ch = currentChannel() ?: return
        val p = player ?: return
        if (ch.id.startsWith("movie_") || ch.id.startsWith("ep_")) {
            prefs.setResumePosition(ch.id, p.currentPosition)
        }
    }

    override fun onStop() {
        super.onStop()
        nextTimer?.cancel(); nextTimer = null
        saveResume()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_IDS = "ids"
        const val EXTRA_INDEX = "index"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
    }
}
