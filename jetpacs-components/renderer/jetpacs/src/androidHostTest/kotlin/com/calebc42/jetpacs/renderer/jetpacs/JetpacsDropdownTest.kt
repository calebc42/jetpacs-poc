// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire reading behind the Foundation dropdown. */
class JetpacsDropdownTest {
    private fun array(json: String) = Json.parseToJsonElement(json) as JsonArray
    private fun node(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun optionsKeepTheirOrderAndPublishTheValueText() {
        val options = jetpacsDropdownOptions(
            array(
                """[{"label":"Filled","value":"filled"},
                    {"label":"Two","value":2},
                    {"label":"Yes","value":true}]""",
            ),
        )
        // An EnumOption value may be a string, number or boolean; what an app
        // receives is its text, exactly what the Material renderer publishes.
        assertEquals(listOf("Filled", "Two", "Yes"), options.map { it.label })
        assertEquals(listOf("filled", "2", "true"), options.map { it.value })
    }

    @Test
    fun aRowWithoutAScalarValueIsSkippedNotCrashedOn() {
        val options = jetpacsDropdownOptions(
            array("""[{"label":"Filled","value":"filled"}, "loose", {"label":"No value"}, {"value":["a"]}]"""),
        )
        assertEquals(listOf("filled"), options.map { it.value })
    }

    @Test
    fun theShownLabelIsTheOptionsOrTheRawValueOrNothing() {
        val options = jetpacsDropdownOptions(
            array("""[{"label":"Filled","value":"filled"},{"label":"Tonal","value":"tonal"}]"""),
        )
        assertEquals("Tonal", jetpacsDropdownLabel(options, "tonal"))
        // A stored value outside the options still shows, as its own text.
        assertEquals("custom", jetpacsDropdownLabel(options, "custom"))
        assertNull(jetpacsDropdownLabel(options, null))
    }

    @Test
    fun theEditableFormDeclinesAndThePlainFormDoesNot() {
        val plain = node("""{"t":"dropdown","id":"variant","options":[]}""")
        val editable = node("""{"t":"dropdown","id":"variant","options":[],"editable":true}""")
        val explicitlyPlain = node("""{"t":"dropdown","id":"variant","options":[],"editable":false}""")
        assertTrue(JetpacsDesignDropdownRenderer.appliesTo(plain))
        assertFalse(JetpacsDesignDropdownRenderer.appliesTo(editable))
        assertTrue(JetpacsDesignDropdownRenderer.appliesTo(explicitlyPlain))
        assertEquals(setOf("dropdown"), JetpacsDesignDropdownRenderer.nodeTypes)
        assertTrue(JetpacsDesignDropdownRenderer in JetpacsDesignCanonicalOverrides)
    }
}
