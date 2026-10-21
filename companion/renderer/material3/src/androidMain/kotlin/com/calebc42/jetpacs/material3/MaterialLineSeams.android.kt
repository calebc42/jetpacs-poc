// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
package com.calebc42.jetpacs.material3

// The Android side of the Material-line seams (docs/PLAN-desktop-d1-renderers.md):
// the code the Android library ran, moved verbatim. The import block is the
// renderer's own, shared with RenderSlider.android.kt.
import com.calebc42.ebp.renderer.model.vocabulary.BooleanOrAuto
import com.calebc42.ebp.renderer.model.vocabulary.NodeProperties
import com.calebc42.ebp.renderer.model.vocabulary.StringOrNumber
import com.calebc42.ebp.renderer.model.vocabulary.generated.*
import com.calebc42.ebp.renderer.model.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ElevatedSuggestionChip
import androidx.compose.material3.ElevatedToggleButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Label
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalWideNavigationRail
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.text.TextStyle
import com.calebc42.ebp.renderer.compose.LocalComposeChromeStyles
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.wire.SurfaceStore
import com.calebc42.ebp.wire.jsonValueEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal actual fun ToggleButtonVariant(
    variant: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier, enabled: Boolean, minHeight: Dp?, square: Boolean,
    checkedShapeName: String, shapeRole: String, contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    val base = ToggleButtonDefaults.shapesFor(minHeight ?: ButtonDefaults.MinHeight)
    // §17.4 `shape_role`: one position of a CONNECTED group. The M3
    // leading/middle/trailing shape sets carry the caps, the press morph
    // and the checked morph; top/bottom are the vertical caps upstream
    // builds by copying CornerSize(100) onto the middle shape.
    val roleShapes: ToggleButtonShapes? = when (shapeRole) {
        "leading" -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        "middle" -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        "trailing" -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        "top", "bottom" -> {
            val mid = ButtonGroupDefaults.connectedMiddleButtonShapes()
            val midShape = mid.shape as? RoundedCornerShape
            val capped = if (midShape != null) {
                if (shapeRole == "top")
                    midShape.copy(topStart = CornerSize(100), topEnd = CornerSize(100))
                else
                    midShape.copy(bottomStart = CornerSize(100), bottomEnd = CornerSize(100))
            } else mid.shape
            ToggleButtonShapes(shape = capped,
                pressedShape = mid.pressedShape,
                checkedShape = ButtonGroupDefaults.connectedButtonCheckedShape)
        }
        else -> null
    }
    val tShapes = if (roleShapes != null) roleShapes
    else if (square || checkedShapeName.isNotEmpty())
        ToggleButtonShapes(
            shape = if (square) ToggleButtonDefaults.squareShape
                else ToggleButtonDefaults.roundShape,
            pressedShape = base.pressedShape,
            checkedShape = when (checkedShapeName) {
                "round" -> ToggleButtonDefaults.roundShape
                "square" -> ToggleButtonDefaults.squareShape
                else -> base.checkedShape
            })
    else base
    when (variant) {
        "elevated" -> ElevatedToggleButton(checked, onCheckedChange, modifier,
            enabled, shapes = tShapes, contentPadding = contentPadding) { content() }
        "tonal" -> TonalToggleButton(checked, onCheckedChange, modifier,
            enabled, shapes = tShapes, contentPadding = contentPadding) { content() }
        "outlined" -> OutlinedToggleButton(checked, onCheckedChange, modifier,
            enabled, shapes = tShapes, contentPadding = contentPadding) { content() }
        else -> ToggleButton(checked, onCheckedChange, modifier,
            enabled, shapes = tShapes, contentPadding = contentPadding) { content() }
    }
}

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

@Composable
internal actual fun ExposedDropdownMenuBoxScope.OptionsMenu(
    expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
}
