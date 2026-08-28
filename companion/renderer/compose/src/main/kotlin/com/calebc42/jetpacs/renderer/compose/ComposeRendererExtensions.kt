// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The renderer-neutral host operations available to one Compose extension.
 *
 * The host retains action admission, dialog rebinding, durable delivery, and
 * state reconciliation. An extension presents already accepted JSON and calls
 * these operations rather than acquiring a transport or persistence boundary.
 */
interface ComposeExtensionRenderContext {
    val surface: String
    val path: String
    val inDialog: Boolean

    fun action(
        descriptor: JsonObject?,
        value: JsonElement? = null,
        onOutcome: (RendererActionOutcome) -> Unit = {},
    ): ActionHandoff
    fun state(id: String, value: JsonElement?)
    fun storeValue(id: String): JsonElement?
    fun epochOf(id: String): Long

    /** Render CHILD through the installed dispatcher at its authored index. */
    @Composable
    fun renderChild(child: JsonObject, index: Int, modifier: Modifier = Modifier)
}

/** One independently owned set of extension-node composables. */
interface ComposeNodeExtension {
    /** Stable implementation identity used to detect duplicate installation. */
    val id: String

    /** Exact node types this renderer owns. */
    val nodeTypes: Set<String>

    /** Render an owned, admitted [node] on the interaction-owning [modifier]. */
    @Composable
    fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    )
}

/**
 * Exact extension-node dispatch assembled by the application composition root.
 *
 * Ownership is singular: two installed renderers cannot race to interpret the
 * same downstream node type, and an implementation identity cannot be reused.
 */
@Stable
class ComposeExtensionRegistry(
    extensions: Iterable<ComposeNodeExtension>,
) {
    private val installed = extensions.toList()
    private val byNodeType: Map<String, ComposeNodeExtension>

    init {
        require(installed.map { it.id }.distinct().size == installed.size) {
            "Compose extension renderer ids must be unique"
        }
        require(installed.all { it.id.isNotBlank() && it.nodeTypes.isNotEmpty() }) {
            "Compose extension renderers need an id and at least one node type"
        }
        val owners = mutableMapOf<String, ComposeNodeExtension>()
        installed.forEach { extension ->
            extension.nodeTypes.forEach { nodeType ->
                val previous = owners.putIfAbsent(nodeType, extension)
                require(previous == null) {
                    "Compose node $nodeType is owned by both ${previous?.id} and ${extension.id}"
                }
            }
        }
        byNodeType = owners
    }

    val nodeTypes: Set<String> get() = byNodeType.keys
    val extensionIds: Set<String> get() = installed.mapTo(mutableSetOf()) { it.id }

    fun rendererFor(nodeType: String): ComposeNodeExtension? = byNodeType[nodeType]

    companion object {
        /** A renderer installation with no downstream Compose nodes. */
        val Empty = ComposeExtensionRegistry(emptyList())
    }
}

/** Target-specific dispatch configuration supplied by the installed app. */
@Stable
data class ComposeRendererConfiguration(
    val appNodeTypes: Set<String>,
    val dialogNodeTypes: Set<String>,
    val extensions: ComposeExtensionRegistry = ComposeExtensionRegistry.Empty,
) {
    init {
        require(extensions.nodeTypes.all { it in appNodeTypes || it in dialogNodeTypes }) {
            "Installed Compose extension nodes must be advertised by a render target"
        }
    }
}
