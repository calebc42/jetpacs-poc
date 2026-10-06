// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calebc42.ebp.renderer.compose.ComposeComponentRegistry
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.compose.CoreComponents
import com.calebc42.ebp.renderer.compose.testing.ComposeComponentTestController
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every `jetpacs-components.golden` line renders through the extension's
 * component registry, headless, and the interactive ones reach the host the
 * way the contract says: a choice publishes its state before its change
 * action, controlled selectors only dispatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JetpacsComponentsGoldenReplayTest {
    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    /** The extension's components beside the core tier, so authored `text` children show. */
    private val registry = ComposeComponentRegistry<ComposeExtensionRenderContext>(
        JetpacsComponentsRenderer.registry.components + CoreComponents.components,
    )

    private fun controller() = ComposeComponentTestController.forExtensionRegistry(
        compose,
        registry,
        extensionId = JETPACS_COMPONENTS_EXTENSION,
    )

    private fun goldenLines(): List<Pair<String, JsonObject>> {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "renderer-extensions/jetpacs-components.golden")
            if (candidate.isFile) {
                return candidate.readLines().filter(String::isNotBlank).map { line ->
                    line.substringBefore(' ') to
                        (Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject)
                }
            }
            directory = directory.parentFile
        }
        error("jetpacs-components.golden not found from ${System.getProperty("user.dir")}")
    }

    private fun golden(index: String): JsonObject = goldenLines().first { it.first == index }.second

    @Test
    fun everyGoldenLineRendersThroughTheRegistry() {
        val lines = goldenLines()
        assertEquals(
            "the corpus must carry every manifest node type",
            JETPACS_COMPONENTS_NODE_SCHEMA.keys,
            lines.flatMap { it.second.nodeTypes() }.toSet().intersect(JETPACS_COMPONENTS_NODE_SCHEMA.keys),
        )
        val controller = controller()
        controller.start(golden("00"))
        for ((index, document) in lines) {
            try {
                controller.update(document)
            } catch (failure: Throwable) {
                throw AssertionError("jetpacs-components golden $index failed to render", failure)
            }
        }
        controller.update(golden("02"))
        compose.onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test
    fun anActionDispatchesItsDescriptorOnce() {
        val controller = controller()
        controller.start(golden("00"))

        compose.onNodeWithText("Run").performClick()

        compose.runOnIdle {
            assertEquals(listOf("action:catalog.run"), controller.events)
            assertEquals(golden("00")["on_tap"], controller.dispatched.single().descriptor)
        }
    }

    @Test
    fun aChoicePublishesItsStateBeforeItsChangeAction() {
        val controller = controller()
        controller.start(golden("01"))

        compose.onNodeWithText("Mirror updates").performClick()

        compose.runOnIdle {
            assertEquals(listOf("state:catalog-choice", "action:catalog.choice"), controller.events)
            assertEquals(JsonPrimitive(true), controller.states.single().value)
            assertEquals(JsonPrimitive(true), controller.dispatched.single().value)
        }
    }

    @Test
    fun controlledTabsDispatchTheChosenValueWithoutPublishingState() {
        val controller = controller()
        controller.start(golden("04"))

        compose.onNodeWithText("Source").performClick()

        compose.runOnIdle {
            assertEquals(listOf("action:catalog.projection.change"), controller.events)
            assertEquals(JsonPrimitive("source"), controller.dispatched.single().value)
        }
    }

    private fun JsonObject.nodeTypes(): List<String> = buildList {
        (this@nodeTypes["t"] as? JsonPrimitive)?.content?.let(::add)
        for (value in this@nodeTypes.values) {
            when (value) {
                is JsonObject -> addAll(value.nodeTypes())
                is kotlinx.serialization.json.JsonArray ->
                    value.forEach { (it as? JsonObject)?.let { child -> addAll(child.nodeTypes()) } }
                else -> Unit
            }
        }
    }
}
