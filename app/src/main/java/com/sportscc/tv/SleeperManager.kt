package com.sportscc.tv

import android.util.Log
import com.sportscc.core.Alert
import com.sportscc.core.HttpText
import com.sportscc.core.League
import com.sportscc.core.SleeperLineup
import com.sportscc.core.SleeperLink
import com.sportscc.core.SleeperPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps your fantasy players matched to your Sleeper lineup: checks the league every few hours
 * (and right after the link changes), then adds players who moved into the lineup and drops the
 * ones who left, announcing the change in the feed.
 */
class SleeperManager(
    private val store: WatchlistStore,
    private val notify: (Alert) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var map: Map<String, SleeperPlayer> = emptyMap()
    private var mapLoadedAt = 0L
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Check again now, e.g. after the phone sent a new list (which may have left the Sleeper players out). */
    fun refreshSoon() {
        kick.tryEmit(Unit)
    }

    fun start() {
        scope.launch {
            store.sleeper.collectLatest { link ->
                link ?: return@collectLatest
                while (true) {
                    runCatching { refresh(link) }.onFailure { Log.w(TAG, "Sleeper refresh failed", it) }
                    withTimeoutOrNull(EVERY_MS) { kick.first() }
                }
            }
        }
    }

    private suspend fun refresh(link: SleeperLink) = withContext(Dispatchers.IO) {
        // The ESPN lookup only changes with trades and signings: reload it once a day.
        if (map.isEmpty() || System.currentTimeMillis() - mapLoadedAt > 24 * 60 * 60_000L) {
            map = SleeperLineup.parseMap(HttpText.get(BuildConfig.REMOTE_URL + "data/NFL/sleeper-players.json"))
            mapLoadedAt = System.currentTimeMillis()
        }
        val ids = SleeperLineup.playerIds(HttpText.get(SleeperLineup.rostersUrl(link.leagueId)), link.userId, link.startersOnly)
            ?: return@withContext
        val lineup = ids.mapNotNull { map[it] }.map { SleeperLineup.toWatch(it) }
        if (lineup.isEmpty()) return@withContext // don't wipe the list over a bad response
        val m = SleeperLineup.merge(store.watchlist.value, lineup)
        if (!m.changed) return@withContext
        store.replaceAll(m.items)
        val now = System.currentTimeMillis()
        notify(
            Alert(
                id = "sleeper:$now", time = now, league = League.NFL, watchKey = "sleeper",
                title = "Lineup updated from Sleeper",
                detail = listOfNotNull(
                    m.added.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Added ") { it.name },
                    m.removed.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Removed ") { it.name },
                ).joinToString(" · "),
                big = false,
            ),
        )
    }

    private companion object {
        const val TAG = "Sleeper"
        const val EVERY_MS = 3 * 60 * 60_000L
    }
}
