package com.sportscc.core

import org.junit.Assert.assertEquals
import org.junit.Test

class KickerTest {
    private fun summary(fg: String, xp: String, plays: String) = """{
      "boxscore": {"players": [{"team": {"id": "33"}, "statistics": [
        {"name": "kicking", "labels": ["FG", "PCT", "LONG", "XP", "PTS"],
         "athletes": [{"athlete": {"id": "4686361", "displayName": "Spencer Shrader"}, "stats": ["$fg", "0", "0", "$xp", "0"]}]}
      ]}]},
      "scoringPlays": [$plays]
    }"""

    private val ppr = StatCatalog.fantasy(League.NFL)!!

    @Test
    fun extraPointCounts() {
        val box = EspnParser.parseSummary(summary("0/0", "1/1", ""), "g")
        assertEquals(1.0, ppr.evaluate(box.players.getValue("4686361"))!!, 1e-9)
    }

    @Test
    fun fieldGoalsByDistanceAndMisses() {
        val plays = """
          {"team": {"id": "33"}, "text": "Spencer Shrader 52 Yd Field Goal"},
          {"team": {"id": "33"}, "text": "S.Shrader 44 yd. Field Goal"},
          {"team": {"id": "33"}, "text": "Spencer Shrader 28 Yd Field Goal"},
          {"team": {"id": "12"}, "text": "Someone Else 55 Yd Field Goal"},
          {"team": {"id": "33"}, "text": "J.Doe 12 Yd Run (Spencer Shrader Kick)"}"""
        // 3 of 4 FG (52, 44, 28 yards), 2 of 3 XP: 5 + 4 + 3 - 1 (miss) + 2 - 1 (miss) = 12
        val box = EspnParser.parseSummary(summary("3/4", "2/3", plays), "g")
        assertEquals(12.0, ppr.evaluate(box.players.getValue("4686361"))!!, 1e-9)
    }
}
