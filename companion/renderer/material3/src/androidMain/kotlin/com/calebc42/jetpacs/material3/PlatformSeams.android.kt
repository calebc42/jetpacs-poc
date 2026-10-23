// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Build
import android.os.Trace
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer

/** Bounds-decode to enforce the pixel + decoded-byte limits before the full
 * decode; reject an undecodable or over-limit image. */
internal actual fun decodeGuardedImage(bytes: ByteArray, limits: ImageLoader.Limits): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null // not a decodable raster
    val pixels = w.toLong() * h.toLong()
    if (pixels > limits.maxPixels) return null
    if (pixels * 4 > limits.maxDecodedBytes) return null // ARGB_8888
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

@Composable
internal actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? {
    // §18.4 `dynamic`: the wallpaper-derived scheme as the BASE, where the
    // platform has one (S+); below S the baseline scheme stands in.
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }
}

// The native canvas draws the text as it always has on Android; the measurer
// is the desktop's instrument.
internal actual fun DrawScope.drawCanvasText(
    measurer: TextMeasurer, text: String, x: Float, y: Float, textSizePx: Float, color: Color,
) {
    drawIntoCanvas { c ->
        val paint = Paint().apply {
            this.color = color.toArgb()
            textSize = textSizePx
            isAntiAlias = true
        }
        c.nativeCanvas.drawText(text, x, y, paint)
    }
}

internal actual fun beginTraceSection(name: String) = Trace.beginSection(name)

internal actual fun endTraceSection() = Trace.endSection()

@Composable
internal actual fun SystemBarsMatch(background: Color) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (view.isInEditMode) return
    val light = background.luminance() > 0.5f
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val bars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) bars else 0, bars)
    }
}
