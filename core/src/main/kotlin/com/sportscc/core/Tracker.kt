package com.sportscc.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class PropProgress(
    val prop: Prop,
    val def: StatDef,
    val current: Double,
    /** "On pace for" during the game (see [Pace]); null before, after, or too early to say. */
    val projected: Double? = null,
) {
    val fraction: Double get() = if (prop.line <= 0) 1.0 else (current / prop.line).coerceIn(0.0, 1.0)
    /** Whether the pace says the bet ends up winning; null when there's no pace to go on. */
    val paceWinning: Boolean? get() = projected?.let { if (prop.side == Prop.Side.OVER) it > prop.line else it <= prop.line }
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
    /** Where the fantasy score is headed at this rate (see [Pace]). */
    val fantasyPace: Double? = null,
) : Card

data class Dashboard(
    /** In watchlist order; see [orderCards] for the on-screen order. */
    val cards: List<Card>,
    val anyLive: Boolean,
    val updatedAt: Long,
    val errors: List<String>,
)

data class ParlayCard(override val watch: ParlayWatch, override val game: Game?, val state: ParlayState) : Card

data class BetCard(override val watch: BetWatch, override val game: Game?, val state: LegState) : Card {
    val description: String get() = BetEngine.describe(state)
}

/** [settlements]: parlay legs decided this poll, for the app to save onto the stored parlay. */
data class RefreshResult(val dashboard: Dashboard, val alerts: List<Alert>, val settlements: List<Settlement> = emptyList())

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

        val leagues = watchlist.flatMap { w -> if (w is ParlayWatch) w.legs.map { it.league } else listOf(w.league) }.distinct()
        // (BetWatch.league is its leg's league, so the line above covers single bets.)
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

        // Box scores are only needed for games with a followed player or a player-prop parlay leg.
        val playerTeams = watchlist.flatMap { w ->
            when (w) {
                is PlayerWatch -> listOf(w.league to w.teamId)
                is ParlayWatch -> w.legs.filter { it is PropLeg && it.result == null }.map { it.league to it.teamId }
                is BetWatch -> if (w.leg is PropLeg && w.leg.result == null) listOf(w.league to w.leg.teamId) else emptyList()
                else -> emptyList()
            }
        }
        val boxGames = playerTeams
            .mapNotNull { (league, teamId) -> gameForTeam(games.values, league, teamId) }
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

        val settlements = watchlist.filterIsInstance<ParlayWatch>()
            .flatMap { ParlayEngine.settlements(ParlayEngine.evaluate(it, snapshot)) } +
            watchlist.filterIsInstance<BetWatch>().mapNotNull { BetEngine.settlement(it, snapshot) }
        RefreshResult(buildDashboard(watchlist, snapshot, now, errors), alerts, settlements)
    }

    companion object {
        fun buildDashboard(watchlist: List<WatchItem>, snap: Snapshot, now: Long, errors: List<String>): Dashboard {
            val cards = watchlist.map { w ->
                when (w) {
                    is TeamWatch -> TeamCard(w, gameForTeam(snap.games.values, w.league, w.teamId))
                    is BetWatch -> {
                        val state = BetEngine.evaluate(w, snap)
                        BetCard(w, state.game, state)
                    }
                    is ParlayWatch -> {
                        val state = ParlayEngine.evaluate(w, snap)
                        // The card's "game" decides live highlighting and live-first sorting.
                        val g = state.legs.mapNotNull { it.game }.let { gs ->
                            gs.firstOrNull { it.state == GameState.IN } ?: gs.firstOrNull { it.state == GameState.PRE } ?: gs.firstOrNull()
                        }
                        ParlayCard(w, g, state)
                    }
                    is PlayerWatch -> {
                        val g = gameForTeam(snap.games.values, w.league, w.teamId)
                        val line = g?.let { snap.boxes[it.id]?.players?.get(w.athleteId) }
                        val props = AlertEngine.propProgress(w, line, g)
                        val fantasyDef = StatCatalog.fantasy(w.league)
                        // Fantasy players show 0.0 before kickoff; for others the chip only appears with stats.
                        val points = fantasyDef?.evaluate(line) ?: if (w.fantasy && fantasyDef != null) 0.0 else null
                        val fantasy = points?.let { fantasyDef!!.short to it }
                        PlayerCard(w, g, line, StatFormat.chips(w.league.kind, line), props, fantasy, points?.let { Pace.project(it, g) })
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
