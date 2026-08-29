// SPDX-License-Identifier: GPL-3.0-or-later
// The node dispatcher (SPEC 16-17): one flat when-over-type whose case labels
// ARE the renderer's supported set — NodeSupportPinTest source-scans them and
// pins them to NodeSupport.APP_NODE_TYPES, which in turn feeds the advertised
// surface_profiles. §16.5 universal attributes apply once here; §16.1
// presentation identity (key > id > tree path) threads through RenderCtx.path
// and keys all local widget state. A DialogContext (SPEC 18.1) rebinds button
// builtins to dialog completion and text_input edits to dialog-local state.
package com.calebc42.ebp.companion.render

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.companion.MaterialRendererHost
import com.calebc42.jetpacs.renderer.model.CompletionCandidate
import com.calebc42.jetpacs.renderer.model.CandidateDocument
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.jetpacs.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.jetpacs.renderer.compose.ComposeNodeRenderContext
import com.calebc42.jetpacs.renderer.compose.ComposeRendererConfiguration
import com.calebc42.jetpacs.renderer.compose.ebpSemantics
import com.calebc42.jetpacs.renderer.compose.keyboardAction
import com.calebc42.jetpacs.renderer.compose.MaskVisualTransformation
import com.calebc42.jetpacs.renderer.compose.rememberLegacyTextInputAdapter
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.RendererActionContext
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import com.calebc42.jetpacs.renderer.model.RendererActionRequest
import com.calebc42.jetpacs.renderer.model.RendererVolatileSecret
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** §17.6 the body-scroll signal: `button.expanded "auto"` and
 * `scaffold.fab_hide_on_scroll` both derive from whether the scaffold
 * body's scrollable rests at its START — exactly the
 * firstVisibleItemIndex == 0 upstream derives locally — so the signal is
 * device-local state no wire message ever carries. The scaffold provides
 * one around its BODY only; the body's scrolling containers publish into
 * it (last writer wins — the body's primary scrollable is the one that
 * matters), and the fab slot reads it. */
class BodyScrollSignal {
    var atStart by mutableStateOf(true)
}

val LocalBodyScrollSignal =
    androidx.compose.runtime.compositionLocalOf<BodyScrollSignal?> { null }

/** SPEC 18.1: a dialog's local state — its captured field values — and the
 * id needed to complete the outstanding request. Stateful dialog nodes are
 * local and never emit state.changed. */
class DialogContext(
    val dialogId: String,
    // Raw scalar values (string, boolean, number) so §14.6 capture returns
    // each stateful node's logical value, not a stringified one.
    val fields: SnapshotStateMap<String, JsonElement?>,
    val bridge: MaterialRendererHost,
    /** T3/LD-3: the authored values the engine computed while validating this
     * dialog's spec — the layer UNDER the user's edits. */
    val defaults: JsonObject? = null,
) {
    private val volatileFields = LinkedHashMap<String, JsonElement?>()
    private val volatileErasers = LinkedHashMap<String, () -> Unit>()

    /** Keep password state outside the ordinary dialog field map. */
    fun putVolatile(id: String, value: JsonElement?) {
        if ((value as? JsonPrimitive)?.content?.isEmpty() == true) {
            volatileFields.remove(id)
        } else {
            volatileFields[id] = value
        }
    }

    /** Register the native owner used by dialog.submit and lifecycle erasure. */
    fun registerVolatile(id: String, erase: () -> Unit): () -> Unit {
        volatileErasers[id] = erase
        return {
            if (volatileErasers[id] === erase) volatileErasers.remove(id)
            volatileFields.remove(id)
        }
    }

    fun isVolatile(id: String): Boolean = id in volatileErasers

    /** Erase selected dialog secrets without retaining their values. */
    fun eraseVolatile(ids: Set<String>) {
        val erasers = ids.mapNotNull(volatileErasers::get)
        ids.forEach(volatileFields::remove)
        erasers.forEach { it() }
    }

    fun dispose() = eraseVolatile(volatileErasers.keys.toSet())

    /**
     * SPEC 14.1: the logical value of a stateful node at occurrence time —
     * the user's dialog-local edit when there is one, else the authored
     * default.
     *
     * The lookup is a CONTAINMENT test, not `?:`, and that distinction is the
     * whole point: a field the user deliberately cleared holds an empty
     * string, `false`, or `0`, and an elvis would treat those exactly like a
     * field never touched. The old capture had no defaults layer at all and
     * substituted `""`, so an untouched `checkbox` authored `checked: true`
     * shipped the STRING `""` where §14.1 requires boolean `true` — the wrong
     * JSON type, silently, for the commonest dialog shape there is.
     *
     * Emacs's `swap_in_symval_forwarding` is the model: fall back to the
     * default cell, with an explicit `found` bit deciding which layer answers
     * rather than the value's own emptiness.
     */
    fun capture(id: String): JsonElement? = when {
        id in volatileErasers -> volatileFields[id] ?: JsonPrimitive("")
        else -> captureValue(id, fields, defaults)
    }
}

/** The two-layer lookup of [DialogContext.capture], as pure logic so the
 * layering rule is testable without a live bridge. */
fun captureValue(id: String, fields: Map<String, JsonElement?>, defaults: JsonObject?): JsonElement? = when {
    fields.containsKey(id) -> fields[id]
    // C6: the engine writes `put(nodeId, authoredValueOf(node) ?: JsonNull)`,
    // so a stateful node with no authored value is PRESENT and JSON null; the
    // sentinel filter is what turns that into "no default", exactly as the
    // JSONObject.NULL test did.
    defaults != null && id in defaults -> defaults[id]?.takeIf { it !is JsonNull }
    // Neither layer has it. §14.1 makes an unresolvable capture a
    // document-level error the Companion already refused at validation, so
    // reaching here means the spec and this map disagree — surface null
    // rather than inventing a value of the wrong type.
    else -> null
}

/**
 * Everything a node render needs beside the node itself: the surface, the
 * engine seam (neutral action/state/editor hosts on the ebp-dispatch executor),
 * the optional dialog rebinding, and the §16.1 identity path.
 */
@Stable
data class RenderCtx(
    val surface: String,
    val bridge: MaterialRendererHost,
    val dialog: DialogContext? = null,
    val path: String = "",
    /** T3/LD-2: what each of this surface's stateful nodes should display,
     * and the generation that value belongs to. */
    val displays: Map<Pair<String, String>, InputDisplay> = emptyMap(),
    /** The app-selected target profile and downstream renderer installation. */
    val configuration: ComposeRendererConfiguration = NodeSupport.COMPOSE_CONFIGURATION,
    /** Nearest admitted downstream design scope; roots always start unscoped. */
    val designScope: String? = null,
) {
    fun child(node: JsonObject?, index: Int): RenderCtx =
        copy(path = identityPath(path, node, index))

    fun atPath(path: String): RenderCtx = copy(path = path)

    /** Select [scope] only for the recursively rendered authored subtree. */
    fun inDesignScope(scope: String): RenderCtx = copy(designScope = scope)

    /**
     * T3/LD-2: the generation of the value this node's widget should show.
     * It belongs in BOTH the `remember` inputs and the saveable `key`: the
     * inputs make the seeding lambda re-run, and the key stops Compose
     * restoring the value saved under the previous generation — a restore
     * beats changed inputs, so an epoch carried only in the inputs is
     * silently defeated wherever state is saved and restored (a recycled
     * `lazy_column` row, a `HorizontalPager` page, process death).
     */
    fun epochOf(id: String): Long = displays[surface to id]?.epoch ?: 0L

    /**
     * T3/LD-2: the value the STORE says this node holds — the draft when the
     * user has one, else the authored value — or null when the store has no
     * opinion (a dialog, or a node not in an accepted snapshot), in which
     * case the caller falls back to the authored member.
     *
     * The store is the seed authority because it is what `capture_fields`
     * (§14.1) and the welcome `input_state` (§15.1) read. Seeding from the
     * node instead means a widget that is disposed and recomposed while a
     * draft stands — a view switch, a fold, a recycled row — silently
     * reverts to the authored value while the store still holds the user's,
     * which is the LD-2 divergence by a route no epoch can detect, because
     * nothing in the store changed.
     */
    fun storeValue(id: String): JsonElement? = displays[surface to id]?.value

    fun action(descriptor: JsonObject?, value: JsonElement? = null) {
        dispatchAction(descriptor, value)
    }

    fun dispatchAction(
        descriptor: JsonObject?,
        value: JsonElement? = null,
        secret: RendererVolatileSecret? = null,
        sourceId: String? = null,
        onOutcome: (RendererActionOutcome) -> Unit = {},
    ): ActionHandoff {
        descriptor ?: return ActionHandoff.Ignored
        // SPEC 18.1 (T3c, closes LD-1): inside a dialog the dialog.submit /
        // dialog.dismiss builtins complete the outstanding request instead of
        // dispatching remotely — resolved HERE, in the one ordinary dispatch
        // chain (command_remapping's shape: an override is an ordinary
        // binding), so every dispatch site is correct without knowing about
        // dialogs. Before this, only the five button-shaped sites rebound;
        // a text_input Done key, an empty_state tap, a section_header
        // trailing action, or any on_tap inside a dialog document fell into
        // the engine's when with no dialog.submit arm and hung Emacs forever.
        val d = dialog
        if (d != null && "builtin" in descriptor) {
            when (descriptor.stringOr("builtin")) {
                "dialog.submit" -> {
                    val (submittedFields, volatile) =
                        captureDialogFields(d, descriptor, secret)
                    return d.bridge.submitDialog(
                        d.dialogId,
                        if ("value" in descriptor) descriptor["value"] else null,
                        submittedFields.takeIf { volatile == null }
                            ?: JsonObject(emptyMap()),
                        volatile,
                        onOutcome,
                    )
                }
                "dialog.dismiss" -> {
                    d.bridge.dismissDialog(d.dialogId)
                    onOutcome(RendererActionOutcome.LocallyCompleted)
                    return ActionHandoff.HandedOff
                }
            }
        }
        if (d != null && "builtin" !in descriptor) {
            // SPEC 18.1/14.4: a REMOTE descriptor inside a dialog dispatches
            // in DIALOG context, its capture snapshot read from the local
            // field layer exactly like dialog.submit (T3/LD-3).  The generic
            // surface path silently dropped these — the JA-5 device gate's
            // token+confirm Archive and date-pick relay were dead taps.
            val (capturedFields, volatile) =
                captureDialogFields(d, descriptor, secret)
            return d.bridge.dispatch(
                RendererActionRequest(
                    RendererActionContext.Dialog(d.dialogId), descriptor,
                    value = value,
                    fields = capturedFields.takeIf { volatile == null },
                    secret = volatile,
                    sourceId = sourceId,
                ),
                onOutcome,
            )
        }
        return bridge.dispatch(
            RendererActionRequest(
                RendererActionContext.Surface(surface), descriptor,
                value = value, secret = secret, sourceId = sourceId,
            ),
            onOutcome,
        )
    }

    /** §14.3 multi-member hooks (on_reorder, on_add_row/col, swipe sides). */
    fun actionInjecting(descriptor: JsonObject?, injected: JsonObject,
                        value: JsonElement? = null) {
        descriptor?.let {
            bridge.dispatch(RendererActionRequest(
                RendererActionContext.Surface(surface), it,
                value = value, injected = injected,
            ))
        }
    }

    val inDialog: Boolean get() = dialog != null

    fun state(
        id: String,
        value: JsonElement?,
        caret: Int? = null,
        volatileSecret: Boolean = false,
    ) {
        if (dialog != null) {
            if (volatileSecret) dialog.putVolatile(id, value)
            else dialog.fields[id] = value // SPEC 18.1: local only
        }
        else bridge.publishState(surface, id, value, caret)
    }

    private fun captureDialogFields(
        context: DialogContext,
        descriptor: JsonObject,
        supplied: RendererVolatileSecret?,
    ): Pair<JsonObject, RendererVolatileSecret?> {
        val suppliedFields = supplied?.fieldsOrNull()
        val capturedIds = descriptor.arrOrNull("capture_fields")
            ?.mapNotNull { it.strOrNull() }
            .orEmpty()
        val fieldValues = linkedMapOf<String, JsonElement>()
        for (fieldId in capturedIds) {
            fieldValues[fieldId] = suppliedFields?.get(fieldId)
                ?: context.capture(fieldId)
                ?: JsonNull
        }
        // Malformed accepted state still reaches the typed engine guard,
        // which will refuse an ID omitted from capture_fields.
        suppliedFields?.forEach { (fieldId, fieldValue) ->
            fieldValues.putIfAbsent(fieldId, fieldValue)
        }
        val fields = JsonObject(fieldValues)
        val secretIds = capturedIds.filterTo(linkedSetOf(), context::isVolatile)
            .also { supplied?.secretIds?.let(it::addAll) }
        if (secretIds.isEmpty()) return fields to null
        val additionalIds = secretIds - supplied?.secretIds.orEmpty()
        val volatile = supplied?.derive(fields, secretIds) {
            context.eraseVolatile(additionalIds)
        } ?: RendererVolatileSecret(fields, secretIds) {
            context.eraseVolatile(secretIds)
        }
        supplied?.releaseCapturedValues()
        return fields to volatile
    }

    /** §17.7 toolbar `command` -> edit.command with the live editor context. */
    fun editorCommand(document: String, editorId: String, command: String,
                      cursor: Int, selStart: Int, selEnd: Int) =
        bridge.dispatchEditorCommand(
            surface,
            document,
            editorId,
            command,
            cursor,
            selStart,
            selEnd,
        )
}

