package com.sportscc.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

// ---------- What the user enters (persisted, synced with the phone site) ----------

enum class LegStatus { PENDING, LIVE_WINNING, LIVE_LOSING, WON, LOST, PUSH }

val LegStatus.decided: Boolean get() = this == LegStatus.WON || this == LegStatus.LOST || this == LegStatus.PUSH

@Serializable
sealed interface Leg {
    val league: League
    val teamId: String
    val teamAbbreviation: String

    /** Set by the TV once the leg is decided, so later scoreboards (next day, next week) can't undo it. */
    val result: LegStatus?
    val final: Double?
}

@Serializable
@SerialName("prop")
data class PropLeg(
    override val league: League,
    val athleteId: String,
    val name: String,
    override val teamId: String,
    override val teamAbbreviation: String,
    val prop: Prop,
    val headshot: String? = null,
    override val result: LegStatus? = null,
    override val final: Double? = null,
) : Leg

@Serializable
@SerialName("moneyline")
data class MoneylineLeg(
    override val league: League,
    override val teamId: String,
    override val teamAbbreviation: String,
    override val result: LegStatus? = null,
    override val final: Double? = null,
) : Leg

/** [points] from the team's side: -3.5 means they must win by 4+, +3.5 means lose by 3 or fewer. */
@Serializable
@SerialName("spread")
data class SpreadLeg(
    override val league: League,
    override val teamId: String,
    override val teamAbbreviation: String,
    val points: Double,
    override val result: LegStatus? = null,
    override val final: Double? = null,
) : Leg

/** Combined score of [teamId]'s game over/under [line]. */
@Serializable
@SerialName("total")
data class TotalLeg(
    override val league: League,
    override val teamId: String,
    override val teamAbbreviation: String,
    val line: Double,
    val side: Prop.Side = Prop.Side.OVER,
    override val result: LegStatus? = null,
    override val final: Double? = null,
) : Leg

@Serializable
@SerialName("parlay")
data class ParlayWatch(
    val id: String,
    val legs: List<Leg>,
    val name: String = "",
    /** American odds for the whole parlay, e.g. 1050 for +1050. */
    val odds: Int? = null,
    val stake: Double? = null,
    /** When it was built; games that started well before this belong to an earlier slate and are ignored. */
    val createdAt: Long = 0,
) : WatchItem {
    override val league: League get() = legs.firstOrNull()?.league ?: League.NFL
    override val key: String get() = "parlay:$id"
    override val displayName: String get() = name.ifBlank { "${legs.size}-leg parlay" }

    /** Profit if every leg wins (before any pushes shorten the parlay). */
    val toWin: Double?
        get() {
            val o = odds ?: return null
            val s = stake ?: return null
            return s * (if (o > 0) o / 100.0 else 100.0 / abs(o))
        }
}

// ---------- Live evaluation ----------

data class LegState(
    val leg: Leg,
    val game: Game?,
    val status: LegStatus,
    val label: String,
    /** e.g. "262", "BUF 24–17", "Up 7", "41 pts". */
    val now: String?,
    /** 0..1 progress toward the line, for prop and total legs. */
    val fraction: Double?,
    val value: Double?,
)

enum class ParlayStatus { PENDING, LIVE, WON, LOST, PUSH }

data class ParlayState(val watch: ParlayWatch, val legs: List<LegState>) {
    val won get() = legs.count { it.status == LegStatus.WON }
    val lost get() = legs.count { it.status == LegStatus.LOST }
    val open get() = legs.count { !it.status.decided }
    val status: ParlayStatus
        get() = when {
            lost > 0 -> ParlayStatus.LOST
            open == 0 && won == 0 -> ParlayStatus.PUSH
            open == 0 -> ParlayStatus.WON
            legs.any { it.status == LegStatus.LIVE_WINNING || it.status == LegStatus.LIVE_LOSING || it.status.decided } -> ParlayStatus.LIVE
            else -> ParlayStatus.PENDING
        }
    val summary: String
        get() {
            val active = legs.size - legs.count { it.status == LegStatus.PUSH }
            return when (status) {
                ParlayStatus.WON -> "All $active legs hit"
                ParlayStatus.LOST -> "Busted · $won of ${legs.size} hit"
                ParlayStatus.PUSH -> "Every leg pushed"
                else -> "$won of $active hit" + if (open > 0) " · $open to go" else ""
            }
        }
}

/** A leg decided this poll: [watchKey] is the parlay's or single bet's key ([legIndex] is 0 for a bet). */
data class Settlement(val watchKey: String, val legIndex: Int, val status: LegStatus, val final: Double?)

object ParlayEngine {

    fun evaluate(p: ParlayWatch, snap: Snapshot): ParlayState =
        ParlayState(p, p.legs.map { evaluateLeg(it, p.createdAt, snap) })

    /** Legs that just became decided and should be written back to the stored parlay. */
    fun settlements(state: ParlayState): List<Settlement> = state.legs.mapIndexedNotNull { i, s ->
        if (s.leg.result == null && s.status.decided) Settlement(state.watch.key, i, s.status, s.value) else null
    }

    fun applySettlements(p: ParlayWatch, s: List<Settlement>): ParlayWatch {
        val mine = s.filter { it.watchKey == p.key }.associateBy { it.legIndex }
        if (mine.isEmpty()) return p
        return p.copy(legs = p.legs.mapIndexed { i, leg -> mine[i]?.let { settle(leg, it) } ?: leg })
    }

