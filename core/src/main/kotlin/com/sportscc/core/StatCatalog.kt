package com.sportscc.core

/**
 * One box-score column. [labels] are alternatives (ESPN isn't always consistent, e.g. "SOG" vs "S"),
 * [category] null means "any category" (basketball/hockey box scores mostly have a single group).
 */
data class StatComponent(
    val labels: List<String>,
    val category: String? = null,
    val weight: Double = 1.0,
    /** Which number of a "made/attempted" value to read: 0 = first ("18" of "18/27"), 1 = second ("27"). */
    val part: Int = 0,
)

/** A trackable number for props and fantasy: a weighted sum of box-score columns. */
data class StatDef(val id: String, val name: String, val short: String, val components: List<StatComponent>) {
    /** Returns null when none of the components appear in the line (player hasn't recorded anything yet). */
    fun evaluate(line: PlayerLine?): Double? {
        if (line == null) return null
        var found = false
        var total = 0.0
        for (c in components) {
            val v = c.labels.firstNotNullOfOrNull { label ->
                line.find(c.category, label)?.let { if (c.part == 0) it.value else parseStatPart(it.raw, c.part) }
            } ?: continue
            found = true
            total += v * c.weight
        }
        return if (found) total else null
    }
}

/** Which columns a player card shows and which ones raise alerts when they tick up. */
data class StatGroup(val category: String?, val title: String, val labels: List<String>)

object StatCatalog {
    private fun c(category: String?, vararg labels: String, weight: Double = 1.0, part: Int = 0) =
        StatComponent(labels.toList(), category, weight, part)

    private fun def(id: String, name: String, short: String, vararg comps: StatComponent) =
        StatDef(id, name, short, comps.toList())

    private val football = listOf(
        def("pass_yds", "Passing Yards", "Pass Yds", c("passing", "YDS")),
        def("pass_td", "Passing TDs", "Pass TD", c("passing", "TD")),
        def("pass_cmp", "Completions", "Cmp", c("passing", "C/ATT")),
        def("pass_att", "Pass Attempts", "Pass Att", c("passing", "C/ATT", part = 1)),
        def("pass_int", "Interceptions Thrown", "INT", c("passing", "INT")),
        def("rush_yds", "Rushing Yards", "Rush Yds", c("rushing", "YDS")),
        def("rush_att", "Rush Attempts", "Carries", c("rushing", "CAR")),
        def("rush_td", "Rushing TDs", "Rush TD", c("rushing", "TD")),
        def("rec", "Receptions", "Rec", c("receiving", "REC")),
        def("rec_yds", "Receiving Yards", "Rec Yds", c("receiving", "YDS")),
        def("rec_td", "Receiving TDs", "Rec TD", c("receiving", "TD")),
        def("rush_rec_yds", "Rush + Rec Yards", "Rush+Rec", c("rushing", "YDS"), c("receiving", "YDS")),
        def("any_td", "Anytime TD (rush/rec)", "TDs", c("rushing", "TD"), c("receiving", "TD")),
        def(
            "fpts_ppr", "Fantasy Points (PPR)", "PPR",
            c("passing", "YDS", weight = 0.04), c("passing", "TD", weight = 4.0), c("passing", "INT", weight = -2.0),
            c("rushing", "YDS", weight = 0.1), c("rushing", "TD", weight = 6.0),
            c("receiving", "REC", weight = 1.0), c("receiving", "YDS", weight = 0.1), c("receiving", "TD", weight = 6.0),
            c("fumbles", "LOST", weight = -2.0),
        ),
        def(
            "fpts_half", "Fantasy Points (Half PPR)", "Half PPR",
            c("passing", "YDS", weight = 0.04), c("passing", "TD", weight = 4.0), c("passing", "INT", weight = -2.0),
            c("rushing", "YDS", weight = 0.1), c("rushing", "TD", weight = 6.0),
            c("receiving", "REC", weight = 0.5), c("receiving", "YDS", weight = 0.1), c("receiving", "TD", weight = 6.0),
            c("fumbles", "LOST", weight = -2.0),
        ),
    )

