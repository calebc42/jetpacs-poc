// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.render

import com.calebc42.ebp.wire.CORE_NODE_SET
import com.calebc42.ebp.wire.EBP_NODE_VOCABULARY
import com.calebc42.ebp.wire.NODE_SCHEMA
import com.calebc42.ebp.wire.NodeVocabulary
import com.calebc42.jetpacs.renderer.compose.CoreComposeContribution
import com.calebc42.jetpacs.renderer.compose.ComposeRendererConfiguration
import com.calebc42.jetpacs.renderer.model.RendererContribution
import com.calebc42.jetpacs.renderer.model.RendererProfile
import com.calebc42.jetpacs.renderer.model.RendererRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Profiles implemented by the reference Material 3 renderer.
 *
 * This compatibility façade keeps runtime call sites small, but the wire
 * objects are derived by [RendererRegistry] from installed renderer slices.
 * EBP owns only its implementation-neutral vocabulary. Glasspane's generated
 * manifest supplies the optional Material node schemas and ownership at this
 * renderer boundary; neither leaks back into the wire contract.
 */
object NodeSupport {
    private val imageFeatures = setOf("image.https", "image.data")
    private val editorFeatures = setOf("editor.candidate_kind")

    /** Material presentation installed for ordinary app surfaces. */
    val MATERIAL_APP_CONTRIBUTION = RendererContribution(
        id = "material3.app",
        nodeTypes = (NODE_SCHEMA.keys - CORE_NODE_SET) +
            GLASSPANE_MATERIAL3_TARGET_NODE_TYPES.getValue("app"),
        builtins = setOf(
            "view.switch",
            "surface.open",
            "companion.settings.open",
            "clipboard.copy",
            "share.send",
            "trigger.fire",
            "variant.switch",
        ),
        features = imageFeatures + editorFeatures + "action.open_surface",
        extensions = setOf(GLASSPANE_MATERIAL3_EXTENSION),
    )

    /** Material presentation installed for bounded dialog documents. */
    val MATERIAL_DIALOG_CONTRIBUTION = RendererContribution(
        id = "material3.dialog",
        nodeTypes = setOf(
            "rich_text", "icon", "badge", "image", "section_header",
            "empty_state", "progress", "date_stamp", "tooltip", "surface",
            "editor", "icon_button", "chip", "menu",
            "checkbox", "switch", "enum_list", "slider", "date_button",
            "time_button", "navigation_rail",
            "search_bar", "dropdown", "segmented_button",
        ) + GLASSPANE_MATERIAL3_TARGET_NODE_TYPES.getValue("dialog"),
        builtins = setOf("dialog.submit", "dialog.dismiss"),
        features = imageFeatures + editorFeatures,
        extensions = setOf(GLASSPANE_MATERIAL3_EXTENSION),
    )

    /** Foundation subset used by platform notifications. */
    val NOTIFICATION_CONTRIBUTION = RendererContribution(
        id = "compose.notification",
        nodeTypes = setOf("text", "row", "column", "box", "spacer", "divider"),
    )

    private val registry = RendererRegistry(
        listOf(
            CoreComposeContribution,
            MATERIAL_APP_CONTRIBUTION,
            MATERIAL_DIALOG_CONTRIBUTION,
            NOTIFICATION_CONTRIBUTION,
        ),
        extensionOwners = mapOf(
            GLASSPANE_MATERIAL3_EXTENSION to GLASSPANE_MATERIAL3_NODE_SCHEMA.keys,
        ),
    )

    /** Complete admission vocabulary installed by this selected renderer. */
    val NODE_VOCABULARY: NodeVocabulary = EBP_NODE_VOCABULARY.copy(
        schema = NODE_SCHEMA + GLASSPANE_MATERIAL3_NODE_SCHEMA,
        statefulWhenPresent = EBP_NODE_VOCABULARY.statefulWhenPresent +
            GLASSPANE_MATERIAL3_STATEFUL_WHEN_PRESENT,
        atLeastOneNonEmpty = GLASSPANE_MATERIAL3_AT_LEAST_ONE_NON_EMPTY,
        extensions = mapOf(
            GLASSPANE_MATERIAL3_EXTENSION to GLASSPANE_MATERIAL3_NODE_SCHEMA.keys,
        ),
    )

    private val appProfile: RendererProfile =
        registry.profile(CoreComposeContribution.id, MATERIAL_APP_CONTRIBUTION.id)
    private val dialogProfile: RendererProfile =
        registry.profile(CoreComposeContribution.id, MATERIAL_DIALOG_CONTRIBUTION.id)
    private val notificationProfile: RendererProfile =
        registry.profile(NOTIFICATION_CONTRIBUTION.id)

    val APP_NODE_TYPES: Set<String> get() = appProfile.nodeTypes
    val DIALOG_NODE_TYPES: Set<String> get() = dialogProfile.nodeTypes
    val NOTIFICATION_NODE_TYPES: Set<String> get() = notificationProfile.nodeTypes
    val APP_BUILTINS: Set<String> get() = appProfile.builtins
    val DIALOG_BUILTINS: Set<String> get() = dialogProfile.builtins
    val NOTIFICATION_BUILTINS: Set<String> get() = notificationProfile.builtins
    val APP_FEATURES: Set<String> get() = appProfile.features
    val DIALOG_FEATURES: Set<String> get() = dialogProfile.features
    val NOTIFICATION_FEATURES: Set<String> get() = notificationProfile.features
    val APP_EXTENSIONS: Set<String> get() = appProfile.extensions
    val DIALOG_EXTENSIONS: Set<String> get() = dialogProfile.extensions
    val NOTIFICATION_EXTENSIONS: Set<String> get() = notificationProfile.extensions

    /** Default standalone Material dispatch; the app may install more slices. */
    val COMPOSE_CONFIGURATION = ComposeRendererConfiguration(
        appNodeTypes = appProfile.nodeTypes,
        dialogNodeTypes = dialogProfile.nodeTypes,
        appExtensions = appProfile.extensions,
        dialogExtensions = dialogProfile.extensions,
    )

    /** EBP 3 §10.2 profiles, including renderer-extension negotiation. */
    fun surfaceProfiles(): JsonObject = buildJsonObject {
        put("app", appProfile.toJson())
        put("dialog", dialogProfile.toJson())
        put("notification", notificationProfile.toJson())
    }
}
