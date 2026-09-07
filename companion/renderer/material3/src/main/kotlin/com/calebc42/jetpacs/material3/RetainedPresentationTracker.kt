// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.renderer.model.*

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Stable lookup path used by the renderer to find an accepted incarnation.
 * The variant_host id is document-unique; continuity is checked separately
 * against the complete canonical host path so key/ancestor transitions cannot
 * alias merely because this lookup route stays stable. */
internal fun retainedVariantLifecyclePath(
    host: JsonObject,
    variant: RetainedVariant,
): String {
    val hostId = host.stringOr("id")
    return identityPath(
        "/vh:${encodedIdentityAtom(hostId)}/v:${encodedIdentityAtom(variant.value)}",
        variant.content,
        0,
    )
}

/** Saveable-owner lookup paths mapped to their current complete presentation
 * paths in the stored SurfaceSpec.
 *
 * Opaque application data is deliberately not interpreted as node content.
 * In particular an action arg shaped like `{t:"variant_host"}` is data, not
 * a retained host. */
internal fun retainedSaveableLifecyclePaths(
    spec: JsonObject?,
): Map<String, String> {
    if (spec == null) return emptyMap()
    val paths = LinkedHashMap<String, String>()

    fun nestedPath(parentPath: String, node: JsonObject, position: String): String {
        val key = node.stringOrNull("key").orEmpty()
        val id = node.stringOrNull("id").orEmpty()
        val type = node.stringOrNull("t").orEmpty()
        val segment = when {
            key.isNotEmpty() -> typedIdentitySegment("k", key, type)
            id.isNotEmpty() -> typedIdentitySegment("id", id, type)
            else -> typedIdentitySegment("p", position, type)
        }
        return "$parentPath/$segment"
    }

    lateinit var walkNode: (JsonObject, String) -> Unit
    lateinit var walkValue: (JsonElement, String, String) -> Unit
    walkValue = { value, parentPath, position ->
        when (value) {
            is JsonArray -> value.forEachIndexed { index, child ->
                walkValue(child, parentPath, "$position[$index]")
            }
            is JsonObject -> {
                if (value.stringOrNull("t") != null) {
                    walkNode(value, nestedPath(parentPath, value, position))
                } else {
                    value.forEach { (member, child) ->
                        if (member !in RETAINED_IDENTITY_OPAQUE_MEMBERS)
                            walkValue(child, parentPath, "$position.$member")
                    }
                }
            }
            else -> Unit
        }
    }
    walkNode = { node, nodePath ->
        if (node.stringOrNull("t") == "variant_host") {
            retainedVariants(node).forEach { variant ->
                val lookup = retainedIdentityInventory(
                    retainedVariantLifecyclePath(node, variant),
                    variant.content,
                ).providerPaths
                val continuity = retainedIdentityInventory(
                    identityPath(
                        "$nodePath/v:${encodedIdentityAtom(variant.value)}",
                        variant.content,
                        0,
                    ),
                    variant.content,
                ).providerPaths
                lookup.zip(continuity).forEach { (lookupPath, continuityPath) ->
                    paths[lookupPath] = continuityPath
                }
            }
        } else {
            node.forEach { (member, child) ->
                if (member !in RETAINED_IDENTITY_OPAQUE_MEMBERS)
                    walkValue(child, nodePath, member)
            }
        }
    }

    if (spec.stringOrNull("t") != null) {
        walkNode(spec, identityPath("", spec, 0))
    } else {
        spec.forEach { (member, child) ->
            if (member !in RETAINED_IDENTITY_OPAQUE_MEMBERS)
                walkValue(child, "", member)
        }
    }
    return paths
}

data class RetainedPresentationIncarnations(
    val surfaceRoots: Map<String, Any>,
    val saveableOwners: Map<Pair<String, String>, Long>,
)

private data class OwnerIncarnation(
    val continuityPath: String,
    val token: Long,
)

/** Acceptance-thread projection for retained presentation identity.
 *
 * A surviving lookup + complete presentation path keeps its token. Removal,
 * re-addition, or a key/type/ancestor path transition receives a fresh local
 * token, so even A -> B -> A between two UI collections cannot resurrect A's
 * old registry. Tokens are independent of wire revisions because release may
 * legitimately restart a surface's revision history. Only current paths are
 * retained.
 *
 * The root gets a fresh opaque identity on absent-to-present and lets the
 * surface host reset all local presentation after remove + byte-identical
 * re-add even if its JsonObject StateFlow collector never observes the null.
 */
class RetainedPresentationIncarnationTracker(
    private var incarnationClock: Long = 0L,
) {
    private val roots = LinkedHashMap<String, Any>()
    private val owners = LinkedHashMap<String, Map<String, OwnerIncarnation>>()

    /** Reclaim the bounded numeric namespace without risking aliasing: every
     * live surface first gets a fresh opaque root, which fences every old
     * SaveableStateHolder, then all current owner tokens are compacted. */
    private fun rebaseIncarnations() {
        roots.keys.toList().forEach { surface -> roots[surface] = Any() }
        incarnationClock = 0L
        owners.keys.toList().forEach { surface ->
            owners[surface] = owners.getValue(surface)
                .mapValuesTo(LinkedHashMap()) { (_, owner) ->
                    owner.copy(token = ++incarnationClock)
                }
        }
    }

    private fun ensureIncarnationCapacity(required: Int) {
        if (required > 0 &&
            incarnationClock > Long.MAX_VALUE - required.toLong()) {
            rebaseIncarnations()
        }
    }

    private fun nextIncarnation(): Long = ++incarnationClock

    @Synchronized
    fun accept(
        surface: String,
        revision: Long?,
        completeSpec: JsonObject?,
    ): RetainedPresentationIncarnations {
        if (completeSpec == null) {
            roots.remove(surface)
            owners.remove(surface)
        } else {
            requireNotNull(revision) {
                "present surface requires an accepted revision"
            }
            // Root keys do not enter a SaveableStateRegistry, so an opaque
            // identity token cannot alias a restarted wire revision history.
            roots.putIfAbsent(surface, Any())
            val lifecyclePaths = retainedSaveableLifecyclePaths(completeSpec)
            var prior = owners[surface].orEmpty()
            val required = lifecyclePaths.count { (lookupPath, continuityPath) ->
                prior[lookupPath]?.continuityPath != continuityPath
            }
            ensureIncarnationCapacity(required)
            // Rebase replaces every owners map, so refresh this reference.
            prior = owners[surface].orEmpty()
            owners[surface] = lifecyclePaths
                .mapValuesTo(LinkedHashMap()) { (lookupPath, continuityPath) ->
                    prior[lookupPath]
                        ?.takeIf { it.continuityPath == continuityPath }
                        ?: OwnerIncarnation(continuityPath, nextIncarnation())
                }
        }
        return RetainedPresentationIncarnations(
            surfaceRoots = LinkedHashMap(roots),
            saveableOwners = buildMap {
                owners.forEach { (surfaceId, paths) ->
                    paths.forEach { (path, incarnation) ->
                        put(surfaceId to path, incarnation.token)
                    }
                }
            },
        )
    }
}