    private val basketball = listOf(
        def("pts", "Points", "PTS", c(null, "PTS")),
        def("reb", "Rebounds", "REB", c(null, "REB")),
        def("ast", "Assists", "AST", c(null, "AST")),
        def("threes", "3-Pointers Made", "3PM", c(null, "3PT")),
        def("stl", "Steals", "STL", c(null, "STL")),
        def("blk", "Blocks", "BLK", c(null, "BLK")),
        def("pra", "Pts + Reb + Ast", "PRA", c(null, "PTS"), c(null, "REB"), c(null, "AST")),
        def("pr", "Pts + Reb", "P+R", c(null, "PTS"), c(null, "REB")),
        def("pa", "Pts + Ast", "P+A", c(null, "PTS"), c(null, "AST")),
        def(
            "fpts_dk", "Fantasy Points (DK)", "FPTS",
            c(null, "PTS"), c(null, "3PT", weight = 0.5), c(null, "REB", weight = 1.25), c(null, "AST", weight = 1.5),
            c(null, "STL", weight = 2.0), c(null, "BLK", weight = 2.0), c(null, "TO", weight = -0.5),
        ),
    )

    private val baseball = listOf(
        def("hits", "Hits", "H", c("batting", "H")),
        def("hr", "Home Runs", "HR", c("batting", "HR")),
        def("rbi", "RBIs", "RBI", c("batting", "RBI")),
        def("runs", "Runs", "R", c("batting", "R")),
        def("hrr", "Hits + Runs + RBIs", "H+R+RBI", c("batting", "H"), c("batting", "R"), c("batting", "RBI")),
        def("walks", "Walks", "BB", c("batting", "BB")),
        def("p_k", "Pitcher Strikeouts", "K", c("pitching", "K")),
        def("p_er", "Earned Runs Allowed", "ER", c("pitching", "ER")),
        def("p_h", "Hits Allowed", "H Allowed", c("pitching", "H")),
        def("p_ip", "Innings Pitched", "IP", c("pitching", "IP")),
    )

    private val hockey = listOf(
        def("goals", "Goals", "G", c(null, "G")),
        def("assists", "Assists", "A", c(null, "A")),
        def("points", "Points", "PTS", c(null, "G"), c(null, "A")),
        def("sog", "Shots on Goal", "SOG", c(null, "SOG", "S")),
        def("saves", "Saves (goalie)", "SV", c(null, "SV")),
    )

    fun forKind(kind: SportKind): List<StatDef> = when (kind) {
        SportKind.FOOTBALL -> football
        SportKind.BASKETBALL -> basketball
        SportKind.BASEBALL -> baseball
        SportKind.HOCKEY -> hockey
        SportKind.SOCCER -> emptyList()
    }

    fun find(league: League, id: String): StatDef? = league.statDefs.firstOrNull { it.id == id }

    /** The sport's standard fantasy score (PPR for football, DraftKings for basketball), if it has one. */
    fun fantasy(league: League): StatDef? = league.statDefs.firstOrNull { it.id.startsWith("fpts") }

    /** Stats offered as over/under lines. Fantasy points aren't: fantasy players are tracked without lines. */
    fun propDefs(league: League): List<StatDef> = league.statDefs.filterNot { it.id.startsWith("fpts") }

    fun groups(kind: SportKind): List<StatGroup> = when (kind) {
        SportKind.FOOTBALL -> listOf(
            StatGroup("passing", "Pass", listOf("C/ATT", "YDS", "TD", "INT")),
            StatGroup("rushing", "Rush", listOf("CAR", "YDS", "TD")),
            StatGroup("receiving", "Rec", listOf("REC", "TGTS", "YDS", "TD")),
            StatGroup("fumbles", "Fum", listOf("LOST")),
        )
        SportKind.BASKETBALL -> listOf(
            StatGroup(null, "", listOf("MIN", "PTS", "REB", "AST", "3PT", "STL", "BLK")),
        )
        SportKind.BASEBALL -> listOf(
            StatGroup("batting", "Bat", listOf("H-AB", "R", "HR", "RBI", "BB", "K")),
            StatGroup("pitching", "Pitch", listOf("IP", "H", "ER", "BB", "K")),
        )
        SportKind.HOCKEY -> listOf(
            StatGroup(null, "", listOf("G", "A", "SOG", "S", "SV", "GA", "TOI")),
        )
        SportKind.SOCCER -> emptyList()
    }

    /** Columns that change constantly or aren't counting stats; never worth an alert on their own. */
    val nonAlertLabels = setOf("MIN", "TOI", "+/-", "IP", "H-AB", "C/ATT", "CAR", "TGTS", "PC", "GA")

    /** A tick in one of these is a "big play" (highlighted alert). */
    fun isBigPlay(kind: SportKind, category: String, label: String): Boolean = when (kind) {
        SportKind.FOOTBALL -> label == "TD" || (category == "passing" && label == "INT") || label == "LOST"
        SportKind.BASEBALL -> category == "batting" && label == "HR"
        SportKind.HOCKEY -> label == "G"
        else -> false
    }
}
