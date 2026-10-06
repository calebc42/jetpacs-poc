// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3.testing

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Material renderer host that records every call instead of reaching a
 * transport or a store. It replaces the inert hosts the instrumented suites
 * hand-rolled: every outbound call lands in a list, every outcome callback is
 * kept so a test can resolve it, and every flow the renderer observes is a
 * [MutableStateFlow] the test may set.
 */
class RecordingMaterialHost(
    override val maxFieldBytes: Int = 65_536,
    override val maxEditorBytes: Int = 262_144,
) : MaterialRendererHost {
    /** One `dispatch` call with the outcome callback the engine would invoke. */
    data class Dispatch(
        val request: RendererActionRequest,
        val onOutcome: (RendererActionOutcome) -> Unit,
    ) {
        /** The descriptor's `action` name, or `builtin`, or an empty string. */
        val name: String
            get() = (request.descriptor["action"] as? JsonPrimitive)?.content
                ?: (request.descriptor["builtin"] as? JsonPrimitive)?.content
                ?: ""
    }

    /** One `publishState` call. */
    data class Published(val surface: String, val id: String, val value: JsonElement?, val caret: Int?)

    /** One `submitDialog` call. */
    data class DialogSubmission(
        val dialogId: String,
        val value: JsonElement?,
        val fields: JsonObject,
        val secret: RendererVolatileSecret?,
        val onOutcome: (RendererActionOutcome) -> Unit,
    )

    /** One `publishEditorEdit` call. */
    data class EditorEdit(
        val document: String,
        val editorId: String,
        val start: ScalarPos,
        val deletedScalars: Int,
        val inserted: String,
        val base: String,
    )

    /** One `dispatchEditorCommand` call. */
    data class EditorCommand(
        val surface: String,
        val document: String,
        val editorId: String,
        val command: String,
        val cursorUtf16: Int,
        val selectionStartUtf16: Int,
        val selectionEndUtf16: Int,
    )

    override val inputDisplays = MutableStateFlow<Map<Pair<String, String>, InputDisplay>>(emptyMap())
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
    override var completionNarrowing: CompletionNarrowing = CompletionNarrowing.STRICT
    override val variantSelections = MutableStateFlow<Map<Pair<String, String>, String>>(emptyMap())
    override val retainedPresentationIncarnations =
        MutableStateFlow<Map<Pair<String, String>, Long>>(emptyMap())

    val dispatched = mutableListOf<Dispatch>()
    val published = mutableListOf<Published>()
    val dialogSubmissions = mutableListOf<DialogSubmission>()
    val dismissedDialogs = mutableListOf<String>()
    val editorEdits = mutableListOf<EditorEdit>()
    val editorCommands = mutableListOf<EditorCommand>()
    /** `requestEditorCompletion` calls as (document, editorId). */
    val completionRequests = mutableListOf<Pair<String, String>>()
    /** `pieMenuSelect` calls as (menuId, categoryIndex, itemIndex). */
    val pieMenuSelections = mutableListOf<Triple<String, Int, Int?>>()
    val pieMenuDismissals = mutableListOf<String>()

    /** Every host call in order, as `action:<name>`, `state:<id>`, `dialog:<id>` or `dismiss:<id>`. */
    val events = mutableListOf<String>()

    /** What [dialogDefaults] answers, by dialog id. */
    val dialogDefaults = mutableMapOf<String, JsonObject>()

    /** What [dispatch] and [submitDialog] report back to the renderer. */
    var handoff: ActionHandoff = ActionHandoff.HandedOff

    /** Delivered to every `publishEditorEdit` callback when non-null. */
    var nextEditOutcome: EditorEditOutcome? = null

    override fun dispatch(
        request: RendererActionRequest,
        onOutcome: (RendererActionOutcome) -> Unit,
    ): ActionHandoff {
        val dispatch = Dispatch(request, onOutcome)
        dispatched += dispatch
        events += "action:${dispatch.name}"
        return handoff
    }

    override fun publishState(surface: String, id: String, value: JsonElement?, caret: Int?) {
        published += Published(surface, id, value, caret)
        events += "state:$id"
    }

    override fun dialogDefaults(dialogId: String): JsonObject? = dialogDefaults[dialogId]

    override fun submitDialog(
        dialogId: String,
        value: JsonElement?,
        fields: JsonObject,
        secret: RendererVolatileSecret?,
        onOutcome: (RendererActionOutcome) -> Unit,
    ): ActionHandoff {
        dialogSubmissions += DialogSubmission(dialogId, value, fields, secret, onOutcome)
        events += "dialog:$dialogId"
        return handoff
    }

    override fun dismissDialog(dialogId: String) {
        dismissedDialogs += dialogId
        events += "dismiss:$dialogId"
    }

    override fun requestEditorCompletion(document: String, editorId: String) {
        completionRequests += document to editorId
    }

    override fun selectEditorCompletion(document: String, editorId: String, label: String, insert: String) = Unit

    override fun requestCandidateDocument(document: String, editorId: String, index: Int, epoch: Long) = Unit

    override fun publishEditorEdit(
        document: String,
        editorId: String,
        start: ScalarPos,
        deletedScalars: Int,
        inserted: String,
        base: String,
        onOutcome: (EditorEditOutcome) -> Unit,
    ) {
        editorEdits += EditorEdit(document, editorId, start, deletedScalars, inserted, base)
        nextEditOutcome?.let(onOutcome)
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
        editorCommands += EditorCommand(
            surface, document, editorId, command, cursorUtf16, selectionStartUtf16, selectionEndUtf16,
        )
    }

    override fun pieMenuSelect(menuId: String, categoryIndex: Int, itemIndex: Int?) {
        pieMenuSelections += Triple(menuId, categoryIndex, itemIndex)
    }

    override fun pieMenuDismiss(menuId: String) {
        pieMenuDismissals += menuId
    }

    /** Forget every recorded call; flows and defaults keep their values. */
    fun clear() {
        dispatched.clear()
        published.clear()
        dialogSubmissions.clear()
        dismissedDialogs.clear()
        editorEdits.clear()
        editorCommands.clear()
        completionRequests.clear()
        pieMenuSelections.clear()
        pieMenuDismissals.clear()
        events.clear()
    }
}
