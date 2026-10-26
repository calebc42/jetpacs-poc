// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import com.calebc42.ebp.renderer.compose.ComposeCanonicalOverrideRegistry
import com.calebc42.ebp.renderer.compose.ComposeExtensionRegistry
import com.calebc42.ebp.renderer.compose.ComposeNodeExtension
import com.calebc42.ebp.renderer.compose.ComposeRendererConfiguration
import com.calebc42.ebp.renderer.compose.CoreComposeContribution
import com.calebc42.ebp.renderer.model.RendererContribution
import com.calebc42.ebp.renderer.model.RendererProfile
import com.calebc42.ebp.renderer.model.RendererRegistry
import com.calebc42.ebp.wire.EBP_NODE_VOCABULARY
import com.calebc42.ebp.wire.NODE_SCHEMA
import com.calebc42.ebp.wire.NodeVocabulary
import com.calebc42.jetpacs.material3.JETPACS_MATERIAL3_AT_LEAST_ONE_NON_EMPTY
import com.calebc42.jetpacs.material3.JETPACS_MATERIAL3_EXTENSION
import com.calebc42.jetpacs.material3.JETPACS_MATERIAL3_NODE_SCHEMA
import com.calebc42.jetpacs.material3.JETPACS_MATERIAL3_STATEFUL_WHEN_PRESENT
import com.calebc42.jetpacs.material3.JETPACS_MATERIAL3_TYPED_NODE_SCHEMA
import com.calebc42.jetpacs.material3.NodeSupport
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_AT_LEAST_ONE_NON_EMPTY
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_SELECTION_OPTIONS
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_STATEFUL_WHEN_PRESENT
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_STATEFUL_WHEN_PRESENT
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_COMPONENTS_TYPED_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_EXTENSION
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JETPACS_DESIGN_TYPED_NODE_SCHEMA
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsContribution
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignCanonicalOverrides
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignContribution
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignDialogContribution
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignSemanticValidator
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsEditorRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsTextInputRenderer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SPEC 14.2: the builtins a host executes itself, outside any renderer. A
 * platform that lacks one of them (a desktop has no share sheet) names the
 * subset it does execute in [PlatformRendererTargets.hostBuiltins], and the
 * renderer installation stops advertising the rest.
 */
val HOST_BUILTINS: Set<String> = setOf(
    "clipboard.copy",
    "share.send",
    "uri.open",
    "surface.open",
    "companion.settings.open",
)

/**
 * The surface targets a platform adds beside the shared app and dialog
 * profiles. Android supplies all three; the desktop supplies none. Every
 * absent target is simply not advertised and never admitted.
 */
data class PlatformRendererTargets(
    /** SPEC 18.5 `notification:*` surfaces, or null where notifications do not exist. */
    val notification: RendererContribution?,
    /** `widget:*` surfaces, or null where home-screen widgets do not exist. */
    val widget: RendererContribution?,
    /** `tile:*` surfaces: a node-less, context-less profile, or null. */
    val tile: RendererProfile?,
    /** The [HOST_BUILTINS] this platform executes. */
    val hostBuiltins: Set<String>,
) {
    init {
        require(hostBuiltins.all { it in HOST_BUILTINS }) {
            "hostBuiltins may name only host builtins: ${hostBuiltins - HOST_BUILTINS}"
        }
    }
}

/** One internally consistent admission, welcome, and Compose installation. */
class CompanionRendererInstallation internal constructor(
    val designRuntimeEnabled: Boolean,
    val appProfile: RendererProfile,
    val surfaceProfiles: JsonObject,
    val nodeVocabulary: NodeVocabulary,
    val composeConfiguration: ComposeRendererConfiguration,
)

/**
 * The renderer installation a Companion composition root packages.
 *
 * EBP contributes neutral vocabulary, each design module contributes only its
 * generated downstream schema and implemented profile, and the composition
 * root is the sole place that selects the installed combination: the shared
 * app and dialog profiles here, plus the platform's own [targets].
 */
open class CompanionRenderer(private val targets: PlatformRendererTargets) {
    private val baseExtensionOwners = mapOf(
        JETPACS_MATERIAL3_EXTENSION to JETPACS_MATERIAL3_NODE_SCHEMA.keys,
        JETPACS_COMPONENTS_EXTENSION to JETPACS_COMPONENTS_NODE_SCHEMA.keys,
    )
    private val allExtensionOwners = baseExtensionOwners +
        (JETPACS_DESIGN_EXTENSION to JETPACS_DESIGN_NODE_SCHEMA.keys)

    private val registry = RendererRegistry(
        listOfNotNull(
            CoreComposeContribution,
            NodeSupport.MATERIAL_APP_CONTRIBUTION,
            NodeSupport.MATERIAL_DIALOG_CONTRIBUTION,
            targets.notification,
            targets.widget,
            JetpacsComponentsContribution,
            JetpacsDesignContribution,
            JetpacsDesignDialogContribution,
        ),
        extensionOwners = allExtensionOwners,
    )

    /** A builtin the platform cannot execute leaves every advertised profile. */
    private val hiddenBuiltins = HOST_BUILTINS - targets.hostBuiltins

    private fun RendererProfile.executable(): RendererProfile =
        if (hiddenBuiltins.isEmpty()) this else copy(builtins = builtins - hiddenBuiltins)

