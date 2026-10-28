package com.sportscc.core

/** Builders for ESPN-shaped JSON, trimmed to the fields the parser reads. */
object Fixtures {
    fun scoreboard(vararg events: String) = """{"leagues":[],"events":[${events.joinToString(",")}]}"""

    fun event(
        id: String,
        state: String,
        awayId: String, awayAbbr: String, awayScore: Int,
        homeId: String, homeAbbr: String, homeScore: Int,
        period: Int = 1,
        clock: String = "15:00",
        shortDetail: String = "Q$period $clock",
        lastPlay: String? = null,
        downDistance: String? = null,
    ): String {
        val situation = if (lastPlay != null || downDistance != null) {
            ""","situation":{${listOfNotNull(
                downDistance?.let { "\"downDistanceText\":\"$it\"" },
                lastPlay?.let { "\"lastPlay\":{\"text\":\"$it\"}" },
            ).joinToString(",")}}"""
        } else ""
        return """
        {"id":"$id","date":"2026-10-25T17:00Z","name":"$awayAbbr at $homeAbbr",
         "competitions":[{"id":"$id",
           "competitors":[
             {"id":"$homeId","homeAway":"home","score":"$homeScore",
              "team":{"id":"$homeId","abbreviation":"$homeAbbr","displayName":"$homeAbbr Team","shortDisplayName":"$homeAbbr","logo":"https://a.espncdn.com/$homeAbbr.png","color":"00338d"},
              "records":[{"summary":"5-2"}]},
             {"id":"$awayId","homeAway":"away","score":"$awayScore",
              "team":{"id":"$awayId","abbreviation":"$awayAbbr","displayName":"$awayAbbr Team","shortDisplayName":"$awayAbbr"}}
           ],
           "status":{"displayClock":"$clock","period":$period,"type":{"state":"$state","completed":${state == "post"},"detail":"$shortDetail","shortDetail":"$shortDetail"}},
           "broadcasts":[{"market":"national","names":["CBS"]}]
           $situation
         }]}
        """.trimIndent()
    }

    data class Ath(val id: String, val name: String, val stats: List<String>)

    fun summaryFootball(teamId: String, passing: List<Ath> = emptyList(), rushing: List<Ath> = emptyList(), receiving: List<Ath> = emptyList()): String {
        fun group(name: String, labels: List<String>, aths: List<Ath>) = """
            {"name":"$name","text":"${name.replaceFirstChar { it.uppercase() }}","labels":[${labels.joinToString(",") { "\"$it\"" }}],
             "athletes":[${aths.joinToString(",") { a -> """{"athlete":{"id":"${a.id}","displayName":"${a.name}","headshot":{"href":"https://h/${a.id}.png"}},"stats":[${a.stats.joinToString(",") { "\"$it\"" }}]}""" }}]}
        """.trimIndent()
        return """{"boxscore":{"players":[{"team":{"id":"$teamId"},"statistics":[
            ${group("passing", listOf("C/ATT", "YDS", "AVG", "TD", "INT", "SACKS", "QBR", "RTG"), passing)},
            ${group("rushing", listOf("CAR", "YDS", "AVG", "TD", "LONG"), rushing)},
            ${group("receiving", listOf("REC", "YDS", "AVG", "TD", "LONG", "TGTS"), receiving)}
        ]}]}}"""
    }

    fun summaryBasketball(teamId: String, vararg aths: Ath): String {
        val labels = listOf("MIN", "FG", "3PT", "FT", "OREB", "DREB", "REB", "AST", "STL", "BLK", "TO", "PF", "+/-", "PTS")
        return """{"boxscore":{"players":[{"team":{"id":"$teamId"},"statistics":[{
            "names":[${labels.joinToString(",") { "\"$it\"" }}],"labels":[${labels.joinToString(",") { "\"$it\"" }}],
            "athletes":[${aths.joinToString(",") { a -> """{"athlete":{"id":"${a.id}","displayName":"${a.name}"},"stats":[${a.stats.joinToString(",") { "\"$it\"" }}]}""" }},
              {"athlete":{"id":"999","displayName":"Bench Guy"},"stats":[],"didNotPlay":true}]
        }]}]}}"""
    }
}
