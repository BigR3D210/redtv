package com.redtv.app.net

import com.redtv.app.data.Prefs
import com.redtv.app.model.RemoteConfig
import com.redtv.app.model.Source
import com.redtv.app.model.SourceProfile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse

/**
 * Tiny LAN web server. The TV shows a URL + pairing code; the laptop opens the URL,
 * enters the code, edits the source, and saves it straight into the app.
 */
class ConfigServer(
    port: Int,
    private val prefs: Prefs,
    private val code: String,
    private val onSaved: () -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.POST) handlePost(session) else handleGet(session)
        } catch (e: Exception) {
            page("<p>Error: ${esc(e.message ?: "unknown")}</p>")
        }
    }

    private fun handleGet(session: IHTTPSession): Response {
        val given = session.parms["code"]
        return if (given == code) page(formBody()) else page(codeBody(null))
    }

    private fun handlePost(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val p = session.parms
        if (p["code"] != code) return page(codeBody("That code didn't match. Check the TV and try again."))

        val type = p["type"] ?: "xtream"
        val name = (p["name"] ?: "").ifBlank { "Laptop edit" }
        val epg = (p["epg"] ?: "").ifBlank { null }
        val source = if (type == "m3u")
            Source(type = "m3u", m3uUrl = (p["m3u"] ?: "").trim(), epgUrl = epg)
        else
            Source(type = "xtream", host = (p["host"] ?: "").trim(),
                username = (p["user"] ?: "").trim(), password = (p["pass"] ?: "").trim(), epgUrl = epg)

        val id = prefs.activeProfile()?.id ?: "p_${System.currentTimeMillis()}"
        prefs.upsertProfile(SourceProfile(id = id, name = name,
            manual = RemoteConfig(appName = "Red TV", source = source)))
        onSaved()
        return page("<h2>Saved ✓</h2><p>Your TV is reloading with the new settings. " +
            "You can close this tab.</p>")
    }

    private fun codeBody(err: String?): String {
        val e = if (err != null) "<p style='color:#ff6b6b'>${esc(err)}</p>" else ""
        return """
            <h2>Red TV — pair this device</h2>
            $e
            <form method="get">
              <label>Enter the 4-digit code shown on your TV</label>
              <input name="code" inputmode="numeric" autofocus />
              <button type="submit">Continue</button>
            </form>
        """.trimIndent()
    }

    private fun formBody(): String {
        val s = prefs.activeProfile()?.manual?.source ?: Source()
        val type = if (s.isXtream()) "xtream" else "m3u"
        fun v(x: String?) = esc(x ?: "")
        return """
            <h2>Edit your Red TV source</h2>
            <form method="post">
              <input type="hidden" name="code" value="${esc(code)}" />
              <label>Source name</label>
              <input name="name" value="${v(prefs.activeProfile()?.name)}" />
              <label>Type</label>
              <select name="type" onchange="t(this.value)">
                <option value="xtream" ${if (type=="xtream") "selected" else ""}>Xtream login</option>
                <option value="m3u" ${if (type=="m3u") "selected" else ""}>M3U playlist</option>
              </select>
              <div id="xt">
                <label>Host (http://server:port)</label><input name="host" value="${v(s.host)}" />
                <label>Username</label><input name="user" value="${v(s.username)}" />
                <label>Password</label><input name="pass" value="${v(s.password)}" />
              </div>
              <div id="m3"><label>M3U URL</label><input name="m3u" value="${v(s.m3uUrl)}" /></div>
              <label>EPG (XMLTV) URL — optional</label><input name="epg" value="${v(s.epgUrl)}" />
              <button type="submit">Save to TV</button>
            </form>
            <script>
              function t(v){document.getElementById('xt').style.display=v=='xtream'?'block':'none';
                document.getElementById('m3').style.display=v=='m3u'?'block':'none';}
              t('${type}');
            </script>
        """.trimIndent()
    }

    private fun page(body: String): Response = newFixedLengthResponse(
        Response.Status.OK, "text/html",
        """
        <!doctype html><html><head><meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <title>Red TV</title><style>
        body{font-family:system-ui,sans-serif;background:#0E0F13;color:#fff;max-width:520px;margin:0 auto;padding:24px}
        h2{color:#E50914}label{display:block;margin:14px 0 4px;color:#A8AEBC;font-size:14px}
        input,select{width:100%;padding:11px;border-radius:8px;border:1px solid #333;background:#23262E;color:#fff;font-size:15px;box-sizing:border-box}
        button{margin-top:18px;padding:12px 18px;border:0;border-radius:8px;background:#E50914;color:#fff;font-weight:700;font-size:15px}
        </style></head><body>$body</body></html>
        """.trimIndent()
    )

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
