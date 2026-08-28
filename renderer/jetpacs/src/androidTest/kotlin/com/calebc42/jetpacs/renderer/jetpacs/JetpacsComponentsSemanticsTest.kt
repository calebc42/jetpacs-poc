// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.wire.SafeAdmissionEvidence
import com.calebc42.ebp.wire.UnsafeAdmissionReason
import com.calebc42.jetpacs.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.jetpacs.renderer.compose.ebpSemantics
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import com.calebc42.jetpacs.renderer.model.RendererVolatileSecret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("Nested").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun scopeRendersChildrenInOwnerScopeWithoutCreatingBounds() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{"t":"jetpacs.scope","children":[{"t":"text","text":"One"},{"t":"text","text":"Two"}]}""",
        ) as JsonObject
        compose.setContent {
            JetpacsComponentsRenderer.render(node, context, Modifier.testTag("scope"))
        }

        compose.onNodeWithText("One").assertIsDisplayed()
        compose.onNodeWithText("Two").assertIsDisplayed()
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "scope"),
        ).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(listOf(0, 1), context.scopedChildren.map { it.second })
        }
    }

    @Test
    fun textFieldHasOneEditableNodeAndUsesTheOrdinaryActionPipeline() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{
              "t":"text_input","id":"command","label":"Command",
              "single_line":true,"filter":"alnum","max_length":4,
              "clear_on_submit":true,
              "on_change":{"action":"catalog.change"},
              "on_submit":{"action":"catalog.submit"}
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsTextInputRenderer.render(
                    node,
                    context,
                    Modifier
                        .testTag("field")
                        .ebpSemantics(node) { context.dispatchAction(it) },
                )
            }
        }

        val field = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "field"),
        )
        field
            .assert(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Command"),
            ))
            .performTextInput("a1😀\n-b")
        compose.runOnIdle {
            assertEquals(listOf("state:a1b", "action:catalog.change"), context.events)
            assertEquals(JsonPrimitive("a1b"), context.actions.single().second)
        }

        field.performImeAction()
        compose.runOnIdle {
            assertEquals(2, context.actions.size)
            assertEquals("catalog.submit", context.actionNames.last())
            assertEquals(JsonPrimitive("a1b"), context.actions.last().second)
            context.outcomes.last()(
                RendererActionOutcome.SafelyAdmitted(SafeAdmissionEvidence.RemoteAccepted),
            )
        }
        compose.runOnIdle {
            assertEquals(JsonPrimitive(""), context.states.last().second)
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        assertTrue(compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size <= 1)
    }

    @Test
    fun maskedTextFieldKeepsLiteralsOutOfLogicalStateAndHasOneEditableOwner() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{
              "t":"text_input","id":"phone","label":"Phone",
              "single_line":true,"mask":"(##) ##",
              "on_change":{"action":"catalog.phone-change"}
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsTextInputRenderer.render(
                    node,
                    context,
                    Modifier
                        .testTag("masked-field")
                        .ebpSemantics(node) { context.dispatchAction(it) },
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "masked-field"),
        ).performTextInput("12😀34")
        compose.runOnIdle {
            assertEquals(listOf("state:12😀34", "action:catalog.phone-change"), context.events)
            assertEquals(JsonPrimitive("12😀34"), context.states.single().second)
            assertEquals(JsonPrimitive("12😀34"), context.actions.single().second)
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test
    fun textFieldProjectsErrorDisabledAndMaximumLengthSemantics() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{
              "t":"text_input","id":"name","label":"Workspace name",
              "value":"taken","enabled":false,"is_error":true,
              "supporting_text":"That name is already in use","max_length":12
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsTextInputRenderer.render(
                    node,
                    context,
                    Modifier
                        .testTag("field")
                        .ebpSemantics(node) { context.dispatchAction(it) },
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "field"),
        )
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.Error,
                "That name is already in use",
            ))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.MaxTextLength, 12))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun secureFieldCapturesOncePublishesNothingAndErasesOnRefusal() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{
              "t":"text_input","id":"secret","label":"One-time secret",
              "password":true,"single_line":true,
              "on_submit":{"action":"catalog.secure-submit","capture_fields":["secret"]}
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsTextInputRenderer.render(
                    node,
                    context,
                    Modifier
                        .testTag("secret")
                        .ebpSemantics(node) { context.dispatchAction(it) },
                )
            }
        }

        val field = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "secret"),
        )
        field.performTextInput("swordfish")
        field.performImeAction()
        compose.runOnIdle {
            assertEquals(emptyList<Pair<String, JsonElement?>>(), context.states)
            assertEquals(1, context.actions.size)
            assertEquals(null, context.actions.single().second)
            assertEquals("swordfish", context.secrets.single()?.fieldsOrNull()
                ?.get("secret")?.let {
                (it as JsonPrimitive).content
            })
        }
        field.assertIsNotEnabled()
        compose.runOnIdle {
            context.outcomes.single()(
                RendererActionOutcome.NotAdmitted(UnsafeAdmissionReason.RemoteRejected),
            )
        }
        field.assertIsEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test
    fun autofocusRunsOnceForThePresentationIdentity() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{"t":"text_input","id":"query","label":"Query","autofocus":true}""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsTextInputRenderer.render(
                    node,
                    context,
                    Modifier.testTag("autofocus"),
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "autofocus"),
        ).assertIsFocused()
    }

    private class RecordingContext : ComposeExtensionRenderContext {
        override val surface = "app:test"
        override val path = "root"
        override val inDialog = false
        override val maxFieldBytes = 65_536
        override val extensionId = JETPACS_COMPONENTS_EXTENSION
        val states = mutableListOf<Pair<String, JsonElement?>>()
        val actions = mutableListOf<Pair<JsonObject?, JsonElement?>>()
        val actionNames = mutableListOf<String>()
        val secrets = mutableListOf<RendererVolatileSecret?>()
        val outcomes = mutableListOf<(RendererActionOutcome) -> Unit>()
        val events = mutableListOf<String>()
        val scopedChildren = mutableListOf<Pair<JsonObject, Int>>()

        override fun dispatchAction(
            descriptor: JsonObject?,
            value: JsonElement?,
            secret: RendererVolatileSecret?,
            sourceId: String?,
            onOutcome: (RendererActionOutcome) -> Unit,
        ): ActionHandoff {
            actions += descriptor to value
            actionNames += (descriptor?.get("action") as? JsonPrimitive)?.content.orEmpty()
            secrets += secret
            outcomes += onOutcome
            events += "action:${actionNames.last()}"
            return ActionHandoff.HandedOff
        }

        override fun state(
            id: String,
            value: JsonElement?,
            volatileSecret: Boolean,
        ) {
            states += id to value
            events += "state:${(value as? JsonPrimitive)?.content.orEmpty()}"
        }

        override fun storeValue(id: String): JsonElement? = null
        override fun epochOf(id: String): Long = 0

        @Composable
        override fun renderChild(child: JsonObject, index: Int, modifier: Modifier) = Unit

        @Composable
        override fun renderScopedChild(
            child: JsonObject,
            index: Int,
            modifier: Modifier,
        ) {
            scopedChildren += child to index
            BasicText((child["text"] as? JsonPrimitive)?.content.orEmpty(), modifier)
        }
    }
}
