package com.sportscc.core

import com.sportscc.core.Fixtures.Ath
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    @Test
    fun parsesScoreboard() {
        val body = Fixtures.scoreboard(
            Fixtures.event("401", "in", "2", "BUF", 14, "15", "MIA", 7, period = 2, clock = "4:12",
                lastPlay = "J.Allen pass to K.Shakir for 22 yds", downDistance = "1st & 10 at MIA 30"),
        )
        val g = EspnParser.parseScoreboard(body, League.NFL).single()
        assertEquals(GameState.IN, g.state)
        assertEquals("MIA", g.home.abbreviation)
        assertEquals(14, g.away.score)
        assertEquals("BUF 14 – 7 MIA", g.scoreLine)
        assertEquals("1st & 10 at MIA 30", g.situation)
        assertEquals("CBS", g.broadcast)
        assertEquals("5-2", g.home.record)
    }

    @Test
    fun parsesFootballBoxScore() {
        val box = EspnParser.parseSummary(
            Fixtures.summaryFootball(
                "2",
                passing = listOf(Ath("3918298", "Josh Allen", listOf("18/27", "245", "9.1", "2", "1", "1-6", "70", "101.2"))),
                rushing = listOf(Ath("3918298", "Josh Allen", listOf("6", "41", "6.8", "1", "15"))),
            ),
            "401",
        )
        val allen = box.players.getValue("3918298")
        assertEquals("2", allen.teamId)
        assertEquals(245.0, allen.find("passing", "YDS")!!.value)
        assertEquals(41.0, allen.find("rushing", "YDS")!!.value)
        assertEquals(18.0, allen.find("passing", "C/ATT")!!.value)
        // PPR: 245*.04 + 2*4 - 2 + 41*.1 + 6 = 9.8 + 8 - 2 + 4.1 + 6
        assertEquals(25.9, StatCatalog.find(League.NFL, "fpts_ppr")!!.evaluate(allen)!!, 1e-9)
        assertEquals(1.0, StatCatalog.find(League.NFL, "any_td")!!.evaluate(allen)!!, 1e-9)
        assertEquals(27.0, StatCatalog.find(League.NFL, "pass_att")!!.evaluate(allen)!!, 1e-9)
    }

    @Test
    fun parsesBasketballBoxScore() {
        val box = EspnParser.parseSummary(
            Fixtures.summaryBasketball("13", Ath("1966", "LeBron James", listOf("30", "9-17", "2-5", "4-4", "1", "6", "7", "8", "1", "0", "3", "2", "+5", "24"))),
            "500",
        )
        val lbj = box.players.getValue("1966")
        assertEquals(39.0, StatCatalog.find(League.NBA, "pra")!!.evaluate(lbj)!!, 1e-9)
        assertEquals(2.0, StatCatalog.find(League.NBA, "threes")!!.evaluate(lbj)!!, 1e-9)
        assertTrue(box.players.getValue("999").didNotPlay)
        assertNull(StatCatalog.find(League.NBA, "pts")!!.evaluate(box.players["999"]))
    }

    @Test
    fun parsesGroupedAndFlatRosters() {
        val grouped = """{"athletes":[{"position":"offense","items":[{"id":"1","displayName":"A One","jersey":"17","position":{"abbreviation":"QB"}}]},
                          {"position":"defense","items":[{"id":"2","fullName":"B Two"}]}]}"""
        assertEquals(listOf("A One", "B Two"), EspnParser.parseRoster(grouped).map { it.name })
        val flat = """{"athletes":[{"id":"9","displayName":"C Three","headshot":{"href":"x"}}]}"""
        assertEquals("x", EspnParser.parseRoster(flat).single().headshot)
    }

    @Test
    fun parsesTeams() {
        val body = """{"sports":[{"leagues":[{"teams":[
            {"team":{"id":"2","abbreviation":"BUF","displayName":"Buffalo Bills","shortDisplayName":"Bills","logos":[{"href":"l.png"}]}},
            {"team":{"id":"1","abbreviation":"ATL","displayName":"Atlanta Falcons","shortDisplayName":"Falcons"}}]}]}]}"""
        val teams = EspnParser.parseTeams(body)
        assertEquals(listOf("ATL", "BUF"), teams.map { it.abbreviation })
        assertEquals("l.png", teams[1].logo)
    }

    @Test
    fun statValues() {
        assertEquals(3.0, parseStatValue("3-7")!!, 0.0)
        assertEquals(-3.0, parseStatValue("-3")!!, 0.0)
        assertEquals(5.1, parseStatValue("5.1")!!, 0.0)
        assertNull(parseStatValue("--"))
    }

    @Test
    fun watchlistRoundTripsThroughJson() {
        val list: List<WatchItem> = listOf(
            TeamWatch(League.NFL, "2", "BUF", "Buffalo Bills"),
            PlayerWatch(League.NBA, "1966", "LeBron James", "13", "LAL", props = listOf(Prop("pts", 24.5))),
        )
        val json = Json.encodeToString(list)
        assertEquals(list, Json.decodeFromString<List<WatchItem>>(json))
    }
}