    fun settle(leg: Leg, st: Settlement): Leg = when (leg) {
        is PropLeg -> leg.copy(result = st.status, final = st.final)
        is MoneylineLeg -> leg.copy(result = st.status, final = st.final)
        is SpreadLeg -> leg.copy(result = st.status, final = st.final)
        is TotalLeg -> leg.copy(result = st.status, final = st.final)
    }

    fun label(leg: Leg, game: Game? = null): String = when (leg) {
        is PropLeg -> {
            val def = StatCatalog.find(leg.league, leg.prop.statId)
            "${leg.name} ${if (leg.prop.side == Prop.Side.OVER) "O" else "U"} ${StatFormat.number(leg.prop.line)} ${def?.short ?: leg.prop.statId}"
        }
        is MoneylineLeg -> "${leg.teamAbbreviation} to win"
        is SpreadLeg -> "${leg.teamAbbreviation} ${if (leg.points > 0) "+" else ""}${StatFormat.number(leg.points)}"
        is TotalLeg -> {
            val matchup = game?.let { "${it.away.abbreviation} @ ${it.home.abbreviation}" } ?: "${leg.teamAbbreviation} game"
            "$matchup ${if (leg.side == Prop.Side.OVER) "O" else "U"} ${StatFormat.number(leg.line)}"
        }
    }

    fun evaluateLeg(leg: Leg, createdAt: Long, snap: Snapshot): LegState {
        val game = gameForTeam(snap.games.values, leg.league, leg.teamId)
            ?.takeIf { g -> g.startMillis?.let { it >= createdAt - SIX_HOURS } ?: true }
        leg.result?.let { r ->
            // Settled: today's scoreboard may already show this team's next game, so don't use it.
            val v = leg.final
            return LegState(leg, null, r, label(leg, null), v?.let { nowText(leg, it, null) }, fractionFor(leg, v), v)
        }
        val label = label(leg, game)
        if (game == null || game.state == GameState.PRE) return LegState(leg, game, LegStatus.PENDING, label, null, if (leg is PropLeg || leg is TotalLeg) 0.0 else null, null)

        val over: Boolean
        val line: Double
        val value: Double
        when (leg) {
            is PropLeg -> {
                val def = StatCatalog.find(leg.league, leg.prop.statId)
                value = def?.evaluate(snap.boxes[game.id]?.players?.get(leg.athleteId)) ?: 0.0
                over = leg.prop.side == Prop.Side.OVER
                line = leg.prop.line
            }
            is TotalLeg -> {
                value = ((game.home.score ?: 0) + (game.away.score ?: 0)).toDouble()
                over = leg.side == Prop.Side.OVER
                line = leg.line
            }
            is MoneylineLeg, is SpreadLeg -> {
                val me = game.competitor(leg.teamId)?.score ?: 0
                val them = game.opponent(leg.teamId)?.score ?: 0
                val margin = (me - them) + ((leg as? SpreadLeg)?.points ?: 0.0)
                val status = when {
                    game.state == GameState.POST -> if (margin > 0) LegStatus.WON else if (margin < 0) LegStatus.LOST else LegStatus.PUSH
                    margin > 0 -> LegStatus.LIVE_WINNING
                    else -> LegStatus.LIVE_LOSING
                }
                return LegState(leg, game, status, label, nowText(leg, (me - them).toDouble(), game), null, (me - them).toDouble())
            }
        }
        // Counting stats only go up, so an over is won (and an under lost) the moment it passes the line.
        val status = when {
            value > line -> if (over) LegStatus.WON else LegStatus.LOST
            game.state == GameState.POST -> if (value == line) LegStatus.PUSH else if (over) LegStatus.LOST else LegStatus.WON
            over -> LegStatus.LIVE_LOSING
            else -> LegStatus.LIVE_WINNING
        }
        return LegState(leg, game, status, label, nowText(leg, value, game), fractionFor(leg, value), value)
    }

    private fun fractionFor(leg: Leg, value: Double?): Double? {
        val line = when (leg) {
            is PropLeg -> leg.prop.line
            is TotalLeg -> leg.line
            else -> return null
        }
        return if (line <= 0) 1.0 else ((value ?: 0.0) / line).coerceIn(0.0, 1.0)
    }

    private fun nowText(leg: Leg, value: Double, game: Game?): String = when (leg) {
        is PropLeg -> StatFormat.number(value)
        is TotalLeg -> "${StatFormat.number(value)} pts"
        is MoneylineLeg, is SpreadLeg -> {
            val g = game
            val m = value.toInt()
            if (g == null) (if (m > 0) "Won by $m" else if (m < 0) "Lost by ${-m}" else "Tied")
            else {
                val me = g.competitor(leg.teamId)
                val them = g.opponent(leg.teamId)
                "${me?.abbreviation} ${me?.score ?: 0}–${them?.score ?: 0}"
            }
        }
    }

    private const val SIX_HOURS = 6 * 60 * 60 * 1000L
}

/** Parses ESPN's "2026-10-25T17:00Z" (no seconds) as well as full ISO instants. */
val Game.startMillis: Long?
    get() = runCatching { java.time.Instant.parse(startTime).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(startTime.removeSuffix("Z") + ":00Z").toEpochMilli() }.getOrNull()
