// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.calebc42.ebp.wire.EditorSession
import com.calebc42.ebp.wire.ScalarPos
import com.calebc42.ebp.wire.normalizeTextInput
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.EditorMirror
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import com.calebc42.jetpacs.renderer.model.RendererEditorHost
import com.calebc42.jetpacs.renderer.model.RendererVolatileSecret
import com.calebc42.jetpacs.renderer.model.Utf16TextSplice
import com.calebc42.jetpacs.renderer.model.utf16TextSplice
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One ordinary renderer action funnel supplied to a shared editing controller. */
fun interface EditingActionDispatcher {
    fun dispatch(
        descriptor: JsonObject,
        value: JsonElement?,
        secret: RendererVolatileSecret?,
        sourceId: String?,
        onOutcome: (RendererActionOutcome) -> Unit,
    ): ActionHandoff
}

/** Behavior-bearing members of one canonical EBP `text_input`. */
data class TextInputControllerConfig(
    val id: String,
    val password: Boolean,
    val singleLine: Boolean,
    val filter: String?,
    val maxLengthScalars: Long?,
    val clearOnSubmit: Boolean,
    val onChange: JsonObject?,
    val onSubmit: JsonObject?,
    val publishPasswordLocally: Boolean,
)

/**
 * Shared state and protocol behavior for a canonical EBP `text_input`.
 *
 * Presentation code supplies slots, colors, and decoration only. Every user
 * transaction passes through [inputTransformation], so keyboard, IME, paste,
 * drop, and autofill share normalization, byte bounds, state-before-action
 * ordering, and password exclusion.
 */
