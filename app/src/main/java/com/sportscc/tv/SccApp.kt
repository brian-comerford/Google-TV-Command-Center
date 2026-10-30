package com.sportscc.tv

import android.app.Application
import android.content.Context
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class SccApp : Application() {
    lateinit var store: WatchlistStore
        private set
    lateinit var engine: TrackerEngine
        private set
    lateinit var sync: PhoneSyncManager
        private set
    lateinit var browser: com.sportscc.tv.browser.BrowserStore
        private set
    lateinit var streams: com.sportscc.tv.stream.StreamStore
        private set
    lateinit var updates: UpdateManager
        private set
    lateinit var sleeper: SleeperManager
        private set

    override fun onCreate() {
        super.onCreate()
        store = WatchlistStore(this)
        browser = com.sportscc.tv.browser.BrowserStore(this)
        streams = com.sportscc.tv.stream.StreamStore(this)
        engine = TrackerEngine(store)
        sleeper = SleeperManager(store) { engine.notify(it) }
        sleeper.start()
        sync = PhoneSyncManager(this, store, notify = { engine.notify(it) }, onApplied = { sleeper.refreshSoon() })
        updates = UpdateManager(this) { engine.notify(it) }
        updates.start()
        MainScope().launch {
            engine.active.collect { if (it) sync.start() else sync.stop() }
        }
    }
}

val Context.scc: SccApp get() = applicationContext as SccApp
