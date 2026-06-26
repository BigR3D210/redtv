package com.redtv.app.model

import com.google.gson.annotations.SerializedName

data class RemoteConfig(
    @SerializedName("accountId") val accountId: String? = null,
    @SerializedName("appName") val appName: String? = null,
    @SerializedName("userAgent") val userAgent: String? = null,
    @SerializedName("source") val source: Source = Source()
)

data class Source(
    @SerializedName("type") val type: String = "m3u",
    @SerializedName("m3uUrl") val m3uUrl: String? = null,
    @SerializedName("epgUrl") val epgUrl: String? = null,
    @SerializedName("host") val host: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("password") val password: String? = null
) {
    fun isXtream() = type.equals("xtream", ignoreCase = true)
}

/** Sections the home screen is split into. */
object Section {
    const val LIVE = "live"
    const val MOVIES = "movie"
    const val SERIES = "series"
    const val SPORTS = "sports"
}

/**
 * A single tile. Covers live channels, movies, series (as a folder), and episodes.
 * For a series folder, streamUrl is empty and id starts with "series_".
 */
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val category: String = "Uncategorized",
    val epgChannelId: String? = null,
    val number: Int? = null,
    val section: String = Section.LIVE
)

data class SourceProfile(
    val id: String,
    val name: String,
    val configUrl: String? = null,
    val manual: RemoteConfig? = null
)

data class EpgProgram(
    val channelId: String,
    val title: String,
    val startMillis: Long,
    val stopMillis: Long,
    val description: String? = null
)

/** Personal edits layered on top of the live playlist (per device). */
data class Overrides(
    val categoryOrder: MutableMap<String, MutableList<String>> = HashMap(),  // section -> ordered category names
    val categoryHidden: MutableSet<String> = HashSet(),                       // "section|name"
    val categoryRename: MutableMap<String, String> = HashMap(),               // "section|name" -> display name
    val channelOrder: MutableMap<String, MutableList<String>> = HashMap()     // "section|category" -> channel ids
)
