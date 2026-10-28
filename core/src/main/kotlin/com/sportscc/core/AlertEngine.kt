package com.sportscc.core

import kotlin.math.abs
import kotlin.math.sign

data class Alert(
    val id: String,
    val time: Long,
    val league: League,
    val watchKey: String,
    val title: String,
    val detail: String?,
    /** Touchdowns, home runs, goals, finals, prop results: shown louder and longer. */
    val big: Boolean,
    val imageUrl: String? = null,
    /** For a stat update on a player you've bet on: where each of their props stands now. */
    val props: List<PropProgress> = emptyList(),
)

/** Everything fetched in one poll, keyed by game id. */
data class Snapshot(val games: Map<String, Game>, val boxes: Map<String, BoxScore>) {
    companion object {
        val EMPTY = Snapshot(emptyMap(), emptyMap())
    }
}

data class StatChip(val label: String, val value: String)

object StatFormat {
    fun number(d: Double): String =
        if (d == Math.floor(d) && abs(d) < 1e9) d.toLong().toString() else String.format("%.1f", d)

    fun chips(kind: SportKind, line: PlayerLine?): List<StatChip> {
        if (line == null) return emptyList()
        val out = mutableListOf<StatChip>()
        for (g in StatCatalog.groups(kind)) {
            val entries = g.labels.mapNotNull { line.find(g.category, it) }
            if (entries.isEmpty()) continue
            for (e in entries) {
                val label = if (g.title.isEmpty() || e.label.contains('/') || e.label.contains('-')) e.label else "${g.title} ${e.label}"
                out += StatChip(label, e.raw)
            }
        }
        return out
    }

    fun summary(kind: SportKind, line: PlayerLine?): String =
        chips(kind, line).joinToString(" · ") { "${it.value} ${it.label}" }
}

/** Picks the most relevant game for a team: live, then upcoming, then most recent final. */
fun gameForTeam(games: Collection<Game>, league: League, teamId: String): Game? {
    val mine = games.filter { it.league == league && it.involves(teamId) }
    return mine.firstOrNull { it.state == GameState.IN }
        ?: mine.filter { it.state == GameState.PRE }.minByOrNull { it.startTime }
        ?: mine.filter { it.state == GameState.POST }.maxByOrNull { it.startTime }
}

object AlertEngine {

    fun diff(prev: Snapshot, next: Snapshot, watchlist: List<WatchItem>, now: Long): List<Alert> {
        val alerts = mutableListOf<Alert>()
        val gameAlertsDone = mutableSetOf<String>()
        var seq = 0
        fun add(w: WatchItem, title: String, detail: String?, big: Boolean, image: String? = null, props: List<PropProgress> = emptyList()) {
            alerts += Alert("${w.key}:$now:${seq++}", now, w.league, w.key, title, detail, big, image, props)
        }

        for (w in watchlist) {
            when (w) {
                is TeamWatch -> {
                    val g = gameForTeam(next.games.values, w.league, w.teamId) ?: continue
                    val old = prev.games[g.id] ?: continue
                    if (!gameAlertsDone.add(g.id)) continue
                    teamAlerts(w, old, g).forEach { (t, d, big) -> add(w, t, d, big, w.logo) }
                }
                is PlayerWatch -> {
                    val g = gameForTeam(next.games.values, w.league, w.teamId) ?: continue
                    val oldGame = prev.games[g.id] ?: continue
                    val oldLine = prev.boxes[g.id]?.players?.get(w.athleteId)
                    val newLine = next.boxes[g.id]?.players?.get(w.athleteId)
                    val prevHadBox = prev.boxes.containsKey(g.id)

                    if (newLine != null && (oldLine != null || prevHadBox)) {
                        statAlert(w, g, oldLine, newLine)?.let { (t, d, big) ->
                            add(w, t, d, big, w.headshot, if (w.fantasy) emptyList() else propProgress(w, newLine))
                        }
                    }
                    if (newLine != null || g.state == GameState.POST) {
                        propAlerts(w, oldGame, g, oldLine, newLine, prevHadBox).forEach { (t, d, big) -> add(w, t, d, big, w.headshot) }
                    }
                }
                is ParlayWatch -> parlayAlerts(w, prev, next).forEach { (t, d, big) -> add(w, t, d, big) }
            }
        }
        return alerts
    }

