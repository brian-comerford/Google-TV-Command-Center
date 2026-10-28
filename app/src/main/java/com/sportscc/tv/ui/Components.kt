@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.sportscc.tv.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

object Palette {
    val Bg = Color(0xFF0B0F17)
    val Panel = Color(0xFF151B26)
    val PanelHi = Color(0xFF1E2735)
    val Text = Color(0xFFF2F5FA)
    val Muted = Color(0xFF8B95A7)
    val Live = Color(0xFFFF3B30)
    val Win = Color(0xFF34C759)
    val Warn = Color(0xFFFFB020)
    val Accent = Color(0xFF3D8BFF)
}

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
        content = content,
    )
}

/** D-pad friendly button: grows and turns accent-colored when focused. */
@Composable
fun FocusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Color = Palette.Accent,
    big: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.35f) else Palette.PanelHi,
            contentColor = Palette.Text,
            focusedContainerColor = accent,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, accent)) else Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = if (big) 24.dp else 16.dp, vertical = if (big) 16.dp else 9.dp),
            fontSize = if (big) 18.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Focusable dashboard tile with an accent outline when selected by the remote. */
@Composable
fun TileCard(onClick: () -> Unit, modifier: Modifier = Modifier, highlight: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Panel,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.PanelHi,
            focusedContentColor = Palette.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = highlight?.let { Border(BorderStroke(2.dp, it)) } ?: Border.None,
            focusedBorder = Border(BorderStroke(3.dp, Palette.Accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = true) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color else color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (filled) Color.White else color, maxLines = 1)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.PanelHi),
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
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        subtitle?.let { Text(it, fontSize = 14.sp, color = Palette.Muted) }
    }
}
