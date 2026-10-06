// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.calebc42.ebp.renderer.compose.testing.EbpGoldens
import com.calebc42.jetpacs.material3.testing.MaterialTestController
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BUILDING-COMPANION.md: "every widgets.golden line parses and renders (or
 * degrades) without crashing" — through the production Material renderer and
 * its registry, headless. The Material extension corpus
 * (`renderer-extensions/jetpacs-material3.golden`) replays the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialWidgetsGoldenReplayTest {
    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private fun node(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun material3GoldenLines(): List<Pair<String, JsonObject>> {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "renderer-extensions/jetpacs-material3.golden")
            if (candidate.isFile) {
                return candidate.readLines().filter(String::isNotBlank).map { line ->
                    line.substringBefore(' ') to (Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject)
                }
            }
            directory = directory.parentFile
        }
        error("jetpacs-material3.golden not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun everyWidgetsGoldenLineRendersOrDegrades() {
        val lines = EbpGoldens.widgets()
        assertTrue("the corpus must still carry every node type", lines.size >= 72)
        val controller = MaterialTestController(compose)
        controller.start(node("""{"t":"text","text":"start"}"""))
        for (line in lines) {
            val document = if (line.isDescriptor) EbpGoldens.wrapDescriptor(line.node) else line.node
            try {
                controller.update(document)
            } catch (failure: Throwable) {
                throw AssertionError("widgets golden ${line.index} failed to render: ${line.raw}", failure)
            }
        }
    }

    @Test
    fun everyMaterialGoldenLineRenders() {
        val controller = MaterialTestController(compose)
        controller.start(node("""{"t":"text","text":"start"}"""))
        for ((index, document) in material3GoldenLines()) {
            try {
                controller.update(document)
            } catch (failure: Throwable) {
                throw AssertionError("material3 golden $index failed to render", failure)
            }
        }
    }

    @Test
    fun everyDescriptorLineReachesTheHostExactlyOnce() {
        val controller = MaterialTestController(compose)
        controller.start(node("""{"t":"text","text":"start"}"""))
        for (line in EbpGoldens.widgets().filter { it.isDescriptor }) {
            controller.update(EbpGoldens.wrapDescriptor(line.node))
            val before = controller.dispatched.size
            compose.onNodeWithText("x").performClick()
            compose.runOnIdle {
                assertEquals("golden ${line.index}: ${line.raw}", before + 1, controller.dispatched.size)
                assertEquals(line.node, controller.dispatched.last().request.descriptor)
            }
        }
    }

    @Test
    fun textInputsAndTogglesPublishStateBeforeTheirChangeAction() {
        val controller = MaterialTestController(compose)
        controller.start(node("""{"t":"text","text":"start"}"""))
        for (line in EbpGoldens.widgets().filter { !it.isDescriptor }) {
            val type = (line.node["t"] as? JsonPrimitive)?.content
            if (type != "text_input" && type != "checkbox" && type != "switch") continue
            val id = (line.node["id"] as? JsonPrimitive)?.content ?: continue
            if (line.node.containsKey("password")) continue
            controller.update(line.node)
            controller.clearRecords()
            when (type) {
                "text_input" -> compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("z")
                // The control itself, not the EBP semantics wrapper around
                // the row (which carries the toggleable state but no click).
                else -> compose.onAllNodes(isToggleable() and hasClickAction()).onFirst().performClick()
            }
            compose.runOnIdle {
                val events = controller.events
                assertTrue("golden ${line.index} published nothing: $events", events.any { it == "state:$id" })
                val firstAction = events.indexOfFirst { it.startsWith("action:") }
                if (firstAction >= 0) {
                    assertTrue(
                        "golden ${line.index}: state must precede the change action: $events",
                        events.indexOf("state:$id") < firstAction,
                    )
                }
            }
        }
    }

    @Test
    fun anUnadvertisedTypeDegradesToItsChildren() {
        val controller = MaterialTestController(compose)
        controller.start(node("""{"t":"zzz.unknown","children":[{"t":"text","text":"x"}]}"""))

        compose.onNodeWithText("x").assertIsDisplayed()
    }
}
