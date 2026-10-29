@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)

package com.sportscc.tv.ui

import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.sportscc.core.Alert
import com.sportscc.core.BetCard
import com.sportscc.core.Card
import com.sportscc.core.Channel
import com.sportscc.core.GameState
import com.sportscc.core.LegStatus
import com.sportscc.core.LinkKind
import com.sportscc.core.M3u
import com.sportscc.core.ParlayCard
import com.sportscc.core.ParlayStatus
import com.sportscc.core.PlayerCard
import com.sportscc.core.StatFormat
import com.sportscc.core.TeamCard
import com.sportscc.core.decided
import com.sportscc.core.orderCards
import com.sportscc.tv.BrowserLayout
import com.sportscc.tv.browser.BrowserView
import com.sportscc.tv.browser.SavedSite
import com.sportscc.tv.stream.LinkResolver
import com.sportscc.tv.stream.Opened
import com.sportscc.tv.stream.StreamPlayerView
import com.sportscc.tv.stream.StreamStatus
import com.sportscc.tv.stream.StreamStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Watch": a web page or a video stream in the top-left, with your cards down the right and live
 * scores plus the feed along the bottom. Stream links (.m3u8 and the like) and channels from .m3u
 * playlists play in a built-in player; everything else opens in the browser.
 */
