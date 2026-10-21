// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PLAN-responsiveness C4a: a lazy row's projection and the renderer's
 * JSON-keyed `remember`s compare the JSON by reference. The same instance is
 * the same authored value (the engine shares every untouched subtree across
 * revisions); an equal copy is a new revision and is treated as one.
 */
class LazyRenderItemIdentityTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun aRowEqualsItselfBySharedInstanceNotByContent() {
        val node = json("""{"t":"text","key":"a","text":"A"}""")
        val copy = json("""{"t":"text","key":"a","text":"A"}""")
        assertEquals(node, copy) // kotlinx: structurally equal …
        val row = LazyRenderItem("k:a", "text", "/k:a", node)
        val sameInstance = LazyRenderItem("k:a", "text", "/k:a", node)
        val equalCopy = LazyRenderItem("k:a", "text", "/k:a", copy)
        assertEquals(row, sameInstance)
        assertEquals(row.hashCode(), sameInstance.hashCode())
        assertNotEquals(row, equalCopy) // … but a row is the instance it was built from.
        assertNotEquals(row, LazyRenderItem("k:b", "text", "/k:a", node))
        assertNotEquals(row, LazyRenderItem("k:a", "text", "/k:b", node))
    }

    @Test
    fun aRefIsItsInstance() {
        val children = Json.parseToJsonElement("""[{"t":"text","text":"x"}]""") as JsonArray
        val copy = Json.parseToJsonElement("""[{"t":"text","text":"x"}]""") as JsonArray
        assertEquals(children, copy)
        assertEquals(ref(children), ref(children))
        assertEquals(ref(children).hashCode(), ref(children).hashCode())
        assertNotEquals(ref(children), ref(copy))
    }
}
