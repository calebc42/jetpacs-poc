// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import com.calebc42.ebp.wire.CORE_NODE_SET
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Renderer-host operations shared by canonical and extension node renderers.
 *
 * The host retains action admission, dialog rebinding, durable delivery, state
 * reconciliation, and recursive dispatch. Renderers present accepted JSON and
 * call these operations rather than acquiring transport or persistence seams.
 */
interface ComposeNodeRenderContext {
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

    /** Render [child] through the installed dispatcher at its authored index. */
    @Composable
    fun renderChild(child: JsonObject, index: Int, modifier: Modifier = Modifier)
}

/** Host operations available only while rendering an admitted extension node. */
interface ComposeExtensionRenderContext : ComposeNodeRenderContext {
    /** The positively advertised wire extension that owns the current node. */
    val extensionId: String

    /**
     * Render [child] under this extension's design scope.
     *
     * Scope selection is deliberately tied to [extensionId]: downstream code
     * cannot activate another installed extension's core overrides.
     */
    @Composable
    fun renderScopedChild(
        child: JsonObject,
        index: Int,
        modifier: Modifier = Modifier,
    )
}

/** One independently owned set of extension-node composables. */
interface ComposeNodeExtension {
    /** Stable implementation identity used to detect duplicate installation. */
    val id: String

    /** Positively advertised renderer extension that owns [nodeTypes]. */
    val extensionId: String

    /** Exact downstream node types this renderer owns. */
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
 * One design-scoped replacement for canonical EBP Core Node Set presentation.
 *
 * This changes only Compose selection. The accepted node, its validation,
 * state, semantics, actions, and wire advertisement remain canonical EBP.
 */
interface ComposeCoreNodeOverride {
    /** Stable implementation identity used to detect duplicate installation. */
    val id: String

    /** Positively advertised renderer-extension identifier selecting this design. */
    val designScope: String

    /** Exact generated Core Node Set types this implementation replaces. */
    val nodeTypes: Set<String>

    /** Render one admitted canonical [node] through the ordinary host context. */
    @Composable
    fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
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
        require(installed.all {
            it.id.isNotBlank() && it.extensionId.isNotBlank() &&
                it.extensionId.contains('.') && it.nodeTypes.isNotEmpty()
        }) {
            "Compose extension renderers need an id, namespaced extension id, and node types"
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

    /** Exact downstream node types with installed Compose owners. */
    val nodeTypes: Set<String> get() = byNodeType.keys

    /** Renderer-extension identifiers represented by the installed owners. */
    val extensionIds: Set<String> get() = installed.mapTo(mutableSetOf()) { it.extensionId }

    /** Return the singular installed owner of [nodeType], when present. */
    fun rendererFor(nodeType: String): ComposeNodeExtension? = byNodeType[nodeType]

    /** Return the admitted wire owner for [nodeType], when one is installed. */
    fun extensionIdFor(nodeType: String): String? = byNodeType[nodeType]?.extensionId

    companion object {
        /** A renderer installation with no downstream Compose nodes. */
        val Empty = ComposeExtensionRegistry(emptyList())
    }
}

/**
 * Strict design-scope lookup for the generated EBP Core Node Set.
 *
 * Registration cannot advertise nodes or reinterpret optional/downstream
 * vocabulary. Duplicate `(designScope, nodeType)` ownership fails here at the
 * composition root rather than racing during composition.
 */
@Stable
class ComposeCoreOverrideRegistry(
    overrides: Iterable<ComposeCoreNodeOverride>,
) {
    private data class Key(val scope: String, val nodeType: String)

    private val installed = overrides.toList()
    private val byKey: Map<Key, ComposeCoreNodeOverride>

    init {
        require(installed.map { it.id }.distinct().size == installed.size) {
            "Compose core override ids must be unique"
        }
        require(installed.all {
            it.id.isNotBlank() && it.designScope.isNotBlank() &&
                it.designScope.contains('.') && it.nodeTypes.isNotEmpty()
        }) {
            "Compose core overrides need an id, namespaced design scope, and node types"
        }
        installed.forEach { renderer ->
            require(renderer.nodeTypes.all { it in CORE_NODE_SET }) {
                "Compose core override ${renderer.id} may own only the EBP Core Node Set"
            }
        }
        val owners = mutableMapOf<Key, ComposeCoreNodeOverride>()
        installed.forEach { renderer ->
            renderer.nodeTypes.forEach { nodeType ->
                val key = Key(renderer.designScope, nodeType)
                val previous = owners.putIfAbsent(key, renderer)
                require(previous == null) {
                    "Compose core node $nodeType in ${renderer.designScope} is overridden by " +
                        "both ${previous?.id} and ${renderer.id}"
                }
            }
        }
        byKey = owners
    }

    /** Positively selectable design-scope identifiers represented here. */
    val designScopes: Set<String> get() = installed.mapTo(mutableSetOf()) { it.designScope }

    /** Core nodes registered beneath [designScope]. */
    fun nodeTypesFor(designScope: String): Set<String> = byKey.keys
        .asSequence()
        .filter { it.scope == designScope }
        .mapTo(mutableSetOf()) { it.nodeType }

    /** Return the exact override selected by [designScope] and [nodeType]. */
    fun rendererFor(
        designScope: String,
        nodeType: String,
    ): ComposeCoreNodeOverride? = byKey[Key(designScope, nodeType)]

    companion object {
        /** A composition with canonical rendering only. */
        val Empty = ComposeCoreOverrideRegistry(emptyList())
    }
}

/** Target-specific dispatch configuration supplied by the installed app. */
@Stable
data class ComposeRendererConfiguration(
    val appNodeTypes: Set<String>,
    val dialogNodeTypes: Set<String>,
    val appExtensions: Set<String> = emptySet(),
    val dialogExtensions: Set<String> = emptySet(),
    val extensions: ComposeExtensionRegistry = ComposeExtensionRegistry.Empty,
    val coreOverrides: ComposeCoreOverrideRegistry = ComposeCoreOverrideRegistry.Empty,
) {
    init {
        require(extensions.nodeTypes.all { it in appNodeTypes || it in dialogNodeTypes }) {
            "Installed Compose extension nodes must be advertised by a render target"
        }
        extensions.nodeTypes.forEach { nodeType ->
            val extensionId = requireNotNull(extensions.extensionIdFor(nodeType))
            if (nodeType in appNodeTypes) {
                require(extensionId in appExtensions) {
                    "App Compose extension node $nodeType requires admitted $extensionId"
                }
            }
            if (nodeType in dialogNodeTypes) {
                require(extensionId in dialogExtensions) {
                    "Dialog Compose extension node $nodeType requires admitted $extensionId"
                }
            }
        }
        coreOverrides.designScopes.forEach { scope ->
            require(scope in appExtensions || scope in dialogExtensions) {
                "Compose core override scope $scope is not admitted by a render target"
            }
            val nodeTypes = coreOverrides.nodeTypesFor(scope)
            if (scope in appExtensions) {
                require(appNodeTypes.containsAll(nodeTypes)) {
                    "App profile does not advertise every core override in $scope"
                }
            }
            if (scope in dialogExtensions) {
                require(dialogNodeTypes.containsAll(nodeTypes)) {
                    "Dialog profile does not advertise every core override in $scope"
                }
            }
        }
    }

    /** Whether [designScope] is positively admitted for the current target. */
    fun admitsDesignScope(designScope: String, inDialog: Boolean): Boolean =
        designScope in if (inDialog) dialogExtensions else appExtensions
}
