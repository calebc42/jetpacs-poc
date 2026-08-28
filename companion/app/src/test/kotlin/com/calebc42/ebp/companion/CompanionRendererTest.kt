// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion

import com.calebc42.ebp.companion.render.GLASSPANE_MATERIAL3_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_NODE_SCHEMA
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
            CompanionRenderer.composeConfiguration.coreOverrides.designScopes,
        )
        assertEquals(
            setOf("text_input"),
            CompanionRenderer.composeConfiguration.coreOverrides
                .nodeTypesFor(JETPACS_COMPONENTS_EXTENSION),
        )
        assertTrue(owned.none { it in CompanionRenderer.DIALOG_NODE_TYPES })
    }
}
