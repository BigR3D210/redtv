package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityEditLaptopBinding
import com.redtv.app.net.ConfigServer
import com.redtv.app.util.QrGen
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class EditOnLaptopActivity : AppCompatActivity() {

    private var server: ConfigServer? = null
    private lateinit var b: ActivityEditLaptopBinding
    private lateinit var prefs: Prefs
    private val code = (1000..9999).random().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditLaptopBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        b.codeText.text = code
        b.btnDone.setOnClickListener { done() }
        b.btnDone.requestFocus()

        val ip = localIp()
        val port = 8080
        val url = if (ip != null) "http://$ip:$port" else null

        val srv = ConfigServer(port, prefs, code) {
            ContentRepository.dirty = true
            runOnUiThread { b.statusText.text = "Saved from your browser ✓  (press Done)" }
        }
        try {
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
        } catch (e: Exception) {
            b.statusText.text = "Couldn't start the editor: ${e.message}"
        }

        b.urlText.text = url ?: "Wi-Fi address not found"
        if (url != null) QrGen.make("$url?code=$code")?.let { b.qrImage.setImageBitmap(it) }
    }

    private fun done() {
        if (prefs.hasSource()) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
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
