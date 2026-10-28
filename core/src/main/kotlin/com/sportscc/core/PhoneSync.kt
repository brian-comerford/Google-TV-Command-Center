package com.sportscc.core

import kotlinx.coroutines.Dispatchers
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
}

data class RelayMessage(val id: String, val time: Long, val message: String)

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

    /** Newest message on [topic] after [sinceId] (or from the last 12 hours), or null. */
    suspend fun latest(topic: String, sinceId: String?): RelayMessage? = withContext(Dispatchers.IO) {
        val since = sinceId ?: "12h"
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

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** ntfy's poll response is one JSON object per line; keep the last "message" event. */
        fun parseLatest(ndjson: String): RelayMessage? = ndjson.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            .filter { it.str("event") == "message" }
            .mapNotNull { o ->
                RelayMessage(
                    id = o.str("id") ?: return@mapNotNull null,
                    time = o["time"]?.jsonPrimitive?.longOrNull ?: 0L,
                    message = o.str("message") ?: return@mapNotNull null,
                )
            }
            .lastOrNull()
    }
}
