// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.glasspane.material3.GLASSPANE_MATERIAL3_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_SELECTION_OPTIONS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRendererTest {
    @Test
    fun appInstallsBothDesignExtensionsWithoutLeakingJetpacsIntoDialogs() {
        val profiles = CompanionRenderer.surfaceProfiles()
        fun extensions(target: String): Set<String> =
            (((profiles.getValue(target) as JsonObject)
                .getValue("extensions") as JsonArray))
                .mapTo(mutableSetOf()) { (it as JsonPrimitive).content }

        assertEquals(
            setOf(GLASSPANE_MATERIAL3_EXTENSION, JETPACS_COMPONENTS_EXTENSION),
            extensions("app"),
        )
        assertEquals(setOf(GLASSPANE_MATERIAL3_EXTENSION), extensions("dialog"))
        assertFalse(JETPACS_COMPONENTS_EXTENSION in extensions("notification"))
    }

    @Test
    fun admissionAndComposeDispatchInstallTheSameJetpacsNodes() {
        val owned = JETPACS_COMPONENTS_NODE_SCHEMA.keys

        assertTrue(CompanionRenderer.APP_NODE_TYPES.containsAll(owned))
        assertEquals(
            owned,
            CompanionRenderer.NODE_VOCABULARY.extensions
                .getValue(JETPACS_COMPONENTS_EXTENSION),
        )
        assertEquals(
            JETPACS_COMPONENTS_SELECTION_OPTIONS,
            CompanionRenderer.NODE_VOCABULARY.selectionOptions,
        )
        assertEquals(
            JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT,
            CompanionRenderer.NODE_VOCABULARY.trueRequiresParent,
        )
        assertEquals(
            JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS,
            CompanionRenderer.composeConfiguration.pinnedNodeMembers,
        )
        assertEquals(
            mapOf(
                "jetpacs.section_navigator" to "pinned",
                "jetpacs.tabs" to "pinned",
            ),
            JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS,
        )
        assertEquals(owned, CompanionRenderer.composeConfiguration.extensions.nodeTypes)
        assertEquals(
            setOf(GLASSPANE_MATERIAL3_EXTENSION, JETPACS_COMPONENTS_EXTENSION),
            CompanionRenderer.composeConfiguration.appExtensions,
        )
        assertEquals(
            setOf(GLASSPANE_MATERIAL3_EXTENSION),
            CompanionRenderer.composeConfiguration.dialogExtensions,
        )
        assertEquals(
            setOf(JETPACS_COMPONENTS_EXTENSION),
            CompanionRenderer.composeConfiguration.canonicalOverrides.designScopes,
        )
        assertEquals(
            setOf("text_input", "editor"),
            CompanionRenderer.composeConfiguration.canonicalOverrides
                .nodeTypesFor(JETPACS_COMPONENTS_EXTENSION),
        )
        assertTrue(owned.none { it in CompanionRenderer.DIALOG_NODE_TYPES })
    }

    @Test
    fun tileProfileIsNodeLessAndContextless() {
        val profile = CompanionRenderer.surfaceProfiles().getValue("tile") as JsonObject
        assertEquals(0, (profile.getValue("node_types") as JsonArray).size)
        assertEquals(
            setOf(
                "surface.open",
                "clipboard.copy",
                "companion.settings.open",
                "trigger.fire",
            ),
            (profile.getValue("builtins") as JsonArray)
                .mapTo(mutableSetOf()) { (it as JsonPrimitive).content },
        )
        assertEquals(0, (profile.getValue("features") as JsonArray).size)
        assertEquals(0, (profile.getValue("extensions") as JsonArray).size)
    }
}
