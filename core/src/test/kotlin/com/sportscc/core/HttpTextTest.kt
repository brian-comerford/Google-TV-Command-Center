package com.sportscc.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream

class HttpTextTest {
    @Test
    fun streamsAGzippedRedirectedPlaylistKeepingLiveChannels() {
        // A big IPTV-style list: 30,000 movies and 50 live channels, gzipped, behind a redirect.
        val text = buildString {
            append("#EXTM3U\n")
            repeat(50) { append("#EXTINF:-1 group-title=\"Sports\",Live $it\nhttp://h/u/p/$it.ts\n") }
            repeat(30_000) { append("#EXTINF:-1 group-title=\"VOD: Movies\",Movie $it\nhttp://h/movie/u/p/$it.mkv\n") }
        }
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
        var agent: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/get.php") { ex ->
            ex.responseHeaders.add("Location", "/real.m3u"); ex.sendResponseHeaders(302, -1); ex.close()
        }
        server.createContext("/real.m3u") { ex ->
            agent = ex.requestHeaders.getFirst("User-Agent")
            ex.sendResponseHeaders(200, gz.size.toLong()); ex.responseBody.use { it.write(gz) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/get.php?username=x&password=y&type=m3u_plus"
            val conn = HttpText.open(url, mapOf("User-Agent" to HttpText.PLAYER_AGENT), 10_000)
            val out = java.io.StringWriter()
            val counts = HttpText.unzipping(conn.inputStream).bufferedReader().useLines { M3u.copyLive(it, out) }
            conn.disconnect()
            assertEquals(50 to 30_000, counts)
            assertEquals(50, M3u.parse(out.toString()).size)
            assertEquals(HttpText.PLAYER_AGENT, agent)
            assertTrue(HttpText.peek(url, emptyMap(), bytes = 100).startsWith("#EXTM3U"))
        } finally {
            server.stop(0)
        }
    }

    @Test(expected = HttpStatusException::class)
    fun refusedRequestsSayTheStatus() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> ex.sendResponseHeaders(403, -1); ex.close() }
        server.start()
        try {
            HttpText.get("http://127.0.0.1:${server.address.port}/x.m3u")
        } finally {
            server.stop(0)
        }
    }
}