    private fun parlayAlerts(w: ParlayWatch, prev: Snapshot, next: Snapshot): List<Msg> {
        val before = ParlayEngine.evaluate(w, prev)
        val after = ParlayEngine.evaluate(w, next)
        val name = w.displayName
        val win = w.toWin?.let { " · To win ${money(it)}" } ?: ""
        if (before.status == ParlayStatus.LOST) return emptyList()
        if (after.status == ParlayStatus.WON && before.status != ParlayStatus.WON) {
            return listOf(Msg("PARLAY CASHED: $name", "${after.summary}${w.toWin?.let { " · Won ${money(it)}" } ?: ""}", true))
        }
        val out = mutableListOf<Msg>()
        after.legs.forEachIndexed { i, a ->
            val b = before.legs[i]
            // A prop leg we had no box score for last poll may already be past its line: that's not news.
            val g = a.game
            val pg = g?.let { prev.games[it.id] }
            if (a.leg is PropLeg && g != null && pg != null && pg.state != GameState.PRE && !prev.boxes.containsKey(g.id)) return@forEachIndexed
            if (b.status.decided || !a.status.decided) return@forEachIndexed
            when (a.status) {
                LegStatus.WON -> {
                    val left = after.legs.filter { !it.status.decided }
                    val next1 = if (left.size == 1) " · One leg away: ${left.single().label}" else ""
                    out += Msg("Parlay leg hit: ${a.label}", "$name · ${after.summary}$next1$win", true)
                }
                LegStatus.LOST -> if (out.none { it.title.startsWith("Parlay busted") }) {
                    out += Msg("Parlay busted: ${a.label}", "$name · ${after.won} of ${after.legs.size} hit", true)
                }
                LegStatus.PUSH -> out += Msg("Parlay leg pushed: ${a.label}", "$name · ${after.summary}", false)
                else -> Unit
            }
        }
        return out
    }

    private fun money(d: Double): String = if (d == Math.floor(d)) "$" + d.toLong() else "$" + String.format("%.2f", d)

    private data class Msg(val title: String, val detail: String?, val big: Boolean)

    /** Every prop on the player, valued from [line], in the order the user added them. */
    fun propProgress(w: PlayerWatch, line: PlayerLine?): List<PropProgress> = w.props.mapNotNull { p ->
        val def = StatCatalog.find(w.league, p.statId) ?: return@mapNotNull null
        PropProgress(p, def, def.evaluate(line) ?: 0.0)
    }

    private fun teamAlerts(w: TeamWatch, old: Game, g: Game): List<Msg> {
        val out = mutableListOf<Msg>()
        val matchup = "${g.away.abbreviation} @ ${g.home.abbreviation}"
        if (old.state == GameState.PRE && g.state == GameState.IN) {
            out += Msg("Game on: $matchup", listOfNotNull(g.shortDetail, g.broadcast?.let { "on $it" }).joinToString(" · "), false)
        }
        if (old.state != GameState.POST && g.state == GameState.POST) {
            val me = g.competitor(w.teamId)
            val opp = g.opponent(w.teamId)
            val result = if (me?.score != null && opp?.score != null) {
                when {
                    me.score > opp.score -> "${me.abbreviation} WIN"
                    me.score < opp.score -> "${me.abbreviation} lose"
                    else -> "Tie"
                }
            } else null
            out += Msg("FINAL: ${g.scoreLine}", result, true)
            return out
        }
        if (g.state != GameState.IN) return out

        if (g.league.kind == SportKind.BASKETBALL) {
            // A bucket every 20 seconds would be spam; report lead changes and new periods instead.
            val oldDiff = (old.home.score ?: 0) - (old.away.score ?: 0)
            val newDiff = (g.home.score ?: 0) - (g.away.score ?: 0)
            if (newDiff != 0 && oldDiff.sign != newDiff.sign) {
                val leader = if (newDiff > 0) g.home else g.away
                out += Msg("Lead change: ${leader.abbreviation} up ${abs(newDiff)}", "${g.scoreLine} · ${g.shortDetail}", false)
            }
            if (g.period > old.period && old.state == GameState.IN) {
                out += Msg("End of ${periodName(g.league, old.period)}", "${g.scoreLine} · ${g.shortDetail}", false)
            }
            return out
        }

        for ((before, after) in listOf(old.away to g.away, old.home to g.home)) {
            val delta = (after.score ?: 0) - (before.score ?: 0)
            if (delta <= 0) continue
            val what = when (g.league.kind) {
                SportKind.FOOTBALL -> when (delta) {
                    6, 7, 8 -> "TOUCHDOWN"
                    3 -> "Field goal"
                    2 -> "2 points"
                    1 -> "Extra point"
                    else -> "+$delta"
                }
                SportKind.BASEBALL -> if (delta == 1) "scores a run" else "scores $delta runs"
                SportKind.HOCKEY, SportKind.SOCCER -> "GOAL"
                SportKind.BASKETBALL -> "+$delta"
            }
            val big = !(g.league.kind == SportKind.FOOTBALL && delta < 6)
            out += Msg("${after.abbreviation} $what", listOfNotNull("${g.scoreLine} · ${g.shortDetail}", g.lastPlay).joinToString("\n"), big)
        }
        return out
    }

