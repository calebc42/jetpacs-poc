// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
package com.calebc42.jetpacs.material3

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedToggleButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.model.*
import com.calebc42.ebp.renderer.model.vocabulary.NodeProperties
import com.calebc42.ebp.renderer.model.vocabulary.generated.SliderMembers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// D1 placeholders on JetBrains' Material line (docs/PLAN-desktop-d1-renderers.md).

/** The variant with JetBrains' default shapes; D3 ports the shape sets. */
@Composable
internal actual fun ToggleButtonVariant(
    variant: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier, enabled: Boolean, minHeight: Dp?, square: Boolean,
    checkedShapeName: String, shapeRole: String, contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    when (variant) {
        "elevated" -> ElevatedToggleButton(
            checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier, enabled = enabled) { content() }
        "tonal" -> FilledTonalToggleButton(
            checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier, enabled = enabled) { content() }
        "outlined" -> OutlinedToggleButton(
            checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier, enabled = enabled) { content() }
        else -> ToggleButton(
            checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier, enabled = enabled) { content() }
    }
}

/** A plain item; the checked icon takes the leading slot while checked. */
@Composable
internal actual fun CheckableMenuItem(
    label: String, checked: Boolean, onCheckedChange: () -> Unit,
    supportingText: (@Composable () -> Unit)?, index: Int?, count: Int,
    leadingIcon: (@Composable () -> Unit)?, checkedIconName: String,
    trailingContent: (@Composable () -> Unit)?, enabled: Boolean,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onCheckedChange,
        leadingIcon = if (checked) {
            { Icon(IconMap.get(checkedIconName), null, Modifier.size(18.dp)) }
        } else leadingIcon,
        trailingIcon = trailingContent,
        enabled = enabled)
}

@Composable
internal actual fun ExposedDropdownMenuBoxScope.OptionsMenu(
    expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
}

/**
 * A plain slider or range slider: value, bounds, authored `values` steps and
 * the commit semantics of §17.4 (state first, then on_change, once, on
 * release). Labels, track icons, tints, the centred track and the vertical
 * rail wait for D3.
 */
@Composable
internal actual fun RenderSlider(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val p = NodeProperties(node, SliderMembers)
    val id = p[SliderMembers.Id].orEmpty()
    val enabled = p.getOr(SliderMembers.Enabled, true)
    val onChange = p[SliderMembers.OnChange]
    val values = p.raw(SliderMembers.Values) as? JsonArray
    val min = p.getOr(SliderMembers.Min, 0.0).toFloat()
    val max = p.getOr(SliderMembers.Max, 1.0).toFloat()
    fun publish(v: JsonElement) {
        ctx.state(id, v)
        if (onChange != null) ctx.action(onChange, v)
    }
    // With authored `values`, publish the EXACT authored number the thumb
    // settles nearest to, never toolkit step arithmetic.
    fun nearestAuthored(x: Float): JsonElement? =
        values?.minByOrNull { v ->
            val n = v.numOrNull()?.toFloat() ?: return@minByOrNull Float.MAX_VALUE
            kotlin.math.abs(n - x)
        }
    val lo = if (values != null && values.isNotEmpty())
        values.first().numOrNull()?.toFloat() ?: min else min
    val hi = if (values != null && values.isNotEmpty())
        values.last().numOrNull()?.toFloat() ?: max else max
    val steps = if (values != null) (values.size - 2).coerceAtLeast(0) else 0
    if (p.has(SliderMembers.ValueEnd)) {
        var range by remember(ctx.surface, id, ctx.epochOf(id)) {
            val draft = (ctx.storeValue(id) as? JsonArray)?.takeIf { it.size == 2 }
            val start = draft?.get(0)?.numOrNull()?.toFloat()
                ?: p.getOr(SliderMembers.Value, lo.toDouble()).toFloat()
            val end = draft?.get(1)?.numOrNull()?.toFloat()
                ?: p.getOr(SliderMembers.ValueEnd, hi.toDouble()).toFloat()
            mutableStateOf(start..end)
        }
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            modifier = m.fillMaxWidth(),
            enabled = enabled,
            valueRange = lo..hi,
            steps = steps,
            onValueChangeFinished = {
                val a = nearestAuthored(range.start)
                    ?: JsonPrimitive(range.start.toDouble())
                val b = nearestAuthored(range.endInclusive)
                    ?: JsonPrimitive(range.endInclusive.toDouble())
                publish(JsonArray(listOf(a, b)))
            })
    } else {
        var pos by remember(ctx.surface, id, ctx.epochOf(id)) {
            mutableFloatStateOf(
                ctx.storeValue(id)?.numOrNull()?.toFloat()
                    ?: p.getOr(SliderMembers.Value, lo.toDouble()).toFloat())
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            modifier = m.fillMaxWidth(),
            enabled = enabled,
            valueRange = lo..hi,
            steps = steps,
            onValueChangeFinished = {
                publish(nearestAuthored(pos) ?: JsonPrimitive(pos.toDouble()))
            })
    }
}
