// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calebc42.ebp.wire.IdentityPath
import com.calebc42.ebp.wire.Materializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SPEC 13.8.5/14.3 (amendment #195): the identity path the renderer reports
 * on a dispatch is the one the engine keyed the expanded template items on,
 * through a scaffold slot and a lazy column — the grammar must agree on both
 * sides or `item` is never injected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemplateSourcePathTest {
    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private val host = SpikeHost()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val authored = json(
        """{"t":"scaffold","top_bar":{"t":"button","label":"Top","on_tap":{"action":"top.tap"}},
            "body":{"t":"lazy_column","bind":{"children":"/rows"},"template":{"t":"row","bind":{"key":"id"},"children":[
                {"t":"text","bind":{"text":"title"}},
                {"t":"button","bind":{"label":"open"},"on_tap":{"action":"note.open"}}]}}}""",
    )
    private val data = json("""{"rows":[{"id":"n1","title":"Inbox","open":"Open Inbox"},{"id":"n2","title":"Projects","open":"Open Projects"}]}""")

    @Test
    fun aTapInsideATemplateItemReportsTheEnginesItemPath() {
        val materialized = Materializer.materializeWithItems(authored, data, "spec", NodeSupport.NODE_VOCABULARY)
        compose.setContent {
            EbpTheme(null) {
                RenderNode(materialized.spec, "app:test", host)
            }
        }
        compose.onNodeWithText("Open Projects").performClick()
        compose.runOnIdle {
            val reported = host.dispatched.single().sourcePath!!
            // The engine's own derivation of the same node: root → body → row n2 → button.
            val root = IdentityPath.child("", materialized.spec, "")
            val body = materialized.spec.getValue("body") as JsonObject
            val row = (body.getValue("children") as kotlinx.serialization.json.JsonArray)[1] as JsonObject
            val button = (row.getValue("children") as kotlinx.serialization.json.JsonArray)[1] as JsonObject
            val expected = IdentityPath.child(
                IdentityPath.child(IdentityPath.child(root, body, "body"), row, "children[1]"),
                button, "children[1]",
            )
            assertEquals(expected, reported)
            // And that path sits under the item root the engine recorded as n2.
            val itemRoot = materialized.itemKeys.keys.single { reported.startsWith("$it/") }
            assertEquals("n2", materialized.itemKeys.getValue(itemRoot))
        }
        host.dispatched.clear()
        compose.onNodeWithText("Top").performClick()
        compose.runOnIdle {
            val outside = host.dispatched.single().sourcePath!!
            assertNull(materialized.itemKeys.keys.firstOrNull { outside == it || outside.startsWith("$it/") })
        }
    }
}
