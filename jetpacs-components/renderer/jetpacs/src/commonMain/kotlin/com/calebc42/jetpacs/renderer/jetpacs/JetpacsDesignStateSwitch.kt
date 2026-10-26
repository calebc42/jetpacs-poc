// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.model.vocabulary.NodeProperties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `jetpacs.state_switch`: the one child whose entry in `values` equals the
 * `source` node's current value, else `otherwise`, following each change as
 * the user makes it (a period dialog's hint follows its type wheel, a clear
 * button its field's text). It reads a value Emacs
 * authored the choices for and publishes nothing, so a dialog needs no round
 * trip, and offline it behaves the same.
 */
internal object JetpacsStateSwitchComponent :
    JetpacsComponent("jetpacs.state_switch", JetpacsStateSwitchMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val p = NodeProperties(node, JetpacsStateSwitchMembers)
        val source = p[JetpacsStateSwitchMembers.Source] ?: return
        val values = (node["values"] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.content }.orEmpty()
        val children = p[JetpacsStateSwitchMembers.Children].orEmpty()
        // A source with no value yet reads as empty, which "" matches.
        val current = (context.liveValue(source) as? JsonPrimitive)?.content.orEmpty()
        val index = values.indexOf(current)
        val child = children.getOrNull(index)
        if (child != null) {
            context.renderChild(child, index, modifier)
        } else {
            // `otherwise` renders after the children, at its own index.
            p[JetpacsStateSwitchMembers.Otherwise]?.let { context.renderChild(it, children.size, modifier) }
        }
    }
}
