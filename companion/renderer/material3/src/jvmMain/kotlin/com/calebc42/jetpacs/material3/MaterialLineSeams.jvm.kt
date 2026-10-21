// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
package com.calebc42.jetpacs.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.VerticalSlider

// JetBrains' Material line, as the desktop links it.

@Composable
internal actual fun TonalToggle(
    checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier,
    enabled: Boolean, shapes: ToggleButtonShapes, contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    FilledTonalToggleButton(checked, onCheckedChange, modifier,
        enabled = enabled, shapes = shapes, contentPadding = contentPadding) { content() }
}

/** The checked container is the square token on this line; the resting one is round. */
@Composable
internal actual fun toggleShape(square: Boolean): Shape =
    if (square) ToggleButtonDefaults.checkedShape else ToggleButtonDefaults.shape

/** No checkable item on this line: a plain item carries the label, the
 * supporting text beneath it, the checked icon in the leading slot while
 * checked, and the toggle state in its semantics. */
@Composable
internal actual fun CheckableMenuItem(
    label: String, checked: Boolean, onCheckedChange: () -> Unit,
    supportingText: (@Composable () -> Unit)?, index: Int?, count: Int,
    leadingIcon: (@Composable () -> Unit)?, checkedIconName: String,
    trailingContent: (@Composable () -> Unit)?, enabled: Boolean,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label)
                if (supportingText != null) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall) { supportingText() }
                }
            }
        },
        onClick = onCheckedChange,
        modifier = Modifier.semantics {
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        },
        leadingIcon = if (checked) {
            { Icon(IconMap.get(checkedIconName), null, Modifier.size(18.dp)) }
        } else leadingIcon,
        trailingIcon = trailingContent,
        enabled = enabled)
}

internal actual fun sliderState(
    value: Float, steps: Int, range: ClosedFloatingPointRange<Float>,
): SliderState = SliderState(value, steps, range)

internal actual fun rangeSliderState(
    start: Float, end: Float, steps: Int, range: ClosedFloatingPointRange<Float>,
): RangeSliderState = RangeSliderState(start, end, steps, range)

@Composable
internal actual fun StateSlider(
    state: SliderState, onValueChangeFinished: () -> Unit, enabled: Boolean,
    colors: SliderColors, interactionSource: MutableInteractionSource,
    thumb: @Composable (SliderState) -> Unit, track: @Composable (SliderState) -> Unit,
    modifier: Modifier,
) {
    Slider(state = state, modifier = modifier, enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, colors = colors,
        interactionSource = interactionSource, thumb = thumb, track = track)
}

/** This line's VerticalSlider takes no finish callback and keeps the state's
 * own private, so the commit is derived from the thumb's interactions: a drag
 * that stops or cancels, or a press that lifts, is the release. */
@Composable
internal actual fun StateVerticalSlider(
    state: SliderState, onValueChangeFinished: () -> Unit, enabled: Boolean,
    colors: SliderColors, interactionSource: MutableInteractionSource,
    thumb: @Composable (SliderState) -> Unit, track: @Composable (SliderState) -> Unit,
    modifier: Modifier,
) {
    val finished by rememberUpdatedState(onValueChangeFinished)
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Stop, is DragInteraction.Cancel, is PressInteraction.Release ->
                    finished()
                else -> Unit
            }
        }
    }
    VerticalSlider(state = state, modifier = modifier, enabled = enabled, colors = colors,
        interactionSource = interactionSource, thumb = thumb, track = track)
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
    RangeSlider(state = state, modifier = modifier, enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, colors = colors,
        startThumbInteractionSource = startThumbInteractionSource,
        endThumbInteractionSource = endThumbInteractionSource,
        startThumb = startThumb, endThumb = endThumb, track = track)
}

internal actual val RangeSliderState.activeStart: Float get() = startValue
internal actual val RangeSliderState.activeEnd: Float get() = endValue
internal actual val RangeSliderState.range: ClosedFloatingPointRange<Float> get() = trackRange

@Composable
internal actual fun ExposedDropdownMenuBoxScope.OptionsMenu(
    expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
}
