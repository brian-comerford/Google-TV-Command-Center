package com.sportscc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhoneSyncTest {
    private val items = listOf(
        TeamWatch(League.NFL, "2", "BUF", "Buffalo Bills", "https://a.espncdn.com/i/teamlogos/nfl/500/buf.png"),
        PlayerWatch(League.NFL, "4362628", "Ja'Marr Chase", "4", "CIN", "WR", "1", props = listOf(Prop("rec_yds", 74.5))),
    )

    @Test
    fun roundTripsCompressed() {
        val text = SyncCodec.encode(SyncPayload(items = items, sentAt = 5))
        assertTrue(text.startsWith("z1:"))
        assertEquals(items, SyncCodec.decode(text).items)
    }

    @Test
    fun readsJsonShapedLikeTheWebsiteSendsIt() {
        // Exactly what web/app.js builds: "type" discriminator, enum names, optional fields omitted or null.
        val web = """{"v":1,"sentAt":1761600000000,"items":[
            {"type":"team","league":"NBA","teamId":"13","abbreviation":"LAL","name":"Los Angeles Lakers","logo":null,"extra":"ignored"},
            {"type":"player","league":"NBA","athleteId":"1966","name":"LeBron James","teamId":"13","teamAbbreviation":"LAL",
             "props":[{"statId":"pts","line":24.5,"side":"UNDER"}]}]}"""
        val p = SyncCodec.decode(web)
        assertEquals("LAL", (p.items[0] as TeamWatch).abbreviation)
        val lbj = p.items[1] as PlayerWatch
        assertEquals(Prop.Side.UNDER, lbj.props.single().side)
        assertNull(lbj.position)
    }

    @Test
    fun decodesMessageProducedByTheWebsite() {
        // Captured from web/app.js running in Chromium (gzip via CompressionStream).
        val text = javaClass.getResource("/web-payload.txt")!!.readText()
        val p = SyncCodec.decode(text)
        val chase = p.items[0] as PlayerWatch
        assertEquals("4362628", chase.athleteId)
        assertEquals(Prop("rec_yds", 74.5, Prop.Side.OVER), chase.props.single())
        assertEquals("BUF", (p.items[1] as TeamWatch).abbreviation)
    }

    @Test
    fun pairingCodes() {
        val code = PairingCode.generate(Random(1))
        assertTrue(code, Regex("^[2-9A-Z]{4}-[2-9A-Z]{4}$").matches(code))
        assertEquals("ABCD2345", PairingCode.normalize(" abcd-2345 "))
        assertEquals("scc-tv-abcd2345-in", PairingCode.inboxTopic("abcd-2345"))
    }

    @Test
    fun parsesNtfyPollResponse() {
        val body = """
            {"id":"a1","time":100,"event":"open","topic":"t"}
            {"id":"m1","time":101,"event":"message","topic":"t","message":"first"}
            {"id":"m2","time":102,"event":"message","topic":"t","message":"second"}
        """.trimIndent()
        assertEquals(RelayMessage("m2", 102, "second"), Relay.parseLatest(body))
        assertNull(Relay.parseLatest(""))
    }
}
