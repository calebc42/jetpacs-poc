// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.calebc42.ebp.renderer.model.EditorSyncPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class JetpacsEditorTest {
    @Test
    fun logicalLineStartsIncludeEmptyAndTrailingLinesWithoutCountingWraps() {
        assertArrayEquals(intArrayOf(0), logicalLineStarts(""))
        assertArrayEquals(intArrayOf(0), logicalLineStarts("a long visual line"))
        assertArrayEquals(intArrayOf(0, 2, 3), logicalLineStarts("a\n\n"))
        assertArrayEquals(intArrayOf(0, 3), logicalLineStarts("😀\nx"))
    }

    @Test
    fun everyUnsettledSynchronizedLifecycleHasVisibleStatusCopy() {
        EditorSyncPhase.entries.filter { it != EditorSyncPhase.READY }.forEach { phase ->
            assertEquals(true, editorSyncStatusText(phase)?.isNotBlank())
        }
        assertNull(editorSyncStatusText(EditorSyncPhase.READY))
    }

    @Test
    fun scopedRendererClaimsCanonicalSynchronizedEditors() {
        val node = Json.parseToJsonElement(
            """{"t":"editor","id":"body","document":"doc:one"}""",
        ) as JsonObject
        assertEquals(true, JetpacsEditorRenderer.appliesTo(node))
    }

    @Test
    fun completionKindsAndSyntaxRolesDegradeWithoutInventedDecoration() {
        assertEquals("ƒ", completionKindGlyph("function"))
        assertEquals("T", completionKindGlyph("type-parameter"))
        assertNull(completionKindGlyph("kind-from-the-future"))
        assertNull(completionKindGlyph(null))

        val colors = JetpacsSyntaxColors(
            comment = Color.Gray,
            string = Color.Green,
            keyword = Color.Blue,
            function = Color.Cyan,
            constant = Color.Magenta,
            number = Color.Red,
            link = Color.Blue,
            preprocessor = Color.DarkGray,
            tag = Color.Green,
            todo = Color.Red,
            done = Color.Green,
            heading = listOf(Color.Blue),
            parenthesis = listOf(Color.Gray),
        )
        assertNotNull(jetpacsRoleStyle("variable", colors))
        assertNotNull(jetpacsRoleStyle("operator", colors))
        assertNull(jetpacsRoleStyle("role-from-the-future", colors))
    }

    private val palette = JetpacsSyntaxColors(
        comment = Color.Gray,
        string = Color.Green,
        keyword = Color.Blue,
        function = Color.Cyan,
        constant = Color.Magenta,
        number = Color.Red,
        link = Color.Blue,
        preprocessor = Color.DarkGray,
        tag = Color.Green,
        todo = Color.Red,
        done = Color.Green,
        heading = listOf(Color.Blue, Color.Red, Color.Green),
        parenthesis = listOf(Color.Gray),
    )

    @Test
    fun headingRunsTakeTheirLevelFromTheDepthCycle() {
        assertEquals(Color.Blue, jetpacsRoleStyle("heading", palette)?.color)
        assertEquals(Color.Red, jetpacsRoleStyle("heading", palette, 2)?.color)
        assertEquals(Color.Blue, jetpacsRoleStyle("heading", palette, 4)?.color)
    }

    @Test
    fun boundSyntaxSlotsOverlayOnlyTheirAuthoredMembers() {
        val overrides = JetpacsSyntaxOverrides(
            byRole = mapOf(
                "comment" to SpanStyle(color = Color.Black, fontStyle = FontStyle.Normal),
                "heading" to SpanStyle(fontWeight = FontWeight(600)),
            ),
            byHeadingLevel = mapOf(2 to SpanStyle(color = Color.Yellow)),
        )
        val comment = overrides.style("comment", null, palette)!!
        assertEquals(Color.Black, comment.color)
        assertEquals(FontStyle.Normal, comment.fontStyle)

        // An unbound level falls back to the role slot over the palette colour.
        val level1 = overrides.style("heading", 1, palette)!!
        assertEquals(Color.Blue, level1.color)
        assertEquals(FontWeight(600), level1.fontWeight)

        // A bound level stacks over the role slot: its colour wins and the
        // role slot's weight still applies.
        val level2 = overrides.style("heading", 2, palette)!!
        assertEquals(Color.Yellow, level2.color)
        assertEquals(FontWeight(600), level2.fontWeight)

        // Unbound roles are untouched; unknown roles still resolve to nothing.
        assertEquals(jetpacsRoleStyle("string", palette), overrides.style("string", null, palette))
        assertNull(overrides.style("role-from-the-future", null, palette))
    }

    @Test
    fun syntaxSlotPropertiesBecomeSpanAttributes() {
        val roles = deriveJetpacsThemeRoles(null, dark = false)
        val span = designSyntaxSpanStyle(
            mapOf(
                DesignProperty.ContentColor to DesignValue.ColorValue(0xFF3F6F86L),
                DesignProperty.BackgroundColor to DesignValue.ColorValue(0xFFEEEEEEL),
                DesignProperty.FontWeight to DesignValue.FontWeightValue(600),
                DesignProperty.Italic to DesignValue.BooleanValue(true),
                DesignProperty.StrikeThrough to DesignValue.BooleanValue(true),
                DesignProperty.Padding to DesignValue.DimensionValue(12.0),
            ),
            roles,
        )
        assertEquals(Color(0xFF3F6F86), span.color)
        assertEquals(Color(0xFFEEEEEE), span.background)
        assertEquals(FontWeight(600), span.fontWeight)
        assertEquals(FontStyle.Italic, span.fontStyle)
        assertEquals(TextDecoration.LineThrough, span.textDecoration)
    }
}
