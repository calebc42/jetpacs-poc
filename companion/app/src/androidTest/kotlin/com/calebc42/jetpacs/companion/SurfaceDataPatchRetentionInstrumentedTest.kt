// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.wire.JsonPointer
import com.calebc42.ebp.wire.Materializer
import com.calebc42.jetpacs.material3.NodeSupport
import com.calebc42.jetpacs.material3.RenderNode
import com.calebc42.jetpacs.material3.testing.RecordingMaterialHost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC §13.8: a `surface.data` patch reaches the presentation as the same
 * authored tree re-materialized against the patched document. `DeviceBridge`
 * publishes it through the listener an applied `surface.update` uses, under
 * the surface's unchanged root incarnation (`RetainedPresentationTracker`
 * mints that once per present surface), so what the renderer sees is a
 * document whose only difference is a bound value. Its retained presentation
 * — here a lazy column's scroll position — must survive that, and the value
 * must show, through the Companion's real installation.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceDataPatchRetentionInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<CompanionTestHostActivity>()

    private val host = RecordingMaterialHost()

    /** A status line bound above a long list with one bound row deep inside. */
    private fun authored(contentPadding: Int? = null): JsonObject = buildJsonObject {
        put("t", "column")
        put("children", buildJsonArray {
            add(buildJsonObject {
                put("t", "text")
                put("key", "status")
                put("style", "title")
                put("bind", buildJsonObject { put("text", "/planning/status") })
            })
            add(buildJsonObject {
                put("t", "lazy_column")
                put("key", "rows")
                put("weight", 1)
                if (contentPadding != null) put("content_padding", contentPadding)
                put("children", buildJsonArray {
                    (0 until 40).forEach { index ->
                        add(buildJsonObject {
                            put("t", "text")
                            put("key", "row-$index")
                            put("height", 72)
                            if (index == 31) {
                                put("bind", buildJsonObject { put("text", "/planning/row31") })
                            } else {
                                put("text", "Row $index")
                            }
                        })
                    }
                })
            })
        })
    }

    /**
     * Re-materializes [spec] against [data]; with [previous] the shown tree,
     * every untouched subtree comes back as [previous]'s instance
     * (`Materializer.share`), which is what `SurfaceStore.patch` publishes.
     */
    private fun materialize(spec: JsonObject, data: JsonObject, previous: JsonObject? = null): JsonObject =
        Materializer.materialize(spec, data, "spec", NodeSupport.NODE_VOCABULARY, previous)

    /** The row keyed [key] inside the lazy column keyed `rows`, found through the children arrays. */
    private fun row(tree: JsonObject, key: String): JsonObject {
        fun JsonObject.child(k: String): JsonObject = (getValue("children") as JsonArray)
            .map { it as JsonObject }
            .single { it["key"]?.jsonPrimitive?.content == k }
        return tree.child("rows").child(key)
    }

    @Test
    fun aDataPatchKeepsTheScrollPositionAndShowsTheValue() {
        val spec = authored()
        var data = buildJsonObject {
            put("planning", buildJsonObject {
                put("status", "Scheduled · 2026-09-04")
                put("row31", "Row 31")
            })
        }
        var shown by mutableStateOf(materialize(spec, data))
        compose.setContent {
            MaterialTheme {
                RenderNode(
                    shown,
                    "app:patch-retention-test",
                    host,
                    configuration = AndroidCompanionRenderer.installation(true).composeConfiguration,
                )
            }
        }
        compose.onNodeWithText("Scheduled · 2026-09-04").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Row 30"))
        val before = compose.onNodeWithText("Row 30").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        // The patch: two `set` ops on the retained document, nothing else.
        compose.runOnIdle {
            data = JsonPointer.set(
                data, listOf("planning", "status"),
                JsonPrimitive("Scheduled · 2026-09-10"), "params.ops[0].path",
            )
            data = JsonPointer.set(
                data, listOf("planning", "row31"),
                JsonPrimitive("Row 31, patched"), "params.ops[1].path",
            )
            val next = materialize(spec, data, shown)
            assertSame("an untouched row must keep its instance across the patch",
                row(shown, "row-30"), row(next, "row-30"))
            shown = next
        }
        compose.waitForIdle()

        compose.onNodeWithText("Scheduled · 2026-09-10").assertIsDisplayed()
        compose.onNodeWithText("Scheduled · 2026-09-04").assertDoesNotExist()
        compose.onNodeWithText("Row 31, patched").assertIsDisplayed()
        val after = compose.onNodeWithText("Row 30").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals(
            "the lazy column must keep its scroll position across the patch",
            before, after, 1f,
        )
    }

    /** SPEC 13.8.5: the same list as a template over `/rows`. */
    private fun templated(): JsonObject = buildJsonObject {
        put("t", "column")
        put("children", buildJsonArray {
            add(buildJsonObject {
                put("t", "text")
                put("key", "status")
                put("style", "title")
                put("bind", buildJsonObject { put("text", "/planning/status") })
            })
            add(buildJsonObject {
                put("t", "lazy_column")
                put("key", "rows")
                put("weight", 1)
                put("bind", buildJsonObject { put("children", "/rows") })
                put("template", buildJsonObject {
                    put("t", "text")
                    put("height", 72)
                    put("bind", buildJsonObject { put("key", "id"); put("text", "title") })
                })
            })
        })
    }

    @Test
    fun aTemplatedListReExpandsAndKeepsItsScrollPosition() {
        val spec = templated()
        var data = buildJsonObject {
            put("planning", buildJsonObject { put("status", "40 rows") })
            put("rows", buildJsonArray {
                (0 until 40).forEach { index ->
                    add(buildJsonObject { put("id", "row-$index"); put("title", "Row $index") })
                }
            })
        }
        var shown by mutableStateOf(materialize(spec, data))
        compose.setContent {
            MaterialTheme {
                RenderNode(
                    shown,
                    "app:template-retention-test",
                    host,
                    configuration = AndroidCompanionRenderer.installation(true).composeConfiguration,
                )
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Row 30"))
        val before = compose.onNodeWithText("Row 30").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        // One element patched, one row inserted at the top: the expansion is
        // rebuilt, and identity follows the bound key, not the index.
        compose.runOnIdle {
            data = JsonPointer.set(
                data, listOf("rows", "31", "title"),
                JsonPrimitive("Row 31, patched"), "params.ops[0].path",
            )
            val rows = data.getValue("rows") as kotlinx.serialization.json.JsonArray
            data = JsonPointer.set(
                data, listOf("rows"),
                kotlinx.serialization.json.JsonArray(
                    listOf(buildJsonObject { put("id", "row-new"); put("title", "Row new") }) + rows),
                "params.ops[1].path",
            )
            val next = materialize(spec, data, shown)
            assertSame("a pre-existing, unpatched row must keep its instance across the insert",
                row(shown, "row-30"), row(next, "row-30"))
            shown = next
        }
        compose.waitForIdle()

        compose.onNodeWithText("Row 31, patched").assertIsDisplayed()
        val after = compose.onNodeWithText("Row 30").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals(
            "a templated lazy column must keep its scroll position across re-expansion",
            before, after, 1f,
        )
    }

    @Test
    fun aPaddedLazyColumnDoesNotCreepAcrossRepeatedPatches() {
        // Grove's planning canvas on the S21 Ultra: `content_padding` 12 and
        // a viewport scrolled deep into a tall item moved 12 dp toward the
        // start on every re-render, patch or snapshot alike, because the
        // retained anchor was measured from the viewport edge and restored
        // from the padded content origin.
        val spec = authored(contentPadding = 12)
        var data = buildJsonObject {
            put("planning", buildJsonObject {
                put("status", "Scheduled · 2026-10-08")
                put("row31", "Row 31")
            })
        }
        var shown by mutableStateOf(materialize(spec, data))
        compose.setContent {
            MaterialTheme {
                RenderNode(
                    shown,
                    "app:padded-patch-retention-test",
                    host,
                    configuration = AndroidCompanionRenderer.installation(true).composeConfiguration,
                )
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Row 30"))
        val before = compose.onNodeWithText("Row 30").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        repeat(3) { round ->
            compose.runOnIdle {
                data = JsonPointer.set(
                    data, listOf("planning", "row31"),
                    JsonPrimitive("Row 31, patch $round"), "params.ops[0].path",
                )
                val next = materialize(spec, data, shown)
                assertSame("an untouched row must keep its instance in round $round",
                    row(shown, "row-30"), row(next, "row-30"))
                shown = next
            }
            compose.waitForIdle()
            compose.onNodeWithText("Row 31, patch $round").assertIsDisplayed()
            val after = compose.onNodeWithText("Row 30").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot.top
            assertEquals(
                "patch $round moved the padded lazy column",
                before, after, 1f,
            )
        }
    }
}
