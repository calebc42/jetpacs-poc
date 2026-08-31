// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.compose.ComposeNodeExtension
import com.calebc42.ebp.renderer.model.RendererContribution
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
            "jetpacs.section_navigator" -> RenderSectionNavigator(node, context, modifier)
            "jetpacs.tabs" -> RenderTabs(node, context, modifier)
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

    @Composable
    private fun RenderTabs(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val options = (node["options"] as? JsonArray)
            ?.mapNotNull { element ->
                val option = element as? JsonObject ?: return@mapNotNull null
                val label = (option["label"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?: return@mapNotNull null
                val value = (option["value"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?: return@mapNotNull null
                JetpacsTabOption(label, value)
            }
            .orEmpty()
        val value = (node["value"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            .orEmpty()
        val scrollable = node.boolean("scrollable", false)
        val variant = (node["variant"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

        JetpacsTabs(
            options = options,
            value = value,
            enabled = node.boolean("enabled", true),
            scrollable = scrollable,
            variant = resolveJetpacsTabVariant(variant, scrollable),
            onValueChange = { next ->
                context.action(node["on_change"] as? JsonObject, JsonPrimitive(next))
            },
            modifier = modifier,
        )
    }

    @Composable
    private fun RenderSectionNavigator(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val options = (node["options"] as? JsonArray)
            ?.mapNotNull { element ->
                val option = element as? JsonObject ?: return@mapNotNull null
                val label = (option["label"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?: return@mapNotNull null
                val value = (option["value"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?: return@mapNotNull null
                val level = (option["level"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }
                    ?.content
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..6 }
                    ?: return@mapNotNull null
                JetpacsSectionOption(label, value, level)
            }
            .orEmpty()
        val value = (node["value"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            .orEmpty()

        JetpacsSectionNavigator(
            options = options,
            value = value,
            enabled = node.boolean("enabled", true),
            onValueChange = { next ->
                context.action(node["on_change"] as? JsonObject, JsonPrimitive(next))
            },
            modifier = modifier,
        )
    }
}

private fun JsonPrimitive.strictBooleanOrNull(): Boolean? =
    takeIf { !isString }?.content?.toBooleanStrictOrNull()

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    (get(name) as? JsonPrimitive)?.strictBooleanOrNull() ?: default
