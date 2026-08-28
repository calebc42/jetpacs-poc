// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Process-owned projections of the durable app-surface cache.
 *
 * The ID flow changes only when a surface appears or disappears. Each surface
 * has its own flow, so an update to a hidden Nav3 destination cannot recompose
 * the surface the user is currently reading.
 */
internal class AppSurfaceRegistry {
    data class Catalog(
        val loaded: Boolean = false,
        val surfaceIds: List<String> = emptyList(),
    )

    private val surfaceFlows =
        ConcurrentHashMap<String, MutableStateFlow<JsonObject?>>()
    private val orderedIds = LinkedHashSet<String>()
    private val _catalog = MutableStateFlow(Catalog())

    /** IDs and hydration status move atomically, so a restored Nav3 key is
     * never reconciled against a transiently empty pre-hydration catalog. */
    val catalog: StateFlow<Catalog> get() = _catalog

    fun surface(surfaceId: String): StateFlow<JsonObject?> =
        surfaceFlows.computeIfAbsent(surfaceId) { MutableStateFlow(null) }

    @Synchronized
    fun publish(surfaceId: String, spec: JsonObject?) {
        val flow = surfaceFlows.computeIfAbsent(surfaceId) { MutableStateFlow(null) }
        flow.value = spec
        val membershipChanged = if (spec == null) {
            orderedIds.remove(surfaceId)
        } else {
            orderedIds.add(surfaceId)
        }
        if (membershipChanged) {
            _catalog.value = _catalog.value.copy(surfaceIds = orderedIds.toList())
        }
    }

    @Synchronized
    fun markLoaded() {
        _catalog.value = _catalog.value.copy(loaded = true)
    }
}
