@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.sportscc.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.sportscc.core.PlayerWatch
import com.sportscc.core.Prop
import com.sportscc.core.StatCatalog
import com.sportscc.core.StatDef
import com.sportscc.core.StatFormat
import com.sportscc.core.WatchItem
import com.sportscc.tv.AppSettings

@Composable
fun PropsScreen(player: PlayerWatch, onChange: (List<Prop>) -> Unit, onDone: () -> Unit) = ScreenFrame {
    var editing by remember { mutableStateOf<StatDef?>(null) }
    val defs = StatCatalog.propDefs(player.league)
    Column {
        ScreenTitle(
            "Props & targets: ${player.name}",
            "Optional. Add the lines you bet so the card shows live progress and you get HIT / CASHED alerts.",
        )
        val def = editing
        if (def != null) {
            LineEditor(def, onCancel = { editing = null }) { line, side ->
                onChange(player.props + Prop(def.id, line, side))
                editing = null
            }
            return@Column
        }

        if (player.props.isEmpty()) {
            Text("No props yet.", fontSize = 15.sp, color = Palette.Muted)
        }
        player.props.forEachIndexed { i, p ->
            val name = StatCatalog.find(player.league, p.statId)?.name ?: p.statId
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    "${if (p.side == Prop.Side.OVER) "Over" else "Under"} ${StatFormat.number(p.line)} $name",
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(420.dp),
                )
                FocusButton("Remove", { onChange(player.props.filterIndexed { j, _ -> j != i }) }, accent = Palette.Live)
            }
        }
        Spacer(Modifier.height(10.dp))
        val done = rememberInitialFocus()
        FocusButton("Done", onDone, Modifier.focusRequester(done), accent = Palette.Win)
        Spacer(Modifier.height(18.dp))
        Text("ADD A PROP", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Palette.Muted, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(210.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(6.dp),
        ) {
            itemsIndexed(defs) { _, d -> FocusButton(d.name, { editing = d }, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun LineEditor(def: StatDef, onCancel: () -> Unit, onSave: (Double, Prop.Side) -> Unit) {
    var line by remember(def) { mutableDoubleStateOf(if (def.id.startsWith("fpts")) 15.5 else 0.5) }
    var side by remember(def) { mutableStateOf(Prop.Side.OVER) }
    Column {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Panel)
                .padding(24.dp),
        ) {
            Text(def.name, fontSize = 16.sp, color = Palette.Muted)
            Text(
                "${if (side == Prop.Side.OVER) "OVER" else "UNDER"}  ${StatFormat.number(line)}",
                fontSize = 44.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                color = if (side == Prop.Side.OVER) Palette.Win else Palette.Warn,
            )
        }
        Spacer(Modifier.height(16.dp))
        val first = rememberInitialFocus()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(-10.0, -1.0, -0.5, 0.5, 1.0, 10.0).forEach { step ->
                val label = (if (step > 0) "+" else "−") + StatFormat.number(kotlin.math.abs(step))
                FocusButton(
                    label, { line = (line + step).coerceAtLeast(0.0) },
                    if (step == 0.5) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton("Over", { side = Prop.Side.OVER }, selected = side == Prop.Side.OVER, accent = Palette.Win)
            FocusButton("Under", { side = Prop.Side.UNDER }, selected = side == Prop.Side.UNDER, accent = Palette.Warn)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton("Save prop", { onSave(line, side) }, accent = Palette.Win, big = true)
            FocusButton("Cancel", onCancel, big = true)
        }
    }
}

/** Asked right after picking a player: fantasy (no lines) or bets (go set lines). */
@Composable
fun TrackModeScreen(player: PlayerWatch, onFantasy: () -> Unit, onBets: () -> Unit) = ScreenFrame {
    Column {
        ScreenTitle("How are you following ${player.name}?", player.league.label)
        val score = StatCatalog.fantasy(player.league)?.short
        val first = rememberInitialFocus()
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FocusButton(
                if (score != null) "Fantasy: live stats and $score score" else "Fantasy: live stats",
                onFantasy, Modifier.focusRequester(first), big = true,
            )
            FocusButton("Bets: add over/under lines", onBets, big = true)
        }
        Spacer(Modifier.height(14.dp))
        Text("You can switch later: select the card, then Switch to bets / fantasy.", fontSize = 13.sp, color = Palette.Muted)
    }
}

@Composable
fun ManageScreen(
    item: WatchItem,
    onEditProps: (() -> Unit)?,
    /** Players only: switch between fantasy (live score) and bets (lines). */
    onToggleFantasy: (() -> Unit)?,
    onMove: () -> Unit,
    onMoveTop: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) = ScreenFrame {
    Column {
        ScreenTitle(item.displayName, item.league.label)
        val first = rememberInitialFocus()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onEditProps != null) {
                FocusButton("Edit props / bet lines", onEditProps, Modifier.focusRequester(first), big = true)
                FocusButton("Move (use the arrows)", onMove, big = true)
            } else {
                FocusButton("Move (use the arrows)", onMove, Modifier.focusRequester(first), big = true)
            }
            FocusButton("Move to top", onMoveTop, big = true)
            if (onToggleFantasy != null && item is PlayerWatch) {
                FocusButton(
                    if (item.fantasy) "Switch to bets (add over/under lines)"
                    else "Switch to fantasy (live score, no lines" + (if (item.props.isNotEmpty()) ", removes your lines)" else ")"),
                    onToggleFantasy, big = true,
                )
            }
            FocusButton("Stop following", onRemove, accent = Palette.Live, big = true)
            FocusButton("Back", onBack, big = true)
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    overlayAllowed: Boolean,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onRequestOverlay: () -> Unit,
    onTestAlert: () -> Unit,
    appVersion: String,
    apkUrl: String,
    onCopyApkUrl: () -> Unit,
) = ScreenFrame {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")
        val first = rememberInitialFocus()

        SectionLabel("Refresh rate during live games")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(10, 15, 20, 30, 60).forEach { sec ->
                FocusButton(
                    "${sec}s", { onChange { it.copy(liveIntervalSec = sec) } },
                    if (sec == 10) Modifier.focusRequester(first) else Modifier,
                    selected = settings.liveIntervalSec == sec,
                )
            }
        }
        Hint("ESPN's feed itself updates every ~15–30s, so going lower mostly costs bandwidth.")

        SectionLabel("Card layout on the home screen")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton("2 columns", { onChange { it.copy(cardColumns = 2) } }, selected = settings.cardColumns == 2)
            FocusButton("1 wide column", { onChange { it.copy(cardColumns = 1) } }, selected = settings.cardColumns == 1)
        }
        Hint("2 columns fits more cards on screen; 1 wide column gives each card more room.")

        SectionLabel("Card order on the home screen")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton("My order", { onChange { it.copy(liveFirst = false) } }, selected = !settings.liveFirst)
            FocusButton("Live games first", { onChange { it.copy(liveFirst = true) } }, selected = settings.liveFirst)
        }
        Hint("My order keeps cards where you put them (select a card, then Move). Live games first moves live games to the top on its own.")

        SectionLabel("Pop-up alerts")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton("Every update", { onChange { it.copy(bigPlaysOnly = false) } }, selected = !settings.bigPlaysOnly)
            FocusButton("Big plays only", { onChange { it.copy(bigPlaysOnly = true) } }, selected = settings.bigPlaysOnly)
        }
        Hint("Big plays: touchdowns, interceptions, home runs, goals, finals, and prop hits. Everything still goes to the Live Feed.")

        SectionLabel("Alerts while watching other apps")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton("On", { onChange { it.copy(backgroundAlerts = true) } }, selected = settings.backgroundAlerts, accent = Palette.Win)
            FocusButton("Off", { onChange { it.copy(backgroundAlerts = false) } }, selected = !settings.backgroundAlerts, accent = Palette.Live)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (overlayAllowed) "✓ \"Display over other apps\" is allowed — alerts will pop up over YouTube TV, Netflix, etc."
            else "✗ \"Display over other apps\" is NOT allowed yet, so alerts only show inside this app.",
            fontSize = 15.sp, color = if (overlayAllowed) Palette.Win else Palette.Warn,
        )
        if (!overlayAllowed) {
            Spacer(Modifier.height(8.dp))
            FocusButton("Open permission settings", onRequestOverlay)
            Hint(
                "If Google TV has no screen for it, run this once from a computer on the same Wi-Fi " +
                    "(with ADB debugging on):\n  adb connect <TV-IP>\n  adb shell appops set com.sportscc.tv SYSTEM_ALERT_WINDOW allow",
            )
        }
        Spacer(Modifier.height(12.dp))
        FocusButton("Send a test alert", onTestAlert)
        Hint("Fires 6 seconds after you press it: press Home and open another app to see it pop up on top.")

        SectionLabel("Install or update the app")
        Text("You have version $appVersion. To get the newest one, open Downloader and enter:", fontSize = 14.sp, color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        Text(apkUrl, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        FocusButton("Copy link", onCopyApkUrl)
        Hint(
            "Easiest: copy it from the phone website (Install or update the TV app), then paste it into Downloader " +
                "with the keyboard in the Google TV app on your phone. Installing over this version keeps your list.",
        )

        SectionLabel("About")
        Hint(
            "Live data comes from ESPN's public scoreboard feed (unofficial, no account needed). " +
                "It can lag the TV broadcast by 15–60 seconds, and ESPN may change it without notice.",
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Black, color = Palette.Muted, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, fontSize = 13.sp, color = Palette.Muted)
}
