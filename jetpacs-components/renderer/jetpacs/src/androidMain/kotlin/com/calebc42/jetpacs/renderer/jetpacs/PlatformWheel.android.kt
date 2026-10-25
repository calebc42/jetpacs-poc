// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import android.widget.NumberPicker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Android's NumberPicker, as an AlertDialog's period pickers show it. The
 * picker's own dividers come from its theme and cannot be recolored, so it
 * draws none and the two 2dp lines are drawn here, 48dp apart around the
 * middle row as the platform places them.
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
    val select by rememberUpdatedState(onSelect)
    val settle by rememberUpdatedState(onSettle)
    Box(
        modifier.width(64.dp).drawWithContent {
            drawContent()
            val gap = 48.dp.toPx()
            val stroke = 2.dp.toPx()
            val middle = size.height / 2f
            for (y in listOf(middle - gap / 2f - stroke / 2f, middle + gap / 2f - stroke / 2f)) {
                drawLine(dividerColor, Offset(0f, y), Offset(size.width, y), stroke)
            }
        },
    ) {
        AndroidView(
            factory = { context ->
                NumberPicker(context).apply {
                    selectionDividerHeight = 0
                    descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                    var scrolling = false
                    setOnValueChangedListener { _, _, next ->
                        select(next)
                        if (!scrolling) settle(next)
                    }
                    setOnScrollListener { picker, state ->
                        scrolling = state != NumberPicker.OnScrollListener.SCROLL_STATE_IDLE
                        if (!scrolling) settle(picker.value)
                    }
                }
            },
            update = { picker ->
                // displayedValues must shrink before the range does.
                if (picker.displayedValues?.toList() != values) {
                    picker.displayedValues = null
                    picker.minValue = 0
                    picker.maxValue = values.size - 1
                    picker.displayedValues = values.toTypedArray()
                }
                picker.wrapSelectorWheel = wrap
                if (picker.value != selected) picker.value = selected
                picker.isEnabled = enabled
                picker.textColor = textColor.toArgb()
            },
        )
    }
}
