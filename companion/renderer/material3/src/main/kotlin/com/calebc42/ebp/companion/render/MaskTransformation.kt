// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.calebc42.ebp.wire.TEXT_INPUT_CONTRACT

/** §17.4 `text_input.mask`: a display template over the STORED value.
 *
 * Every `#` consumes one stored Unicode scalar; everything else is literal filler
 * that displays but never enters `value` or `state.changed` — the wire form
 * of upstream's phone-number OutputTransformation, where formatting happens
 * locally, per keystroke, and the round trip never sees the parentheses.
 *
 * Stored text beyond the mask's `#` capacity displays raw after the template,
 * mapped 1:1, so a mask never TRUNCATES a value it cannot format (`max_length`
 * is the member that limits length). Both offset mappings are total functions
 * over their domains — Compose crashes on a partial one. */
internal class MaskTransformation(private val mask: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val slotCodePoint = TEXT_INPUT_CONTRACT.mask.slot.codePointAt(0)
        val out = StringBuilder()
        // transformedAt[o] = transformed offset where original offset o lands.
        val transformedAt = IntArray(raw.length + 1)
        // originalAt[t] = original offset represented by transformed offset t.
        // Both arrays use Compose's UTF-16 offsets; only mask SLOT consumption
        // is scalar-based.
        val originalAt = mutableListOf(0)
        var consumed = 0
        var maskOffset = 0
        fun appendLiteral(codePoint: Int) {
            val before = out.length
            out.appendCodePoint(codePoint)
            while (originalAt.size <= out.length) originalAt.add(consumed)
            check(originalAt.size == out.length + 1 && before < out.length)
        }
        fun appendStoredScalar() {
            val codePoint = raw.codePointAt(consumed)
            val width = Character.charCount(codePoint)
            val transformedStart = out.length
            for (unit in 0..width)
                transformedAt[consumed + unit] = transformedStart + unit
            out.appendCodePoint(codePoint)
            for (unit in 1..width) originalAt.add(consumed + unit)
            consumed += width
        }
        while (maskOffset < mask.length) {
            if (consumed >= raw.length) break
            val codePoint = mask.codePointAt(maskOffset)
            if (codePoint == slotCodePoint) appendStoredScalar()
            else appendLiteral(codePoint)
            maskOffset += Character.charCount(codePoint)
        }
        // Anything past the template (or a template with fewer `#` than
        // characters) rides raw, 1:1.
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
