package com.sportscc.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class PropProgress(val prop: Prop, val def: StatDef, val current: Double) {
    val fraction: Double get() = if (prop.line <= 0) 1.0 else (current / prop.line).coerceIn(0.0, 1.0)
    /** True when the bet is currently winning (over: above the line, under: at or below it). */
    val winning: Boolean get() = if (prop.side == Prop.Side.OVER) current > prop.line else current <= prop.line
}

sealed interface Card {
    val watch: WatchItem
    val game: Game?
}

data class TeamCard(override val watch: TeamWatch, override val game: Game?) : Card

data class PlayerCard(
    override val watch: PlayerWatch,
    override val game: Game?,
    val line: PlayerLine?,
    val chips: List<StatChip>,
    val props: List<PropProgress>,
    /** Standard fantasy score for the sport, when one is defined (PPR / DraftKings). */
    val fantasy: Pair<String, Double>?,
) : Card

data class Dashboard(
    /** In watchlist order; see [orderCards] for the on-screen order. */
    val cards: List<Card>,
    val anyLive: Boolean,
    val updatedAt: Long,
    val errors: List<String>,
)

data class RefreshResult(val dashboard: Dashboard, val alerts: List<Alert>)

/**
 * Polls ESPN for every league in the watchlist, fetches box scores only for games that matter,
 * and diffs against the previous poll to produce alerts. Not thread-safe: call [refresh] from one coroutine.
 */
class Tracker(private val client: EspnClient, private val clock: () -> Long = System::currentTimeMillis) {
    private var previous: Snapshot? = null
    private val finalBoxes = mutableMapOf<String, BoxScore>()

    /** Forget the baseline so the next poll doesn't alert (e.g. after the app was paused for hours). */
    fun resetBaseline() {
        previous = null
    }

    suspend fun refresh(watchlist: List<WatchItem>): RefreshResult = coroutineScope {
        val errors = mutableListOf<String>()
        val prev = previous

        val leagues = watchlist.map { it.league }.distinct()
        val scoreboards = leagues.map { league ->
            async {
                league to runCatching { client.scoreboard(league) }
            }
        }.awaitAll()

        val games = mutableMapOf<String, Game>()
        for ((league, result) in scoreboards) {
            result.onSuccess { list -> list.forEach { games[it.id] = it } }
                .onFailure { e ->
                    errors += "${league.label}: ${e.message ?: e::class.simpleName}"
                    // Keep last known games so cards don't blank out on one failed poll.
                    prev?.games?.values?.filter { it.league == league }?.forEach { games[it.id] = it }
                }
        }

        val boxGames = watchlist.filterIsInstance<PlayerWatch>()
            .mapNotNull { gameForTeam(games.values, it.league, it.teamId) }
            .filter { it.state != GameState.PRE }
            .distinctBy { it.id }

        val boxes = boxGames.map { g ->
            async {
                val cached = finalBoxes[g.id]
                if (cached != null && g.state == GameState.POST) return@async g.id to Result.success(cached)
                g.id to runCatching { client.summary(g.league, g.id) }
            }
        }.awaitAll().mapNotNull { (id, r) ->
            r.onFailure { e -> errors += "Box score ${games[id]?.scoreLine ?: id}: ${e.message}" }
            val box = r.getOrNull() ?: prev?.boxes?.get(id) ?: return@mapNotNull null
            if (games[id]?.state == GameState.POST) finalBoxes[id] = box
            id to box
        }.toMap()

        val snapshot = Snapshot(games, boxes)
        val now = clock()
        val alerts = if (prev == null) emptyList() else AlertEngine.diff(prev, snapshot, watchlist, now)
        previous = snapshot

        RefreshResult(buildDashboard(watchlist, snapshot, now, errors), alerts)
    }

    companion object {
        fun buildDashboard(watchlist: List<WatchItem>, snap: Snapshot, now: Long, errors: List<String>): Dashboard {
            val cards = watchlist.map { w ->
                when (w) {
                    is TeamWatch -> TeamCard(w, gameForTeam(snap.games.values, w.league, w.teamId))
                    is PlayerWatch -> {
                        val g = gameForTeam(snap.games.values, w.league, w.teamId)
                        val line = g?.let { snap.boxes[it.id]?.players?.get(w.athleteId) }
                        val props = w.props.mapNotNull { p ->
                            val def = StatCatalog.find(w.league, p.statId) ?: return@mapNotNull null
                            PropProgress(p, def, def.evaluate(line) ?: 0.0)
                        }
                        val fantasyDef = StatCatalog.fantasy(w.league)
                        // Fantasy players show 0.0 before kickoff; for others the chip only appears with stats.
                        val points = fantasyDef?.evaluate(line) ?: if (w.fantasy && fantasyDef != null) 0.0 else null
                        val fantasy = points?.let { fantasyDef!!.short to it }
                        PlayerCard(w, g, line, StatFormat.chips(w.league.kind, line), props, fantasy)
                    }
                }
            }
            return Dashboard(cards, cards.any { it.game?.state == GameState.IN }, now, errors)
        }
    }
}

/**
 * On-screen order. With [liveFirst], live games come first, then upcoming, then finished / no game,
 * keeping the user's order within each group; otherwise the user's order as-is.
 */
fun orderCards(cards: List<Card>, liveFirst: Boolean): List<Card> =
    if (!liveFirst) cards else cards.sortedBy {
        when (it.game?.state) {
            GameState.IN -> 0
            GameState.PRE -> 1
            GameState.POST -> 2
            null -> 3
        }
    }

/** Moves the item at [from] by [delta] places (clamped), e.g. -1 = left, +columns = down a row. */
fun <T> List<T>.moved(from: Int, delta: Int): List<T> {
    if (from !in indices) return this
    val to = (from + delta).coerceIn(0, lastIndex)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
