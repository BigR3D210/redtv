package com.redtv.app.net

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.redtv.app.model.Channel
import com.redtv.app.model.EpgProgram
import com.redtv.app.model.Section
import com.redtv.app.model.Source
import java.net.URLEncoder

/** Xtream Codes API client: Live (+ Sports split), Movies (VOD), and Series. */
object XtreamClient {

    /** Set after load(): non-null if the VOD/Movies fetch failed (so the UI can explain a blank section). */
    @Volatile var lastVodError: String? = null

    private val sportsKeywords = listOf(
        "sport", "ppv", "pay per view", "pay-per-view", "espn", "dazn", "ufc", "nfl",
        "nba", "mlb", "nhl", "fight", "boxing", "wwe", "wrestling", "event", "racing",
        "formula", " f1", "soccer", "football", "fifa", "tennis", "golf", "rugby",
        "cricket", "nascar", "motogp", "fanatiz", "willow"
    )

    private fun isSports(category: String?): Boolean {
        val c = (category ?: "").lowercase()
        return sportsKeywords.any { c.contains(it) }
    }

    /** Live + Sports + Movies in one shot (the grid-able tiles). */
    fun load(source: Source): List<Channel> {
        val ctx = ctx(source)
        val out = ArrayList<Channel>()
        out += loadLive(ctx)
        lastVodError = null
        runCatching { out += loadVod(ctx) }.onFailure { lastVodError = it.message ?: it.toString() }
        return out
    }

    /** Series folders (each opens to its episodes). */
    fun loadSeries(source: Source): List<Channel> {
        val ctx = ctx(source)
        val cats = catMap(get("${ctx.base}&action=get_series_categories"))
        val arr = getArray("${ctx.base}&action=get_series")
        val out = ArrayList<Channel>(arr.size())
        for (e in arr) {
            val o = e.asJsonObject
            val id = o.str("series_id") ?: continue
            out.add(
                Channel(
                    id = "series_$id",
                    name = o.str("name") ?: "Series $id",
                    streamUrl = "",
                    logoUrl = o.str("cover"),
                    category = cats[o.str("category_id")] ?: "Series",
                    section = Section.SERIES,
                    added = o.str("last_modified")?.toLongOrNull() ?: 0,
                    rating = o.str("rating_5based")?.toDoubleOrNull() ?: o.str("rating")?.toDoubleOrNull() ?: 0.0
                )
            )
        }
        return out
    }

    /** Episodes inside a series, ready to play. */
    fun loadEpisodes(source: Source, seriesId: String): List<Channel> {
        val ctx = ctx(source)
        val json = get("${ctx.base}&action=get_series_info&series_id=$seriesId")
        val root = JsonParser.parseString(json).asJsonObject
        val episodesObj = root.getAsJsonObject("episodes") ?: return emptyList()
        val out = ArrayList<Channel>()
        for ((season, list) in episodesObj.entrySet()) {
            if (!list.isJsonArray) continue
            for (e in list.asJsonArray) {
                val o = e.asJsonObject
                val id = o.str("id") ?: continue
                val ext = o.str("container_extension") ?: "mp4"
                val title = o.str("title") ?: "Episode $id"
                out.add(
                    Channel(
                        id = "ep_$id",
                        name = "S${season} • $title",
                        streamUrl = "${ctx.host}/series/${ctx.user}/${ctx.pass}/$id.$ext",
                        logoUrl = o.getAsJsonObject("info")?.str("movie_image"),
                        section = Section.SERIES
                    )
                )
            }
        }
        return out
    }