class FantasyTest {
    @Test
    fun fantasyPlayersShowScoreBeforeStatsAndGetPointsInAlerts() {
        val w = PlayerWatch(League.NFL, "3918298", "Josh Allen", "2", "BUF", fantasy = true)
        fun g(state: String) = EspnParser.parseScoreboard(Fixtures.scoreboard(Fixtures.event("401", state, "2", "BUF", 0, "15", "MIA", 0)), League.NFL)
        fun box(yds: Int, td: Int) = EspnParser.parseSummary(Fixtures.summaryFootball("2",
            passing = listOf(Fixtures.Ath("3918298", "Josh Allen", listOf("18/27", "$yds", "9.1", "$td", "0", "0-0", "70", "101.2")))), "401")

        val pre = Snapshot(g("pre").associateBy { it.id }, emptyMap())
        val card = Tracker.buildDashboard(listOf(w), pre, 0, emptyList()).cards.single() as PlayerCard
        assertEquals("PPR" to 0.0, card.fantasy)

        val a = Snapshot(g("in").associateBy { it.id }, mapOf("401" to box(200, 1)))
        val b = Snapshot(g("in").associateBy { it.id }, mapOf("401" to box(225, 2)))
        val alert = AlertEngine.diff(a, b, listOf(w), 1).single()
        assertEquals("Josh Allen: +25 Pass YDS, +1 Pass TD (+5 PPR)", alert.title)
        assertTrue(alert.detail!!, alert.detail!!.startsWith("17 PPR · "))
        assertFalse(StatCatalog.propDefs(League.NFL).any { it.id.startsWith("fpts") })
    }
}

class TrackerTest {
    private val bills = TeamWatch(League.NFL, "2", "BUF", "Buffalo Bills")
    private val allen = PlayerWatch(
        League.NFL, "3918298", "Josh Allen", "2", "BUF",
        props = listOf(Prop("pass_yds", 249.5), Prop("pass_int", 0.5, Prop.Side.UNDER)),
    )

    private class FakeEspn {
        var scoreboard = ""
        var summary = ""
        var summaryCalls = 0
        val client = EspnClient({ url ->
            when {
                "scoreboard" in url -> scoreboard
                "summary" in url -> { summaryCalls++; summary }
                else -> error("unexpected $url")
            }
        })
    }

    private fun allenBox(yds: Int, td: Int, int: Int) = Fixtures.summaryFootball(
        "2", passing = listOf(Ath("3918298", "Josh Allen", listOf("18/27", "$yds", "9.1", "$td", "$int", "0-0", "70", "101.2"))),
    )

    @Test
    fun firstPollIsBaselineThenAlertsOnChanges() = runTest {
        val fake = FakeEspn()
        var t = 1000L
        val tracker = Tracker(fake.client) { t }
        val watch = listOf(bills, allen)

        fake.scoreboard = Fixtures.scoreboard(Fixtures.event("401", "pre", "2", "BUF", 0, "15", "MIA", 0))
        val r0 = tracker.refresh(watch)
        assertTrue(r0.alerts.isEmpty())
        assertEquals(0, fake.summaryCalls) // no box score before kickoff
        assertNull((r0.dashboard.cards.first { it is PlayerCard } as PlayerCard).line)

        fake.scoreboard = Fixtures.scoreboard(Fixtures.event("401", "in", "2", "BUF", 0, "15", "MIA", 0))
        fake.summary = allenBox(10, 0, 0)
        val r1 = tracker.refresh(watch)
        assertEquals(listOf("Game on: BUF @ MIA"), r1.alerts.map { it.title })
        assertTrue(r1.dashboard.anyLive)

        t = 2000L
        fake.scoreboard = Fixtures.scoreboard(
            Fixtures.event("401", "in", "2", "BUF", 7, "15", "MIA", 0, lastPlay = "Allen 40 yd pass to Cook, TD"),
        )
        fake.summary = allenBox(260, 1, 0)
        val r2 = tracker.refresh(watch)
        val titles = r2.alerts.map { it.title }
        assertTrue(titles.toString(), "BUF TOUCHDOWN" in titles)
        assertTrue(titles.toString(), "Josh Allen: +250 Pass YDS, +1 Pass TD" in titles)
        assertTrue(titles.toString(), "PROP HIT: Josh Allen over 249.5 Pass Yds" in titles)
        assertTrue(r2.alerts.first { it.title == "BUF TOUCHDOWN" }.big)
        // The stat update carries every prop's progress for the pop-up's bars.
        val statPop = r2.alerts.first { it.title.startsWith("Josh Allen:") }
        assertEquals(listOf("pass_yds" to 260.0, "pass_int" to 0.0), statPop.props.map { it.def.id to it.current })
        assertTrue(statPop.props.all { it.winning })
        assertTrue(r2.alerts.first { it.title == "BUF TOUCHDOWN" }.props.isEmpty())

        val card = r2.dashboard.cards.first { it is PlayerCard } as PlayerCard
        val yds = card.props.first { it.def.id == "pass_yds" }
        assertEquals(260.0, yds.current, 0.0)
        assertTrue(yds.winning)
        assertEquals("PPR", card.fantasy!!.first)

        fake.scoreboard = Fixtures.scoreboard(Fixtures.event("401", "post", "2", "BUF", 7, "15", "MIA", 3, shortDetail = "Final"))
        fake.summary = allenBox(260, 1, 1)
        val r3 = tracker.refresh(watch)
        val t3 = r3.alerts.map { it.title }
        assertTrue(t3.toString(), "FINAL: BUF 7 – 3 MIA" in t3)
        assertTrue(t3.toString(), "Prop busted: Josh Allen under 0.5 INT" in t3)
        assertTrue(t3.toString(), "PROP CASHED: Josh Allen over 249.5 Pass Yds" in t3)
        assertTrue(t3.toString(), "Prop lost: Josh Allen under 0.5 INT" in t3)

        // Finished games' box scores are cached.
        val calls = fake.summaryCalls
        tracker.refresh(watch)
        assertEquals(calls, fake.summaryCalls)
    }

