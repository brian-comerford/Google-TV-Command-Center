package com.sportscc.tv

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sportscc.core.Alert
import com.sportscc.core.League
import com.sportscc.core.PlayerWatch
import com.sportscc.core.TeamInfo
import com.sportscc.core.TeamWatch
import com.sportscc.core.orderCards
import com.sportscc.tv.ui.DashboardScreen
import com.sportscc.tv.ui.LeaguePickerScreen
import com.sportscc.tv.ui.ManageScreen
import com.sportscc.tv.ui.PhoneScreen
import com.sportscc.tv.ui.PlayerPickerScreen
import com.sportscc.tv.ui.PropsScreen
import com.sportscc.tv.ui.SccTheme
import com.sportscc.tv.ui.SettingsScreen
import com.sportscc.tv.ui.TeamOptionsScreen
import com.sportscc.tv.ui.TeamPickerScreen
import com.sportscc.tv.ui.TrackModeScreen
import com.sportscc.tv.ui.WatchScreen
import com.sportscc.tv.browser.BrowserView
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Home : Screen
    data object Leagues : Screen
    data class Teams(val league: League) : Screen
    data class TeamOptions(val league: League, val team: TeamInfo) : Screen
    data class Players(val league: League, val team: TeamInfo) : Screen
    data class Props(val key: String) : Screen
    data class TrackMode(val key: String) : Screen
    data class Manage(val key: String) : Screen
    data object Settings : Screen
    data object Phone : Screen
    data class Watch(val url: String? = null) : Screen
}

