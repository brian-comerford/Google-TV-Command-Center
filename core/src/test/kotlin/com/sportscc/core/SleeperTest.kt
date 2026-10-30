package com.sportscc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleeperTest {
    private val rosters = """[
        {"roster_id":1,"owner_id":"111","starters":["4046","0","6794"],"players":["4046","6794","9999"]},
        {"roster_id":2,"owner_id":"222","co_owners":["333"],"starters":["1234"],"players":["1234","5678"]}
    ]"""
    private val map = SleeperLineup.parseMap(
        """{"4046":["3139477","12","KC","Patrick Mahomes","QB","https://x/pm.png"],
            "6794":["4362628","4","CIN","Ja'Marr Chase","WR",null],
            "bad":[null,"1"]}""",
    )

    @Test
    fun findsYourStartersOrRoster() {
        assertEquals(listOf("4046", "6794"), SleeperLineup.playerIds(rosters, "111", startersOnly = true))
        assertEquals(listOf("4046", "6794", "9999"), SleeperLineup.playerIds(rosters, "111", startersOnly = false))
        assertEquals(listOf("1234"), SleeperLineup.playerIds(rosters, "333", startersOnly = true)) // co-owner
        assertNull(SleeperLineup.playerIds(rosters, "nobody", true))
        assertNull(SleeperLineup.playerIds("{\"error\":1}", "111", true))
    }

    @Test
    fun readsTheLookupFile() {
        assertEquals(2, map.size)
        val pm = map.getValue("4046")
        assertEquals("3139477", pm.athleteId)
        assertEquals("12", pm.teamId)
        assertEquals("Patrick Mahomes", pm.name)
        val w = SleeperLineup.toWatch(pm)
        assertTrue(w.fantasy)
        assertEquals("sleeper", w.source)
    }

    @Test
    fun mergeSwapsOnlySleeperPlayers() {
        val mahomes = SleeperLineup.toWatch(map.getValue("4046"))
        val chase = SleeperLineup.toWatch(map.getValue("6794"))
        val oldSleeper = mahomes.copy(athleteId = "1", name = "Benched Guy")
        val mine = PlayerWatch(League.NFL, "4362628", "Ja'Marr Chase", "4", "CIN", props = listOf(Prop("rec_yds", 74.5)))
        val team = TeamWatch(League.NFL, "2", "BUF", "Buffalo Bills")
        val m = SleeperLineup.merge(listOf(team, oldSleeper, mine), listOf(mahomes, chase))
        assertEquals(listOf(oldSleeper), m.removed)
        assertEquals(listOf(mahomes), m.added) // Chase is already on the list as a bet: left alone
        assertEquals(listOf(team.key, mine.key, mahomes.key), m.items.map { it.key })
        assertEquals(mine, m.items[1])
        assertFalse(SleeperLineup.merge(m.items, listOf(mahomes, chase)).changed)
    }

    @Test
    fun linkTravelsWithTheList() {
        val p = SyncPayload(items = emptyList(), sentAt = 1, sleeper = SleeperLink("111", "999", "Dynasty", "me"))
        assertEquals(p.sleeper, SyncCodec.decode(SyncCodec.encode(p)).sleeper)
        // Older phones/TVs leave it out.
        assertNull(SyncCodec.decode("""{"v":1,"items":[]}""").sleeper)
    }
}
