// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticsProjectionTest {
    @Test
    fun accessibleNameUsesTheContractOrder() {
        fun projected(vararg pairs: Pair<String, String>): String =
            resolveAccessibleName(buildJsonObject {
                pairs.forEach { (name, value) -> put(name, value) }
            })

        assertEquals(
            "Authored",
            resolveAccessibleName(buildJsonObject {
                put("t", "button")
                put("icon", "star")
                put("label", "Label")
                put("content_description", "Legacy")
                put("semantics", buildJsonObject { put("name", "Authored") })
            }),
        )
        assertEquals("Legacy", projected("t" to "button", "content_description" to "Legacy"))
        assertEquals("Label", projected("t" to "button", "label" to "Label"))
        assertEquals("star", projected("t" to "icon_button", "icon" to "star"))
        assertEquals("home", projected("t" to "icon", "name" to "home"))
        assertEquals("button", projected("t" to "button"))
        assertEquals("node", resolveAccessibleName(JsonObject(emptyMap())))
    }

    @Test
    fun authoredMetadataAndNestedShapesRemainToolkitNeutral() {
        val projection = projectNodeSemantics(buildJsonObject {
            put("t", "column")
            put("semantics", buildJsonObject {
                put("name", "Inbox")
                put("description", "Messages")
                put("state_description", "3 unread")
                put("error", "Offline")
                put("pane_title", "Inbox")
                put("heading_level", 1)
                put("live_region", "assertive")
                put("traversal_group", false)
                put("traversal_index", 2.5)
                put("collection", buildJsonObject {
                    put("row_count", 3)
                    put("column_count", 2)
                })
                put("collection_item", buildJsonObject {
                    put("row_index", 1)
                    put("row_span", 1)
                    put("column_index", 0)
                    put("column_span", 2)
                })
            })
        })

        assertEquals("Inbox", projection.accessibleName)
        assertEquals("Messages", projection.description)
        assertEquals("3 unread", projection.stateDescription)
        assertEquals("Offline", projection.error)
        assertEquals("Inbox", projection.paneTitle)
        assertEquals(1, projection.headingLevel)
        assertEquals("assertive", projection.liveRegion)
        assertEquals(false, projection.traversalGroup)
        assertEquals(2.5, projection.traversalIndex!!, 0.0)
        assertEquals(SemanticCollection(3, 2), projection.collection)
        assertEquals(SemanticCollectionItem(1, 1, 0, 2), projection.collectionItem)
    }

    @Test
    fun rolesAndStateComeOnlyFromExistingNodeMembers() {
        val checkbox = projectNodeSemantics(buildJsonObject {
            put("t", "checkbox")
            put("state", "indeterminate")
            put("enabled", false)
        })
        assertEquals("checkbox", checkbox.role)
        assertEquals(SemanticToggleState.INDETERMINATE, checkbox.state.toggleState)
        assertNull(checkbox.state.checked)
        assertEquals(false, checkbox.state.enabled)

        val chip = projectNodeSemantics(buildJsonObject {
            put("t", "chip")
            put("label", "Chosen")
            put("on_tap", descriptor("chip.choose"))
            put("selected", true)
        })
        assertEquals("button", chip.role)
        assertEquals(true, chip.state.selected)

        val editor = projectNodeSemantics(buildJsonObject {
            put("t", "editor")
            put("read_only", true)
        })
        assertEquals("text_input", editor.role)
        assertEquals(true, editor.state.readOnly)

        val collapsible = projectNodeSemantics(
            buildJsonObject {
                put("t", "collapsible")
                put("collapsed", true)
            },
            SemanticStateOverride(expanded = true),
        )
        assertTrue(collapsible.state.expanded == true)
        assertEquals(2, projectNodeSemantics(buildJsonObject {
            put("t", "section_header")
        }).headingLevel)
    }

    @Test
    fun progressAndSliderRangesAreDerivedFromTheNode() {
        val indeterminate = projectNodeSemantics(buildJsonObject { put("t", "progress") })
        assertNull(indeterminate.state.progress!!.current)

        val determinate = projectNodeSemantics(buildJsonObject {
            put("t", "progress")
            put("value", 0.75)
        })
        assertEquals(0.75, determinate.state.progress!!.current!!, 0.0)
        assertEquals(0.0, determinate.state.progress.minimum, 0.0)
        assertEquals(1.0, determinate.state.progress.maximum, 0.0)

        val discrete = projectNodeSemantics(buildJsonObject {
            put("t", "slider")
            put("value", 2)
            put("values", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(4))))
        }).state.progress!!
        assertEquals(1.0, discrete.minimum, 0.0)
        assertEquals(4.0, discrete.maximum, 0.0)
        assertEquals(1, discrete.steps)
    }

    @Test
    fun authoredAndSwipeActionsShareOneStableDistinctLabelList() {
        val authoredDescriptor = descriptor("card.authored")
        val swipeDescriptor = descriptor("card.swipe")
        val projection = projectNodeSemantics(buildJsonObject {
            put("t", "card")
            put("on_tap", descriptor("card.tap"))
            put("semantics", buildJsonObject {
                put("actions", buildJsonArray {
                    add(buildJsonObject {
                        put("label", "Delete")
                        put("on_action", authoredDescriptor)
                    })
                })
            })
            put("swipe_end", buildJsonObject {
                put("label", "Delete")
                put("on_trigger", swipeDescriptor)
            })
            put("swipe_start", buildJsonObject {
                put("label", "Archive")
                put("on_trigger", descriptor("card.archive"))
            })
        })

        assertEquals(listOf("Delete", "Archive"), projection.actions.map { it.label })
        assertEquals(authoredDescriptor, projection.actions.first().descriptor)
        assertTrue(projection.exposesAccessibleName)
        assertFalse(projectNodeSemantics(buildJsonObject {
            put("t", "text")
            put("text", "Visible")
        }).exposesAccessibleName)
    }

    private fun descriptor(name: String): JsonObject = buildJsonObject {
        put("action", name)
    }
}
