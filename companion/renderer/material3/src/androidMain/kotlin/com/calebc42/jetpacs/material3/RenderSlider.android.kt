// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
package com.calebc42.jetpacs.material3

// The Android slider renderer, moved verbatim from InputNodes.kt: its state
// constructors, track slots and labels follow Google's Material line, which
// JetBrains' desktop build does not yet match (docs/PLAN-desktop-d1-renderers.md).
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

/**
 * §17.4 slider. Continuous: min/max/value, dispatching once on gesture
 * commit. Discrete: `values` (strictly increasing authored numbers) — the
 * thumb moves over indices and the EXACT authored number is returned, never
 * toolkit step arithmetic. `value_end` present makes it a RangeSlider and
 * the published value a two-number array; with `values` authored each thumb
 * SNAPS to the nearest authored number on commit, keeping the discrete rule.
 * `orientation` vertical renders M3's VerticalSlider and deliberately does
 * NOT fillMaxWidth — the author's universal height is the length of the
 * rail. `value_label` wraps the thumb in M3's Label/PlainTooltip showing
 * the in-flight position, which never crosses the wire (dispatch stays on
 * commit). `track_icon_start`/`track_icon_end` reproduce the M3 sample's
 * drawWithContent recipe: icons at both edges of each track segment, tinted
 * active/inactive, suppressed when the segment is narrower than the icon.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
internal actual fun RenderSlider(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val p = NodeProperties(node, SliderMembers)
    val id = p[SliderMembers.Id].orEmpty()
    val enabled = p.getOr(SliderMembers.Enabled, true)
    val onChange = p[SliderMembers.OnChange]
    // Read raw on purpose: the EXACT authored elements are republished on
    // commit (spelling preserved), which a numeric projection would lose.
    val values = p.raw(SliderMembers.Values) as? JsonArray
    // §17.4 presentation members. `color` tints the thumb and the active
    // track together, which is the pair every upstream custom-colour sample
    // sets; `color_end` tints the END thumb of a range alone; `track` picks
    // M3's centred track; `thumb_icon` names a vector, since IconMap is the
    // only path a drawable reaches the device.
    val tint = resolveColor(p[SliderMembers.Color].orEmpty().takeIf { it.isNotEmpty() })
    val endTint = resolveColor(p[SliderMembers.ColorEnd].orEmpty().takeIf { it.isNotEmpty() })
    val sliderColors = if (tint != null)
        SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint)
        else SliderDefaults.colors()
    val endColors = if (endTint != null)
        SliderDefaults.colors(thumbColor = endTint) else sliderColors
    val centered = p[SliderMembers.Track]?.wire.orEmpty() == "centered"
    val vertical = p[SliderMembers.Orientation]?.wire.orEmpty() == "vertical"
    val showLabel = p.getOr(SliderMembers.ValueLabel, false)
    val thumbIconName = p[SliderMembers.ThumbIcon].orEmpty()
    val startIconName = p[SliderMembers.TrackIconStart].orEmpty()
    val endIconName = p[SliderMembers.TrackIconEnd].orEmpty()
    // SliderDefaults.Thumb wants the slider's OWN interaction source, so it is
    // hoisted and handed to both — otherwise the default thumb loses its
    // press/hover feedback the moment we supply the slot at all.
    val interaction = remember { MutableInteractionSource() }
    fun publish(v: JsonElement) {
        ctx.state(id, v)
        if (onChange != null) ctx.action(onChange, v)
    }
    // The upstream SliderWithTrackIconsSample recipe, driven by two icon
    // NAMES: drawn at both edges of the active and inactive segments, active
    // tick colour on the active side, suppressed when a segment is narrower
    // than icon + padding. No DrawScope ever crosses the wire.
    val trackIcons = startIconName.isNotEmpty() || endIconName.isNotEmpty()
    val startPainter = if (startIconName.isNotEmpty())
        rememberVectorPainter(IconMap.get(startIconName)) else null
    val endPainter = if (endIconName.isNotEmpty())
        rememberVectorPainter(IconMap.get(endIconName)) else null
    val activeIconColor = sliderColors.activeTickColor
    val inactiveIconColor = sliderColors.inactiveTickColor
    fun trackIconModifier(fraction: () -> Float): Modifier =
        if (!trackIcons) Modifier else Modifier.height(36.dp).drawWithContent {
            drawContent()
            val iconSize = Size(20.dp.toPx(), 20.dp.toPx())
            val iconPad = 10.dp.toPx()
            val gap = 6.dp.toPx()
            val yOffset = size.height / 2 - iconSize.height / 2
            fun drawPair(startX: Float, endX: Float, color: Color) {
                if (iconSize.width >= endX - startX - iconPad * 2) return
                startPainter?.let {
                    translate(startX + iconPad, yOffset) {
                        with(it) { draw(iconSize, colorFilter = ColorFilter.tint(color)) }
                    }
                }
                endPainter?.let {
                    translate(endX - iconPad - iconSize.width, yOffset) {
                        with(it) { draw(iconSize, colorFilter = ColorFilter.tint(color)) }
                    }
                }
            }
            val activeEnd = size.width * fraction() - gap
            drawPair(0f, activeEnd, activeIconColor)
            drawPair(activeEnd + gap * 2, size.width, inactiveIconColor)
        }
    // The label is presentation only: the in-flight position shows over the
    // thumb and dispatch still happens once, on commit (§17.4). Each thumb
    // hands its OWN interaction source, or a range's end label would pop
    // while the start thumb is the one being dragged.
    val labelledThumb: @Composable (source: MutableInteractionSource, position: () -> Float, content: @Composable () -> Unit) -> Unit =
        { source, position, content ->
            if (showLabel)
                Label(label = {
                    PlainTooltip(Modifier.sizeIn(45.dp, 25.dp).wrapContentWidth()) {
                        Text("%.2f".format(position()))
                    }
                }, interactionSource = source) { content() }
            else content()
        }
    val defaultThumb: @Composable () -> Unit = {
        if (thumbIconName.isNotEmpty())
            Icon(IconMap.get(thumbIconName), null,
                Modifier.size(24.dp), tint = tint ?: LocalContentColor.current)
        else SliderDefaults.Thumb(interaction, colors = sliderColors,
            enabled = enabled)
    }
    val min = p.getOr(SliderMembers.Min, 0.0).toFloat()
    val max = p.getOr(SliderMembers.Max, 1.0).toFloat()
    fun nearestAuthored(x: Float): JsonElement? =
        values?.minByOrNull { v ->
            val n = v.numOrNull()?.toFloat() ?: return@minByOrNull Float.MAX_VALUE
            kotlin.math.abs(n - x)
        }
    if (p.has(SliderMembers.ValueEnd)) {
        // ---- RangeSlider: two thumbs, a two-number array on the wire.
        val lo = if (values != null && values.isNotEmpty())
            values.first().numOrNull()?.toFloat() ?: min else min
        val hi = if (values != null && values.isNotEmpty())
            values.last().numOrNull()?.toFloat() ?: max else max
        val steps = if (values != null) (values.size - 2).coerceAtLeast(0) else 0
        val state = remember(ctx.surface, id, ctx.epochOf(id)) {
            val draft = (ctx.storeValue(id) as? JsonArray)?.takeIf { it.size == 2 }
            val start = draft?.get(0)?.numOrNull()?.toFloat()
                ?: p.getOr(SliderMembers.Value, lo.toDouble()).toFloat()
            val end = draft?.get(1)?.numOrNull()?.toFloat()
                ?: p.getOr(SliderMembers.ValueEnd, hi.toDouble()).toFloat()
            RangeSliderState(start, end, steps = steps, valueRange = lo..hi)
        }
        state.onValueChangeFinished = {
            // With authored `values`, publish the EXACT authored numbers the
            // thumbs settle nearest to — never toolkit step arithmetic.
            val a = nearestAuthored(state.activeRangeStart)
                ?: JsonPrimitive(state.activeRangeStart.toDouble())
            val b = nearestAuthored(state.activeRangeEnd)
                ?: JsonPrimitive(state.activeRangeEnd.toDouble())
            publish(JsonArray(listOf(a, b)))
        }
        val endInteraction = remember { MutableInteractionSource() }
        RangeSlider(
            state = state,
            enabled = enabled,
            colors = sliderColors,
            startThumbInteractionSource = interaction,
            endThumbInteractionSource = endInteraction,
            startThumb = {
                labelledThumb(interaction, { state.activeRangeStart }) { defaultThumb() }
            },
            endThumb = {
                // Each thumb carries its own Label upstream, so the end
                // thumb is wrapped too — reading its own end of the range.
                labelledThumb(endInteraction, { state.activeRangeEnd }) {
                    if (endTint != null)
                        SliderDefaults.Thumb(endInteraction, colors = endColors,
                            enabled = enabled)
                    else SliderDefaults.Thumb(endInteraction, colors = sliderColors,
                        enabled = enabled)
                }
            },
            track = { st ->
                SliderDefaults.Track(rangeSliderState = st, colors = sliderColors,
                    modifier = trackIconModifier {
                        (st.activeRangeEnd - st.valueRange.start) /
                            (st.valueRange.endInclusive - st.valueRange.start)
                    })
            },
            modifier = m.fillMaxWidth())
    } else if (vertical) {
        // ---- VerticalSlider: the author's height is the rail length, so no
        // fillMaxWidth — the flattening the audit warned about.
        val lo = if (values != null && values.isNotEmpty())
            values.first().numOrNull()?.toFloat() ?: min else min
        val hi = if (values != null && values.isNotEmpty())
            values.last().numOrNull()?.toFloat() ?: max else max
        val steps = if (values != null) (values.size - 2).coerceAtLeast(0) else 0
        val state = remember(ctx.surface, id, ctx.epochOf(id)) {
            val seed = ctx.storeValue(id)?.numOrNull()?.toFloat()
                ?: p.getOr(SliderMembers.Value, lo.toDouble()).toFloat()
            SliderState(seed, steps = steps, valueRange = lo..hi)
        }
        state.onValueChangeFinished = {
            publish(nearestAuthored(state.value)
                ?: JsonPrimitive(state.value.toDouble()))
        }
        VerticalSlider(
            state = state,
            enabled = enabled,
            colors = sliderColors,
            interactionSource = interaction,
            thumb = { labelledThumb(interaction, { state.value }) { defaultThumb() } },
            track = { st ->
                if (centered) SliderDefaults.CenteredTrack(sliderState = st,
                    colors = sliderColors)
                else SliderDefaults.Track(sliderState = st, colors = sliderColors)
            },
            modifier = m)
    } else if (values != null && values.size >= 2) {
        val n = values.size
        fun seedIndex(): Int {
            // C6: the org.json gate was `as? Number ?: return 0` — keep only a
            // NUMERIC element, so a string/boolean/null draft seeds index 0 as
            // before. jsonValueEquals then does the §4.3 numeric compare.
            val v = (ctx.storeValue(id) ?: p.raw(SliderMembers.Value))
                ?.takeIf { it.numOrNull() != null } ?: return 0
            for (i in 0 until n)
                if (jsonValueEquals(values[i], v)) return i
            return 0
        }
        var index by remember(ctx.surface, id, ctx.epochOf(id)) { mutableIntStateOf(seedIndex()) }
        Slider(
            value = index.toFloat(),
            onValueChange = { index = it.toInt().coerceIn(0, n - 1) },
            onValueChangeFinished = {
                val exact = values[index] // the authored number, exactly
                publish(exact)
            },
            valueRange = 0f..(n - 1).toFloat(),
            steps = (n - 2).coerceAtLeast(0),
            enabled = enabled,
            colors = sliderColors,
            track = { st ->
                if (centered) SliderDefaults.CenteredTrack(st, colors = sliderColors)
                else SliderDefaults.Track(st, colors = sliderColors,
                    modifier = trackIconModifier { st.coercedValueAsFraction })
            },
            interactionSource = interaction,
            thumb = {
                labelledThumb(interaction, { values[index].numOrNull()?.toFloat() ?: 0f }) {
                    defaultThumb()
                }
            },
            modifier = m.fillMaxWidth())
    } else {
        var pos by remember(ctx.surface, id, ctx.epochOf(id)) {
            mutableFloatStateOf(
                (ctx.storeValue(id)?.numOrNull()?.toFloat()
                    ?: p.getOr(SliderMembers.Value, min.toDouble()).toFloat()))
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = {
                publish(JsonPrimitive(pos.toDouble()))
            },
            valueRange = min..max,
            enabled = enabled,
            colors = sliderColors,
            track = { st ->
                if (centered) SliderDefaults.CenteredTrack(st, colors = sliderColors)
                else SliderDefaults.Track(st, colors = sliderColors,
                    modifier = trackIconModifier { st.coercedValueAsFraction })
            },
            interactionSource = interaction,
            thumb = { labelledThumb(interaction, { pos }) { defaultThumb() } },
            modifier = m.fillMaxWidth())
    }
}
