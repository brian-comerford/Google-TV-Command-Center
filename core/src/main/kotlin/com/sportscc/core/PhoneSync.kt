package com.sportscc.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** What the phone website and the TV send each other: the whole watchlist. */
@Serializable
data class SyncPayload(val v: Int = 1, val items: List<WatchItem>, val sentAt: Long = 0)

/**
 * Wire format shared with web/app.js: "z1:" + base64(gzip(json)). Plain JSON is accepted too.
 * Gzip keeps a big watchlist under the relay's 4 KB message limit.
 */
object SyncCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(payload: SyncPayload): String {
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.encodeToString(payload).toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        return PREFIX + Base64.getEncoder().encodeToString(bytes)
    }

    fun decode(text: String): SyncPayload {
        val t = text.trim()
        val raw = if (t.startsWith(PREFIX)) {
            GZIPInputStream(Base64.getDecoder().decode(t.removePrefix(PREFIX)).inputStream()).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } else t
        return json.decodeFromString(raw)
    }

    private const val PREFIX = "z1:"
}

/** A TV's pairing code, shown as "ABCD-EFGH". No 0/O/1/I/L so it's easy to read off a TV. */
object PairingCode {
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    fun generate(random: Random = Random.Default): String =
        (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").let { "${it.take(4)}-${it.drop(4)}" }

    fun normalize(code: String): String = code.uppercase().filter { it in ALPHABET }

    /** Phone -> TV channel. */
    fun inboxTopic(code: String) = "scc-tv-${normalize(code).lowercase()}-in"

    /** TV -> phone channel (so the website can load what's already on the TV). */
    fun stateTopic(code: String) = "scc-tv-${normalize(code).lowercase()}-tv"

    /** Phone -> TV links to open in the TV's built-in browser (plain text: a URL or search words). */
    fun openTopic(code: String) = "scc-tv-${normalize(code).lowercase()}-open"
}

/** [attachmentUrl] is set when the sender posted a file (e.g. a playlist) instead of a short message. */
data class RelayMessage(val id: String, val time: Long, val message: String, val attachmentUrl: String? = null)

/** Minimal client for ntfy.sh, a free public pub/sub relay (messages are kept for 12 hours). */
class Relay(private val base: String = "https://ntfy.sh", private val timeoutMs: Int = 10_000) {

    suspend fun publish(topic: String, body: String) = withContext(Dispatchers.IO) {
        val conn = URL("$base/$topic").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Relay HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Newest message on [topic] after [sinceId] (or from the last 12 hours), or null.
     * [since] overrides that: a message id, a duration like "12h", or a Unix time in seconds.
     */
    suspend fun latest(topic: String, sinceId: String?, since: String = sinceId ?: "12h"): RelayMessage? = withContext(Dispatchers.IO) {
        val conn = URL("$base/$topic/json?poll=1&since=$since").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Relay HTTP $code")
            parseLatest(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads a text file, e.g. a message's attachment or a playlist; refuses anything over [maxBytes]. */
    suspend fun fetchText(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 20_000_000): String =
        withContext(Dispatchers.IO) { HttpText.get(url, headers, maxBytes, timeoutMs * 3) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** ntfy's poll response is one JSON object per line; keep the last "message" event. */
        fun parseLatest(ndjson: String): RelayMessage? = ndjson.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            .filter { it.str("event") == "message" }
            .mapNotNull { o ->
                val attachment = (o["attachment"] as? JsonObject)?.str("url")
                RelayMessage(
                    id = o.str("id") ?: return@mapNotNull null,
                    time = o["time"]?.jsonPrimitive?.longOrNull ?: 0L,
                    message = o.str("message") ?: if (attachment != null) "" else return@mapNotNull null,
                    attachmentUrl = attachment,
                )
            }
            .lastOrNull()
    }
}

/**
 * The same messages go through several relays at once, so phone sync keeps working when one of them
 * is down or can't be reached from your network. ntfy.sh is the official server; the others are
 * community-run ntfy servers (same API). The phone website (web/app.js) uses the same list.
 */
class Relays(val bases: List<String> = DEFAULT, timeoutMs: Int = 10_000) {
    private val relays = bases.map { it to Relay(it, timeoutMs) }

    /** Posts to every relay; fine as long as one of them took it. Returns how many did. */
    suspend fun publish(topic: String, body: String): Int = coroutineScope {
        val results = relays.map { (_, r) -> async { runCatching { r.publish(topic, body) } } }.awaitAll()
        val ok = results.count { it.isSuccess }
        if (ok == 0) throw results.first().exceptionOrNull() ?: IOException("No relay answered")
        ok
    }

    /**
     * The newest message on each relay that has one, as (relay, message). [since] gives each relay's
     * "since" value (its last message id, a duration or a Unix time). Throws only if no relay answered.
     */
    suspend fun poll(topic: String, since: (String) -> String): List<Pair<String, RelayMessage>> = coroutineScope {
        val results = relays.map { (base, r) -> async { base to runCatching { r.latest(topic, null, since(base)) } } }.awaitAll()
        if (results.all { it.second.isFailure }) throw results.first().second.exceptionOrNull() ?: IOException("No relay answered")
        results.mapNotNull { (base, res) -> res.getOrNull()?.let { base to it } }
    }

    companion object {
        val DEFAULT = listOf("https://ntfy.sh", "https://ntfy.envs.net", "https://ntfy.adminforge.de", "https://ntfy.hostux.net")
    }
}

/** Plain HTTP GET of a text file, following redirects (including http <-> https, which Java won't do itself). */
object HttpText {
    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 20_000_000, timeoutMs: Int = 30_000): String {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android TV) SportsCommandCenter")
                headers.forEach { (k, v) -> if (k != "User-Agent") conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without a location")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw IOException("File is too big")
                    }
                }
                val bytes = out.toByteArray()
                // Some servers gzip without saying so.
                val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                } else bytes
                return text.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