    private fun statAlert(w: PlayerWatch, g: Game, old: PlayerLine?, new: PlayerLine): Msg? {
        val kind = w.league.kind
        val parts = mutableListOf<String>()
        var big = false
        for (group in StatCatalog.groups(kind)) {
            for (label in group.labels) {
                if (label in StatCatalog.nonAlertLabels) continue
                val n = new.find(group.category, label) ?: continue
                val nv = n.value ?: continue
                val ov = old?.find(group.category, label)?.value ?: 0.0
                val delta = nv - ov
                if (delta <= 0.0) continue
                val name = if (group.title.isEmpty()) label else "${group.title} $label"
                parts += "+${StatFormat.number(delta)} $name"
                if (StatCatalog.isBigPlay(kind, n.category, label)) big = true
            }
        }
        if (parts.isEmpty()) return null
        var line = StatFormat.summary(kind, new)
        var title = "${w.name}: ${parts.joinToString(", ")}"
        val fantasyDef = StatCatalog.fantasy(w.league)
        if (w.fantasy && fantasyDef != null) {
            val now = fantasyDef.evaluate(new) ?: 0.0
            val delta = now - (fantasyDef.evaluate(old) ?: 0.0)
            if (delta != 0.0) title += " (${if (delta > 0) "+" else "−"}${StatFormat.number(kotlin.math.abs(delta))} ${fantasyDef.short})"
            line = "${StatFormat.number(now)} ${fantasyDef.short} · $line"
        }
        val detail = listOfNotNull(line.ifBlank { null }, g.lastPlay).joinToString("\n")
        return Msg(title, detail, big)
    }

    private fun propAlerts(
        w: PlayerWatch, oldGame: Game, g: Game, old: PlayerLine?, new: PlayerLine?, prevHadBox: Boolean,
    ): List<Msg> {
        val out = mutableListOf<Msg>()
        for (p in w.props) {
            val def = StatCatalog.find(w.league, p.statId) ?: continue
            val nv = def.evaluate(new) ?: 0.0
            val line = StatFormat.number(p.line)
            val label = "${w.name} ${p.side.name.lowercase()} $line ${def.short}"
            if (old != null || prevHadBox) {
                val ov = def.evaluate(old) ?: 0.0
                if (ov <= p.line && nv > p.line) {
                    out += if (p.side == Prop.Side.OVER) Msg("PROP HIT: $label", "Now at ${StatFormat.number(nv)}", true)
                    else Msg("Prop busted: $label", "Now at ${StatFormat.number(nv)}", true)
                }
            }
            if (oldGame.state != GameState.POST && g.state == GameState.POST) {
                val won = if (p.side == Prop.Side.OVER) nv > p.line else nv < p.line
                val title = when {
                    nv == p.line -> "Push: $label"
                    won -> "PROP CASHED: $label"
                    else -> "Prop lost: $label"
                }
                out += Msg(title, "Final: ${StatFormat.number(nv)}", true)
            }
        }
        return out
    }

    fun periodName(league: League, period: Int): String = when {
        league == League.NCAAM ->
            if (period <= 2) "${if (period == 1) "1st" else "2nd"} half" else "OT${if (period > 3) period - 2 else ""}"
        else -> periodName(league.kind, period)
    }

    private fun periodName(kind: SportKind, period: Int): String = when (kind) {
        SportKind.FOOTBALL, SportKind.BASKETBALL -> if (period <= 4) "Q$period" else "OT${if (period > 5) period - 4 else ""}"
        SportKind.HOCKEY -> if (period <= 3) "P$period" else "OT"
        SportKind.BASEBALL -> "Inning $period"
        SportKind.SOCCER -> if (period == 1) "1st half" else "2nd half"
    }
}
