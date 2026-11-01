package com.sportscc.tv.stream

import android.content.Context
import com.sportscc.core.Channel
import com.sportscc.core.HttpStatusException
import com.sportscc.core.HttpText
import com.sportscc.core.M3u
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A stream assigned to one game ([gameId] is ESPN's event id), from a playlist channel or a single link.
 * [label] is the matchup, e.g. "BUF @ MIA", for when the game is no longer on the scoreboard.
 */
@Serializable
data class GameFeed(
    val gameId: String,
    val league: com.sportscc.core.League,
    val label: String,
    val channel: Channel,
    val hls: Boolean = false,
    val savedAt: Long = 0,
)

/** A channel list. [url] is null when it was sent as a file, so it can't be refreshed. */
@Serializable
data class Playlist(val id: String, val name: String, val url: String? = null, val count: Int = 0, val updatedAt: Long = 0)

/**
 * Playlists, favorite channels and the last channel watched, kept on the TV.
 * Each playlist's text is cached in a file and parsed when it's first needed.
 */
class StreamStore(context: Context) {
    private val prefs = context.getSharedPreferences("scc_streams", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _playlists = MutableStateFlow(read<List<Playlist>>("playlists") ?: emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favorites = MutableStateFlow(read<List<Channel>>("favorites") ?: emptyList())
    val favorites: StateFlow<List<Channel>> = _favorites.asStateFlow()

    // Assignments are for one game, so they're dropped two days after they were made.
    private val _feeds = MutableStateFlow(
        (read<List<GameFeed>>("feeds") ?: emptyList()).filter { System.currentTimeMillis() - it.savedAt < 2 * 24 * 60 * 60_000L },
    )
    val feeds: StateFlow<List<GameFeed>> = _feeds.asStateFlow()

    fun feedFor(gameId: String): GameFeed? = _feeds.value.firstOrNull { it.gameId == gameId }

    fun assign(feed: GameFeed) = putFeeds(listOf(feed.copy(savedAt = System.currentTimeMillis())) + _feeds.value.filterNot { it.gameId == feed.gameId })

    fun unassign(gameId: String) = putFeeds(_feeds.value.filterNot { it.gameId == gameId })

    private fun putFeeds(list: List<GameFeed>) {
        _feeds.value = list
        prefs.edit().putString("feeds", json.encodeToString(list)).apply()
    }

    var last: Channel?
        get() = read("last")
        set(v) = prefs.edit().putString("last", v?.let { json.encodeToString(it) }).apply()

    private val parsed = HashMap<String, List<Channel>>()

    /** The channels in [id], read from the TV's copy. */
    suspend fun channels(id: String): List<Channel> = withContext(Dispatchers.IO) {
        synchronized(parsed) { parsed[id] }
            ?: forPlayer(M3u.parse(file(id).takeIf { it.exists() }?.readText() ?: "")).also { synchronized(parsed) { parsed[id] = it } }
    }

    /**
     * Downloads a playlist and keeps it (replacing one with the same address). It's streamed straight
     * to the TV's storage, keeping only live channels: IPTV lists with movies and series run to tens of MB.
     */
    suspend fun addFromUrl(url: String, name: String? = null): Playlist = withContext(Dispatchers.IO) {
        val (bare, given) = M3u.splitHeaders(url)
        val headers = if (given.containsKey("User-Agent")) given else given + ("User-Agent" to HttpText.PLAYER_AGENT)
        val existing = _playlists.value.firstOrNull { it.url == url }
        val id = existing?.id ?: "p${System.currentTimeMillis()}"
        val part = File(dir, "$id.part")
        val start = StringBuilder()
        val (kept, dropped) = try {
            val conn = HttpText.open(bare, headers, 60_000)
            try {
                HttpText.unzipping(conn.inputStream).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    part.bufferedWriter().use { out ->
                        M3u.copyLive(lines.onEach { if (start.length < 300) start.append(it.take(300)).append(' ') }, out)
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: HttpStatusException) {
            part.delete()
            throw IOException(
                when (e.code) {
                    401, 403 -> "the provider refused it (HTTP ${e.code}). Check the username and password in the link, or the subscription may have ended."
                    404 -> "the provider says it doesn't exist (HTTP 404). Check the link."
                    in 500..599 -> "the provider's server had a problem (HTTP ${e.code}). Try again in a few minutes."
                    else -> "the provider answered HTTP ${e.code}."
                },
            )
        } catch (e: java.net.SocketTimeoutException) {
            part.delete()
            throw IOException("the provider took too long to answer. Try again, or check the link.")
        } catch (e: java.net.UnknownHostException) {
            part.delete()
            throw IOException("the TV couldn't find the provider's server (${e.message}). Check the link.")
        }
        if (kept == 0) {
            part.delete()
            val said = start.toString().trim()
            throw IOException(
                when {
                    dropped > 0 -> "it only has movies and series ($dropped), no live channels."
                    said.isEmpty() -> "the provider sent back an empty playlist. The trial may not have started yet, or the account has no channels."
                    M3u.isHls(said) -> "that's a single stream, not a channel list."
                    else -> "no channels found. The provider sent: \"${said.take(140)}\""
                },
            )
        }
        val target = file(id)
        target.delete()
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
        val channels = forPlayer(M3u.parse(target.readText()))
        synchronized(parsed) { parsed[id] = channels }
        val p = Playlist(id, name ?: existing?.name ?: nameFromUrl(bare), url, channels.size, System.currentTimeMillis())
        putPlaylists(listOf(p) + _playlists.value.filterNot { it.id == id })
        p
    }

    /** Keeps a playlist sent as text (a file from the phone). */
    suspend fun addFromText(text: String): Playlist = withContext(Dispatchers.IO) {
        val live = java.io.StringWriter()
        val (kept, dropped) = M3u.copyLive(text.lineSequence(), live)
        if (kept == 0) throw IOException(if (dropped > 0) "it only has movies and series, no live channels." else if (M3u.isHls(text)) "that's a single stream, not a channel list." else "no channels found in that playlist.")
        add(live.toString(), null, M3u.title(text) ?: "Playlist from phone", null)
    }

    /** IPTV servers often refuse players they don't recognize, so channels without their own User-Agent use a media player's. */
    private fun forPlayer(list: List<Channel>): List<Channel> =
        list.map { c -> if (c.headers.containsKey("User-Agent")) c else c.copy(headers = c.headers + ("User-Agent" to HttpText.PLAYER_AGENT)) }

    suspend fun refresh(p: Playlist): Playlist = addFromUrl(p.url ?: throw IOException("This playlist was sent as a file, so there's nothing to refresh."), p.name)

    fun remove(id: String) {
        file(id).delete()
        synchronized(parsed) { parsed.remove(id) }
        putPlaylists(_playlists.value.filterNot { it.id == id })
    }

    fun isFavorite(c: Channel) = _favorites.value.any { it.url == c.url }

    fun toggleFavorite(c: Channel) {
        _favorites.value = if (isFavorite(c)) _favorites.value.filterNot { it.url == c.url } else _favorites.value + c
        prefs.edit().putString("favorites", json.encodeToString(_favorites.value)).apply()
    }

    private suspend fun add(text: String, url: String?, name: String, replaceId: String?): Playlist = withContext(Dispatchers.IO) {
        val channels = forPlayer(M3u.parse(text))
        if (channels.isEmpty()) throw IOException(if (M3u.isHls(text)) "that's a single stream, not a channel list." else "no channels found in that playlist.")
        val id = replaceId ?: "p${System.currentTimeMillis()}"
        file(id).writeText(text)
        synchronized(parsed) { parsed[id] = channels }
        val p = Playlist(id, name, url, channels.size, System.currentTimeMillis())
        putPlaylists(listOf(p) + _playlists.value.filterNot { it.id == id })
        p
    }

    private fun putPlaylists(list: List<Playlist>) {
        _playlists.value = list
        prefs.edit().putString("playlists", json.encodeToString(list)).apply()
    }

    private fun file(id: String) = File(dir, "$id.m3u")

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private fun nameFromUrl(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.")
        val file = url.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
        return if (file.isBlank() || file == host || file.equals("get", true)) host else "$file ($host)"
    }
}
