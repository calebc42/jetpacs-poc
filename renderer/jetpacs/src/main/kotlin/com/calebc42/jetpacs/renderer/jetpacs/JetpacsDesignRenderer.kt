// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.style.StyleState
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
import androidx.compose.ui.graphics.Color
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.compose.ComposeThemeRoles
import com.calebc42.ebp.renderer.compose.LocalComposeThemeRoles
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

internal val LocalDesignScope = staticCompositionLocalOf<CompiledDesignScope?> { null }

/**
 * The nearest enclosing `jetpacs.styled` or `jetpacs.pressable` text program.
 *
 * Foundation Styles reach layout and drawing through modifiers; text
 * attributes must instead be read by the canonical `text` override, which
 * resolves [computed] against the live [state] so a selected or disabled face
 * recolors its label without any Emacs round trip.
 */
internal class DesignTextContext(
    val computed: ComputedDesignStyle,
    val state: StyleState?,
)

internal val LocalDesignTextContext = staticCompositionLocalOf<DesignTextContext?> { null }

/** Resolve one optional semantic-component style from the effective scope. */
@Composable
internal fun designComponentStyle(slot: DesignComponentStyleSlot): androidx.compose.foundation.style.Style {
    val computed = LocalDesignScope.current?.componentStyle(slot)
    return remember(computed?.layers) {
        computed?.toFoundationStyle() ?: androidx.compose.foundation.style.Style
    }
}

/** Apply label layout/draw properties without double-applying text attributes. */
@Composable
internal fun designComponentNonTextStyle(
    slot: DesignComponentStyleSlot,
): androidx.compose.foundation.style.Style {
    val computed = LocalDesignScope.current?.componentStyle(slot)
    return remember(computed?.layers) {
        computed?.toFoundationStyle(includeTextProperties = false)
            ?: androidx.compose.foundation.style.Style
    }
}

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
        val ambient = LocalJetpacsTheme.current
        // Re-declared roles re-derive both palettes for this subtree: the
        // private Jetpacs theme here, and whichever toolkit theme the
        // dispatcher installs from the shared roles at its scope boundary.
        val scopedTheme = remember(scope, ambient) {
            scope.themeRoles.takeIf { it.isNotEmpty() }?.let { declared ->
                jetpacsThemeFor(ambient.roles.overridden(declared), ambient.dark)
            }
        }
        if (scopedTheme == null) {
            CompositionLocalProvider(LocalDesignScope provides scope) {
                RenderChildren(node, context, modifier, scoped = true)
            }
        } else {
            CompositionLocalProvider(
                LocalDesignScope provides scope,
                LocalJetpacsTheme provides scopedTheme,
                LocalComposeThemeRoles provides scopedTheme.roles.toComposeThemeRoles(),
            ) {
                RenderChildren(node, context, modifier, scoped = true)
            }
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
        val textContext = remember(computed) { DesignTextContext(computed, null) }
        CompositionLocalProvider(LocalDesignTextContext provides textContext) {
            RenderChildren(node, context, modifier, childStyle, scoped = true)
        }
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
            val textContext = remember(computed, styleState) {
                DesignTextContext(computed, styleState)
            }
            val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
            CompositionLocalProvider(LocalDesignTextContext provides textContext) {
                children.forEachIndexed { index, child ->
                    (child as? JsonObject)?.let { context.renderScopedChild(it, index) }
                }
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

    /**
     * Render authored children. A compiled scope renders them [scoped], so the
     * canonical `text`, `text_input`, and `editor` overrides registered under
     * `jetpacs.design` select Foundation presentation for the whole subtree;
     * the malformed-configuration fallback renders unscoped so nothing
     * authored against a broken scope gains styling or interaction.
     */
    @Composable
    private fun RenderChildren(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        outerModifier: Modifier,
        childModifier: Modifier = Modifier,
        scoped: Boolean = false,
    ) {
        val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
        children.forEachIndexed { index, child ->
            (child as? JsonObject)?.let {
                val childModifierAt =
                    if (index == 0) outerModifier.then(childModifier) else childModifier
                if (scoped) {
                    context.renderScopedChild(it, index, childModifierAt)
                } else {
                    context.renderChild(it, index, childModifierAt)
                }
            }
        }
    }
}

/** Apply a scope's re-declared roles over the ambient ones; references resolve against the ambient set. */
internal fun JetpacsThemeRoles.overridden(declared: Map<DesignThemeRole, DesignValue>): JetpacsThemeRoles {
    fun pick(role: DesignThemeRole): Color = when (val value = declared[role]) {
        is DesignValue.ColorValue -> Color(value.argb)
        is DesignValue.ThemeRoleValue -> this[value.value]
        else -> this[role]
    }
    return JetpacsThemeRoles(
        primary = pick(DesignThemeRole.Primary),
        onPrimary = pick(DesignThemeRole.OnPrimary),
        secondary = pick(DesignThemeRole.Secondary),
        onSecondary = pick(DesignThemeRole.OnSecondary),
        error = pick(DesignThemeRole.Error),
        onError = pick(DesignThemeRole.OnError),
        background = pick(DesignThemeRole.Background),
        onBackground = pick(DesignThemeRole.OnBackground),
        surface = pick(DesignThemeRole.Surface),
        onSurface = pick(DesignThemeRole.OnSurface),
        outline = pick(DesignThemeRole.Outline),
        success = pick(DesignThemeRole.Success),
        warning = pick(DesignThemeRole.Warning),
    )
}

internal fun JetpacsThemeRoles.toComposeThemeRoles(): ComposeThemeRoles = ComposeThemeRoles(
    primary = primary,
    onPrimary = onPrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    error = error,
    onError = onError,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    outline = outline,
    success = success,
    warning = warning,
)

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
