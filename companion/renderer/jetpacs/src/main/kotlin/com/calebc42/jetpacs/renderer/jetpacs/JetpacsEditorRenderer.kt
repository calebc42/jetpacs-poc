// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.jetpacs.renderer.compose.ComposeNodeRenderContext
import com.calebc42.jetpacs.renderer.compose.rememberEditorBinding
import kotlinx.serialization.json.JsonObject

/** Jetpacs-scoped presentation override for the local tier of canonical `editor`. */
object JetpacsEditorRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.components.editor.compose"
    override val designScope: String = JETPACS_COMPONENTS_EXTENSION
    override val nodeTypes: Set<String> = setOf("editor")

    /** Synchronized editing keeps the mature Material path until Phase 5. */
    override fun appliesTo(node: JsonObject): Boolean = "document" !in node

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val binding = rememberEditorBinding(node, context)
        val presentation = binding.presentation
        val syntaxColors = JetpacsTheme.syntax
        val outputTransformation = remember(presentation.syntax, syntaxColors) {
            presentation.syntax?.let {
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
                    enabled = binding.interactive,
                )
            }
            JetpacsEditor(
                state = binding.controller.state,
                modifier = binding.fieldModifier(modifier.fillMaxWidth()),
                enabled = presentation.enabled,
                readOnly = presentation.readOnly,
                lineNumbers = presentation.lineNumbers,
                chromeless = presentation.chromeless,
                inputTransformation = binding.controller.inputTransformation,
                outputTransformation = outputTransformation,
                keyboardOptions = presentation.keyboardOptions,
                onKeyboardAction = { binding.enter() },
                lineLimits = presentation.lineLimits,
            )
            if (presentation.onSave != null) {
                JetpacsAction(
                    label = "Save",
                    onClick = { binding.save() },
                    enabled = binding.interactive,
                )
            }
        }
    }
}
