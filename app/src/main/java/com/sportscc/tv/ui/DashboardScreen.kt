@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package com.sportscc.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.sportscc.core.moved
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.sportscc.core.Alert
import com.sportscc.core.Card
import com.sportscc.core.Competitor
import com.sportscc.core.Dashboard
import com.sportscc.core.Game
import com.sportscc.core.GameState
import com.sportscc.core.PlayerCard
import com.sportscc.core.Prop
import com.sportscc.core.PropProgress
import com.sportscc.core.SportKind
import com.sportscc.core.StatFormat
import com.sportscc.core.TeamCard
import java.text.DateFormat
import java.util.Date

@Composable
fun DashboardScreen(
    dashboard: Dashboard?,
    /** Already in on-screen order (see orderCards). */
    cards: List<Card>,
    feed: List<Alert>,
    banner: Alert?,
    liveFirst: Boolean,
    /** 1 or 2 card columns (Settings). Fixed, so it doesn't depend on the TV's screen density. */
    columns: Int,
    /** Card to focus when the screen opens (the one you last selected), else the first. */
    focusKey: String?,
    /** Card being moved with the arrows, or null. */
    movingKey: String?,
    onAdd: () -> Unit,
    onPhone: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onCardClick: (Card) -> Unit,
    onMoveDone: (List<String>) -> Unit,
    onMoveCancel: () -> Unit,
) {
    val addFocus = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // While moving, the grid shows this working order; it's saved on OK and dropped on Back.
    var moveOrder by remember(movingKey) { mutableStateOf(movingKey?.let { cards.map { c -> c.watch.key } }) }
    val byKey = cards.associateBy { it.watch.key }
    val shown = moveOrder?.mapNotNull { byKey[it] } ?: cards
    val movingName = movingKey?.let { byKey[it]?.watch?.displayName }

    BackHandler(enabled = movingKey != null) { onMoveCancel() }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            // ---- top bar ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SPORTS COMMAND CENTER", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.Text, letterSpacing = 1.5.sp)
                    if (movingName != null) MoveHint(movingName, liveFirst) else StatusLine(dashboard)
                }
                FocusButton("+ Add", onAdd, Modifier.focusRequester(addFocus).padding(start = 8.dp))
                FocusButton("Phone", onPhone, Modifier.padding(start = 8.dp))
                FocusButton("Refresh", onRefresh, Modifier.padding(start = 8.dp))
                FocusButton("Settings", onSettings, Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(14.dp))

            Row(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onPreviewKeyEvent { e ->
                            val key = movingKey ?: return@onPreviewKeyEvent false
                            val order = moveOrder ?: return@onPreviewKeyEvent false
                            val columns = (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1
                            val delta = when (e.key) {
                                Key.DirectionLeft -> -1
                                Key.DirectionRight -> 1
                                Key.DirectionUp -> -columns
                                Key.DirectionDown -> columns
                                else -> null
                            }
                            when {
                                delta != null -> {
                                    if (e.type == KeyEventType.KeyDown) {
                                        val next = order.moved(order.indexOf(key), delta)
                                        moveOrder = next
                                        scope.launch { gridState.revealItem(next.indexOf(key)) }
                                    }
                                    true
                                }
                                e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                                    if (e.type == KeyEventType.KeyUp) onMoveDone(order)
                                    true
                                }
                                else -> false
                            }
                        },
                ) {
                    when {
                        dashboard == null -> Text("Loading scores…", color = Palette.Muted, fontSize = 18.sp)
                        shown.isEmpty() -> EmptyState()
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(columns.coerceIn(1, 2)),
                            state = gridState,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(6.dp),
                        ) {
                            items(shown, key = { it.watch.key }) { card ->
                                val mod = Modifier.focusRequester(requester(card.watch.key))
                                val moving = card.watch.key == movingKey
                                when (card) {
                                    is TeamCard -> TeamCardView(card, mod, moving) { onCardClick(card) }
                                    is PlayerCard -> PlayerCardView(card, mod, moving) { onCardClick(card) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(18.dp))
                FeedPanel(feed, Modifier.width(280.dp).fillMaxHeight())
            }
        }

        AlertBanner(banner, Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
    }

    LaunchedEffect(dashboard != null, shown.isEmpty()) {
        if (initialFocusDone || dashboard == null) return@LaunchedEffect
        if (shown.isEmpty()) {
            initialFocusDone = addFocus.requestFocusWhenReady()
            return@LaunchedEffect
        }
        val target = (movingKey ?: focusKey)?.takeIf { k -> shown.any { it.watch.key == k } } ?: shown.first().watch.key
        gridState.revealItem(shown.indexOfFirst { it.watch.key == target })
        initialFocusDone = requester(target).requestFocusWhenReady()
    }
}

/** Scrolls just enough to bring [index] into view; no-op when it's already fully visible. */
private suspend fun LazyGridState.revealItem(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val fullyVisible = item != null && item.offset.y >= info.viewportStartOffset &&
        item.offset.y + item.size.height <= info.viewportEndOffset
    if (!fullyVisible) animateScrollToItem(index)
}

@Composable
private fun MoveHint(name: String, liveFirst: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill("MOVING", Palette.Warn)
        Spacer(Modifier.width(8.dp))
        Text(
            "$name · arrows to move · OK to place · Back to cancel" +
                if (liveFirst) " · Live games first is on, so live games stay on top" else "",
            fontSize = 12.sp, color = Palette.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusLine(d: Dashboard?) {
    if (d == null) return
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(d.updatedAt))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (d.anyLive) {
            Pill("LIVE", Palette.Live)
            Spacer(Modifier.width(8.dp))
        }
        Text("Updated $time", fontSize = 12.sp, color = Palette.Muted)
        if (d.errors.isNotEmpty()) {
            Text("  ·  ${d.errors.first()}", fontSize = 12.sp, color = Palette.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your command center is empty", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Press  + Add  to follow teams and players, or  Phone  to type them in on your phone.",
            fontSize = 16.sp, color = Palette.Muted,
        )
    }
}

// ---------------- cards ----------------

@Composable
private fun TeamCardView(card: TeamCard, modifier: Modifier, moving: Boolean, onClick: () -> Unit) {
    val g = card.game
    TileCard(onClick, modifier.fillMaxWidth(), highlight = cardHighlight(g, moving)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(card.watch.league.label, Palette.Accent, filled = false)
            Spacer(Modifier.weight(1f))
            g?.let { StatusPill(it) }
        }
        Spacer(Modifier.height(10.dp))
        if (g == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(card.watch.logo, 36)
                Spacer(Modifier.width(10.dp))
                Text(card.watch.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text("No game on today's slate", fontSize = 13.sp, color = Palette.Muted)
            return@TileCard
        }
        CompetitorRow(g, g.away, watched = g.away.teamId == card.watch.teamId)
        Spacer(Modifier.height(4.dp))
        CompetitorRow(g, g.home, watched = g.home.teamId == card.watch.teamId)
        val extra = listOfNotNull(g.situation, g.lastPlay).joinToString("\n")
        if (extra.isNotBlank() && g.state == GameState.IN) {
            Spacer(Modifier.height(8.dp))
            Text(extra, fontSize = 12.sp, color = Palette.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else if (g.state == GameState.PRE && g.broadcast != null) {
            Spacer(Modifier.height(8.dp))
            Text("on ${g.broadcast}", fontSize = 12.sp, color = Palette.Muted)
        }
    }
}

private fun cardHighlight(g: Game?, moving: Boolean): Color? = when {
    moving -> Palette.Warn
    g?.state == GameState.IN -> Palette.Live.copy(alpha = 0.6f)
    else -> null
}

@Composable
private fun CompetitorRow(g: Game, c: Competitor, watched: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(c.logo, 30)
        Spacer(Modifier.width(10.dp))
        Text(
            c.abbreviation, fontSize = 18.sp, fontWeight = if (watched) FontWeight.Black else FontWeight.SemiBold,
            color = if (watched) Palette.Text else Palette.Muted,
        )
        c.record?.let { Text("  $it", fontSize = 12.sp, color = Palette.Muted) }
        if (g.state == GameState.IN && g.possessionTeamId == c.teamId && g.league.kind == SportKind.FOOTBALL) {
            Text("  ●", fontSize = 12.sp, color = Palette.Warn)
        }
        Spacer(Modifier.weight(1f))
        if (g.state != GameState.PRE) {
            val other = if (c.isHome) g.away else g.home
            val leading = (c.score ?: 0) > (other.score ?: 0)
            Text(
                "${c.score ?: 0}", fontSize = 26.sp, fontWeight = FontWeight.Black,
                color = if (leading || g.state == GameState.IN) Palette.Text else Palette.Muted,
            )
        }
    }
}

@Composable
private fun PlayerCardView(card: PlayerCard, modifier: Modifier, moving: Boolean, onClick: () -> Unit) {
    val w = card.watch
    val g = card.game
    TileCard(onClick, modifier.fillMaxWidth(), highlight = cardHighlight(g, moving)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = w.headshot,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Palette.PanelHi),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(w.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(w.teamAbbreviation, w.position, w.jersey?.let { "#$it" }).joinToString(" · "),
                    fontSize = 12.sp, color = Palette.Muted,
                )
            }
            g?.let { StatusPill(it) }
        }
        Spacer(Modifier.height(8.dp))
        val fantasy = card.fantasy?.takeIf { w.fantasy }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if (w.fantasy) Pill("FANTASY", Palette.Accent, filled = false)
                if (w.fantasy) Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        g == null -> "No game on today's slate"
                        g.state == GameState.PRE -> "${g.away.abbreviation} @ ${g.home.abbreviation}"
                        else -> g.scoreLine
                    },
                    fontSize = 13.sp, color = Palette.Muted,
                )
            }
            // Fantasy players lead with the live score instead of over/under bars.
            if (fantasy != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(String.format("%.1f", fantasy.second), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Palette.Accent)
                    Text("${fantasy.first} PTS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.Muted, letterSpacing = 1.sp)
                }
            }
        }
        if (g != null && g.state != GameState.PRE) {
            Spacer(Modifier.height(6.dp))
            if (card.chips.isEmpty()) {
                Text(if (card.line?.didNotPlay == true) "Did not play" else "No stats yet", fontSize = 13.sp, color = Palette.Muted)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!w.fantasy) card.fantasy?.let { (label, pts) -> StatChipView(StatFormat.number(pts), label, Palette.Accent) }
                    card.chips.forEach { StatChipView(it.value, it.label) }
                }
            }
        }
        if (card.props.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            card.props.forEach { PropRow(it, g?.state) }
        }
    }
}

