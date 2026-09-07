// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

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
    fun stickyOverlayDoesNotReplaceTheLeadingScrollingAnchor() {
        val children = Json.parseToJsonElement("""[
            {"t":"example.header","key":"tabs","pinned":true},
            {"t":"card","key":"before","children":[]},
            {"t":"card","key":"current","children":[]},
            {"t":"card","key":"after","children":[]}
        ]""") as JsonArray
        val projection = lazyColumnProjection(
            children,
            pinnedNodeMembers = mapOf("example.header" to "pinned"),
        )

        // Foundation reports the overlaid sticky header as visible even when
        // chunk 2 is the item logically crossing the viewport start.
        val leading = leadingLazyColumnViewport(
            projection = projection,
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 13,
            viewportStartOffset = 0,
            visibleItems = listOf(
                LazyViewportItemInfo(index = 0, offset = 0, size = 48),
                LazyViewportItemInfo(index = 2, offset = -13, size = 80),
                LazyViewportItemInfo(index = 3, offset = 67, size = 80),
            ),
        )

        assertEquals(ResolvedViewport(index = 2, scrollOffset = 13), leading)
        assertEquals(
            KeyedViewportAnchor(key("current", "card"), 2, 13),
            captureLazyColumnViewportAnchor(
                projection,
                leading!!.index,
                leading.scrollOffset,
                emptyMap(),
            ),
        )
    }

    @Test
    fun stickyViewportPreservesTopAndAllPinnedFallbacks() {
        val mixed = lazyColumnProjection(
            Json.parseToJsonElement("""[
                {"t":"example.header","key":"tabs","pinned":true},
                {"t":"card","key":"body","children":[]}
            ]""") as JsonArray,
            pinnedNodeMembers = mapOf("example.header" to "pinned"),
        )
        assertEquals(
            ResolvedViewport(0, 0),
            leadingLazyColumnViewport(
                projection = mixed,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                viewportStartOffset = 0,
                visibleItems = listOf(
                    LazyViewportItemInfo(0, 0, 48),
                    LazyViewportItemInfo(1, 48, 80),
                ),
            ),
        )

        val allPinned = lazyColumnProjection(
            Json.parseToJsonElement("""[
                {"t":"example.header","key":"first","pinned":true},
                {"t":"example.header","key":"second","pinned":true}
            ]""") as JsonArray,
            pinnedNodeMembers = mapOf("example.header" to "pinned"),
        )
        assertEquals(
            ResolvedViewport(1, 7),
            leadingLazyColumnViewport(
                projection = allPinned,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 7,
                viewportStartOffset = 0,
                visibleItems = listOf(
                    LazyViewportItemInfo(0, 0, 48),
                    LazyViewportItemInfo(1, -7, 48),
                ),
            ),
        )
    }

    @Test
    fun authoredTargetUsesOnlyTheLastPrecedingStickyHeaderHeight() {
        val projection = lazyColumnProjection(
            Json.parseToJsonElement("""[
                {"t":"example.header","key":"primary","pinned":true},
                {"t":"text","key":"before"},
                {"t":"example.header","key":"section","pinned":true},
                {"t":"text","key":"target","scroll_here":true},
                {"t":"text","key":"after"}
            ]""") as JsonArray,
            pinnedNodeMembers = mapOf("example.header" to "pinned"),
        )
        val target = projection.scrollTargetChunk

        assertEquals(2, precedingPinnedLazyColumnChunk(projection, target))
        assertEquals(
            -64,
            stickyAwareLazyColumnTargetOffset(
                projection,
                target,
                mapOf(0 to 48, 2 to 64),
            ),
        )
        assertEquals(
            0,
            stickyAwareLazyColumnTargetOffset(projection, target, emptyMap()),
        )
    }

    @Test
    fun authoredTargetBeforeAnyStickyHeaderKeepsTheOrdinaryZeroOffset() {
        val projection = lazyColumnProjection(
            Json.parseToJsonElement("""[
                {"t":"text","key":"target","scroll_here":true},
                {"t":"example.header","key":"later","pinned":true}
            ]""") as JsonArray,
            pinnedNodeMembers = mapOf("example.header" to "pinned"),
        )

        assertNull(precedingPinnedLazyColumnChunk(
            projection, projection.scrollTargetChunk))
        assertEquals(
            0,
            stickyAwareLazyColumnTargetOffset(
                projection, projection.scrollTargetChunk, mapOf(1 to 72)),
        )
    }

    @Test
    fun pinnedAuthoredTargetReplacesThePrecedingHeaderAtZeroOffset() {
        val projection = lazyColumnProjection(
            Json.parseToJsonElement("""[
                {"t":"example.header","key":"primary","pinned":true},
                {"t":"text","key":"before"},
                {"t":"example.header","key":"target","pinned":true,
                 "scroll_here":true},
                {"t":"text","key":"after"}
            ]""") as JsonArray,
            pinnedNodeMembers = mapOf("example.header" to "pinned"),
        )
        val target = projection.scrollTargetChunk

        assertEquals(true, projection.chunks[target].pinned)
        assertNull(precedingPinnedLazyColumnChunk(projection, target))
        assertEquals(
            0,
            stickyAwareLazyColumnTargetOffset(
                projection,
                target,
                mapOf(0 to 48, target to 72),
            ),
        )
    }

    @Test
    fun staleStickyMeasurementCannotSatisfyANewContentGeneration() {
        val staleGeneration = Any()
        val currentGeneration = Any()
        val chunk = 2

        assertNull(
            currentLazyStickyHeaderHeight(
                mapOf(
                    chunk to LazyStickyHeaderMeasurement(staleGeneration, 48),
                ),
                chunk,
                currentGeneration,
            ),
        )
        assertEquals(
            96,
            currentLazyStickyHeaderHeight(
                mapOf(
                    chunk to LazyStickyHeaderMeasurement(currentGeneration, 96),
                ),
                chunk,
                currentGeneration,
            ),
        )
        assertEquals(
            0,
            currentLazyStickyHeaderHeight(
                mapOf(
                    chunk to LazyStickyHeaderMeasurement(currentGeneration, 0),
                ),
                chunk,
                currentGeneration,
            ),
        )
    }

    @Test
    fun pinnedToggleAndReorderStillResolveTheSavedChildKey() {
        fun projection(pinned: Boolean, reordered: Boolean): LazyColumnProjection {
            val header = """{
                "t":"example.header","key":"tabs","pinned":$pinned
            }"""
            val current = """{
                "t":"card","key":"current","children":[]
            }"""
            val other = """{
                "t":"card","key":"other","children":[]
            }"""
            val json = if (reordered) "[$other,$header,$current]"
                else "[$header,$current,$other]"
            return lazyColumnProjection(
                Json.parseToJsonElement(json) as JsonArray,
                pinnedNodeMembers = mapOf("example.header" to "pinned"),
            )
        }
        val before = projection(pinned = false, reordered = false)
        val after = projection(pinned = true, reordered = true)
        val saved = KeyedViewportAnchor(key("current", "card"), 1, 9)

        assertEquals(before.chunks[0].key, after.chunks[1].key)
        assertEquals(false, before.chunks[0].pinned)
        assertEquals(true, after.chunks[1].pinned)
        assertEquals(
            ResolvedViewport(index = 2, scrollOffset = 9),
            resolveLazyColumnViewportAnchor(saved, after, emptyMap()),
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