    private val dialogProfile: RendererProfile = registry.profile(
        CoreComposeContribution.id,
        NodeSupport.MATERIAL_DIALOG_CONTRIBUTION.id,
    ).executable()
    val notificationProfile: RendererProfile? =
        targets.notification?.let { registry.profile(it.id).executable() }
    val widgetProfile: RendererProfile? =
        targets.widget?.let { registry.profile(it.id).executable() }
    val tileProfile: RendererProfile? = targets.tile?.executable()

    private fun buildInstallation(designRuntimeEnabled: Boolean): CompanionRendererInstallation {
        val appContributions = buildList {
            add(CoreComposeContribution.id)
            add(NodeSupport.MATERIAL_APP_CONTRIBUTION.id)
            add(JetpacsComponentsContribution.id)
            if (designRuntimeEnabled) add(JetpacsDesignContribution.id)
        }
        val appProfile = registry.profile(*appContributions.toTypedArray()).executable()
        // A dialog an app presents wears the app's design language: with
        // the runtime installed, the dialog target admits the design nodes.
        val dialogProfile = if (designRuntimeEnabled) {
            registry.profile(
                CoreComposeContribution.id,
                NodeSupport.MATERIAL_DIALOG_CONTRIBUTION.id,
                JetpacsDesignDialogContribution.id,
            ).executable()
        } else {
            this.dialogProfile
        }
        val extensionOwners = if (designRuntimeEnabled) {
            allExtensionOwners
        } else {
            baseExtensionOwners
        }
        // The design rows stay known while disabled so cached nodes can walk
        // their conventional children. Ownership, typed semantics, profile
        // advertisement, and executable rendering are installed together.
        val vocabulary = EBP_NODE_VOCABULARY.copy(
            schema = NODE_SCHEMA + JETPACS_MATERIAL3_NODE_SCHEMA +
                JETPACS_COMPONENTS_NODE_SCHEMA + JETPACS_DESIGN_NODE_SCHEMA,
            statefulWhenPresent = EBP_NODE_VOCABULARY.statefulWhenPresent +
                JETPACS_MATERIAL3_STATEFUL_WHEN_PRESENT +
                JETPACS_COMPONENTS_STATEFUL_WHEN_PRESENT +
                JETPACS_DESIGN_STATEFUL_WHEN_PRESENT,
            atLeastOneNonEmpty = JETPACS_MATERIAL3_AT_LEAST_ONE_NON_EMPTY +
                JETPACS_COMPONENTS_AT_LEAST_ONE_NON_EMPTY,
            selectionOptions = JETPACS_COMPONENTS_SELECTION_OPTIONS,
            trueRequiresParent = JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT,
            extensions = extensionOwners,
            extensionSchemas = JETPACS_MATERIAL3_TYPED_NODE_SCHEMA +
                JETPACS_COMPONENTS_TYPED_NODE_SCHEMA + if (designRuntimeEnabled) {
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
        // SPEC 10.2: only the targets this platform has are advertised.
        val profiles = buildJsonObject {
            put("app", appProfile.toJson())
            put("dialog", dialogProfile.toJson())
            notificationProfile?.let { put("notification", it.toJson()) }
            widgetProfile?.let { put("widget", it.toJson()) }
            tileProfile?.let { put("tile", it.toJson()) }
        }
        val extensionRenderers = buildList<ComposeNodeExtension> {
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
            // The design scope re-selects canonical text and the Foundation
            // field/editor for its whole subtree, so authored typography and
            // the text-field/editor slots take effect only when the runtime
            // is installed; the disabled installation keeps Material text.
            canonicalOverrides = ComposeCanonicalOverrideRegistry(
                buildList {
                    add(JetpacsTextInputRenderer)
                    add(JetpacsEditorRenderer)
                    if (designRuntimeEnabled) addAll(JetpacsDesignCanonicalOverrides)
                },
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
    val NOTIFICATION_NODE_TYPES: Set<String> get() = notificationProfile?.nodeTypes.orEmpty()
    val APP_BUILTINS: Set<String> get() = disabledInstallation.appProfile.builtins
    val DIALOG_BUILTINS: Set<String> get() = dialogProfile.builtins
    val NOTIFICATION_BUILTINS: Set<String> get() = notificationProfile?.builtins.orEmpty()
    val APP_FEATURES: Set<String> get() = disabledInstallation.appProfile.features
    val DIALOG_FEATURES: Set<String> get() = dialogProfile.features
    val NOTIFICATION_FEATURES: Set<String> get() = notificationProfile?.features.orEmpty()
    val WIDGET_NODE_TYPES: Set<String> get() = widgetProfile?.nodeTypes.orEmpty()
    val WIDGET_BUILTINS: Set<String> get() = widgetProfile?.builtins.orEmpty()
    val WIDGET_FEATURES: Set<String> get() = widgetProfile?.features.orEmpty()
    val TILE_BUILTINS: Set<String> get() = tileProfile?.builtins.orEmpty()
    val TILE_FEATURES: Set<String> get() = tileProfile?.features.orEmpty()

    /** Target profiles advertised in the authenticated EBP welcome. */
    fun surfaceProfiles(designRuntimeEnabled: Boolean = false): JsonObject =
        installation(designRuntimeEnabled).surfaceProfiles
}
