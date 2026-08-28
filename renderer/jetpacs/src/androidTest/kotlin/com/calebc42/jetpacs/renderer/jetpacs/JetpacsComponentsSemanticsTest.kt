// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.jetpacs.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JetpacsComponentsSemanticsTest {
    @get:Rule
    val compose = createAndroidComposeRule<SemanticsHostActivity>()

    @Test
    fun actionHasOneButtonTargetAndDispatchesOnce() {
        var taps = 0
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsAction("Run", onClick = { taps += 1 })
            }
        }

        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNodeWithText("Run")
            .assert(button)
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun disabledActionExposesDisabledState() {
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsAction("Disabled", onClick = {}, enabled = false)
            }
        }
        compose.onNodeWithText("Disabled").assertIsNotEnabled()
    }

    @Test
    fun choiceDispatchesStateThenOrdinaryActionExactlyOnce() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{"t":"jetpacs.choice","id":"choice","label":"Mirror","checked":false,"on_change":{"action":"catalog.choice"}}""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsComponentsRenderer.render(node, context, Modifier)
            }
        }

        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        compose.onNodeWithText("Mirror")
            .assert(checkbox)
            .assertHasClickAction()
            .performClick()
            .assertIsOn()
        compose.runOnIdle {
            assertEquals(1, context.states.size)
            assertEquals(1, context.actions.size)
            assertEquals("choice", context.states.single().first)
            assertEquals(context.states.single().second, context.actions.single().second)
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun panelHeadingDoesNotMergeItsInteractiveChild() {
        var taps = 0
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsPanel("STATUS") {
                    BasicText("Ready")
                    JetpacsAction("Nested", onClick = { taps += 1 })
                }
            }
        }

        compose.onNodeWithText("STATUS").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
        )
        compose.onNodeWithText("Ready")
        compose.onNodeWithText("Nested").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    private class RecordingContext : ComposeExtensionRenderContext {
        override val surface = "app:test"
        override val path = "root"
        override val inDialog = false
        val states = mutableListOf<Pair<String, JsonElement?>>()
        val actions = mutableListOf<Pair<JsonObject?, JsonElement?>>()

        override fun action(
            descriptor: JsonObject?,
            value: JsonElement?,
            onOutcome: (RendererActionOutcome) -> Unit,
        ): ActionHandoff {
            actions += descriptor to value
            return ActionHandoff.HandedOff
        }

        override fun state(id: String, value: JsonElement?) {
            states += id to value
        }

        override fun storeValue(id: String): JsonElement? = null
        override fun epochOf(id: String): Long = 0

        @Composable
        override fun renderChild(child: JsonObject, index: Int, modifier: Modifier) = Unit
    }
}
