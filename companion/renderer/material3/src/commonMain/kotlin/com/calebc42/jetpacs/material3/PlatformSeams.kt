// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer

// The Material renderer's platform seams (docs/PLAN-desktop-d1-renderers.md,
// closed by D3 of docs/PLAN-desktop-companion.md). Each platform decodes,
// draws and traces with its own toolkit; everything around these calls is
// common code.

/**
 * Bounds-check and decode an encoded raster under [limits]: null for an
 * undecodable response or one over the pixel or decoded-byte limit. Android
 * decodes with BitmapFactory, the desktop with Skia; both retain four bytes
 * per pixel.
 */
internal expect fun decodeGuardedImage(bytes: ByteArray, limits: ImageLoader.Limits): ImageBitmap?

/**
 * The platform's wallpaper-derived color scheme for a `dynamic` theme, or
 * null where the platform has none. Android answers on S and later; the
 * desktop has none.
 */
@Composable
internal expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

/**
 * A `canvas` text op: TEXT with its baseline at (X, Y) in pixels, at
 * TEXT-SIZE-PX and COLOR. Android draws through the native canvas; the
 * desktop measures with [measurer] and draws the layout.
 */
internal expect fun DrawScope.drawCanvasText(
    measurer: TextMeasurer, text: String, x: Float, y: Float, textSizePx: Float, color: Color,
)

/** A system-trace section around variant layout; a no-op off Android. */
internal expect fun beginTraceSection(name: String)

internal expect fun endTraceSection()

/**
 * Match the window's system-bar icons to a presented scaffold's [background]:
 * dark icons over a light background, light icons over a dark one. A design
 * scope can give a surface a palette unrelated to the system dark setting
 * (an app's own light theme on a phone in dark mode), so the bars follow the
 * pixels actually drawn behind them. Android sets the window's insets
 * controller; the desktop window has no system bars.
 */
@Composable
internal expect fun SystemBarsMatch(background: Color)

/** Whether this platform has its own date and time dialogs. */
internal expect val hasPlatformPickers: Boolean

/**
 * The platform's own date dialog at [value] (YYYY-MM-DD), shown while
 * composed. [onPick] gets the picked date; [onDismiss] runs either way.
 */
@Composable
internal expect fun PlatformDateDialog(value: String, onPick: (String) -> Unit, onDismiss: () -> Unit)

/**
 * The platform's own time dialog at [hour]:[minute], on the device's 12 or
 * 24 hour clock. [onPick] gets HH:MM; [onDismiss] runs either way.
 */
@Composable
internal expect fun PlatformTimeDialog(hour: Int, minute: Int, onPick: (String) -> Unit, onDismiss: () -> Unit)
