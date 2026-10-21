// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlinx.serialization.json.JsonObject

// Seams for the drift between Google's Material 3 1.5.0-alpha25, which Android
// links, and JetBrains' 1.13.0-alpha02, which the desktop links
// (docs/PLAN-desktop-d1-renderers.md). Each Android actual is the code the
// Android library ran, verbatim; each JVM actual is a placeholder that D3
// of docs/PLAN-desktop-companion.md replaces. When the two lines converge the
// seams fold back into InputNodes.kt.

/**
 * §17.4 toggle button: M3's ToggleButton family with the resting, pressed and
 * checked shape set that `shape`, `checked_shape` and `shape_role` select.
 * The desktop placeholder draws the variant with its default shapes.
 */
@Composable
internal expect fun ToggleButtonVariant(
    variant: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier, enabled: Boolean, minHeight: Dp?, square: Boolean,
    checkedShapeName: String, shapeRole: String, contentPadding: PaddingValues,
    content: @Composable () -> Unit,
)

/**
 * §17.4 menu item with `checked`: the expressive checkable item whose checked
 * icon replaces the leading one. The desktop placeholder is a plain item that
 * shows the checked icon in the leading slot.
 */
@Composable
internal expect fun CheckableMenuItem(
    label: String, checked: Boolean, onCheckedChange: () -> Unit,
    supportingText: (@Composable () -> Unit)?, index: Int?, count: Int,
    leadingIcon: (@Composable () -> Unit)?, checkedIconName: String,
    trailingContent: (@Composable () -> Unit)?, enabled: Boolean,
)

/** §17.4 dropdown: the options popup anchored to the field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal expect fun ExposedDropdownMenuBoxScope.OptionsMenu(
    expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
)

/**
 * §17.4 slider and range slider. Android renders every member: value labels,
 * track icons, tints, the centred track and the vertical rail. The desktop
 * placeholder renders a plain slider or range slider with the wire's value,
 * bounds, steps and commit semantics.
 */
@Composable
internal expect fun RenderSlider(node: JsonObject, ctx: RenderCtx, m: Modifier)
