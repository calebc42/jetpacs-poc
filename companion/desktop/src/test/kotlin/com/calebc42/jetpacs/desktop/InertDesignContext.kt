// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.compose.RevealSwipeDirection
import com.calebc42.ebp.renderer.model.ActionHandoff
import com.calebc42.ebp.renderer.model.CandidateDocument
import com.calebc42.ebp.renderer.model.CompletionOffer
import com.calebc42.ebp.renderer.model.EditorAnnotationState
import com.calebc42.ebp.renderer.model.EditorConnectionPhase
import com.calebc42.ebp.renderer.model.EditorEditOutcome
import com.calebc42.ebp.renderer.model.EditorMirror
import com.calebc42.ebp.renderer.model.RendererActionOutcome
import com.calebc42.ebp.renderer.model.RendererEditorHost
import com.calebc42.ebp.renderer.model.RendererVolatileSecret
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.ScalarPos
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignTextRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A render context with no Emacs behind it: every action, state publication
 * and editor call is dropped. It composes `jetpacs.design` nodes and their
 * `text` children through the public renderer entry points, which is enough
 * for the spike's design sample and the desktop font evidence (D2). The D5
 * composition root replaces it with the Companion's own context.
 */
internal class InertDesignContext : ComposeExtensionRenderContext, RendererEditorHost {
    override val extensionId = JETPACS_DESIGN_EXTENSION
    override val surface = "app:spike"
    override val path = "root"
    override val inDialog = false
    override val maxFieldBytes = 65_536
    override val editorHost: RendererEditorHost get() = this
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

    override fun dispatchAction(
        descriptor: JsonObject?,
        value: JsonElement?,
        secret: RendererVolatileSecret?,
        sourceId: String?,
        onOutcome: (RendererActionOutcome) -> Unit,
    ): ActionHandoff = ActionHandoff.HandedOff

    override fun swipeAction(descriptor: JsonObject?, direction: RevealSwipeDirection) = Unit
    override fun state(id: String, value: JsonElement?, volatileSecret: Boolean) = Unit
    override fun storeValue(id: String): JsonElement? = null
    override fun epochOf(id: String): Long = 0
    override fun requestEditorCompletion(document: String, editorId: String) = Unit
    override fun selectEditorCompletion(
        document: String, editorId: String, label: String, insert: String,
    ) = Unit
    override fun requestCandidateDocument(
        document: String, editorId: String, index: Int, epoch: Long,
    ) = Unit
    override fun publishEditorEdit(
        document: String, editorId: String, start: ScalarPos, deletedScalars: Int,
        inserted: String, base: String, onOutcome: (EditorEditOutcome) -> Unit,
    ) = Unit
    override fun publishEditorCaret(
        document: String, editorId: String, cursorUtf16: Int,
        selectionStartUtf16: Int, selectionEndUtf16: Int,
    ) = Unit
    override fun dispatchEditorCommand(
        surface: String, document: String, editorId: String, command: String,
        cursorUtf16: Int, selectionStartUtf16: Int, selectionEndUtf16: Int,
    ) = Unit

    @Composable
    override fun renderChild(child: JsonObject, index: Int, modifier: Modifier) {
        when ((child["t"] as? JsonPrimitive)?.content) {
            in JetpacsDesignRenderer.nodeTypes -> JetpacsDesignRenderer.render(child, this, modifier)
            "text" -> JetpacsDesignTextRenderer.render(child, this, modifier)
            else -> Unit
        }
    }

    @Composable
    override fun renderScopedChild(child: JsonObject, index: Int, modifier: Modifier) =
        renderChild(child, index, modifier)
}

/** A `jetpacs.design_scope` whose body text is IBM Plex Serif. */
internal fun plexSerifScope(text: String): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(
        """
        {"t":"jetpacs.design_scope","tokens":{},
         "styles":{"plex":{"properties":{"font_family":{"kind":"font-family","value":"plex-serif"}},"rules":[]}},
         "component_styles":[{"slot":"text.body","styles":["plex"]}],
         "children":[{"t":"text","text":${JsonPrimitive(text)}}]}
        """,
    ) as JsonObject
