// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.glance

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetProjectionTest {
    @Test
    fun firstAuthoredEligibleVariantWins() {
        val spec = buildJsonObject {
            put("body", text("base"))
            put("size_variants", buildJsonArray {
                add(variant(200, 100, "first"))
                add(variant(100, 100, "second"))
            })
        }

        assertEquals("first", selectWidgetBody(spec, 240f, 120f)?.string("text"))
        assertEquals("base", selectWidgetBody(spec, 120f, 80f)?.string("text"))
    }

    @Test
    fun emptyProjectionUsesAuthoredEmptyNode() {
        val spec = buildJsonObject {
            put("body", buildJsonObject {
                put("t", "column")
                put("children", buildJsonArray { add(text("")) })
            })
            put("empty", text("Nothing here"))
        }

        assertEquals("Nothing here", selectWidgetBody(spec, 100f, 100f)?.string("text"))
        assertFalse(nodeHasPresentableContent(text("")))
        assertTrue(nodeHasPresentableContent(text("hello")))
    }

    @Test
    fun profileIsExactAndBinderBounded() {
        assertEquals(GlanceRendererContribution.nodeTypes, GlanceWidgetProfile.memberSupport!!.nodes.keys)
        assertEquals(716_800, GlanceWidgetProfile.limits!!.maxRemoteViewsBytes)
        assertEquals(setOf("surface.open"), GlanceWidgetProfile.builtins)
    }

    private fun text(value: String) = buildJsonObject {
        put("t", "text")
        put("text", value)
    }

    private fun variant(width: Int, height: Int, value: String) = buildJsonObject {
        put("min_width", width)
        put("min_height", height)
        put("body", text(value))
    }
}