@Stable
class TextInputController internal constructor(
    val state: TextFieldState,
    config: TextInputControllerConfig,
    maxFieldBytes: Int,
    publishState: (String) -> Unit,
    actionDispatcher: EditingActionDispatcher,
) {
    private var config = config
    private var maxFieldBytes = maxFieldBytes
    private var publishState = publishState
    private var actionDispatcher = actionDispatcher
    private var editGeneration = 0L
    private var disposed = false
    private var activeSecret: RendererVolatileSecret? = null

    /** True only while a secret-bearing occurrence awaits its terminal result. */
    var passwordSubmissionPending by mutableStateOf(false)
        private set

    /** Atomic normalization and publication for every platform edit source. */
    val inputTransformation: InputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            if (config.password && passwordSubmissionPending) {
                revertAllChanges()
                return
            }
            val normalized = normalizeTextInput(
                asCharSequence().toString(),
                config.singleLine,
                config.filter,
                config.maxLengthScalars,
            )
            if (normalized != asCharSequence().toString()) {
                replace(0, length, normalized)
            }
            val committed = asCharSequence().toString()
            if (EditorSession.jcsUtf8Bytes(committed) > maxFieldBytes) {
                revertAllChanges()
                return
            }
            if (committed == originalText.toString()) return
            recordUserEdit(committed)
        }
    }

    internal fun update(
        config: TextInputControllerConfig,
        maxFieldBytes: Int,
        publishState: (String) -> Unit,
        actionDispatcher: EditingActionDispatcher,
    ) {
        this.config = config
        this.maxFieldBytes = maxFieldBytes
        this.publishState = publishState
        this.actionDispatcher = actionDispatcher
    }

    /** Record the already-normalized result of one platform edit transaction. */
    internal fun recordUserEdit(text: String) {
        editGeneration++
        if (!config.password || config.publishPasswordLocally) publishState(text)
        if (!config.password) {
            config.onChange?.let { descriptor ->
                actionDispatcher.dispatch(
                    descriptor,
                    JsonPrimitive(text),
                    null,
                    config.id,
                ) {}
            }
        }
    }

    /**
     * Admit one legacy value-based Foundation transaction.
     *
     * Compose's value-based API is used only for masks because it accepts an
     * explicit linear offset mapping. The canonical [state] is updated before
     * publication, preserving the same state-before-action ordering as
     * [inputTransformation]. A null result means the entire edit was refused.
     */
    internal fun applyLegacyEdit(proposed: TextFieldValue): TextFieldValue? {
        if (config.password && passwordSubmissionPending) return null
        val normalized = normalizeLegacyTextFieldValue(
            proposed,
            config.singleLine,
            config.filter,
            config.maxLengthScalars,
        )
        if (EditorSession.jcsUtf8Bytes(normalized.text) > maxFieldBytes) return null
        val changed = normalized.text != state.text.toString()
        state.edit {
            if (changed) replace(0, length, normalized.text)
            selection = normalized.selection
        }
        if (changed) recordUserEdit(normalized.text)
        return normalized
    }

    /**
     * Submit the current value through the ordinary host path.
     *
     * Non-secret clearing waits for safe admission and is generation-guarded;
     * a newer user edit therefore always wins. Secret text is blocked from a
     * second submission and erased for every terminal outcome.
     */
    fun submit(): ActionHandoff {
        val descriptor = config.onSubmit ?: return ActionHandoff.Ignored
        if (config.password && passwordSubmissionPending) return ActionHandoff.Ignored
        val submitted = state.text.toString()
        if (EditorSession.jcsUtf8Bytes(submitted) > maxFieldBytes) {
            return ActionHandoff.Ignored
        }
        val submittedGeneration = editGeneration
        if (config.password) passwordSubmissionPending = true
        val secret = if (config.password) {
            RendererVolatileSecret(
                fields = buildJsonObject { put(config.id, submitted) },
                secretIds = setOf(config.id),
                eraseNativeState = ::eraseVolatileState,
            ).also { activeSecret = it }
        } else null
        val handoff = actionDispatcher.dispatch(
            descriptor,
            if (config.password) null else JsonPrimitive(submitted),
            secret,
            config.id,
        ) { outcome ->
            if (config.password) {
                secret!!.erase()
                if (activeSecret === secret) activeSecret = null
                passwordSubmissionPending = false
            } else if (config.clearOnSubmit && outcome.isSafeAdmission() &&
                submittedGeneration == editGeneration && !disposed
            ) {
                state.setTextAndPlaceCursorAtEnd("")
                publishState("")
            }
        }
        if (handoff == ActionHandoff.Ignored && config.password) {
            secret!!.erase()
            if (activeSecret === secret) activeSecret = null
            passwordSubmissionPending = false
        }
        return handoff
    }

    /** Erase volatile secret state when its containing presentation ends. */
    fun dispose() {
        disposed = true
        val submittedSecret = activeSecret
        activeSecret = null
        if (submittedSecret != null) submittedSecret.erase()
        else if (config.password) eraseVolatileState()
        passwordSubmissionPending = false
    }

    /**
     * Clear every native password owner; dialog hosts register this callback.
     *
     * Compose stages user edits for undo separately from [TextFieldState.text].
     * Purging both sides of the text clear prevents the submitted password and
     * the clear transaction from remaining reachable through undo history.
     */
    internal fun eraseVolatileState() {
        state.undoState.clearHistory()
        if (state.text.isNotEmpty()) state.setTextAndPlaceCursorAtEnd("")
        state.undoState.clearHistory()
        if (config.publishPasswordLocally) publishState("")
    }
}

/** Remember a saveable normal field or an explicitly non-saveable secret field. */
@Composable
fun rememberTextInputController(
    presentationEpoch: Long,
    initialText: String,
    initialSelection: TextRange,
    config: TextInputControllerConfig,
    maxFieldBytes: Int,
    publishState: (String) -> Unit,
    actionDispatcher: EditingActionDispatcher,
): TextInputController = key(presentationEpoch, config.password) {
    val state = if (config.password) {
        remember { TextFieldState() }
    } else {
        rememberTextFieldState(initialText, initialSelection)
    }
    val controller = remember(state) {
        TextInputController(
            state,
            config,
            maxFieldBytes,
            publishState,
            actionDispatcher,
        )
    }
    SideEffect {
        controller.update(config, maxFieldBytes, publishState, actionDispatcher)
    }
    DisposableEffect(controller) { onDispose(controller::dispose) }
    controller
}

