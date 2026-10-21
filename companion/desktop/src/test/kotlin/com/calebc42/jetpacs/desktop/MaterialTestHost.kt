// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

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
import com.calebc42.jetpacs.material3.MaterialRendererHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Material renderer host with no Emacs behind it: it records what the
 * renderer dispatches and publishes and drops the rest. The desktop twin of
 * SpikeHost in the Robolectric suite, for composing Material RenderNode on
 * the JVM (D3 of docs/PLAN-desktop-companion.md).
 */
internal class MaterialTestHost : MaterialRendererHost {
    val dispatched = mutableListOf<RendererActionRequest>()
    val published = mutableListOf<Pair<String, JsonElement?>>()
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
    override fun publishState(surface: String, id: String, value: JsonElement?, caret: Int?) {
        published += id to value
    }
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
    override fun pieMenuSelect(menuId: String, categoryIndex: Int, itemIndex: Int?) = Unit
    override fun pieMenuDismiss(menuId: String) = Unit
}
