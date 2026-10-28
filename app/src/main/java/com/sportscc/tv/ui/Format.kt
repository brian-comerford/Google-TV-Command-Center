package com.sportscc.tv.ui

import com.sportscc.core.Game
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dayFmt = DateTimeFormatter.ofPattern("EEE h:mm a")

/** ESPN start times look like "2026-10-25T17:00Z"; show them in the TV's own time zone. */
fun formatStart(g: Game): String {
    val instant = runCatching { Instant.parse(g.startTime) }.getOrNull()
        ?: runCatching { Instant.parse(g.startTime.removeSuffix("Z") + ":00Z") }.getOrNull()
        ?: return g.shortDetail
    val local = instant.atZone(ZoneId.systemDefault())
    return if (local.toLocalDate() == LocalDate.now()) local.format(timeFmt) else local.format(dayFmt)
}