@Composable
fun WatchScreen(
    cards: List<Card>,
    feed: List<Alert>,
    banner: Alert?,
    layout: BrowserLayout,
    desktop: Boolean,
    saved: List<SavedSite>,
    streams: StreamStore,
    homeUrl: String,
    startInput: String?,
    incomingUrl: String?,
    onIncomingHandled: () -> Unit,
    onLayout: (BrowserLayout) -> Unit,
    onDesktop: (Boolean) -> Unit,
    onPage: (String) -> Unit,
    onSave: (SavedSite) -> Unit,
    onRemoveSaved: (String) -> Unit,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val browser = remember { runCatching { BrowserView(ctx) }.getOrNull() }
    if (browser == null) {
        ScreenFrame {
            Column {
                ScreenTitle("Browser not available", "This TV's web view component (Android System WebView) couldn't start.")
                FocusButton("Back", onExit, Modifier.focusRequester(rememberInitialFocus()))
            }
        }
        return
    }
    val player = remember { StreamPlayerView(ctx) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(homeUrl) }
    var title by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pointer by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    // The channel playing (null = showing the browser), and the list channel up/down moves through.
    var playing by remember { mutableStateOf<Channel?>(null) }
    var queue by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var status by remember { mutableStateOf(StreamStatus(StreamStatus.State.IDLE)) }
    var showInfo by remember { mutableIntStateOf(0) }
    var listId by remember { mutableStateOf<String?>(null) }
    val toolbarFocus = remember { FocusRequester() }

    fun focusContent() {
        if (playing != null) player.requestFocus() else browser.requestFocus()
    }
    fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

    fun play(c: Channel, list: List<Channel>, hls: Boolean = false) {
        browser.pauseMedia()
        player.play(c, hls)
        playing = c
        queue = list.ifEmpty { listOf(c) }
        streams.last = c
        showInfo++
        dialog = null
        player.requestFocus()
    }
    fun stopStream() {
        player.stop()
        playing = null
        browser.requestFocus()
    }
    fun step(d: Int) {
        val c = playing ?: return
        if (queue.size < 2) return
        val i = queue.indexOfFirst { it.url == c.url }.coerceAtLeast(0)
        play(queue[(i + d).mod(queue.size)], queue)
    }
    fun openInput(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        dialog = null
        if (M3u.classify(text) == LinkKind.WEB) {
            stopStream()
            browser.load(text)
            return
        }
        busy = "Opening…"
        scope.launch {
            val opened = try {
                LinkResolver.resolve(text, streams)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                busy = null
                toast("Couldn't load that: ${e.message ?: e::class.simpleName}")
                return@launch
            }
            busy = null
            when (opened) {
                is Opened.Web -> { stopStream(); browser.load(opened.input) }
                is Opened.Play -> play(opened.channel, listOf(opened.channel), opened.hls)
                is Opened.Channels -> {
                    toast("${opened.playlist.name}: ${opened.playlist.count} channels")
                    listId = opened.playlist.id
                    dialog = "channels"
                }
            }
        }
    }

    DisposableEffect(browser) {
        browser.onPageChanged = { u, t ->
            if (u.isNotBlank()) url = u
            title = t
            if (u.startsWith("http")) onPage(u)
        }
        browser.onLoading = { progress = it }
        browser.onExitTop = { runCatching { toolbarFocus.requestFocus() } }
        player.onExitTop = { runCatching { toolbarFocus.requestFocus() } }
        player.onDown = { dialog = "channels" }
        player.onChannelStep = { step(it) }
        player.onStatus = { st ->
            status = st
            if (st.state != StreamStatus.State.PLAYING) showInfo++
        }
        browser.setDesktopMode(desktop)
        val first = startInput?.takeIf { it.isNotBlank() }
        if (first != null && M3u.classify(first) == LinkKind.WEB) browser.load(first) else browser.load(homeUrl)
        if (first != null && M3u.classify(first) != LinkKind.WEB) openInput(first)
        onDispose {
            player.stop()
            browser.destroy()
        }
    }
    // Pause video and sound when you leave the app; pick up again when you come back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { browser.webView.onPause(); player.pause() }
                Lifecycle.Event.ON_RESUME -> { browser.webView.onResume(); if (playing != null) player.resume() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(desktop) { browser.setDesktopMode(desktop) }
    LaunchedEffect(pointer) { browser.mode = if (pointer) BrowserView.Mode.POINTER else BrowserView.Mode.SCROLL }
    LaunchedEffect(incomingUrl) {
        val u = incomingUrl ?: return@LaunchedEffect
        onIncomingHandled()
        openInput(u)
    }
    LaunchedEffect(Unit) {
        delay(300)
        if (dialog == null) focusContent()
    }
    // The channel name shows for a few seconds after a change, and stays while loading or on an error.
    var infoVisible by remember { mutableStateOf(false) }
    LaunchedEffect(showInfo, status.state) {
        infoVisible = true
        if (status.state == StreamStatus.State.PLAYING) {
            delay(4_000)
            infoVisible = false
        }
    }
    BackHandler(enabled = dialog == null) {
        when {
            playing != null -> stopStream()
            !browser.goBack() -> onExit()
        }
    }
    BackHandler(enabled = dialog != null) {
        dialog = null
        focusContent()
    }

    val showRight = layout == BrowserLayout.FRAMED || layout == BrowserLayout.RIGHT
    val showBottom = layout == BrowserLayout.FRAMED || layout == BrowserLayout.BOTTOM
    val ch = playing

    // Older TVs crop the picture edges (overscan), so keep everything inside a safe margin.
    Box(Modifier.fillMaxSize().background(Palette.Bg).padding(horizontal = 30.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // ---- toolbar ----
                // Scrolls sideways if it runs out of room, so no button ever gets squeezed.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 4.dp)
                        .onPreviewKeyEvent { e ->
                            if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) {
                                focusContent(); true
                            } else false
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (ch != null) {
                        FocusButton("Browser", { stopStream() }, Modifier.focusRequester(toolbarFocus), compact = true)
                        FocusButton("Channels", { dialog = "channels" }, compact = true)
                        if (queue.size > 1) {
                            FocusButton("◀ Ch", { step(-1) }, compact = true)
                            FocusButton("Ch ▶", { step(1) }, compact = true)
                        }
                        FocusButton(if (status.state == StreamStatus.State.PAUSED) "Play" else "Pause", { player.togglePause() }, compact = true)
                        FocusButton("Retry", { player.retry() }, compact = true)
                        FocusButton("Search", { dialog = "open" }, compact = true)
                        FocusButton("Saved", { dialog = "saved" }, compact = true)
                        FocusButton("Save", { onSave(SavedSite(ch.name, M3u.PLAY_PREFIX + M3u.link(ch))) }, compact = true)
                    } else {
                        FocusButton("Back", { if (!browser.goBack()) onExit() }, Modifier.focusRequester(toolbarFocus), compact = true)
                        FocusButton("Search", { dialog = "open" }, compact = true)
                        FocusButton("Channels", { dialog = "channels" }, compact = true)
                        FocusButton("Saved", { dialog = "saved" }, compact = true)
                        FocusButton("Save", { onSave(SavedSite(title?.takeIf { it.isNotBlank() } ?: url, url)) }, compact = true)
                        FocusButton("Reload", { browser.reload() }, compact = true)
                        FocusButton(if (pointer) "Pointer" else "Scroll", { pointer = !pointer }, selected = true, compact = true)
                        FocusButton(if (desktop) "Desktop" else "Mobile", { onDesktop(!desktop) }, compact = true)
                    }
                    FocusButton("Layout", { onLayout(layout.next()) }, compact = true)
                    FocusButton("Exit", onExit, accent = Palette.Live, compact = true)
                }
                val line = busy ?: if (ch != null) {
                    "▶ ${ch.name}" + (ch.group?.let { "  ·  $it" } ?: "") + "  ·  OK pause · ◀ ▶ skip 10 s · ▼ channels"
                } else {
                    listOfNotNull(title?.takeIf { it.isNotBlank() }, url.takeIf { it.startsWith("http") }).joinToString("  ·  ").ifBlank { url }
                }
                Text(
                    "$line   ·   Layout: ${layout.label}",
                    fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (ch == null && progress in 1..99) ProgressBar(progress / 100f, Palette.Accent, Modifier.height(2.dp))
                }
                // The browser and player stay in the same place in the tree whatever the layout, so they're never rebuilt.
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp))) {
                    AndroidView(factory = { browser }, modifier = Modifier.fillMaxSize())
                    AndroidView(
                        factory = { player },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.visibility = if (ch != null) View.VISIBLE else View.INVISIBLE },
                    )
                    if (ch != null) StreamInfo(ch, status, infoVisible, Modifier.align(Alignment.BottomStart))
                }
                if (showBottom) BottomTicker(cards, feed)
            }
            if (showRight) SidePanel(cards, Modifier.width(250.dp).fillMaxHeight())
        }

        AlertBanner(banner, Modifier.align(Alignment.TopEnd).padding(top = 40.dp), width = 360.dp)

        when (dialog) {
            "open" -> OpenDialog(
                initial = if (ch == null && url.startsWith("http")) url else "",
                onGo = { text -> openInput(text) },
                onCancel = { dialog = null; focusContent() },
            )
            "saved" -> SavedDialog(
                saved,
                onOpen = { s -> openInput(s.url) },
                onRemove = onRemoveSaved,
                onClose = { dialog = null; focusContent() },
            )
            "channels" -> ChannelsDialog(
                streams = streams,
                selectedId = listId,
                onSelect = { listId = it },
                playing = ch,
                onPlay = { c, list -> play(c, list) },
                onAdd = { dialog = "open" },
                onMessage = { toast(it) },
                onClose = { dialog = null; focusContent() },
            )
        }
    }
}

