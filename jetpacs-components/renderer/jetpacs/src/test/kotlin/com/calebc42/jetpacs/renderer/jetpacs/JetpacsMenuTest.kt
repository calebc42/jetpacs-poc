// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire reading behind the Foundation menu. */
class JetpacsMenuTest {
    private fun array(json: String) = Json.parseToJsonElement(json) as JsonArray

    private class Recorder {
        val dispatched = mutableListOf<JsonObject>()
        val dispatch: (JsonObject) -> Unit = { dispatched += it }
    }

    @Test
    fun aFlatItemCarriesEveryAuthoredMember() {
        val items = jetpacsMenuItems(
            array(
                """[{"label":"Delete","icon":"delete","trailing_icon":"chevron",
                     "supporting_text":"Moves to trash","enabled":false,
                     "on_tap":{"action":"demo.delete"}}]""",
            ),
            Recorder().dispatch,
        )
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("Delete", item.label)
        assertEquals("delete", item.icon)
        assertEquals("chevron", item.trailingIcon)
        assertEquals("Moves to trash", item.supportingText)
        assertEquals(false, item.enabled)
        // No `checked` member means an ordinary row, not an unchecked one.
        assertNull(item.checked)
    }

    @Test
    fun theCheckableFormIsSelectedByPresenceNotValue() {
        val unchecked = jetpacsMenuItems(
            array("""[{"label":"Wrap","checked":false,"on_tap":{"action":"a"}}]"""),
            Recorder().dispatch,
        )[0]
        assertEquals(false, unchecked.checked)
        // The default the renderer must supply when the author omits it.
        assertEquals("check", unchecked.checkedIcon)

        val checked = jetpacsMenuItems(
            array(
                """[{"label":"Wrap","checked":true,"checked_icon":"edit",
                     "on_tap":{"action":"a"}}]""",
            ),
            Recorder().dispatch,
        )[0]
        assertEquals(true, checked.checked)
        assertEquals("edit", checked.checkedIcon)
    }

    @Test
    fun anItemWithoutALabelIsDroppedRatherThanDrawnNameless() {
        val items = jetpacsMenuItems(
            array("""[{"icon":"delete","on_tap":{"action":"a"}},{"label":"Keep"}]"""),
            Recorder().dispatch,
        )
        assertEquals(listOf("Keep"), items.map { it.label })
    }

    @Test
    fun anItemWithoutAnActionIsDrawnButInert() {
        val recorder = Recorder()
        val items = jetpacsMenuItems(array("""[{"label":"Heading only"}]"""), recorder.dispatch)
        assertEquals(1, items.size)
        items[0].onSelect()
        assertTrue(recorder.dispatched.isEmpty())
    }

    @Test
    fun selectingAnItemDispatchesItsOwnDescriptorExactlyOnce() {
        val recorder = Recorder()
        val items = jetpacsMenuItems(
            array(
                """[{"label":"A","on_tap":{"action":"demo.a"}},
                    {"label":"B","on_tap":{"action":"demo.b"}}]""",
            ),
            recorder.dispatch,
        )
        items[1].onSelect()
        assertEquals(1, recorder.dispatched.size)
        assertEquals("demo.b", recorder.dispatched[0]["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun groupsKeepTheirLabelAndOrder() {
        val groups = jetpacsMenuGroups(
            array(
                """[{"label":"Add","items":[{"label":"Above","on_tap":{"action":"a"}},
                                            {"label":"Below","on_tap":{"action":"b"}}]},
                    {"label":"Remove","items":[{"label":"Delete","on_tap":{"action":"d"}}]}]""",
            ),
            Recorder().dispatch,
        )
        assertEquals(listOf("Add", "Remove"), groups.map { it.label })
        assertEquals(listOf("Above", "Below"), groups[0].items.map { it.label })
    }

    @Test
    fun aGroupWithNoUsableItemIsNotAGroup() {
        // Its heading would draw a label and a rule over nothing.
        val groups = jetpacsMenuGroups(
            array("""[{"label":"Empty","items":[]},{"label":"Real","items":[{"label":"X"}]}]"""),
            Recorder().dispatch,
        )
        assertEquals(listOf("Real"), groups.map { it.label })
    }

    @Test
    fun anAbsentArrayReadsAsNoItemsRatherThanFailing() {
        assertTrue(jetpacsMenuItems(null, Recorder().dispatch).isEmpty())
        assertTrue(jetpacsMenuGroups(null, Recorder().dispatch).isEmpty())
    }

    @Test
    fun theTrailingSlotCarriesEitherAGlyphOrAHint() {
        // SPEC 17.4 makes the pair mutually exclusive, so the renderer never
        // has to choose between them; it only has to read both.
        val icon = jetpacsMenuItems(
            array("""[{"label":"Open","trailing_icon":"chevron_right"}]"""),
            Recorder().dispatch,
        )[0]
        assertEquals("chevron_right", icon.trailingIcon)
        assertNull(icon.trailingText)

        val hint = jetpacsMenuItems(
            array("""[{"label":"Send feedback","trailing_text":"F11"}]"""),
            Recorder().dispatch,
        )[0]
        assertEquals("F11", hint.trailingText)
        assertNull(hint.trailingIcon)
    }

    @Test
    fun supportingTextSurvivesOnAnOrdinaryItem() {
        // The Material renderer computes this and then drops it on the
        // non-checkable overload; the Foundation row honors it on every item.
        val item = jetpacsMenuItems(
            array("""[{"label":"Termux home","supporting_text":"/data/data/com.termux"}]"""),
            Recorder().dispatch,
        )[0]
        assertNull(item.checked)
        assertEquals("/data/data/com.termux", item.supportingText)
    }
}
