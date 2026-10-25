// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import org.jetbrains.skia.Image

// The desktop side of the platform seams, on JetBrains' Compose and Skia.
// This module is compiled only by the Companion build, whose JVM classpaths
// link JetBrains' desktop jars, so desktop-only API is in reach here.

/** Decode with Skia under the same pixel and byte limits as Android. HEIC and
 * HEIF, which the guards admit, are not Skia formats and fail closed like any
 * undecodable response. */
internal actual fun decodeGuardedImage(bytes: ByteArray, limits: ImageLoader.Limits): ImageBitmap? {
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0) return null // not a decodable raster
    val pixels = w.toLong() * h.toLong()
    if (pixels > limits.maxPixels) return null
    if (pixels * 4 > limits.maxDecodedBytes) return null // RGBA_8888
    return runCatching { image.toComposeImageBitmap() }.getOrNull()
}

/** The desktop has no wallpaper-derived scheme. */
@Composable
internal actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

/** Measure, then place the layout so its first baseline sits at Y, the
 * meaning Y has on Android's native canvas. The size is pixels and ignores
 * font scale, as there. */
internal actual fun DrawScope.drawCanvasText(
    measurer: TextMeasurer, text: String, x: Float, y: Float, textSizePx: Float, color: Color,
) {
    val layout = measurer.measure(text, TextStyle(fontSize = textSizePx.toSp(), color = color))
    drawText(layout, topLeft = Offset(x, y - layout.firstBaseline))
}

internal actual fun beginTraceSection(name: String) = Unit

internal actual fun endTraceSection() = Unit

@Composable
internal actual fun SystemBarsMatch(background: Color) = Unit

internal actual val hasPlatformPickers: Boolean = false

// The desktop has no platform dialogs; date_button and time_button keep
// Material's, and these are never composed.
@Composable
internal actual fun PlatformDateDialog(value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
}

@Composable
internal actual fun PlatformTimeDialog(hour: Int, minute: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
}