/** Root entry for a surface (MainActivity). */
@Composable
fun RenderNode(node: JsonObject, surface: String, bridge: MaterialRendererHost,
               dialog: DialogContext? = null,
               configuration: ComposeRendererConfiguration =
                   NodeSupport.COMPOSE_CONFIGURATION) {
    // T3/LD-2: collected once at the root and carried down the tree, so a
    // stateful widget reads its generation and its seed without each one
    // subscribing.
    val displays by bridge.inputDisplays.collectAsState()
    RenderNode(node, RenderCtx(surface, bridge, dialog, displays = displays,
        configuration = configuration))
}

/** Root of a dialog's node tree: owns the local field map (SPEC 18.1). */
@Composable
fun RenderDialogRoot(dialogId: String, spec: JsonObject, bridge: MaterialRendererHost,
                     epoch: Long = 0L,
                     configuration: ComposeRendererConfiguration =
                         NodeSupport.COMPOSE_CONFIGURATION) {
    // D-3(d): keyed on the show EPOCH, not the id alone — a same-id dialog
    // shown while its predecessor is still composed (replace-in-place,
    // SPEC 18.1) mints a FRESH engine-side dialog but inherited the old
    // remember here: the predecessor's typed fields leaked into the
    // successor and the stale defaults layer shadowed the new spec's
    // authored values.  The epoch advances per show
    // (EbpApplication.DialogShow), so identity here follows the engine's,
    // not the id string's.
    val fields = remember(dialogId, epoch) {
        mutableStateMapOf<String, JsonElement?>()
    }
    // T3/LD-3: the authored layer, computed by the engine while it validated
    // this spec — read once per presented dialog, so the two layers can never
    // disagree about which nodes are stateful.
    val defaults = remember(dialogId, epoch) { bridge.dialogDefaults(dialogId) }
    val dialog = remember(dialogId, epoch, bridge, defaults) {
        DialogContext(dialogId, fields, bridge, defaults)
    }
    DisposableEffect(dialog) { onDispose(dialog::dispose) }
    RenderNode(spec, RenderCtx("dialog:$dialogId", bridge, dialog,
        configuration = configuration))
}

/** Adapter that keeps alternate rendering behind the ordinary host paths. */
private open class MaterialNodeRenderContext(
    protected val context: RenderCtx,
) : ComposeNodeRenderContext {
    override val surface: String get() = context.surface
    override val path: String get() = context.path
    override val inDialog: Boolean get() = context.inDialog
    override val maxFieldBytes: Int get() = context.bridge.maxFieldBytes
    override val editorHost: com.calebc42.jetpacs.renderer.model.RendererEditorHost
        get() = context.bridge
    override val volatileSecretRegistryKey: Any? get() = context.dialog

    override fun dispatchAction(
        descriptor: JsonObject?,
        value: JsonElement?,
        secret: RendererVolatileSecret?,
        sourceId: String?,
        onOutcome: (RendererActionOutcome) -> Unit,
    ) = context.dispatchAction(
        descriptor = descriptor,
        value = value,
        secret = secret,
        sourceId = sourceId,
        onOutcome = onOutcome,
    )

    override fun state(id: String, value: JsonElement?, volatileSecret: Boolean) =
        context.state(id, value, volatileSecret = volatileSecret)

    override fun registerVolatileSecret(id: String, erase: () -> Unit): () -> Unit =
        context.dialog?.registerVolatile(id, erase) ?: {}
    override fun storeValue(id: String): JsonElement? = context.storeValue(id)
    override fun epochOf(id: String): Long = context.epochOf(id)

    @Composable
    override fun renderChild(child: JsonObject, index: Int, modifier: Modifier) {
        RenderNode(child, context.child(child, index), modifier)
    }
}

/** Extension adapter whose scoped-child operation can select only its owner. */
private class MaterialExtensionRenderContext(
    context: RenderCtx,
    override val extensionId: String,
) : MaterialNodeRenderContext(context), ComposeExtensionRenderContext {
    @Composable
    override fun renderScopedChild(
        child: JsonObject,
        index: Int,
        modifier: Modifier,
    ) {
        RenderNode(
            child,
            context.inDesignScope(extensionId).child(child, index),
            modifier,
        )
    }
}

@Composable
fun RenderNode(node: JsonObject, ctx: RenderCtx, modifier: Modifier = Modifier) {
    // One movable composition group per complete §16.1 identity. Eager Row /
    // Column loops are positional, so a SaveableStateProvider alone would
    // otherwise restore an inserted/reordered sibling's auto-keyed state into
    // the old slot after variant reactivation. Root calls resolve their path
    // here; every nested caller already carries its parent-resolved path.
    val resolvedPath = ctx.path.ifEmpty { identityPath("", node, 0) }
    key(resolvedPath) {
        val retained = LocalRetainedSaveableScope.current
        val providerPath = retained?.providerPath(node)
        if (retained != null && providerPath != null) {
            // Each retained presentation-state owner has its own registry.
            // The host can therefore retire a removed/type-changed inactive
            // owner without clearing saveable state from surviving siblings.
            retained.holder.SaveableStateProvider(providerPath) {
                RenderNodeContent(node, ctx.atPath(resolvedPath), modifier)
            }
        } else {
            RenderNodeContent(node, ctx.atPath(resolvedPath), modifier)
        }
    }
}