/** Channel name and what the player is doing, over the bottom of the video. */
@Composable
private fun StreamInfo(ch: Channel, status: StreamStatus, visible: Boolean, modifier: Modifier) {
    val error = status.state == StreamStatus.State.ERROR
    if (!visible && !error) return
    val state = when (status.state) {
        StreamStatus.State.LOADING -> "Loading…"
        StreamStatus.State.PAUSED -> "Paused"
        StreamStatus.State.ENDED -> "Ended"
        StreamStatus.State.ERROR -> status.message ?: "Couldn't play this stream."
        else -> null
    }
    Column(
        modifier.padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ch.logo?.let {
                AsyncImage(model = it, contentDescription = null, modifier = Modifier.height(28.dp).width(44.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(ch.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state?.let { Text(it, fontSize = 13.sp, color = if (error) Palette.Warn else Palette.Muted) }
        if (error) Text("Press OK to try again, or Back for the browser.", fontSize = 12.sp, color = Palette.Muted)
    }
}

// ---------------- around the browser ----------------

@Composable
private fun BottomTicker(cards: List<Card>, feed: List<Alert>) {
    val scores = cards.mapNotNull { it.game }.distinctBy { it.id }.filter { it.state != GameState.PRE }
        .joinToString("     ") { "${it.scoreLine} · ${it.shortDetail}" }
    val news = feed.take(6).joinToString("     •     ") { it.title }
    val text = listOf(scores, news).filter { it.isNotBlank() }.joinToString("     |     ")
        .ifBlank { "Scores and updates for your picks scroll here during games." }
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Panel).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cards.any { it.game?.state == GameState.IN }) {
            Pill("LIVE", Palette.Live)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1,
            modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
        )
    }
}

@Composable
private fun SidePanel(cards: List<Card>, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(10.dp)) {
        Text("YOUR CARDS", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Palette.Muted, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        if (cards.isEmpty()) Text("Add teams, players and bets from the home screen or your phone.", fontSize = 12.sp, color = Palette.Muted)
        // Live first here regardless of the home-screen setting: this panel is about what's happening now.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(orderCards(cards, liveFirst = true), key = { it.watch.key }) { MiniCard(it) }
        }
    }
}

