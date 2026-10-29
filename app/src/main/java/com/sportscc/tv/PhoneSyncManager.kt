package com.sportscc.tv

import android.content.Context
import android.util.Log
import com.sportscc.core.Alert
import com.sportscc.core.BetWatch
import com.sportscc.core.League
import com.sportscc.core.PairingCode
import com.sportscc.core.ParlayWatch
import com.sportscc.core.PlayerWatch
import com.sportscc.core.Relay
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

data class SyncStatus(val lastFromPhone: Long? = null, val error: String? = null)

/**
 * Links the TV to the phone website through a public relay. The phone posts the whole watchlist to
 * this TV's inbox channel; the TV posts its own list to a second channel so the website can load it.
 */
class PhoneSyncManager(context: Context, private val store: WatchlistStore, private val notify: (Alert) -> Unit) {
    private val prefs = context.getSharedPreferences("scc_sync", Context.MODE_PRIVATE)
    private val relay = Relay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _code = MutableStateFlow(prefs.getString(KEY_CODE, null) ?: PairingCode.generate().also { saveCode(it) })
    val code: StateFlow<String> = _code.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus(lastFromPhone = prefs.getLong(KEY_LAST_TIME, 0L).takeIf { it > 0 }))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Poll every few seconds while the pairing screen is open, so the first sync feels instant. */
    val fast = MutableStateFlow(false)

    /** A link sent from the phone's "Open on TV" box, waiting for the browser to open it. */
    private val _incomingUrl = MutableStateFlow<String?>(null)
    val incomingUrl: StateFlow<String?> = _incomingUrl.asStateFlow()
    fun consumeIncomingUrl() {
        _incomingUrl.value = null
    }

    // Links sent before the app started are stale: only open ones sent from now on.
    private var lastOpenId: String? = null
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
        prefs.edit().remove(KEY_LAST_ID).apply()
        _code.value = c
        scope.launch { publishState() }
    }

    private fun saveCode(c: String) = prefs.edit().putString(KEY_CODE, c).apply()

    private suspend fun publishState() {
        runCatching {
            relay.publish(
                PairingCode.stateTopic(_code.value),
                SyncCodec.encode(SyncPayload(items = store.watchlist.value, sentAt = System.currentTimeMillis())),
            )
        }.onFailure { Log.w(TAG, "publish failed", it) }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val lastId = prefs.getString(KEY_LAST_ID, null)
                val msg = relay.latest(PairingCode.inboxTopic(_code.value), lastId)
                if (msg != null && msg.id != lastId) {
                    prefs.edit().putString(KEY_LAST_ID, msg.id).apply()
                    apply(SyncCodec.decode(msg.message))
                }
                val open = relay.latest(PairingCode.openTopic(_code.value), lastOpenId, lastOpenId ?: startedAtSec.toString())
                if (open != null && open.id != lastOpenId) {
                    lastOpenId = open.id
                    open.message.trim().takeIf { it.isNotEmpty() }?.let { _incomingUrl.value = it }
                }
                if (_status.value.error != null) _status.value = _status.value.copy(error = null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "poll failed", e)
                _status.value = _status.value.copy(error = e.message ?: e::class.simpleName)
            }
            delay(if (fast.value) 3_000 else 20_000)
        }
    }

    private fun apply(p: SyncPayload) {
        store.replaceAll(p.items)
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
    }
}
