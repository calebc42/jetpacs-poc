// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The desktop has no platform selector wheel: the choices before and after
 * the selection sit dimmed above and below it, and a click on either steps.
 */
@Composable
internal actual fun PlatformWheel(
    values: List<String>,
    selected: Int,
    wrap: Boolean,
    enabled: Boolean,
    textColor: Color,
    dividerColor: Color,
    onSelect: (Int) -> Unit,
    onSettle: (Int) -> Unit,
    modifier: Modifier,
) {
    fun neighbour(step: Int): Int? {
        val next = selected + step
        return when {
            next in values.indices -> next
            wrap -> (next + values.size) % values.size
            else -> null
        }
    }
    Column(modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (step in -1..1) {
            val index = neighbour(step)
            val style = TextStyle(
                color = if (step == 0) textColor else textColor.copy(alpha = 0.4f),
                fontSize = 18.sp, textAlign = TextAlign.Center,
            )
            BasicText(
                index?.let { values[it] }.orEmpty(), style = style,
                modifier = Modifier.height(48.dp).wrapContentHeight().let {
                    if (index == null || step == 0 || !enabled) it
                    else it.clickable { onSelect(index); onSettle(index) }
                },
            )
        }
    }
}
