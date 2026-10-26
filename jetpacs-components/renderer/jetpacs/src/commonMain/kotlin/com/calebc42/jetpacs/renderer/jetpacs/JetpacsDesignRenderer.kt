// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.calebc42.ebp.renderer.compose.ComposeComponentRegistry
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.compose.ComposeThemeRoles
import com.calebc42.ebp.renderer.compose.LocalComposeChromeStyles
import com.calebc42.ebp.renderer.compose.LocalComposeThemeRoles
import com.calebc42.ebp.renderer.compose.ComposeNodeExtension
import com.calebc42.ebp.renderer.compose.RenderChildrenFallback
import com.calebc42.ebp.renderer.model.RendererContribution
import com.calebc42.ebp.renderer.model.vocabulary.NodeProperties
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** App-surface contribution implemented by [JetpacsDesignRenderer]. */
val JetpacsDesignContribution = RendererContribution(
    id = "jetpacs.design.app",
    nodeTypes = JETPACS_DESIGN_TARGET_NODE_TYPES.getValue("app"),
    extensions = setOf(JETPACS_DESIGN_EXTENSION),
)

/** The design nodes a dialog may carry, so an app's dialogs wear its styles. */
val JetpacsDesignDialogContribution = RendererContribution(
    id = "jetpacs.design.dialog",
    nodeTypes = JETPACS_DESIGN_TARGET_NODE_TYPES.getValue("dialog"),
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

/**
 * Foundation implementation of the bounded `jetpacs.design` extension.
 *
 * Dispatch is [registry]: one component per manifest node type, reading its
 * children through the member set generated from
 * `renderer-extensions/jetpacs-design.json`. The scope and style programs
 * themselves are compiled from the node by [DesignModel], exactly as
 * admission compiles them.
 */
object JetpacsDesignRenderer : ComposeNodeExtension {
    override val id: String = "jetpacs.design.compose"
    override val extensionId: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = JETPACS_DESIGN_NODE_SCHEMA.keys

    /** One component per owned node type; `JetpacsComponentRegistriesTest` pins it to the manifest. */
    val registry: ComposeComponentRegistry<ComposeExtensionRenderContext> = ComposeComponentRegistry(
        listOf(
            JetpacsDesignScopeComponent, JetpacsStyledComponent, JetpacsPressableComponent,
            JetpacsGlyphComponent, JetpacsInlineTextComponent, JetpacsInlineComponent,
            JetpacsWheelComponent, JetpacsStateSwitchComponent,
        ),
    )

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val type = (node["t"] as? JsonPrimitive)?.content
        val component = type?.let(registry::componentFor)
        if (component != null) {
            component.render(node, context, modifier)
        } else {
            // Advertised but unowned: the SPEC §16.2 degradation.
            RenderChildrenFallback(node, context, modifier)
        }
    }
}

/**
 * `jetpacs.design_scope`: compiles the scope against the enclosing one and
 * provides it -- with any re-declared theme roles and the chrome they imply --
 * to the scoped subtree. A scope that does not compile renders its children
 * unscoped.
 */
internal object JetpacsDesignScopeComponent :
    JetpacsComponent("jetpacs.design_scope", JetpacsDesignScopeMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val children = NodeProperties(node, JetpacsDesignScopeMembers)[JetpacsDesignScopeMembers.Children]
            .orEmpty()
        val parent = LocalDesignScope.current
        val scope = remember(node, parent, context.path) {
            modelOrNull { DesignModel.compileScope(node, parent, context.path) }
        }
        if (scope == null) {
            RenderDesignChildren(children, context, modifier)
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
        // The chrome the toolkit draws around scoped content follows the
        // scope's chrome slots; an unbound scope leaves the enclosing value.
        val theme = scopedTheme ?: ambient
        val parentChrome = LocalComposeChromeStyles.current
        val chrome = remember(scope, theme, parentChrome) {
            scope.composeChromeStyles(theme.roles) ?: parentChrome
        }
        if (scopedTheme == null) {
            CompositionLocalProvider(
                LocalDesignScope provides scope,
                LocalComposeChromeStyles provides chrome,
            ) {
                RenderDesignChildren(children, context, modifier, scoped = true)
            }
        } else {
            CompositionLocalProvider(
                LocalDesignScope provides scope,
                LocalJetpacsTheme provides scopedTheme,
                LocalComposeThemeRoles provides scopedTheme.roles.toComposeThemeRoles(),
                LocalComposeChromeStyles provides chrome,
            ) {
                RenderDesignChildren(children, context, modifier, scoped = true)
            }
        }
    }
}

