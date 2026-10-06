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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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
    private fun authored(): JsonObject = buildJsonObject {
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

    private fun materialize(spec: JsonObject, data: JsonObject): JsonObject =
        Materializer.materialize(spec, data, "spec", NodeSupport.NODE_VOCABULARY)

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
                    configuration = CompanionRenderer.installation(true).composeConfiguration,
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
            shown = materialize(spec, data)
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
}