/** Behavior-bearing members of one canonical EBP `editor`. */
data class EditorControllerConfig(
    val surface: String,
    val id: String,
    val document: String,
    val singleLine: Boolean,
    val publishState: Boolean,
    val maxFieldBytes: Int,
    val maxEditorBytes: Int,
)

/** Immutable selection snapshot used by presentation-owned toolbar chrome. */
data class EditorValueSnapshot(
    val text: String,
    val selectionStartUtf16: Int,
    val selectionEndUtf16: Int,
)

/**
 * Shared state and reconciliation for local and synchronized EBP editors.
 *
 * User IME transactions are reduced from `TextFieldBuffer.changes` to one
 * enclosing splice. Programmatic mirror adoption edits [state] directly and
 * therefore cannot echo through [inputTransformation].
 */
@Stable
class EditorController internal constructor(
    val state: TextFieldState,
    config: EditorControllerConfig,
    publishLocalState: (String) -> Unit,
    editorHost: RendererEditorHost,
    actionDispatcher: EditingActionDispatcher,
) {
    private var config = config
    private var publishLocalState = publishLocalState
    private var editorHost = editorHost
    private var actionDispatcher = actionDispatcher

    /** One atomic input transaction, preserving the platform change list. */
    val inputTransformation: InputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            if (config.singleLine) removeLineFeeds()
            val next = asCharSequence().toString()
            if (exceedsByteLimit(next)) {
                revertAllChanges()
                return
            }
            val splice = enclosingUtf16Splice() ?: return
            publishEdit(originalText.toString(), next, splice)
        }
    }

    internal fun update(
        config: EditorControllerConfig,
        publishLocalState: (String) -> Unit,
        editorHost: RendererEditorHost,
        actionDispatcher: EditingActionDispatcher,
    ) {
        this.config = config
        this.publishLocalState = publishLocalState
        this.editorHost = editorHost
        this.actionDispatcher = actionDispatcher
    }

    /** Current logical text and UTF-16 selection for renderer-owned chrome. */
    fun snapshot(): EditorValueSnapshot = EditorValueSnapshot(
        state.text.toString(),
        state.selection.start,
        state.selection.end,
    )

    /** Apply a toolbar/snippet edit as one user occurrence. */
    fun applyPresentationEdit(
        proposedText: String,
        selectionStartUtf16: Int,
        selectionEndUtf16: Int,
    ) {
        val normalized = if (config.singleLine) proposedText.replace("\n", "")
        else proposedText
        if (exceedsByteLimit(normalized)) return
        val base = state.text.toString()
        utf16TextSplice(base, normalized)?.let { publishEdit(base, normalized, it) }
        state.edit {
            replace(0, length, normalized)
            selection = TextRange(
                selectionStartUtf16.coerceIn(0, normalized.length),
                selectionEndUtf16.coerceIn(0, normalized.length),
            )
        }
    }

    /** Adopt a remote authority publication without producing a local delta. */
    fun adoptMirror(mirror: EditorMirror) {
        state.edit {
            replace(0, length, mirror.text)
            selection = if (mirror.selectionStartUtf16 != mirror.selectionEndUtf16) {
                TextRange(
                    mirror.selectionStartUtf16.coerceIn(0, mirror.text.length),
                    mirror.selectionEndUtf16.coerceIn(0, mirror.text.length),
                )
            } else {
                TextRange(mirror.cursorUtf16.coerceIn(0, mirror.text.length))
            }
        }
    }

    /** Dispatch `on_save` or `on_enter` with the live full editor value. */
    fun dispatchValueAction(descriptor: JsonObject?): ActionHandoff =
        descriptor?.let {
            actionDispatcher.dispatch(
                it,
                JsonPrimitive(state.text.toString()),
                null,
                config.id,
            ) {}
        } ?: ActionHandoff.Ignored

    private fun publishEdit(base: String, next: String, splice: Utf16TextSplice) {
        if (config.document.isEmpty()) {
            if (config.publishState) publishLocalState(next)
            return
        }
        val start = splice.start.coerceIn(0, base.length)
        val deletedEnd = (start + splice.deleted).coerceIn(start, base.length)
        editorHost.publishEditorEdit(
            config.document,
            config.id,
            ScalarPos(base.codePointCount(0, start)),
            base.codePointCount(start, deletedEnd),
            splice.inserted,
            base,
        )
    }

    private fun exceedsByteLimit(text: String): Boolean {
        val limit = if (config.document.isEmpty()) {
            config.maxFieldBytes
        } else {
            config.maxEditorBytes
        }
        return EditorSession.jcsUtf8Bytes(text) > limit
    }
}

