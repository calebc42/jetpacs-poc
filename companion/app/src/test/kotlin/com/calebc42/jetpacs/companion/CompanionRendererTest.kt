// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.jetpacs.material3.JETPACS_MATERIAL3_EXTENSION
import com.calebc42.jetpacs.material3.JETPACS_MATERIAL3_TYPED_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_TYPED_NODE_SCHEMA
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
        val profiles = AndroidCompanionRenderer.surfaceProfiles()
        fun extensions(target: String): Set<String> =
            (((profiles.getValue(target) as JsonObject)
                .getValue("extensions") as JsonArray))
                .mapTo(mutableSetOf()) { (it as JsonPrimitive).content }

        assertEquals(
            setOf(JETPACS_MATERIAL3_EXTENSION, JETPACS_COMPONENTS_EXTENSION),
            extensions("app"),
        )
        assertFalse(JETPACS_DESIGN_EXTENSION in extensions("app"))
        assertEquals(setOf(JETPACS_MATERIAL3_EXTENSION), extensions("dialog"))
        assertFalse(JETPACS_COMPONENTS_EXTENSION in extensions("notification"))
        assertFalse(JETPACS_DESIGN_EXTENSION in extensions("notification"))
    }

    @Test
    fun disabledInstallationRecognizesCachedDesignWithoutOwningOrExecutingIt() {
        val disabled = AndroidCompanionRenderer.installation(false)

        assertTrue(disabled.nodeVocabulary.schema.keys.containsAll(JETPACS_DESIGN_NODE_SCHEMA.keys))
        assertFalse(JETPACS_DESIGN_EXTENSION in disabled.nodeVocabulary.extensions)
        // The Material and components typed schemas are always installed; the
        // design one only when the runtime is enabled.
        assertEquals(
            JETPACS_MATERIAL3_TYPED_NODE_SCHEMA + JETPACS_COMPONENTS_TYPED_NODE_SCHEMA,
            disabled.nodeVocabulary.extensionSchemas,
        )
        assertTrue(disabled.nodeVocabulary.extensionSemanticValidators.isEmpty())
        assertFalse(
            disabled.composeConfiguration.extensions.nodeTypes
                .any { it in JETPACS_DESIGN_NODE_SCHEMA },
        )
    }

    @Test
    fun enabledInstallationAddsTypedAdmissionSemanticsAndComposeAsOneUnit() {
        val disabled = AndroidCompanionRenderer.installation(false)
        val enabled = AndroidCompanionRenderer.installation(true)

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
            JETPACS_MATERIAL3_TYPED_NODE_SCHEMA + JETPACS_COMPONENTS_TYPED_NODE_SCHEMA +
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
        // An app's dialogs wear its design language once the runtime is on,
        // and never before.
        assertTrue(
            JETPACS_DESIGN_EXTENSION in enabled.composeConfiguration.dialogExtensions,
        )
        assertFalse(
            JETPACS_DESIGN_EXTENSION in disabled.composeConfiguration.dialogExtensions,
        )
    }

    @Test
    fun admissionAndComposeDispatchInstallTheSameJetpacsNodes() {
        val owned = JETPACS_COMPONENTS_NODE_SCHEMA.keys

        assertTrue(AndroidCompanionRenderer.APP_NODE_TYPES.containsAll(owned))
        assertEquals(
            owned,
            AndroidCompanionRenderer.NODE_VOCABULARY.extensions
                .getValue(JETPACS_COMPONENTS_EXTENSION),
        )
        assertEquals(
            JETPACS_COMPONENTS_SELECTION_OPTIONS,
            AndroidCompanionRenderer.NODE_VOCABULARY.selectionOptions,
        )
        assertEquals(
            JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT,
            AndroidCompanionRenderer.NODE_VOCABULARY.trueRequiresParent,
        )
        assertEquals(
            JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS,
            AndroidCompanionRenderer.composeConfiguration.pinnedNodeMembers,
        )
        assertEquals(
            mapOf(
                "jetpacs.section_navigator" to "pinned",
                "jetpacs.tabs" to "pinned",
            ),
            JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS,
        )
        assertEquals(owned, AndroidCompanionRenderer.composeConfiguration.extensions.nodeTypes)
        assertEquals(
            setOf(JETPACS_MATERIAL3_EXTENSION, JETPACS_COMPONENTS_EXTENSION),
            AndroidCompanionRenderer.composeConfiguration.appExtensions,
        )
        assertEquals(
            setOf(JETPACS_MATERIAL3_EXTENSION),
            AndroidCompanionRenderer.composeConfiguration.dialogExtensions,
        )
        assertEquals(
            setOf(JETPACS_COMPONENTS_EXTENSION),
            AndroidCompanionRenderer.composeConfiguration.canonicalOverrides.designScopes,
        )
        assertEquals(
            setOf("text_input", "editor"),
            AndroidCompanionRenderer.composeConfiguration.canonicalOverrides
                .nodeTypesFor(JETPACS_COMPONENTS_EXTENSION),
        )
        val designOverrides = AndroidCompanionRenderer.installation(designRuntimeEnabled = true)
            .composeConfiguration.canonicalOverrides
        assertEquals(
            setOf(JETPACS_COMPONENTS_EXTENSION, JETPACS_DESIGN_EXTENSION),
            designOverrides.designScopes,
        )
        assertEquals(
            setOf(
                "text", "rich_text", "chart", "card", "icon", "icon_button", "badge", "empty_state",
                "button", "chip", "divider", "section_header", "menu", "switch", "collapsible", "month_grid",
                "dropdown", "text_input", "editor",
            ),
            designOverrides.nodeTypesFor(JETPACS_DESIGN_EXTENSION),
        )
        assertTrue(owned.none { it in AndroidCompanionRenderer.DIALOG_NODE_TYPES })
    }

    @Test
    fun tileProfileIsNodeLessAndContextless() {
        val profile = AndroidCompanionRenderer.surfaceProfiles().getValue("tile") as JsonObject
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
