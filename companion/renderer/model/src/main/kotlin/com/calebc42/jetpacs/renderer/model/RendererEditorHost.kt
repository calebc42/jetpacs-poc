// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.ScalarPos
import kotlinx.coroutines.flow.StateFlow

/**
 * Toolkit-neutral synchronized-editor operations exposed to Compose renderers.
 *
 * The host owns session state, scalar conversion at the wire boundary,
 * sequencing, and delivery. A visual renderer owns only its `TextFieldState`
 * and reports one occurrence-time edit expressed against [base].
 */
interface RendererEditorHost {
    /** Negotiated ceiling for one JCS-encoded synchronized editor document. */
    val maxEditorBytes: Int

    /** Exact connection readiness used to make synchronized fields read-only. */
    val editorConnectionPhase: StateFlow<EditorConnectionPhase>

    /** Latest synchronized text/selection authority by document and editor ID. */
    val editorMirrors: StateFlow<Map<Pair<String, String>, EditorMirror>>

    /** Latest independently sequenced annotation batches for each editor. */
    val editorAnnotations: StateFlow<Map<Pair<String, String>, EditorAnnotationState>>

    /** Completion rows paired with the exact session position requested. */
    val completionOffers: StateFlow<Map<Pair<String, String>, CompletionOffer>>

    /** Receiver-local narrowing state corresponding to each completion offer. */
    val completionOfferViews: StateFlow<Map<Pair<String, String>, CompletionOfferView>>

    /** Lazily fetched candidate documentation that remains tied to its epoch. */
    val candidateDocuments: StateFlow<Map<Pair<String, String>, CandidateDocument>>

    /** Receiver-owned completion narrowing policy; never a wire preference. */
    var completionNarrowing: CompletionNarrowing

    /** Request completion at the host's accepted caret for this editor. */
    fun requestEditorCompletion(document: String, editorId: String)

    /** Select one exact candidate through the host's stale-offer gates. */
    fun selectEditorCompletion(
        document: String,
        editorId: String,
        label: String,
        insert: String,
    )

    /** Fetch documentation for one row of the specified offer [epoch]. */
    fun requestCandidateDocument(
        document: String,
        editorId: String,
        index: Int,
        epoch: Long,
    )

    /** Publish one local splice expressed against immutable [base] text. */
    fun publishEditorEdit(
        document: String,
        editorId: String,
        start: ScalarPos,
        deletedScalars: Int,
        inserted: String,
        base: String,
        onOutcome: (EditorEditOutcome) -> Unit = {},
    )

    /** Report whether the platform currently owns an IME composition. */
    fun publishEditorComposition(document: String, editorId: String, active: Boolean) {}

    /** Publish current UTF-16 caret/selection positions for host conversion. */
    fun publishEditorCaret(
        document: String,
        editorId: String,
        cursorUtf16: Int,
        selectionStartUtf16: Int,
        selectionEndUtf16: Int,
    )

    /** Dispatch one admitted editor command with its occurrence-time selection. */
    fun dispatchEditorCommand(
        surface: String,
        document: String,
        editorId: String,
        command: String,
        cursorUtf16: Int,
        selectionStartUtf16: Int,
        selectionEndUtf16: Int,
    )
}
