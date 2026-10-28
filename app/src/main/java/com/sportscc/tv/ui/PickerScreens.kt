@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.sportscc.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.sportscc.core.AthleteInfo
import com.sportscc.core.League
import com.sportscc.core.TeamInfo

/** Requests focus once, the first time this composable is shown. */
@Composable
fun rememberInitialFocus(): FocusRequester {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocusWhenReady() }
    return fr
}

/** Lazy-list items attach a frame or two after composition; requestFocus throws until they do. */
suspend fun FocusRequester.requestFocusWhenReady(): Boolean {
    repeat(20) {
        if (runCatching { requestFocus() }.isSuccess) return true
        delay(50)
    }
    return false
}

@Composable
fun ScreenFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Bg).padding(horizontal = 48.dp, vertical = 28.dp)) { content() }
}

@Composable
fun LeaguePickerScreen(onPick: (League) -> Unit) = ScreenFrame {
    Column {
        ScreenTitle("Add to your command center", "Pick a league")
        val first = rememberInitialFocus()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(6.dp),
        ) {
            itemsIndexed(League.entries.toList()) { i, league ->
                FocusButton(
                    league.label + if (league.supportsPlayers) "" else "  (teams)",
                    { onPick(league) },
                    Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                    big = true,
                )
            }
        }
    }
}

/** Load-once helper for the pickers' network calls, with a retry button on failure. */
@Composable
fun <T> Loader(key: Any, load: suspend () -> T, content: @Composable (T) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(key) { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(key, attempt) {
        result = null
        result = runCatching { load() }
    }
    val r = result
    when {
        r == null -> Text("Loading…", fontSize = 18.sp, color = Palette.Muted)
        r.isFailure -> Column {
            Text("Couldn't reach ESPN: ${r.exceptionOrNull()?.message}", color = Palette.Warn, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            FocusButton("Retry", { attempt++ }, Modifier.focusRequester(rememberInitialFocus()))
        }
        else -> content(r.getOrThrow())
    }
}

@Composable
fun TeamPickerScreen(league: League, load: suspend () -> List<TeamInfo>, onPick: (TeamInfo) -> Unit) = ScreenFrame {
    Column {
        ScreenTitle(league.label, "Pick a team")
        Loader(league, load) { teams ->
            // College leagues have hundreds of teams; a letter row keeps D-pad travel short.
            val letters = remember(teams) { teams.mapNotNull { it.displayName.firstOrNull()?.uppercaseChar() }.distinct().sorted() }
            val useLetters = teams.size > 40
            var letter by remember(teams) { mutableStateOf(if (useLetters) letters.firstOrNull() else null) }
            val shown = if (letter == null) teams else teams.filter { it.displayName.firstOrNull()?.uppercaseChar() == letter }
            val first = rememberInitialFocus()
            if (useLetters) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(6.dp)) {
                    items(letters) { c ->
                        FocusButton(c.toString(), { letter = c }, if (c == letters.first()) Modifier.focusRequester(first) else Modifier, selected = c == letter)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(200.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(6.dp),
            ) {
                itemsIndexed(shown, key = { _, t -> t.id }) { i, t ->
                    val mod = if (!useLetters && i == 0) Modifier.focusRequester(first) else Modifier
                    TileCard({ onPick(t) }, mod.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = t.logo, contentDescription = null, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(t.shortName.ifBlank { t.displayName }, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.abbreviation, fontSize = 11.sp, color = Palette.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TeamOptionsScreen(
    league: League,
    team: TeamInfo,
    teamTracked: Boolean,
    onTrackTeam: () -> Unit,
    onPickPlayer: () -> Unit,
) = ScreenFrame {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = team.logo, contentDescription = null, modifier = Modifier.size(72.dp))
            Spacer(Modifier.width(18.dp))
            ScreenTitle(team.displayName, league.label)
        }
        Spacer(Modifier.height(18.dp))
        val first = rememberInitialFocus()
        FocusButton(
            if (teamTracked) "Already following ${team.shortName} ✓" else "Follow ${team.shortName}: live score & scoring alerts",
            onTrackTeam, Modifier.focusRequester(first), big = true,
        )
        if (league.supportsPlayers) {
            Spacer(Modifier.height(14.dp))
            FocusButton("Follow a ${team.shortName} player (props & fantasy)", onPickPlayer, big = true)
        }
    }
}

@Composable
fun PlayerPickerScreen(
    league: League,
    team: TeamInfo,
    load: suspend () -> List<AthleteInfo>,
    isTracked: (AthleteInfo) -> Boolean,
    onPick: (AthleteInfo) -> Unit,
) = ScreenFrame {
    Column {
        ScreenTitle(team.displayName, "Pick a player")
        Loader(team.id, load) { players ->
            val first = rememberInitialFocus()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(230.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(6.dp),
            ) {
                itemsIndexed(players, key = { _, p -> p.id }) { i, p ->
                    val tracked = isTracked(p)
                    TileCard({ onPick(p) }, (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(), highlight = if (tracked) Palette.Win else null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = p.headshot, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(Palette.PanelHi),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(p.position, p.jersey?.let { "#$it" }, if (tracked) "following" else null).joinToString(" · "),
                                    fontSize = 11.sp, color = if (tracked) Palette.Win else Palette.Muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
