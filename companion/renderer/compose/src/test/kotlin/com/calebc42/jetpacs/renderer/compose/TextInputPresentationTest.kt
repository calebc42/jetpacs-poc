// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInputPresentationTest {
    @Test
    fun projectsCompleteAcceptedPresentationAndGeneratedDefaults() {
        val presentation = textInputPresentationOf(
            node(
                """{
                  "t":"text_input","id":"phone","label":"Phone","hint":"Digits",
                  "supporting_text":"Ten digits","prefix":"+1","suffix":"ext",
                  "leading_icon":"search","trailing_icon":"clear","single_line":true,
                  "monospace":true,"autofocus":true,"enabled":false,
                  "is_error":true,"variant":"filled","mask":"(###)",
                  "content_padding":4,"hide_keyboard_on_submit":true,
                  "keyboard":"phone","on_submit":{"action":"demo.submit"}
                }""",
            ),
        )

        assertEquals("phone", presentation.id)
        assertEquals("Phone", presentation.label)
        assertEquals("Digits", presentation.hint)
        assertEquals("Ten digits", presentation.supportingText)
        assertEquals("+1", presentation.prefix)
        assertEquals("ext", presentation.suffix)
        assertEquals("search", presentation.leadingIcon)
        assertEquals("clear", presentation.trailingIcon)
        assertTrue(presentation.singleLine)
        assertTrue(presentation.monospace)
        assertTrue(presentation.autofocus)
        assertFalse(presentation.enabled)
        assertTrue(presentation.isError)
        assertEquals("filled", presentation.variant)
        assertEquals(4f, presentation.contentPadding?.value)
        assertEquals(KeyboardType.Phone, presentation.keyboardOptions.keyboardType)
        assertEquals(ImeAction.Done, presentation.keyboardOptions.imeAction)
        assertEquals(TextFieldLineLimits.SingleLine, presentation.lineLimits)

        val defaults = textInputPresentationOf(node("""{"t":"text_input","id":"n"}"""))
        assertEquals("outlined", defaults.variant)
        assertTrue(defaults.enabled)
        assertFalse(defaults.password)
        assertNull(defaults.label)
        assertEquals(KeyboardType.Text, defaults.keyboardOptions.keyboardType)
        assertEquals(ImeAction.Default, defaults.keyboardOptions.imeAction)
        assertEquals(
            TextFieldLineLimits.MultiLine(1, 1),
            defaults.lineLimits,
        )
    }

    @Test
    fun scalarSelectionSeedsAuthoredTextAndDirtyDraftMovesCursorToEnd() {
        val input = node(
            """{"t":"text_input","id":"n","value":"a😀b","selection":[1,2]}""",
        )

        assertEquals(TextInputSeed("a😀b", TextRange(1, 3)), textInputSeed(input, null))
        assertEquals(
            TextInputSeed("retained", TextRange(8)),
            textInputSeed(input, "retained"),
        )
    }

    @Test
    fun passwordUsesSecretKeyboardAndNeverSeedsRetainedText() {
        val input = node(
            """{"t":"text_input","id":"pw","password":true,"keyboard":"email"}""",
        )

        val presentation = textInputPresentationOf(input)
        assertEquals(KeyboardType.Password, presentation.keyboardOptions.keyboardType)
        assertEquals(TextInputSeed("", TextRange.Zero), textInputSeed(input, "secret"))
    }

    @Test
    fun platformLineAndDpProjectionCannotOverflowAcceptedNumbers() {
        val presentation = textInputPresentationOf(
            node(
                """{
                  "t":"text_input","id":"large",
                  "min_lines":1,"max_lines":9007199254740991,
                  "content_padding":1e308
                }""",
            ),
        )

        assertEquals(
            TextFieldLineLimits.MultiLine(1, Int.MAX_VALUE),
            presentation.lineLimits,
        )
        assertNull(presentation.contentPadding)
    }

    private fun node(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject
}