    /** Current/next programs for one live channel, straight from the Xtream EPG (no XMLTV needed). */
    fun loadShortEpg(source: Source, streamId: String): List<EpgProgram> {
        val ctx = ctx(source)
        val json = get("${ctx.base}&action=get_short_epg&stream_id=$streamId&limit=4")
        val root = JsonParser.parseString(json).asJsonObject
        val arr = root.getAsJsonArray("epg_listings") ?: return emptyList()
        val out = ArrayList<EpgProgram>(arr.size())
        for (e in arr) {
            val o = e.asJsonObject
            val start = o.str("start_timestamp")?.toLongOrNull() ?: continue
            val stop = o.str("stop_timestamp")?.toLongOrNull() ?: continue
            out.add(
                EpgProgram(
                    channelId = streamId,
                    title = decodeB64(o.str("title")),
                    startMillis = start * 1000,
                    stopMillis = stop * 1000,
                    description = decodeB64(o.str("description"))
                )
            )
        }
        return out
    }

    private fun decodeB64(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)).trim() }
            .getOrDefault(s)
    }

    // ---- internals ----

    private class Ctx(val host: String, val user: String, val pass: String, val base: String)

    private fun ctx(source: Source): Ctx {
        val host = normalizeHost(source.host ?: error("Missing host"))
        val user = source.username ?: error("Missing username")
        val pass = source.password ?: error("Missing password")
        val base = "$host/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        return Ctx(host, user, pass, base)
    }

    private fun loadLive(ctx: Ctx): List<Channel> {
        val cats = catMap(get("${ctx.base}&action=get_live_categories"))
        val streams = getArray("${ctx.base}&action=get_live_streams")
        val out = ArrayList<Channel>(streams.size())
        for (e in streams) {
            val o = e.asJsonObject
            val id = o.str("stream_id") ?: continue
            val ext = o.str("container_extension") ?: "m3u8"
            val cat = cats[o.str("category_id")] ?: "Live TV"
            out.add(
                Channel(
                    id = "live_$id",
                    name = o.str("name") ?: "Channel $id",
                    streamUrl = "${ctx.host}/live/${ctx.user}/${ctx.pass}/$id.$ext",
                    logoUrl = o.str("stream_icon"),
                    category = cat,
                    epgChannelId = o.str("epg_channel_id"),
                    number = o.str("num")?.toIntOrNull(),
                    section = if (isSports(cat)) Section.SPORTS else Section.LIVE
                )
            )
        }
        return out
    }

    private fun loadVod(ctx: Ctx): List<Channel> {
        val cats = catMap(get("${ctx.base}&action=get_vod_categories"))
        val streams = getArray("${ctx.base}&action=get_vod_streams")
        val out = ArrayList<Channel>(streams.size())
        for (e in streams) {
            val o = e.asJsonObject
            val id = o.str("stream_id") ?: continue
            val ext = o.str("container_extension") ?: "mp4"
            out.add(
                Channel(
                    id = "movie_$id",
                    name = o.str("name") ?: "Movie $id",
                    streamUrl = "${ctx.host}/movie/${ctx.user}/${ctx.pass}/$id.$ext",
                    logoUrl = o.str("stream_icon") ?: o.str("cover"),
                    category = cats[o.str("category_id")] ?: "VOD",
                    section = Section.MOVIES,
                    added = o.str("added")?.toLongOrNull() ?: 0,
                    rating = o.str("rating_5based")?.toDoubleOrNull() ?: o.str("rating")?.toDoubleOrNull() ?: 0.0
                )
            )
        }
        return out
    }

    private fun catMap(json: String): Map<String, String> {
        val map = HashMap<String, String>()
        runCatching {
            JsonParser.parseString(json).asJsonArray.forEach {
                val o = it.asJsonObject
                val id = o.str("category_id") ?: return@forEach
                map[id] = o.str("category_name") ?: id
            }
        }
        return map
    }

    private fun get(url: String): String = Http.getString(url)

    private fun getArray(url: String): JsonArray {
        val el = JsonParser.parseString(get(url))
        return if (el.isJsonArray) el.asJsonArray else JsonArray()
    }

    private fun JsonObject.str(key: String): String? {
        val v = get(key) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun normalizeHost(raw: String): String {
        var h = raw.trim().trimEnd('/')
        if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
        return h
    }
}
