// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.ScalarPos
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.CandidateDocument
import com.calebc42.jetpacs.renderer.model.CompletionOffer
import com.calebc42.jetpacs.renderer.model.EditorAnnotationState
import com.calebc42.jetpacs.renderer.model.EditorConnectionPhase
import com.calebc42.jetpacs.renderer.model.EditorEditOutcome
import com.calebc42.jetpacs.renderer.model.EditorMirror
import com.calebc42.jetpacs.renderer.model.RendererEditorHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditingControllersInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<EditingHostActivity>()

    @Test
    fun textInputUsesOneTransformationForPasteNormalizationAndPublication() {
        val events = mutableListOf<String>()
        lateinit var controller: TextInputController
        compose.setContent {
            controller = rememberTextInputController(
                presentationEpoch = 0,
                initialText = "",
                initialSelection = TextRange.Zero,
                config = TextInputControllerConfig(
                    id = "title",
                    password = false,
                    singleLine = true,
                    filter = "alnum",
                    maxLengthScalars = 3,
                    clearOnSubmit = false,
                    onChange = buildJsonObject { put("action", "demo.change") },
                    onSubmit = null,
                    publishPasswordLocally = false,
                ),
                maxFieldBytes = 65_536,
                publishState = { events += "state:$it" },
                actionDispatcher = EditingActionDispatcher { _, value, _, _, _ ->
                    events += "action:$value"
                    ActionHandoff.HandedOff
                },
            )
            BasicTextField(
                state = controller.state,
                inputTransformation = controller.inputTransformation,
            )
        }

        compose.onNode(hasSetTextAction()).performTextInput("a1😀\n-b")

        compose.runOnIdle {
            assertEquals("a1b", controller.state.text.toString())
            assertEquals(listOf("state:a1b", "action:\"a1b\""), events)
        }
    }

    @Test
    fun passwordErasureClearsTextAndComposeUndoHistory() {
        lateinit var controller: TextInputController
        compose.setContent {
            controller = rememberTextInputController(
                presentationEpoch = 0,
                initialText = "",
                initialSelection = TextRange.Zero,
                config = TextInputControllerConfig(
                    id = "password",
                    password = true,
                    singleLine = true,
                    filter = null,
                    maxLengthScalars = null,
                    clearOnSubmit = false,
                    onChange = null,
                    onSubmit = null,
                    publishPasswordLocally = false,
                ),
                maxFieldBytes = 65_536,
                publishState = { error("password text must not be published") },
                actionDispatcher = EditingActionDispatcher { _, _, _, _, _ ->
                    ActionHandoff.HandedOff
                },
            )
            BasicTextField(
                state = controller.state,
                inputTransformation = controller.inputTransformation,
            )
        }

        compose.onNode(hasSetTextAction()).performTextInput("heap-canary")

        compose.runOnIdle {
            assertEquals("heap-canary", controller.state.text.toString())
            assertTrue(controller.state.undoState.canUndo)
            controller.eraseVolatileState()
            assertEquals("", controller.state.text.toString())
            assertFalse(controller.state.undoState.canUndo)
            assertFalse(controller.state.undoState.canRedo)
        }
    }

    @Test
    fun editorChangeListPublishesOneScalarSafeSplice() {
        val host = InstrumentedEditorHost()
        lateinit var controller: EditorController
        compose.setContent {
            controller = rememberEditorController(
                presentationEpoch = 0,
                initialText = "a😀b",
                initialSelection = TextRange(1, 3),
                config = EditorControllerConfig(
                    surface = "app:main",
                    id = "body",
                    document = "buffer:one",
                    singleLine = false,
                    publishState = false,
                    maxFieldBytes = 65_536,
                    maxEditorBytes = 65_536,
                ),
                mirror = EditorMirror(
                    text = "a😀b",
                    cursorUtf16 = 3,
                    selectionStartUtf16 = 1,
                    selectionEndUtf16 = 3,
                    sequence = 0,
                    epoch = 1,
                ),
                connectionPhase = EditorConnectionPhase.READY,
                requestCompletion = false,
                enabled = true,
                readOnly = false,
                publishLocalState = {},
                editorHost = host,
                actionDispatcher = EditingActionDispatcher { _, _, _, _, _ ->
                    ActionHandoff.HandedOff
                },
            )
            BasicTextField(
                state = controller.state,
                inputTransformation = controller.inputTransformation,
            )
        }

        val field = compose.onNode(hasSetTextAction())
        field.performTextInputSelection(TextRange(1, 3))
        field.performTextInput("!")

        compose.runOnIdle {
            assertEquals("a!b", controller.state.text.toString())
            assertEquals(
                listOf(InstrumentedEdit(ScalarPos(1), 1, "!", "a😀b")),
                host.edits,
            )
        }
    }
}

private data class InstrumentedEdit(
    val start: ScalarPos,
    val deletedScalars: Int,
    val inserted: String,
    val base: String,
)

private class InstrumentedEditorHost : RendererEditorHost {
    override val maxEditorBytes = 65_536
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
    val edits = mutableListOf<InstrumentedEdit>()

    override fun requestEditorCompletion(document: String, editorId: String) = Unit
    override fun selectEditorCompletion(
        document: String,
        editorId: String,
        label: String,
        insert: String,
    ) = Unit
    override fun requestCandidateDocument(
        document: String,
        editorId: String,
        index: Int,
        epoch: Long,
    ) = Unit
    override fun publishEditorEdit(
        document: String,
        editorId: String,
        start: ScalarPos,
        deletedScalars: Int,
        inserted: String,
        base: String,
        onOutcome: (EditorEditOutcome) -> Unit,
    ) {
        edits += InstrumentedEdit(start, deletedScalars, inserted, base)
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
    ) = Unit
}
