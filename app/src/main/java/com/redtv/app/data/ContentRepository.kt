package com.redtv.app.data

import com.google.gson.Gson
import com.redtv.app.model.Channel
import com.redtv.app.model.EpgProgram
import com.redtv.app.model.RemoteConfig
import com.redtv.app.model.Section
import com.redtv.app.model.SourceProfile
import com.redtv.app.net.EpgParser
import com.redtv.app.net.Http
import com.redtv.app.net.M3uParser
import com.redtv.app.net.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-memory store, split by section (Live / Movies / Series / Sports). */
object ContentRepository {

    @Volatile var live: List<Channel> = emptyList(); private set
    @Volatile var movies: List<Channel> = emptyList(); private set
    @Volatile var sports: List<Channel> = emptyList(); private set
    @Volatile var series: List<Channel> = emptyList(); private set
    @Volatile var epg: Map<String, List<EpgProgram>> = emptyMap(); private set
    @Volatile var config: RemoteConfig? = null; private set

    /** Episodes of the series currently being browsed (so the player can resolve them). */
    @Volatile var currentEpisodes: List<Channel> = emptyList()

    /** Set when the config changes from the laptop editor, so the home reloads. */
    @Volatile var dirty = false

    private var currentSource: com.redtv.app.model.Source? = null
    private val gson = Gson()

    fun hasAny() = live.isNotEmpty() || movies.isNotEmpty() || sports.isNotEmpty() || series.isNotEmpty()

    fun sectionItems(section: String): List<Channel> = when (section) {
        Section.MOVIES -> movies
        Section.SERIES -> series
        Section.SPORTS -> sports
        else -> live
    }

    fun categoriesFor(section: String): List<String> =
        sectionItems(section).map { it.category }.distinct().sortedBy { it.lowercase() }

    fun channelById(id: String): Channel? =
        (live.asSequence() + movies.asSequence() + sports.asSequence() +
            series.asSequence() + currentEpisodes.asSequence()).firstOrNull { it.id == id }

    suspend fun reload(prefs: Prefs) = withContext(Dispatchers.IO) {
        val cfg = resolveConfig(prefs)
        config = cfg
        currentSource = cfg.source
        Http.userAgent = cfg.userAgent?.takeIf { it.isNotBlank() } ?: Http.userAgent

        val all: List<Channel>
        if (cfg.source.isXtream()) {
            all = XtreamClient.load(cfg.source)
            series = runCatching { XtreamClient.loadSeries(cfg.source) }.getOrDefault(emptyList())
        } else {
            val url = cfg.source.m3uUrl ?: error("No M3U URL in config")
            all = M3uParser.parse(Http.getString(url))
            series = all.filter { it.section == Section.SERIES }
        }
        live = all.filter { it.section == Section.LIVE }
        movies = all.filter { it.section == Section.MOVIES }
        sports = all.filter { it.section == Section.SPORTS }

        val epgUrl = cfg.source.epgUrl
        epg = if (!epgUrl.isNullOrBlank())
            runCatching { EpgParser.parse(Http.getString(epgUrl)) }.getOrDefault(emptyMap())
        else emptyMap()
    }

    /** Load the episodes for a series id and remember them for the player. */
    suspend fun loadEpisodes(seriesId: String): List<Channel> = withContext(Dispatchers.IO) {
        val src = currentSource ?: return@withContext emptyList()
        val eps = runCatching { XtreamClient.loadEpisodes(src, seriesId) }.getOrDefault(emptyList())
        currentEpisodes = eps
        eps
    }

    suspend fun previewChannels(profile: SourceProfile): List<Channel> = withContext(Dispatchers.IO) {
        val cfg = configForProfile(profile)
        Http.userAgent = cfg.userAgent?.takeIf { it.isNotBlank() } ?: Http.userAgent
        if (cfg.source.isXtream()) XtreamClient.load(cfg.source)
        else M3uParser.parse(Http.getString(cfg.source.m3uUrl ?: error("No M3U URL")))
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
        if (!profile.configUrl.isNullOrBlank()) {
            return runCatching {
                val cfg = configForProfile(profile)
                prefs.cachedConfig = cfg
                cfg
            }.getOrElse { e -> prefs.cachedConfig ?: throw e }
        }
        return profile.manual ?: error("No source configured")
    }

    fun nowAndNext(epgChannelId: String?): Pair<EpgProgram?, EpgProgram?> {
        if (epgChannelId == null) return null to null
        val list = epg[epgChannelId] ?: return null to null
        val now = System.currentTimeMillis()
        val current = list.firstOrNull { now in it.startMillis until it.stopMillis }
        val next = list.firstOrNull { it.startMillis >= now }
        return current to next
    }
}
