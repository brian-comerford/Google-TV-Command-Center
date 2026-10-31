@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.sportscc.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Shown after a crash: the first lines of the error, and where to copy the whole report. */
@Composable
fun CrashReport(report: String, onClose: () -> Unit) {
    Column(Modifier.width(760.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(22.dp)) {
        Text("Sports Command Center closed unexpectedly last time", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(
            "The report was sent to your phone: on the phone website, open \"TV error report\" and tap Copy, then paste it to whoever is fixing the app. A photo of this screen works too.",
            fontSize = 13.sp, color = Palette.Muted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            report.lineSequence().filter { it.isNotBlank() }.take(14).joinToString("\n"),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Palette.Text,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Bg).padding(10.dp),
        )
        Spacer(Modifier.height(12.dp))
        FocusButton("OK", onClose, Modifier.focusRequester(rememberInitialFocus()), accent = Palette.Win)
    }
}