/** Remember one saveable editor controller and own mirror/caret/completion effects. */
@Composable
fun rememberEditorController(
    presentationEpoch: Long,
    initialText: String,
    initialSelection: TextRange,
    config: EditorControllerConfig,
    mirror: EditorMirror?,
    requestCompletion: Boolean,
    enabled: Boolean,
    readOnly: Boolean,
    publishLocalState: (String) -> Unit,
    editorHost: RendererEditorHost,
    actionDispatcher: EditingActionDispatcher,
): EditorController = key(presentationEpoch) {
    val state = rememberTextFieldState(initialText, initialSelection)
    val controller = remember(state) {
        EditorController(
            state,
            config,
            publishLocalState,
            editorHost,
            actionDispatcher,
        )
    }
    SideEffect {
        controller.update(config, publishLocalState, editorHost, actionDispatcher)
    }
    androidx.compose.runtime.LaunchedEffect(controller, mirror?.epoch) {
        mirror?.let(controller::adoptMirror)
    }
    val selection = state.selection
    val text = state.text.toString()
    if (config.document.isNotEmpty()) {
        androidx.compose.runtime.LaunchedEffect(
            controller,
            config.document,
            config.id,
            selection,
        ) {
            delay(90)
            editorHost.publishEditorCaret(
                config.document,
                config.id,
                selection.end,
                selection.start,
                selection.end,
            )
        }
    }
    if (requestCompletion && config.document.isNotEmpty() && enabled && !readOnly) {
        androidx.compose.runtime.LaunchedEffect(
            controller,
            config.document,
            config.id,
            text,
        ) {
            if (text.isEmpty()) return@LaunchedEffect
            delay(180)
            editorHost.requestEditorCompletion(config.document, config.id)
        }
    }
    controller
}

/** One enclosing splice derived from this transaction's `ChangeList`. */
internal fun TextFieldBuffer.enclosingUtf16Splice(): Utf16TextSplice? {
    if (changes.changeCount == 0) return null
    var originalStart = Int.MAX_VALUE
    var originalEnd = 0
    var currentStart = Int.MAX_VALUE
    var currentEnd = 0
    for (index in 0 until changes.changeCount) {
        val original = changes.getOriginalRange(index)
        val current = changes.getRange(index)
        originalStart = minOf(originalStart, original.min)
        originalEnd = maxOf(originalEnd, original.max)
        currentStart = minOf(currentStart, current.min)
        currentEnd = maxOf(currentEnd, current.max)
    }
    val base = originalText.toString()
    val current = asCharSequence().toString()
    // The earliest changed boundary has the same coordinate in the original
    // and current buffers; later ranges may shift after earlier edits. Widen
    // defensively instead of rescanning both complete strings on each IME
    // transaction if a platform implementation reports otherwise.
    val enclosingStart = minOf(originalStart, currentStart)
    val safeStart = enclosingStart.coerceIn(0, base.length)
    val safeOriginalEnd = originalEnd.coerceIn(safeStart, base.length)
    val safeCurrentEnd = currentEnd.coerceIn(safeStart, current.length)
    return Utf16TextSplice(
        safeStart,
        safeOriginalEnd - safeStart,
        current.substring(safeStart, safeCurrentEnd),
    )
}

private fun TextFieldBuffer.removeLineFeeds() {
    for (index in length - 1 downTo 0) {
        if (charAt(index) == '\n') delete(index, index + 1)
    }
}

private fun RendererActionOutcome.isSafeAdmission(): Boolean =
    this is RendererActionOutcome.SafelyAdmitted ||
        this is RendererActionOutcome.LocallyCompleted
