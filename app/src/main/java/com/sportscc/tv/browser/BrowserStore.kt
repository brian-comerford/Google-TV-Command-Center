package com.sportscc.tv.browser

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedSite(val title: String, val url: String)

/** The browser's saved sites and last page, kept on the TV. */
class BrowserStore(context: Context) {
    private val prefs = context.getSharedPreferences("scc_browser", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _saved = MutableStateFlow(
        prefs.getString("saved", null)?.let { runCatching { json.decodeFromString<List<SavedSite>>(it) }.getOrNull() } ?: emptyList(),
    )
    val saved: StateFlow<List<SavedSite>> = _saved.asStateFlow()

    var lastUrl: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    fun save(site: SavedSite) = put(listOf(site) + _saved.value.filterNot { it.url == site.url })
    fun remove(url: String) = put(_saved.value.filterNot { it.url == url })

    private fun put(list: List<SavedSite>) {
        _saved.value = list.take(30)
        prefs.edit().putString("saved", json.encodeToString(_saved.value)).apply()
    }
}
