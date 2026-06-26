package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.se_bastiaan.torrentstream.StreamStatus
import com.github.se_bastiaan.torrentstream.Torrent
import com.github.se_bastiaan.torrentstream.TorrentOptions
import com.github.se_bastiaan.torrentstream.TorrentStream
import com.github.se_bastiaan.torrentstream.listeners.TorrentListener
import com.redtv.app.databinding.ActivityCastBinding
import com.redtv.app.net.CastServer
import com.redtv.app.util.QrGen
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class CastActivity : AppCompatActivity() {

    private var server: CastServer? = null
    private var torrentStream: TorrentStream? = null
    private lateinit var b: ActivityCastBinding
    private val code = (1000..9999).random().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCastBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.codeText.text = code
        b.btnDone.setOnClickListener { finish() }
        b.btnDone.requestFocus()

        val ip = localIp()
        val port = 8081
        val url = if (ip != null) "http://$ip:$port" else null

        val srv = CastServer(port, code, cacheDir) { mediaUrl, name ->
            runOnUiThread { handlePlay(mediaUrl, name) }
        }
        try {
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
        } catch (e: Exception) {
            b.statusText.text = "Couldn't start cast: ${e.message}"
        }
        b.urlText.text = url ?: "Wi-Fi address not found"
        if (url != null) QrGen.make("$url?code=$code")?.let { b.qrImage.setImageBitmap(it) }
    }

    private fun handlePlay(mediaUrl: String, name: String) {
        if (mediaUrl.startsWith("magnet:", true) || mediaUrl.endsWith(".torrent", true)) {
            startTorrent(mediaUrl)
        } else {
            b.statusText.text = "Now playing: $name"
            launchPlayer(mediaUrl, name)
        }
    }

    private fun launchPlayer(mediaUrl: String, name: String) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, mediaUrl)
            .putExtra(PlayerActivity.EXTRA_NAME, name))
    }

    private fun startTorrent(magnet: String) {
        b.statusText.text = "Preparing torrent… finding peers"
        val options = TorrentOptions.Builder()
            .saveLocation(cacheDir)
            .removeFilesAfterStop(true)
            .prepareSize(10L * 1024 * 1024)
            .build()
        val ts = torrentStream ?: TorrentStream.init(options).also { torrentStream = it }
        ts.addListener(object : TorrentListener {
            override fun onStreamPrepared(torrent: Torrent?) {}
            override fun onStreamStarted(torrent: Torrent?) {}
            override fun onStreamReady(torrent: Torrent?) {
                val f = torrent?.videoFile ?: return
                runOnUiThread {
                    b.statusText.text = "Streaming on TV…"
                    launchPlayer("file://${f.absolutePath}", "Torrent")
                }
            }
            override fun onStreamProgress(torrent: Torrent?, status: StreamStatus?) {
                val s = status ?: return
                runOnUiThread { b.statusText.text = "Buffering ${s.bufferProgress}%  •  ${s.seeds} peers" }
            }
            override fun onStreamStopped() {}
            override fun onStreamError(torrent: Torrent?, e: Exception?) {
                runOnUiThread { b.statusText.text = "Torrent error: ${e?.message ?: "failed"}" }
            }
        })
        try {
            ts.startStream(magnet)
        } catch (e: Exception) {
            b.statusText.text = "Couldn't start torrent: ${e.message}"
        }
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { null }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        try { torrentStream?.stopStream() } catch (e: Exception) {}
    }
}
