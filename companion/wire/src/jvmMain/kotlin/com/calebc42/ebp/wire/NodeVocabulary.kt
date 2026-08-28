// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

/**
 * Admission metadata installed at an endpoint's presentation boundary.
 *
 * [NODE_SCHEMA] and [STATEFUL_NODE_TYPES] are EBP's own vocabulary. Optional
 * renderers may add schemas and rules without adding their names, frameworks,
 * or design-system contracts to the wire module or the EBP contract. The
 * engine still validates one raw EBP document; this is not a second UI model.
 *
 * [extensions] maps each positively advertised extension identifier to the
 * node types its provider owns. Ownership is receiver configuration, never
 * inferred from a node-name prefix.
 */
data class NodeVocabulary(
    val schema: Map<String, NodeRow> = NODE_SCHEMA,
    val statefulTypes: Set<String> = STATEFUL_NODE_TYPES,
    val statefulWhenPresent: Map<String, String> = mapOf(
        "button" to "checked",
        "icon_button" to "checked",
    ),
    val localEditorTypes: Set<String> = setOf("editor"),
    val atLeastOneNonEmpty: Map<String, Set<String>> = emptyMap(),
    val extensions: Map<String, Set<String>> = emptyMap(),
) {
    init {
        require(extensions.keys.all { it.contains('.') }) {
            "Renderer extension identifiers must be namespaced"
        }
        val owners = mutableMapOf<String, String>()
        for ((extension, nodeTypes) in extensions) {
            for (nodeType in nodeTypes) {
                require(nodeType in schema) {
                    "Renderer extension $extension owns unknown node $nodeType"
                }
                require(owners.put(nodeType, extension) == null) {
                    "Renderer node $nodeType has more than one owner"
                }
            }
        }
        require((statefulTypes + statefulWhenPresent.keys + localEditorTypes)
            .all { it in schema }) {
            "Stateful rules must name installed node schemas"
        }
        require(atLeastOneNonEmpty.all { (nodeType, members) ->
            nodeType in schema && members.isNotEmpty() &&
                members.all { member ->
                    val row = schema.getValue(nodeType)
                    member in row.required || member in row.optional
                }
        }) {
            "Non-empty constraints must name installed schema members"
        }
    }

    /** Whether [node] contributes durable input state under its installed rule. */
    fun isStateful(nodeType: String, node: kotlinx.serialization.json.JsonObject): Boolean {
        statefulWhenPresent[nodeType]?.let { return it in node }
        if (nodeType in localEditorTypes) {
            return node.boolOr("publish_state") && "document" !in node
        }
        return nodeType in statefulTypes
    }
}

/** EBP's implementation-neutral vocabulary with no downstream extensions. */
val EBP_NODE_VOCABULARY = NodeVocabulary()
