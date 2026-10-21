// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReorderablePresentationTest {

    @Test
    fun unchangedAuthoredItemsRetainAValidUserOrder() {
        val saved = ReorderablePresentation("revision-a", listOf(2, 0, 1))
        assertSame(saved,
            reconcileReorderablePresentation(saved, "revision-a", 3))
    }

    @Test
    fun inactiveAuthoredChangeOrMalformedRestoreResetsToServerOrder() {
        val saved = ReorderablePresentation("revision-a", listOf(1, 0))
        assertEquals(
            ReorderablePresentation("revision-b", listOf(0, 1, 2)),
            reconcileReorderablePresentation(saved, "revision-b", 3),
        )
        assertEquals(
            ReorderablePresentation("revision-a", listOf(0, 1)),
            reconcileReorderablePresentation(
                ReorderablePresentation("revision-a", listOf(1, 1)),
                "revision-a",
                2,
            ),
        )
    }

    @Test
    fun authoredRevisionIsStableAndContentSensitive() {
        fun items(json: String) = Json.parseToJsonElement(json) as JsonArray
        val first = reorderableItemsRevision(items("""[{"t":"text","key":"a"}]"""))
        val same = reorderableItemsRevision(items("""[{"t":"text","key":"a"}]"""))
        val changed = reorderableItemsRevision(items("""[{"t":"text","key":"b"}]"""))
        assertEquals(64, first.length)
        assertEquals(first, same)
        assertNotEquals(first, changed)
    }

    @Test
    fun inactiveAuthoredInsertionKeepsViewportOnTheSameTypedItemKey() {
        fun keys(json: String): List<String> = lazyChildKeys(
            Json.parseToJsonElement(json) as JsonArray)
        val before = keys("""[
            {"t":"text","key":"a"},
            {"t":"card","key":"current"}
        ]""")
        val saved = captureKeyedViewportAnchor(before, index = 1, scrollOffset = 23)
        val after = keys("""[
            {"t":"text","key":"new"},
            {"t":"text","key":"a"},
            {"t":"card","key":"current"}
        ]""")
        val display = reorderableDisplayKeys(after, listOf(0, 1, 2))

        assertEquals(after, display)
        assertEquals(
            ResolvedViewport(index = 2, scrollOffset = 23),
            resolveKeyedViewportAnchor(saved, display),
        )
    }
}
