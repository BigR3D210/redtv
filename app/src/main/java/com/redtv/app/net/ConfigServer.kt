package com.redtv.app.net

import com.google.gson.JsonParser
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.model.RemoteConfig
import com.redtv.app.model.Section
import com.redtv.app.model.Source
import com.redtv.app.model.SourceProfile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.net.URLEncoder

/** LAN web editor: source, plus reorder/hide/rename categories and reorder/hide/favorite channels. */
class ConfigServer(
    port: Int,
    private val prefs: Prefs,
    private val code: String,
    private val onSaved: () -> Unit
) : NanoHTTPD(port) {

    private val sections = listOf(
        Section.LIVE to "Live TV", Section.MOVIES to "Movies",
        Section.SERIES to "Series", Section.SPORTS to "Sports/PPV"
    )

    override fun serve(session: IHTTPSession): Response {
        return try {
            val p: Map<String, String>
            if (session.method == Method.POST) {
                val files = HashMap<String, String>()
                session.parseBody(files)
                p = session.parms
            } else p = session.parms
            if (p["code"] != code) {
                return page(codeBody(if (session.method == Method.POST) "That code didn't match." else null))
            }
            if (session.method == Method.POST) handlePost(p) else handleGet(p)
        } catch (e: Exception) {
            page("<p>Error: ${esc(e.message ?: "unknown")}</p>${homeLink()}")
        }
    }

    private fun handleGet(p: Map<String, String>): Response = when (p["view"]) {
        "source" -> page(sourceBody())
        "cats" -> page(catsBody(p["section"] ?: Section.LIVE))
        "chans" -> page(chansBody(p["section"] ?: Section.LIVE, p["cat"] ?: ""))
        else -> page(menuBody())
    }

    private fun menuBody(): String {
        val secLinks = sections.joinToString("") { (key, label) ->
            "<a class='card' href='?code=$code&view=cats&section=$key'>Organize $label →</a>"
        }
        return """
            <h2>Red TV — edit</h2>
            <a class='card' href='?code=$code&view=source'>Source / login settings →</a>
            $secLinks
        """.trimIndent()
    }

    private fun catsBody(section: String): String {
        val raw = prefs.orderedCategories(section, ContentRepository.categoriesFor(section))
        val rows = raw.joinToString("") { name ->
            val disp = esc(prefs.categoryDisplayName(section, name))
            val hidden = if (prefs.isCategoryHidden(section, name)) "checked" else ""
            """
            <li data-name="${esc(name)}">
              <span class="h">≡</span>
              <input class="rn" value="$disp" />
              <label class="hd"><input type="checkbox" class="hide" $hidden/> hide</label>
              <a class="edit" href="?code=$code&view=chans&section=$section&cat=${enc(name)}">channels →</a>
            </li>
            """.trimIndent()
        }
        return """
            <h2>Categories — ${esc(sectionLabel(section))}</h2>
            ${sectionTabs(section, "cats")}
            <a class="card" href="?code=$code&view=chans&section=$section&cat=__all__">🔎 Search &amp; favorite all channels →</a>
            <div class="bar">
              <button type="button" onclick="document.querySelectorAll('#list .hide').forEach(function(c){c.checked=true})">Hide all</button>
              <button type="button" onclick="document.querySelectorAll('#list .hide').forEach(function(c){c.checked=false})">Show all</button>
            </div>
            <p class="hint">Drag ≡ to reorder, rename in the box, tick "hide" to remove. Use Hide all / Show all to bulk toggle, then untick the ones you want.</p>
            <form method="post" onsubmit="return pack(this,false)">
              <input type="hidden" name="code" value="$code"/>
              <input type="hidden" name="action" value="cats"/>
              <input type="hidden" name="section" value="$section"/>
              <input type="hidden" name="payload"/>
              <ul id="list">$rows</ul>
              <button type="submit">Save categories</button>
            </form>
            $sortableJs
            ${homeLink()}
        """.trimIndent()
    }

    private fun chansBody(section: String, cat: String): String {
        val all = cat == "__all__"
        val items = if (all) ContentRepository.sectionItems(section)
            else prefs.orderedChannels(section, cat, ContentRepository.sectionItems(section).filter { it.category == cat })
        val title = if (all) "All channels — ${esc(sectionLabel(section))}" else esc(cat)
        var i = 0
        val rows = items.joinToString("") { ch ->
            val hidden = if (prefs.isHidden(ch.id)) "checked" else ""
            val fav = if (prefs.isFavorite(ch.id)) "checked" else ""
            val row = """
            <li data-id="${esc(ch.id)}" data-name="${esc(ch.name.lowercase())}" data-added="${ch.added}" data-i="$i">
              <span class="h">≡</span>
              <span class="nm">${esc(ch.name)}</span>
              <label class="hd"><input type="checkbox" class="fav" $fav/> ★</label>
              <label class="hd"><input type="checkbox" class="hide" $hidden/> hide</label>
            </li>
            """.trimIndent()
            i++; row
        }
        return """
            <h2>Channels — $title</h2>
            <a class="back" href="?code=$code&view=cats&section=$section">← back to categories</a>
            <input id="search" placeholder="🔎 Search channels…" oninput="flt()" />
            <div class="bar">
              <button type="button" onclick="setAll('hide',true)">Hide all</button>
              <button type="button" onclick="setAll('hide',false)">Show all</button>
              <select id="sort" onchange="srt()">
                <option value="def">Default order</option>
                <option value="az">A → Z</option>
                <option value="za">Z → A</option>
                <option value="new">Newest first</option>
              </select>
            </div>
            <p class="hint">Search, sort, tick ★ to favorite, tick "hide" to remove. Drag ≡ to reorder.</p>
            <form method="post" onsubmit="return pack(this,true)">
              <input type="hidden" name="code" value="$code"/>
              <input type="hidden" name="action" value="chans"/>
              <input type="hidden" name="section" value="$section"/>
              <input type="hidden" name="cat" value="${esc(cat)}"/>
              <input type="hidden" name="payload"/>
              <ul id="list">$rows</ul>
              <button type="submit">Save</button>
            </form>
            $sortableJs
            $chanToolsJs
        """.trimIndent()
    }

    private fun sourceBody(): String {
        val s = prefs.activeProfile()?.manual?.source ?: Source()
        val type = if (s.isXtream()) "xtream" else "m3u"
        fun v(x: String?) = esc(x ?: "")
        return """
            <h2>Source / login</h2>
            <form method="post">
              <input type="hidden" name="code" value="$code"/>
              <input type="hidden" name="action" value="source"/>
              <label>Source name</label><input name="name" value="${v(prefs.activeProfile()?.name)}"/>
              <label>Type</label>
              <select name="type" onchange="t(this.value)">
                <option value="xtream" ${if (type=="xtream") "selected" else ""}>Xtream login</option>
                <option value="m3u" ${if (type=="m3u") "selected" else ""}>M3U playlist</option>
              </select>
              <div id="xt">
                <label>Host</label><input name="host" value="${v(s.host)}"/>
                <label>Username</label><input name="user" value="${v(s.username)}"/>
                <label>Password</label><input name="pass" value="${v(s.password)}"/>
              </div>
              <div id="m3"><label>M3U URL</label><input name="m3u" value="${v(s.m3uUrl)}"/></div>
              <label>EPG URL (optional)</label><input name="epg" value="${v(s.epgUrl)}"/>
              <button type="submit">Save source</button>
            </form>
            <script>function t(v){document.getElementById('xt').style.display=v=='xtream'?'block':'none';
              document.getElementById('m3').style.display=v=='m3u'?'block':'none';}t('$type');</script>
            ${homeLink()}
        """.trimIndent()
    }

    // ---------- POST ----------

    private fun handlePost(p: Map<String, String>): Response = when (p["action"]) {
        "source" -> { saveSource(p); page("<h2>Saved ✓</h2>${homeLink()}") }
        "cats" -> { saveCats(p); page("<h2>Categories saved ✓</h2><a class='card' href='?code=$code&view=cats&section=${p["section"]}'>Back</a>${homeLink()}") }
        "chans" -> { saveChans(p); page("<h2>Saved ✓</h2><a class='card' href='?code=$code&view=cats&section=${p["section"]}'>Back to categories</a>${homeLink()}") }
        else -> page(menuBody())
    }

    private fun saveSource(p: Map<String, String>) {
        val type = p["type"] ?: "xtream"
        val name = (p["name"] ?: "").ifBlank { "Laptop edit" }
        val epg = (p["epg"] ?: "").ifBlank { null }
        val source = if (type == "m3u")
            Source(type = "m3u", m3uUrl = (p["m3u"] ?: "").trim(), epgUrl = epg)
        else Source(type = "xtream", host = (p["host"] ?: "").trim(),
            username = (p["user"] ?: "").trim(), password = (p["pass"] ?: "").trim(), epgUrl = epg)
        val id = prefs.activeProfile()?.id ?: "p_${System.currentTimeMillis()}"
        prefs.upsertProfile(SourceProfile(id = id, name = name,
            manual = RemoteConfig(appName = "Red TV", source = source)))
        onSaved()
    }

    private fun saveCats(p: Map<String, String>) {
        val section = p["section"] ?: return
        val root = JsonParser.parseString(p["payload"] ?: "{}").asJsonObject
        val order = root.getAsJsonArray("order")?.map { it.asString } ?: emptyList()
        val hidden = root.getAsJsonArray("hidden")?.map { it.asString }?.toSet() ?: emptySet()
        val rename = root.getAsJsonObject("rename")
        val o = prefs.overrides()
        o.categoryOrder[section] = order.toMutableList()
        val prefix = "$section|"
        o.categoryHidden.removeAll { it.startsWith(prefix) }
        hidden.forEach { o.categoryHidden.add("$section|$it") }
        o.categoryRename.keys.filter { it.startsWith(prefix) }.toList().forEach { o.categoryRename.remove(it) }
        rename?.entrySet()?.forEach { (k, v) ->
            val nv = v.asString.trim()
            if (nv.isNotEmpty() && nv != k) o.categoryRename["$section|$k"] = nv
        }
        prefs.saveOverrides(o)
        ContentRepository.dirty = true
        onSaved()
    }

    private fun saveChans(p: Map<String, String>) {
        val section = p["section"] ?: return
        val cat = p["cat"] ?: return
        val root = JsonParser.parseString(p["payload"] ?: "{}").asJsonObject
        val order = root.getAsJsonArray("order")?.map { it.asString } ?: emptyList()
        val hidden = root.getAsJsonArray("hidden")?.map { it.asString }?.toSet() ?: emptySet()
        val fav = root.getAsJsonArray("fav")?.map { it.asString }?.toSet() ?: emptySet()
        if (cat != "__all__") {
            val o = prefs.overrides()
            o.channelOrder["$section|$cat"] = order.toMutableList()
            prefs.saveOverrides(o)
        }
        prefs.setHiddenForScope(order, hidden)
        prefs.setFavoritesForScope(order, fav)
        ContentRepository.dirty = true
        onSaved()
    }

    // ---------- shared ----------

    private fun sectionLabel(key: String) = sections.firstOrNull { it.first == key }?.second ?: key

    private fun sectionTabs(current: String, view: String): String =
        "<div class='tabs'>" + sections.joinToString("") { (key, label) ->
            val cls = if (key == current) "tab on" else "tab"
            "<a class='$cls' href='?code=$code&view=$view&section=$key'>${esc(label)}</a>"
        } + "</div>"

    private fun homeLink() = "<a class='home' href='?code=$code'>⌂ menu</a>"

    private val sortableJs = """
        <script>
          (function(){
            var ul=document.getElementById('list'); if(!ul) return;
            var drag=null;
            ul.querySelectorAll('li').forEach(function(li){
              li.draggable=true;
              li.addEventListener('dragstart',function(){drag=li; li.classList.add('dg');});
              li.addEventListener('dragend',function(){li.classList.remove('dg');});
              li.addEventListener('dragover',function(e){e.preventDefault();
                if(li===drag) return;
                var r=li.getBoundingClientRect();
                ul.insertBefore(drag, (e.clientY-r.top)/r.height>0.5?li.nextSibling:li);
              });
            });
          })();
          function pack(form, isChan){
            var lis=form.querySelectorAll('#list li'); var order=[],hidden=[],fav=[],rename={};
            lis.forEach(function(li){
              var key=isChan?li.getAttribute('data-id'):li.getAttribute('data-name');
              order.push(key);
              if(li.querySelector('.hide').checked) hidden.push(key);
              if(isChan){ if(li.querySelector('.fav').checked) fav.push(key); }
              else { var rn=li.querySelector('.rn'); if(rn) rename[li.getAttribute('data-name')]=rn.value; }
            });
            var pl={order:order,hidden:hidden}; if(isChan) pl.fav=fav; else pl.rename=rename;
            form.querySelector('[name=payload]').value=JSON.stringify(pl);
            return true;
          }
        </script>
    """.trimIndent()

    private val chanToolsJs = """
        <script>
          var L=document.getElementById('list');
          function flt(){var q=document.getElementById('search').value.toLowerCase();
            L.querySelectorAll('li').forEach(function(li){
              li.style.display=li.getAttribute('data-name').indexOf(q)>=0?'flex':'none';});}
          function setAll(cls,val){L.querySelectorAll('li').forEach(function(li){
            if(li.style.display!=='none') li.querySelector('.'+cls).checked=val;});}
          function srt(){var m=document.getElementById('sort').value;
            var a=Array.prototype.slice.call(L.querySelectorAll('li'));
            a.sort(function(x,y){
              if(m=='az') return x.getAttribute('data-name').localeCompare(y.getAttribute('data-name'));
              if(m=='za') return y.getAttribute('data-name').localeCompare(x.getAttribute('data-name'));
              if(m=='new') return (+y.getAttribute('data-added'))-(+x.getAttribute('data-added'));
              return (+x.getAttribute('data-i'))-(+y.getAttribute('data-i'));
            });
            a.forEach(function(li){L.appendChild(li);});}
        </script>
    """.trimIndent()

    private fun codeBody(err: String?): String {
        val e = if (err != null) "<p style='color:#ff6b6b'>${esc(err)}</p>" else ""
        return """
            <h2>Red TV — pair this device</h2>$e
            <form method="get">
              <label>Enter the 4-digit code shown on your TV</label>
              <input name="code" inputmode="numeric" autofocus/>
              <button type="submit">Continue</button>
            </form>
        """.trimIndent()
    }

    private fun page(body: String): Response = newFixedLengthResponse(
        Status.OK, "text/html",
        """
        <!doctype html><html><head><meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <title>Red TV</title><style>
        body{font-family:system-ui,sans-serif;background:#0E0F13;color:#fff;max-width:640px;margin:0 auto;padding:20px}
        h2{color:#E50914}a{color:#fff}.hint{color:#A8AEBC;font-size:13px}
        label{display:block;margin:12px 0 4px;color:#A8AEBC;font-size:14px}
        input,select{width:100%;padding:10px;border-radius:8px;border:1px solid #333;background:#23262E;color:#fff;font-size:15px;box-sizing:border-box}
        button{margin-top:10px;padding:10px 16px;border:0;border-radius:8px;background:#E50914;color:#fff;font-weight:700;font-size:15px;cursor:pointer}
        .bar{display:flex;gap:8px;margin:8px 0;align-items:center;flex-wrap:wrap}.bar button{margin:0;background:#333}.bar select{width:auto}
        .card{display:block;background:#23262E;border:1px solid #333;border-radius:10px;padding:16px;margin:10px 0;text-decoration:none;font-weight:600}
        .tabs{margin:10px 0;display:flex;flex-wrap:wrap;gap:6px}.tab{padding:6px 12px;border-radius:8px;background:#23262E;text-decoration:none;font-size:14px}.tab.on{background:#E50914}
        ul{list-style:none;padding:0;margin:14px 0}
        li{display:flex;align-items:center;gap:8px;background:#23262E;border:1px solid #333;border-radius:8px;padding:8px 10px;margin-bottom:6px}
        li.dg{opacity:.4}.h{cursor:grab;color:#A8AEBC;font-size:20px;padding:0 4px}
        .rn{flex:1;width:auto}.nm{flex:1}.hd{display:flex;align-items:center;gap:4px;color:#A8AEBC;font-size:13px;margin:0;white-space:nowrap}
        .hd input{width:auto}.edit,.back,.home{font-size:13px;color:#E50914}.home{display:inline-block;margin-top:18px}
        </style></head><body>$body</body></html>
        """.trimIndent()
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