@Composable
private fun RenderNodeContent(node: JsonObject, ctx: RenderCtx,
                              modifier: Modifier = Modifier) {
    val type = node.stringOr("t")
    // SPEC 17.1/16.2: a type not advertised for THIS target (a dialog advertises
    // fewer than the app profile) is unsupported — degrade to its children as a
    // neutral column, never render its semantics or dispatch its actions.
    val advertised = if (ctx.inDialog) {
        ctx.configuration.dialogNodeTypes
    } else {
        ctx.configuration.appNodeTypes
    }
    if (type !in advertised) {
        node.arrOrNull("children")?.let { kids ->
            Column(modifier) { RenderChildren(kids, ctx) }
        }
        return
    }

    val universalModifier = modifier.universal(node)
    // A collapsible's header owns its interaction and receives the projection
    // with live expanded state in RenderCollapsible. Every other node owns the
    // bounds represented by this top-level modifier.
    val m = if (type == "collapsible") {
        universalModifier
    } else {
        universalModifier.ebpSemantics(
            node = node,
            onAction = { ctx.action(it) },
        )
    }
    val extension = ctx.configuration.extensions.rendererFor(type)
    if (extension != null) {
        extension.render(
            node,
            MaterialExtensionRenderContext(ctx, extension.extensionId),
            m,
        )
        return
    }
    val canonicalOverride = ctx.designScope
        ?.takeIf { ctx.configuration.admitsDesignScope(it, ctx.inDialog) }
        ?.let { ctx.configuration.canonicalOverrides.rendererFor(it, type) }
        ?.takeIf { it.appliesTo(node) }
    if (canonicalOverride != null) {
        canonicalOverride.render(node, MaterialNodeRenderContext(ctx), m)
        return
    }
    when (type) {
        "text" -> RenderText(node, m)
        "rich_text" -> RenderRichText(node, ctx, m)
        "icon" -> RenderIcon(node, m)
        "badge" -> RenderBadge(node, ctx, m)
        "tooltip" -> RenderTooltip(node, ctx, m)
        "image" -> RenderImage(node, m)
        "section_header" -> RenderSectionHeader(node, ctx, m)
        "empty_state" -> RenderEmptyState(node, ctx, m)
        "progress" -> RenderProgress(node, m)
        "date_stamp" -> RenderDateStamp(node, m)
        "row" -> RenderRow(node, ctx, m)
        "column" -> RenderColumn(node, ctx, m)
        "flow_row" -> RenderFlowRow(node, ctx, m)
        "box" -> RenderBox(node, ctx, m)
        "surface" -> RenderSurfaceNode(node, ctx, m)
        "lazy_column" -> RenderLazyColumn(node, ctx, m)
        "variant_host" -> RenderVariantHost(node, ctx, m)
        "card" -> RenderCard(node, ctx, m)
        "collapsible" -> RenderCollapsible(node, ctx, m)
        "tabs" -> RenderTabs(node, ctx, m)
        "table" -> RenderTable(node, ctx, m)
        "pane_scaffold" -> RenderPaneScaffold(node, ctx, m)
        "reorderable_list" -> RenderReorderableList(node, ctx, m)
        "chart" -> RenderChart(node, ctx, m)
        "canvas" -> RenderCanvas(node, m)
        "month_grid" -> RenderMonthGrid(node, ctx, m)
        "spacer" -> Spacer(m
            .width((safeDp(node.doubleOr("width", 0.0)) ?: 0f).dp)
            .height((safeDp(node.doubleOr("height", 0.0)) ?: 0f).dp))
        "divider" -> HorizontalDivider(modifier = m)
        "scaffold" -> RenderScaffold(node, ctx, m)
        "editor" -> RenderEditor(node, ctx, m)
        "button" -> RenderButton(node, ctx, m)
        "icon_button" -> RenderIconButton(node, ctx, m)
        "material3.split_button" -> RenderSplitButton(node, ctx, m)
        "navigation_rail" -> RenderNavigationRail(node, ctx, m)
        "search_bar" -> RenderSearchBar(node, ctx, m)
        "dropdown" -> RenderDropdown(node, ctx, m)
        "segmented_button" -> RenderSegmentedButton(node, ctx, m)
        "material3.app_bar_row" -> RenderAppBarStrip(node, ctx, m, vertical = false)
        "material3.app_bar_column" -> RenderAppBarStrip(node, ctx, m, vertical = true)
        "carousel" -> RenderCarousel(node, ctx, m)
        "material3.fab_menu" -> RenderFabMenu(node, ctx, m)
        "button_group" -> RenderButtonGroup(node, ctx, m)
        "lazy_grid" -> RenderLazyGrid(node, ctx, m)
        "chip" -> RenderChip(node, ctx, m)
        "material3.assist_chip" -> RenderAssistChip(node, ctx, m)
        "menu" -> RenderMenu(node, ctx, m)
        "checkbox" -> RenderCheckbox(node, ctx, m)
        "switch" -> RenderSwitch(node, ctx, m)
        "enum_list" -> RenderEnumList(node, ctx, m)
        "slider" -> RenderSlider(node, ctx, m)
        "date_button" -> RenderDateButton(node, ctx, m)
        "time_button" -> RenderTimeButton(node, ctx, m)
        "text_input" -> RenderTextInput(node, ctx, m)
        else ->
            // SPEC 16.2: unknown types render children as a neutral
            // vertical sequence, or nothing.
            node.arrOrNull("children")?.let { children ->
                Column(modifier = m) { RenderColumnChildren(children, ctx) }
            }
    }
}

/**
 * Restart/skipping boundary for one visible lazy item. [LazyRenderItem] is an
 * immutable value projection, so a complete new SurfaceSpec need not
 * recompose a row whose authored presentation is structurally unchanged.
 */
@Composable
internal fun RenderLazyItem(item: LazyRenderItem, ctx: RenderCtx) {
    item.node?.let { RenderNode(it, ctx.atPath(item.path)) }
}

/**
 * One bounded LazyColumn subcomposition. Individual row restart boundaries
 * remain inside it, so an updated chunk can still skip every unchanged row.
 */
