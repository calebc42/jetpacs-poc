// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.calebc42.jetpacs.renderer.compose.ComposeCoreNodeOverride
import com.calebc42.jetpacs.renderer.compose.ComposeNodeRenderContext
import com.calebc42.jetpacs.renderer.compose.MaskOutputTransformation
import com.calebc42.jetpacs.renderer.compose.keyboardAction
import com.calebc42.jetpacs.renderer.compose.rememberTextInputBinding
import kotlinx.serialization.json.JsonObject

/** Jetpacs-scoped presentation override for canonical EBP `text_input`. */
object JetpacsTextInputRenderer : ComposeCoreNodeOverride {
    override val id: String = "jetpacs.components.text-input.compose"
    override val designScope: String = JETPACS_COMPONENTS_EXTENSION
    override val nodeTypes: Set<String> = setOf("text_input")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val binding = rememberTextInputBinding(node, context)
        val presentation = binding.presentation
        val keyboard = LocalSoftwareKeyboardController.current
        val syntaxColors = JetpacsTheme.syntax
        val mask = presentation.mask
        val syntax = presentation.syntax
        val outputTransformation = remember(
            mask,
            syntax,
            syntaxColors,
        ) {
            when {
                mask != null -> MaskOutputTransformation(mask)
                syntax != null -> JetpacsSyntaxOutputTransformation(syntax, syntaxColors)
                else -> null
            }
        }
        JetpacsTextField(
            state = binding.controller.state,
            modifier = modifier.focusRequester(binding.focusRequester),
            enabled = binding.enabled,
            secure = presentation.password,
            variant = if (presentation.variant == "filled") {
                JetpacsTextFieldVariant.Filled
            } else {
                JetpacsTextFieldVariant.Outlined
            },
            label = presentation.label,
            placeholder = presentation.hint,
            supportingText = presentation.supportingText,
            prefix = presentation.prefix,
            suffix = presentation.suffix,
            leadingDecoration = presentation.leadingIcon?.let { name ->
                { JetpacsFieldGlyph(name) }
            },
            trailingDecoration = presentation.trailingIcon?.let { name ->
                { JetpacsFieldGlyph(name) }
            },
            isError = presentation.isError,
            monospace = presentation.monospace || presentation.syntax != null,
            contentPadding = presentation.contentPadding,
            inputTransformation = binding.controller.inputTransformation,
            outputTransformation = outputTransformation,
            keyboardOptions = presentation.keyboardOptions,
            onKeyboardAction = binding.keyboardAction { keyboard?.hide() },
            lineLimits = presentation.lineLimits,
        )
    }
}
