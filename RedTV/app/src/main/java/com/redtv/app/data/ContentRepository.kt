package com.redtv.app.data

import com.google.gson.Gson
import com.redtv.app.model.Channel
import com.redtv.app.model.EpgProgram
import com.redtv.app.model.RemoteConfig
import com.redtv.app.model.SourceProfile
import com.redtv.app.net.EpgParser
import com.redtv.app.net.Http
import com.redtv.app.net.M3uParser
import com.redtv.app.net.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central in-memory store. Resolves the account config, loads channels from the
 * chosen source (M3U or Xtream) and optionally an XMLTV EPG. Held as a singleton
 * so the browser and player share the same loaded data.
 */
object ContentRepository {

    @Volatile var channels: List<Channel> = emptyList()
        private set
    @Volatile var categories: List<String> = emptyList()
        private set
    @Volatile var epg: Map<String, List<EpgProgram>> = emptyMap()
        private set
    @Volatile var config: RemoteConfig? = null
        private set

    private val gson = Gson()

    fun channelById(id: String): Channel? = channels.firstOrNull { it.id == id }

    /** Resolve config -> load channels -> load EPG. Throws on hard failure. */
    suspend fun reload(prefs: Prefs) = withContext(Dispatchers.IO) {
        val cfg = resolveConfig(prefs)
        config = cfg
        val list = buildChannels(cfg)
        channels = list
        categories = list.map { it.category }
            .distinct()
            .sortedWith(compareBy({ !it.startsWith("Movies") }, { it.lowercase() }))

        val epgUrl = cfg.source.epgUrl
        epg = if (!epgUrl.isNullOrBlank()) {
            runCatching { EpgParser.parse(Http.getString(epgUrl)) }.getOrDefault(emptyMap())
        } else emptyMap()
    }

    /**
     * Load channels for a candidate profile WITHOUT committing it as the active
     * source. Used by the setup wizard's "Test connection" step. Throws on failure.
     */
    suspend fun previewChannels(profile: SourceProfile): List<Channel> = withContext(Dispatchers.IO) {
        val cfg = configForProfile(profile)
        buildChannels(cfg)
    }

    private fun buildChannels(cfg: RemoteConfig): List<Channel> {
        Http.userAgent = cfg.userAgent?.takeIf { it.isNotBlank() } ?: Http.userAgent
        return if (cfg.source.isXtream()) {
            XtreamClient.load(cfg.source)
        } else {
            val url = cfg.source.m3uUrl ?: error("No M3U URL in config")
            M3uParser.parse(Http.getString(url))
        }
    }

    private fun configForProfile(profile: SourceProfile): RemoteConfig {
        val url = profile.configUrl
        if (!url.isNullOrBlank()) {
            val json = Http.getString(url)
            return gson.fromJson(json, RemoteConfig::class.java) ?: error("Empty config")
        }
        return profile.manual ?: error("No source configured")
    }

    private fun resolveConfig(prefs: Prefs): RemoteConfig {
        val profile = prefs.activeProfile() ?: error("No source configured")
        val url = profile.configUrl
        if (!url.isNullOrBlank()) {
            return runCatching {
                val cfg = configForProfile(profile)
                prefs.cachedConfig = cfg
                cfg
            }.getOrElse { e ->
                prefs.cachedConfig ?: throw e
            }
        }
        return profile.manual ?: error("No source configured")
    }

    /** Current programme for an EPG channel id, if any. */
    fun nowAndNext(epgChannelId: String?): Pair<EpgProgram?, EpgProgram?> {
        if (epgChannelId == null) return null to null
        val list = epg[epgChannelId] ?: return null to null
        val now = System.currentTimeMillis()
        val current = list.firstOrNull { now in it.startMillis until it.stopMillis }
        val next = list.firstOrNull { it.startMillis >= now }
        return current to next
    }
}