@Composable
private fun StatChipView(value: String, label: String, accent: Color? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent?.copy(alpha = 0.22f) ?: Palette.PanelHi)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Text(" $label", fontSize = 10.sp, color = Palette.Muted)
    }
}

@Composable
private fun PropRow(p: PropProgress, state: GameState?) {
    val over = p.prop.side == Prop.Side.OVER
    val color = when {
        p.winning -> Palette.Win
        !over -> Palette.Live // under and already past the line: busted
        state == GameState.POST -> Palette.Live
        else -> Palette.Warn
    }
    val status = when {
        state == GameState.POST -> if (p.winning) "WON" else if (p.current == p.prop.line) "PUSH" else "LOST"
        over && p.winning -> "HIT"
        !over && !p.winning -> "BUSTED"
        else -> null
    }
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (over) "O" else "U"} ${StatFormat.number(p.prop.line)} ${p.def.short}",
                fontSize = 13.sp, color = Palette.Text, modifier = Modifier.weight(1f),
            )
            status?.let { Pill(it, color); Spacer(Modifier.width(6.dp)) }
            Text(StatFormat.number(p.current), fontSize = 15.sp, fontWeight = FontWeight.Black, color = color)
        }
        Spacer(Modifier.height(3.dp))
        ProgressBar(p.fraction.toFloat(), color)
    }
}

