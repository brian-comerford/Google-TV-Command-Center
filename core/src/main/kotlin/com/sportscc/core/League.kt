package com.sportscc.core

import kotlinx.serialization.Serializable

enum class SportKind { FOOTBALL, BASKETBALL, BASEBALL, HOCKEY, SOCCER }

/**
 * A league covered by ESPN's public site API. [sport] and [path] are the two URL segments
 * used in `.../apis/site/v2/sports/{sport}/{path}/...`.
 */
@Serializable
enum class League(
    val sport: String,
    val path: String,
    val label: String,
    val kind: SportKind,
    /** Extra scoreboard query params, e.g. to get every college game instead of only the Top 25. */
    val scoreboardQuery: String = "",
) {
    NFL("football", "nfl", "NFL", SportKind.FOOTBALL),
    NCAAF("football", "college-football", "College Football", SportKind.FOOTBALL, "groups=80&limit=300"),
    NBA("basketball", "nba", "NBA", SportKind.BASKETBALL),
    WNBA("basketball", "wnba", "WNBA", SportKind.BASKETBALL),
    NCAAM("basketball", "mens-college-basketball", "College Basketball", SportKind.BASKETBALL, "groups=50&limit=400"),
    MLB("baseball", "mlb", "MLB", SportKind.BASEBALL),
    NHL("hockey", "nhl", "NHL", SportKind.HOCKEY),
    MLS("soccer", "usa.1", "MLS", SportKind.SOCCER),
    EPL("soccer", "eng.1", "Premier League", SportKind.SOCCER);

    /** ESPN soccer box scores don't list per-player stats the same way, so only teams are trackable. */
    val supportsPlayers: Boolean get() = kind != SportKind.SOCCER

    val statDefs: List<StatDef> get() = StatCatalog.forKind(kind)
}
