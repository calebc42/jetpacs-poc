// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion

import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.ebp.wire.ScalarPos
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Runtime operations required by the Material renderer.
 *
 * The renderer owns presentation; the application/bridge owns transport,
 * durability, and EBP decisions. Keeping this interface in the renderer
 * module prevents presentation code from depending on the concrete socket
 * host while preserving that ownership boundary.
 */
interface MaterialRendererBridge {
    val inputDisplays: StateFlow<Map<Pair<String, String>, InputDisplay>>
    val variantSelections: StateFlow<Map<Pair<String, String>, String>>
    val retainedPresentationIncarnations: StateFlow<Map<Pair<String, String>, Long>>
    val editorMirrors: StateFlow<Map<Pair<String, String>, EditorMirror>>
    val editorAnnotations: StateFlow<Map<Pair<String, String>, EditorAnnotationState>>
    val completionOffers: StateFlow<Map<Pair<String, String>, CompletionOffer>>
    val offerViews: StateFlow<Map<Pair<String, String>, CompletionOfferView>>
    val candidateDocs: StateFlow<Map<Pair<String, String>, CandidateDoc>>
    var completionNarrowing: CompletionNarrowing

    fun action(surface: String, descriptor: JsonObject?, value: JsonElement? = null)
    fun dialogAction(
        dialogId: String,
        descriptor: JsonObject?,
        value: JsonElement?,
        fields: JsonObject?,
    )
    fun actionInjecting(
        surface: String,
        descriptor: JsonObject?,
        injected: JsonObject,
        value: JsonElement? = null,
    )
    fun actionWithFields(surface: String, descriptor: JsonObject?, fields: JsonObject)
    fun state(surface: String, id: String, value: JsonElement?, caret: Int? = null)
    fun editorComplete(document: String, editorId: String)
    fun editorSelectCompletion(document: String, editorId: String, label: String, insert: String)
    fun editorCandidateDoc(document: String, editorId: String, index: Int, epoch: Long)
    fun editorEdit(
        document: String,
        editorId: String,
        start: ScalarPos,
        del: Int,
        text: String,
        base: String,
    )
    fun editorCaret(document: String, editorId: String, cursor: Int, selStart: Int, selEnd: Int)
    fun editorCommand(
        surface: String,
        document: String,
        editorId: String,
        command: String,
        cursor: Int,
        selStart: Int,
        selEnd: Int,
    )
    fun dialogSubmit(dialogId: String, value: JsonElement?, fields: JsonObject)
    fun dialogDefaults(dialogId: String): JsonObject?
    fun dialogDismiss(dialogId: String)
    fun pieMenuSelect(menuId: String, categoryIndex: Int, itemIndex: Int?)
    fun pieMenuDismiss(menuId: String)
}
