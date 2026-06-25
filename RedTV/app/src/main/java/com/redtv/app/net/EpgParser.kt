package com.redtv.app.net

import android.util.Xml
import com.redtv.app.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses an XMLTV EPG document into programmes grouped by channel id (tvg-id).
 * Times look like "20240131201500 +0000".
 */
object EpgParser {

    fun parse(xml: String): Map<String, List<EpgProgram>> {
        val result = HashMap<String, MutableList<EpgProgram>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        var channelId: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var current: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            channelId = parser.getAttributeValue(null, "channel")
                            start = parseTime(parser.getAttributeValue(null, "start"))
                            stop = parseTime(parser.getAttributeValue(null, "stop"))
                            title = null; desc = null
                        }
                        "title" -> current = "title"
                        "desc" -> current = "desc"
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (!text.isNullOrBlank()) {
                        when (current) {
                            "title" -> title = text.trim()
                            "desc" -> desc = text.trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title", "desc" -> current = null
                        "programme" -> {
                            val cid = channelId
                            if (cid != null && start > 0) {
                                result.getOrPut(cid) { ArrayList() }
                                    .add(EpgProgram(cid, title ?: "", start, stop, desc))
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        result.values.forEach { it.sortBy { p -> p.startMillis } }
        return result
    }

    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val fmtNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val s = raw.trim()
        return runCatching {
            if (s.length > 14 && (s.contains('+') || s.contains('-'))) fmt.parse(s)!!.time
            else fmtNoTz.parse(s.take(14))!!.time
        }.getOrDefault(0L)
    }
}
