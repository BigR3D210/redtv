package com.redtv.app.net

import com.redtv.app.model.Channel

/**
 * Parses an M3U / M3U8 playlist into Channel objects.
 * Supports the common extended attributes: tvg-id, tvg-logo, tvg-name, group-title.
 */
object M3uParser {

    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(content: String): List<Channel> {
        val lines = content.lineSequence().map { it.trim() }.iterator()
        val out = ArrayList<Channel>()
        var pending: Pending? = null
        var idx = 0

        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                }
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    val grp = line.substringAfter(":", "").trim()
                    if (grp.isNotEmpty() && pending != null) pending = pending.copy(group = grp)
                }
                line.startsWith("#") -> { /* ignore other tags */ }
                else -> {
                    // This is a stream URL line
                    val p = pending
                    if (p != null) {
                        out.add(
                            Channel(
                                id = (p.tvgId?.takeIf { it.isNotBlank() } ?: "ch_$idx"),
                                name = p.name.ifBlank { "Channel ${idx + 1}" },
                                streamUrl = line,
                                logoUrl = p.logo,
                                category = p.group.ifBlank { "Uncategorized" },
                                epgChannelId = p.tvgId
                            )
                        )
                        idx++
                        pending = null
                    }
                }
            }
        }
        return out
    }

    private data class Pending(
        val name: String,
        val tvgId: String?,
        val logo: String?,
        val group: String
    )

    private fun parseExtInf(line: String): Pending {
        val attrs = HashMap<String, String>()
        attrRegex.findAll(line).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
        val name = line.substringAfterLast(",", "").trim()
        return Pending(
            name = if (name.isNotBlank()) name else (attrs["tvg-name"] ?: ""),
            tvgId = attrs["tvg-id"],
            logo = attrs["tvg-logo"],
            group = attrs["group-title"] ?: ""
        )
    }
}
