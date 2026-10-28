package com.sportscc.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- Things the user chooses to follow (persisted) ----------

@Serializable
sealed interface WatchItem {
    val league: League
    val key: String
    val displayName: String
}

@Serializable
@SerialName("team")
data class TeamWatch(
    override val league: League,
    val teamId: String,
    val abbreviation: String,
    val name: String,
    val logo: String? = null,
    val color: String? = null,
) : WatchItem {
    override val key: String get() = "team:${league.name}:$teamId"
    override val displayName: String get() = name
}

@Serializable
@SerialName("player")
data class PlayerWatch(
    override val league: League,
    val athleteId: String,
    val name: String,
    val teamId: String,
    val teamAbbreviation: String,
    val position: String? = null,
    val jersey: String? = null,
    val headshot: String? = null,
    val props: List<Prop> = emptyList(),
    /** Followed for fantasy: the card leads with the live fantasy score and there are no lines to set. */
    val fantasy: Boolean = false,
) : WatchItem {
    override val key: String get() = "player:${league.name}:$athleteId"
    override val displayName: String get() = name
}

/** A bet / target on one stat, e.g. "Over 74.5 receiving yards". */
@Serializable
data class Prop(val statId: String, val line: Double, val side: Side = Side.OVER) {
    enum class Side { OVER, UNDER }
}

// ---------- Live data parsed from ESPN ----------

enum class GameState { PRE, IN, POST }

data class Competitor(
    val teamId: String,
    val abbreviation: String,
    val displayName: String,
    val shortName: String,
    val logo: String?,
    val color: String?,
    val score: Int?,
    val record: String?,
    val isHome: Boolean,
)

data class Game(
    val id: String,
    val league: League,
    val startTime: String,
    val state: GameState,
    val detail: String,
    val shortDetail: String,
    val period: Int,
    val clock: String,
    val home: Competitor,
    val away: Competitor,
    val situation: String? = null,
    val lastPlay: String? = null,
    val possessionTeamId: String? = null,
    val broadcast: String? = null,
) {
    fun involves(teamId: String) = home.teamId == teamId || away.teamId == teamId
    fun competitor(teamId: String) = if (home.teamId == teamId) home else if (away.teamId == teamId) away else null
    fun opponent(teamId: String) = if (home.teamId == teamId) away else if (away.teamId == teamId) home else null

    /** "BUF 21 – 14 MIA" style, away team first like most US broadcasts. */
    val scoreLine: String
        get() = "${away.abbreviation} ${away.score ?: 0} – ${home.score ?: 0} ${home.abbreviation}"
}

data class StatEntry(val category: String, val label: String, val raw: String) {
    /** Numeric value; for "made-attempted" style values ("3-7", "18/27") this is the first number. */
    val value: Double? get() = parseStatValue(raw)
}

data class PlayerLine(
    val athleteId: String,
    val teamId: String,
    val name: String,
    val headshot: String?,
    val stats: List<StatEntry>,
    val didNotPlay: Boolean = false,
) {
    fun find(category: String?, label: String): StatEntry? = stats.firstOrNull {
        it.label.equals(label, ignoreCase = true) && (category == null || it.category.equals(category, ignoreCase = true))
    }
}

data class BoxScore(val gameId: String, val players: Map<String, PlayerLine>)

data class TeamInfo(
    val id: String,
    val abbreviation: String,
    val displayName: String,
    val shortName: String,
    val logo: String?,
    val color: String?,
)

data class AthleteInfo(
    val id: String,
    val name: String,
    val position: String?,
    val jersey: String?,
    val headshot: String?,
)

internal fun parseStatValue(raw: String): Double? {
    val s = raw.trim()
    if (s.isEmpty() || s == "-" || s == "--") return null
    s.toDoubleOrNull()?.let { return it }
    // "3-7", "18/27", "12-4" -> first number. Negative values like "-3" are caught above.
    val first = Regex("""^-?\d+(\.\d+)?""").find(s)?.value ?: return null
    return first.toDoubleOrNull()
}

/** The [part]-th number in a compound value: parseStatPart("18/27", 1) == 27.0. */
internal fun parseStatPart(raw: String, part: Int): Double? =
    Regex("""\d+(\.\d+)?""").findAll(raw).elementAtOrNull(part)?.value?.toDoubleOrNull()
