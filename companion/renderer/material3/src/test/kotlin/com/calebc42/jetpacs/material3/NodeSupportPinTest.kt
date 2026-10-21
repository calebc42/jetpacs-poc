// SPDX-License-Identifier: GPL-3.0-or-later
// Pins the renderer's component registry (MaterialComponents) to
// NodeSupport.APP_NODE_TYPES — which is also what DeviceBridge advertises
// (surface_profiles is BUILT from NodeSupport), so: dispatch == advertised ⊆
// installed vocabulary. A component installed without advertising fails
// here; advertising without a component fails here; advertising outside
// installed renderer schemas fails here. The registry replaced a `when`
// whose labels this test used to scrape from the source.
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.wire.CORE_NODE_SET
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSupportPinTest {

    private val relPath =
        "src/main/kotlin/com/calebc42/jetpacs/material3/Renderer.kt"

    private fun rendererSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relPath),
                File(dir, "renderer/material3/$relPath"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        error("Renderer.kt not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun dispatchMatchesAdvertisedAppNodeTypes() {
        assertEquals(NodeSupport.APP_NODE_TYPES.toSortedSet(),
            MaterialComponents.nodeTypes.toSortedSet())
    }

    @Test
    fun everyComponentReadsThroughItsOwnMemberSet() {
        // The registry rejects a mismatch at construction; this pins that the
        // generated member sets cover every installed component and that the
        // five Material extension nodes read through the manifest's typed
        // members rather than core ones.
        for (component in MaterialComponents.registry.components) {
            assertEquals(component.nodeType, component.members.nodeType)
            assertFalse("${component.nodeType} has no typed members",
                component.members.all.isEmpty() && component.nodeType != "spacer" &&
                    component.nodeType != "divider")
        }
        assertEquals(
            JETPACS_MATERIAL3_NODE_SCHEMA.keys,
            MaterialComponents.nodeTypes.filter { it.startsWith("material3.") }.toSet(),
        )
    }

    @Test
    fun everyAdvertisedTypeIsInTheContract() {
        for (set in listOf(NodeSupport.APP_NODE_TYPES, NodeSupport.DIALOG_NODE_TYPES,
            NodeSupport.NOTIFICATION_NODE_TYPES)) {
            val unknown = set - NodeSupport.NODE_VOCABULARY.schema.keys
            assertTrue("advertised outside the installed vocabulary: $unknown",
                unknown.isEmpty())
        }
    }

    @Test
    fun coreNodeSetIsAlwaysAdvertised() {
        // SPEC 10.2/16.2: the app and dialog profiles carry the Core Node Set.
        assertTrue((CORE_NODE_SET - NodeSupport.APP_NODE_TYPES).isEmpty())
        assertTrue((CORE_NODE_SET - NodeSupport.DIALOG_NODE_TYPES).isEmpty())
    }

    @Test
    fun requiredBuiltinsAreAdvertised() {
        // SPEC 10.2: app carries view.switch + companion.settings.open;
        // dialog carries dialog.submit + dialog.dismiss.
        assertTrue(NodeSupport.APP_BUILTINS.containsAll(
            setOf("view.switch", "companion.settings.open")))
        assertTrue(NodeSupport.APP_BUILTINS.contains("surface.open"))
        assertTrue(NodeSupport.APP_BUILTINS.contains("variant.switch"))
        assertTrue(NodeSupport.APP_FEATURES.contains("action.open_surface"))
        assertFalse(NodeSupport.DIALOG_FEATURES.contains("action.open_surface"))
        assertFalse(NodeSupport.NOTIFICATION_FEATURES.contains("action.open_surface"))
        assertTrue(NodeSupport.DIALOG_BUILTINS.containsAll(
            setOf("dialog.submit", "dialog.dismiss")))
    }

    @Test
    fun materialExtensionIsExplicitAndDualGated() {
        assertTrue(JETPACS_MATERIAL3_EXTENSION in NodeSupport.APP_EXTENSIONS)
        assertTrue(JETPACS_MATERIAL3_EXTENSION in NodeSupport.DIALOG_EXTENSIONS)
        assertTrue(NodeSupport.NOTIFICATION_EXTENSIONS.isEmpty())
        val owned = JETPACS_MATERIAL3_NODE_SCHEMA.keys
        assertTrue(NodeSupport.APP_NODE_TYPES.containsAll(owned))
        val profile = NodeSupport.surfaceProfiles()
            .getValue("app") as JsonObject
        assertEquals(
            listOf(JETPACS_MATERIAL3_EXTENSION),
            (profile.getValue("extensions") as JsonArray).map {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            },
        )
    }

    @Test
    fun imageRequiresAFormFeature() {
        // SPEC 17.2: advertising `image` requires image.https or image.data in
        // the SAME profile's features.
        for ((nodes, features) in listOf(
            NodeSupport.APP_NODE_TYPES to NodeSupport.APP_FEATURES,
            NodeSupport.DIALOG_NODE_TYPES to NodeSupport.DIALOG_FEATURES,
            NodeSupport.NOTIFICATION_NODE_TYPES to NodeSupport.NOTIFICATION_FEATURES)) {
            if ("image" in nodes)
                assertTrue("image advertised without a form feature",
                    "image.https" in features || "image.data" in features)
        }
    }

    @Test
    fun pureHelpersClampNotThrow() {
        // The render-never-throws philosophy: bad numbers skip, not crash.
        assertEquals(null, safeDp(-1.0))
        assertEquals(null, safeDp(Double.NaN))
        assertEquals(12f, safeDp(12.0))
        assertEquals(null, safeFraction(1.5))
        assertEquals(0.5f, safeFraction(0.5))
        assertEquals(null, safeAspect(0.0))
        assertEquals(null, safeAlpha(1.01))
    }

    @Test
    fun hexColorForms() {
        // §16.6: #rgb / #rgba / #rrggbb / #rrggbbaa, case-insensitive.
        assertEquals(0xFFFF0000L, parseHexColor("#f00"))
        assertEquals(0xFFFF0000L, parseHexColor("#FF0000"))
        assertEquals(0x80FF0000L, parseHexColor("#ff000080"))
        assertEquals(0xFF00FF00L.let { it }, parseHexColor("#0f0"))
        assertEquals(null, parseHexColor("#12345"))
        assertEquals(null, parseHexColor("primary"))
        assertEquals(null, parseHexColor("#gg0000"))
        // #rgba: alpha nibble widened, alpha in the high byte.
        assertEquals(0x88FF0000L, parseHexColor("#f008"))
    }

    @Test
    fun lazyKeysAreStableAndUnique() {
        val children = Json.parseToJsonElement("""[
            {"t":"text","key":"a"},
            {"t":"text_input","id":"field"},
            {"t":"text"},
            {"t":"text","key":"a"}
        ]""") as JsonArray
        val keys = lazyChildKeys(children)
        assertEquals(listOf(
            typedIdentitySegment("k", "a", "text"),
            typedIdentitySegment("id", "field", "text_input"),
            typedIdentitySegment("i", "2", "text"),
            typedIdentitySegment("k", "a", "text") + "#1",
        ), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun lazyItemProjectionIsStableAcrossSharedTreesAndTyped() {
        // PLAN-responsiveness C4a: a row projected twice from the SAME tree
        // is equal; one projected from an equal but fresh tree is a new
        // revision of the row (the engine shares untouched subtrees across
        // revisions, so a fresh instance means the row changed).
        fun children() = Json.parseToJsonElement("""[
            {"t":"text","key":"a","text":"same"},
            {"t":"button","id":"action","label":"Go"}
        ]""") as JsonArray
        val firstChildren = children()
        val freshChildren = children()
        val first = lazyRenderItem(
            firstChildren, lazyChildKeys(firstChildren), "/root", 0)
        val again = lazyRenderItem(
            firstChildren, lazyChildKeys(firstChildren), "/root", 0)
        val fresh = lazyRenderItem(
            freshChildren, lazyChildKeys(freshChildren), "/root", 0)

        assertEquals(first, again)
        assertEquals(first.hashCode(), again.hashCode())
        assertNotSame(first.node, fresh.node)
        assertEquals(first.node, fresh.node)
        assertNotEquals(first, fresh)
        // The projection's own fields are value-stable across the fresh tree.
        assertEquals(first.key, fresh.key)
        assertEquals(first.path, fresh.path)
        assertEquals(typedIdentitySegment("k", "a", "text"), first.key)
        assertEquals("text", first.contentType)
        assertEquals("/root/${typedIdentitySegment("k", "a", "text")}", first.path)
    }

    @Test
    fun lightweightLazyRowsUseBoundedHeadingStableChunks() {
        val children = Json.parseToJsonElement("""[
            {"t":"row","key":"org-row-heading"},
            {"t":"rich_text","key":"org-row-2"},
            {"t":"rich_text","key":"org-row-3"},
            {"t":"rich_text","key":"org-row-4"},
            {"t":"rich_text","key":"org-row-5"},
            {"t":"row","key":"org-row-next"},
            {"t":"rich_text","key":"org-row-7","scroll_here":true},
            {"t":"rich_text","key":"org-row-8"},
            {"t":"card","key":"ordinary-card"}
        ]""") as JsonArray

        val projection = lazyColumnProjection(children,
            maxPresentationRowsPerChunk = 4)

        assertEquals(
            listOf(0 to 4, 4 to 5, 5 to 6, 6 to 8, 8 to 9),
            projection.chunks.map { it.first to it.endExclusive },
        )
        assertEquals("chunk:${typedIdentitySegment("k", "org-row-heading", "row")}",
            projection.chunks[0].key)
        assertEquals("chunk:${typedIdentitySegment("k", "org-row-next", "row")}",
            projection.chunks[2].key)
        assertEquals(3, projection.scrollTargetChunk)
        assertEquals(typedIdentitySegment("k", "ordinary-card", "card"),
            projection.chunks.last().key)
    }

    @Test
    fun configuredPinnedChildKeepsTheOrdinaryProjectionSlotAndIdentity() {
        fun children(pinned: Boolean, reordered: Boolean = false): JsonArray {
            val component = """{
                "t":"example.component","key":"mode-tabs",
                "pinned":$pinned,"scroll_here":true
            }"""
            val ordinary = """{
                "t":"card","key":"ordinary","pinned":true,"children":[]
            }"""
            val json = if (reordered) "[$component,$ordinary]"
                else "[$ordinary,$component]"
            return Json.parseToJsonElement(json) as JsonArray
        }
        val capability = mapOf("example.component" to "pinned")
        val ordinary = lazyColumnProjection(
            children(pinned = false),
            pinnedNodeMembers = capability,
        )
        val pinned = lazyColumnProjection(
            children(pinned = true),
            pinnedNodeMembers = capability,
        )

        // Raw `pinned` on an otherwise advertised/canonical-shaped child is
        // inert unless its renderer slice grants that exact member.
        assertEquals(listOf(false, true), pinned.chunks.map { it.pinned })
        assertEquals(listOf(false, false), ordinary.chunks.map { it.pinned })
        assertEquals(ordinary.childKeys, pinned.childKeys)
        assertEquals(ordinary.chunks.map { it.key }, pinned.chunks.map { it.key })
        assertEquals(ordinary.chunks.map { it.contentType },
            pinned.chunks.map { it.contentType })
        assertEquals(ordinary.scrollTargetChunk, pinned.scrollTargetChunk)

        val reordered = lazyColumnProjection(
            children(pinned = true, reordered = true),
            pinnedNodeMembers = capability,
        )
        val componentKey = typedIdentitySegment(
            "k", "mode-tabs", "example.component")
        assertEquals(componentKey, pinned.chunks[1].key)
        assertEquals(componentKey, reordered.chunks[0].key)
        assertTrue(reordered.chunks[0].pinned)
        assertEquals(0, reordered.scrollTargetChunk)
    }

    @Test
    fun lazyChunkProjectionIsStableAcrossSharedTreesNotFreshOnes() {
        // C4a: the chunk, like its rows, is the instances it was built from;
        // the projection (keys, chunk ranges) stays value-stable across a
        // fresh tree, so the LazyColumn's keyed items keep their identity.
        fun children() = Json.parseToJsonElement("""[
            {"t":"row","key":"org-row-heading"},
            {"t":"rich_text","key":"org-row-body","spans":[{"text":"same"}]}
        ]""") as JsonArray
        val firstChildren = children()
        val freshChildren = children()
        val firstProjection = lazyColumnProjection(firstChildren)
        val freshProjection = lazyColumnProjection(freshChildren)
        assertEquals(firstProjection, freshProjection)
        val first = lazyRenderChunk(firstChildren, firstProjection, "/root", 0)
        val again = lazyRenderChunk(firstChildren, firstProjection, "/root", 0)
        val fresh = lazyRenderChunk(freshChildren, freshProjection, "/root", 0)

        val firstItems = first.items
        val freshItems = fresh.items
        assertEquals(first, again)
        assertNotSame(firstItems[0].node, freshItems[0].node)
        assertNotEquals(first, fresh)
        assertEquals(first.key, fresh.key)
        assertEquals(firstItems.map { it.path }, freshItems.map { it.path })
        assertEquals("presentation_chunk", first.contentType)
        assertEquals(listOf(
            "/root/${typedIdentitySegment("k", "org-row-heading", "row")}",
            "/root/${typedIdentitySegment("k", "org-row-body", "rich_text")}",
        ),
            firstItems.map { it.path })
    }

    @Test
    fun presentationChunkIdentitySurvivesSingletonExpansionAndCollapse() {
        fun projection(body: Boolean): LazyColumnProjection {
            val json = if (body) """[
                {"t":"row","key":"heading"},
                {"t":"rich_text","key":"body"}
            ]""" else """[{"t":"row","key":"heading"}]"""
            return lazyColumnProjection(Json.parseToJsonElement(json) as JsonArray)
        }

        val overview = projection(body = false).chunks.single()
        val contents = projection(body = true).chunks.single()
        val collapsedAgain = projection(body = false).chunks.single()

        assertEquals("chunk:${typedIdentitySegment("k", "heading", "row")}",
            overview.key)
        assertEquals(overview.key, contents.key)
        assertEquals(overview.contentType, contents.contentType)
        assertEquals(overview, collapsedAgain)
    }

    @Test
    fun lazyChunkScrollTargetPreservesFirstMarkerWins() {
        val children = Json.parseToJsonElement("""[
            {"t":"text","key":"intro"},
            {"t":"text","key":"first","scroll_here":true},
            {"t":"text","key":"middle"},
            {"t":"card","key":"card","scroll_here":true}
        ]""") as JsonArray

        val projection = lazyColumnProjection(children)

        assertEquals(1, projection.scrollTargetChunk)
        assertEquals("chunk:${typedIdentitySegment("k", "first", "text")}",
            projection.chunks[1].key)
    }

    @Test
    fun identityPathAlwaysIncludesTypeThenPrefersKeyIdOrTreePosition() {
        val n = Json.parseToJsonElement(
            """{"t":"text","key":"k","id":"i"}""") as JsonObject
        assertEquals("/k:6b:t:74657874", identityPath("", n, 3))
        assertEquals("/id:69:t:74657874", identityPath("", Json.parseToJsonElement(
            """{"t":"text","id":"i"}""") as JsonObject, 3))
        assertEquals("/i:33:t:74657874", identityPath("", Json.parseToJsonElement(
            """{"t":"text"}""") as JsonObject, 3))
        assertEquals("612f623a63", encodedIdentityAtom("a/b:c"))

        assertNotEquals(identityPath("", n, 3), identityPath("",
            Json.parseToJsonElement(
                """{"t":"column","key":"k","id":"i"}""") as JsonObject, 3))
        assertNotEquals(
            identityPath("", Json.parseToJsonElement(
                """{"t":"text","id":"i"}""") as JsonObject, 3),
            identityPath("", Json.parseToJsonElement(
                """{"t":"column","id":"i"}""") as JsonObject, 3),
        )
    }

    @Test
    fun eagerSiblingIdentityMovesWithKeyInsteadOfTreeSlot() {
        fun paths(json: String): List<String> {
            val children = Json.parseToJsonElement(json) as JsonArray
            return children.mapIndexed { index, child ->
                identityPath("/root", child as JsonObject, index)
            }
        }
        val before = paths("""[
            {"t":"collapsible","key":"a"},
            {"t":"month_grid","key":"b"}
        ]""")
        val after = paths("""[
            {"t":"month_grid","key":"b"},
            {"t":"collapsible","key":"a"}
        ]""")

        assertEquals(before.reversed(), after)
        assertTrue(rendererSource().contains("key(resolvedPath)"))
    }
}
