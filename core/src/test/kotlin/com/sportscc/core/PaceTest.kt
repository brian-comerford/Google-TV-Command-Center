package com.sportscc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceTest {
    private val c = Competitor("1", "BUF", "Buffalo Bills", "Bills", null, null, 0, null, true)
    private fun game(league: League, period: Int, clock: String, state: GameState = GameState.IN, short: String = "") =
        Game("g", league, "", state, "", short, period, clock, c, c.copy(teamId = "2", isHome = false))

    @Test
    fun elapsedByLeague() {
        assertEquals(0.5, Pace.elapsed(game(League.NFL, 3, "15:00"))!!, 1e-9) // start of the 3rd
        assertEquals(0.5, Pace.elapsed(game(League.NFL, 2, "0:00"))!!, 1e-9) // halftime
        assertEquals((30 + 6.3) / 60, Pace.elapsed(game(League.NFL, 3, "8:42"))!!, 1e-9)
        assertEquals(0.75, Pace.elapsed(game(League.NBA, 4, "12:00"))!!, 1e-9)
        assertEquals(0.5, Pace.elapsed(game(League.NCAAM, 2, "20:00"))!!, 1e-9) // halves
        assertEquals(1.0 / 3, Pace.elapsed(game(League.NHL, 2, "20:00"))!!, 1e-9)
        assertEquals(1.0, Pace.elapsed(game(League.NFL, 5, "6:00"))!!, 1e-9) // overtime
        assertEquals(4.25 / 9, Pace.elapsed(game(League.MLB, 5, "", short = "Top 5th"))!!, 1e-9)
        assertEquals(5.0 / 9, Pace.elapsed(game(League.MLB, 5, "", short = "End 5th"))!!, 1e-9)
        assertNull(Pace.elapsed(game(League.NFL, 1, "15:00", GameState.PRE)))
        assertNull(Pace.elapsed(game(League.EPL, 1, "30'")))
        assertEquals(0.755, Pace.clockMinutes("45.3")!!, 1e-9)
    }

    @Test
    fun projectsOnlyOnceTheGameIsUnderWay() {
        assertNull(Pace.project(40.0, game(League.NFL, 1, "12:00"))) // 5% in: too early
        assertEquals(140.0, Pace.project(70.0, game(League.NFL, 3, "15:00"))!!, 1e-9)
        assertNull(Pace.project(70.0, game(League.NFL, 4, "0:00", GameState.POST)))
        assertNull(Pace.project(70.0, null))
    }

    @Test
    fun propProgressCarriesThePace() {
        val g = game(League.NFL, 3, "15:00")
        val over = PropProgress(Prop("rec_yds", 74.5), StatCatalog.find(League.NFL, "rec_yds")!!, 40.0, Pace.project(40.0, g))
        assertEquals(80.0, over.projected!!, 1e-9)
        assertTrue(over.paceWinning!!)
        val under = over.copy(prop = Prop("rec_yds", 74.5, Prop.Side.UNDER))
        assertFalse(under.paceWinning!!)
    }
}
