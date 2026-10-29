package com.sportscc.tv.stream

import com.sportscc.core.Channel
import com.sportscc.core.HttpText
import com.sportscc.core.LinkKind
import com.sportscc.core.M3u
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What to do with something typed, saved, or sent from the phone to the Watch screen. */
sealed interface Opened {
    data class Web(val input: String) : Opened
    data class Play(val channel: Channel, val hls: Boolean = false) : Opened
    data class Channels(val playlist: Playlist) : Opened
}

object LinkResolver {
    /**
     * Web pages and searches go to the browser, video links to the player, and channel lists are
     * kept as playlists. An .m3u8 or .m3u link can be either a stream or a channel list, so it's
     * downloaded and looked at first. Throws with a readable message if a playlist can't be loaded.
     */
    suspend fun resolve(input: String, streams: StreamStore): Opened {
        val s = input.trim()
        val body = M3u.stripPrefix(s)
        return when (M3u.classify(s)) {
            LinkKind.WEB -> Opened.Web(s)
            LinkKind.STREAM -> Opened.Play(M3u.single(body))
            LinkKind.PLAYLIST ->
                Opened.Channels(if (M3u.looksLikePlaylistText(body)) streams.addFromText(body) else streams.addFromUrl(body))
            LinkKind.STREAM_OR_PLAYLIST -> {
                if (M3u.looksLikePlaylistText(body)) {
                    if (M3u.isHls(body)) throw java.io.IOException("That's the inside of a single stream. Send the stream's link instead.")
                    return Opened.Channels(streams.addFromText(body))
                }
                val (url, headers) = M3u.splitHeaders(body)
                // A stream's own playlist is small; a channel list can be big.
                val text = runCatching { withContext(Dispatchers.IO) { HttpText.get(url, headers, maxBytes = 20_000_000, timeoutMs = 15_000) } }.getOrNull()
                when {
                    text == null -> Opened.Play(M3u.single(body)) // let the player try and explain any error
                    M3u.isHls(text) -> Opened.Play(M3u.single(body), hls = true)
                    M3u.parse(text).isNotEmpty() -> Opened.Channels(streams.addFromUrl(body, downloaded = text))
                    else -> Opened.Play(M3u.single(body))
                }
            }
        }
    }
}
