// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.jetpacs.renderer.model.CandidateDocument
import com.calebc42.jetpacs.renderer.model.CompletionCandidate
import com.calebc42.jetpacs.renderer.model.CompletionOffer
import com.calebc42.jetpacs.renderer.model.DiagnosticRange
import com.calebc42.jetpacs.renderer.model.EldocLine
import com.calebc42.jetpacs.renderer.model.candidateDocumentVisible
import com.calebc42.jetpacs.renderer.model.narrowedCompletionCandidates

/** Maximum completion rows rendered on one bounded editor surface. */
internal const val MAX_VISIBLE_JETPACS_COMPLETIONS = 12

/**
 * Inline completion and lazy documentation presentation for one active offer.
 *
 * Rows retain their wire indices after receiver-local narrowing. They remain
 * siblings of the editor so neither TalkBack nor Switch Access merges their
 * actions into the sole editable semantics owner.
 */
@Composable
internal fun JetpacsEditorCompletion(
    offer: CompletionOffer,
    view: CompletionOfferView,
    document: CandidateDocument?,
    narrowing: CompletionNarrowing,
    onSelect: (CompletionCandidate) -> Unit,
    onRequestDocument: (wireIndex: Int, offerEpoch: Long) -> Unit,
) {
    val visible = narrowedCompletionCandidates(
        offer.candidates,
        view.active,
        view.extendedPrefix,
        view.ext,
        narrowing,
    ).take(MAX_VISIBLE_JETPACS_COMPLETIONS)
    if (visible.isEmpty()) return
    Column(
        modifier = Modifier.styleable(
            remember { MutableStyleState(null) },
            JetpacsTheme.styles.editorCompletionList,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val haptic = LocalHapticFeedback.current
        visible.forEach { (wireIndex, candidate) ->
            JetpacsCompletionRow(
                candidate = candidate,
                onClick = { onSelect(candidate) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRequestDocument(wireIndex, offer.epoch)
                },
            )
        }
        if (candidateDocumentVisible(document, offer.epoch, visible.map { it.index })) {
            BasicText(
                text = document!!.text,
                style = JetpacsTheme.typography.code.copy(
                    color = JetpacsTheme.colors.mutedContent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 128.dp)
                    .verticalScroll(remember(document.index, document.epoch) { ScrollState(0) })
                    .styleable(
                        remember { MutableStyleState(null) },
                        JetpacsTheme.styles.editorCandidateDocument,
                    ),
            )
        }
    }
}

@Composable
private fun JetpacsCompletionRow(
    candidate: CompletionCandidate,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val state = rememberUpdatedStyleState(source)
    val accessibleLabel = buildString {
        append(candidate.label)
        candidate.annotation?.takeIf { it.isNotEmpty() }?.let {
            append(", ")
            append(it)
        }
        candidate.kind?.takeIf { completionKindGlyph(it) != null }?.let {
            append(", ")
            append(it.replace('-', ' '))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .semantics { contentDescription = accessibleLabel }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onLongClickLabel = "Show documentation",
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .styleable(state, JetpacsTheme.styles.editorCompletionItem),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        completionKindGlyph(candidate.kind)?.let { glyph ->
            BasicText(
                glyph,
                style = JetpacsTheme.typography.panelLabel.copy(
                    color = JetpacsTheme.colors.accent,
                ),
                modifier = Modifier
                    .width(22.dp)
                    .clearAndSetSemantics { },
            )
            Spacer(Modifier.width(4.dp))
        }
        BasicText(candidate.label, style = JetpacsTheme.typography.field)
        candidate.annotation?.takeIf { it.isNotEmpty() }?.let { annotation ->
            Spacer(Modifier.width(8.dp))
            BasicText(
                annotation,
                style = JetpacsTheme.typography.fieldSupporting.copy(
                    color = JetpacsTheme.colors.mutedContent,
                ),
            )
        }
    }
}

/**
 * Explicit candidate-kind glyph map. An absent or future kind has no glyph,
 * which is the protocol's required additive-vocabulary degradation behavior.
 */
internal fun completionKindGlyph(kind: String?): String? = when (kind) {
    "text" -> "t"
    "method", "function", "constructor" -> "ƒ"
    "field", "variable", "property" -> "v"
    "class", "interface", "struct", "type-parameter" -> "T"
    "module" -> "M"
    "unit", "value", "enum", "enum-member" -> "E"
    "keyword" -> "k"
    "snippet" -> "<>"
    "color" -> "●"
    "file", "folder" -> "F"
    "reference" -> "↗"
    "constant" -> "C"
    "event" -> "!"
    "operator" -> "±"
    else -> null
}

/** Diagnostic-at-caret status, with eldoc as the lower-priority fallback. */
@Composable
internal fun JetpacsEditorToolingStatus(
    diagnostic: DiagnosticRange?,
    eldoc: EldocLine?,
    diagnosticColors: JetpacsDiagnosticColors,
) {
    val text = diagnostic?.message ?: eldoc?.text?.takeIf { it.isNotEmpty() } ?: return
    val accessibleText = diagnostic?.let {
        "${it.severity.replaceFirstChar(Char::uppercaseChar)}: $text"
    } ?: text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .styleable(
                remember { MutableStyleState(null) },
                JetpacsTheme.styles.editorToolingStatus,
            )
            .semantics {
                contentDescription = accessibleText
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        diagnostic?.let {
            BasicText(
                "●",
                style = JetpacsTheme.typography.fieldSupporting.copy(
                    color = diagnosticColors.forSeverity(it.severity),
                ),
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.width(6.dp))
        }
        BasicText(
            text,
            style = if (diagnostic == null) {
                JetpacsTheme.typography.code.copy(color = JetpacsTheme.colors.mutedContent)
            } else {
                JetpacsTheme.typography.fieldSupporting.copy(
                    color = JetpacsTheme.colors.mutedContent,
                )
            },
            maxLines = 2,
        )
    }
}
