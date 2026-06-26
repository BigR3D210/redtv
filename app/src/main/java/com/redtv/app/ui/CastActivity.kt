package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.redtv.app.databinding.ActivityCastBinding
import com.redtv.app.net.CastServer
import com.redtv.app.util.QrGen
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class CastActivity : AppCompatActivity() {

    private var server: CastServer? = null
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
            runOnUiThread {
                b.statusText.text = "Now playing: $name"
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, mediaUrl)
                    .putExtra(PlayerActivity.EXTRA_NAME, name))
            }
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

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { null }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }
}
