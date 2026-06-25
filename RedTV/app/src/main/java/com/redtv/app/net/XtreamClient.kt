package com.redtv.app.net

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.redtv.app.model.Channel
import com.redtv.app.model.Source
import java.net.URLEncoder

/**
 * Minimal Xtream Codes API client. Loads Live TV channels and VOD movies.
 * (Series is intentionally left out of v1 — it needs a per-series detail call.
 *  See README for how to add it.)
 */
object XtreamClient {

    fun load(source: Source): List<Channel> {
        val host = normalizeHost(source.host ?: error("Missing host"))
        val user = source.username ?: error("Missing username")
        val pass = source.password ?: error("Missing password")
        val base = "$host/player_api.php?username=${enc(user)}&password=${enc(pass)}"

        val channels = ArrayList<Channel>()
        channels += loadLive(base, host, user, pass)
        runCatching { channels += loadVod(base, host, user, pass) }
        return channels
    }

    private fun loadLive(base: String, host: String, user: String, pass: String): List<Channel> {
        val cats = catMap(get("$base&action=get_live_categories"))
        val streams = getArray("$base&action=get_live_streams")
        val out = ArrayList<Channel>(streams.size())
        for (e in streams) {
            val o = e.asJsonObject
            val id = o.str("stream_id") ?: continue
            val ext = o.str("container_extension") ?: "m3u8"
            out.add(
                Channel(
                    id = "live_$id",
                    name = o.str("name") ?: "Channel $id",
                    streamUrl = "$host/live/${enc(user)}/${enc(pass)}/$id.$ext",
                    logoUrl = o.str("stream_icon"),
                    category = cats[o.str("category_id")] ?: "Live TV",
                    epgChannelId = o.str("epg_channel_id"),
                    number = o.str("num")?.toIntOrNull()
                )
            )
        }
        return out
    }

    private fun loadVod(base: String, host: String, user: String, pass: String): List<Channel> {
        val cats = catMap(get("$base&action=get_vod_categories"))
        val streams = getArray("$base&action=get_vod_streams")
        val out = ArrayList<Channel>(streams.size())
        for (e in streams) {
            val o = e.asJsonObject
            val id = o.str("stream_id") ?: continue
            val ext = o.str("container_extension") ?: "mp4"
            out.add(
                Channel(
                    id = "movie_$id",
                    name = o.str("name") ?: "Movie $id",
                    streamUrl = "$host/movie/${enc(user)}/${enc(pass)}/$id.$ext",
                    logoUrl = o.str("stream_icon") ?: o.str("cover"),
                    category = "Movies • " + (cats[o.str("category_id")] ?: "VOD")
                )
            )
        }
        return out
    }

    // ---- helpers ----

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
