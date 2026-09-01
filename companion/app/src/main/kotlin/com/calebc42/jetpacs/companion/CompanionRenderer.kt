// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.glasspane.material3.GLASSPANE_MATERIAL3_AT_LEAST_ONE_NON_EMPTY
import com.calebc42.glasspane.material3.GLASSPANE_MATERIAL3_EXTENSION
import com.calebc42.glasspane.material3.GLASSPANE_MATERIAL3_NODE_SCHEMA
import com.calebc42.glasspane.material3.GLASSPANE_MATERIAL3_STATEFUL_WHEN_PRESENT
import com.calebc42.glasspane.material3.NodeSupport
import com.calebc42.ebp.wire.EBP_NODE_VOCABULARY
import com.calebc42.ebp.wire.NODE_SCHEMA
import com.calebc42.ebp.wire.NodeVocabulary
import com.calebc42.ebp.renderer.compose.ComposeExtensionRegistry
import com.calebc42.ebp.renderer.compose.ComposeCanonicalOverrideRegistry
import com.calebc42.ebp.renderer.compose.ComposeRendererConfiguration
import com.calebc42.ebp.renderer.compose.CoreComposeContribution
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_AT_LEAST_ONE_NON_EMPTY
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_SELECTION_OPTIONS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_STATEFUL_WHEN_PRESENT
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsContribution
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsEditorRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsTextInputRenderer
import com.calebc42.jetpacs.renderer.glance.GlanceRendererContribution
import com.calebc42.ebp.renderer.model.RendererProfile
import com.calebc42.ebp.renderer.model.RendererRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The renderer installation packaged by the Glasspane Companion application.
 *
 * EBP contributes neutral vocabulary, each design module contributes only its
 * generated downstream schema and implemented profile, and this composition
 * root is the sole place that selects the installed combination.
 */
object CompanionRenderer {
    private val extensionOwners = mapOf(
        GLASSPANE_MATERIAL3_EXTENSION to GLASSPANE_MATERIAL3_NODE_SCHEMA.keys,
        JETPACS_COMPONENTS_EXTENSION to JETPACS_COMPONENTS_NODE_SCHEMA.keys,
    )

    private val registry = RendererRegistry(
        listOf(
            CoreComposeContribution,
            NodeSupport.MATERIAL_APP_CONTRIBUTION,
            NodeSupport.MATERIAL_DIALOG_CONTRIBUTION,
            NodeSupport.NOTIFICATION_CONTRIBUTION,
            GlanceRendererContribution,
            JetpacsComponentsContribution,
        ),
        extensionOwners = extensionOwners,
    )

    /** Complete receiver admission vocabulary selected by this application. */
    val NODE_VOCABULARY: NodeVocabulary = EBP_NODE_VOCABULARY.copy(
        schema = NODE_SCHEMA + GLASSPANE_MATERIAL3_NODE_SCHEMA +
            JETPACS_COMPONENTS_NODE_SCHEMA,
        statefulWhenPresent = EBP_NODE_VOCABULARY.statefulWhenPresent +
            GLASSPANE_MATERIAL3_STATEFUL_WHEN_PRESENT +
            JETPACS_COMPONENTS_STATEFUL_WHEN_PRESENT,
        atLeastOneNonEmpty = GLASSPANE_MATERIAL3_AT_LEAST_ONE_NON_EMPTY +
            JETPACS_COMPONENTS_AT_LEAST_ONE_NON_EMPTY,
        selectionOptions = JETPACS_COMPONENTS_SELECTION_OPTIONS,
        trueRequiresParent = JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT,
        extensions = extensionOwners,
    )

    private val appProfile: RendererProfile = registry.profile(
        CoreComposeContribution.id,
        NodeSupport.MATERIAL_APP_CONTRIBUTION.id,
        JetpacsComponentsContribution.id,
    )
    private val dialogProfile: RendererProfile = registry.profile(
        CoreComposeContribution.id,
        NodeSupport.MATERIAL_DIALOG_CONTRIBUTION.id,
    )
    private val notificationProfile: RendererProfile = registry.profile(
        NodeSupport.NOTIFICATION_CONTRIBUTION.id,
    )
    val widgetProfile: RendererProfile = registry.profile(
        GlanceRendererContribution.id,
    )
    // A Quick Settings tile has no Node renderer. Its only executable field
    // is a context-less ActionDescriptor, so the profile advertises exactly
    // the builtins DeviceBridge can execute without a surface or dialog.
    private val tileProfile = RendererProfile(
        nodeTypes = emptySet(),
        builtins = setOf(
            "surface.open",
            "clipboard.copy",
            "companion.settings.open",
            "trigger.fire",
        ),
        features = emptySet(),
        extensions = emptySet(),
    )

    val APP_NODE_TYPES: Set<String> get() = appProfile.nodeTypes
    val DIALOG_NODE_TYPES: Set<String> get() = dialogProfile.nodeTypes
    val NOTIFICATION_NODE_TYPES: Set<String> get() = notificationProfile.nodeTypes
    val APP_BUILTINS: Set<String> get() = appProfile.builtins
    val DIALOG_BUILTINS: Set<String> get() = dialogProfile.builtins
    val NOTIFICATION_BUILTINS: Set<String> get() = notificationProfile.builtins
    val APP_FEATURES: Set<String> get() = appProfile.features
    val DIALOG_FEATURES: Set<String> get() = dialogProfile.features
    val NOTIFICATION_FEATURES: Set<String> get() = notificationProfile.features
    val WIDGET_NODE_TYPES: Set<String> get() = widgetProfile.nodeTypes
    val WIDGET_BUILTINS: Set<String> get() = widgetProfile.builtins
    val WIDGET_FEATURES: Set<String> get() = widgetProfile.features
    val TILE_BUILTINS: Set<String> get() = tileProfile.builtins
    val TILE_FEATURES: Set<String> get() = tileProfile.features

    /** Target profiles advertised in the authenticated EBP welcome. */
    fun surfaceProfiles(): JsonObject = buildJsonObject {
        put("app", appProfile.toJson())
        put("dialog", dialogProfile.toJson())
        put("notification", notificationProfile.toJson())
        put("widget", widgetProfile.toJson())
        put("tile", tileProfile.toJson())
    }

    /** Visual dispatch corresponding exactly to the installed app profile. */
    val composeConfiguration = ComposeRendererConfiguration(
        appNodeTypes = appProfile.nodeTypes,
        dialogNodeTypes = dialogProfile.nodeTypes,
        appExtensions = appProfile.extensions,
        dialogExtensions = dialogProfile.extensions,
        pinnedNodeMembers = JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS,
        extensions = ComposeExtensionRegistry(listOf(JetpacsComponentsRenderer)),
        canonicalOverrides = ComposeCanonicalOverrideRegistry(
            listOf(JetpacsTextInputRenderer, JetpacsEditorRenderer),
        ),
    )
}
