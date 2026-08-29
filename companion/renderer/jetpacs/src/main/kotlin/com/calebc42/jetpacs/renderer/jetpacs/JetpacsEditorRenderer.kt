// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.jetpacs.renderer.compose.ComposeNodeRenderContext
import com.calebc42.jetpacs.renderer.compose.rememberEditorBinding
import com.calebc42.jetpacs.renderer.model.EditorSyncPhase
import kotlinx.serialization.json.JsonObject

/** Jetpacs-scoped presentation override for canonical local and synchronized editors. */
object JetpacsEditorRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.components.editor.compose"
    override val designScope: String = JETPACS_COMPONENTS_EXTENSION
    override val nodeTypes: Set<String> = setOf("editor")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        // Completion, annotations, and editor commands remain Phase 6. The
        // shared binding still owns text synchronization, but this renderer
        // does not start a completion request it cannot yet present.
        val binding = rememberEditorBinding(node, context, requestCompletion = false)
        val presentation = binding.presentation
        val syntaxColors = JetpacsTheme.syntax
        val outputTransformation = remember(presentation.syntax, syntaxColors) {
            presentation.syntax?.takeIf { presentation.document == null }?.let {
                JetpacsSyntaxOutputTransformation(it, syntaxColors)
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presentation.toolbar?.let { toolbar ->
                JetpacsEditorToolbar(
                    items = toolbar,
                    controller = binding.controller,
                    dispatch = { context.action(it) },
                    onCommand = {},
                    enabled = binding.actionsEnabled,
                )
            }
            JetpacsEditor(
                state = binding.controller.state,
                modifier = binding.fieldModifier(modifier.fillMaxWidth()),
                enabled = presentation.enabled,
                readOnly = binding.effectiveReadOnly,
                lineNumbers = presentation.lineNumbers,
                chromeless = presentation.chromeless,
                inputTransformation = binding.controller.inputTransformation,
                outputTransformation = outputTransformation,
                keyboardOptions = presentation.keyboardOptions,
                onKeyboardAction = { binding.enter() },
                lineLimits = presentation.lineLimits,
            )
            binding.syncPhase?.let { phase ->
                JetpacsEditorSyncStatus(phase)
            }
            if (presentation.onSave != null) {
                JetpacsAction(
                    label = "Save",
                    onClick = { binding.save() },
                    enabled = binding.actionsEnabled,
                )
            }
        }
    }
}

/** Visible lifecycle copy for every synchronized state except settled READY. */
internal fun editorSyncStatusText(phase: EditorSyncPhase): String? = when (phase) {
    EditorSyncPhase.OPENING -> "Opening synchronized editor…"
    EditorSyncPhase.READY -> null
    EditorSyncPhase.COMPOSING -> "Composing text…"
    EditorSyncPhase.AWAITING_RECONCILIATION -> "Reconciling with Emacs…"
    EditorSyncPhase.STALE -> "Editor changed remotely; restoring synchronized text…"
    EditorSyncPhase.OFFLINE_READ_ONLY ->
        "Emacs is offline — synchronized editor is read-only"
    EditorSyncPhase.CLOSED -> "Synchronized editor closed"
}

@Composable
internal fun JetpacsEditorSyncStatus(phase: EditorSyncPhase) {
    val text = editorSyncStatusText(phase) ?: return
    val styleState = remember { MutableStyleState(null) }
    BasicText(
        text = text,
        style = JetpacsTheme.typography.fieldSupporting.copy(
            color = JetpacsTheme.colors.mutedContent,
        ),
        modifier = Modifier
            .styleable(styleState, JetpacsTheme.styles.editorSyncStatus)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
