package com.sportscc.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Your team in a Sleeper fantasy league (set on the phone site). */
@Serializable
data class SleeperLink(
    val userId: String,
    val leagueId: String,
    val leagueName: String = "",
    val username: String = "",
    /** Only this week's starters, or everyone on the roster. */
    val startersOnly: Boolean = true,
)

/** A Sleeper player matched to ESPN, from the phone site's data/NFL/sleeper-players.json. */
data class SleeperPlayer(
    val athleteId: String,
    val teamId: String,
    val teamAbbreviation: String,
    val name: String,
    val position: String?,
    val headshot: String?,
)

/**
 * Keeps the fantasy players on your list matched to your Sleeper lineup. Sleeper's API is public
 * (api.sleeper.app, no sign-in). Its players carry ESPN ids, which tools/build-web-data.js turns
 * into a small lookup file next to the phone site, so the TV doesn't download Sleeper's 5 MB list.
 */
object SleeperLineup {
    const val SOURCE = "sleeper"
    private val json = Json { ignoreUnknownKeys = true }

    fun rostersUrl(leagueId: String) = "https://api.sleeper.app/v1/league/$leagueId/rosters"

    /**
     * Sleeper player ids on [userId]'s team in a league's rosters response
     * (GET /v1/league/<id>/rosters). Empty starter slots are "0". Null if the user has no team there.
     */
    fun playerIds(rostersJson: String, userId: String, startersOnly: Boolean): List<String>? {
        val rosters = runCatching { json.parseToJsonElement(rostersJson) as? JsonArray }.getOrNull() ?: return null
        val mine = rosters.mapNotNull { it as? JsonObject }.firstOrNull { r ->
            r.text("owner_id") == userId || (r["co_owners"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == userId } == true
        } ?: return null
        val ids = (if (startersOnly) mine["starters"] else mine["players"]) as? JsonArray ?: return emptyList()
        return ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() && it != "0" }.distinct()
    }

    /** The lookup file: {"<sleeper id>": [espnAthleteId, espnTeamId, teamAbbr, name, position, headshot], ...}. */
    fun parseMap(text: String): Map<String, SleeperPlayer> {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        return o.mapNotNull { (id, v) ->
            val a = v as? JsonArray ?: return@mapNotNull null
            fun at(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            val athlete = at(0) ?: return@mapNotNull null
            val team = at(1) ?: return@mapNotNull null
            id to SleeperPlayer(athlete, team, at(2) ?: "", at(3) ?: "", at(4), at(5))
        }.toMap()
    }

    fun toWatch(p: SleeperPlayer) = PlayerWatch(
        league = League.NFL, athleteId = p.athleteId, name = p.name, teamId = p.teamId,
        teamAbbreviation = p.teamAbbreviation, position = p.position, headshot = p.headshot,
        fantasy = true, source = SOURCE,
    )

    data class Merge(val items: List<WatchItem>, val added: List<PlayerWatch>, val removed: List<PlayerWatch>) {
        val changed get() = added.isNotEmpty() || removed.isNotEmpty()
    }

    /**
     * Your list with the Sleeper players swapped for [lineup]: players that left the lineup go (only
     * ones that came from Sleeper), new ones are added at the end, and anything you added yourself
     * stays as it is, even if it's the same player.
     */
    fun merge(items: List<WatchItem>, lineup: List<PlayerWatch>): Merge {
        val wanted = lineup.associateBy { it.key }
        val removed = items.filterIsInstance<PlayerWatch>().filter { it.source == SOURCE && it.key !in wanted }
        val kept = items.filterNot { it is PlayerWatch && it.source == SOURCE && it.key !in wanted }
        val present = kept.map { it.key }.toSet()
        val added = lineup.filter { it.key !in present }
        return Merge(kept + added, added, removed)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
