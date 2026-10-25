// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.model.vocabulary.NodeProperties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `jetpacs.wheel`: a stateful selector wheel over `values`, drawn by the
 * platform's own number picker. Its published value is the selected string;
 * `on_change` follows once a change settles, carrying the same value.
 */
internal object JetpacsWheelComponent : JetpacsComponent("jetpacs.wheel", JetpacsWheelMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val p = NodeProperties(node, JetpacsWheelMembers)
        val id = p[JetpacsWheelMembers.Id] ?: return
        val values = (node["values"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (values.isEmpty()) return
        val epoch = context.epochOf(id)
        var selected by rememberSaveable(context.surface, id, epoch) {
            mutableStateOf(
                (context.storeValue(id) as? JsonPrimitive)?.content?.takeIf { it in values }
                    ?: p[JetpacsWheelMembers.Value]?.takeIf { it in values }
                    ?: values.first(),
            )
        }
        val onChange = p[JetpacsWheelMembers.OnChange]
        PlatformWheel(
            values = values,
            selected = values.indexOf(selected).coerceAtLeast(0),
            wrap = p.getOr(JetpacsWheelMembers.Wrap, false),
            enabled = p.getOr(JetpacsWheelMembers.Enabled, true),
            textColor = wheelColor(p[JetpacsWheelMembers.Color].orEmpty()) ?: JetpacsTheme.colors.content,
            dividerColor = wheelColor(p[JetpacsWheelMembers.DividerColor].orEmpty())
                ?: JetpacsTheme.colors.content.copy(alpha = 0.6f),
            onSelect = { index ->
                selected = values[index]
                context.state(id, JsonPrimitive(values[index]))
            },
            onSettle = { index ->
                if (onChange != null) context.action(onChange, JsonPrimitive(values[index]))
            },
            modifier = modifier,
        )
    }

    @Composable
    private fun wheelColor(spec: String): Color? {
        if (spec.isEmpty()) return null
        DesignThemeRole.fromWireName(spec)?.let { return JetpacsTheme.roles[it] }
        return parseHexColor(spec)
    }
}

/**
 * The platform's selector wheel showing [values] with [selected] in the
 * middle. [onSelect] runs on every step; [onSettle] once the wheel stops.
 */
@Composable
internal expect fun PlatformWheel(
    values: List<String>,
    selected: Int,
    wrap: Boolean,
    enabled: Boolean,
    textColor: Color,
    dividerColor: Color,
    onSelect: (Int) -> Unit,
    onSettle: (Int) -> Unit,
    modifier: Modifier,
)
