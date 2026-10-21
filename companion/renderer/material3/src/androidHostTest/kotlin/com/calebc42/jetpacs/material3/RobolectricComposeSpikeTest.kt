// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calebc42.ebp.renderer.model.ActionHandoff
import com.calebc42.ebp.renderer.model.CandidateDocument
import com.calebc42.ebp.renderer.model.CompletionOffer
import com.calebc42.ebp.renderer.model.EditorAnnotationState
import com.calebc42.ebp.renderer.model.EditorConnectionPhase
import com.calebc42.ebp.renderer.model.EditorEditOutcome
import com.calebc42.ebp.renderer.model.EditorMirror
import com.calebc42.ebp.renderer.model.RendererActionOutcome
import com.calebc42.ebp.renderer.model.RendererActionRequest
import com.calebc42.ebp.renderer.model.RendererVolatileSecret
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.ebp.wire.ScalarPos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Proves the Material renderer composes on the JVM under Robolectric, so the
 * golden-corpus replay and per-component tests can run in `testDebugUnitTest`
 * without a device. The two cases are the smallest end-to-end facts the
 * headless harness relies on: a node reaches the semantics tree, and a tap
 * reaches the host through the ordinary action path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobolectricComposeSpikeTest {
    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private val host = SpikeHost()

    private fun spec(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun textNodeRendersOnTheJvm() {
        compose.setContent {
            EbpTheme(null) {
                RenderNode(spec("""{"t":"text","text":"hi"}"""), "app:test", host)
            }
        }
        compose.onNodeWithText("hi").assertIsDisplayed()
    }

    @Test
    fun buttonTapDispatchesThroughTheHost() {
        compose.setContent {
            EbpTheme(null) {
                RenderNode(
                    spec("""{"t":"button","label":"Go","on_tap":{"action":"demo.tap"}}"""),
                    "app:test",
                    host,
                )
            }
        }
        compose.onNodeWithText("Go").performClick()
        compose.runOnIdle {
            assertEquals(1, host.dispatched.size)
            assertEquals(
                "demo.tap",
                host.dispatched.single().descriptor["action"]?.jsonPrimitive?.content,
            )
        }
    }
}

/** Records dispatches and drops everything else; the JVM twin of the instrumented inert host. */
internal class SpikeHost : MaterialRendererHost {
    val dispatched = mutableListOf<RendererActionRequest>()
    override val inputDisplays = MutableStateFlow<Map<Pair<String, String>, InputDisplay>>(emptyMap())
    override val maxFieldBytes = 65_536
    override val maxEditorBytes = 65_536
    override val editorConnectionPhase = MutableStateFlow(EditorConnectionPhase.READY)
    override val editorMirrors = MutableStateFlow<Map<Pair<String, String>, EditorMirror>>(emptyMap())
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
    ): ActionHandoff {
        dispatched += request
        return ActionHandoff.HandedOff
    }
    override fun publishState(surface: String, id: String, value: JsonElement?, caret: Int?) = Unit
    override fun dialogDefaults(dialogId: String): JsonObject? = null
    override fun submitDialog(
        dialogId: String,
        value: JsonElement?,
        fields: JsonObject,
        secret: RendererVolatileSecret?,
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
