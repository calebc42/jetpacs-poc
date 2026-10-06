// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.glance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import com.calebc42.ebp.renderer.model.NodeComponent
import kotlinx.serialization.json.JsonObject

/**
 * What a widget node's translation needs from its host: the resolver that
 * turns an admitted descriptor into a click action, and the identity path
 * of the node being presented, which the resolver keys actions by.
 */
@Stable
class GlanceRenderContext(
    val actions: GlanceActionResolver,
    /** The node's identity path under the widget body (`body/0/2`). */
    val path: String,
) {
    /** The context of the child at [index] under this node. */
    fun child(index: Int): GlanceRenderContext = GlanceRenderContext(actions, "$path/$index")

    /** The context of the child in the named [slot] (`trailing`). */
    fun child(slot: String): GlanceRenderContext = GlanceRenderContext(actions, "$path/$slot")

    /** The host's click action for [descriptor] at this path, or null when there is none to resolve. */
    fun resolve(descriptor: JsonObject?): Action? = descriptor?.let { actions.resolve(path, it) }
}

/**
 * One widget node type's Glance translation: the toolkit-neutral
 * [NodeComponent] declaration plus a Glance composable on a [GlanceModifier].
 * Glance and Compose UI share no modifier type, so this is a sibling of the
 * Compose UI component interface, not a subtype of it.
 */
interface GlanceNodeComponent : NodeComponent {
    /** Present one admitted [node] on [modifier], which already carries the node's semantics. */
    @Composable
    fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier)
}
