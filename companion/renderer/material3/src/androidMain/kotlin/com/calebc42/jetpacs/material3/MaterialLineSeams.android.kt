// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
package com.calebc42.jetpacs.material3

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TonalToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.VerticalSlider

// Google's Material line, as the Android library always ran it.

@Composable
internal actual fun TonalToggle(
    checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier,
    enabled: Boolean, shapes: ToggleButtonShapes, contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    TonalToggleButton(checked, onCheckedChange, modifier,
        enabled = enabled, shapes = shapes, contentPadding = contentPadding) { content() }
}

@Composable
internal actual fun toggleShape(square: Boolean): Shape =
    if (square) ToggleButtonDefaults.squareShape else ToggleButtonDefaults.roundShape

@Composable
internal actual fun CheckableMenuItem(
    label: String, checked: Boolean, onCheckedChange: () -> Unit,
    supportingText: (@Composable () -> Unit)?, index: Int?, count: Int,
    leadingIcon: (@Composable () -> Unit)?, checkedIconName: String,
    trailingContent: (@Composable () -> Unit)?, enabled: Boolean,
) {
    DropdownMenuItem(
        text = { Text(label) },
        checked = checked,
        onCheckedChange = { onCheckedChange() },
        supportingText = supportingText,
        shapes = if (index != null) MenuDefaults.itemShape(index, count)
            else MenuDefaults.itemShape(0, 1),
        leadingIcon = leadingIcon,
        checkedLeadingIcon = {
            Icon(IconMap.get(checkedIconName), null, Modifier.size(18.dp))
        },
        trailingContent = trailingContent,
        enabled = enabled)
}

internal actual fun sliderState(
    value: Float, steps: Int, range: ClosedFloatingPointRange<Float>,
): SliderState = SliderState(value, steps, null, range)

internal actual fun rangeSliderState(
    start: Float, end: Float, steps: Int, range: ClosedFloatingPointRange<Float>,
): RangeSliderState = RangeSliderState(start, end, steps, null, range)

@Composable
internal actual fun StateSlider(
    state: SliderState, onValueChangeFinished: () -> Unit, enabled: Boolean,
    colors: SliderColors, interactionSource: MutableInteractionSource,
    thumb: @Composable (SliderState) -> Unit, track: @Composable (SliderState) -> Unit,
    modifier: Modifier,
) {
    state.onValueChangeFinished = onValueChangeFinished
    Slider(state = state, enabled = enabled, colors = colors,
        interactionSource = interactionSource, thumb = thumb, track = track, modifier = modifier)
}

@Composable
internal actual fun StateVerticalSlider(
    state: SliderState, onValueChangeFinished: () -> Unit, enabled: Boolean,
    colors: SliderColors, interactionSource: MutableInteractionSource,
    thumb: @Composable (SliderState) -> Unit, track: @Composable (SliderState) -> Unit,
    modifier: Modifier,
) {
    state.onValueChangeFinished = onValueChangeFinished
    VerticalSlider(state = state, enabled = enabled, colors = colors,
        interactionSource = interactionSource, thumb = thumb, track = track, modifier = modifier)
}

@Composable
internal actual fun StateRangeSlider(
    state: RangeSliderState, onValueChangeFinished: () -> Unit, enabled: Boolean,
    colors: SliderColors, startThumbInteractionSource: MutableInteractionSource,
    endThumbInteractionSource: MutableInteractionSource,
    startThumb: @Composable (RangeSliderState) -> Unit,
    endThumb: @Composable (RangeSliderState) -> Unit,
    track: @Composable (RangeSliderState) -> Unit,
    modifier: Modifier,
) {
    state.onValueChangeFinished = onValueChangeFinished
    RangeSlider(state = state, enabled = enabled, colors = colors,
        startThumbInteractionSource = startThumbInteractionSource,
        endThumbInteractionSource = endThumbInteractionSource,
        startThumb = startThumb, endThumb = endThumb, track = track, modifier = modifier)
}

internal actual val RangeSliderState.activeStart: Float get() = activeRangeStart
internal actual val RangeSliderState.activeEnd: Float get() = activeRangeEnd
internal actual val RangeSliderState.range: ClosedFloatingPointRange<Float> get() = valueRange

@Composable
internal actual fun ExposedDropdownMenuBoxScope.OptionsMenu(
    expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
}