@Composable
internal fun RenderLazyChunk(
    chunk: LazyRenderChunk,
    ctx: RenderCtx,
    itemSpacing: androidx.compose.ui.unit.Dp,
    onItemPositioned: (String, Int) -> Unit = { _, _ -> },
) {
    val only = chunk.items.singleOrNull()
    if (only != null) {
        Box(Modifier.onGloballyPositioned {
            onItemPositioned(only.key, 0)
        }) {
            RenderLazyItem(only, ctx)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
        for (item in chunk.items) {
            androidx.compose.runtime.key(item.key) {
                Box(Modifier.onGloballyPositioned { coordinates ->
                    onItemPositioned(
                        item.key,
                        coordinates.positionInParent().y.roundToInt(),
                    )
                }) {
                    RenderLazyItem(item, ctx)
                }
            }
        }
    }
}

// ------------------------------------------------------------ children

@Composable
fun RenderChildren(children: JsonArray?, ctx: RenderCtx) {
    if (children == null) return
    for (i in 0 until children.size) {
        (children[i] as? JsonObject)?.let { RenderNode(it, ctx.child(it, i)) }
    }
}

// SPEC 16.5: `weight` distributes remaining main-axis space — it needs the
// Row/Column scope, so the container cases route through these. `align_self`
// (start|center|end|stretch, SPEC 16.5) overrides the parent's CROSS-axis
// alignment and needs the same scope: in a Row the cross axis is vertical, in
// a Column it is horizontal. Attributes.kt has always claimed the containers
// applied it; they did not, so it was a declared, elisp-validated, never-read
// member — the `badge ""` defect class.
private fun weightOf(node: JsonObject): Float? =
    node.doubleOr("weight", 0.0).toFloat().takeIf { it.isFinite() && it > 0f }

@Composable
fun RowScope.RenderRowChildren(children: JsonArray?, ctx: RenderCtx) {
    if (children == null) return
    for (i in 0 until children.size) {
        val child = children[i] as? JsonObject ?: continue
        var m = weightOf(child)?.let { Modifier.weight(it) } ?: Modifier
        m = when (child.stringOr("align_self")) {
            "start" -> m.align(Alignment.Top)
            "center" -> m.align(Alignment.CenterVertically)
            "end" -> m.align(Alignment.Bottom)
            "stretch" -> m.fillMaxHeight()
            else -> m
        }
        RenderNode(child, ctx.child(child, i), m)
    }
}

@Composable
fun ColumnScope.RenderColumnChildren(children: JsonArray?, ctx: RenderCtx) {
    if (children == null) return
    for (i in 0 until children.size) {
        val child = children[i] as? JsonObject ?: continue
        var m = weightOf(child)?.let { Modifier.weight(it) } ?: Modifier
        m = when (child.stringOr("align_self")) {
            "start" -> m.align(Alignment.Start)
            "center" -> m.align(Alignment.CenterHorizontally)
            "end" -> m.align(Alignment.End)
            "stretch" -> m.fillMaxWidth()
            else -> m
        }
        RenderNode(child, ctx.child(child, i), m)
    }
}

// ------------------------------------------------------------ input nodes

@Composable
private fun RenderTextInput(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val binding = com.calebc42.jetpacs.renderer.compose.rememberTextInputBinding(
        node,
        MaterialNodeRenderContext(ctx),
    )
    val presentation = binding.presentation
    val controller = binding.controller

    // Output-only presentation never changes the logical controller value.
    val language = presentation.syntax.orEmpty()
    val syntaxColors = LocalSyntaxColors.current
    val maskSpec = presentation.mask.orEmpty()
    val outputTransformation = remember(language, syntaxColors) {
        language.takeIf { it.isNotEmpty() }
            ?.let { SyntaxOutputTransformation(it, syntaxColors) }
    }
    val isError = presentation.isError
    val supporting = presentation.supportingText.orEmpty()
    val leadingName = presentation.leadingIcon.orEmpty()
    val trailingName = presentation.trailingIcon.orEmpty()
    val prefixText = presentation.prefix.orEmpty()
    val suffixText = presentation.suffix.orEmpty()
    // §17.4 shared slots. M3 measures the helper line to the FIELD's own
    // width and tints it from (enabled, isError, focused), which is exactly
    // why a sibling `text` node underneath is not a substitute.
    val labelSlot:
        (@Composable androidx.compose.material3.TextFieldLabelScope.() -> Unit)? =
        presentation.label?.let { { Text(it) } }
    val legacyLabelSlot: (@Composable () -> Unit)? =
        presentation.label?.let { { Text(it) } }
    val placeholderSlot: (@Composable () -> Unit)? = presentation.hint
        ?.let { { Text(it) } }
    val supportingSlot: (@Composable () -> Unit)? =
        supporting.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
    val leadingSlot: (@Composable () -> Unit)? =
        leadingName.takeIf { it.isNotEmpty() }?.let { { Icon(IconMap.get(it), null) } }
    val trailingSlot: (@Composable () -> Unit)? =
        trailingName.takeIf { it.isNotEmpty() }?.let { { Icon(IconMap.get(it), null) } }
    val prefixSlot: (@Composable () -> Unit)? =
        prefixText.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
    val suffixSlot: (@Composable () -> Unit)? =
        suffixText.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
    val keyboardController = LocalSoftwareKeyboardController.current
    val onKeyboardAction:
        androidx.compose.foundation.text.input.KeyboardActionHandler =
        binding.keyboardAction { keyboardController?.hide() }
    val enabled = binding.enabled
    val filled = presentation.variant == "filled"
    val padding = presentation.contentPadding?.let { PaddingValues(it) } ?: if (filled) {
        if (labelSlot == null) TextFieldDefaults.contentPaddingWithoutLabel()
        else TextFieldDefaults.contentPaddingWithLabel()
    } else {
        if (labelSlot == null) OutlinedTextFieldDefaults.contentPaddingWithoutLabel()
        else OutlinedTextFieldDefaults.contentPaddingWithLabel()
    }

    val fieldModifier = binding.fieldModifier(m)
    if (presentation.password) {
        if (filled) {
                androidx.compose.material3.SecureTextField(
                    state = controller.state,
                    modifier = fieldModifier,
                    enabled = enabled,
                    label = labelSlot,
                    placeholder = placeholderSlot,
                    leadingIcon = leadingSlot,
                    trailingIcon = trailingSlot,
                    prefix = prefixSlot,
                    suffix = suffixSlot,
                    supportingText = supportingSlot,
                    isError = isError,
                    inputTransformation = controller.inputTransformation,
                    textObfuscationMode =
                        androidx.compose.foundation.text.input.TextObfuscationMode.Hidden,
                    keyboardOptions = presentation.keyboardOptions,
                    onKeyboardAction = onKeyboardAction,
                    contentPadding = padding,
                )
        } else {
                androidx.compose.material3.OutlinedSecureTextField(
                    state = controller.state,
                    modifier = fieldModifier,
                    enabled = enabled,
                    label = labelSlot,
                    placeholder = placeholderSlot,
                    leadingIcon = leadingSlot,
                    trailingIcon = trailingSlot,
                    prefix = prefixSlot,
                    suffix = suffixSlot,
                    supportingText = supportingSlot,
                    isError = isError,
                    inputTransformation = controller.inputTransformation,
                    textObfuscationMode =
                        androidx.compose.foundation.text.input.TextObfuscationMode.Hidden,
                    keyboardOptions = presentation.keyboardOptions,
                    onKeyboardAction = onKeyboardAction,
                    contentPadding = padding,
                )
        }
    } else if (maskSpec.isNotEmpty()) {
        val adapter = rememberLegacyTextInputAdapter(controller)
        val maskTransformation = remember(maskSpec) { MaskVisualTransformation(maskSpec) }
        LegacyMaskedMaterialTextField(
            value = adapter.value,
            onValueChange = adapter::onValueChange,
            visualTransformation = maskTransformation,
            modifier = fieldModifier,
            filled = filled,
            enabled = enabled,
            label = legacyLabelSlot,
            placeholder = placeholderSlot,
            leadingIcon = leadingSlot,
            trailingIcon = trailingSlot,
            prefix = prefixSlot,
            suffix = suffixSlot,
            supportingText = supportingSlot,
            isError = isError,
            keyboardOptions = presentation.keyboardOptions,
            keyboardActions = KeyboardActions(onDone = {
                if (binding.submit() == ActionHandoff.HandedOff &&
                    presentation.hideKeyboardOnSubmit
                ) {
                    keyboardController?.hide()
                }
            }),
            lineLimits = presentation.lineLimits,
            contentPadding = padding,
        )
    } else if (filled) {
        TextField(
            state = controller.state,
            modifier = fieldModifier,
            enabled = enabled,
            label = labelSlot,
            placeholder = placeholderSlot,
            leadingIcon = leadingSlot,
            trailingIcon = trailingSlot,
            prefix = prefixSlot,
            suffix = suffixSlot,
            supportingText = supportingSlot,
            isError = isError,
            inputTransformation = controller.inputTransformation,
            outputTransformation = outputTransformation,
            keyboardOptions = presentation.keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = presentation.lineLimits,
            contentPadding = padding,
        )
    } else {
        OutlinedTextField(
            state = controller.state,
            modifier = fieldModifier,
            enabled = enabled,
            label = labelSlot,
            placeholder = placeholderSlot,
            leadingIcon = leadingSlot,
            trailingIcon = trailingSlot,
            prefix = prefixSlot,
            suffix = suffixSlot,
            supportingText = supportingSlot,
            isError = isError,
            inputTransformation = controller.inputTransformation,
            outputTransformation = outputTransformation,
            keyboardOptions = presentation.keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = presentation.lineLimits,
            contentPadding = padding,
        )
    }
}

/** Legacy value-based Material field with a linear explicit mask mapping. */
@Composable
private fun LegacyMaskedMaterialTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    visualTransformation: VisualTransformation,
    modifier: Modifier,
    filled: Boolean,
    enabled: Boolean,
    label: (@Composable () -> Unit)?,
    placeholder: (@Composable () -> Unit)?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    prefix: (@Composable () -> Unit)?,
    suffix: (@Composable () -> Unit)?,
    supportingText: (@Composable () -> Unit)?,
    isError: Boolean,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions,
    keyboardActions: KeyboardActions,
    lineLimits: androidx.compose.foundation.text.input.TextFieldLineLimits,
    contentPadding: PaddingValues,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = if (filled) TextFieldDefaults.colors() else OutlinedTextFieldDefaults.colors()
    val textColor = colors.textColor(enabled, isError, focused)
    val multiLine = lineLimits as?
        androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine
    val singleLine = lineLimits ==
        androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(
            minWidth = if (filled) TextFieldDefaults.MinWidth
            else OutlinedTextFieldDefaults.MinWidth,
            minHeight = if (filled) TextFieldDefaults.MinHeight
            else OutlinedTextFieldDefaults.MinHeight,
        ),
        enabled = enabled,
        readOnly = false,
        textStyle = LocalTextStyle.current.copy(color = textColor),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        minLines = multiLine?.minHeightInLines ?: 1,
        maxLines = multiLine?.maxHeightInLines ?: 1,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(colors.cursorColor(isError)),
        decorationBox = { innerTextField ->
            if (filled) {
                TextFieldDefaults.DecorationBox(
                    value = value.text,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    isError = isError,
                    label = label,
                    placeholder = placeholder,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    prefix = prefix,
                    suffix = suffix,
                    supportingText = supportingText,
                    colors = colors,
                    contentPadding = contentPadding,
                )
            } else {
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value.text,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    isError = isError,
                    label = label,
                    placeholder = placeholder,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    prefix = prefix,
                    suffix = suffix,
                    supportingText = supportingText,
                    colors = colors,
                    contentPadding = contentPadding,
                )
            }
        },
    )
}

