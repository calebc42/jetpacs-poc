// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderState
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

// Seams for the drift between Google's Material 3 1.5.0-alpha25, which Android
// links, and JetBrains' 1.13.0-alpha02, which the desktop links
// (docs/PLAN-desktop-d1-renderers.md; narrowed by D3 of
// docs/PLAN-desktop-companion.md to the calls that differ). When the two
// lines converge the seams fold back into InputNodes.kt.

/** M3's tonal toggle button: `TonalToggleButton` on one line, `FilledTonalToggleButton` on the other. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal expect fun TonalToggle(
    checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier,
    enabled: Boolean, shapes: ToggleButtonShapes, contentPadding: PaddingValues,
    content: @Composable () -> Unit,
)

/** The toggle button's square or round container shape. Google names both;
 * JetBrains' nearest tokens are its checked (square) and resting (round) shapes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal expect fun toggleShape(square: Boolean): Shape

/**
 * §17.4 menu item with `checked`. Google's expressive checkable item shows the
 * checked icon in place of the leading one; JetBrains' line has no checkable
 * item, so the desktop composes a plain item whose leading slot shows the
 * checked icon, with the supporting text beneath the label and the toggle
 * state in its semantics.
 */
@Composable
internal expect fun CheckableMenuItem(
    label: String, checked: Boolean, onCheckedChange: () -> Unit,
    supportingText: (@Composable () -> Unit)?, index: Int?, count: Int,
    leadingIcon: (@Composable () -> Unit)?, checkedIconName: String,
    trailingContent: (@Composable () -> Unit)?, enabled: Boolean,
)

/** M3's slider states: Google's constructors take the finish callback,
 * JetBrains' do not; both set it on the state afterwards. */
@OptIn(ExperimentalMaterial3Api::class)
internal expect fun sliderState(
    value: Float, steps: Int, range: ClosedFloatingPointRange<Float>,
): SliderState

@OptIn(ExperimentalMaterial3Api::class)
internal expect fun rangeSliderState(
    start: Float, end: Float, steps: Int, range: ClosedFloatingPointRange<Float>,
): RangeSliderState

/**
 * M3's state-based sliders. Google's line sets the finish callback on the
 * state and exposes a range's active ends; JetBrains' takes the callback as a
 * parameter and exposes the ends as start and end values. The slider body in
 * InputNodes.kt is common; only these forwarding calls differ.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal expect fun StateSlider(
    state: SliderState, onValueChangeFinished: () -> Unit, enabled: Boolean,
    colors: SliderColors, interactionSource: MutableInteractionSource,
    thumb: @Composable (SliderState) -> Unit, track: @Composable (SliderState) -> Unit,
    modifier: Modifier,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal expect fun StateVerticalSlider(
    state: SliderState, onValueChangeFinished: () -> Unit, enabled: Boolean,
    colors: SliderColors, interactionSource: MutableInteractionSource,
    thumb: @Composable (SliderState) -> Unit, track: @Composable (SliderState) -> Unit,
    modifier: Modifier,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal expect fun StateRangeSlider(
    state: RangeSliderState, onValueChangeFinished: () -> Unit, enabled: Boolean,
    colors: SliderColors, startThumbInteractionSource: MutableInteractionSource,
    endThumbInteractionSource: MutableInteractionSource,
    startThumb: @Composable (RangeSliderState) -> Unit,
    endThumb: @Composable (RangeSliderState) -> Unit,
    track: @Composable (RangeSliderState) -> Unit,
    modifier: Modifier,
)

@OptIn(ExperimentalMaterial3Api::class)
internal expect val RangeSliderState.activeStart: Float

@OptIn(ExperimentalMaterial3Api::class)
internal expect val RangeSliderState.activeEnd: Float

@OptIn(ExperimentalMaterial3Api::class)
internal expect val RangeSliderState.range: ClosedFloatingPointRange<Float>

/** §17.4 dropdown: the options popup anchored to the field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal expect fun ExposedDropdownMenuBoxScope.OptionsMenu(
    expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
)
