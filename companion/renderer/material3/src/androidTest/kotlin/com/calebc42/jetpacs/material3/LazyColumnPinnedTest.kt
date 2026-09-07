// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.jetpacs.material3.ui.SemanticsHostActivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LazyColumnPinnedTest {
    @get:Rule
    val compose = createAndroidComposeRule<SemanticsHostActivity>()

    @Test
    fun configuredDirectChildRemainsPinnedAtTheViewportStart() {
        val configuration = NodeSupport.COMPOSE_CONFIGURATION.copy(
            pinnedNodeMembers = mapOf("text" to "pinned"),
        )
        val node = longList(pinned = true)
        val root = RenderCtx(
            surface = "app:sticky-test",
            bridge = InertMaterialHost(),
            configuration = configuration,
        )
        compose.setContent { RenderNode(node, root.child(node, 0)) }

        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Row 30"))

        val header = compose.onNodeWithText("Pinned modes").assertIsDisplayed()
        assertEquals(
            list.fetchSemanticsNode().boundsInRoot.top,
            header.fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
    }

    @Test
    fun rawPinnedMemberWithoutAConfiguredCapabilityStaysOrdinary() {
        val node = longList(pinned = true)
        val root = RenderCtx(
            surface = "app:ordinary-test",
            bridge = InertMaterialHost(),
            configuration = NodeSupport.COMPOSE_CONFIGURATION,
        )
        compose.setContent { RenderNode(node, root.child(node, 0)) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Row 30"))

        compose.onNodeWithText("Pinned modes").assertDoesNotExist()
    }

    @Test
    fun pinningAndInsertingBeforeTheViewportRetainsTheScrollingChildPixel() {
        val configuration = NodeSupport.COMPOSE_CONFIGURATION.copy(
            pinnedNodeMembers = mapOf("text" to "pinned"),
        )
        var node by mutableStateOf(longList(pinned = false))
        val root = RenderCtx(
            surface = "app:sticky-retention-test",
            bridge = InertMaterialHost(),
            configuration = configuration,
        )
        compose.setContent {
            val current = node
            RenderNode(current, root.child(current, 0))
        }

        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Row 30"))
        val before = compose.onNodeWithText("Row 30")
            .fetchSemanticsNode().boundsInRoot.top

        compose.runOnIdle {
            node = longList(pinned = true, insertBeforeHeader = true)
        }
        compose.waitForIdle()

        val header = compose.onNodeWithText("Pinned modes").assertIsDisplayed()
        assertEquals(
            list.fetchSemanticsNode().boundsInRoot.top,
            header.fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
        assertEquals(
            before,
            compose.onNodeWithText("Row 30")
                .fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
    }

    @Test
    fun authoredScrollTargetSettlesBelowTheActivePinnedHeader() {
        val configuration = NodeSupport.COMPOSE_CONFIGURATION.copy(
            pinnedNodeMembers = mapOf("text" to "pinned"),
        )
        var node by mutableStateOf(longList(pinned = true))
        val root = RenderCtx(
            surface = "app:sticky-target-test",
            bridge = InertMaterialHost(),
            configuration = configuration,
        )
        compose.setContent {
            val current = node
            RenderNode(current, root.child(current, 0))
        }
        compose.waitForIdle()

        compose.runOnIdle {
            node = longList(pinned = true, scrollTarget = 30)
        }
        compose.waitForIdle()

        val headerBounds = compose.onNodeWithText("Pinned modes")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val targetBounds = compose.onNodeWithText("Row 30")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "scroll_here target must not be obscured by the sticky header",
            targetBounds.top >= headerBounds.bottom - 1f,
        )
    }

    @Test
    fun authoredTargetWaitsForTheResizedHeaderGeneration() {
        val configuration = NodeSupport.COMPOSE_CONFIGURATION.copy(
            pinnedNodeMembers = mapOf("text" to "pinned"),
        )
        var node by mutableStateOf(
            resizingHeaderList(headerHeight = 48, targeted = false),
        )
        val root = RenderCtx(
            surface = "app:sticky-resize-target-test",
            bridge = InertMaterialHost(),
            configuration = configuration,
        )
        compose.setContent {
            val current = node
            RenderNode(current, root.child(current, 0))
        }
        compose.waitForIdle()
        val oldHeight = compose.onNodeWithText("Resizable modes")
            .fetchSemanticsNode().boundsInRoot.height

        // The target card is already a singleton chunk, so adding scroll_here
        // does not change LazyChunkRange equality. The old implementation
        // could therefore settle with the preceding 48dp measurement.
        compose.runOnIdle {
            node = resizingHeaderList(headerHeight = 128, targeted = true)
        }
        compose.waitForIdle()

        val headerBounds = compose.onNodeWithText("Resizable modes")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val targetBounds = compose.onNodeWithText("Resize target")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(headerBounds.height > oldHeight)
        assertTrue(
            "scroll_here must use the resized header's current measurement",
            targetBounds.top >= headerBounds.bottom - 1f,
        )
    }

    @Test
    fun pinnedAuthoredTargetReplacesThePreviousHeaderAtTheViewportStart() {
        val configuration = NodeSupport.COMPOSE_CONFIGURATION.copy(
            pinnedNodeMembers = mapOf("text" to "pinned"),
        )
        var node by mutableStateOf(pinnedTargetList(targeted = false))
        val root = RenderCtx(
            surface = "app:pinned-scroll-target-test",
            bridge = InertMaterialHost(),
            configuration = configuration,
        )
        compose.setContent {
            val current = node
            RenderNode(current, root.child(current, 0))
        }
        compose.waitForIdle()

        compose.runOnIdle { node = pinnedTargetList(targeted = true) }
        compose.waitForIdle()

        val listTop = compose.onNode(hasScrollAction())
            .fetchSemanticsNode().boundsInRoot.top
        val targetTop = compose.onNodeWithText("Pinned target")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals(listTop, targetTop, 1f)
    }

    private fun longList(
        pinned: Boolean,
        insertBeforeHeader: Boolean = false,
        scrollTarget: Int? = null,
    ): JsonObject = buildJsonObject {
        put("t", "lazy_column")
        put("children", buildJsonArray {
            val header = buildJsonObject {
                put("t", "text")
                put("key", "modes")
                put("text", "Pinned modes")
                put("height", 56)
                put("pinned", pinned)
            }
            val rows = (0 until 40).map { index ->
                buildJsonObject {
                    put("t", "text")
                    put("key", "row-$index")
                    put("text", "Row $index")
                    put("height", 72)
                    if (index == scrollTarget) put("scroll_here", true)
                }
            }
            if (insertBeforeHeader) {
                add(buildJsonObject {
                    put("t", "text")
                    put("key", "inserted")
                    put("text", "Inserted before the retained viewport")
                    put("height", 96)
                })
            }
            add(header)
            rows.forEach(::add)
        })
    }

    private fun resizingHeaderList(
        headerHeight: Int,
        targeted: Boolean,
    ): JsonObject = buildJsonObject {
        put("t", "lazy_column")
        put("children", buildJsonArray {
            add(buildJsonObject {
                put("t", "text")
                put("key", "resizable-modes")
                put("text", "Resizable modes")
                put("height", headerHeight)
                put("pinned", true)
            })
            repeat(24) { index ->
                add(buildJsonObject {
                    put("t", "text")
                    put("key", "resize-row-$index")
                    put("text", "Resize row $index")
                    put("height", 72)
                })
            }
            add(buildJsonObject {
                put("t", "card")
                put("key", "resize-target")
                if (targeted) put("scroll_here", true)
                put("children", buildJsonArray {
                    add(buildJsonObject {
                        put("t", "text")
                        put("text", "Resize target")
                    })
                })
            })
            repeat(8) { index ->
                add(buildJsonObject {
                    put("t", "text")
                    put("key", "resize-after-$index")
                    put("text", "Resize after $index")
                    put("height", 72)
                })
            }
        })
    }

    private fun pinnedTargetList(targeted: Boolean): JsonObject =
        buildJsonObject {
            put("t", "lazy_column")
            put("children", buildJsonArray {
                add(buildJsonObject {
                    put("t", "text")
                    put("key", "primary-header")
                    put("text", "Primary header")
                    put("height", 56)
                    put("pinned", true)
                })
                repeat(24) { index ->
                    add(buildJsonObject {
                        put("t", "text")
                        put("key", "pinned-before-$index")
                        put("text", "Pinned before $index")
                        put("height", 72)
                    })
                }
                add(buildJsonObject {
                    put("t", "text")
                    put("key", "pinned-target")
                    put("text", "Pinned target")
                    put("height", 64)
                    put("pinned", true)
                    if (targeted) put("scroll_here", true)
                })
                // Enough trailing content for a large tablet viewport to
                // place the pinned target at the absolute start without the
                // list's max-scroll clamp determining its final position.
                repeat(32) { index ->
                    add(buildJsonObject {
                        put("t", "text")
                        put("key", "pinned-after-$index")
                        put("text", "Pinned after $index")
                        put("height", 72)
                    })
                }
            })
        }
}
