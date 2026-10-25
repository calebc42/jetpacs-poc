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
import androidx.compose.runtime.getValue
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

internal actual val hasPlatformPickers: Boolean = true

@Composable
private fun platformPickerContext(): android.content.Context {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return androidx.compose.runtime.remember(context, configuration, dark) {
        android.view.ContextThemeWrapper(
            context,
            if (dark) R.style.Jetpacs_PlatformPicker_Dark else R.style.Jetpacs_PlatformPicker_Light,
        ).apply {
            // The window's own configuration: a split-screen pane is portrait
            // even where the activity's resources still say landscape, and
            // the pickers lay their header out by orientation.
            applyOverrideConfiguration(android.content.res.Configuration(configuration))
        }
    }
}

@Composable
internal actual fun PlatformDateDialog(value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = platformPickerContext()
    val pick by androidx.compose.runtime.rememberUpdatedState(onPick)
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    androidx.compose.runtime.DisposableEffect(context) {
        val parts = value.split("-").mapNotNull { it.toIntOrNull() }
        val today = java.util.Calendar.getInstance()
        val dialog = android.app.DatePickerDialog(
            context,
            { _, year, month, day -> pick(String.format("%04d-%02d-%02d", year, month + 1, day)) },
            parts.getOrNull(0) ?: today.get(java.util.Calendar.YEAR),
            (parts.getOrNull(1) ?: (today.get(java.util.Calendar.MONTH) + 1)) - 1,
            parts.getOrNull(2) ?: today.get(java.util.Calendar.DAY_OF_MONTH),
        )
        dialog.setOnDismissListener { dismiss() }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}

@Composable
internal actual fun PlatformTimeDialog(hour: Int, minute: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = platformPickerContext()
    val pick by androidx.compose.runtime.rememberUpdatedState(onPick)
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    androidx.compose.runtime.DisposableEffect(context) {
        val dialog = android.app.TimePickerDialog(
            context,
            { _, h, m -> pick(String.format("%02d:%02d", h, m)) },
            hour, minute, android.text.format.DateFormat.is24HourFormat(context),
        )
        dialog.setOnDismissListener { dismiss() }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
