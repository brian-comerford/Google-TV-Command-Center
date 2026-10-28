package com.sportscc.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun interface HttpFetcher {
    suspend fun get(url: String): String
}

class UrlConnectionFetcher(private val timeoutMs: Int = 10_000) : HttpFetcher {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "SportsCommandCenter/1.0 (Android TV)")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

class EspnClient(
    private val fetcher: HttpFetcher = UrlConnectionFetcher(),
    private val base: String = "https://site.api.espn.com/apis/site/v2/sports",
) {
    private fun url(league: League, path: String) = "$base/${league.sport}/${league.path}/$path"

    suspend fun scoreboard(league: League): List<Game> {
        val q = league.scoreboardQuery.let { if (it.isEmpty()) "" else "?$it" }
        return EspnParser.parseScoreboard(fetcher.get(url(league, "scoreboard$q")), league)
    }

    suspend fun summary(league: League, eventId: String): BoxScore =
        EspnParser.parseSummary(fetcher.get(url(league, "summary?event=$eventId")), eventId)

    suspend fun teams(league: League): List<TeamInfo> =
        EspnParser.parseTeams(fetcher.get(url(league, "teams?limit=1000")))

    suspend fun roster(league: League, teamId: String): List<AthleteInfo> =
        EspnParser.parseRoster(fetcher.get(url(league, "teams/$teamId/roster")))
}
