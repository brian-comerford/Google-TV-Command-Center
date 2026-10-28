package com.sportscc.tv

import android.os.SystemClock
import android.util.Log
import com.sportscc.core.Alert
import com.sportscc.core.Dashboard
import com.sportscc.core.EspnClient
import com.sportscc.core.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-wide polling loop. Runs while anyone holds it (the visible activity and/or the
 * background [AlertService]), publishes the dashboard, and emits alerts.
 */
class TrackerEngine(private val store: WatchlistStore) {
    val client = EspnClient()
    private val tracker = Tracker(client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _dashboard = MutableStateFlow<Dashboard?>(null)
    val dashboard: StateFlow<Dashboard?> = _dashboard.asStateFlow()

    /** Newest first; every alert, regardless of the popup filter. */
    private val _feed = MutableStateFlow<List<Alert>>(emptyList())
    val feed: StateFlow<List<Alert>> = _feed.asStateFlow()

    /** Alerts that should pop up (already filtered by the "big plays only" setting). */
    private val _popups = MutableSharedFlow<Alert>(extraBufferCapacity = 64)
    val popups: SharedFlow<Alert> = _popups.asSharedFlow()

    /** True while the activity is on screen; the service only draws overlays when it isn't. */
    val uiVisible = MutableStateFlow(false)

    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var holders = 0
    private var job: Job? = null
    private var stoppedAt = 0L

    init {
        scope.launch {
            store.watchlist.collect { kick.trySend(Unit) }
        }
    }

    @Synchronized
    fun acquire() {
        holders++
        if (job?.isActive == true) return
        // After a long pause, diffing against stale data would replay an hour of plays as alerts.
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > 5 * 60_000) tracker.resetBaseline()
        val previous = job
        job = scope.launch {
            previous?.join() // the tracker isn't thread-safe; never let two loops overlap
            loop()
        }
    }

    @Synchronized
    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            job?.cancel()
            stoppedAt = SystemClock.elapsedRealtime()
        }
    }

    fun refreshNow() {
        kick.trySend(Unit)
    }

    /** Pushes a fake alert through the popup path so the user can check overlay setup. */
    fun sendTestAlert(alert: Alert) {
        // Delayed so there's time to press Home and see it land on top of another app.
        scope.launch {
            delay(TEST_ALERT_DELAY_MS)
            _feed.value = (listOf(alert) + _feed.value).take(FEED_SIZE)
            _popups.tryEmit(alert)
        }
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val watchlist = store.watchlist.value
            var live = false
            if (watchlist.isEmpty()) {
                _dashboard.value = Dashboard(emptyList(), false, System.currentTimeMillis(), emptyList())
            } else {
                try {
                    val result = tracker.refresh(watchlist)
                    _dashboard.value = result.dashboard
                    live = result.dashboard.anyLive
                    if (result.alerts.isNotEmpty()) {
                        _feed.value = (result.alerts.reversed() + _feed.value).take(FEED_SIZE)
                        val bigOnly = store.settings.value.bigPlaysOnly
                        result.alerts.filter { !bigOnly || it.big }.forEach { _popups.tryEmit(it) }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("TrackerEngine", "refresh failed", e)
                    _dashboard.value = _dashboard.value?.copy(errors = listOf(e.message ?: "Refresh failed"))
                }
            }
            val waitMs = if (live) store.settings.value.liveIntervalSec * 1000L else IDLE_INTERVAL_MS
            withTimeoutOrNull(waitMs) { kick.receive() }
        }
    }

    private companion object {
        const val IDLE_INTERVAL_MS = 120_000L
        const val FEED_SIZE = 100
        const val TEST_ALERT_DELAY_MS = 6_000L
    }
}
