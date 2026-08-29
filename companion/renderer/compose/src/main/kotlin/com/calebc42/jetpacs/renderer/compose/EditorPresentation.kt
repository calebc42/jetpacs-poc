// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.EditorConnectionPhase
import com.calebc42.jetpacs.renderer.model.EditorSyncPhase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Accepted, toolkit-neutral presentation members of one EBP `editor`. */
data class EditorPresentation(
    val id: String,
    val document: String?,
    val enabled: Boolean,
    val readOnly: Boolean,
    val singleLine: Boolean,
    val syntax: String?,
    val lineNumbers: Boolean,
    val complete: Boolean,
    val chromeless: Boolean,
    val publishState: Boolean,
    val autofocus: Boolean,
    val toolbar: JsonArray?,
    val onSave: JsonObject?,
    val onEnter: JsonObject?,
    val keyboardOptions: KeyboardOptions,
    val lineLimits: TextFieldLineLimits,
)

/**
 * Shared behavioral binding consumed by canonical `editor` presentations.
 *
 * Renderers own decoration and toolbar layout. This binding owns parsing,
 * retained-draft seeding, focus identity, byte limits, state publication, and
 * action value injection through the shared [EditorController].
 */
@Stable
class EditorBinding internal constructor(
    val presentation: EditorPresentation,
    val controller: EditorController,
    val focusRequester: FocusRequester,
) {
    /** Effective read-only state, including synchronized lifecycle gates. */
    val effectiveReadOnly: Boolean
        get() = presentation.readOnly || !controller.synchronizedFieldWritable

    /** Whether the field itself may accept a text transaction. */
    val fieldEditable: Boolean
        get() = presentation.enabled && !effectiveReadOnly

    /** Whether save, Enter, completion, and toolbar actions are allowed. */
    val actionsEnabled: Boolean
        get() = presentation.enabled && !presentation.readOnly &&
            controller.synchronizedActionsReady

    /** Compatibility name for existing presentation code. */
    val interactive: Boolean get() = actionsEnabled

    /** Explicit synchronized lifecycle; null for an ordinary local editor. */
    val syncPhase: EditorSyncPhase? get() = controller.syncPhase

    /**
     * Attach live editability semantics and the identity-scoped focus requester.
     *
     * The runtime semantics must precede the authored EBP modifier so a
     * synchronized lifecycle transition can override its static `read_only`
     * projection without clearing the field's text and selection actions.
     */
    fun fieldModifier(modifier: Modifier, errorMessage: String? = null): Modifier = Modifier
        .semantics {
            isEditable = fieldEditable
            errorMessage?.takeIf { it.isNotEmpty() }?.let(::error)
        }
        .then(modifier)
        .focusRequester(focusRequester)

    /** Dispatch `on_save` with the occurrence-time full editor value. */
    fun save(): ActionHandoff = if (actionsEnabled) {
        controller.dispatchValueAction(presentation.onSave)
    } else {
        ActionHandoff.Ignored
    }

    /** Dispatch `on_enter` with the occurrence-time full editor value. */
    fun enter(): ActionHandoff = if (actionsEnabled) {
        controller.dispatchValueAction(presentation.onEnter)
    } else {
        ActionHandoff.Ignored
    }
}

/**
 * Bind one admitted canonical editor to shared state and protocol behavior.
 *
 * A local editor uses `max_field_bytes` and may reconcile an explicitly
 * published retained draft. A synchronized editor uses `max_editor_bytes` and
 * adopts only the host mirror. [requestCompletion] lets a presentation defer
 * the candidate UI without starting requests it cannot display.
 */
@Composable
fun rememberEditorBinding(
    node: JsonObject,
    context: ComposeNodeRenderContext,
    requestCompletion: Boolean = true,
): EditorBinding {
    val presentation = editorPresentationOf(node)
    val id = presentation.id
    val stored = if (presentation.document == null && presentation.publishState) {
        (context.storeValue(id) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    } else {
        null
    }
    val initialText = stored ?: node.editorString("value").orEmpty()
    val mirrors by context.editorHost.editorMirrors.collectAsState()
    val connectionPhase by context.editorHost.editorConnectionPhase.collectAsState()
    val mirror = presentation.document?.let { mirrors[it to id] }
    val controller = rememberEditorController(
        presentationEpoch = context.epochOf(id),
        initialText = initialText,
        initialSelection = TextRange(initialText.length),
        config = EditorControllerConfig(
            surface = context.surface,
            id = id,
            document = presentation.document.orEmpty(),
            singleLine = presentation.singleLine,
            publishState = presentation.publishState,
            maxFieldBytes = context.maxFieldBytes,
            maxEditorBytes = context.editorHost.maxEditorBytes,
        ),
        mirror = mirror,
        connectionPhase = if (presentation.document == null) {
            EditorConnectionPhase.READY
        } else {
            connectionPhase
        },
        requestCompletion = requestCompletion && presentation.complete,
        enabled = presentation.enabled,
        readOnly = presentation.readOnly,
        publishLocalState = { context.state(id, JsonPrimitive(it)) },
        editorHost = context.editorHost,
        actionDispatcher = EditingActionDispatcher {
                descriptor, value, secret, sourceId, onOutcome ->
            context.dispatchAction(
                descriptor = descriptor,
                value = value,
                secret = secret,
                sourceId = sourceId,
                onOutcome = onOutcome,
            )
        },
    )
    val focusRequester = rememberPresentationFocusRequester(
        identity = context.path,
        autofocus = presentation.autofocus,
    )
    return remember(presentation, controller, focusRequester) {
        EditorBinding(presentation, controller, focusRequester)
    }
}

/** Pure contract projection shared by tests and every editor presentation. */
internal fun editorPresentationOf(node: JsonObject): EditorPresentation {
    val singleLine = node.editorBoolean("single_line")
    val onEnter = node.editorObject("on_enter")
    return EditorPresentation(
        id = (node.getValue("id") as JsonPrimitive).content,
        document = node.editorString("document"),
        enabled = node.editorBoolean("enabled", true),
        readOnly = node.editorBoolean("read_only"),
        singleLine = singleLine,
        syntax = node.editorString("syntax"),
        lineNumbers = node.editorBoolean("line_numbers"),
        complete = node.editorBoolean("complete"),
        chromeless = node.editorBoolean("chromeless"),
        publishState = node.editorBoolean("publish_state"),
        autofocus = node.editorBoolean("autofocus"),
        toolbar = node["toolbar"] as? JsonArray,
        onSave = node.editorObject("on_save"),
        onEnter = onEnter,
        keyboardOptions = KeyboardOptions(
            imeAction = if (onEnter == null) ImeAction.Default else ImeAction.Done,
        ),
        lineLimits = node.editorLineLimits(singleLine),
    )
}

private fun JsonObject.editorLineLimits(singleLine: Boolean): TextFieldLineLimits {
    if (singleLine) return TextFieldLineLimits.SingleLine
    return TextFieldLineLimits.MultiLine(
        minHeightInLines = editorPositiveInt("min_lines") ?: 3,
        maxHeightInLines = editorPositiveInt("max_lines") ?: Int.MAX_VALUE,
    )
}

private fun JsonObject.editorString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.editorBoolean(name: String, default: Boolean = false): Boolean =
    (get(name) as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonObject.editorPositiveInt(name: String): Int? =
    (get(name) as? JsonPrimitive)?.longOrNull
        ?.coerceIn(1L, Int.MAX_VALUE.toLong())
        ?.toInt()

private fun JsonObject.editorObject(name: String): JsonObject? = get(name) as? JsonObject
