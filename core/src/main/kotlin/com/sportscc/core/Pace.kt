package com.sportscc.core

/**
 * "On pace for": where a counting stat ends up if the player keeps going at the same rate.
 * It's a plain straight-line projection over regulation time, so it's only shown once enough of the
 * game has gone by to mean something.
 */
object Pace {
    /** Don't project from the first few minutes: one long play would look like a 400-yard day. */
    const val MIN_ELAPSED = 0.2

    /** How much of regulation is done for a live game, 0..1; null if it hasn't started, is over, or can't tell. */
    fun elapsed(g: Game): Double? {
        if (g.state != GameState.IN || g.period <= 0) return null
        val (periods, minutes) = when (g.league) {
            League.NFL, League.NCAAF -> 4 to 15.0
            League.NBA -> 4 to 12.0
            League.WNBA -> 4 to 10.0
            League.NCAAM -> 2 to 20.0 // halves
            League.NHL -> 3 to 20.0
            League.MLB -> return baseball(g)
            League.MLS, League.EPL -> return null
        }
        if (g.period > periods) return 1.0 // overtime
        val left = clockMinutes(g.clock) ?: return null
        val done = (g.period - 1) * minutes + (minutes - left.coerceIn(0.0, minutes))
        return (done / (periods * minutes)).coerceIn(0.0, 1.0)
    }

    /** Projected final value, or null when it isn't worth showing. */
    fun project(current: Double, g: Game?): Double? {
        val e = g?.let { elapsed(it) } ?: return null
        if (e < MIN_ELAPSED || e >= 1.0) return null
        return current / e
    }

    /** "287" for big numbers, "4.5" for small ones (receptions, touchdowns). */
    fun format(v: Double): String = if (v >= 10) Math.round(v).toString() else StatFormat.number(Math.round(v * 10) / 10.0)

    /** Innings count as the period: "Top 5th" is 4.25 innings in, "Bot 5th" 4.75, "End 5th" 5. */
    private fun baseball(g: Game): Double? {
        val half = g.shortDetail.trim().lowercase()
        val inning = g.period.toDouble()
        val done = when {
            half.startsWith("top") -> inning - 0.75
            half.startsWith("mid") -> inning - 0.5
            half.startsWith("bot") -> inning - 0.25
            half.startsWith("end") -> inning
            else -> inning - 0.5
        }
        return (done / 9.0).coerceIn(0.0, 1.0)
    }

    /** "8:42" -> 8.7, "45.3" (under a minute) -> 0.755. */
    fun clockMinutes(clock: String): Double? {
        val c = clock.trim()
        if (c.isEmpty()) return null
        return if (':' in c) {
            val m = c.substringBefore(':').toIntOrNull() ?: return null
            val s = c.substringAfter(':').toDoubleOrNull() ?: return null
            m + s / 60.0
        } else c.toDoubleOrNull()?.let { it / 60.0 }
    }
}
