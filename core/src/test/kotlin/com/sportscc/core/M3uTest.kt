package com.sportscc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {
    private val playlist = """
        ﻿#EXTM3U x-tvg-url="https://epg.example/guide.xml"
        #EXTINF:-1 tvg-id="espn.us" tvg-name="ESPN" tvg-logo="https://logos.example/espn.png" group-title="Sports",ESPN HD
        https://cdn.example/espn/index.m3u8
        #EXTINF:-1 tvg-logo="" group-title="News, World",BBC, One
        #EXTVLCOPT:http-user-agent=VLC/3.0
        #EXTVLCOPT:http-referrer=https://bbc.example/
        http://bbc.example/live.ts
        #EXTINF:0,Local
        #EXTGRP:Locals
        https://local.example/a.m3u8|User-Agent=Mozilla%2F5.0&Referer=https://local.example/

        #EXTINF:-1,Not a link
        just text
        #EXTINF:-1 tvg-name="Fallback Name",
        https://x.example/b.m3u8
    """.trimIndent()

    @Test
    fun parsesChannelsWithGroupsLogosAndHeaders() {
        val ch = M3u.parse(playlist)
        assertEquals(listOf("ESPN HD", "BBC, One", "Local", "Fallback Name"), ch.map { it.name })
        assertEquals("Sports", ch[0].group)
        assertEquals("https://logos.example/espn.png", ch[0].logo)
        assertEquals("https://cdn.example/espn/index.m3u8", ch[0].url)
        assertEquals("News, World", ch[1].group)
        assertNull(ch[1].logo)
        assertEquals(mapOf("User-Agent" to "VLC/3.0", "Referer" to "https://bbc.example/"), ch[1].headers)
        assertEquals("Locals", ch[2].group)
        assertEquals("https://local.example/a.m3u8", ch[2].url)
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://local.example/"), ch[2].headers)
        assertTrue(ch[3].headers.isEmpty())
    }

    @Test
    fun tellsHlsFromChannelLists() {
        val hls = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n"
        assertTrue(M3u.isHls(hls))
        assertFalse(M3u.isHls(playlist))
        assertEquals(LinkKind.PLAYLIST, M3u.classify(playlist))
        assertEquals(LinkKind.STREAM_OR_PLAYLIST, M3u.classify(hls))
    }

    @Test
    fun classifiesLinks() {
        assertEquals(LinkKind.WEB, M3u.classify("Bills Dolphins live"))
        assertEquals(LinkKind.WEB, M3u.classify("espn.com"))
        assertEquals(LinkKind.WEB, M3u.classify("https://www.example.com/watch?v=1"))
        assertEquals(LinkKind.STREAM_OR_PLAYLIST, M3u.classify("https://cdn.example/live/index.m3u8?token=abc"))
        assertEquals(LinkKind.STREAM_OR_PLAYLIST, M3u.classify("https://iptv.example/us.m3u"))
        assertEquals(LinkKind.STREAM, M3u.classify("https://cdn.example/manifest.mpd"))
        assertEquals(LinkKind.STREAM, M3u.classify("http://x.example/movie.mp4|Referer=https://x.example/"))
        assertEquals(LinkKind.PLAYLIST, M3u.classify("http://host:8080/get.php?username=a&password=b&type=m3u_plus&output=ts"))
        assertEquals(LinkKind.STREAM, M3u.classify("play:https://x.example/whatever"))
        assertEquals(LinkKind.PLAYLIST, M3u.classify("m3u:https://x.example/list"))
    }

    @Test
    fun singleLinkBecomesAChannel() {
        val c = M3u.single("play:https://cdn.example/live/game1.m3u8?t=1|User-Agent=VLC")
        assertEquals("https://cdn.example/live/game1.m3u8?t=1", c.url)
        assertEquals("game1.m3u8", c.name)
        assertEquals(mapOf("User-Agent" to "VLC"), c.headers)
        assertEquals(c, M3u.single(M3u.link(c), "game1.m3u8"))
        assertEquals("Sunday", M3u.title("#EXTM3U\n#PLAYLIST:Sunday\n#EXTINF:-1,A\nhttp://a/b.ts"))
        assertNull(M3u.title(playlist))
    }

    @Test
    fun relayAttachmentsAreRead() {
        val m = Relay.parseLatest(
            """{"id":"a1","time":5,"event":"message","attachment":{"name":"list.m3u","url":"https://ntfy.sh/file/a1.m3u"}}""",
        )!!
        assertEquals("", m.message)
        assertEquals("https://ntfy.sh/file/a1.m3u", m.attachmentUrl)
    }
}
