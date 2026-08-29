// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.SafeAdmissionEvidence
import com.calebc42.ebp.wire.ScalarPos
import com.calebc42.ebp.wire.UnsafeAdmissionReason
import com.calebc42.jetpacs.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.jetpacs.renderer.compose.ebpSemantics
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.CandidateDocument
import com.calebc42.jetpacs.renderer.model.CompletionCandidate
import com.calebc42.jetpacs.renderer.model.CompletionOffer
import com.calebc42.jetpacs.renderer.model.DiagnosticRange
import com.calebc42.jetpacs.renderer.model.DiagnosticSet
import com.calebc42.jetpacs.renderer.model.EldocLine
import com.calebc42.jetpacs.renderer.model.EditorAnnotationState
import com.calebc42.jetpacs.renderer.model.EditorConnectionPhase
import com.calebc42.jetpacs.renderer.model.EditorEditOutcome
import com.calebc42.jetpacs.renderer.model.EditorMirror
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import com.calebc42.jetpacs.renderer.model.RendererEditorHost
import com.calebc42.jetpacs.renderer.model.RendererVolatileSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val secret = "a\uD83D\uDE00b"
        val obfuscated = AnnotatedString("\u2022\u2022\u2022")
        field.performTextInput(secret)
        field
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.InputText,
                obfuscated,
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                obfuscated,
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.TextSelectionRange,
                androidx.compose.ui.text.TextRange(3),
            ))
        field.performImeAction()
        compose.runOnIdle {
            assertEquals(emptyList<Pair<String, JsonElement?>>(), context.states)
            assertEquals(1, context.actions.size)
            assertEquals(null, context.actions.single().second)
            assertEquals(secret, context.secrets.single()?.fieldsOrNull()
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
    fun localEditorPublishesStateAndInjectsCurrentValueIntoEachActionOnce() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{
              "t":"editor","id":"command","single_line":true,
              "publish_state":true,"line_numbers":true,
              "on_enter":{"action":"catalog.editor-enter"},
              "on_save":{"action":"catalog.editor-save"}
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsEditorRenderer.render(
                    node,
                    context,
                    Modifier
                        .testTag("editor")
                        .ebpSemantics(node) { context.dispatchAction(it) },
                )
            }
        }

        val editor = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "editor"),
        )
        editor.assert(hasSetTextAction()).performTextInput("alpha\nbeta")
        compose.runOnIdle {
            assertEquals(listOf("state:alphabeta"), context.events)
            assertEquals(JsonPrimitive("alphabeta"), context.states.single().second)
        }

        editor.performImeAction()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("catalog.editor-enter", "catalog.editor-save"),
                context.actionNames,
            )
            assertEquals(
                listOf(JsonPrimitive("alphabeta"), JsonPrimitive("alphabeta")),
                context.actions.map { it.second },
            )
            assertTrue(context.editorEdits.isEmpty())
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test
    fun synchronizedEditorRetainsTextAndDisablesEveryActionWhenOffline() {
        val context = RecordingContext()
        context.editorMirrors.value = mapOf(
            ("doc:catalog" to "sync") to EditorMirror(
                text = "seed",
                cursorUtf16 = 4,
                selectionStartUtf16 = 4,
                selectionEndUtf16 = 4,
                sequence = 0,
                epoch = 1,
            ),
        )
        val node = Json.parseToJsonElement(
            """{
              "t":"editor","id":"sync","document":"doc:catalog",
              "value":"authored","complete":true,
              "on_save":{"action":"catalog.sync-save"},
              "toolbar":[{"label":"Prefix","snippet":"* ","placement":"line-start"}]
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsEditorRenderer.render(
                    node,
                    context,
                    Modifier
                        .testTag("sync-editor")
                        .ebpSemantics(node) { context.dispatchAction(it) },
                )
            }
        }

        val editor = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sync-editor"),
        )
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertEquals(1, context.completionRequests) }
        editor.performTextInput("!")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("!"), context.editorEdits)
            assertEquals(listOf("catalog.sync-save"), context.actionNames)
            context.editorConnectionPhase.value = EditorConnectionPhase.OFFLINE
        }

        compose.onNodeWithText(
            "Emacs is offline — synchronized editor is read-only",
        ).assertIsDisplayed()
        editor
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("seed!"),
            ))
        compose.onNodeWithText("Prefix").assertIsNotEnabled()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("!"), context.editorEdits)
            assertEquals(1, context.actions.size)
            assertEquals(2, context.completionRequests)
        }
    }

    @Test
    fun synchronizedToolingIsAccessibleCurrentAndOccurrenceScoped() {
        val context = RecordingContext()
        val key = "doc:catalog" to "sync"
        context.editorMirrors.value = mapOf(
            key to EditorMirror("pri", 3, 3, 3, sequence = 4, epoch = 1),
        )
        context.editorAnnotations.value = mapOf(
            key to EditorAnnotationState(
                diagnostics = DiagnosticSet(
                    session = "session",
                    sequence = 4,
                    text = "pri",
                    diagnostics = listOf(DiagnosticRange(0, 3, "error", "Incomplete call")),
                ),
                eldoc = EldocLine("session", 4, "Fallback documentation"),
                epoch = 1,
            ),
        )
        context.completionOffers.value = mapOf(
            key to CompletionOffer(
                prefix = "pri",
                candidates = listOf(
                    CompletionCandidate("print", "built-in", "print()", "function"),
                ),
                session = "session",
                sequence = 4,
                cursor = 3,
                epoch = 9,
            ),
        )
        context.completionOfferViews.value = mapOf(
            key to CompletionOfferView("pri", "", active = true),
        )
        val node = Json.parseToJsonElement(
            """{
              "t":"editor","id":"sync","document":"doc:catalog",
              "value":"authored","complete":true,"syntax":"elisp",
              "toolbar":[{"label":"Indent","command":"indent-region"}]
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsEditorRenderer.render(
                    node,
                    context,
                    Modifier
                        .testTag("sync-tooling")
                        .ebpSemantics(node) { context.dispatchAction(it) },
                )
            }
        }

        val editor = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sync-tooling"),
        )
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        editor.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.Error,
            "Incomplete call",
        ))
        compose.onNodeWithContentDescription("Error: Incomplete call").assertIsDisplayed()
        val candidate = compose.onNodeWithContentDescription("print, built-in, function")
        candidate.assertHasClickAction().performClick()
        candidate.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle {
            assertEquals(listOf("print" to "print()"), context.completionSelections)
            assertEquals(listOf(0 to 9L), context.candidateDocumentRequests)
            context.candidateDocuments.value = mapOf(
                key to CandidateDocument(0, "Print documentation.", 9),
            )
        }
        compose.onNodeWithText("Print documentation.").assertIsDisplayed()

        compose.onNodeWithText("Indent").performClick()
        compose.runOnIdle {
            assertEquals(
                EditorCommandCall("indent-region", cursor = 3, start = 3, end = 3),
                context.editorCommands.single(),
            )
            context.editorConnectionPhase.value = EditorConnectionPhase.OFFLINE
        }
        compose.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("print, built-in, function"),
            ),
        ).assertCountEquals(0)
        compose.onNodeWithText("Indent").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun localEditorToolbarUsesSharedTransformsAndKeepsActionsSeparate() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{
              "t":"editor","id":"outline","value":"item","publish_state":true,
              "toolbar":[
                {"label":"Prefix","snippet":"* ","placement":"line-start"},
                {"label":"Run","on_tap":{"action":"catalog.toolbar"}},
                {"label":"Insert","menu":[
                  {"label":"Message","snippet":"(message \"${'$'}{input:Text}\")"}
                ]}
              ]
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsEditorRenderer.render(
                    node,
                    context,
                    Modifier.testTag("toolbar-editor"),
                )
            }
        }

        compose.onNodeWithText("Prefix").performClick()
        compose.onNodeWithText("Run").performClick()
        compose.runOnIdle {
            assertEquals(JsonPrimitive("* item"), context.states.single().second)
            assertEquals(listOf("catalog.toolbar"), context.actionNames)
            assertEquals(null, context.actions.single().second)
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Insert").performClick()
        compose.onNodeWithText("Message").performClick()
        compose.onNodeWithContentDescription("Input").assert(hasSetTextAction())
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
    }

    @Test
    fun readOnlyEditorExposesStateAndMakesToolbarAndSaveInert() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{
              "t":"editor","id":"readonly","value":"locked","read_only":true,
              "on_save":{"action":"catalog.save"},
              "toolbar":[{"label":"Prefix","snippet":"* ","placement":"line-start"}]
            }""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsEditorRenderer.render(
                    node,
                    context,
                    Modifier
                        .testTag("readonly-editor")
                        .ebpSemantics(node) { context.dispatchAction(it) },
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "readonly-editor"),
        ).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false),
        )
        compose.onNodeWithText("Prefix").assertIsNotEnabled()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(context.states.isEmpty())
            assertTrue(context.actions.isEmpty())
        }
    }

    @Test
    fun localEditorAutofocusRunsForItsPresentationIdentity() {
        val context = RecordingContext()
        val node = Json.parseToJsonElement(
            """{"t":"editor","id":"draft","autofocus":true}""",
        ) as JsonObject
        compose.setContent {
            ProvideJetpacsTheme(null) {
                JetpacsEditorRenderer.render(
                    node,
                    context,
                    Modifier.testTag("editor-autofocus"),
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TestTag,
                "editor-autofocus",
            ),
        ).assertIsFocused()
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

    private data class EditorCommandCall(
        val command: String,
        val cursor: Int,
        val start: Int,
        val end: Int,
    )

    private class RecordingContext : ComposeExtensionRenderContext, RendererEditorHost {
        override val surface = "app:test"
        override val path = "root"
        override val inDialog = false
        override val maxFieldBytes = 65_536
        override val editorHost: RendererEditorHost get() = this
        override val extensionId = JETPACS_COMPONENTS_EXTENSION
        override val maxEditorBytes = 262_144
        override val editorConnectionPhase = MutableStateFlow(EditorConnectionPhase.READY)
        override val editorMirrors =
            MutableStateFlow<Map<Pair<String, String>, EditorMirror>>(emptyMap())
        override val editorAnnotations =
            MutableStateFlow<Map<Pair<String, String>, EditorAnnotationState>>(emptyMap())
        override val completionOffers =
            MutableStateFlow<Map<Pair<String, String>, CompletionOffer>>(emptyMap())
        override val completionOfferViews =
            MutableStateFlow<Map<Pair<String, String>, CompletionOfferView>>(emptyMap())
        override val candidateDocuments =
            MutableStateFlow<Map<Pair<String, String>, CandidateDocument>>(emptyMap())
        override var completionNarrowing = CompletionNarrowing.STRICT
        val states = mutableListOf<Pair<String, JsonElement?>>()
        val actions = mutableListOf<Pair<JsonObject?, JsonElement?>>()
        val actionNames = mutableListOf<String>()
        val secrets = mutableListOf<RendererVolatileSecret?>()
        val outcomes = mutableListOf<(RendererActionOutcome) -> Unit>()
        val events = mutableListOf<String>()
        val scopedChildren = mutableListOf<Pair<JsonObject, Int>>()
        val editorEdits = mutableListOf<String>()
        val completionSelections = mutableListOf<Pair<String, String>>()
        val candidateDocumentRequests = mutableListOf<Pair<Int, Long>>()
        val editorCommands = mutableListOf<EditorCommandCall>()
        var completionRequests = 0

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

        override fun requestEditorCompletion(document: String, editorId: String) {
            completionRequests++
        }

        override fun selectEditorCompletion(
            document: String,
            editorId: String,
            label: String,
            insert: String,
        ) {
            completionSelections += label to insert
        }

        override fun requestCandidateDocument(
            document: String,
            editorId: String,
            index: Int,
            epoch: Long,
        ) {
            candidateDocumentRequests += index to epoch
        }

        override fun publishEditorEdit(
            document: String,
            editorId: String,
            start: ScalarPos,
            deletedScalars: Int,
            inserted: String,
            base: String,
            onOutcome: (EditorEditOutcome) -> Unit,
        ) {
            editorEdits += inserted
            onOutcome(EditorEditOutcome.ACCEPTED)
        }

        override fun publishEditorCaret(
            document: String,
            editorId: String,
            cursorUtf16: Int,
            selectionStartUtf16: Int,
            selectionEndUtf16: Int,
        ) = Unit

        override fun dispatchEditorCommand(
            surface: String,
            document: String,
            editorId: String,
            command: String,
            cursorUtf16: Int,
            selectionStartUtf16: Int,
            selectionEndUtf16: Int,
        ) {
            editorCommands += EditorCommandCall(
                command,
                cursorUtf16,
                selectionStartUtf16,
                selectionEndUtf16,
            )
        }

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
