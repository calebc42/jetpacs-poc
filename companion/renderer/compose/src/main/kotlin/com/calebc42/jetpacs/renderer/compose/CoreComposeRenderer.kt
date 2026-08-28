// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.wire.CORE_NODE_SET
import com.calebc42.jetpacs.renderer.model.RendererContribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The renderer-neutral Compose contribution required by every app target. */
val CoreComposeContribution = RendererContribution(
    id = "compose.core",
    nodeTypes = CORE_NODE_SET,
)

/** Effects owned by the host; core Compose only presents accepted EBP data. */
fun interface CoreComposeActionSink {
    fun dispatch(descriptor: JsonObject)
}

/**
 * Small reference renderer for EBP's Core Node Set using Compose Foundation.
 *
 * It consumes the real JSON IR and intentionally imports neither Material nor
 * a design-system token type. Product renderers can delegate these nodes or
 * replace their presentation while retaining the same protocol semantics.
 */
@Composable
fun RenderCoreComposeNode(
    node: JsonObject,
    actions: CoreComposeActionSink,
    modifier: Modifier = Modifier,
) {
    val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
    val semanticModifier = modifier.ebpSemantics(
        node = node,
        onAction = actions::dispatch,
    )
    when ((node["t"] as? JsonPrimitive)?.content) {
        "text" -> BasicText(
            (node["text"] as? JsonPrimitive)?.content.orEmpty(),
            semanticModifier,
        )
        "row" -> Row(semanticModifier) {
            children.forEach { (it as? JsonObject)?.let { child ->
                RenderCoreComposeNode(child, actions)
            } }
        }
        "column" -> Column(semanticModifier) {
            children.forEach { (it as? JsonObject)?.let { child ->
                RenderCoreComposeNode(child, actions)
            } }
        }
        "box" -> Box(semanticModifier) {
            children.forEach { (it as? JsonObject)?.let { child ->
                RenderCoreComposeNode(child, actions)
            } }
        }
        "spacer" -> Spacer(semanticModifier.size(8.dp))
        "divider" -> Spacer(semanticModifier.background(Color.Gray).size(1.dp))
        "button" -> {
            val descriptor = node["on_tap"] as? JsonObject
            Box(
                semanticModifier
                    .clickable(enabled = descriptor != null) {
                        descriptor?.let(actions::dispatch)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                BasicText((node["label"] as? JsonPrimitive)?.content.orEmpty())
            }
        }
        "text_input" -> {
            var value by remember(node) {
                mutableStateOf((node["value"] as? JsonPrimitive)?.content.orEmpty())
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = semanticModifier,
            )
        }
    }
}