/** `jetpacs.styled`: a style program applied to its children through the nearest scope. */
internal object JetpacsStyledComponent : JetpacsComponent("jetpacs.styled", JetpacsStyledMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val children = NodeProperties(node, JetpacsStyledMembers)[JetpacsStyledMembers.Children].orEmpty()
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
            RenderDesignChildren(children, context, modifier)
            return
        }
        val style = remember(computed.layers) { computed.toFoundationStyle() }
        val interactionSource = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(interactionSource)
        val childStyle = computed.intrinsicLayout().then(computed.elevationShadow()).styleable(styleState, style)
        val textContext = remember(computed) { DesignTextContext(computed, null) }
        CompositionLocalProvider(LocalDesignTextContext provides textContext) {
            RenderDesignChildren(children, context, modifier, childStyle, scoped = true)
        }
    }
}

/**
 * `jetpacs.pressable`: an interactive face whose style follows its live
 * state. What a press means is Emacs' decision, carried by `on_tap`.
 */
internal object JetpacsPressableComponent : JetpacsComponent("jetpacs.pressable", JetpacsPressableMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val p = NodeProperties(node, JetpacsPressableMembers)
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
            RenderDesignChildren(p[JetpacsPressableMembers.Children].orEmpty(), context, modifier)
            return
        }

        val enabled = p.getOr(JetpacsPressableMembers.Enabled, true)
        val selected = p.getOr(JetpacsPressableMembers.Selected, false)
        val toggled = p.getOr(JetpacsPressableMembers.Toggled, false)
        val hasSelected = p.has(JetpacsPressableMembers.Selected)
        val hasToggled = p.has(JetpacsPressableMembers.Toggled)
        val onTap = p[JetpacsPressableMembers.OnTap]
        val interactionSource = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(interactionSource) {
            it.isEnabled = enabled
            it.isSelected = selected
            it.isChecked = toggled
        }
        val style = remember(computed.layers) { computed.toFoundationStyle() }
        val (authoredWidth, authoredHeight) = remember(computed.layers) { computed.authoredSize() }
        val centered = remember(computed.layers) {
            (computed.baseOnly().resolve().properties[DesignProperty.CenterVertically]
                as? DesignValue.BooleanValue)?.value == true
        }
        // SPEC 17.3 (#207): a pressable row may carry swipe sides — revealed
        // beneath it, or shown as a popup beside the gesture — like a list item.
        // The incoming modifier carries the parent's layout data (a row's
        // weight, an authored width): it belongs on the outermost node, so a
        // swiping pressable hands it to the swipe row and fills that instead.
        val swipeStart = p[JetpacsPressableMembers.SwipeStart]
        val swipeEnd = p[JetpacsPressableMembers.SwipeEnd]
        val swipes = swipeStart != null || swipeEnd != null
        val face: @Composable (Modifier) -> Unit = { faceModifier ->
        Row(
            verticalAlignment = if (centered) Alignment.CenterVertically else Alignment.Top,
            modifier = faceModifier
                .then(computed.intrinsicLayout())
                .sizeIn(
                    minWidth = if (authoredWidth) 0.dp else 48.dp,
                    minHeight = if (authoredHeight) 0.dp else 48.dp,
                )
                .hoverable(interactionSource, enabled)
                .jetpacsFocusable(enabled, interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                ) {
                    context.action(onTap)
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
                .then(computed.elevationShadow())
                .authoredRipple(computed, interactionSource)
                .styleable(styleState, style),
        ) {
            val textContext = remember(computed, styleState) {
                DesignTextContext(computed, styleState)
            }
            val children = p[JetpacsPressableMembers.Children].orEmpty()
            CompositionLocalProvider(LocalDesignTextContext provides textContext) {
                children.forEachIndexed { index, child ->
                    context.renderScopedChild(child, index)
                }
            }
        }
        }
        if (!swipes) {
            face(modifier)
        } else {
            JetpacsSwipeRow(
                identity = context.path,
                swipeStart = swipeStart,
                swipeEnd = swipeEnd,
                context = context,
                modifier = modifier,
            ) { _, _ -> face(Modifier.fillMaxWidth()) }
        }
    }
}

/**
 * Render a design node's authored [children]. A compiled scope renders them
 * [scoped], so the canonical `text`, `text_input`, and `editor` overrides
 * registered under `jetpacs.design` select Foundation presentation for the
 * whole subtree; the malformed-configuration fallback renders unscoped so
 * nothing authored against a broken scope gains styling or interaction.
 */
@Composable
internal fun RenderDesignChildren(
    children: List<JsonObject>,
    context: ComposeExtensionRenderContext,
    outerModifier: Modifier,
    childModifier: Modifier = Modifier,
    scoped: Boolean = false,
) {
    children.forEachIndexed { index, child ->
        val childModifierAt =
            if (index == 0) outerModifier.then(childModifier) else childModifier
        if (scoped) {
            context.renderScopedChild(child, index, childModifierAt)
        } else {
            context.renderChild(child, index, childModifierAt)
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

