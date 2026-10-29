package com.sportscc.tv.stream

import android.content.Context
import com.sportscc.core.Channel
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

    var last: Channel?
        get() = read("last")
        set(v) = prefs.edit().putString("last", v?.let { json.encodeToString(it) }).apply()

    private val parsed = HashMap<String, List<Channel>>()

    /** The channels in [id], read from the TV's copy. */
    suspend fun channels(id: String): List<Channel> = withContext(Dispatchers.IO) {
        synchronized(parsed) { parsed[id] }
            ?: M3u.parse(file(id).takeIf { it.exists() }?.readText() ?: "").also { synchronized(parsed) { parsed[id] = it } }
    }

    /** Downloads a playlist and keeps it (replacing one with the same address). */
    suspend fun addFromUrl(url: String, name: String? = null, downloaded: String? = null): Playlist {
        val (bare, headers) = M3u.splitHeaders(url)
        val text = downloaded ?: withContext(Dispatchers.IO) { HttpText.get(bare, headers) }
        val existing = _playlists.value.firstOrNull { it.url == url }
        return add(text, url, name ?: existing?.name ?: M3u.title(text) ?: nameFromUrl(bare), existing?.id)
    }

    /** Keeps a playlist sent as text (a file from the phone). */
    suspend fun addFromText(text: String): Playlist =
        add(text, null, M3u.title(text) ?: "Playlist from phone", null)

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
        val channels = M3u.parse(text)
        if (channels.isEmpty()) throw IOException(if (M3u.isHls(text)) "That's a single stream, not a channel list." else "No channels found in that playlist.")
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
