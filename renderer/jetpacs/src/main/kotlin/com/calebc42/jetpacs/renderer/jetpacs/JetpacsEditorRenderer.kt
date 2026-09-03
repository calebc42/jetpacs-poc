// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import com.calebc42.ebp.renderer.compose.rememberEditorBinding
import com.calebc42.ebp.renderer.model.EditorSyncPhase
import com.calebc42.ebp.renderer.model.currentDiagnostics
import com.calebc42.ebp.renderer.model.currentEldoc
import com.calebc42.ebp.renderer.model.diagnosticAt
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
        val binding = rememberEditorBinding(node, context)
        val presentation = binding.presentation
        val document = presentation.document
        val editorKey = document?.let { it to presentation.id }
        val annotationMap by context.editorHost.editorAnnotations.collectAsState()
        val annotations = editorKey?.let(annotationMap::get)
        val mirrors by context.editorHost.editorMirrors.collectAsState()
        val mirror = editorKey?.let(mirrors::get)
        val offers by context.editorHost.completionOffers.collectAsState()
        val offerViews by context.editorHost.completionOfferViews.collectAsState()
        val candidateDocuments by context.editorHost.candidateDocuments.collectAsState()
        val offer = editorKey?.takeIf {
            presentation.complete && binding.actionsEnabled
        }?.let(offers::get)
        val offerView = editorKey?.let(offerViews::get)
        val candidateDocument = editorKey?.let(candidateDocuments::get)
        val syntaxColors = JetpacsTheme.syntax
        val componentColors = JetpacsTheme.colors
        val diagnosticColors = remember(componentColors, syntaxColors) {
            JetpacsDiagnosticColors(
                error = componentColors.error,
                warning = syntaxColors.number,
                info = componentColors.accent,
                hint = componentColors.mutedContent,
            )
        }
        val outputTransformation = remember(
            document,
            presentation.syntax,
            syntaxColors,
            diagnosticColors,
            annotations?.epoch,
        ) {
            if (document != null) {
                JetpacsAnnotationOutputTransformation(
                    annotations?.fontify,
                    annotations?.diagnostics,
                    presentation.syntax.orEmpty(),
                    syntaxColors,
                    diagnosticColors,
                )
            } else {
                presentation.syntax?.let { JetpacsSyntaxOutputTransformation(it, syntaxColors) }
            }
        }
        val value = binding.controller.snapshot()
        val collapsedSelection = value.selectionStartUtf16 == value.selectionEndUtf16
        val caretDiagnostic = if (collapsedSelection) {
            diagnosticAt(
                annotations?.diagnostics,
                value.text,
                value.selectionEndUtf16,
            )
        } else {
            null
        }
        val applicableDiagnostics = currentDiagnostics(annotations?.diagnostics, value.text)
        val accessibleError = applicableDiagnostics
            ?.firstOrNull { it.severity == "error" }
            ?.message
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presentation.toolbar?.let { toolbar ->
                JetpacsEditorToolbar(
                    items = toolbar,
                    controller = binding.controller,
                    dispatch = { context.action(it) },
                    onCommand = { command ->
                        if (document != null && binding.actionsEnabled) {
                            val occurrence = binding.controller.snapshot()
                            context.editorHost.dispatchEditorCommand(
                                surface = context.surface,
                                document = document,
                                editorId = presentation.id,
                                command = command,
                                cursorUtf16 = occurrence.selectionEndUtf16,
                                selectionStartUtf16 = occurrence.selectionStartUtf16,
                                selectionEndUtf16 = occurrence.selectionEndUtf16,
                            )
                        }
                    },
                    enabled = binding.actionsEnabled,
                )
            }
            JetpacsEditor(
                state = binding.controller.state,
                modifier = binding.fieldModifier(
                    modifier.fillMaxWidth(),
                    errorMessage = accessibleError,
                ),
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
            if (document != null && collapsedSelection) {
                JetpacsEditorToolingStatus(
                    diagnostic = caretDiagnostic,
                    eldoc = currentEldoc(annotations?.eldoc, mirror, value.text),
                    diagnosticColors = diagnosticColors,
                )
            }
            binding.syncPhase?.let { phase ->
                JetpacsEditorSyncStatus(phase)
            }
            if (offer != null && offerView != null) {
                JetpacsEditorCompletion(
                    offer = offer,
                    view = offerView,
                    document = candidateDocument,
                    narrowing = context.editorHost.completionNarrowing,
                    onSelect = { candidate ->
                        context.editorHost.selectEditorCompletion(
                            document,
                            presentation.id,
                            candidate.label,
                            candidate.insert,
                        )
                    },
                    onRequestDocument = { wireIndex, offerEpoch ->
                        context.editorHost.requestCandidateDocument(
                            document,
                            presentation.id,
                            wireIndex,
                            offerEpoch,
                        )
                    },
                )
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
    Box(
        modifier = Modifier
            .styleable(
                styleState,
                JetpacsTheme.styles.editorSyncStatus,
                designComponentNonTextStyle(
                    DesignComponentStyleSlot.EditorSyncStatus,
                ),
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        BasicText(
            text = text,
            style = resolvedDesignTextStyle(
                DesignComponentStyleSlot.EditorSyncStatus,
                JetpacsTheme.typography.fieldSupporting.copy(
                    color = JetpacsTheme.colors.mutedContent,
                ),
                styleState,
            ),
        )
    }
}
