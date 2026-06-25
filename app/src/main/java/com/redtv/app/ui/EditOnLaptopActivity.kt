package com.redtv.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityEditLaptopBinding
import com.redtv.app.net.ConfigServer
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class EditOnLaptopActivity : AppCompatActivity() {

    private var server: ConfigServer? = null
    private lateinit var b: ActivityEditLaptopBinding
    private val code = (1000..9999).random().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditLaptopBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.codeText.text = code
        b.btnDone.setOnClickListener { finish() }
        b.btnDone.requestFocus()

        val prefs = Prefs(this)
        val ip = localIp()
        var port = 8080
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
        b.urlText.text = if (ip != null) "http://$ip:$port" else "Wi-Fi address not found"
    }

    private fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }
}
