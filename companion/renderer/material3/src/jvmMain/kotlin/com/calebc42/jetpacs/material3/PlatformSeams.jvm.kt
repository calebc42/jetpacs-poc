// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.util.GregorianCalendar
import java.util.Calendar
import java.util.TimeZone

// D1 placeholders for the desktop JVM (docs/PLAN-desktop-d1-renderers.md).

/** D3 ports the guarded loader; until then every image shows its placeholder. */
@Composable
internal actual fun rememberNodeImage(url: String): ImageBitmap? = null

/** The desktop has no wallpaper-derived scheme. */
@Composable
internal actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

// A Gregorian calendar by name: java.util.Calendar.getInstance would hand a
// Thai or Japanese locale its own calendar, and the picker speaks ISO dates.
private fun utcCalendar(): Calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))

internal actual fun parseIsoDateUtc(iso: String): Long? {
    val parts = iso.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val mo = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return utcCalendar().apply { clear(); set(y, mo - 1, d) }.timeInMillis
}

internal actual fun isoDateFromUtcMillis(millis: Long): String {
    val cal = utcCalendar().apply { timeInMillis = millis }
    return String.format("%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH))
}

internal actual fun utcCalendarDayOfWeek(utcMillis: Long): Int =
    utcCalendar().apply { timeInMillis = utcMillis }.get(Calendar.DAY_OF_WEEK)

internal actual fun utcYear(utcMillis: Long): Int =
    utcCalendar().apply { timeInMillis = utcMillis }.get(Calendar.YEAR)

/** D3 draws canvas text with Compose text measurement; nothing until then. */
internal actual fun DrawScope.drawCanvasText(
    text: String, x: Float, y: Float, textSizePx: Float, color: Color,
) = Unit

internal actual fun beginTraceSection(name: String) = Unit

internal actual fun endTraceSection() = Unit
