// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyedViewportAnchorTest {

    private fun key(value: String, type: String) =
        typedIdentitySegment("k", value, type)

    @Test
    fun insertedItemsDoNotMoveTheRetainedViewportToAnotherIdentity() {
        val saved = KeyedViewportAnchor(
            itemKey = key("heading-b", "row"),
            fallbackIndex = 1,
            scrollOffset = 37,
        )

        assertEquals(
            ResolvedViewport(index = 2, scrollOffset = 37),
            resolveKeyedViewportAnchor(
                saved,
                listOf(key("new", "text"), key("heading-a", "row"),
                    key("heading-b", "row")),
            ),
        )
    }

    @Test
    fun removedAnchorFallsBackSafelyWithoutBorrowingItsOffset() {
        val saved = KeyedViewportAnchor(
            itemKey = key("removed", "text"),
            fallbackIndex = 9,
            scrollOffset = 83,
        )
        assertEquals(
            ResolvedViewport(index = 1, scrollOffset = 0),
            resolveKeyedViewportAnchor(
                saved, listOf(key("a", "text"), key("b", "text"))),
        )
        assertEquals(
            KeyedViewportAnchor(key("b", "text"), 1, 12),
            captureKeyedViewportAnchor(
                listOf(key("a", "text"), key("b", "text")), 1, 12),
        )
    }

    @Test
    fun insertionInsideOnePresentationChunkKeepsTheExactChildAndPixel() {
        val children = Json.parseToJsonElement("""[
            {"t":"text","key":"a"},
            {"t":"text","key":"inserted"},
            {"t":"text","key":"b"},
            {"t":"text","key":"c"}
        ]""") as JsonArray
        val projection = lazyColumnProjection(children)
        val saved = KeyedViewportAnchor(key("b", "text"), 1, 7)
        val offsets = mapOf(
            key("a", "text") to 0,
            key("inserted", "text") to 20,
            key("b", "text") to 52,
            key("c", "text") to 80,
        )

        assertEquals(
            ResolvedViewport(index = 0, scrollOffset = 59),
            resolveLazyColumnViewportAnchor(saved, projection, offsets),
        )
        assertEquals(
            KeyedViewportAnchor(key("b", "text"), 2, 7),
            captureLazyColumnViewportAnchor(projection, 0, 59, offsets),
        )
    }

    @Test
    fun retainedBranchRoundTripUsesTheHostAnchorAfterLocalStateIsForgotten() {
        val children = Json.parseToJsonElement("""[
            {"t":"text","key":"line-50"},
            {"t":"text","key":"line-51"},
            {"t":"text","key":"line-52"},
            {"t":"text","key":"line-53"}
        ]""") as JsonArray
        val projection = lazyColumnProjection(children)
        val offsets = mapOf(
            key("line-50", "text") to 0,
            key("line-51", "text") to 20,
            key("line-52", "text") to 44,
            key("line-53", "text") to 70,
        )
        val providerPath = "/host/v:all/node:lazy/i:7"
        val hostAnchors = RetainedViewportAnchorRegistry()

        val captured = captureLazyColumnViewportAnchor(
            projection, chunkIndex = 0, chunkScrollOffset = 51,
            itemOffsets = offsets)!!
        hostAnchors.record(providerPath, captured)

        // ReusableContentHost deactivation discards the branch-local remember
        // state. Re-creating the branch must seed from the host hand-off, not
        // from that new local state's top-of-list default.
        val newLocalDefault = KeyedViewportAnchor(null, 0, 0)
        val reactivated = hostAnchors.anchor(providerPath) ?: newLocalDefault
        assertEquals(KeyedViewportAnchor(key("line-52", "text"), 2, 7), reactivated)
        assertEquals(
            ResolvedViewport(index = 0, scrollOffset = 51),
            resolveLazyColumnViewportAnchor(reactivated, projection, offsets),
        )

        // The host hand-off is itself process-saveable.
        val restored = RetainedViewportAnchorRegistry.restore(
            hostAnchors.saveableSnapshot())
        assertEquals(reactivated, restored.anchor(providerPath))
    }

    @Test
    fun retiringAnOwnerDropsOnlyItsHoistedViewportAnchor() {
        val registry = RetainedViewportAnchorRegistry()
        val removed = "/host/v:all/node:lazy/i:1"
        val survivor = "/host/v:contents/node:lazy/i:2"
        registry.record(removed, KeyedViewportAnchor("old", 12, 4))
        registry.record(survivor, KeyedViewportAnchor("kept", 3, 9))

        registry.removeAll(setOf(removed))

        assertNull(registry.anchor(removed))
        assertEquals(
            KeyedViewportAnchor("kept", 3, 9),
            registry.anchor(survivor),
        )
    }

    @Test
    fun activeCarouselReorderResolvesTheSavedKeyBeforeRecapture() {
        val saved = KeyedViewportAnchor(key("current", "card"), 1, 0)
        assertEquals(
            ResolvedViewport(index = 2, scrollOffset = 0),
            resolveKeyedViewportAnchor(
                saved,
                listOf(key("other", "card"), key("new", "card"),
                    key("current", "card")),
            ),
        )
    }

    @Test
    fun newScrollMarkerWinsWithoutWaitingForAnInnerRetainedChild() {
        val children = Json.parseToJsonElement("""[
            {"t":"text","key":"a"},
            {"t":"text","key":"old"},
            {"t":"card","key":"target","scroll_here":true,"children":[]}
        ]""") as JsonArray
        val projection = lazyColumnProjection(children)
        val retained = KeyedViewportAnchor(key("old", "text"), 1, 9)
        val targetToken = lazyColumnScrollTargetToken(projection)

        // The retained child is inside an unmeasured chunk and cannot yet be
        // resolved, but the unapplied authored target is immediately known.
        assertEquals(
            null,
            resolveLazyColumnViewportAnchor(retained, projection, emptyMap()),
        )
        assertEquals(
            ResolvedViewport(projection.scrollTargetChunk, 0),
            pendingLazyColumnScrollTarget(projection, targetToken, null),
        )
        assertEquals(
            null,
            pendingLazyColumnScrollTarget(projection, targetToken, targetToken),
        )
    }

    @Test
    fun keyedScrollMarkerMovingToANewAuthoredIndexIsAppliedAgain() {
        val before = lazyColumnProjection(Json.parseToJsonElement("""[
            {"t":"text","key":"a"},
            {"t":"text","key":"target","scroll_here":true}
        ]""") as JsonArray)
        val after = lazyColumnProjection(Json.parseToJsonElement("""[
            {"t":"text","key":"inserted"},
            {"t":"text","key":"a"},
            {"t":"text","key":"target","scroll_here":true}
        ]""") as JsonArray)
        val applied = lazyColumnScrollTargetToken(before)
        val moved = lazyColumnScrollTargetToken(after)

        assertEquals(false, applied == moved)
        assertEquals(
            ResolvedViewport(after.scrollTargetChunk, 0),
            pendingLazyColumnScrollTarget(after, moved, applied),
        )
    }
}
