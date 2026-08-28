// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.calebc42.ebp.wire.TEXT_INPUT_CONTRACT
import com.calebc42.ebp.wire.normalizeTextInput

/**
 * Value-based view of one shared controller used by masked fields.
 *
 * The adapter retains IME composition while [TextInputController.state]
 * remains the canonical logical owner used by reconciliation and submission.
 */
@Stable
class LegacyTextInputAdapter internal constructor(
    private val controller: TextInputController,
) {
    /** Current logical value, selection, and in-flight IME composition. */
    var value by mutableStateOf(
        TextFieldValue(
            controller.state.text.toString(),
            controller.state.selection,
        ),
    )
        private set

    /** Apply one keyboard, IME, paste, drop, or autofill-equivalent edit. */
    fun onValueChange(proposed: TextFieldValue) {
        controller.applyLegacyEdit(proposed)?.let { value = it }
    }

    internal fun reconcile(text: String, selection: TextRange) {
        if (value.text != text) {
            value = TextFieldValue(text, selection)
        } else if (value.composition == null && value.selection != selection) {
            value = value.copy(selection = selection)
        }
    }
}

/** Remember the value-based bridge required by [MaskVisualTransformation]. */
@Composable
fun rememberLegacyTextInputAdapter(
    controller: TextInputController,
): LegacyTextInputAdapter {
    val adapter = remember(controller) { LegacyTextInputAdapter(controller) }
    val text = controller.state.text.toString()
    val selection = controller.state.selection
    SideEffect { adapter.reconcile(text, selection) }
    return adapter
}

/**
 * Apply the generated normalization order and remap UTF-16 ranges.
 *
 * EBP transforms only remove input units (including scalar truncation), so a
 * single subsequence walk derives total selection/composition mappings from
 * the authoritative normalized result without duplicating contract rules.
 */
internal fun normalizeLegacyTextFieldValue(
    proposed: TextFieldValue,
    singleLine: Boolean,
    filter: String?,
    maximum: Long?,
): TextFieldValue {
    val normalized = normalizeTextInput(proposed.text, singleLine, filter, maximum)
    if (normalized == proposed.text) return proposed
    val normalizedAt = IntArray(proposed.text.length + 1)
    var outputOffset = 0
    for (inputOffset in proposed.text.indices) {
        if (outputOffset < normalized.length &&
            proposed.text[inputOffset] == normalized[outputOffset]
        ) {
            outputOffset++
        }
        normalizedAt[inputOffset + 1] = outputOffset
    }
    check(outputOffset == normalized.length) {
        "generated text-input normalization must preserve subsequence order"
    }
    fun map(range: TextRange): TextRange = TextRange(
        normalizedAt[range.start.coerceIn(0, proposed.text.length)],
        normalizedAt[range.end.coerceIn(0, proposed.text.length)],
    )
    val composition = proposed.composition?.let(::map)?.takeUnless { it.collapsed }
    return TextFieldValue(
        text = normalized,
        selection = map(proposed.selection),
        composition = composition,
    )
}

/**
 * Linear, scalar-aware presentation of EBP's generated `mask` template.
 *
 * Mask literals never enter the stored value. Text beyond the mask remains
 * visible, and both mappings are total over Compose's UTF-16 offset domains.
 */
class MaskVisualTransformation(private val mask: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val slotCodePoint = TEXT_INPUT_CONTRACT.mask.slot.codePointAt(0)
        val out = StringBuilder(raw.length + mask.length)
        val transformedAt = IntArray(raw.length + 1)
        val originalAt = IntArray(raw.length + mask.length + 1)
        var consumed = 0
        var maskOffset = 0

        var originalAtIndex = 0
        fun fillLiteralMapping() {
            while (originalAtIndex < out.length) {
                originalAt[++originalAtIndex] = consumed
            }
        }

        fun appendStoredScalar() {
            val codePoint = raw.codePointAt(consumed)
            val width = Character.charCount(codePoint)
            val transformedStart = out.length
            for (unit in 0..width) {
                transformedAt[consumed + unit] = transformedStart + unit
            }
            out.appendCodePoint(codePoint)
            repeat(width) {
                originalAt[++originalAtIndex] = consumed + it + 1
            }
            consumed += width
        }

        while (maskOffset < mask.length && consumed < raw.length) {
            val codePoint = mask.codePointAt(maskOffset)
            if (codePoint == slotCodePoint) {
                appendStoredScalar()
            } else {
                out.appendCodePoint(codePoint)
                fillLiteralMapping()
            }
            maskOffset += Character.charCount(codePoint)
        }
        while (consumed < raw.length) appendStoredScalar()
        transformedAt[raw.length] = out.length
        val transformed = out.toString()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                transformedAt[offset.coerceIn(0, raw.length)]

            override fun transformedToOriginal(offset: Int): Int =
                originalAt[offset.coerceIn(0, transformed.length)]
        }
        return TransformedText(AnnotatedString(transformed), mapping)
    }
}
