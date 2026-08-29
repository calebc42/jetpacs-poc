// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.ui.text.input.ImeAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPresentationTest {
    @Test
    fun localDefaultsMatchTheCanonicalEditorContract() {
        val presentation = editorPresentationOf(node("""{"t":"editor","id":"draft"}"""))

        assertEquals("draft", presentation.id)
        assertNull(presentation.document)
        assertTrue(presentation.enabled)
        assertFalse(presentation.readOnly)
        assertFalse(presentation.singleLine)
        assertFalse(presentation.publishState)
        assertFalse(presentation.autofocus)
        assertEquals(TextFieldLineLimits.MultiLine(3, Int.MAX_VALUE), presentation.lineLimits)
        assertEquals(ImeAction.Default, presentation.keyboardOptions.imeAction)
    }

    @Test
    fun projectsLocalPresentationAndActionMembersWithoutInventingSemantics() {
        val presentation = editorPresentationOf(node("""
            {
              "t":"editor","id":"command","value":"alpha","single_line":true,
              "read_only":true,"enabled":false,"syntax":"elisp","line_numbers":true,
              "chromeless":true,"publish_state":true,"autofocus":true,
              "toolbar":[{"label":"Insert","snippet":"()"}],
              "on_save":{"action":"catalog.save"},
              "on_enter":{"action":"catalog.enter"}
            }
        """))

        assertNull(presentation.document)
        assertFalse(presentation.enabled)
        assertTrue(presentation.readOnly)
        assertTrue(presentation.singleLine)
        assertEquals("elisp", presentation.syntax)
        assertTrue(presentation.lineNumbers)
        assertFalse(presentation.complete)
        assertTrue(presentation.chromeless)
        assertTrue(presentation.publishState)
        assertTrue(presentation.autofocus)
        assertEquals(1, presentation.toolbar?.size)
        assertEquals(TextFieldLineLimits.SingleLine, presentation.lineLimits)
        assertEquals(ImeAction.Done, presentation.keyboardOptions.imeAction)
        assertEquals("catalog.save", presentation.onSave?.get("action")?.toString()?.trim('"'))
        assertEquals("catalog.enter", presentation.onEnter?.get("action")?.toString()?.trim('"'))
    }

    @Test
    fun synchronizedIdentityAndAuthoredLineBoundsRemainVisibleToMaterialFallback() {
        val presentation = editorPresentationOf(node("""
            {
              "t":"editor","id":"main","document":"notes","complete":true,
              "min_lines":4,"max_lines":9
            }
        """))

        assertEquals("notes", presentation.document)
        assertTrue(presentation.complete)
        assertEquals(TextFieldLineLimits.MultiLine(4, 9), presentation.lineLimits)
    }

    private fun node(source: String): JsonObject = Json.parseToJsonElement(source) as JsonObject
}
