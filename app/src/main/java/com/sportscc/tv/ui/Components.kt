@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.sportscc.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.LocalTextStyle
import com.sportscc.tv.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/** Design 1 (docs/designs/design-1): charcoal cards, yellow accent, orange for losing, green for passing bets. */
object Palette {
    val Bg = Color(0xFF26272B)
    val Panel = Color(0xFF34353B)
    val PanelHi = Color(0xFF3E3F46)
    /** Ring and bar tracks, chart backgrounds. */
    val Track = Color(0xFF2C2D32)
    /** Outlines of buttons that aren't focused. */
    val Outline = Color(0xFF5C5E66)
    val Text = Color(0xFFEDEDF0)
    val Muted = Color(0xFFB3B5BD) // light enough to read from the couch on a dark background
    /** Losing, busted, opponent scores and live game clocks. */
    val Live = Color(0xFFF07B3F)
    /** Passing bets: hits, covering, on pace to win. */
    val Win = Color(0xFF4FC48A)
    /** Still chasing a line (and live but not decided). */
    val Warn = Color(0xFFF6C446)
    /** Focus, selection and the main buttons. */
    val Accent = Color(0xFFF6C446)
    /** Text and icons on a yellow, orange or green fill. */
    val OnAccent = Color(0xFF26272B)
}

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
    Font(R.font.poppins_extrabold, FontWeight.Black),
)

@Composable
fun SccTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            background = Palette.Bg,
            surface = Palette.Panel,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
        ),
    ) {
        // Text drawn outside a Surface (e.g. the Watch screen's side cards) otherwise defaults to black.
        CompositionLocalProvider(
            LocalContentColor provides Palette.Text,
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Poppins),
            content = content,
        )
    }
}

/**
 * D-pad friendly pill button: an outline at rest, filled with [accent] (dark label) when focused.
 * [selected] draws the outline and label in [accent].
 */
@Composable
fun FocusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Color = Palette.Accent,
    big: Boolean = false,
    /** Small, single-line button for dense toolbars; it never wraps or squeezes its label. */
    compact: Boolean = false,
    /** Filled even when not focused: the screen's main action (Watch, Send, Update now). */
    filled: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (filled) accent else Color.Transparent,
            contentColor = if (filled) Palette.OnAccent else if (selected) accent else Palette.Text,
            focusedContainerColor = accent,
            focusedContentColor = Palette.OnAccent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (filled) Border.None else Border(BorderStroke(2.dp, if (selected) accent else Palette.Outline)),
            focusedBorder = Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(
                horizontal = if (big) 26.dp else if (compact) 12.dp else 18.dp,
                vertical = if (big) 14.dp else if (compact) 5.dp else 9.dp,
            ),
            fontSize = if (big) 18.sp else if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (compact) 1 else Int.MAX_VALUE,
            softWrap = !compact,
        )
    }
}

/** Round icon button (outline at rest, yellow when focused), like the design's circle buttons. */
@Composable
fun IconCircleButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size).semantics { contentDescription = label },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Accent,
            focusedContentColor = Palette.OnAccent,
        ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(2.dp, Palette.Outline)), focusedBorder = Border.None),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(icon, contentDescription = null, colorFilter = ColorFilter.tint(LocalContentColor.current), modifier = Modifier.size(size * 0.45f))
        }
    }
}

/**
 * A ring gauge: [fraction] of the way round in [color] over a dark track, with [content] in the middle
 * (a number or a check). Used for props, fantasy points and game progress.
 */
@Composable
fun Ring(fraction: Float, color: Color, modifier: Modifier = Modifier, size: Dp = 40.dp, stroke: Dp = 5.dp, track: Color = Palette.Track, content: @Composable () -> Unit = {}) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arc = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(w))
            val f = fraction.coerceIn(0f, 1f)
            if (f > 0f) drawArc(color, -90f, 360f * f, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** The design's dotted step slider: a dot per step on a line, filled up to [filledTo] (0..1). */
@Composable
fun StepDots(colors: List<Color?>, filledTo: Float, lineColor: Color, modifier: Modifier = Modifier, marker: Float? = null) {
    Box(modifier.fillMaxWidth().height(18.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val y = this.size.height / 2
            val r = 6.dp.toPx()
            val x0 = r
            val x1 = this.size.width - r
            drawLine(Palette.Outline, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x1, y), 2.dp.toPx())
            drawLine(lineColor, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x0 + (x1 - x0) * filledTo.coerceIn(0f, 1f), y), 2.dp.toPx())
            val n = colors.size
            colors.forEachIndexed { i, c ->
                val x = if (n == 1) x0 else x0 + (x1 - x0) * i / (n - 1)
                val center = androidx.compose.ui.geometry.Offset(x, y)
                drawCircle(Palette.Panel, r, center)
                if (c != null) drawCircle(c, r, center) else drawCircle(Palette.Outline, r - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            }
            marker?.let { m ->
                drawCircle(lineColor, 9.dp.toPx(), androidx.compose.ui.geometry.Offset(x0 + (x1 - x0) * m.coerceIn(0f, 1f), y))
            }
        }
    }
}

/** Focusable dashboard tile with an accent outline when selected by the remote. */
@Composable
fun TileCard(onClick: () -> Unit, modifier: Modifier = Modifier, highlight: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Panel,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Panel,
            focusedContentColor = Palette.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = highlight?.let { Border(BorderStroke(1.5.dp, it)) } ?: Border.None,
            focusedBorder = Border(BorderStroke(3.dp, Palette.Accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = true) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) color else color.copy(alpha = 0.18f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (filled) Palette.OnAccent else color, maxLines = 1)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.Track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color),
        )
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
        subtitle?.let { Text(it, fontSize = 14.sp, color = Palette.Muted) }
    }
}
