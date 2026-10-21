// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

// The Material renderer's platform seams (docs/PLAN-desktop-d1-renderers.md).
// Each Android actual is the code the Android library ran; each JVM actual is
// the placeholder that the named item of docs/PLAN-desktop-companion.md
// replaces.

/**
 * The decoded image for an `image` node, or null while it loads and on any
 * failure, in which case the caller shows its neutral placeholder. Android
 * loads through [LocalMaterialImageSource]; the desktop JVM returns null
 * until D3 ports the guarded loader.
 */
@Composable
internal expect fun rememberNodeImage(url: String): ImageBitmap?

/**
 * The platform's wallpaper-derived color scheme for a `dynamic` theme, or
 * null where the platform has none. Android answers on S and later; the
 * desktop JVM has none.
 */
@Composable
internal expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

/** Parse YYYY-MM-DD to UTC-midnight millis for the picker; null if invalid. */
internal expect fun parseIsoDateUtc(iso: String): Long?

/** Format the picker's UTC-midnight millis back to YYYY-MM-DD. */
internal expect fun isoDateFromUtcMillis(millis: Long): String

/** The day of week of a UTC instant, 1 = Sunday through 7 = Saturday. */
internal expect fun utcCalendarDayOfWeek(utcMillis: Long): Int

/** The calendar year of a UTC instant. */
internal expect fun utcYear(utcMillis: Long): Int

/**
 * A `canvas` text op: TEXT at (X, Y) in pixels with TEXT-SIZE-PX and COLOR.
 * Android draws through the native canvas; the desktop JVM draws nothing
 * until D3 measures text with Compose.
 */
internal expect fun DrawScope.drawCanvasText(
    text: String, x: Float, y: Float, textSizePx: Float, color: Color,
)

/** A system-trace section around variant layout; a no-op off Android. */
internal expect fun beginTraceSection(name: String)

internal expect fun endTraceSection()
