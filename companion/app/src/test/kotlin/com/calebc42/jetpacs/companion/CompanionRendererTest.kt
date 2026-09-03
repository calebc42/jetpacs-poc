// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.glasspane.material3.GLASSPANE_MATERIAL3_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_SELECTION_OPTIONS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_TYPED_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignSemanticValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRendererTest {
    @Test
    fun designRuntimeIsDefaultOffWithoutLeakingJetpacsIntoDialogs() {
        val profiles = CompanionRenderer.surfaceProfiles()
        fun extensions(target: String): Set<String> =
            (((profiles.getValue(target) as JsonObject)
                .getValue("extensions") as JsonArray))
                .mapTo(mutableSetOf()) { (it as JsonPrimitive).content }

        assertEquals(
            setOf(GLASSPANE_MATERIAL3_EXTENSION, JETPACS_COMPONENTS_EXTENSION),
            extensions("app"),
        )
        assertFalse(JETPACS_DESIGN_EXTENSION in extensions("app"))
        assertEquals(setOf(GLASSPANE_MATERIAL3_EXTENSION), extensions("dialog"))
        assertFalse(JETPACS_COMPONENTS_EXTENSION in extensions("notification"))
        assertFalse(JETPACS_DESIGN_EXTENSION in extensions("notification"))
    }

    @Test
    fun disabledInstallationRecognizesCachedDesignWithoutOwningOrExecutingIt() {
        val disabled = CompanionRenderer.installation(false)

        assertTrue(disabled.nodeVocabulary.schema.keys.containsAll(JETPACS_DESIGN_NODE_SCHEMA.keys))
        assertFalse(JETPACS_DESIGN_EXTENSION in disabled.nodeVocabulary.extensions)
        assertTrue(disabled.nodeVocabulary.extensionSchemas.isEmpty())
        assertTrue(disabled.nodeVocabulary.extensionSemanticValidators.isEmpty())
        assertFalse(
            disabled.composeConfiguration.extensions.nodeTypes
                .any { it in JETPACS_DESIGN_NODE_SCHEMA },
        )
    }

    @Test
    fun enabledInstallationAddsTypedAdmissionSemanticsAndComposeAsOneUnit() {
        val disabled = CompanionRenderer.installation(false)
        val enabled = CompanionRenderer.installation(true)

        assertEquals(
            disabled.appProfile.nodeTypes + JETPACS_DESIGN_NODE_SCHEMA.keys,
            enabled.appProfile.nodeTypes,
        )
        assertEquals(disabled.appProfile.builtins, enabled.appProfile.builtins)
        assertEquals(disabled.appProfile.features, enabled.appProfile.features)
        assertEquals(
            disabled.appProfile.extensions + JETPACS_DESIGN_EXTENSION,
            enabled.appProfile.extensions,
        )
        assertEquals(
            JETPACS_DESIGN_NODE_SCHEMA.keys,
            enabled.nodeVocabulary.extensions.getValue(JETPACS_DESIGN_EXTENSION),
        )
        assertEquals(
            JETPACS_DESIGN_TYPED_NODE_SCHEMA,
            enabled.nodeVocabulary.extensionSchemas,
        )
        assertEquals(
            JetpacsDesignSemanticValidator,
            enabled.nodeVocabulary.extensionSemanticValidators
                .getValue(JETPACS_DESIGN_EXTENSION),
        )
        assertTrue(
            enabled.composeConfiguration.extensions.nodeTypes
                .containsAll(JETPACS_DESIGN_NODE_SCHEMA.keys),
        )
        assertTrue(
            JETPACS_DESIGN_EXTENSION in enabled.composeConfiguration.appExtensions,
        )
        assertFalse(
            JETPACS_DESIGN_EXTENSION in enabled.composeConfiguration.dialogExtensions,
        )
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
        val designOverrides = CompanionRenderer.installation(designRuntimeEnabled = true)
            .composeConfiguration.canonicalOverrides
        assertEquals(
            setOf(JETPACS_COMPONENTS_EXTENSION, JETPACS_DESIGN_EXTENSION),
            designOverrides.designScopes,
        )
        assertEquals(
            setOf(
                "text", "card", "icon", "icon_button", "badge", "empty_state",
                "button", "chip", "divider", "section_header", "menu", "switch", "collapsible", "month_grid",
                "text_input", "editor",
            ),
            designOverrides.nodeTypesFor(JETPACS_DESIGN_EXTENSION),
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
