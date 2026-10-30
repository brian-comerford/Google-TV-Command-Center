@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.sportscc.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.sportscc.core.Channel
import com.sportscc.tv.stream.StreamStore
import kotlinx.coroutines.launch

private const val FAVORITES = "__favorites"

/**
 * Pick a channel from your playlists: choose a playlist (or Favorites), narrow it by group or by
 * typing part of a name, and press OK on a channel to play it. ☆ marks a favorite.
 */
@Composable
fun ChannelsDialog(
    streams: StreamStore,
    selectedId: String?,
    onSelect: (String) -> Unit,
    playing: Channel?,
    onPlay: (Channel, List<Channel>) -> Unit,
    /** Pick the game this channel is the stream for. */
    onAssign: ((Channel) -> Unit)? = null,
    onAdd: () -> Unit,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val playlists by streams.playlists.collectAsState()
    val favorites by streams.favorites.collectAsState()
    val scope = rememberCoroutineScope()

    val source = selectedId?.takeIf { id -> id == FAVORITES && favorites.isNotEmpty() || playlists.any { it.id == id } }
        ?: playlists.firstOrNull()?.id ?: if (favorites.isNotEmpty()) FAVORITES else null
    val playlist = playlists.firstOrNull { it.id == source }

    var all by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(source, favorites, version) {
        all = when (source) {
            null -> emptyList()
            FAVORITES -> favorites
            else -> {
                loading = true
                streams.channels(source).also { loading = false }
            }
        }
    }
    var group by remember(source) { mutableStateOf<String?>(null) }
    var query by remember(source) { mutableStateOf("") }
    val groups = remember(all) { all.mapNotNull { it.group }.distinct() }
    val shown = remember(all, group, query) {
        val q = query.trim()
        all.filter { (group == null || it.group == group) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
    }
    val favUrls = remember(favorites) { favorites.map { it.url }.toSet() }

    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Once per list: start on the channel that's playing (if it's in this list), else the first one.
    var focusedFor by remember { mutableStateOf<String?>("-") }
    val target = playing?.let { p -> shown.indexOfFirst { it.url == p.url } }?.takeIf { it >= 0 } ?: 0
    LaunchedEffect(source, shown.isEmpty()) {
        if (focusedFor == source || (source != null && shown.isEmpty())) return@LaunchedEffect
        if (source != null) listState.scrollToItem(target)
        if (firstFocus.requestFocusWhenReady()) focusedFor = source
    }

    Box(Modifier.fillMaxSize().background(Color(0xE6000000)).padding(horizontal = 40.dp, vertical = 22.dp)) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Channels", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            source == FAVORITES -> "Favorites · ${favorites.size}"
                            playlist != null -> "${playlist.name} · ${playlist.count} channels"
                            else -> "No playlists yet"
                        },
                        fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                FocusButton("+ Add playlist", onAdd, compact = true)
                if (playlist?.url != null) FocusButton("Refresh", {
                    scope.launch {
                        runCatching { streams.refresh(playlist) }
                            .onSuccess { onMessage("${it.name}: ${it.count} channels"); version++ }
                            .onFailure { onMessage("Couldn't refresh: ${it.message}") }
                    }
                }, compact = true)
                if (playlist != null) FocusButton("Remove", { streams.remove(playlist.id) }, accent = Palette.Live, compact = true)
                FocusButton("Close", onClose, modifier = if (source == null) Modifier.focusRequester(firstFocus) else Modifier, compact = true)
            }

            if (source == null) {
                Spacer(Modifier.height(16.dp))
                Text("Add a playlist to see its channels here.", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "On the phone website, paste the playlist's link (.m3u) into Open on TV, or send the playlist file. " +
                        "Or press + Add playlist and type the link. Single stream links (.m3u8) play straight away.",
                    fontSize = 13.sp, color = Palette.Muted,
                )
                return@Column
            }

            // Which list: favorites and each playlist.
            if (playlists.size + (if (favorites.isNotEmpty()) 1 else 0) > 1) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (favorites.isNotEmpty()) item { FocusButton("★ Favorites", { onSelect(FAVORITES) }, selected = source == FAVORITES, compact = true) }
                    items(playlists, key = { it.id }) { p -> FocusButton(p.name.take(28), { onSelect(p.id) }, selected = source == p.id, compact = true) }
                }
            }
            // Groups (e.g. Sports, News).
            if (groups.size > 1) {
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FocusButton("All", { group = null }, selected = group == null, compact = true) }
                    items(groups) { g -> FocusButton(g.take(28), { group = g }, selected = group == g, compact = true) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("Find a channel…", fontSize = 15.sp, color = Palette.Muted)
                            inner()
                        }
                    },
                    modifier = Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.Bg)
                        .border(1.dp, Palette.PanelHi, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (loading) "Loading…" else "${shown.size} shown",
                    fontSize = 12.sp, color = Palette.Muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (!loading && shown.isEmpty()) Text("No channels match.", fontSize = 14.sp, color = Palette.Muted)
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(shown, key = { i, c -> "$i|${c.url}" }) { i, c ->
                    ChannelRow(
                        c,
                        isPlaying = c.url == playing?.url,
                        favorite = c.url in favUrls,
                        onPlay = { onPlay(c, shown) },
                        onFavorite = { streams.toggleFavorite(c) },
                        onAssign = onAssign?.let { f -> { f(c) } },
                        modifier = if (i == target) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    c: Channel,
    isPlaying: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAssign: (() -> Unit)?,
    modifier: Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onPlay,
            modifier = modifier.weight(1f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (isPlaying) Palette.Accent.copy(alpha = 0.25f) else Palette.PanelHi,
                contentColor = Palette.Text,
                focusedContainerColor = Palette.Accent,
                focusedContentColor = Color.White,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 44.dp, height = 28.dp), contentAlignment = Alignment.Center) {
                    if (c.logo != null) AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    c.group?.let { Text(it, fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (isPlaying) Pill("PLAYING", Palette.Live)
            }
        }
        FocusButton(if (favorite) "★" else "☆", onFavorite, selected = favorite, accent = Palette.Warn, compact = true)
        onAssign?.let { FocusButton("Game…", it, compact = true) }
    }
}