@Composable
private fun MiniCard(card: Card) {
    val live = card.game?.state == GameState.IN
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.PanelHi)
            .then(if (live) Modifier.border(1.dp, Palette.Live.copy(alpha = 0.6f), RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        when (card) {
            is TeamCard -> {
                val g = card.game
                if (g == null) {
                    Text(card.watch.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("No game today", fontSize = 12.sp, color = Palette.Muted)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (g.state == GameState.PRE) "${g.away.abbreviation} @ ${g.home.abbreviation}" else g.scoreLine,
                            fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f),
                        )
                        StatusPill(g)
                    }
                    if (live) (g.situation ?: g.lastPlay)?.let { Text(it, fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            is PlayerCard -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.watch.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    card.fantasy?.takeIf { card.watch.fantasy }?.let { (label, pts) ->
                        Text("${String.format("%.1f", pts)} $label", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Palette.Accent)
                    }
                }
                val line = card.chips.take(4).joinToString(" · ") { "${it.value} ${it.label}" }
                Text(line.ifBlank { card.game?.let { if (it.state == GameState.PRE) formatStart(it) else "No stats yet" } ?: "No game today" },
                    fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!card.watch.fantasy) card.props.forEach { PropRow(it, card.game?.state) }
            }
            is ParlayCard -> {
                val st = card.state
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.watch.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when (st.status) {
                        ParlayStatus.WON -> Pill("CASHED", Palette.Win)
                        ParlayStatus.LOST -> Pill("BUSTED", Palette.Live)
                        else -> Unit
                    }
                }
                Text(st.summary, fontSize = 12.sp, color = Palette.Muted)
                Spacer(Modifier.height(4.dp))
                st.legs.forEach { LegRow(it) }
            }
            is BetCard -> {
                val st = card.state
                val color = when (st.status) {
                    LegStatus.WON, LegStatus.LIVE_WINNING -> Palette.Win
                    LegStatus.LOST -> Palette.Live
                    LegStatus.LIVE_LOSING -> Palette.Warn
                    else -> Palette.Muted
                }
                Text(st.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                card.game?.takeIf { it.state != GameState.PRE }?.let { Text("${it.scoreLine} · ${it.shortDetail}", fontSize = 12.sp, color = Palette.Muted, maxLines = 1) }
                Text(card.description, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
                val f = st.fraction
                if (f != null && !st.status.decided && st.status != LegStatus.PENDING) ProgressBar(f.toFloat(), color, Modifier.padding(top = 3.dp))
            }
        }
    }
}

// ---------------- dialogs ----------------

@Composable
private fun OpenDialog(initial: String, onGo: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocusWhenReady() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(24.dp)) {
            Text("Search, or enter a site or stream", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("A site or search words open in the browser; a stream link (.m3u8) plays; a playlist link (.m3u) adds its channels.", fontSize = 13.sp, color = Palette.Muted)
            Text("Typing is easier on your phone: on the phone website, use Open on TV.", fontSize = 13.sp, color = Palette.Muted)
            Spacer(Modifier.height(14.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 20.sp),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Bg)
                    .border(2.dp, Palette.Accent, RoundedCornerShape(10.dp))
                    .padding(14.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton("Go", { onGo(text) }, accent = Palette.Win)
                FocusButton("Cancel", onCancel)
            }
        }
    }
}

@Composable
private fun SavedDialog(saved: List<SavedSite>, onOpen: (SavedSite) -> Unit, onRemove: (String) -> Unit, onClose: () -> Unit) {
    val first = rememberInitialFocus()
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(24.dp)) {
            Text("Saved sites and streams", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (saved.isEmpty()) Text("Nothing saved yet. Open a page and press Save.", fontSize = 14.sp, color = Palette.Muted)
            LazyColumn(Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(saved, key = { it.url }) { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusButton(s.title.take(60), { onOpen(s) }, Modifier.weight(1f).let { if (s == saved.first()) it.focusRequester(first) else it })
                        FocusButton("Remove", { onRemove(s.url) }, accent = Palette.Live)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FocusButton("Close", onClose, if (saved.isEmpty()) Modifier.focusRequester(first) else Modifier)
        }
    }
}
