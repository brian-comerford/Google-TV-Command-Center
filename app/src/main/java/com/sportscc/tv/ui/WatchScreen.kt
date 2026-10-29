@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)

package com.sportscc.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.sportscc.core.Alert
import com.sportscc.core.BetCard
import com.sportscc.core.Card
import com.sportscc.core.GameState
import com.sportscc.core.LegStatus
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
import kotlinx.coroutines.delay

/**
 * "Watch": a web page (e.g. a game stream) in the top-left, with your cards down the right
 * and live scores plus the feed along the bottom.
 */
@Composable
fun WatchScreen(
    cards: List<Card>,
    feed: List<Alert>,
    banner: Alert?,
    layout: BrowserLayout,
    desktop: Boolean,
    saved: List<SavedSite>,
    startUrl: String,
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

    var url by remember { mutableStateOf(startUrl) }
    var title by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pointer by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val toolbarFocus = remember { FocusRequester() }

    DisposableEffect(browser) {
        browser.onPageChanged = { u, t ->
            if (u.isNotBlank()) url = u
            title = t
            if (u.startsWith("http")) onPage(u)
        }
        browser.onLoading = { progress = it }
        browser.onExitTop = { runCatching { toolbarFocus.requestFocus() } }
        browser.setDesktopMode(desktop)
        browser.load(startUrl)
        onDispose { browser.destroy() }
    }
    // Pause video and sound when you leave the app; pick up again when you come back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> browser.webView.onPause()
                Lifecycle.Event.ON_RESUME -> browser.webView.onResume()
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
        browser.load(u)
        onIncomingHandled()
        dialog = null
        browser.requestFocus()
    }
    LaunchedEffect(Unit) {
        delay(300)
        browser.requestFocus()
    }
    BackHandler(enabled = dialog == null) { if (!browser.goBack()) onExit() }
    BackHandler(enabled = dialog != null) {
        dialog = null
        browser.requestFocus()
    }

    val showRight = layout == BrowserLayout.FRAMED || layout == BrowserLayout.RIGHT
    val showBottom = layout == BrowserLayout.FRAMED || layout == BrowserLayout.BOTTOM

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
                                browser.requestFocus(); true
                            } else false
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    FocusButton("Back", { if (!browser.goBack()) onExit() }, Modifier.focusRequester(toolbarFocus), compact = true)
                    FocusButton("Search", { dialog = "open" }, compact = true)
                    FocusButton("Saved", { dialog = "saved" }, compact = true)
                    FocusButton("Save", { onSave(SavedSite(title?.takeIf { it.isNotBlank() } ?: url, url)) }, compact = true)
                    FocusButton("Reload", { browser.reload() }, compact = true)
                    FocusButton(if (pointer) "Pointer" else "Scroll", { pointer = !pointer }, selected = true, compact = true)
                    FocusButton(if (desktop) "Desktop" else "Mobile", { onDesktop(!desktop) }, compact = true)
                    FocusButton("Layout", { onLayout(layout.next()) }, compact = true)
                    FocusButton("Exit", onExit, accent = Palette.Live, compact = true)
                }
                Text(
                    listOfNotNull(title?.takeIf { it.isNotBlank() }, url.takeIf { it.startsWith("http") }).joinToString("  ·  ")
                        .ifBlank { url } + "   ·   Layout: ${layout.label}",
                    fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (progress in 1..99) ProgressBar(progress / 100f, Palette.Accent, Modifier.height(2.dp))
                }
                // The browser stays in the same place in the tree whatever the layout, so it's never rebuilt.
                AndroidView(factory = { browser }, modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)))
                if (showBottom) BottomTicker(cards, feed)
            }
            if (showRight) SidePanel(cards, Modifier.width(250.dp).fillMaxHeight())
        }

        AlertBanner(banner, Modifier.align(Alignment.TopEnd).padding(top = 40.dp), width = 360.dp)

        when (dialog) {
            "open" -> OpenDialog(
                initial = if (url.startsWith("http")) url else "",
                onGo = { text ->
                    browser.load(text)
                    dialog = null
                    browser.requestFocus()
                },
                onCancel = { dialog = null; browser.requestFocus() },
            )
            "saved" -> SavedDialog(
                saved,
                onOpen = { s -> browser.load(s.url); dialog = null; browser.requestFocus() },
                onRemove = onRemoveSaved,
                onClose = { dialog = null; browser.requestFocus() },
            )
        }
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
                    Text(card.watch.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("No game today", fontSize = 11.sp, color = Palette.Muted)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (g.state == GameState.PRE) "${g.away.abbreviation} @ ${g.home.abbreviation}" else g.scoreLine,
                            fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f),
                        )
                        StatusPill(g)
                    }
                    if (live) (g.situation ?: g.lastPlay)?.let { Text(it, fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            is PlayerCard -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.watch.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    card.fantasy?.takeIf { card.watch.fantasy }?.let { (label, pts) ->
                        Text("${String.format("%.1f", pts)} $label", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Palette.Accent)
                    }
                }
                val line = card.chips.take(4).joinToString(" · ") { "${it.value} ${it.label}" }
                Text(line.ifBlank { card.game?.let { if (it.state == GameState.PRE) formatStart(it) else "No stats yet" } ?: "No game today" },
                    fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!card.watch.fantasy) card.props.forEach { PropRow(it, card.game?.state) }
            }
            is ParlayCard -> {
                val st = card.state
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.watch.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when (st.status) {
                        ParlayStatus.WON -> Pill("CASHED", Palette.Win)
                        ParlayStatus.LOST -> Pill("BUSTED", Palette.Live)
                        else -> Unit
                    }
                }
                Text(st.summary, fontSize = 11.sp, color = Palette.Muted)
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
                Text(st.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                card.game?.takeIf { it.state != GameState.PRE }?.let { Text("${it.scoreLine} · ${it.shortDetail}", fontSize = 11.sp, color = Palette.Muted, maxLines = 1) }
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
            Text("Search or enter a site", fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
            Text("Saved sites", fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
