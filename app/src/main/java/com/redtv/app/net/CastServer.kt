package com.redtv.app.net

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File

/** Tiny LAN server to push a video to the TV: paste a link or upload a local file. */
class CastServer(
    port: Int,
    private val code: String,
    private val cacheDir: File,
    private val onPlay: (url: String, name: String) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            if (session.method == Method.POST) session.parseBody(files)
            val p = session.parms
            if (p["code"] != code) return page(body(if (session.method == Method.POST) "Wrong code." else null))

            if (session.method == Method.POST) {
                when (p["action"]) {
                    "url" -> {
                        val url = (p["url"] ?: "").trim()
                        if (url.isNotEmpty()) { onPlay(url, "Cast link"); return page(okBody("Playing your link on the TV ✓")) }
                        return page(body("Enter a link."))
                    }
                    "file" -> {
                        val tmp = files["file"]
                        val name = (p["file"] ?: "video").substringAfterLast('/')
                        if (tmp != null && File(tmp).exists()) {
                            val ext = name.substringAfterLast('.', "mp4")
                            val dest = File(cacheDir, "cast_${System.currentTimeMillis()}.$ext")
                            File(tmp).copyTo(dest, overwrite = true)
                            onPlay("file://${dest.absolutePath}", name)
                            return page(okBody("Sent \"${esc(name)}\" to the TV ✓"))
                        }
                        return page(body("No file received."))
                    }
                }
            }
            page(body(null))
        } catch (e: Exception) {
            page(body("Error: ${esc(e.message ?: "unknown")}"))
        }
    }

    private fun body(err: String?): String {
        val e = if (err != null) "<p style='color:#ff6b6b'>${esc(err)}</p>" else ""
        return """
            <h2>Cast to TV</h2>$e
            <h3>Paste a video link</h3>
            <form method="post">
              <input type="hidden" name="code" value="$code"/>
              <input type="hidden" name="action" value="url"/>
              <input name="url" placeholder="https://… .mp4 / .m3u8 / .mkv / .ts"/>
              <button type="submit">Play link on TV</button>
            </form>
            <h3>Or send a file from this device</h3>
            <form method="post" enctype="multipart/form-data">
              <input type="hidden" name="code" value="$code"/>
              <input type="hidden" name="action" value="file"/>
              <input type="file" name="file" accept="video/*"/>
              <button type="submit">Upload &amp; play</button>
            </form>
            <p class="hint">Direct video files and most stream URLs work. YouTube/Netflix page links do not.</p>
        """.trimIndent()
    }

    private fun okBody(msg: String) = "<h2>$msg</h2><a href='?code=$code'>← cast another</a>"

    private fun page(b: String): Response = newFixedLengthResponse(
        Status.OK, "text/html",
        """
        <!doctype html><html><head><meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1"/><title>Cast — Red TV</title><style>
        body{font-family:system-ui,sans-serif;background:#0E0F13;color:#fff;max-width:520px;margin:0 auto;padding:20px}
        h2{color:#E50914}h3{margin-top:22px;color:#fff}.hint{color:#A8AEBC;font-size:13px}
        input{width:100%;padding:11px;border-radius:8px;border:1px solid #333;background:#23262E;color:#fff;font-size:15px;box-sizing:border-box;margin-top:6px}
        button{margin-top:12px;padding:12px 18px;border:0;border-radius:8px;background:#E50914;color:#fff;font-weight:700;font-size:15px}
        a{color:#E50914}
        </style></head><body>$b</body></html>
        """.trimIndent()
    )

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