    @Test
    fun failedScoreboardKeepsLastKnownGame() = runTest {
        var fail = false
        val sb = Fixtures.scoreboard(Fixtures.event("401", "pre", "2", "BUF", 0, "15", "MIA", 0))
        val tracker = Tracker(EspnClient({ if (fail) throw java.io.IOException("offline") else sb }))
        tracker.refresh(listOf(bills))
        fail = true
        val r = tracker.refresh(listOf(bills))
        assertNotNull(r.dashboard.cards.single().game)
        assertEquals(1, r.dashboard.errors.size)
    }

    @Test
    fun basketballOnlyAlertsLeadChanges() {
        fun g(away: Int, home: Int, period: Int = 1) = EspnParser.parseEvent(
            kotlinx.serialization.json.Json.parseToJsonElement(Fixtures.event("9", "in", "13", "LAL", away, "2", "BOS", home, period = period)),
            League.NBA,
        )!!
        val lakers = TeamWatch(League.NBA, "13", "LAL", "Lakers")
        val prev = Snapshot(mapOf("9" to g(10, 8)), emptyMap())
        assertTrue(AlertEngine.diff(prev, Snapshot(mapOf("9" to g(12, 8)), emptyMap()), listOf(lakers), 1).isEmpty())
        val lead = AlertEngine.diff(prev, Snapshot(mapOf("9" to g(10, 11)), emptyMap()), listOf(lakers), 1)
        assertEquals("Lead change: BOS up 1", lead.single().title)
        val q = AlertEngine.diff(prev, Snapshot(mapOf("9" to g(12, 9, period = 2)), emptyMap()), listOf(lakers), 1)
        assertEquals("End of Q1", q.single().title)
        assertFalse(q.single().big)
    }
}

class OrderTest {
    private fun card(key: String, state: GameState?): Card {
        val g = state?.let {
            val c = Competitor(key, key, key, key, null, null, 0, null, true)
            Game("g$key", League.NBA, "", it, "", "", 1, "", c, c.copy(isHome = false))
        }
        return TeamCard(TeamWatch(League.NBA, key, key, key), g)
    }

    @Test
    fun myOrderKeepsWatchlistOrderAndLiveFirstGroups() {
        val cards = listOf(card("a", GameState.POST), card("b", null), card("c", GameState.IN), card("d", GameState.PRE), card("e", GameState.IN))
        assertEquals(listOf("a", "b", "c", "d", "e"), orderCards(cards, liveFirst = false).map { (it.watch as TeamWatch).teamId })
        assertEquals(listOf("c", "e", "d", "a", "b"), orderCards(cards, liveFirst = true).map { (it.watch as TeamWatch).teamId })
    }

    @Test
    fun movesItems() {
        val l = listOf(1, 2, 3, 4, 5)
        assertEquals(listOf(2, 1, 3, 4, 5), l.moved(0, 1))
        assertEquals(listOf(1, 2, 4, 5, 3), l.moved(2, 2)) // down one row in a 2-column grid
        assertEquals(listOf(5, 1, 2, 3, 4), l.moved(4, -10)) // clamped to the start
        assertEquals(l, l.moved(0, -1))
    }
}