// The @OptIn is combinedClickable's (the completion rows' long-press);
// a long-press-ONLY affordance with no visual cue makes onLongClickLabel
// its entire TalkBack surface, so the label is not optional decoration.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RenderEditor(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val binding = com.calebc42.jetpacs.renderer.compose.rememberEditorBinding(
        node,
        MaterialNodeRenderContext(ctx),
    )
    val presentation = binding.presentation
    val id = presentation.id
    val document = presentation.document.orEmpty()
    val readOnly = presentation.readOnly
    val enabled = presentation.enabled
    val onSave = presentation.onSave
    val wantsCompletion = presentation.complete
    val controller = binding.controller
    val value = controller.snapshot()

    // Material owns palette and decoration; the shared controller owns text.
    val language = presentation.syntax.orEmpty()
    val syntaxColors = LocalSyntaxColors.current
    val annotations = if (document.isEmpty()) null else {
        val all by ctx.bridge.editorAnnotations.collectAsState()
        all[document to id]
    }
    val diagColors = DiagnosticColors(
        error = MaterialTheme.colorScheme.error,
        warning = Color(0xFFC08A00),
        info = MaterialTheme.colorScheme.primary,
        hint = MaterialTheme.colorScheme.outline)
    val outputTransformation = remember(
        language,
        syntaxColors,
        diagColors,
        annotations?.epoch,
    ) {
        if (annotations != null) {
            AnnotationOutputTransformation(
                annotations.fontify,
                annotations.diagnostics,
                language,
                syntaxColors,
                diagColors,
            )
        } else if (language.isEmpty()) {
            null
        } else {
            SyntaxOutputTransformation(language, syntaxColors)
        }
    }
    val offers by ctx.bridge.completionOffers.collectAsState()
    val offer = if (wantsCompletion) offers[document to id] else null
    val offerViewMap by ctx.bridge.completionOfferViews.collectAsState()
    // R5 (amendment #172): the lazily fetched candidate doc, observed
    // like the offer views beside it.
    val candidateDocs by ctx.bridge.candidateDocuments.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(modifier = m) {
        // SPEC 17.7: the toolbar rail above the field. `command` is valid only
        // for a synchronized editor (document present) in READY — the wire side
        // enforces the session/state gate; a local editor's command no-ops.
        presentation.toolbar?.let { items ->
            EditorToolbar(
                items = items,
                enabled = binding.interactive, // §17.4: disabled/read-only inert
                value = {
                    TextFieldValue(
                        value.text,
                        TextRange(
                            value.selectionStartUtf16,
                            value.selectionEndUtf16,
                        ),
                    )
                },
                onValueChange = {
                    controller.applyPresentationEdit(
                        it.text,
                        it.selection.start,
                        it.selection.end,
                    )
                },
                dispatch = { ctx.action(it) },
                onCommand = { command ->
                    // T2/LD-4: cursor is the ACTIVE selection end (where the
                    // caret is); a backward drag has start > end and the
                    // engine orders the pair during scalar conversion.
                    if (document.isNotEmpty())
                        ctx.editorCommand(document, id, command,
                            value.selectionEndUtf16,
                            value.selectionStartUtf16,
                            value.selectionEndUtf16)
                },
                localDate = ::localDateStamp,
                localTime = ::localTimeStamp)
        }
        // SPEC 17.4 `on_enter`: dispatches the descriptor with the full editor
        // value (§14.3, the same injection `on_save` uses). The IME action is
        // gated on the member being PRESENT: an editor is multi-line by
        // default, and ImeAction.Done replaces the soft keyboard's newline
        // key — so a file editor that authors no `on_enter` must keep Default
        // and its Return key. A single-line picker is still submitted by its
        // dialog button unless it explicitly authors `on_enter`.
        OutlinedTextField(
            state = controller.state,
            readOnly = readOnly,
            enabled = enabled,
            inputTransformation = controller.inputTransformation,
            outputTransformation = outputTransformation,
            lineLimits = presentation.lineLimits,
            keyboardOptions = presentation.keyboardOptions,
            onKeyboardAction =
                androidx.compose.foundation.text.input.KeyboardActionHandler {
                    binding.enter()
                },
            // A code editor that renders in the body font undermines every
            // fontify run Emacs sends: alignment is half of what font-lock
            // communicates. POC 1's editor was monospaced; this one was not.
            textStyle = LocalTextStyle.current.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            modifier = binding.fieldModifier(Modifier.fillMaxWidth()))
        // SPEC 19.5: the doc line, between the field and the keyboard. A
        // diagnostic under the caret WINS — a user who moved onto a squiggle
        // is asking what is wrong there — and eldoc answers otherwise. Shown
        // only for a collapsed caret, because a selection drag is not a
        // question about a position. A plain row rather than a popup, for the
        // reason the completion rows below record.
        if (document.isNotEmpty() &&
            value.selectionStartUtf16 == value.selectionEndUtf16
        ) {
            val caretDiag = com.calebc42.jetpacs.renderer.model.diagnosticAt(
                annotations?.diagnostics,
                value.text,
                value.selectionStartUtf16,
            )
            val eldoc = annotations?.eldoc?.text?.takeIf { it.isNotEmpty() }
            if (caretDiag != null || eldoc != null) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (caretDiag != null) {
                        Text("●",
                            color = diagColors.forSeverity(caretDiag.severity),
                            style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(6.dp))
                        Text(caretDiag.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    } else {
                        Text(eldoc!!,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily =
                                    androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // SPEC 17.4 `on_save`: the save affordance for a value+on_save
        // editor — dispatches the descriptor with the LIVE text injected
        // as `value` (§14.3, the same injection as text_input's
        // on_submit). Missing from the first shipped renderer: the
        // member was in the contract, the validator, and the elisp
        // goldens, and no chrome ever dispatched it — found by the JA-6
        // device gate when a hardware save had nothing to tap.
        if (onSave != null) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = { binding.save() },
                    // §17.4: a read-only or disabled editor MUST NOT
                    // dispatch — same rule the commit path pins.
                    enabled = binding.interactive) {
                    Icon(IconMap.get("save"), contentDescription = "Save")
                }
            }
        }
        // SPEC 19.3 (JC-4b): the candidate list. Plain rows rather than a
        // floating DropdownMenu: this must work inside a dialog whose host
        // container scrolls, and a popup anchored to a field inside a
        // scrolling column drifts away from it.
        // Amendment #171: display narrowing SHOULD match the emission
        // predicate - showing what cannot be accepted is a lie of
        // presentation, and so is hiding what CAN be: a pristine offer is
        // the base path (no predicate at emission - Emacs tables are not
        // prefix engines), so it displays unfiltered. The emit-time
        // re-proof in the engine remains the normative gate either way.
        // The view is OBSERVED state the bridge publishes after each
        // tracker mutation - never a synchronous engine read from the
        // composition (the monitor is held across socket writes).
        val offerView = if (offer != null && document.isNotEmpty())
            offerViewMap[document to id] else null
        if (offer != null && offerView != null) {
            // R5: rows keep their WIRE index through filter + take —
            // list position == wire position holds because the engine
            // discards invalid replies whole — so a long-press names the
            // candidate Emacs retained at that index, never the row's
            // screen slot after narrowing shifted it.
            val visible = narrowedWithWireIndex(
                offer.candidates, offerView.active, offerView.extendedPrefix,
                offerView.ext, ctx.bridge.completionNarrowing)
                .take(MAX_VISIBLE_COMPLETIONS)
            visible.forEach { (wireIndex, cand) ->
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                ctx.bridge.selectEditorCompletion(
                                    document, id, cand.label, cand.insert)
                            },
                            // R5 (amendment #172): docs for the row, on
                            // demand — the method exists to be lazy. The
                            // haptic is the EditorToolbar/LayoutNodes
                            // precedent; the label is the affordance's
                            // whole TalkBack surface. A drag cancels the
                            // press, so scroll and long-press coexist.
                            onLongClickLabel = "Show documentation",
                            onLongClick = {
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback
                                        .HapticFeedbackType.LongPress)
                                ctx.bridge.requestCandidateDocument(
                                    document, id, wireIndex, offer.epoch)
                            })
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Amendment #169: the kind icon, through an EXPLICIT map -
                    // IconMap.get answers unknowns with a placeholder, and the
                    // SPEC's degrade for an unrecognized kind is NO decoration.
                    completionKindIcon(cand.kind)?.let { iconName ->
                        Icon(IconMap.get(iconName), contentDescription = cand.kind,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(cand.label, style = MaterialTheme.typography.bodyMedium)
                    cand.annotation?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // R5 (amendment #172): the doc panel — local chrome in the
            // eldoc-row idiom, never a floating popup (the rows' own
            // rationale above). THREE show conditions, all required: the
            // published doc belongs to THIS offer (epoch — a stale fetch
            // or a fresh offer must not pair), its row is still in the
            // VISIBLE narrowed set (an offer survives a qualifying
            // extension without a new epoch, so narrowing can drop the
            // documented row while the doc stays published — showing it
            // then is the lie of presentation the narrowing rationale
            // forbids), and it is non-empty (the eldoc row's own takeIf
            // guard: "" is the universal degradation arm — every picker
            // candidate, timeout, and failure — not a rare case).
            // Rendered verbatim per §16.4: raw markdown punctuation from
            // a markdown-mode-less device Emacs displays as typed.
            val doc = candidateDocs[document to id]
            if (doc != null && candidateDocVisible(doc, offer.epoch,
                    visible.map { it.index })) {
                Text(doc.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily =
                            androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        // ~8 bodySmall lines (16sp line height), bounded:
                        // a frame budget, not a reading pane — SPEC 19.3
                        // caps the doc itself at 16384 octets.
                        .heightIn(max = 128.dp)
                        // Keyed on the documented row (R5 review): a
                        // plain rememberScrollState survives doc
                        // replacement, rendering a fresh candidate's
                        // doc pre-scrolled to the previous one's offset.
                        .verticalScroll(remember(doc.index, doc.epoch) {
                            androidx.compose.foundation.ScrollState(0)
                        })
                        .padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
}

/** Amendment #171 display narrowing with WIRE indices preserved (R5): a
 * row that survives filter keeps its ORIGINAL index into the offer's
 * candidate list, because `edit.candidate.doc` names candidates by wire
 * position and narrowing must not renumber them under the user's
 * finger. A pristine offer (empty ext) is the base path and displays
 * unfiltered — the predicate never applies to it (the emit-time re-proof
 * in the engine stays the normative gate either way). */
/** R5's three-condition show gate for the doc panel, pure so each
 * condition is a killable mutant (no Compose test rig exists here): the
 * doc belongs to the CURRENT offer epoch, its row is still among the
 * VISIBLE wire indices after narrowing, and it is non-empty — "" is the
 * universal degradation arm (every picker candidate, word-fallback
 * candidate, timeout, latch collision, and failure), so an empty doc
 * shows NO panel, exactly the eldoc row's own takeIf guard. */
internal fun candidateDocVisible(
    doc: CandidateDocument?,
    offerEpoch: Long,
    visibleIndices: List<Int>,
): Boolean = doc != null && doc.epoch == offerEpoch &&
    doc.index in visibleIndices && doc.text.isNotEmpty()

internal fun narrowedWithWireIndex(
    candidates: List<CompletionCandidate>,
    active: Boolean,
    extendedPrefix: String,
    ext: String,
    narrowing: CompletionNarrowing,
): List<IndexedValue<CompletionCandidate>> = when {
    !active -> emptyList()
    ext.isEmpty() -> candidates.withIndex().toList()
    else -> candidates.withIndex().filter { (_, c) ->
        when (narrowing) {
            CompletionNarrowing.STRICT ->
                c.label.startsWith(extendedPrefix) ||
                    c.insert.startsWith(extendedPrefix)
            CompletionNarrowing.CONTAINS ->
                c.label.contains(extendedPrefix) ||
                    c.insert.contains(extendedPrefix)
        }
    }
}

/** How many candidates one editor shows. Emacs decides what to send; this
 * bounds what a phone-sized surface renders from it. */
private const val MAX_VISIBLE_COMPLETIONS = 12

/** Amendment #169: candidate `kind` -> material icon NAME, or null for no
 * decoration. Every name here must resolve in the material catalog (IconMap
 * is reflective, so a typo would draw the HelpOutline placeholder - which is
 * exactly the decoration the SPEC's unrecognized-value degrade forbids; the
 * render test resolves each name and asserts non-placeholder). Kinds absent
 * from the map - recognized or not - render without decoration, which is the
 * conforming degrade in both cases. */
internal fun completionKindIcon(kind: String?): String? = when (kind) {
    "text" -> "abc"
    "method", "function", "constructor" -> "functions"
    "field", "variable", "property" -> "data_object"
    "class", "interface", "struct" -> "category"
    "module" -> "inventory_2"
    "value", "enum", "enum-member" -> "tag"
    "keyword" -> "key"
    "snippet" -> "content_paste"
    "color" -> "palette"
    "file" -> "description"
    "reference" -> "link"
    "folder" -> "folder"
    "constant" -> "bookmark"
    "event" -> "event"
    "operator" -> "calculate"
    "type-parameter" -> "code"
    else -> null
}

/** SPEC 17.7 `${date}`: local `YYYY-MM-DD Day`. */
private fun localDateStamp(): String {
    val cal = java.util.Calendar.getInstance()
    val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    return "%04d-%02d-%02d %s".format(
        cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1])
}

/** SPEC 17.7 `${time}`: local `HH:MM`. */
private fun localTimeStamp(): String {
    val cal = java.util.Calendar.getInstance()
    return "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
}

// ------------------------------------------------------------ scaffold

/**
 * SPEC 17.6: the scaffold's application chrome — every slot (top_bar, body,
 * bottom_bar, fab, floating_toolbar, drawer) is structural NODE content with
 * no hidden navigation behavior beyond its own nodes. The snackbar action
 * dispatches only on a user tap (never timeout); on_refresh only after a user
 * refresh gesture (the spinner self-clears — there is no completion signal,
 * the refreshed push lands in roughly the same window). A drawer opens from
 * the hamburger (Companion-local, like view switching) and closes by scrim.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RenderScaffold(
    node: JsonObject,
    ctx: RenderCtx,
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }
    val scrollSignal = remember { BodyScrollSignal() }
    val snackbar = node.stringOr("snackbar").takeIf { it.isNotEmpty() }
    val action = node.objOrNull("snackbar_action")
    val drawer = node.objOrNull("drawer")
    // The state belongs to this drawer-bearing SURFACE, not merely to this
    // call-site in the resolved-view renderer.  A pushed screen occupies the
    // same composition position as the root it replaced.  Remembering here
    // unconditionally therefore carried an OPEN root drawer invisibly through
    // drawer-less detail screens; it resurfaced when Back returned to the
    // root.  Two drawer-bearing guest roots reuse that position too (Files ->
    // Eval), so presence alone is insufficient: key the remember group by
    // surface.  Leaving/changing the group disposes the old sheet state and a
    // later root starts closed as a fresh presentation.
    val drawerState = if (drawer != null) {
        androidx.compose.runtime.key(ctx.surface) {
            androidx.compose.material3.rememberDrawerState(
                androidx.compose.material3.DrawerValue.Closed)
        }
    } else null
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(snackbar) {
        if (snackbar != null) {
            val durationName = node.stringOr("snackbar_duration")
            val result = hostState.showSnackbar(
                message = snackbar,
                actionLabel = action?.stringOr("label")?.takeIf { it.isNotEmpty() },
                // §17.6: `snackbar_dismiss` is the trailing X, and an
                // INDEFINITE snackbar implies it — such a snackbar must
                // always leave the user an exit.
                withDismissAction = node.boolOr("snackbar_dismiss") ||
                    durationName == "indefinite",
                duration = when (durationName) {
                    "long" -> SnackbarDuration.Long
                    "indefinite" -> SnackbarDuration.Indefinite
                    else -> SnackbarDuration.Short
                })
            if (result == SnackbarResult.ActionPerformed)
                ctx.action(action?.objOrNull("on_tap"))
        }
    }
    // §17.6 `top_bar_style`: an ABSENT style keeps the plain status-bar-padded
    // Row this renderer has always drawn, so no existing chrome moves. A
    // present one asks for the real M3 TopAppBar, which is the only thing a
    // scroll behavior can attach to — the authored `top_bar` node becomes its
    // title slot, and the drawer hamburger its navigationIcon.
    val topBarStyle = node.stringOr("top_bar_style")
    val scrollBehavior: TopAppBarScrollBehavior? =
        if (topBarStyle.isEmpty()) null else when (node.stringOr("scroll_behavior")) {
            "pinned" -> TopAppBarDefaults.pinnedScrollBehavior()
            "enter_always" -> TopAppBarDefaults.enterAlwaysScrollBehavior()
            "exit_until_collapsed" -> TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            else -> null
        }
    // §17.6 `bottom_bar_behavior`: exit_always hides the bottom bar as the
    // body scrolls up and returns it on the way down — Companion-local
    // nested scroll, the same discipline as the top bar's behaviors.
    val bottomBarBehavior =
        if (node.stringOr("bottom_bar_behavior") == "exit_always")
            androidx.compose.material3.BottomAppBarDefaults.exitAlwaysScrollBehavior()
        else null
    // §17.6 floating toolbar. Absent orientation keeps the full-width band in
    // the bottomBar slot; present, it becomes M3's real pill floating OVER the
    // body, which is what every upstream sample actually shows.
    val toolbarOrientation = node.stringOr("floating_toolbar_orientation")
    val toolbarScroll = node.boolOr("floating_toolbar_scroll")
    val toolbarBehavior: FloatingToolbarScrollBehavior? =
        if (!toolbarScroll || toolbarOrientation.isEmpty()) null
        else FloatingToolbarDefaults.exitAlwaysScrollBehavior(
            exitDirection = when (node.stringOr("floating_toolbar_exit_direction")) {
                "top" -> FloatingToolbarExitDirection.Top
                "start" -> FloatingToolbarExitDirection.Start
                "end" -> FloatingToolbarExitDirection.End
                else -> FloatingToolbarExitDirection.Bottom
            })
    // SPEC 18.2.1: consume a pending event-driven snackbar in THIS host —
    // RenderScaffold only composes for the surface being presented, so the
    // raise lands where the SPEC says it must. The respond callback answers
    // the pending snackbar.show request with how it concluded.
    // The raise consumer, hardened by the device: the collector runs only
    // while THIS host's lifecycle is STARTED, and takes the raise with an
    // atomic compareAndSet. Both halves matter. A second MainActivity
    // stacked behind the visible one keeps its whole composition — and an
    // always-on collector there raced the visible host for every raise on
    // a CONFLATED StateFlow, so raises landed in an invisible scaffold or
    // vanished entirely, depending on who resumed first. Lifecycle gating
    // stops backgrounded hosts from competing; the CAS makes the surviving
    // race (two STARTED hosts, e.g. split screen) single-show. Keying the
    // effect on the pending value was the previous bug: clearing the flow
    // changed the key and cancelled the coroutine awaiting showSnackbar.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            SnackbarRaises.flow.collect { raise ->
                raise ?: return@collect
                if (!SnackbarRaises.flow.compareAndSet(raise, null))
                    return@collect
                val res = hostState.showSnackbar(
                    message = raise.message,
                    actionLabel = raise.actionLabel,
                    withDismissAction = raise.duration == "indefinite",
                    duration = when (raise.duration) {
                        "long" -> SnackbarDuration.Long
                        "indefinite" -> SnackbarDuration.Indefinite
                        else -> SnackbarDuration.Short
                    })
                raise.respond(
                    if (res == SnackbarResult.ActionPerformed) "action" else "dismissed")
            }
        }
    }
    // §17.6 `rail`: a node slot laid on the START edge beside the whole
    // chrome — with the §20.1.1 geometry known, Emacs fills bottom_bar or
    // rail from the same items, the NavigationSuiteScaffold swap. The slot
    // hosts an authored navigation_rail, whose own variant and synced
    // expanded state carry the collapsed/expanded forms.
    val rail = node.objOrNull("rail")
    val scaffold: @Composable () -> Unit = {
        Scaffold(
            modifier = modifier
                .let { m -> scrollBehavior?.let { m.nestedScroll(it.nestedScrollConnection) } ?: m }
                .let { m -> bottomBarBehavior?.let { m.nestedScroll(it.nestedScrollConnection) } ?: m }
                .let { m -> toolbarBehavior?.let { m.nestedScroll(it) } ?: m },
            // §17.6 `fab_position`: end_overlay rides the FAB OVER the
            // bottom bar — the ExitAlways pairing.
            floatingActionButtonPosition = when (node.stringOr("fab_position")) {
                "end_overlay" -> androidx.compose.material3.FabPosition.EndOverlay
                "center" -> androidx.compose.material3.FabPosition.Center
                else -> androidx.compose.material3.FabPosition.End
            },
            snackbarHost = {
                SnackbarHost(hostState) { data ->
                    // §17.6 `snackbar_content`: the authored node replaces the
                    // whole Snackbar face while the HOST keeps M3's animation,
                    // timing and dismissal; the plain `snackbar` string stays
                    // the message and the accessible text, so the author
                    // repeats it inside the face.
                    val custom = node.objOrNull("snackbar_content")
                    // §17.6 `snackbar_max_lines`: clamp the VISIBLE message
                    // to the Material-recommended line count; Text semantics
                    // keep the whole string, so a screen reader loses nothing.
                    val cap = node.doubleOr("snackbar_max_lines", 0.0).toInt()
                    if (custom != null)
                        RenderNode(custom, ctx.child(custom, 0))
                    else if (cap > 0)
                        androidx.compose.material3.Snackbar(
                            action = data.visuals.actionLabel?.let { label ->
                                {
                                    TextButton(onClick = { data.performAction() }) {
                                        Text(label)
                                    }
                                }
                            },
                            dismissAction = if (data.visuals.withDismissAction) {
                                {
                                    IconButton(onClick = { data.dismiss() }) {
                                        Icon(IconMap.get("close"),
                                            contentDescription = "Dismiss")
                                    }
                                }
                            } else null,
                        ) {
                            Text(data.visuals.message, maxLines = cap,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    else androidx.compose.material3.Snackbar(data)
                }
            },
            topBar = {
                val topBar = node.objOrNull("top_bar")
                if (topBarStyle.isNotEmpty()) {
                    val title: @Composable () -> Unit = {
                        topBar?.let { RenderNode(it, ctx.child(it, 0)) }
                    }
                    val nav: @Composable () -> Unit = {
                        // A PERMANENT drawer stands open; its hamburger
                        // would toggle nothing, so it is suppressed.
                        val state = drawerState
                        if (state != null &&
                            node.stringOr("drawer_variant") != "permanent")
                            IconButton(onClick = {
                                scope.launch {
                                    if (state.isClosed) state.open()
                                    else state.close()
                                }
                            }) {
                                Icon(IconMap.get("menu"),
                                    contentDescription = "Menu")
                            }
                    }
                    val subtitle = node.stringOr("top_bar_subtitle")
                    // §17.6 heights: authored dp overrides the per-style M3
                    // default; -1 marks "unauthored" (dp members are >= 0).
                    val collapsedH = node.doubleOr("top_bar_collapsed_height", -1.0)
                    val expandedH = node.doubleOr("top_bar_expanded_height", -1.0)
                    val titleAlign =
                        if (node.boolOr("top_bar_centered")) Alignment.CenterHorizontally
                        else Alignment.Start
                    val subtitleSlot: (@Composable () -> Unit)? =
                        if (subtitle.isNotEmpty()) { { Text(subtitle) } } else null
                    // Only the small TopAppBar takes a `subtitle`; the
                    // center-aligned one carries the alignment instead, so a
                    // subtitled centre bar is the small overload with
                    // titleHorizontalAlignment rather than a different bar.
                    when {
                        topBarStyle == "medium_flexible" -> MediumFlexibleTopAppBar(
                            title = title, subtitle = subtitleSlot,
                            navigationIcon = nav,
                            titleHorizontalAlignment = titleAlign,
                            collapsedHeight = if (collapsedH >= 0) collapsedH.dp
                                else TopAppBarDefaults.MediumAppBarCollapsedHeight,
                            expandedHeight = if (expandedH >= 0) expandedH.dp
                                else if (subtitle.isNotEmpty())
                                    TopAppBarDefaults.MediumFlexibleAppBarWithSubtitleExpandedHeight
                                else TopAppBarDefaults.MediumFlexibleAppBarWithoutSubtitleExpandedHeight,
                            scrollBehavior = scrollBehavior)
                        topBarStyle == "large_flexible" -> LargeFlexibleTopAppBar(
                            title = title, subtitle = subtitleSlot,
                            navigationIcon = nav,
                            titleHorizontalAlignment = titleAlign,
                            collapsedHeight = if (collapsedH >= 0) collapsedH.dp
                                else TopAppBarDefaults.LargeAppBarCollapsedHeight,
                            expandedHeight = if (expandedH >= 0) expandedH.dp
                                else if (subtitle.isNotEmpty())
                                    TopAppBarDefaults.LargeFlexibleAppBarWithSubtitleExpandedHeight
                                else TopAppBarDefaults.LargeFlexibleAppBarWithoutSubtitleExpandedHeight,
                            scrollBehavior = scrollBehavior)
                        // §17.6 two_rows: the title slot takes the EXPANDED
                        // flag — the bar swaps `top_bar_expanded` for the
                        // plain `top_bar` as it folds, so the string swap
                        // upstream's CustomTwoRowsTopAppBar demonstrates
                        // needs no collapse fraction on the wire.
                        topBarStyle == "two_rows" -> TwoRowsTopAppBar(
                            title = { expanded ->
                                val n = if (expanded)
                                    node.objOrNull("top_bar_expanded") ?: topBar
                                else topBar
                                n?.let { RenderNode(it, ctx.child(it, 0)) }
                            },
                            navigationIcon = nav,
                            titleHorizontalAlignment = titleAlign,
                            collapsedHeight = if (collapsedH >= 0) collapsedH.dp
                                else TopAppBarDefaults.MediumAppBarCollapsedHeight,
                            expandedHeight = if (expandedH >= 0) expandedH.dp
                                else TopAppBarDefaults.MediumFlexibleAppBarWithSubtitleExpandedHeight,
                            scrollBehavior = scrollBehavior)
                        subtitle.isNotEmpty() && topBarStyle != "medium" &&
                            topBarStyle != "large" ->
                            TopAppBar(title = title, subtitle = { Text(subtitle) },
                                navigationIcon = nav,
                                titleHorizontalAlignment =
                                    if (topBarStyle == "center") Alignment.CenterHorizontally
                                    else Alignment.Start,
                                scrollBehavior = scrollBehavior)
                        topBarStyle == "center" -> CenterAlignedTopAppBar(
                            title = title, navigationIcon = nav,
                            scrollBehavior = scrollBehavior)
                        topBarStyle == "medium" -> MediumTopAppBar(
                            title = title, navigationIcon = nav,
                            scrollBehavior = scrollBehavior)
                        topBarStyle == "large" -> LargeTopAppBar(
                            title = title, navigationIcon = nav,
                            scrollBehavior = scrollBehavior)
                        else -> TopAppBar(title = title, navigationIcon = nav,
                            scrollBehavior = scrollBehavior)
                    }
                } else if (topBar != null || drawer != null) {
                    // §17.6: the top bar is drawn edge-to-edge, so it MUST clear
                    // the system status bar itself (a plain Row, unlike M3's
                    // TopAppBar, gets no automatic inset) — else the hamburger
                    // sits under the status icons and is neither visible nor
                    // tappable.
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val state = drawerState
                        if (state != null &&
                            node.stringOr("drawer_variant") != "permanent") {
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    scope.launch {
                                        if (state.isClosed) state.open()
                                        else state.close()
                                    }
                                }) {
                                androidx.compose.material3.Icon(
                                    IconMap.get("menu"), contentDescription = "Menu")
                            }
                        }
                        topBar?.let { RenderNode(it, ctx.child(it, 0)) }
                    }
                }
            },
            floatingActionButton = {
                // §17.6 `fab_hide_on_scroll`: scale the slot's occupant away
                // as the body leaves its start — the derived form; an
                // author-driven boolean alone would have no driver.
                val fabMod = if (node.boolOr("fab_hide_on_scroll"))
                    Modifier.animateFloatingActionButton(
                        visible = scrollSignal.atStart,
                        alignment = Alignment.BottomEnd)
                else Modifier
                Box(fabMod) {
                    node.objOrNull("fab")?.let { RenderNode(it, ctx.child(it, 2)) }
                }
            },
            bottomBar = {
                // §17.6: a STYLED floating toolbar floats over the body
                // instead, so it is excluded here — the band below is the
                // unstyled rendering this slot has always drawn.
                val floatingToolbar =
                    if (toolbarOrientation.isEmpty()) node.objOrNull("floating_toolbar")
                    else null
                val bottomBar = node.objOrNull("bottom_bar")
                if (floatingToolbar != null || bottomBar != null) {
                    Column {
                        floatingToolbar?.let {
                            androidx.compose.material3.Surface(
                                tonalElevation = 3.dp, shadowElevation = 4.dp,
                                modifier = Modifier.fillMaxWidth()) {
                                RenderNode(it, ctx.child(it, 3))
                            }
                        }
                        bottomBar?.let {
                            // With the exit_always behavior the slot content
                            // rides a real M3 BottomAppBar — the composable
                            // that owns the hide-and-return offset. Otherwise
                            // the docked Surface band this slot has always
                            // drawn (which owns the navigation inset itself,
                            // since a plain Surface gets none).
                            if (bottomBarBehavior != null)
                                androidx.compose.material3.BottomAppBar(
                                    scrollBehavior = bottomBarBehavior) {
                                    RenderNode(it, ctx.child(it, 4))
                                }
                            else androidx.compose.material3.Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()) {
                                Box(Modifier.navigationBarsPadding()
                                        .padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    RenderNode(it, ctx.child(it, 4))
                                }
                            }
                        }
                    }
                }
            },
        ) { inner ->
            // Edge-to-edge: lift keyboard-adjacent body content clear of the
            // IME while the bottom bar stays put; consumeWindowInsets keeps
            // descendants with their own imePadding from double-padding.
            val bodyModifier = Modifier.padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
            val onRefresh = node.objOrNull("on_refresh")
            val body = node.objOrNull("body")
            if (onRefresh != null) {
                var localRefreshing by remember { mutableStateOf(false) }
                LaunchedEffect(localRefreshing) {
                    if (localRefreshing) { kotlinx.coroutines.delay(1200); localRefreshing = false }
                }
                // §17.6 `is_refreshing`: Emacs IS the ViewModel — an authored
                // flag replaces the optimistic local one (which stays the
                // default so existing senders are unaffected), and the next
                // accepted snapshot clears it.
                val refreshing = if ("is_refreshing" in node)
                    node.boolOr("is_refreshing") else localRefreshing
                val ptrState =
                    androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                val indicatorName = node.stringOr("refresh_indicator")
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = refreshing,
                    state = ptrState,
                    onRefresh = {
                        localRefreshing = true
                        ctx.action(onRefresh) // §17.6: user gesture only
                    },
                    // `refresh_indicator`: the indicator SLOT of the box —
                    // M3's spinner (the default), its LoadingIndicator
                    // sibling, or nothing for a body that draws its own.
                    indicator = {
                        when (indicatorName) {
                            "loading" ->
                                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
                                    .LoadingIndicator(
                                        state = ptrState,
                                        isRefreshing = refreshing,
                                        modifier = Modifier.align(Alignment.TopCenter))
                            "none" -> {}
                            else ->
                                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
                                    .Indicator(
                                        state = ptrState,
                                        isRefreshing = refreshing,
                                        modifier = Modifier.align(Alignment.TopCenter))
                        }
                    },
                    modifier = bodyModifier) {
                    CompositionLocalProvider(
                        LocalBodyScrollSignal provides scrollSignal) {
                        body?.let { RenderNode(it, ctx.child(it, 1)) }
                    }
                }
            } else {
                Box(bodyModifier) {
                    CompositionLocalProvider(
                        LocalBodyScrollSignal provides scrollSignal) {
                        body?.let { RenderNode(it, ctx.child(it, 1)) }
                    }
                }
            }
            // The pill sits OVER the body, aligned by `placement`.
            val toolbar = node.objOrNull("floating_toolbar")
            if (toolbar != null && toolbarOrientation.isNotEmpty()) {
                val expanded = node.boolOr("floating_toolbar_expanded", true)
                val fab = node.objOrNull("floating_toolbar_fab")
                val align = when (node.stringOr("floating_toolbar_placement")) {
                    "bottom_start" -> Alignment.BottomStart
                    "bottom_end" -> Alignment.BottomEnd
                    "center_start" -> Alignment.CenterStart
                    "center_end" -> Alignment.CenterEnd
                    else -> Alignment.BottomCenter
                }
                Box(Modifier.padding(inner).fillMaxSize()) {
                    val pill = Modifier.align(align).padding(16.dp)
                    val content: @Composable RowScope.() -> Unit = {
                        RenderNode(toolbar, ctx.child(toolbar, 3))
                    }
                    val vContent: @Composable ColumnScope.() -> Unit = {
                        RenderNode(toolbar, ctx.child(toolbar, 3))
                    }
                    if (toolbarOrientation == "vertical") {
                        if (fab != null) VerticalFloatingToolbar(
                            expanded = expanded, modifier = pill,
                            scrollBehavior = toolbarBehavior,
                            floatingActionButton = { RenderNode(fab, ctx.child(fab, 6)) },
                            content = vContent)
                        else VerticalFloatingToolbar(
                            expanded = expanded, modifier = pill,
                            scrollBehavior = toolbarBehavior, content = vContent)
                    } else {
                        if (fab != null) HorizontalFloatingToolbar(
                            expanded = expanded, modifier = pill,
                            scrollBehavior = toolbarBehavior,
                            floatingActionButton = { RenderNode(fab, ctx.child(fab, 6)) },
                            content = content)
                        else HorizontalFloatingToolbar(
                            expanded = expanded, modifier = pill,
                            scrollBehavior = toolbarBehavior, content = content)
                    }
                }
            }
        }
    }
    // §17.6 `sheet` + `sheet_peek_height`: the PERSISTENT form. The whole
    // chrome nests inside BottomSheetScaffold's content so the sheet rests
    // at its peek over everything and drags between peek and expanded;
    // nested scroll from a lazy_column inside the sheet comes free. User
    // drags report through on_sheet_change with the settled state name.
    val persistentSheet = node.objOrNull("sheet")
        ?.takeIf { "sheet_peek_height" in node }
    val sheetWrap: @Composable (@Composable () -> Unit) -> Unit =
        if (persistentSheet == null) { content -> content() }
        else { content ->
            val peek = (safeDp(node.doubleOr("sheet_peek_height", 0.0)) ?: 0f).dp
            val onSheetChange = node.objOrNull("on_sheet_change")
            val authored = node.stringOr("sheet_state")
            val sheetScaffoldState =
                androidx.compose.material3.rememberBottomSheetScaffoldState(
                    bottomSheetState =
                        androidx.compose.material3.rememberStandardBottomSheetState(
                            initialValue =
                                if (authored == "expanded")
                                    androidx.compose.material3.SheetValue.Expanded
                                else androidx.compose.material3.SheetValue.PartiallyExpanded))
            val settled = sheetScaffoldState.bottomSheetState.currentValue
            var reported by remember { mutableStateOf(settled) }
            // §17.6: `sheet_state` is AUTHORED presentation state, so a change
            // to it must MOVE the sheet. It was read once as `initialValue` and
            // never again, which made every later push inert — Emacs could not
            // open or collapse a persistent sheet, although both the comment
            // below and `jetpacs-scaffold's docstring promise exactly that.
            //
            // Keyed on the AUTHORED value alone, so a user's drag is never
            // fought: only an author's change drives. `hidden` is deliberately
            // not handled here — the persistent form's resting state IS its
            // peek (`skipHiddenState` defaults true, and making Hidden
            // reachable would let a downward fling dismiss a sheet the author
            // never said could go away). Hiding belongs to the MODAL form
            // below, which is selected by omitting `sheet_peek_height`.
            var driven by remember { mutableStateOf(authored) }
            LaunchedEffect(authored) {
                if (authored.isEmpty() || authored == driven) return@LaunchedEffect
                driven = authored
                val want = if (authored == "expanded")
                    androidx.compose.material3.SheetValue.Expanded
                else androidx.compose.material3.SheetValue.PartiallyExpanded
                // The drive is not a user gesture, so claim it as already
                // reported: otherwise settling there dispatches
                // `on_sheet_change` straight back at the author who asked for
                // it, and a handler that re-pushes would loop.
                reported = want
                // An animation interrupted by a drag or a recomposition throws
                // CancellationException; the sheet is a decoration and must
                // never take the render down with it.
                runCatching {
                    if (want == androidx.compose.material3.SheetValue.Expanded)
                        sheetScaffoldState.bottomSheetState.expand()
                    else sheetScaffoldState.bottomSheetState.partialExpand()
                }
            }
            LaunchedEffect(settled) {
                if (settled != reported) {
                    reported = settled
                    onSheetChange?.let {
                        ctx.action(it, JsonPrimitive(when (settled) {
                            androidx.compose.material3.SheetValue.Expanded -> "expanded"
                            androidx.compose.material3.SheetValue.Hidden -> "hidden"
                            else -> "partial"
                        }))
                    }
                }
            }
            androidx.compose.material3.BottomSheetScaffold(
                sheetContent = {
                    // The scaffold BODY gets `imePadding` (see RenderScaffold);
                    // sheet content sits outside that Scaffold entirely, so
                    // without this the soft keyboard covers whatever the sheet
                    // is holding — and a sheet is exactly where a text field
                    // or an editor tends to live.
                    Box(modifier = Modifier.imePadding()) {
                        RenderNode(persistentSheet, ctx.child(persistentSheet, 7))
                    }
                },
                sheetPeekHeight = peek,
                scaffoldState = sheetScaffoldState) { _ -> content() }
        }
    val railed: @Composable () -> Unit = if (rail != null) {
        {
            Row(Modifier.fillMaxSize()) {
                RenderNode(rail, ctx.child(rail, 0))
                Box(Modifier.weight(1f)) { scaffold() }
            }
        }
    } else scaffold
    if (drawer != null) {
        // Correlated with `drawer` above: state is created only for this arm.
        val state = checkNotNull(drawerState)
        // §17.6 `drawer_variant`: the host around the SAME drawer node.
        // Dismissible pushes the body aside and leaves it live; permanent
        // stands open (its hamburger is suppressed at the bar — it would
        // toggle nothing).
        // The SHEETS take drawerState deliberately (S11): that overload is
        // the one installing DrawerPredictiveBackHandler, so an OPEN drawer
        // owns system back — it composes after, and therefore outranks, the
        // chrome BackHandler in MainActivity. The stateless overload handles
        // no back at all, and the view stack would move under the open sheet.
        when (node.stringOr("drawer_variant")) {
            "dismissible" ->
                androidx.compose.material3.DismissibleNavigationDrawer(
                    drawerState = state,
                    drawerContent = {
                        androidx.compose.material3.DismissibleDrawerSheet(
                            drawerState = state) {
                            RenderNode(drawer, ctx.child(drawer, 5))
                        }
                    }) { sheetWrap { railed() } }
            "permanent" ->
                androidx.compose.material3.PermanentNavigationDrawer(
                    drawerContent = {
                        androidx.compose.material3.PermanentDrawerSheet {
                            RenderNode(drawer, ctx.child(drawer, 5))
                        }
                    }) { sheetWrap { railed() } }
            else ->
                androidx.compose.material3.ModalNavigationDrawer(
                    drawerState = state,
                    drawerContent = {
                        androidx.compose.material3.ModalDrawerSheet(
                            drawerState = state,
                            modifier = Modifier.fillMaxWidth(0.75f)) {
                            RenderNode(drawer, ctx.child(drawer, 5))
                        }
                    }) { sheetWrap { railed() } }
        }
    } else sheetWrap { railed() }
    // §17.6 `sheet`: the bottom-sheet slot. With `sheet_peek_height` absent
    // this is the MODAL form — an overlay composing after the scaffold, shown
    // while the AUTHORED sheet_state says so. A user dismissal dispatches
    // on_sheet_change("hidden") and holds locally until the authored value
    // CHANGES, so a re-push cannot slam the sheet back open (the tooltip.shown
    // discipline). The persistent form wraps the body inside RenderScaffold's
    // Scaffold — see the sheetWrap seam there.
    val sheet = node.objOrNull("sheet")
    if (sheet != null && "sheet_peek_height" !in node) {
        val authored = node.stringOr("sheet_state")
        val onSheetChange = node.objOrNull("on_sheet_change")
        var dismissedWhile by remember { mutableStateOf<String?>(null) }
        val show = authored.isNotEmpty() && authored != "hidden" &&
            dismissedWhile != authored
        if (show) {
            val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = authored == "expanded")
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = {
                    dismissedWhile = authored
                    onSheetChange?.let { ctx.action(it, JsonPrimitive("hidden")) }
                },
                sheetState = sheetState) {
                // Same reason as the persistent form: a modal sheet composes
                // OUTSIDE the Scaffold that carries `imePadding`, so without
                // this the keyboard covers its content.
                Box(modifier = Modifier.imePadding()) {
                    RenderNode(sheet, ctx.child(sheet, 7))
                }
            }
        }
    }
}

// ------------------------------------------------------------ actions

// The dialog rebinding lives in RenderCtx.action (T3c) — button sites are
// plain dispatches like every other descriptor site.
fun onButton(onTap: JsonObject?, ctx: RenderCtx) {
    onTap ?: return
    ctx.action(onTap)
}
