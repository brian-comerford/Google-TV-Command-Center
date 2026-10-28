package com.sportscc.tv

import android.app.Application
import android.content.Context

class SccApp : Application() {
    lateinit var store: WatchlistStore
        private set
    lateinit var engine: TrackerEngine
        private set

    override fun onCreate() {
        super.onCreate()
        store = WatchlistStore(this)
        engine = TrackerEngine(store)
    }
}

val Context.scc: SccApp get() = applicationContext as SccApp
