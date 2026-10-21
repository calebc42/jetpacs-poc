// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the two per-component answers that replaced type-keyed tables: which
 * nodes own retained presentation state, and which a lazy column may batch.
 * The expected sets are the literal tables they replaced.
 */
class MaterialComponentTablesTest {
    private val retainedTypes = setOf(
        "lazy_column", "collapsible", "tabs", "table", "reorderable_list", "pane_scaffold",
        "carousel", "material3.fab_menu", "lazy_grid", "month_grid", "scaffold",
    )
    private val batchableTypes = setOf("row", "rich_text", "text", "spacer", "divider")

    @Test
    fun retainedPresentationOwnersMatchTheFormerTable() {
        val owners = MaterialComponents.nodeTypes.filter { type ->
            retainedNodeOwnsSaveablePresentation(buildJsonObject { put("t", type) })
        }.toSet()
        assertEquals(retainedTypes, owners)
        for (container in listOf("row", "column")) {
            assertEquals(
                true,
                retainedNodeOwnsSaveablePresentation(
                    buildJsonObject { put("t", container); put("scroll", true) },
                ),
            )
            assertEquals(
                false,
                retainedNodeOwnsSaveablePresentation(
                    buildJsonObject { put("t", container); put("scroll", false) },
                ),
            )
            // A string-spelled flag is not a flag (SPEC §17.1).
            assertEquals(
                false,
                retainedNodeOwnsSaveablePresentation(
                    buildJsonObject { put("t", container); put("scroll", "true") },
                ),
            )
        }
        assertEquals(false, retainedNodeOwnsSaveablePresentation(buildJsonObject { put("t", "zzz.unknown") }))
    }

    @Test
    fun batchableLeavesMatchTheFormerTable() {
        val batchable = MaterialComponents.registry.components
            .filter { (it as MaterialComponent).batchable }
            .map { it.nodeType }
            .toSet()
        assertEquals(batchableTypes, batchable)
    }
}
