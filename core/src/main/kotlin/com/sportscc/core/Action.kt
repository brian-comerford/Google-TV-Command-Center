package com.sportscc.core

import kotlin.math.abs

/** How much a live game is worth switching to right now. */
enum class Heat { NONE, WARM, HOT }

/** [reason] is what the switch prompt says, e.g. "BUF in the red zone (MIA 12)". */
data class Interest(val heat: Heat, val reason: String? = null)

/**
 * "Switch to the action": rates live games so the Watch screen can jump to (HOT) or quietly preload
 * (WARM) the stream assigned to the most interesting one.
 * HOT: a football team inside the 20, or a close game in its final minutes.
 * WARM: the build-up to that (a drive past midfield, a close game late), worth loading in the background.
 */
object Action {
    fun interest(g: Game): Interest {
        if (g.state != GameState.IN) return Interest(Heat.NONE)
        val margin = abs((g.home.score ?: 0) - (g.away.score ?: 0))
        val left = Pace.clockMinutes(g.clock)
        val score = "${g.away.abbreviation} ${g.away.score ?: 0}–${g.home.score ?: 0} ${g.home.abbreviation}"
        fun late(finalPeriod: Int, hot: Double, warm: Double, closeHot: Int, closeWarm: Int): Interest? {
            val overtime = g.period > finalPeriod
            if (overtime && margin <= closeHot) return Interest(Heat.HOT, "Overtime: $score")
            if (g.period != finalPeriod || left == null) return null
            if (left <= hot && margin <= closeHot) return Interest(Heat.HOT, "Close finish: $score, ${g.clock} left")
            if (left <= warm && margin <= closeWarm) return Interest(Heat.WARM, "Close game: $score, ${g.clock} left")
            return null
        }
        return when (g.league.kind) {
            SportKind.FOOTBALL -> {
                val offense = listOf(g.home, g.away).firstOrNull { it.teamId == g.possessionTeamId }
                val yards = g.yardsToGoal
                val spot = g.situation?.substringAfter(" at ", "")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                when {
                    offense != null && (g.redZone || (yards != null && yards <= 20)) -> Interest(Heat.HOT, "${offense.abbreviation} in the red zone$spot")
                    else -> late(4, 2.0, 5.0, 8, 8)
                        ?: if (offense != null && yards != null && yards <= 40) Interest(Heat.WARM, "${offense.abbreviation} driving$spot") else Interest(Heat.NONE)
                }
            }
            SportKind.BASKETBALL -> late(if (g.league == League.NCAAM) 2 else 4, 2.0, 5.0, 5, 10) ?: Interest(Heat.NONE)
            SportKind.HOCKEY -> late(3, 3.0, 8.0, 1, 1) ?: Interest(Heat.NONE)
            SportKind.BASEBALL -> when {
                g.period >= 9 && margin <= 1 -> Interest(Heat.HOT, "Final inning: $score")
                g.period >= 8 && margin <= 2 -> Interest(Heat.WARM, "Late and close: $score")
                else -> Interest(Heat.NONE)
            }
            SportKind.SOCCER -> Interest(Heat.NONE)
        }
    }

    /**
     * The most interesting game among [games] other than [currentGameId], hottest first (ties keep
     * the given order), or null if none is at least WARM.
     */
    fun pick(games: List<Game>, currentGameId: String?): Pair<Game, Interest>? =
        games.filter { it.id != currentGameId }
            .map { it to interest(it) }
            .filter { it.second.heat != Heat.NONE }
            .maxWithOrNull(compareBy { it.second.heat.ordinal })
}
