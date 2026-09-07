// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class JetpacsDesignRichTextTest {
    @Test fun richRunsPreserveUnicodeOffsetsAndEveryCanonicalAttribute() {
        val spans = Json.parseToJsonElement("""[
          {"text":"TODO ","color":"error","font_weight":"bold"},
          {"text":"🌲 café","italic":true,"mono":true,"underline":true,"bg":"surface"}
        ]""") as JsonArray
        val text = buildDesignRichText(spans, Color.Blue, {
            when(it) { "error" -> Color.Red; "surface" -> Color.White; else -> null }
        }) { fail("Passive text dispatched") }
        assertEquals("TODO 🌲 café", text.text)
        assertEquals(FontWeight.Bold, text.spanStyles[0].item.fontWeight)
        assertEquals(Color.Red, text.spanStyles[0].item.color)
        val content = text.spanStyles[1]
        assertEquals(5, content.start)
        assertEquals(text.length, content.end)
        assertEquals(FontStyle.Italic, content.item.fontStyle)
        assertEquals(FontFamily.Monospace, content.item.fontFamily)
        assertEquals(TextDecoration.Underline, content.item.textDecoration)
        assertEquals(Color.White, content.item.background)
    }

    @Test fun linkUsesItsOwnActionAndAuthoredColorWins() {
        val spans = Json.parseToJsonElement("""[
          {"text":"One", "on_tap":{"action":"test.one"}},
          {"text":"Two", "color":"error", "on_tap":{"action":"test.two"}}
        ]""") as JsonArray
        val dispatched = mutableListOf<JsonObject>()
        val text = buildDesignRichText(spans, Color.Blue, { if(it == "error") Color.Red else null }, dispatched::add)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(2, links.size)
        val first = links[0].item as LinkAnnotation.Clickable
        val second = links[1].item as LinkAnnotation.Clickable
        assertEquals(Color.Blue, first.styles?.style?.color)
        assertEquals(Color.Red, second.styles?.style?.color)
        second.linkInteractionListener?.onClick(second)
        assertEquals(listOf(Json.parseToJsonElement("""{"action":"test.two"}""")), dispatched)
    }
}
