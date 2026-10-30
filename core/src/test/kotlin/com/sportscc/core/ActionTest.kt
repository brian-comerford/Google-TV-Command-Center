package com.sportscc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionTest {
    private val buf = Competitor("2", "BUF", "Buffalo Bills", "Bills", null, null, 17, null, false)
    private val mia = Competitor("15", "MIA", "Miami Dolphins", "Dolphins", null, null, 17, null, true)
    private fun nfl(id: String = "g1", period: Int = 3, clock: String = "8:42", yards: Int? = null, redZone: Boolean = false, situation: String? = null,
                    homeScore: Int = 17, awayScore: Int = 17) =
        Game(id, League.NFL, "", GameState.IN, "", "", period, clock, mia.copy(score = homeScore), buf.copy(score = awayScore),
            situation = situation, possessionTeamId = "2", redZone = redZone, yardsToGoal = yards)

    @Test
    fun footballFieldPosition() {
        assertEquals(Interest(Heat.HOT, "BUF in the red zone (MIA 12)"), Action.interest(nfl(yards = 12, situation = "1st & 10 at MIA 12")))
        assertEquals(Heat.HOT, Action.interest(nfl(redZone = true)).heat)
        assertEquals(Interest(Heat.WARM, "BUF driving (MIA 34)"), Action.interest(nfl(yards = 34, situation = "2nd & 7 at MIA 34")))
        assertEquals(Heat.NONE, Action.interest(nfl(yards = 75)).heat)
        assertEquals(Heat.HOT, Action.interest(nfl(period = 4, clock = "1:48", yards = 75, homeScore = 20)).heat) // close finish
        assertEquals(Heat.NONE, Action.interest(nfl(period = 4, clock = "1:48", yards = 75, homeScore = 38)).heat) // blowout
        assertEquals(Heat.NONE, Action.interest(nfl(yards = 5).copy(state = GameState.POST)).heat)
    }

    @Test
    fun otherSports() {
        val c = Competitor("1", "LAL", "Lakers", "Lakers", null, null, 100, null, true)
        fun nba(period: Int, clock: String, home: Int, away: Int) =
            Game("n", League.NBA, "", GameState.IN, "", "", period, clock, c.copy(score = home), c.copy(teamId = "2", abbreviation = "BOS", score = away, isHome = false))
        assertEquals(Heat.HOT, Action.interest(nba(4, "1:30", 101, 99)).heat)
        assertEquals(Heat.WARM, Action.interest(nba(4, "4:10", 101, 93)).heat)
        assertEquals(Heat.NONE, Action.interest(nba(3, "1:30", 101, 99)).heat)
        assertEquals(Heat.HOT, Action.interest(nba(5, "3:00", 110, 108)).heat) // overtime
        val mlb = Game("m", League.MLB, "", GameState.IN, "", "Bot 9th", 9, "", c.copy(score = 3), c.copy(teamId = "2", score = 2, isHome = false))
        assertEquals(Heat.HOT, Action.interest(mlb).heat)
    }

    @Test
    fun picksTheHottestOtherGame() {
        val warm = nfl("a", yards = 34)
        val hot = nfl("b", yards = 8)
        val quiet = nfl("c", yards = 80)
        assertEquals("b", Action.pick(listOf(quiet, warm, hot), currentGameId = null)!!.first.id)
        assertEquals("a", Action.pick(listOf(quiet, warm, hot), currentGameId = "b")!!.first.id) // already watching b
        assertNull(Action.pick(listOf(quiet), currentGameId = null))
    }
}
