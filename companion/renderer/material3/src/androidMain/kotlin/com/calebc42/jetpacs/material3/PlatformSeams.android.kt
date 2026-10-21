// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import android.graphics.Bitmap
import android.graphics.Paint
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.os.Build
import android.os.Trace
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * Where `image` nodes fetch from: the shared [ImageCache] in production, a
 * stub in headless tests so no socket is opened. The only seam the Material
 * renderer exposes for the network.
 */
val LocalMaterialImageSource =
    staticCompositionLocalOf<suspend (String, ImageLoader.Limits) -> Bitmap?> {
        ImageCache::get
    }

@Composable
internal actual fun rememberNodeImage(url: String): ImageBitmap? {
    val limits = ImageLoader.DEFAULT_LIMITS
    val source = LocalMaterialImageSource.current
    // LD-11: through the cache, so scrolling a lazy_column back to a seen
    // image does not re-fetch, and N nodes on one URL share one load.
    val bitmap by produceState<Bitmap?>(null, url) {
        value = source(url, limits)
    }
    return bitmap?.asImageBitmap()
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

internal actual fun parseIsoDateUtc(iso: String): Long? {
    val parts = iso.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val mo = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(y, mo - 1, d)
    }.timeInMillis
}

internal actual fun isoDateFromUtcMillis(millis: Long): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        .apply { timeInMillis = millis }
    return String.format("%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH))
}

internal actual fun utcCalendarDayOfWeek(utcMillis: Long): Int {
    val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    c.timeInMillis = utcMillis
    return c.get(Calendar.DAY_OF_WEEK)
}

internal actual fun utcYear(utcMillis: Long): Int =
    Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        .apply { timeInMillis = utcMillis }.get(Calendar.YEAR)

internal actual fun DrawScope.drawCanvasText(
    text: String, x: Float, y: Float, textSizePx: Float, color: Color,
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
