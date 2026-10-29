package com.sportscc.core

import kotlinx.serialization.Serializable

/** One channel from an .m3u playlist, or a single stream link. */
@Serializable
data class Channel(
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null,
    /** Extra HTTP headers some streams need, e.g. User-Agent or Referer. */
    val headers: Map<String, String> = emptyMap(),
)

/** What a link or a piece of text sent to the Watch screen is. */
enum class LinkKind {
    /** A web page or search words: open in the browser. */
    WEB,

    /** A video stream (.m3u8 HLS, .mpd DASH, .mp4, .ts): play in the built-in player. */
    STREAM,

    /** An .m3u channel list, as a link or as the playlist text itself. */
    PLAYLIST,

    /** An .m3u8/.m3u link that could be either: look at what it contains. */
    STREAM_OR_PLAYLIST,
}

/** Reads .m3u / .m3u8 playlists (the "#EXTM3U" channel-list format IPTV services use). */
object M3u {
    /** Explicit prefixes, e.g. from the phone's buttons: "play:<url>", "m3u:<url or text>". */
    const val PLAY_PREFIX = "play:"
    const val PLAYLIST_PREFIX = "m3u:"

    fun classify(input: String): LinkKind {
        val s = input.trim()
        if (s.startsWith(PLAY_PREFIX)) return LinkKind.STREAM
        if (s.startsWith(PLAYLIST_PREFIX)) return LinkKind.PLAYLIST
        if (looksLikePlaylistText(s)) return if (isHls(s)) LinkKind.STREAM_OR_PLAYLIST else LinkKind.PLAYLIST
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true) && !s.startsWith("rtmp://", true) && !s.startsWith("rtsp://", true)) {
            return LinkKind.WEB
        }
        if (s.startsWith("rtmp", true) || s.startsWith("rtsp", true)) return LinkKind.STREAM
        val bare = splitHeaders(s).first
        val path = bare.substringBefore('#').substringBefore('?').lowercase()
        val query = bare.substringAfter('?', "").lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> LinkKind.STREAM_OR_PLAYLIST
            STREAM_EXTENSIONS.any { path.endsWith(it) } -> LinkKind.STREAM
            // Xtream-style playlist links: .../get.php?username=..&password=..&type=m3u_plus
            path.endsWith("/get.php") && query.contains("type=m3u") -> LinkKind.PLAYLIST
            query.contains("output=m3u8") || query.contains("output=hls") -> LinkKind.STREAM
            else -> LinkKind.WEB
        }
    }

    /** Text (not a link) that is a playlist: what you get if you paste a file's contents. */
    fun looksLikePlaylistText(s: String): Boolean {
        val t = s.trimStart('﻿', ' ', '\n', '\r', '\t')
        return t.startsWith("#EXTM3U") || t.startsWith("#EXTINF")
    }

    /**
     * An HLS stream's own playlist (segments or quality levels), as opposed to a list of channels.
     * Only HLS uses the "#EXT-X-" tags.
     */
    fun isHls(text: String): Boolean = text.lineSequence().take(400).any { it.trimStart().startsWith("#EXT-X-") }

    /** Removes the "play:" / "m3u:" prefix if there is one. */
    fun stripPrefix(s: String): String = s.trim().removePrefix(PLAY_PREFIX).removePrefix(PLAYLIST_PREFIX).trim()

    /**
     * Kodi-style headers after a pipe: "https://x/live.m3u8|User-Agent=VLC&Referer=https://x/".
     */
    fun splitHeaders(url: String): Pair<String, Map<String, String>> {
        val i = url.indexOf('|')
        if (i < 0) return url.trim() to emptyMap()
        val headers = url.substring(i + 1).split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "").trim()
            val v = part.substringAfter('=', "").trim()
            if (k.isEmpty() || v.isEmpty()) null else headerName(k) to decode(v)
        }.toMap()
        return url.substring(0, i).trim() to headers
    }

    /** A single stream link as a channel to play. */
    fun single(input: String, name: String? = null): Channel {
        val (url, headers) = splitHeaders(stripPrefix(input))
        val label = name ?: url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }
        return Channel(name = label, url = url, headers = headers)
    }

    /** A channel as one link, with any headers after a pipe (the reverse of [single]). */
    fun link(c: Channel): String =
        if (c.headers.isEmpty()) c.url
        else c.url + "|" + c.headers.entries.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }

    /** The playlist's own title, from a "#PLAYLIST:" line, if it has one. */
    fun title(text: String): String? = text.lineSequence().take(50)
        .firstOrNull { it.trim().startsWith("#PLAYLIST:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** All channels in an #EXTM3U playlist, in order. Lines it doesn't understand are skipped. */
    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var group: String? = null
        val opts = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('﻿')
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val (a, title) = parseExtInf(line)
                    attrs = a
                    name = title
                    group = null
                    opts.clear()
                    a["user-agent"]?.let { opts["User-Agent"] = it }
                    (a["referrer"] ?: a["referer"] ?: a["http-referrer"])?.let { opts["Referer"] = it }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val k = opt.substringBefore('=').trim().lowercase()
                    val v = opt.substringAfter('=', "").trim()
                    if (v.isNotEmpty()) when (k) {
                        "http-user-agent" -> opts["User-Agent"] = v
                        "http-referrer", "http-referer" -> opts["Referer"] = v
                        "http-origin" -> opts["Origin"] = v
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val (url, pipeHeaders) = splitHeaders(line)
                    if (url.contains("://")) {
                        val title = name?.takeIf { it.isNotBlank() }
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: url.substringBefore('?').trimEnd('/').substringAfterLast('/')
                        out += Channel(
                            name = title,
                            url = url,
                            group = (attrs["group-title"] ?: group)?.trim()?.takeIf { it.isNotEmpty() },
                            logo = attrs["tvg-logo"]?.trim()?.takeIf { it.startsWith("http") },
                            headers = opts + pipeHeaders,
                        )
                    }
                    name = null
                    attrs = emptyMap()
                    group = null
                    opts.clear()
                }
            }
        }
        return out
    }

    /** `#EXTINF:-1 tvg-id="x" tvg-logo="y" group-title="Sports",ESPN HD` -> (attributes, "ESPN HD"). */
    private fun parseExtInf(line: String): Pair<Map<String, String>, String> {
        val body = line.substringAfter(':', "")
        val attrs = LinkedHashMap<String, String>()
        var i = 0
        var titleStart = -1
        var inQuote = false
        // The title starts after the first comma that isn't inside an attribute's quotes.
        while (i < body.length) {
            val c = body[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) {
                titleStart = i + 1; break
            }
            i++
        }
        val head = if (titleStart >= 0) body.substring(0, titleStart - 1) else body
        ATTR.findAll(head).forEach { m -> attrs[m.groupValues[1].lowercase()] = m.groupValues[2] }
        val title = if (titleStart >= 0) body.substring(titleStart).trim() else ""
        return attrs to title
    }

    private fun headerName(k: String): String = when (k.lowercase()) {
        "user-agent", "useragent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> k
    }

    private fun decode(v: String): String = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)

    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val STREAM_EXTENSIONS = listOf(".mpd", ".mp4", ".m4v", ".mkv", ".webm", ".ts", ".mov", ".mp3", ".aac")
}
