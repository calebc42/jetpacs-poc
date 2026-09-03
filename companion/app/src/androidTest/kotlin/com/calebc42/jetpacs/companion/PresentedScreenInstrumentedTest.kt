// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.renderer.model.ActionHandoff
import com.calebc42.ebp.renderer.model.CandidateDocument
import com.calebc42.ebp.renderer.model.CompletionOffer
import com.calebc42.ebp.renderer.model.EditorAnnotationState
import com.calebc42.ebp.renderer.model.EditorConnectionPhase
import com.calebc42.ebp.renderer.model.EditorEditOutcome
import com.calebc42.ebp.renderer.model.EditorMirror
import com.calebc42.ebp.renderer.model.RendererActionOutcome
import com.calebc42.ebp.renderer.model.RendererActionRequest
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.ebp.wire.ScalarPos
import com.calebc42.glasspane.material3.MaterialRendererHost
import com.calebc42.glasspane.material3.RenderNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A host screen presented inside the baseline design scope renders through
 * the Companion's real installation: Material chrome and layout, Foundation
 * overrides for the nodes the design runtime owns.
 */
@RunWith(AndroidJUnit4::class)
class PresentedScreenInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<CompanionTestHostActivity>()

    private val host = InertHost()

    private fun spec(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun theHubBodyRendersUnderThePresentedScope() {
        // The exact REPL body the host presents: a weighted empty state over a
        // divider and an editor row, inside a scaffold, inside the scope.
        val node = spec(
            """{"t":"jetpacs.design_scope","tokens":{},"styles":{},"children":[
              {"t":"scaffold",
               "top_bar":{"t":"row","children":[{"t":"text","text":"Hub"}]},
               "body":{"t":"column","children":[
                 {"t":"empty_state","icon":"code","title":"Elisp REPL",
                  "caption":"Results appear here.","weight":1},
                 {"t":"divider"},
                 {"t":"row","padding":8,"children":[
                   {"t":"editor","id":"hub-eval","document":"scratch.el",
                    "syntax":"elisp","complete":true,"chromeless":true,"weight":1},
                   {"t":"icon_button","icon":"send","on_tap":{"action":"hub.eval"},
                    "content_description":"Eval","variant":"filled"}]}]}}]}""",
        )
        compose.setContent {
            MaterialTheme {
                RenderNode(
                    node,
                    "app:test",
                    host,
                    configuration = CompanionRenderer.installation(true).composeConfiguration,
                )
            }
        }
        // The weighted empty state keeps the height the editor row does not
        // need, and the editor's row weight leaves the button its target.
        compose.onNodeWithText("Elisp REPL").assertIsDisplayed()
        compose.onNodeWithText("Results appear here.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Eval")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
        val editor = compose.onNodeWithContentDescription("editor")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Elisp REPL").fetchSemanticsNode().boundsInRoot
        assertTrue("editor $editor should sit below the title $title", editor.top > title.bottom)
    }
}

private class InertHost : MaterialRendererHost {
    override val inputDisplays =
        MutableStateFlow<Map<Pair<String, String>, InputDisplay>>(emptyMap())
    override val maxFieldBytes = 65_536
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
    override val variantSelections: StateFlow<Map<Pair<String, String>, String>> =
        MutableStateFlow(emptyMap())
    override val retainedPresentationIncarnations: StateFlow<Map<Pair<String, String>, Long>> =
        MutableStateFlow(emptyMap())

    override fun dispatch(
        request: RendererActionRequest,
        onOutcome: (RendererActionOutcome) -> Unit,
    ): ActionHandoff = ActionHandoff.HandedOff
    override fun publishState(surface: String, id: String, value: JsonElement?, caret: Int?) = Unit
    override fun dialogDefaults(dialogId: String): JsonObject? = null
    override fun submitDialog(
        dialogId: String,
        value: JsonElement?,
        fields: JsonObject,
        secret: com.calebc42.ebp.renderer.model.RendererVolatileSecret?,
        onOutcome: (RendererActionOutcome) -> Unit,
    ) = ActionHandoff.HandedOff
    override fun dismissDialog(dialogId: String) = Unit
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
    ) = Unit
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
    override fun pieMenuSelect(menuId: String, categoryIndex: Int, itemIndex: Int?) = Unit
    override fun pieMenuDismiss(menuId: String) = Unit
}
