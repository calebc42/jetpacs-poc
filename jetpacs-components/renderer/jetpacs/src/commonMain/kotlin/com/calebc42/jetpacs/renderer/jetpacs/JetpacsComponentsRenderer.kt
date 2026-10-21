// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.calebc42.ebp.renderer.compose.ComposeComponentRegistry
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.compose.ComposeNodeComponent
import com.calebc42.ebp.renderer.compose.ComposeNodeExtension
import com.calebc42.ebp.renderer.model.RendererContribution
import com.calebc42.ebp.renderer.model.vocabulary.NodeMemberSet
import com.calebc42.ebp.renderer.model.vocabulary.NodeProperties
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** App-surface contribution implemented by [JetpacsComponentsRenderer]. */
val JetpacsComponentsContribution = RendererContribution(
    id = "jetpacs.components.app",
    nodeTypes = JETPACS_COMPONENTS_TARGET_NODE_TYPES.getValue("app"),
    extensions = setOf(JETPACS_COMPONENTS_EXTENSION),
)

/** A Jetpacs extension node component: one node type presented in the extension context. */
internal typealias JetpacsNodeComponent = ComposeNodeComponent<ComposeExtensionRenderContext>

/**
 * Base of every Jetpacs extension component. The node type and the generated
 * member set it reads through are fixed per object, so a registry's
 * `members.nodeType == nodeType` invariant holds by construction.
 */
internal abstract class JetpacsComponent(
    final override val nodeType: String,
    final override val members: NodeMemberSet,
) : JetpacsNodeComponent

/**
 * Compose Foundation implementation of the `jetpacs.components` extension.
 *
 * Dispatch is [registry]: one component per manifest node type, each reading
 * its node through the member set generated from the format-2
 * `renderer-extensions/jetpacs-components.json`, which types every member
 * and whose typed schema admission applies before any node reaches here.
 */
object JetpacsComponentsRenderer : ComposeNodeExtension {
    override val id: String = "jetpacs.components.compose"
    override val extensionId: String = JETPACS_COMPONENTS_EXTENSION
    override val nodeTypes: Set<String> = JETPACS_COMPONENTS_NODE_SCHEMA.keys

    /** One component per owned node type; `JetpacsComponentRegistriesTest` pins it to the manifest. */
    val registry: ComposeComponentRegistry<ComposeExtensionRenderContext> = ComposeComponentRegistry(
        listOf(
            JetpacsActionComponent,
            JetpacsChoiceComponent,
            JetpacsPanelComponent,
            JetpacsListItemComponent,
            JetpacsSectionNavigatorComponent,
            JetpacsTabsComponent,
            JetpacsScopeComponent,
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
            ?: error("${node["t"]} is not owned by $id")
        component.render(node, context, modifier)
    }
}

/** `jetpacs.action`: the Foundation action face, dispatching `on_tap`. */
internal object JetpacsActionComponent : JetpacsComponent("jetpacs.action", JetpacsActionMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val p = NodeProperties(node, JetpacsActionMembers)
        val onTap = p[JetpacsActionMembers.OnTap]
        JetpacsAction(
            label = p.getOr(JetpacsActionMembers.Label, ""),
            enabled = p.getOr(JetpacsActionMembers.Enabled, true),
            onClick = { context.action(onTap) },
            modifier = modifier,
        )
    }
}

/** `jetpacs.choice`: a stateful toggle that publishes its value before `on_change`. */
internal object JetpacsChoiceComponent : JetpacsComponent("jetpacs.choice", JetpacsChoiceMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val p = NodeProperties(node, JetpacsChoiceMembers)
        val id = p[JetpacsChoiceMembers.Id] ?: return
        val epoch = context.epochOf(id)
        var checked by rememberSaveable(
            context.surface,
            id,
            epoch,
        ) {
            mutableStateOf(
                (context.storeValue(id) as? JsonPrimitive)?.strictBooleanOrNull()
                    ?: p.getOr(JetpacsChoiceMembers.Checked, false),
            )
        }
        val onChange = p[JetpacsChoiceMembers.OnChange]
        JetpacsChoice(
            label = p.getOr(JetpacsChoiceMembers.Label, ""),
            checked = checked,
            enabled = p.getOr(JetpacsChoiceMembers.Enabled, true),
            onCheckedChange = { next ->
                checked = next
                val value = JsonPrimitive(next)
                context.state(id, value)
                context.action(onChange, value)
            },
            modifier = modifier,
        )
    }
}

