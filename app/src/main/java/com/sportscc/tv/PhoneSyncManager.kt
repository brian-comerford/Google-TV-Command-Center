package com.sportscc.tv

import android.content.Context
import android.util.Log
import com.sportscc.core.Alert
import com.sportscc.core.BetWatch
import com.sportscc.core.HttpText
import com.sportscc.core.League
import com.sportscc.core.PairingCode
import com.sportscc.core.ParlayWatch
import com.sportscc.core.PlayerWatch
import com.sportscc.core.Relays
import com.sportscc.core.SyncCodec
import com.sportscc.core.SyncPayload
import com.sportscc.core.TeamWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class SyncStatus(val lastFromPhone: Long? = null, val error: String? = null)

/**
 * Links the TV to the phone website through public relays (several at once, see [Relays]). The phone posts the whole watchlist to
 * this TV's inbox channel; the TV posts its own list to a second channel so the website can load it.
 */
class PhoneSyncManager(
    context: Context,
    private val store: WatchlistStore,
    private val notify: (Alert) -> Unit,
    /** Called after a new list from the phone is applied. */
    private val onApplied: () -> Unit = {},
) {
    private val prefs = context.getSharedPreferences("scc_sync", Context.MODE_PRIVATE)
    private val relays = Relays(timeoutMs = 8_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _code = MutableStateFlow(prefs.getString(KEY_CODE, null) ?: PairingCode.generate().also { saveCode(it) })
    val code: StateFlow<String> = _code.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus(lastFromPhone = prefs.getLong(KEY_LAST_TIME, 0L).takeIf { it > 0 }))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Poll every few seconds while the pairing screen is open, so the first sync feels instant. */
    val fast = MutableStateFlow(false)

    /**
     * Something sent from the phone's "Open on TV" box, waiting for the Watch screen: a web address or
     * search words, a stream link, a playlist link, or a playlist file's text.
     */
    private val _incomingUrl = MutableStateFlow<String?>(null)
    val incomingUrl: StateFlow<String?> = _incomingUrl.asStateFlow()
    fun consumeIncomingUrl() {
        _incomingUrl.value = null
    }

    // Links sent before the app started are stale: only open ones sent from now on.
    // The phone sends each link through every relay, so the same link can arrive more than once.
    private val lastOpenIds = HashMap<String, String>()
    private var lastOpen: Pair<String, Long>? = null // text, relay time in seconds
    private val startedAtSec = System.currentTimeMillis() / 1000

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { pollLoop() }
            launch {
                // Republish now and then: the relay forgets messages after 12 hours.
                while (currentCoroutineContext().isActive) {
                    publishState()
                    delay(6 * 60 * 60_000L)
                }
            }
            store.watchlist.drop(1).collectLatest {
                delay(2_000) // let a burst of edits settle
                publishState()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    fun newCode() {
        val c = PairingCode.generate()
        saveCode(c)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_LAST_ID) }.forEach { edit.remove(it) }
        edit.apply()
        lastOpenIds.clear()
        _code.value = c
        scope.launch { publishState() }
    }

    private fun saveCode(c: String) = prefs.edit().putString(KEY_CODE, c).apply()

    private suspend fun publishState() {
        runCatching {
            relays.publish(
                PairingCode.stateTopic(_code.value),
                SyncCodec.encode(SyncPayload(items = store.watchlist.value, sentAt = System.currentTimeMillis(), sleeper = store.sleeper.value)),
            )
        }.onFailure { Log.w(TAG, "publish failed", it) }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                pollInbox()
                pollOpen()
                if (_status.value.error != null) _status.value = _status.value.copy(error = null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "poll failed", e)
                _status.value = _status.value.copy(error = e.message ?: e::class.simpleName)
            }
            delay(if (fast.value) 3_000 else 20_000)
        }
    }

    /** Each relay remembers its own last message id; the newest list sent wins, and only once. */
    private suspend fun pollInbox() {
        val topic = PairingCode.inboxTopic(_code.value)
        val fresh = relays.poll(topic) { base -> lastId(base) ?: "12h" }.filter { (base, m) -> m.id != lastId(base) }
        var best: SyncPayload? = null
        for ((base, m) in fresh) {
            prefs.edit().putString(KEY_LAST_ID + idSuffix(base), m.id).apply()
            val p = runCatching { SyncCodec.decode(m.message) }.getOrNull() ?: continue
            val sent = p.sentAt.takeIf { it > 0 } ?: (m.time * 1000)
            if (best == null || sent > best.sentAt) best = p.copy(sentAt = sent)
        }
        val p = best ?: return
        if (p.sentAt <= prefs.getLong(KEY_LAST_SENT, prefs.getLong(KEY_LAST_TIME, 0L))) return // already have this one (or a newer one)
        prefs.edit().putLong(KEY_LAST_SENT, p.sentAt).apply()
        apply(p)
    }

    private suspend fun pollOpen() {
        val topic = PairingCode.openTopic(_code.value)
        val fresh = relays.poll(topic) { base -> lastOpenIds[base] ?: startedAtSec.toString() }
            .filter { (base, m) -> m.id != lastOpenIds[base] }
        fresh.forEach { (base, m) -> lastOpenIds[base] = m.id }
        val (_, open) = fresh.maxByOrNull { it.second.time } ?: return
        // A playlist file sent from the phone arrives as an attachment: pass on its text.
        val text = (
            open.attachmentUrl?.let { url -> runCatching { withContext(Dispatchers.IO) { HttpText.get(url) } }.getOrNull() } ?: open.message
            ).trim()
        if (text.isEmpty()) return
        val prev = lastOpen
        // The same link through a second relay, a moment later: already opened.
        if (prev != null && prev.first == text && abs(open.time - prev.second) < 30) return
        lastOpen = text to open.time
        _incomingUrl.value = text
    }

    /** The last message id seen on [base]; ntfy.sh keeps the key older versions used. */
    private fun lastId(base: String): String? = prefs.getString(KEY_LAST_ID + idSuffix(base), null)
    private fun idSuffix(base: String) = if (base == Relays.DEFAULT.first()) "" else ":" + base.substringAfter("://")

    private fun apply(p: SyncPayload) {
        store.replaceAll(p.items)
        store.setSleeper(p.sleeper)
        onApplied()
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_TIME, now).apply()
        _status.value = SyncStatus(lastFromPhone = now)
        val teams = p.items.count { it is TeamWatch }
        val players = p.items.filterIsInstance<PlayerWatch>()
        val props = players.sumOf { it.props.size }
        notify(
            Alert(
                id = "sync:$now", time = now, league = p.items.firstOrNull()?.league ?: League.NFL, watchKey = "sync",
                title = "Updated from your phone",
                detail = "$teams teams · ${players.size} players · $props props" +
                    p.items.count { it is ParlayWatch }.let { if (it > 0) " · $it parlays" else "" } +
                    p.items.count { it is BetWatch }.let { if (it > 0) " · $it bets" else "" },
                big = false,
            ),
        )
    }

    private companion object {
        const val TAG = "PhoneSync"
        const val KEY_CODE = "code"
        const val KEY_LAST_ID = "lastId"
        const val KEY_LAST_TIME = "lastTime"
        const val KEY_LAST_SENT = "lastSentAt"
    }
}
