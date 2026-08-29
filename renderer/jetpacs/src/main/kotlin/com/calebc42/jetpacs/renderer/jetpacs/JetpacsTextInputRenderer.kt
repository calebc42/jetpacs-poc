// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.calebc42.jetpacs.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.jetpacs.renderer.compose.ComposeNodeRenderContext
import com.calebc42.jetpacs.renderer.compose.MaskVisualTransformation
import com.calebc42.jetpacs.renderer.compose.keyboardAction
import com.calebc42.jetpacs.renderer.compose.rememberLegacyTextInputAdapter
import com.calebc42.jetpacs.renderer.compose.rememberTextInputBinding
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import kotlinx.serialization.json.JsonObject

/** Jetpacs-scoped presentation override for canonical EBP `text_input`. */
object JetpacsTextInputRenderer : ComposeCanonicalNodeOverride {
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
        val outputTransformation = remember(syntax, syntaxColors) {
            syntax?.let { JetpacsSyntaxOutputTransformation(it, syntaxColors) }
        }
        val variant = if (presentation.variant == "filled") {
            JetpacsTextFieldVariant.Filled
        } else {
            JetpacsTextFieldVariant.Outlined
        }
        val leading: (@Composable () -> Unit)? = presentation.leadingIcon?.let { name ->
            { JetpacsFieldGlyph(name) }
        }
        val trailing: (@Composable () -> Unit)? = presentation.trailingIcon?.let { name ->
            { JetpacsFieldGlyph(name) }
        }
        if (mask != null) {
            val adapter = rememberLegacyTextInputAdapter(binding.controller)
            val transformation = remember(mask) { MaskVisualTransformation(mask) }
            JetpacsMaskedTextField(
                value = adapter.value,
                onValueChange = adapter::onValueChange,
                visualTransformation = transformation,
                modifier = binding.fieldModifier(modifier),
                enabled = binding.enabled,
                variant = variant,
                label = presentation.label,
                placeholder = presentation.hint,
                supportingText = presentation.supportingText,
                prefix = presentation.prefix,
                suffix = presentation.suffix,
                leadingDecoration = leading,
                trailingDecoration = trailing,
                isError = presentation.isError,
                monospace = presentation.monospace,
                contentPadding = presentation.contentPadding,
                keyboardOptions = presentation.keyboardOptions,
                keyboardActions = KeyboardActions(onDone = {
                    if (binding.submit() == ActionHandoff.HandedOff &&
                        presentation.hideKeyboardOnSubmit
                    ) {
                        keyboard?.hide()
                    }
                }),
                lineLimits = presentation.lineLimits,
            )
            return
        }
        JetpacsTextField(
            state = binding.controller.state,
            modifier = binding.fieldModifier(modifier),
            enabled = binding.enabled,
            secure = presentation.password,
            variant = variant,
            label = presentation.label,
            placeholder = presentation.hint,
            supportingText = presentation.supportingText,
            prefix = presentation.prefix,
            suffix = presentation.suffix,
            leadingDecoration = leading,
            trailingDecoration = trailing,
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
