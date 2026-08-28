// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.calebc42.jetpacs.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.jetpacs.renderer.compose.ComposeNodeExtension
import com.calebc42.jetpacs.renderer.model.RendererContribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** App-surface contribution implemented by [JetpacsComponentsRenderer]. */
val JetpacsComponentsContribution = RendererContribution(
    id = "jetpacs.components.app",
    nodeTypes = JETPACS_COMPONENTS_TARGET_NODE_TYPES.getValue("app"),
    extensions = setOf(JETPACS_COMPONENTS_EXTENSION),
)

/** Compose Foundation implementation of the `jetpacs.components` extension. */
object JetpacsComponentsRenderer : ComposeNodeExtension {
    override val id: String = "jetpacs.components.compose"
    override val extensionId: String = JETPACS_COMPONENTS_EXTENSION
    override val nodeTypes: Set<String> = JETPACS_COMPONENTS_NODE_SCHEMA.keys

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        when ((node["t"] as JsonPrimitive).content) {
            "jetpacs.action" -> JetpacsAction(
                label = (node["label"] as JsonPrimitive).content,
                enabled = node.boolean("enabled", true),
                onClick = { context.action(node["on_tap"] as? JsonObject) },
                modifier = modifier,
            )
            "jetpacs.choice" -> RenderChoice(node, context, modifier)
            "jetpacs.panel" -> JetpacsPanel(
                label = (node["label"] as JsonPrimitive).content,
                modifier = modifier,
            ) {
                val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
                children.forEachIndexed { index, element ->
                    (element as? JsonObject)?.let {
                        context.renderChild(it, index)
                    }
                }
            }
            "jetpacs.scope" -> {
                // An invisible selection boundary: no layout, modifier,
                // semantics, state, or interaction owner is introduced.
                val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
                children.forEachIndexed { index, element ->
                    (element as? JsonObject)?.let {
                        context.renderScopedChild(it, index)
                    }
                }
            }
            else -> error("${node["t"]} is not owned by $id")
        }
    }

    @Composable
    private fun RenderChoice(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val id = (node["id"] as JsonPrimitive).content
        val epoch = context.epochOf(id)
        var checked by rememberSaveable(
            context.surface,
            id,
            epoch,
        ) {
            mutableStateOf(
                (context.storeValue(id) as? JsonPrimitive)?.strictBooleanOrNull()
                    ?: node.boolean("checked", false),
            )
        }
        JetpacsChoice(
            label = (node["label"] as JsonPrimitive).content,
            checked = checked,
            enabled = node.boolean("enabled", true),
            onCheckedChange = { next ->
                checked = next
                val value = JsonPrimitive(next)
                context.state(id, value)
                context.action(node["on_change"] as? JsonObject, value)
            },
            modifier = modifier,
        )
    }
}

private fun JsonPrimitive.strictBooleanOrNull(): Boolean? =
    takeIf { !isString }?.content?.toBooleanStrictOrNull()

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    (get(name) as? JsonPrimitive)?.strictBooleanOrNull() ?: default
