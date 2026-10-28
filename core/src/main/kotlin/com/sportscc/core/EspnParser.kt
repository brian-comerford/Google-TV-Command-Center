package com.sportscc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parses ESPN's (unofficial, undocumented) site API. Every lookup is null-safe because fields
 * come and go between sports and seasons; a missing field should degrade a card, not crash.
 */
object EspnParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseScoreboard(body: String, league: League): List<Game> {
        val root = json.parseToJsonElement(body)
        return root.arr("events").mapNotNull { parseEvent(it, league) }
    }

    fun parseEvent(event: JsonElement, league: League): Game? {
        val comp = event.arr("competitions").firstOrNull() ?: return null
        val competitors = comp.arr("competitors").mapNotNull { parseCompetitor(it) }
        val home = competitors.firstOrNull { it.isHome } ?: competitors.getOrNull(0) ?: return null
        val away = competitors.firstOrNull { !it.isHome && it !== home } ?: competitors.getOrNull(1) ?: return null
        val status = comp.obj("status") ?: event.obj("status")
        val type = status.obj("type")
        val state = when (type.str("state")) {
            "in" -> GameState.IN
            "post" -> GameState.POST
            else -> GameState.PRE
        }
        val situation = comp.obj("situation")
        return Game(
            id = event.str("id") ?: return null,
            league = league,
            startTime = event.str("date") ?: comp.str("date") ?: "",
            state = state,
            detail = type.str("detail") ?: type.str("description") ?: "",
            shortDetail = type.str("shortDetail") ?: type.str("detail") ?: "",
            period = status.int("period") ?: 0,
            clock = status.str("displayClock") ?: "",
            home = home,
            away = away,
            situation = situationText(situation, league.kind),
            lastPlay = situation.obj("lastPlay").str("text")?.takeIf { it.isNotBlank() },
            possessionTeamId = situation.str("possession")?.takeIf { it.isNotBlank() },
            broadcast = comp.arr("broadcasts").firstOrNull()?.arr("names")?.firstOrNull()?.prim()
                ?: comp.arr("geoBroadcasts").firstOrNull()?.obj("media").str("shortName"),
        )
    }

    private fun parseCompetitor(el: JsonElement): Competitor? {
        val team = el.obj("team") ?: return null
        val id = team.str("id") ?: el.str("id") ?: return null
        val score = el["score"]?.let { s ->
            when (s) {
                is JsonPrimitive -> s.contentOrNull?.toDoubleOrNull()?.toInt()
                is JsonObject -> s.str("value")?.toDoubleOrNull()?.toInt() ?: s.str("displayValue")?.toIntOrNull()
                else -> null
            }
        }
        val record = el.arr("records").firstOrNull()?.str("summary") ?: el.arr("record").firstOrNull()?.str("summary")
        return Competitor(
            teamId = id,
            abbreviation = team.str("abbreviation") ?: team.str("shortDisplayName") ?: "?",
            displayName = team.str("displayName") ?: team.str("name") ?: "",
            shortName = team.str("shortDisplayName") ?: team.str("name") ?: "",
            logo = team.str("logo") ?: team.arr("logos").firstOrNull()?.str("href"),
            color = team.str("color"),
            score = score,
            record = record,
            isHome = el.str("homeAway") == "home",
        )
    }

    private fun situationText(s: JsonObject?, kind: SportKind): String? {
        if (s == null) return null
        return when (kind) {
            SportKind.FOOTBALL -> s.str("downDistanceText") ?: s.str("shortDownDistanceText")
            SportKind.BASEBALL -> {
                val balls = s.int("balls")
                val strikes = s.int("strikes")
                val outs = s.int("outs")
                val bases = listOfNotNull(
                    "1st".takeIf { s.bool("onFirst") == true },
                    "2nd".takeIf { s.bool("onSecond") == true },
                    "3rd".takeIf { s.bool("onThird") == true },
                )
                buildList {
                    if (balls != null && strikes != null) add("$balls-$strikes")
                    if (outs != null) add("$outs out")
                    if (bases.isNotEmpty()) add("On: ${bases.joinToString(", ")}")
                }.joinToString(" · ").ifBlank { null }
            }
            else -> null
        }
    }

    fun parseSummary(body: String, gameId: String): BoxScore {
        val root = json.parseToJsonElement(body)
        val players = mutableMapOf<String, PlayerLine>()
        for (teamBlock in root.obj("boxscore").arr("players")) {
            val teamId = teamBlock.obj("team").str("id") ?: ""
            for (group in teamBlock.arr("statistics")) {
                val category = (group.str("name") ?: group.str("type") ?: group.str("text") ?: "").lowercase()
                val labels = group.arr("labels").mapNotNull { it.prim() }
                    .ifEmpty { group.arr("names").mapNotNull { it.prim() } }
                for (a in group.arr("athletes")) {
                    val athlete = a.obj("athlete") ?: continue
                    val id = athlete.str("id") ?: continue
                    val values = a.arr("stats").map { it.prim() ?: "" }
                    val entries = labels.zip(values).map { (l, v) -> StatEntry(category, l, v) }
                    val existing = players[id]
                    players[id] = PlayerLine(
                        athleteId = id,
                        teamId = teamId,
                        name = athlete.str("displayName") ?: existing?.name ?: "",
                        headshot = athlete.obj("headshot").str("href") ?: existing?.headshot,
                        stats = (existing?.stats ?: emptyList()) + entries,
                        didNotPlay = a.bool("didNotPlay") == true && existing?.didNotPlay != false,
                    )
                }
            }
        }
        return BoxScore(gameId, players)
    }

    fun parseTeams(body: String): List<TeamInfo> {
        val root = json.parseToJsonElement(body)
        val teams = root.arr("sports").flatMap { it.arr("leagues") }.flatMap { it.arr("teams") }
        return teams.mapNotNull { wrapper ->
            val t = wrapper.obj("team") ?: wrapper as? JsonObject ?: return@mapNotNull null
            TeamInfo(
                id = t.str("id") ?: return@mapNotNull null,
                abbreviation = t.str("abbreviation") ?: "",
                displayName = t.str("displayName") ?: t.str("name") ?: "",
                shortName = t.str("shortDisplayName") ?: t.str("name") ?: "",
                logo = t.arr("logos").firstOrNull()?.str("href") ?: t.str("logo"),
                color = t.str("color"),
            )
        }.sortedBy { it.displayName }
    }

    /** Football rosters are grouped ({position, items: [...]}); other sports are a flat list. */
    fun parseRoster(body: String): List<AthleteInfo> {
        val root = json.parseToJsonElement(body)
        val flat = root.arr("athletes").flatMap { el -> if (el["items"] is JsonArray) el.arr("items") else listOf(el) }
        return flat.mapNotNull { a ->
            AthleteInfo(
                id = a.str("id") ?: return@mapNotNull null,
                name = a.str("displayName") ?: a.str("fullName") ?: return@mapNotNull null,
                position = a.obj("position").str("abbreviation"),
                jersey = a.str("jersey"),
                headshot = a.obj("headshot").str("href"),
            )
        }
    }
}

// ---- tiny null-safe JSON helpers ----

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal fun JsonElement?.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonElement?.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()
internal fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement?.str(key: String): String? = this[key].prim()
internal fun JsonElement?.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
internal fun JsonElement?.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