@Composable
private fun StatusPill(g: Game) {
    when (g.state) {
        GameState.IN -> Pill(g.shortDetail.ifBlank { "LIVE" }, Palette.Live)
        GameState.POST -> Pill(g.shortDetail.ifBlank { "Final" }, Palette.Muted, filled = false)
        GameState.PRE -> Pill(formatStart(g), Palette.Accent, filled = false)
    }
}

@Composable
private fun Logo(url: String?, size: Int) {
    AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp))
}

// ---------------- feed + banner ----------------

@Composable
private fun FeedPanel(feed: List<Alert>, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Panel)
            .padding(14.dp),
    ) {
        Text("LIVE FEED", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Palette.Muted, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        if (feed.isEmpty()) {
            Text("Scores, stat changes and prop updates for your picks will appear here.", fontSize = 12.sp, color = Palette.Muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(feed, key = { it.id }) { a -> FeedRow(a) }
        }
    }
}

@Composable
private fun FeedRow(a: Alert) {
    Row {
        Box(Modifier.width(3.dp).height(44.dp).background(if (a.big) Palette.Live else Palette.Accent))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(a.time))} · ${a.league.label}",
                fontSize = 10.sp, color = Palette.Muted,
            )
            Text(a.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            a.detail?.let { Text(it, fontSize = 11.sp, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun AlertBanner(alert: Alert?, modifier: Modifier = Modifier) {
    // Keep the last alert around so the exit animation still has content to draw.
    var last by remember { mutableStateOf<Alert?>(null) }
    if (alert != null) last = alert
    AnimatedVisibility(
        visible = alert != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val a = last ?: return@AnimatedVisibility
        val accent = if (a.big) Palette.Live else Palette.Accent
        Row(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF0101621))
                .border(BorderStroke(3.dp, accent), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            a.imageUrl?.let {
                AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape).background(Palette.PanelHi), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(14.dp))
            }
            Column {
                Text(
                    if (a.big) "${a.league.label} · BIG PLAY" else a.league.label,
                    fontSize = 12.sp, fontWeight = FontWeight.Black, color = accent, letterSpacing = 1.sp,
                )
                Text(a.title, fontSize = if (a.big) 22.sp else 19.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2)
                a.detail?.let { Text(it, fontSize = 14.sp, color = Palette.Muted, maxLines = if (a.props.isEmpty()) 3 else 2) }
                if (a.props.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    a.props.forEach { PropRow(it, null) }
                }
            }
        }
    }
}
