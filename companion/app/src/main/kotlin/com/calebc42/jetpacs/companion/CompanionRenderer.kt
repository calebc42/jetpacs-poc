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
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_TYPED_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsContribution
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignContribution
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignSemanticValidator
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsEditorRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsTextInputRenderer
import com.calebc42.jetpacs.renderer.glance.GlanceRendererContribution
import com.calebc42.ebp.renderer.model.RendererProfile
import com.calebc42.ebp.renderer.model.RendererRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One internally consistent admission, welcome, and Compose installation. */
class CompanionRendererInstallation internal constructor(
    val designRuntimeEnabled: Boolean,
    val appProfile: RendererProfile,
    val surfaceProfiles: JsonObject,
    val nodeVocabulary: NodeVocabulary,
    val composeConfiguration: ComposeRendererConfiguration,
)

/** Renderer selection followed by every cached app-surface composition. */
internal val LocalCompanionRendererConfiguration =
    androidx.compose.runtime.staticCompositionLocalOf {
        CompanionRenderer.composeConfiguration
    }

/**
 * The renderer installation packaged by the Glasspane Companion application.
 *
 * EBP contributes neutral vocabulary, each design module contributes only its
 * generated downstream schema and implemented profile, and this composition
 * root is the sole place that selects the installed combination.
 */
object CompanionRenderer {
    private val baseExtensionOwners = mapOf(
        GLASSPANE_MATERIAL3_EXTENSION to GLASSPANE_MATERIAL3_NODE_SCHEMA.keys,
        JETPACS_COMPONENTS_EXTENSION to JETPACS_COMPONENTS_NODE_SCHEMA.keys,
    )
    private val allExtensionOwners = baseExtensionOwners +
        (JETPACS_DESIGN_EXTENSION to JETPACS_DESIGN_NODE_SCHEMA.keys)

    private val registry = RendererRegistry(
        listOf(
            CoreComposeContribution,
            NodeSupport.MATERIAL_APP_CONTRIBUTION,
            NodeSupport.MATERIAL_DIALOG_CONTRIBUTION,
            NodeSupport.NOTIFICATION_CONTRIBUTION,
            GlanceRendererContribution,
            JetpacsComponentsContribution,
            JetpacsDesignContribution,
        ),
        extensionOwners = allExtensionOwners,
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

    private fun buildInstallation(designRuntimeEnabled: Boolean): CompanionRendererInstallation {
        val appContributions = buildList {
            add(CoreComposeContribution.id)
            add(NodeSupport.MATERIAL_APP_CONTRIBUTION.id)
            add(JetpacsComponentsContribution.id)
            if (designRuntimeEnabled) add(JetpacsDesignContribution.id)
        }
        val appProfile = registry.profile(*appContributions.toTypedArray())
        val extensionOwners = if (designRuntimeEnabled) {
            allExtensionOwners
        } else {
            baseExtensionOwners
        }
        // The design rows stay known while disabled so cached nodes can walk
        // their conventional children. Ownership, typed semantics, profile
        // advertisement, and executable rendering are installed together.
        val vocabulary = EBP_NODE_VOCABULARY.copy(
            schema = NODE_SCHEMA + GLASSPANE_MATERIAL3_NODE_SCHEMA +
                JETPACS_COMPONENTS_NODE_SCHEMA + JETPACS_DESIGN_NODE_SCHEMA,
            statefulWhenPresent = EBP_NODE_VOCABULARY.statefulWhenPresent +
                GLASSPANE_MATERIAL3_STATEFUL_WHEN_PRESENT +
                JETPACS_COMPONENTS_STATEFUL_WHEN_PRESENT,
            atLeastOneNonEmpty = GLASSPANE_MATERIAL3_AT_LEAST_ONE_NON_EMPTY +
                JETPACS_COMPONENTS_AT_LEAST_ONE_NON_EMPTY,
            selectionOptions = JETPACS_COMPONENTS_SELECTION_OPTIONS,
            trueRequiresParent = JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT,
            extensions = extensionOwners,
            extensionSchemas = if (designRuntimeEnabled) {
                JETPACS_DESIGN_TYPED_NODE_SCHEMA
            } else {
                emptyMap()
            },
            extensionSemanticValidators = if (designRuntimeEnabled) {
                mapOf(JETPACS_DESIGN_EXTENSION to JetpacsDesignSemanticValidator)
            } else {
                emptyMap()
            },
        )
        val profiles = buildJsonObject {
            put("app", appProfile.toJson())
            put("dialog", dialogProfile.toJson())
            put("notification", notificationProfile.toJson())
            put("widget", widgetProfile.toJson())
            put("tile", tileProfile.toJson())
        }
        val extensionRenderers = buildList<com.calebc42.ebp.renderer.compose.ComposeNodeExtension> {
            add(JetpacsComponentsRenderer)
            if (designRuntimeEnabled) add(JetpacsDesignRenderer)
        }
        val compose = ComposeRendererConfiguration(
            appNodeTypes = appProfile.nodeTypes,
            dialogNodeTypes = dialogProfile.nodeTypes,
            appExtensions = appProfile.extensions,
            dialogExtensions = dialogProfile.extensions,
            pinnedNodeMembers = JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS,
            extensions = ComposeExtensionRegistry(extensionRenderers),
            canonicalOverrides = ComposeCanonicalOverrideRegistry(
                listOf(JetpacsTextInputRenderer, JetpacsEditorRenderer),
            ),
        )
        return CompanionRendererInstallation(
            designRuntimeEnabled,
            appProfile,
            profiles,
            vocabulary,
            compose,
        )
    }

    private val disabledInstallation = buildInstallation(false)
    private val enabledInstallation = buildInstallation(true)

    /** Return one authoritative installation for the current receiver setting. */
    fun installation(designRuntimeEnabled: Boolean): CompanionRendererInstallation =
        if (designRuntimeEnabled) enabledInstallation else disabledInstallation

    /** Compatibility accessors use the required default-off installation. */
    val NODE_VOCABULARY: NodeVocabulary get() = disabledInstallation.nodeVocabulary
    val composeConfiguration: ComposeRendererConfiguration
        get() = disabledInstallation.composeConfiguration

    val APP_NODE_TYPES: Set<String> get() = disabledInstallation.appProfile.nodeTypes
    val DIALOG_NODE_TYPES: Set<String> get() = dialogProfile.nodeTypes
    val NOTIFICATION_NODE_TYPES: Set<String> get() = notificationProfile.nodeTypes
    val APP_BUILTINS: Set<String> get() = disabledInstallation.appProfile.builtins
    val DIALOG_BUILTINS: Set<String> get() = dialogProfile.builtins
    val NOTIFICATION_BUILTINS: Set<String> get() = notificationProfile.builtins
    val APP_FEATURES: Set<String> get() = disabledInstallation.appProfile.features
    val DIALOG_FEATURES: Set<String> get() = dialogProfile.features
    val NOTIFICATION_FEATURES: Set<String> get() = notificationProfile.features
    val WIDGET_NODE_TYPES: Set<String> get() = widgetProfile.nodeTypes
    val WIDGET_BUILTINS: Set<String> get() = widgetProfile.builtins
    val WIDGET_FEATURES: Set<String> get() = widgetProfile.features
    val TILE_BUILTINS: Set<String> get() = tileProfile.builtins
    val TILE_FEATURES: Set<String> get() = tileProfile.features

    /** Target profiles advertised in the authenticated EBP welcome. */
    fun surfaceProfiles(designRuntimeEnabled: Boolean = false): JsonObject =
        installation(designRuntimeEnabled).surfaceProfiles
}
