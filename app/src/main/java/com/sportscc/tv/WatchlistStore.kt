package com.sportscc.tv

import android.content.Context
import com.sportscc.core.PlayerWatch
import com.sportscc.core.WatchItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    /** Seconds between polls while a followed game is live. */
    val liveIntervalSec: Int = 20,
    /** Show popups for every stat tick, or only big plays (TDs, HRs, goals, finals, props). */
    val bigPlaysOnly: Boolean = false,
    /** Keep polling and pop alerts over other apps (needs "display over other apps"). */
    val backgroundAlerts: Boolean = true,
    /** Sort live games to the top; off keeps cards exactly where the user put them. */
    val liveFirst: Boolean = false,
    /** Home-screen card columns: 2 side by side, or 1 wide column. */
    val cardColumns: Int = 2,
)

/** Watchlist + settings, persisted as JSON in SharedPreferences. */
class WatchlistStore(context: Context) {
    private val prefs = context.getSharedPreferences("scc", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _watchlist = MutableStateFlow(load<List<WatchItem>>(KEY_WATCHLIST) ?: emptyList())
    val watchlist: StateFlow<List<WatchItem>> = _watchlist.asStateFlow()

    private val _settings = MutableStateFlow(load<AppSettings>(KEY_SETTINGS) ?: AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private inline fun <reified T> load(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private fun saveWatchlist(list: List<WatchItem>) {
        _watchlist.value = list
        prefs.edit().putString(KEY_WATCHLIST, json.encodeToString(list)).apply()
    }

    fun contains(key: String) = _watchlist.value.any { it.key == key }

    fun find(key: String): WatchItem? = _watchlist.value.firstOrNull { it.key == key }

    /** Adds the item, or replaces the existing one with the same key in place. */
    fun upsert(item: WatchItem) {
        val list = _watchlist.value
        val i = list.indexOfFirst { it.key == item.key }
        saveWatchlist(if (i >= 0) list.toMutableList().also { it[i] = item } else list + item)
    }

    /** Replaces the whole list (phone sync), dropping duplicates by key. */
    fun replaceAll(items: List<WatchItem>) = saveWatchlist(items.distinctBy { it.key })

    fun remove(key: String) = saveWatchlist(_watchlist.value.filterNot { it.key == key })

    /** Puts the list in the order of [keys]; anything not named keeps its relative order at the end. */
    fun reorder(keys: List<String>) {
        val rank = keys.withIndex().associate { (i, k) -> k to i }
        saveWatchlist(_watchlist.value.sortedBy { rank[it.key] ?: Int.MAX_VALUE })
    }

    fun moveToTop(key: String) {
        val item = find(key) ?: return
        saveWatchlist(listOf(item) + _watchlist.value.filterNot { it.key == key })
    }

    fun updatePlayer(key: String, transform: (PlayerWatch) -> PlayerWatch) {
        (find(key) as? PlayerWatch)?.let { upsert(transform(it)) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(s)).apply()
    }

    private companion object {
        const val KEY_WATCHLIST = "watchlist"
        const val KEY_SETTINGS = "settings"
    }
}