class MainActivity : ComponentActivity() {
    private val overlayAllowed = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SccTheme { App() } }
    }

    override fun onStart() {
        super.onStart()
        scc.engine.uiVisible.value = true
        scc.engine.acquire()
        syncService()
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed.value = Settings.canDrawOverlays(this)
    }

    override fun onStop() {
        scc.engine.uiVisible.value = false
        scc.engine.release()
        super.onStop()
    }

    private fun syncService() {
        if (scc.store.settings.value.backgroundAlerts) AlertService.start(this) else AlertService.stop(this)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Sports Command Center download link", text))
        Toast.makeText(this, "Link copied. Long-press the URL box in Downloader to paste.", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlay() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(this, "This TV has no settings screen for it; use the adb command shown below.", Toast.LENGTH_LONG).show()
    }

    @Composable
    private fun App() {
        val store = scc.store
        val engine = scc.engine
        val dashboard by engine.dashboard.collectAsStateWithLifecycle()
        val feed by engine.feed.collectAsStateWithLifecycle()
        val watchlist by store.watchlist.collectAsStateWithLifecycle()
        val settings by store.settings.collectAsStateWithLifecycle()

        val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
        fun push(s: Screen) = stack.add(s)
        fun pop() {
            if (stack.size > 1) stack.removeAt(stack.lastIndex)
        }
        fun home() {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        }
        // Screen updates don't count as activity, so without this the screen saver starts mid-game.
        // The Watch screen is usually showing video, so it always stays awake.
        val keepOn = stack.last() is Screen.Watch || when (settings.keepAwake) {
            KeepAwake.ALWAYS -> true
            KeepAwake.LIVE -> dashboard?.anyLive == true
            KeepAwake.NEVER -> false
        }
        LaunchedEffect(keepOn) {
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        BackHandler(enabled = stack.size > 1) { pop() }

        // "Open on TV" from the phone (a page, a stream or a playlist): jump to the Watch screen,
        // or open it there if it's already showing.
        val incoming by scc.sync.incomingUrl.collectAsStateWithLifecycle()
        LaunchedEffect(incoming) {
            val u = incoming ?: return@LaunchedEffect
            if (stack.last() !is Screen.Watch) {
                home()
                push(Screen.Watch(u))
                scc.sync.consumeIncomingUrl()
            }
        }

        // Home-screen state that outlives a trip to another screen.
        var focusKey by remember { mutableStateOf<String?>(null) }
        var movingKey by remember { mutableStateOf<String?>(null) }
        val cards = remember(dashboard, watchlist, settings.liveFirst) {
            val byKey = dashboard?.cards.orEmpty().associateBy { it.watch.key }
            orderCards(watchlist.mapNotNull { byKey[it.key] }, settings.liveFirst)
        }

        // In-app banner: show popups one at a time.
        var banner by remember { mutableStateOf<Alert?>(null) }
        LaunchedEffect(Unit) {
            val queue = Channel<Alert>(Channel.UNLIMITED)
            launch { engine.popups.collect { queue.trySend(it) } }
            for (a in queue) {
                banner = a
                delay(if (a.big) 8_000 else 5_000)
                banner = null
                delay(400)
            }
        }

        when (val screen = stack.last()) {
            Screen.Home -> DashboardScreen(
                dashboard = dashboard,
                cards = cards,
                feed = feed,
                banner = banner,
                liveFirst = settings.liveFirst,
                columns = settings.cardColumns,
                focusKey = focusKey,
                movingKey = movingKey,
                onAdd = { push(Screen.Leagues) },
                onPhone = { push(Screen.Phone) },
                onWatch = { push(Screen.Watch()) },
                onRefresh = { engine.refreshNow() },
                onSettings = { push(Screen.Settings) },
                onCardClick = {
                    focusKey = it.watch.key
                    push(Screen.Manage(it.watch.key))
                },
                onMoveDone = { order ->
                    store.reorder(order)
                    movingKey = null
                },
                onMoveCancel = { movingKey = null },
            )

            Screen.Leagues -> LeaguePickerScreen { push(Screen.Teams(it)) }

            is Screen.Teams -> TeamPickerScreen(screen.league, { engine.client.teams(screen.league) }) {
                if (screen.league.supportsPlayers) push(Screen.TeamOptions(screen.league, it))
                else {
                    store.upsert(it.toWatch(screen.league))
                    home()
                }
            }

            is Screen.TeamOptions -> {
                val key = screen.team.toWatch(screen.league).key
                TeamOptionsScreen(
                    screen.league, screen.team,
                    teamTracked = watchlist.any { it.key == key },
                    onTrackTeam = {
                        store.upsert(screen.team.toWatch(screen.league))
                        home()
                    },
                    onPickPlayer = { push(Screen.Players(screen.league, screen.team)) },
                )
            }

            is Screen.Players -> PlayerPickerScreen(
                screen.league, screen.team,
                load = { engine.client.roster(screen.league, screen.team.id) },
                isTracked = { a -> watchlist.any { it is PlayerWatch && it.athleteId == a.id } },
            ) { a ->
                val existing = watchlist.firstOrNull { it is PlayerWatch && it.athleteId == a.id && it.league == screen.league } as? PlayerWatch
                val watch = PlayerWatch(
                    league = screen.league,
                    athleteId = a.id,
                    name = a.name,
                    teamId = screen.team.id,
                    teamAbbreviation = screen.team.abbreviation,
                    position = a.position,
                    jersey = a.jersey,
                    headshot = a.headshot,
                    props = existing?.props ?: emptyList(),
                    fantasy = existing?.fantasy ?: false,
                )
                store.upsert(watch)
                home()
                push(Screen.TrackMode(watch.key))
            }

            is Screen.TrackMode -> {
                val player = watchlist.firstOrNull { it.key == screen.key } as? PlayerWatch
                if (player == null) LaunchedEffect(Unit) { home() } else TrackModeScreen(
                    player,
                    onFantasy = {
                        store.updatePlayer(player.key) { it.copy(fantasy = true, props = emptyList()) }
                        home()
                    },
                    onBets = {
                        store.updatePlayer(player.key) { it.copy(fantasy = false) }
                        stack[stack.lastIndex] = Screen.Props(player.key)
                    },
                )
            }

            is Screen.Props -> {
                val player = watchlist.firstOrNull { it.key == screen.key } as? PlayerWatch
                if (player == null) LaunchedEffect(Unit) { home() } else PropsScreen(
                    player,
                    onChange = { props -> store.updatePlayer(player.key) { it.copy(props = props) } },
                    onDone = { home() },
                )
            }

            is Screen.Manage -> {
                val item = watchlist.firstOrNull { it.key == screen.key }
                if (item == null) LaunchedEffect(Unit) { home() } else ManageScreen(
                    item,
                    onEditProps = if (item is PlayerWatch && !item.fantasy) ({ stack[stack.lastIndex] = Screen.Props(item.key) }) else null,
                    onToggleFantasy = if (item is PlayerWatch) ({
                        if (item.fantasy) {
                            store.updatePlayer(item.key) { it.copy(fantasy = false) }
                            stack[stack.lastIndex] = Screen.Props(item.key)
                        } else {
                            store.updatePlayer(item.key) { it.copy(fantasy = true, props = emptyList()) }
                            home()
                        }
                    }) else null,
                    onMove = {
                        movingKey = item.key
                        home()
                    },
                    onMoveTop = { store.moveToTop(item.key); home() },
                    onRemove = { store.remove(item.key); home() },
                    onBack = { pop() },
                )
            }

            is Screen.Watch -> {
                val saved by scc.browser.saved.collectAsStateWithLifecycle()
                DisposableEffect(Unit) {
                    scc.sync.fast.value = true // links from the phone arrive within a few seconds
                    onDispose { scc.sync.fast.value = false }
                }
                WatchScreen(
                    cards = cards,
                    feed = feed,
                    banner = banner,
                    layout = settings.browserLayout,
                    desktop = settings.browserDesktop,
                    saved = saved,
                    streams = scc.streams,
                    homeUrl = scc.browser.lastUrl ?: BrowserView.HOME,
                    startInput = screen.url,
                    incomingUrl = incoming,
                    onIncomingHandled = { scc.sync.consumeIncomingUrl() },
                    onLayout = { l -> store.updateSettings { it.copy(browserLayout = l) } },
                    onDesktop = { d -> store.updateSettings { it.copy(browserDesktop = d) } },
                    onPage = { scc.browser.lastUrl = it },
                    onSave = { scc.browser.save(it); Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show() },
                    onRemoveSaved = { scc.browser.remove(it) },
                    onExit = { pop() },
                )
            }

            Screen.Phone -> {
                val sync = scc.sync
                val code by sync.code.collectAsStateWithLifecycle()
                val status by sync.status.collectAsStateWithLifecycle()
                DisposableEffect(Unit) {
                    sync.fast.value = true
                    onDispose { sync.fast.value = false }
                }
                PhoneScreen(BuildConfig.REMOTE_URL, code, status, onNewCode = { sync.newCode() }, onDone = { home() })
            }

            Screen.Settings -> SettingsScreen(
                appVersion = BuildConfig.VERSION_NAME,
                apkUrl = BuildConfig.APK_URL,
                onCopyApkUrl = { copyToClipboard(BuildConfig.APK_URL) },
                settings = settings,
                overlayAllowed = overlayAllowed.value,
                onChange = { t ->
                    store.updateSettings(t)
                    syncService()
                },
                onRequestOverlay = { requestOverlay() },
                onTestAlert = {
                    engine.sendTestAlert(
                        Alert(
                            id = "test:${System.currentTimeMillis()}",
                            time = System.currentTimeMillis(),
                            league = League.NFL,
                            watchKey = "test",
                            title = "TEST: Josh Allen +1 Pass TD",
                            detail = "If you can read this over another app, background alerts work.",
                            big = true,
                        ),
                    )
                },
            )
        }
    }
}

private fun TeamInfo.toWatch(league: League) = TeamWatch(league, id, abbreviation, displayName, logo, color)
