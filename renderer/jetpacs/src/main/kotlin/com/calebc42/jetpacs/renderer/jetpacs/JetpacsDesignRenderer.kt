// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.compose.ComposeNodeExtension
import com.calebc42.ebp.renderer.model.RendererContribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** App-surface contribution implemented by [JetpacsDesignRenderer]. */
val JetpacsDesignContribution = RendererContribution(
    id = "jetpacs.design.app",
    nodeTypes = JETPACS_DESIGN_TARGET_NODE_TYPES.getValue("app"),
    extensions = setOf(JETPACS_DESIGN_EXTENSION),
)

private val LocalDesignScope = staticCompositionLocalOf<CompiledDesignScope?> { null }

/** Foundation implementation of the bounded `jetpacs.design` extension. */
object JetpacsDesignRenderer : ComposeNodeExtension {
    override val id: String = "jetpacs.design.compose"
    override val extensionId: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = JETPACS_DESIGN_NODE_SCHEMA.keys

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        when ((node["t"] as? JsonPrimitive)?.content) {
            "jetpacs.design_scope" -> RenderScope(node, context, modifier)
            "jetpacs.styled" -> RenderStyled(node, context, modifier)
            "jetpacs.pressable" -> RenderPressable(node, context, modifier)
            else -> FallbackChildren(node, context, modifier)
        }
    }

    @Composable
    private fun RenderScope(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val parent = LocalDesignScope.current
        val scope = remember(node, parent, context.path) {
            modelOrNull { DesignModel.compileScope(node, parent, context.path) }
        }
        if (scope == null) {
            FallbackChildren(node, context, modifier)
            return
        }
        CompositionLocalProvider(LocalDesignScope provides scope) {
            RenderChildren(node, context, modifier)
        }
    }

    @Composable
    private fun RenderStyled(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val scope = LocalDesignScope.current
        val computed = remember(node, scope, context.path) {
            modelOrNull {
                DesignModel.computedStyle(
                    node = node,
                    scope = scope ?: invalid(context.path, "styled node requires a design scope"),
                    path = context.path,
                    baseOnly = true,
                )
            }
        }
        if (computed == null) {
            FallbackChildren(node, context, modifier)
            return
        }
        val style = remember(computed.layers) { computed.toFoundationStyle() }
        val interactionSource = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(interactionSource)
        val childStyle = Modifier.styleable(styleState, style)
        RenderChildren(node, context, modifier, childStyle)
    }

    @Composable
    private fun RenderPressable(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val scope = LocalDesignScope.current
        val computed = remember(node, scope, context.path) {
            modelOrNull {
                JetpacsDesignSemanticValidator.validatePressableForRender(
                    node = node,
                    scope = scope ?: invalid(context.path, "pressable node requires a design scope"),
                    path = context.path,
                )
            }
        }
        if (computed == null) {
            FallbackChildren(node, context, modifier)
            return
        }

        val enabled = node.boolean("enabled", true)
        val selected = node.boolean("selected", false)
        val toggled = node.boolean("toggled", false)
        val hasSelected = "selected" in node
        val hasToggled = "toggled" in node
        val interactionSource = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(interactionSource) {
            it.isEnabled = enabled
            it.isSelected = selected
            it.isChecked = toggled
        }
        val style = remember(computed.layers) { computed.toFoundationStyle() }
        Row(
            modifier = modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .hoverable(interactionSource, enabled)
                .focusable(enabled, interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                ) {
                    context.action(node["on_tap"] as? JsonObject)
                }
                .semantics(mergeDescendants = true) {
                    if (hasSelected) this.selected = selected
                    if (hasToggled) {
                        toggleableState = if (toggled) {
                            ToggleableState.On
                        } else {
                            ToggleableState.Off
                        }
                    }
                }
                .styleable(styleState, style),
        ) {
            val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
            children.forEachIndexed { index, child ->
                (child as? JsonObject)?.let { context.renderChild(it, index) }
            }
        }
    }

    @Composable
    private fun FallbackChildren(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        RenderChildren(node, context, modifier)
    }

    @Composable
    private fun RenderChildren(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        outerModifier: Modifier,
        childModifier: Modifier = Modifier,
    ) {
        val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
        children.forEachIndexed { index, child ->
            (child as? JsonObject)?.let {
                context.renderChild(
                    it,
                    index,
                    if (index == 0) outerModifier.then(childModifier) else childModifier,
                )
            }
        }
    }
}

private inline fun <T> modelOrNull(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

private fun JsonObject.boolean(name: String, default: Boolean): Boolean {
    val primitive = this[name] as? JsonPrimitive ?: return default
    if (primitive.isString) return default
    return primitive.content.toBooleanStrictOrNull() ?: default
}
