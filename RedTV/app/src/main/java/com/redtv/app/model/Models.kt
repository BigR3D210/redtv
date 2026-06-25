package com.redtv.app.model

import com.google.gson.annotations.SerializedName

/**
 * Account config. You host this JSON at a URL you control (GitHub raw, Firebase,
 * any static host) and paste that URL into the app once. Edit the file online to
 * change channels or login info; the app re-reads it on every launch.
 */
data class RemoteConfig(
    @SerializedName("accountId") val accountId: String? = null,
    @SerializedName("appName") val appName: String? = null,
    @SerializedName("userAgent") val userAgent: String? = null,
    @SerializedName("source") val source: Source = Source()
)

data class Source(
    /** "xtream" or "m3u" */
    @SerializedName("type") val type: String = "m3u",
    @SerializedName("m3uUrl") val m3uUrl: String? = null,
    @SerializedName("epgUrl") val epgUrl: String? = null,
    @SerializedName("host") val host: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("password") val password: String? = null
) {
    fun isXtream() = type.equals("xtream", ignoreCase = true)
}

/** A single playable item (live channel, movie, or series episode entry). */
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val category: String = "Uncategorized",
    val epgChannelId: String? = null,
    val number: Int? = null
)

data class Category(
    val id: String,
    val name: String
)

/**
 * A saved source ("profile"). Either points to a remote config URL or holds a
 * manually-entered config. Lets the app keep multiple providers and switch fast.
 */
data class SourceProfile(
    val id: String,
    val name: String,
    val configUrl: String? = null,
    val manual: RemoteConfig? = null
)

/** One EPG programme entry parsed from XMLTV. */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val startMillis: Long,
    val stopMillis: Long,
    val description: String? = null
)
