// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.render

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EditorSingleLineTest {

    @Test
    fun lineFeedsAreDeletedAndOffsetsAreRemapped() {
        val original = TextFieldValue(
            text = "ab\ncd\nef",
            selection = TextRange(7, 3),
            composition = TextRange(3, 6))

        val result = editorWithoutLineFeeds(original)

        assertEquals("abcdef", result.text)
        assertEquals(TextRange(5, 2), result.selection)
        assertEquals(TextRange(2, 4), result.composition)
    }

    @Test
    fun anAlreadySingleLineValueIsUnchanged() {
        val value = TextFieldValue("execute-extended-command", TextRange(8))
        assertSame(value, editorWithoutLineFeeds(value))
    }
}
