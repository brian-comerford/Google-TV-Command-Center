package com.sportscc.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
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
import com.sportscc.tv.ui.DashboardScreen
import com.sportscc.tv.ui.LeaguePickerScreen
import com.sportscc.tv.ui.ManageScreen
import com.sportscc.tv.ui.PlayerPickerScreen
import com.sportscc.tv.ui.PropsScreen
import com.sportscc.tv.ui.SccTheme
import com.sportscc.tv.ui.SettingsScreen
import com.sportscc.tv.ui.TeamOptionsScreen
import com.sportscc.tv.ui.TeamPickerScreen
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
    data class Manage(val key: String) : Screen
    data object Settings : Screen
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
        BackHandler(enabled = stack.size > 1) { pop() }

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
                feed = feed,
                banner = banner,
                onAdd = { push(Screen.Leagues) },
                onRefresh = { engine.refreshNow() },
                onSettings = { push(Screen.Settings) },
                onCardClick = { push(Screen.Manage(it.watch.key)) },
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
                )
                store.upsert(watch)
                home()
                push(Screen.Props(watch.key))
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
                    onEditProps = if (item is PlayerWatch) ({ stack[stack.lastIndex] = Screen.Props(item.key) }) else null,
                    onMoveTop = { store.moveToTop(item.key); home() },
                    onRemove = { store.remove(item.key); home() },
                    onBack = { pop() },
                )
            }

            Screen.Settings -> SettingsScreen(
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
