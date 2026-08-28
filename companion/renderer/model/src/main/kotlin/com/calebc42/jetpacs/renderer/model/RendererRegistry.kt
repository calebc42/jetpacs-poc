// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One independently owned renderer slice.
 *
 * The contribution is implementation metadata, not another UI tree: renderers
 * still consume the accepted EBP JSON document directly. A contribution names
 * only behavior implemented by that slice, which lets a receiver derive its
 * welcome profile from installed code instead of maintaining a parallel list.
 */
data class RendererContribution(
    val id: String,
    val nodeTypes: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val features: Set<String> = emptySet(),
    val extensions: Set<String> = emptySet(),
)

/** A complete receiver target profile assembled from renderer contributions. */
data class RendererProfile(
    val nodeTypes: Set<String>,
    val builtins: Set<String>,
    val features: Set<String>,
    val extensions: Set<String>,
) {
    /** EBP 3 §10.2 wire projection with stable lexical ordering. */
    fun toJson(): JsonObject = buildJsonObject {
        put("node_types", strings(nodeTypes))
        put("builtins", strings(builtins))
        put("features", strings(features))
        put("extensions", strings(extensions))
    }

    private fun strings(values: Set<String>) =
        JsonArray(values.sorted().map(::JsonPrimitive))
}

/**
 * Validates and combines the renderer code actually installed in a target.
 *
 * Extension nodes are dual-gated: a target may implement a subset of an
 * extension, but an extension node can never be advertised without its owner.
 * This is the receiver-side proof behind the EBP 3 profile rather than a
 * handwritten capability claim.
 */
class RendererRegistry(
    contributions: Iterable<RendererContribution>,
    /** Renderer-owned node ownership, injected by the selected implementation. */
    private val extensionOwners: Map<String, Set<String>> = emptyMap(),
) {
    private val contributionList = contributions.toList()
    private val contributions = contributionList.associateBy { contribution ->
        require(contribution.id.isNotBlank()) { "Renderer contribution id is blank" }
        contribution.id
    }.also { indexed ->
        require(indexed.size == contributionList.size) {
            "Renderer contribution ids must be unique"
        }
    }

    fun profile(vararg contributionIds: String): RendererProfile {
        val selected = contributionIds.map { id ->
            requireNotNull(contributions[id]) { "Unknown renderer contribution: $id" }
        }
        val profile = RendererProfile(
            nodeTypes = selected.flatMap { it.nodeTypes }.toSet(),
            builtins = selected.flatMap { it.builtins }.toSet(),
            features = selected.flatMap { it.features }.toSet(),
            extensions = selected.flatMap { it.extensions }.toSet(),
        )
        validateExtensions(profile)
        return profile
    }

    private fun validateExtensions(profile: RendererProfile) {
        val owners = extensionOwners.entries.flatMap { (extension, nodes) ->
            nodes.map { it to extension }
        }.toMap()
        profile.nodeTypes.forEach { node ->
            owners[node]?.let { owner ->
                require(owner in profile.extensions) {
                    "Extension node $node requires $owner"
                }
            }
        }
        profile.extensions.forEach { extension ->
            requireNotNull(extensionOwners[extension]) {
                "Unknown renderer extension: $extension"
            }
        }
    }
}
