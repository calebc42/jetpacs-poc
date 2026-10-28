// SPDX-License-Identifier: GPL-3.0-or-later
// `material3.chronometer`: a running timer the Companion ticks itself, so a
// clock on screen counts every second with no traffic from Emacs.  Elapsed
// time since base_ms, or time left until it when counting down (held at zero
// past the end).  Formatting is pure and JVM-tested.
package com.calebc42.jetpacs.material3

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.calebc42.ebp.renderer.model.vocabulary.NodeProperties
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** H:MM:SS from an hour on, else M:SS; negative spans read as zero. */
fun formatChronometer(spanMs: Long): String {
    val total = (spanMs.coerceAtLeast(0L)) / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** The span a chronometer shows at NOW: elapsed, or remaining when counting down. */
fun chronometerSpan(baseMs: Long, countDown: Boolean, nowMs: Long): Long =
    if (countDown) baseMs - nowMs else nowMs - baseMs

@Composable
internal fun RenderChronometer(node: JsonObject, m: Modifier) {
    val p = NodeProperties(node, Material3ChronometerMembers)
    // Epoch milliseconds: a manifest `number`, exact in a Double.
    val base = p[Material3ChronometerMembers.BaseMs]?.toLong() ?: return
    val countDown = p.getOr(Material3ChronometerMembers.CountDown, false)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(base, countDown) {
        while (true) {
            now = System.currentTimeMillis()
            // Wake on the next whole second of the span so digits change together.
            val phase = Math.floorMod(chronometerSpan(base, countDown, now), 1000L)
            delay(if (countDown) phase.coerceAtLeast(1L) else 1000L - phase)
        }
    }
    val text = formatChronometer(chronometerSpan(base, countDown, now))
    val style = textStyleForName(p[Material3ChronometerMembers.Style]?.wire ?: "body")
    val color = resolveColor(p[Material3ChronometerMembers.Color])
    val spoken = p[Material3ChronometerMembers.ContentDescription]?.takeIf { it.isNotEmpty() }
    Text(
        text,
        modifier = if (spoken != null) m.semantics { contentDescription = "$spoken $text" } else m,
        style = style,
        color = color ?: androidx.compose.ui.graphics.Color.Unspecified,
    )
}
