// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion

import com.calebc42.ebp.companion.render.GLASSPANE_MATERIAL3_AT_LEAST_ONE_NON_EMPTY
import com.calebc42.ebp.companion.render.GLASSPANE_MATERIAL3_EXTENSION
import com.calebc42.ebp.companion.render.GLASSPANE_MATERIAL3_NODE_SCHEMA
import com.calebc42.ebp.companion.render.GLASSPANE_MATERIAL3_STATEFUL_WHEN_PRESENT
import com.calebc42.ebp.companion.render.NodeSupport
import com.calebc42.ebp.wire.EBP_NODE_VOCABULARY
import com.calebc42.ebp.wire.NODE_SCHEMA
import com.calebc42.ebp.wire.NodeVocabulary
import com.calebc42.jetpacs.renderer.compose.ComposeExtensionRegistry
import com.calebc42.jetpacs.renderer.compose.ComposeRendererConfiguration
import com.calebc42.jetpacs.renderer.compose.CoreComposeContribution
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_AT_LEAST_ONE_NON_EMPTY
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_STATEFUL_WHEN_PRESENT
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsContribution
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsRenderer
import com.calebc42.jetpacs.renderer.model.RendererProfile
import com.calebc42.jetpacs.renderer.model.RendererRegistry
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

    val APP_NODE_TYPES: Set<String> get() = appProfile.nodeTypes
    val DIALOG_NODE_TYPES: Set<String> get() = dialogProfile.nodeTypes
    val NOTIFICATION_NODE_TYPES: Set<String> get() = notificationProfile.nodeTypes
    val APP_BUILTINS: Set<String> get() = appProfile.builtins
    val DIALOG_BUILTINS: Set<String> get() = dialogProfile.builtins
    val NOTIFICATION_BUILTINS: Set<String> get() = notificationProfile.builtins
    val APP_FEATURES: Set<String> get() = appProfile.features
    val DIALOG_FEATURES: Set<String> get() = dialogProfile.features
    val NOTIFICATION_FEATURES: Set<String> get() = notificationProfile.features

    /** Target profiles advertised in the authenticated EBP welcome. */
    fun surfaceProfiles(): JsonObject = buildJsonObject {
        put("app", appProfile.toJson())
        put("dialog", dialogProfile.toJson())
        put("notification", notificationProfile.toJson())
    }

    /** Visual dispatch corresponding exactly to the installed app profile. */
    val composeConfiguration = ComposeRendererConfiguration(
        appNodeTypes = appProfile.nodeTypes,
        dialogNodeTypes = dialogProfile.nodeTypes,
        extensions = ComposeExtensionRegistry(listOf(JetpacsComponentsRenderer)),
    )
}
