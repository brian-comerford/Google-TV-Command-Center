@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.sportscc.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.sportscc.core.Action
import com.sportscc.core.Channel
import com.sportscc.core.Game
import com.sportscc.core.GameState
import com.sportscc.core.Heat
import com.sportscc.tv.AutoSwitch
import com.sportscc.tv.stream.GameFeed

/**
 * Today's games and the stream assigned to each. Assign the stream that's playing (or the channel
 * picked in the Channels list) to a game, jump to a game's stream, and set what auto-switch does.
 */
@Composable
fun GamesDialog(
    games: List<Game>,
    feeds: List<GameFeed>,
    /** Games on your cards, listed first. */
    myGameIds: Set<String>,
    /** What Assign gives a game: the channel picked in the Channels list, or the stream that's on. */
    assigning: Channel?,
    fromChannels: Boolean,
    autoSwitch: AutoSwitch,
    onAutoSwitch: () -> Unit,
    onWatch: (GameFeed) -> Unit,
    onAssign: (Game, Channel) -> Unit,
    onUnassign: (String) -> Unit,
    onClose: () -> Unit,
) {
    val byId = feeds.associateBy { it.gameId }
    // Live first, then coming up, then finished; your games and games with a stream first within each.
    val sorted = remember(games, feeds, myGameIds) {
        games.sortedWith(
            compareBy<Game>(
                { when (it.state) { GameState.IN -> 0; GameState.PRE -> 1; GameState.POST -> 2 } },
                { if (it.id in myGameIds || byId.containsKey(it.id)) 0 else 1 },
                { it.startTime },
            ),
        )
    }
    val first = remember { FocusRequester() }
    // Start on the first game that has a button; if none does, on Close.
    val firstWithButtons = sorted.indexOfFirst { assigning != null || byId.containsKey(it.id) }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }

    Box(Modifier.fillMaxSize().background(Color(0xE6000000)).padding(horizontal = 40.dp, vertical = 22.dp)) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (fromChannels && assigning != null) "Which game is ${assigning.name}?" else "Games", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            fromChannels -> "Press Assign on its game."
                            assigning != null -> "Assign puts ${assigning.name} on a game. Watch jumps to a game's stream."
                            else -> "Play a stream (a channel, or a link from your phone), then Assign it to its game."
                        },
                        fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                FocusButton("Auto-switch: ${autoSwitch.label}", onAutoSwitch, selected = autoSwitch != AutoSwitch.OFF, compact = true)
                FocusButton("Close", onClose, if (firstWithButtons < 0) Modifier.focusRequester(first) else Modifier, compact = true)
            }
            Text(
                "Auto-switch: when a game with a stream gets exciting (red zone, close finish), Ask offers to switch, " +
                    "Auto switches, Off does nothing. The next likely stream is loaded in the background so it starts right away.",
                fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (sorted.isEmpty()) {
                Text(
                    "No games on the scoreboard. The games come from the leagues on your list, so add a team or player first.",
                    fontSize = 14.sp, color = Palette.Muted,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(sorted, key = { _, g -> g.id }) { i, g ->
                    GameRow(
                        g, byId[g.id], mine = g.id in myGameIds, assigning = assigning,
                        onWatch = onWatch, onAssign = { c -> onAssign(g, c) }, onUnassign = { onUnassign(g.id) },
                        firstModifier = if (i == firstWithButtons) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameRow(
    g: Game,
    feed: GameFeed?,
    mine: Boolean,
    assigning: Channel?,
    onWatch: (GameFeed) -> Unit,
    onAssign: (Channel) -> Unit,
    onUnassign: () -> Unit,
    firstModifier: Modifier,
) {
    val interest = Action.interest(g)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.PanelHi).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (g.state == GameState.PRE) "${g.away.abbreviation} @ ${g.home.abbreviation}" else g.scoreLine,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(g)
                if (mine) { Spacer(Modifier.width(6.dp)); Pill("YOURS", Palette.Accent, filled = false) }
                when (interest.heat) {
                    Heat.HOT -> { Spacer(Modifier.width(6.dp)); Pill("HOT", Palette.Live) }
                    Heat.WARM -> { Spacer(Modifier.width(6.dp)); Pill("HEATING UP", Palette.Warn, filled = false) }
                    Heat.NONE -> Unit
                }
            }
            Text(
                listOfNotNull(
                    g.league.label,
                    interest.reason,
                    feed?.let { "Stream: ${it.channel.name}" } ?: "No stream assigned",
                ).joinToString("  ·  "),
                fontSize = 12.sp, color = if (feed != null) Palette.Text else Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        var focusUsed = false
        fun mod(): Modifier = if (!focusUsed) { focusUsed = true; firstModifier } else Modifier
        if (feed != null) FocusButton("Watch", { onWatch(feed) }, mod(), accent = Palette.Win, compact = true)
        if (assigning != null && feed?.channel?.url != assigning.url) FocusButton("Assign", { onAssign(assigning) }, mod(), compact = true)
        if (feed != null) FocusButton("Clear", onUnassign, mod(), accent = Palette.Live, compact = true)
        if (feed == null && assigning == null) Text("—", fontSize = 14.sp, color = Palette.Muted)
    }
}