/** `jetpacs.panel`: a labelled panel around its children. */
internal object JetpacsPanelComponent : JetpacsComponent("jetpacs.panel", JetpacsPanelMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val p = NodeProperties(node, JetpacsPanelMembers)
        JetpacsPanel(
            label = p.getOr(JetpacsPanelMembers.Label, ""),
            modifier = modifier,
        ) {
            p[JetpacsPanelMembers.Children].orEmpty().forEachIndexed { index, child ->
                context.renderChild(child, index)
            }
        }
    }
}

/** `jetpacs.list_item`: the row primitive; its reading lives with the row in JetpacsListItem.kt. */
internal object JetpacsListItemComponent : JetpacsComponent("jetpacs.list_item", JetpacsListItemMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) = RenderListItem(node, context, modifier)
}

/** `jetpacs.section_navigator`: a controlled outline selector; the selection is Emacs' decision. */
internal object JetpacsSectionNavigatorComponent :
    JetpacsComponent("jetpacs.section_navigator", JetpacsSectionNavigatorMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val p = NodeProperties(node, JetpacsSectionNavigatorMembers)
        val options = p[JetpacsSectionNavigatorMembers.Options]
            ?.mapNotNull { element ->
                val option = element as? JsonObject ?: return@mapNotNull null
                val label = option.optionText("label") ?: return@mapNotNull null
                val value = option.optionText("value") ?: return@mapNotNull null
                val level = (option["level"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }
                    ?.content
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..6 }
                    ?: return@mapNotNull null
                JetpacsSectionOption(label, value, level)
            }
            .orEmpty()
        val onChange = p[JetpacsSectionNavigatorMembers.OnChange]

        JetpacsSectionNavigator(
            options = options,
            value = p.getOr(JetpacsSectionNavigatorMembers.Value, ""),
            enabled = p.getOr(JetpacsSectionNavigatorMembers.Enabled, true),
            onValueChange = { next ->
                context.action(onChange, JsonPrimitive(next))
            },
            modifier = modifier,
        )
    }
}

/** `jetpacs.tabs`: a controlled tab strip; `variant` resolves against `scrollable`. */
internal object JetpacsTabsComponent : JetpacsComponent("jetpacs.tabs", JetpacsTabsMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val p = NodeProperties(node, JetpacsTabsMembers)
        val options = p[JetpacsTabsMembers.Options]
            ?.mapNotNull { element ->
                val option = element as? JsonObject ?: return@mapNotNull null
                val label = option.optionText("label") ?: return@mapNotNull null
                val value = option.optionText("value") ?: return@mapNotNull null
                JetpacsTabOption(label, value)
            }
            .orEmpty()
        val scrollable = p.getOr(JetpacsTabsMembers.Scrollable, false)
        val onChange = p[JetpacsTabsMembers.OnChange]

        JetpacsTabs(
            options = options,
            value = p.getOr(JetpacsTabsMembers.Value, ""),
            enabled = p.getOr(JetpacsTabsMembers.Enabled, true),
            scrollable = scrollable,
            // Variant names are forward-compatible renderer policy, so the
            // wire admits any bounded string and unknown names fall back here.
            variant = resolveJetpacsTabVariant(p[JetpacsTabsMembers.Variant], scrollable),
            onValueChange = { next ->
                context.action(onChange, JsonPrimitive(next))
            },
            modifier = modifier,
        )
    }
}

/**
 * `jetpacs.scope`: an invisible selection boundary. No layout, modifier,
 * semantics, state, or interaction owner is introduced. Inside an active
 * design scope the boundary is redundant -- the design scope selects the
 * Foundation field and editor this scope exists for, and every other
 * override besides -- so the children keep the enclosing scope rather than
 * narrowing to this one and losing those.
 */
internal object JetpacsScopeComponent : JetpacsComponent("jetpacs.scope", JetpacsScopeMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val designActive = LocalDesignScope.current != null
        val children = NodeProperties(node, JetpacsScopeMembers)[JetpacsScopeMembers.Children]
        children.orEmpty().forEachIndexed { index, child ->
            if (designActive) {
                context.renderChild(child, index)
            } else {
                context.renderScopedChild(child, index)
            }
        }
    }
}

private fun JsonPrimitive.strictBooleanOrNull(): Boolean? =
    takeIf { !isString }?.content?.toBooleanStrictOrNull()

/** One option's string member: a string primitive, or nothing. */
private fun JsonObject.optionText(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
