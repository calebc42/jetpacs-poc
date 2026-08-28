// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 17.3 layout nodes, ported from poc-v1 with the format-6 vocabulary:
// - `fill` defaults FALSE (§17.1 optional booleans; poc filled by default) and
//   `spacing` defaults 0; `arrange` (start/center/end/space_between/
//   space_around/space_evenly) distributes space and beats spacing (§17.3).
// - box grows the full 9-value alignment set (poc missed the corners).
// - card/collapsible keep ONLY the contract's swipe_start/swipe_end sides via
//   SwipeActionBox (poc's legacy single-action on_swipe is gone).
// - collapsible expansion + tabs selection key on §16.1 presentation identity
//   (ctx.path: key > id > tree path); a same-identity re-push preserves the
//   user's state, a new identity reseeds. Tabs additionally shrink-clamp to
//   `initial` WITHOUT emitting on_change when a smaller same-identity snapshot
//   invalidates the retained index (§17.3).
// - table rows carry `kind` (data/header/rule — poc used booleans) with span
//   cells; on_add_row/on_add_col inject `index` (§14.3).
// - reorderable_list is a contract-shaped rebuild of poc's drag list:
//   items are arbitrary nodes with unique key/id; a completed drag injects
//   from/to/order (§14.3), and the vertical drag mechanics survive while the
//   org-specific promote/demote does not.
package com.calebc42.ebp.companion.render

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AppBarColumn
import androidx.compose.material3.AppBarMenuState
import androidx.compose.material3.AppBarOverflowIndicator
import androidx.compose.material3.AppBarRow
import androidx.compose.material3.AppBarScope
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupMenuState
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldPaneScope
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.calebc42.jetpacs.renderer.compose.ebpSemantics
import com.calebc42.jetpacs.renderer.model.SemanticStateOverride
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.IdentityHashMap

/** One closed, validated alternative inside a retained [variant_host]. */
internal data class RetainedVariant(
    val value: String,
    val content: JsonObject,
)

/** Defensive projection used by both the renderer and its JVM tests. The
 * accepted wire document has already been validated, but keeping malformed
 * entries out here preserves the renderer's never-throw boundary. */
internal fun retainedVariants(node: JsonObject): List<RetainedVariant> =
    node.arrOrNull("variants").orEmpty().mapNotNull { raw ->
        val entry = raw as? JsonObject ?: return@mapNotNull null
        val value = entry.stringOrNull("value") ?: return@mapNotNull null
        val content = entry.objOrNull("content") ?: return@mapNotNull null
        RetainedVariant(value, content)
    }

/** Local draft wins, then the authored selection, then the first branch.
 * The final fallback is defensive only: validation requires the authored
 * value to name one of the alternatives. */
internal fun selectedRetainedVariantIndex(
    variants: List<RetainedVariant>,
    local: String?,
    authored: String,
): Int {
    val localIndex = variants.indexOfFirst { it.value == local }
    if (localIndex >= 0) return localIndex
    return variants.indexOfFirst { it.value == authored }.takeIf { it >= 0 } ?: 0
}

/** One stable outer layout slot per authored alternative, with exactly the
 * selected content host active. Keeping the outer Boxes present preserves the
 * custom Layout's measurable indexing while [ReusableContentHost] deactivates
 * every inactive subtree. */
internal fun retainedVariantActiveSlots(count: Int, selected: Int): List<Boolean> =
    List(count.coerceAtLeast(0)) { index -> index == selected }

/** Saveable branch identities that disappeared from a newer authored host.
 * Their state must be removed so a later reuse starts as a genuinely new
 * presentation identity. */
internal fun removedRetainedVariantPaths(
    previous: Collection<String>,
    current: Collection<String>,
): Set<String> = previous.toSet() - current.toSet()

/** The host's own §16.1 identity. Root RenderNode calls have an empty path,
 * while nested calls already carry the identity selected by their parent. */
internal fun retainedVariantHostPath(ctxPath: String, host: JsonObject): String =
    ctxPath.ifEmpty { identityPath("", host, 0) }

/** Stable presentation identity for a branch root. */
internal fun retainedVariantPath(ctxPath: String, host: JsonObject,
                                 variant: RetainedVariant): String {
    val hostPath = retainedVariantHostPath(ctxPath, host)
    return identityPath(
        "$hostPath/v:${encodedIdentityAtom(variant.value)}",
        variant.content,
        0,
    )
}

/** Exact save-state ownership for nodes that actually own saveable local
 * presentation in an authored retained branch. JsonObject equality is
 * structural, so the lookup intentionally uses object identity: equal sibling
 * nodes are still distinct occurrences. */
internal class RetainedIdentityInventory internal constructor(
    private val byNode: IdentityHashMap<JsonObject, String>,
    val providerPaths: Set<String>,
) {
    fun providerPath(node: JsonObject): String? = byNode[node]
}

internal val RETAINED_IDENTITY_OPAQUE_MEMBERS = setOf("args", "meta", "value")

/** Nodes whose renderer owns state that is meaningful after switching away
 * and back. Restricting providers to these nodes keeps the saved identity
 * inventory proportional to actual retained presentation state rather than
 * the complete (up to 10k-node) authored tree. Descendants are still walked,
 * so a state owner beneath a stateless container gets its own registry. */
internal fun retainedNodeOwnsSaveablePresentation(node: JsonObject): Boolean =
    when (node.stringOr("t")) {
        "lazy_column", "collapsible", "tabs", "table", "reorderable_list",
        "pane_scaffold", "carousel", "material3.fab_menu", "lazy_grid", "month_grid",
        "scaffold" -> true
        "row", "column" -> node.boolOr("scroll")
        else -> false
    }

/** Build a canonical tree-location inventory without composing the branch.
 * This is what lets an inactive accepted snapshot retire a removed nested
 * identity. Key/id still outrank tree position and type always participates;
 * unkeyed positions use the encoded JSON member/index path. */
internal fun retainedIdentityInventory(
    rootPath: String,
    root: JsonObject,
): RetainedIdentityInventory {
    val byNode = IdentityHashMap<JsonObject, String>()
    val providerPaths = LinkedHashSet<String>()

    fun nestedPath(parentPath: String, node: JsonObject, position: String): String {
        val key = node.stringOrNull("key").orEmpty()
        val id = node.stringOrNull("id").orEmpty()
        val type = node.stringOrNull("t").orEmpty()
        val segment = when {
            key.isNotEmpty() -> typedIdentitySegment("k", key, type)
            id.isNotEmpty() -> typedIdentitySegment("id", id, type)
            else -> typedIdentitySegment("p", position, type)
        }
        return "$parentPath/$segment"
    }

    lateinit var walkNode: (JsonObject, String) -> Unit
    lateinit var walkValue: (JsonElement, String, String) -> Unit
    walkValue = { value, parentPath, position ->
        when (value) {
            is JsonArray -> value.forEachIndexed { index, child ->
                walkValue(child, parentPath, "$position[$index]")
            }
            is JsonObject -> {
                if (value.stringOrNull("t") != null) {
                    walkNode(value, nestedPath(parentPath, value, position))
                } else {
                    value.forEach { (member, child) ->
                        if (member !in RETAINED_IDENTITY_OPAQUE_MEMBERS)
                            walkValue(child, parentPath, "$position.$member")
                    }
                }
            }
            else -> Unit
        }
    }
    walkNode = { node, path ->
        if (retainedNodeOwnsSaveablePresentation(node)) {
            val providerPath = "node:$path"
            byNode[node] = providerPath
            providerPaths += providerPath
        }
        node.forEach { (member, child) ->
            if (member !in RETAINED_IDENTITY_OPAQUE_MEMBERS)
                walkValue(child, path, member)
        }
    }
    walkNode(root, rootPath)
    return RetainedIdentityInventory(byNode, providerPaths)
}

internal class RetainedSaveableScope(
    val holder: SaveableStateHolder,
    val viewportAnchors: RetainedViewportAnchorRegistry,
    private val inventory: RetainedIdentityInventory,
    private val lifecycleInventory: RetainedIdentityInventory,
    private val lifecycleIncarnations: Map<Pair<String, String>, Long>,
    private val surface: String,
) {
    fun providerPath(node: JsonObject): String? {
        val providerPath = inventory.providerPath(node) ?: return null
        val lifecyclePath = lifecycleInventory.providerPath(node) ?: return null
        return incarnatedRetainedProviderPath(
            providerPath, lifecyclePath, surface, lifecycleIncarnations)
    }
}

internal fun incarnatedRetainedProviderPath(
    providerPath: String,
    lifecyclePath: String,
    surface: String,
    lifecycleIncarnations: Map<Pair<String, String>, Long>,
): String = "$providerPath/i:${lifecycleIncarnations[surface to lifecyclePath] ?: 0L}"

internal val LocalRetainedSaveableScope =
    staticCompositionLocalOf<RetainedSaveableScope?> { null }

/**
 * SPEC 17.3 retained alternatives. Every branch owns a stable reusable slot,
 * but inactive content is DEACTIVATED: effects/resources are disposed and it
 * cannot dispatch. [ReusableContentHost] retains reusable nodes, while the
 * host-scoped SaveableStateHolder restores saveable presentation state by
 * the branch's complete §16.1 path; keyed lazy-column anchors additionally
 * hand off through a host-owned saveable registry across deactivation. A
 * content-root identity change therefore starts fresh.
 * Arbitrary non-saveable remembered state is intentionally not promised.
 * Only the selected branch is active, measured, placed, and semantically
 * exposed. The outer Box remains for every alternative so the selected index
 * continues to address exactly one measurable.
 */
@Composable
internal fun RenderVariantHost(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val variants = remember(node) { retainedVariants(node) }
    if (variants.isEmpty()) return
    val selections by ctx.bridge.variantSelections.collectAsState()
    val selected = selectedRetainedVariantIndex(
        variants,
        selections[ctx.surface to node.stringOr("id")],
        node.stringOr("value"),
    ).coerceIn(variants.indices)
    val activeSlots = retainedVariantActiveSlots(variants.size, selected)

    val hostPath = retainedVariantHostPath(ctx.path, node)
    val branchPaths = remember(hostPath, variants) {
        variants.map { retainedVariantPath(hostPath, node, it) }
    }
    val inventories = remember(branchPaths, variants) {
        variants.mapIndexed { index, variant ->
            retainedIdentityInventory(branchPaths[index], variant.content)
        }
    }
    val lifecycleInventories = remember(variants) {
        variants.map { variant ->
            retainedIdentityInventory(
                retainedVariantLifecyclePath(node, variant), variant.content)
        }
    }
    val lifecycleIncarnations by
        ctx.bridge.retainedPresentationIncarnations.collectAsState()
    val providerPaths = remember(
        inventories, lifecycleInventories, lifecycleIncarnations, ctx.surface,
    ) {
        buildSet {
            inventories.indices.forEach { index ->
                inventories[index].providerPaths
                    .zip(lifecycleInventories[index].providerPaths)
                    .forEach { (providerPath, lifecyclePath) ->
                        add(incarnatedRetainedProviderPath(
                            providerPath,
                            lifecyclePath,
                            ctx.surface,
                            lifecycleIncarnations,
                        ))
                    }
            }
        }
    }

    // Key the holder by the universal presentation identity. In particular,
    // an authored key outranks the stateful ID, exactly like every other node.
    key(hostPath) {
        val stateHolder = rememberSaveableStateHolder()
        // ReusableContentHost deliberately deletes every ordinary remember
        // below an inactive branch. Keep the latest keyed viewport outside
        // that deletion boundary as well as in the per-owner saveable
        // registry. This makes a rapid branch round-trip independent of the
        // framework's nested deactivate/dispose hand-off, while the saver
        // still carries the bounded anchors through process recreation.
        val viewportAnchors = rememberSaveable(
            hostPath,
            saver = RetainedViewportAnchorRegistrySaver,
        ) { RetainedViewportAnchorRegistry() }
        var knownPaths by rememberSaveable {
            mutableStateOf(ArrayList(providerPaths))
        }
        SideEffect {
            val removed = removedRetainedVariantPaths(knownPaths, providerPaths)
            removed.forEach(stateHolder::removeState)
            viewportAnchors.removeAll(removed)
            knownPaths = ArrayList(providerPaths)
        }

        Layout(modifier = m, content = {
            variants.forEachIndexed { index, variant ->
                key(branchPaths[index]) {
                    val retainedScope = remember(
                        stateHolder,
                        viewportAnchors,
                        inventories[index],
                        lifecycleInventories[index],
                        lifecycleIncarnations,
                        ctx.surface,
                    ) {
                        RetainedSaveableScope(
                            stateHolder,
                            viewportAnchors,
                            inventories[index],
                            lifecycleInventories[index],
                            lifecycleIncarnations,
                            ctx.surface,
                        )
                    }
                    Box(
                        modifier = if (index == selected) Modifier
                        else Modifier.clearAndSetSemantics { },
                    ) {
                        ReusableContentHost(active = activeSlots[index]) {
                            CompositionLocalProvider(
                                LocalRetainedSaveableScope provides retainedScope,
                            ) {
                                RenderNode(
                                    variant.content,
                                    ctx.atPath(branchPaths[index]),
                                )
                            }
                        }
                    }
                }
            }
        }) { measurables, constraints ->
            // Deliberately do not measure inactive alternatives. Their outer
            // slots remain reusable, but hidden LazyColumns do no frame work.
            android.os.Trace.beginSection("EBP variant layout")
            try {
                val placeable = measurables.getOrNull(selected)?.measure(constraints)
                if (placeable == null) {
                    layout(constraints.minWidth, constraints.minHeight) { }
                } else {
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                }
            } finally {
                android.os.Trace.endSection()
            }
        }
    }
}

// ------------------------------------------------------ row/column/flow_row

// §17.3: arrange distributes available space; a non-start value takes
// precedence over spacing.
private fun horizontalArrange(node: JsonObject): Arrangement.Horizontal =
    when (node.stringOr("arrange")) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "space_between" -> Arrangement.SpaceBetween
        "space_around" -> Arrangement.SpaceAround
        "space_evenly" -> Arrangement.SpaceEvenly
        // §16.5 keeps `spacing` non-negative; `overlap` is the one door to
        // Arrangement.spacedBy(-dp) — children interlocking, the vertical
        // connected button group's -6dp.
        else -> {
            val overlap = safeDp(node.doubleOr("overlap", 0.0)) ?: 0f
            if (overlap > 0f) Arrangement.spacedBy(-overlap.dp)
            else Arrangement.spacedBy(
                (safeDp(node.doubleOr("spacing", 0.0)) ?: 0f).dp)
        }
    }

private fun verticalArrange(node: JsonObject): Arrangement.Vertical =
    when (node.stringOr("arrange")) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.Bottom
        "space_between" -> Arrangement.SpaceBetween
        "space_around" -> Arrangement.SpaceAround
        "space_evenly" -> Arrangement.SpaceEvenly
        else -> {
            val overlap = safeDp(node.doubleOr("overlap", 0.0)) ?: 0f
            if (overlap > 0f) Arrangement.spacedBy(-overlap.dp)
            else Arrangement.spacedBy(
                (safeDp(node.doubleOr("spacing", 0.0)) ?: 0f).dp)
        }
    }

@Composable
internal fun RenderRow(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val scroll = node.boolOr("scroll")
    val fill = node.boolOr("fill")
    // §17.3 `content_padding`: INSIDE the scroll viewport (after
    // horizontalScroll in the chain), so the content scrolls under it —
    // upstream LazyRow's contentPadding, which the universal `padding`
    // (applied before the scroll) cannot express.
    val contentPad = (safeDp(node.doubleOr("content_padding", 0.0)) ?: 0f).dp
    val mod = when {
        scroll -> m.fillMaxWidth().horizontalScroll(rememberScrollState())
            .let { if (contentPad > 0.dp) it.padding(horizontal = contentPad) else it }
        fill -> m.fillMaxWidth()
        else -> m
    }
    Row(
        modifier = mod,
        horizontalArrangement = horizontalArrange(node),
        verticalAlignment = when (node.stringOr("align")) {
            "top" -> Alignment.Top
            "bottom" -> Alignment.Bottom
            // baseline needs per-child alignBy; center is the honest default.
            else -> Alignment.CenterVertically
        }) {
        // Weights are meaningless with unbounded width (§ port note).
        if (scroll) RenderChildren(node.arrOrNull("children"), ctx)
        else RenderRowChildren(node.arrOrNull("children"), ctx)
    }
}

@Composable
internal fun RenderColumn(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    // Inside a dialog the host already wraps in verticalScroll (MainActivity
    // DialogHost); honouring a spec-level scroll:true here would nest two
    // scrollable containers, which Compose refuses with an
    // IllegalStateException.  Suppress it.
    val scroll = node.boolOr("scroll") && ctx.dialog == null
    // The §17.6 body-scroll signal: a scrolling column inside a scaffold
    // body publishes whether it rests at its start.
    val signal = LocalBodyScrollSignal.current
    val fill = node.boolOr("fill")
    val mod = (if (fill) m.fillMaxWidth() else m).let {
        if (scroll) {
            val state = rememberScrollState()
            if (signal != null) {
                val atStart by remember { derivedStateOf { state.value == 0 } }
                LaunchedEffect(atStart) { signal.atStart = atStart }
            }
            it.verticalScroll(state,
                reverseScrolling = node.boolOr("reverse_scroll"))
        } else it
    }
    Column(
        modifier = mod,
        verticalArrangement = verticalArrange(node),
        horizontalAlignment = when (node.stringOr("align")) {
            "center" -> Alignment.CenterHorizontally
            "end" -> Alignment.End
            else -> Alignment.Start
        }) {
        RenderColumnChildren(node.arrOrNull("children"), ctx)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RenderFlowRow(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    FlowRow(
        modifier = m,
        horizontalArrangement = horizontalArrange(node),
        // SPEC 17.3: flow_row.align is the per-run cross-axis alignment
        // (top|center|bottom, default top).
        itemVerticalAlignment = when (node.stringOr("align")) {
            "center" -> Alignment.CenterVertically
            "bottom" -> Alignment.Bottom
            else -> Alignment.Top
        },
        verticalArrangement = Arrangement.spacedBy(
            (safeDp(node.doubleOr("run_spacing", 0.0)) ?: 0f).dp)) {
        RenderChildren(node.arrOrNull("children"), ctx)
    }
}

// ------------------------------------------------------------- box/surface

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun RenderBox(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val onTap = node.objOrNull("on_tap")
    val onLongTap = node.objOrNull("on_long_tap")
    // §17.3: the full 9-value alignment vocabulary; default top_start.
    val alignment = when (node.stringOr("alignment")) {
        "top_center" -> Alignment.TopCenter
        "top_end" -> Alignment.TopEnd
        "center_start" -> Alignment.CenterStart
        "center" -> Alignment.Center
        "center_end" -> Alignment.CenterEnd
        "bottom_start" -> Alignment.BottomStart
        "bottom_center" -> Alignment.BottomCenter
        "bottom_end" -> Alignment.BottomEnd
        else -> Alignment.TopStart
    }
    val mod = when {
        onLongTap != null -> m.combinedClickable(
            onClick = { if (onTap != null) ctx.action(onTap) },
            onLongClick = { ctx.action(onLongTap) })
        onTap != null -> m.clickable { ctx.action(onTap) }
        else -> m
    }
    Box(modifier = mod, contentAlignment = alignment) {
        RenderChildren(node.arrOrNull("children"), ctx)
    }
}

@Composable
internal fun RenderSurfaceNode(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val color = resolveColor(node.stringOr("color").takeIf { it.isNotEmpty() })
        ?: MaterialTheme.colorScheme.surface
    // §16.5: a numeric universal corner overrides shape, except circle.
    // Beyond the three classics, `shape` accepts the M3 MaterialShapes
    // polygon vocabulary — resolved here so bg/clip/border honor it; an
    // unknown name stays rectangular per §12 rule 6.
    val base = when (val s = node.stringOr("shape")) {
        "rounded" -> RoundedCornerShape(8.dp)
        "rounded_small" -> RoundedCornerShape(4.dp)
        "circle" -> CircleShape
        else -> materialShapeOf(s) ?: RectangleShape
    }
    val shape = if ("corner" in node && base != CircleShape)
        cornerShape(node) else base
    Surface(
        modifier = m,
        color = color,
        shape = shape,
        tonalElevation = (safeDp(node.doubleOr("elevation", 0.0)) ?: 0f).dp,
        // §17.3: `elevation` is TONAL — applyTonalElevation returns the colour
        // unchanged for every container but colorScheme.surface, so it can
        // never make a container float. `shadow_elevation` is the cast shadow.
        shadowElevation = (safeDp(node.doubleOr("shadow_elevation", 0.0)) ?: 0f).dp) {
        RenderChildren(node.arrOrNull("children"), ctx)
    }
}

// ------------------------------------------------------------- lazy_column

/** Saveable keyed viewport. LazyList/Grid built-in Savers retain only a
 * numeric index; after an inactive keyed insertion that index names a
 * different child. The authored key closes that identity gap. */
internal data class KeyedViewportAnchor(
    val itemKey: String?,
    val fallbackIndex: Int,
    val scrollOffset: Int,
)

internal data class ResolvedViewport(val index: Int, val scrollOffset: Int)

/** Host-owned keyed viewport hand-off across ReusableContentHost
 * deactivation. The registry itself lives outside every branch, so its
 * current anchors survive even though Compose correctly forgets the active
 * LazyListState and all effects when that branch becomes inactive. */
internal class RetainedViewportAnchorRegistry(
    initial: Map<String, KeyedViewportAnchor> = emptyMap(),
) {
    private val anchors = LinkedHashMap(initial)

    fun anchor(providerPath: String): KeyedViewportAnchor? = anchors[providerPath]

    fun record(providerPath: String, anchor: KeyedViewportAnchor) {
        anchors[providerPath] = anchor
    }

    fun removeAll(providerPaths: Collection<String>) {
        providerPaths.forEach(anchors::remove)
    }

    internal fun saveableSnapshot(): List<Any> = buildList(anchors.size * 4) {
        anchors.forEach { (providerPath, anchor) ->
            add(providerPath)
            add(anchor.itemKey.orEmpty())
            add(anchor.fallbackIndex)
            add(anchor.scrollOffset)
        }
    }

    companion object {
        internal fun restore(snapshot: List<Any>): RetainedViewportAnchorRegistry {
            val restored = LinkedHashMap<String, KeyedViewportAnchor>()
            var index = 0
            while (index + 3 < snapshot.size) {
                val providerPath = snapshot[index] as? String ?: break
                val itemKey = snapshot[index + 1] as? String ?: break
                val fallbackIndex = (snapshot[index + 2] as? Number)?.toInt() ?: break
                val scrollOffset = (snapshot[index + 3] as? Number)?.toInt() ?: break
                restored[providerPath] = KeyedViewportAnchor(
                    itemKey.ifEmpty { null }, fallbackIndex, scrollOffset)
                index += 4
            }
            return RetainedViewportAnchorRegistry(restored)
        }
    }
}

private val RetainedViewportAnchorRegistrySaver =
    listSaver<RetainedViewportAnchorRegistry, Any>(
        save = { it.saveableSnapshot() },
        restore = { RetainedViewportAnchorRegistry.restore(it) },
    )

internal fun resolveKeyedViewportAnchor(
    saved: KeyedViewportAnchor,
    currentKeys: List<String>,
): ResolvedViewport {
    if (currentKeys.isEmpty()) return ResolvedViewport(0, 0)
    val keyedIndex = saved.itemKey?.let(currentKeys::indexOf) ?: -1
    return if (keyedIndex >= 0)
        ResolvedViewport(keyedIndex, saved.scrollOffset.coerceAtLeast(0))
    else ResolvedViewport(
        saved.fallbackIndex.coerceIn(currentKeys.indices),
        0,
    )
}

internal fun captureKeyedViewportAnchor(
    currentKeys: List<String>,
    index: Int,
    scrollOffset: Int,
): KeyedViewportAnchor = KeyedViewportAnchor(
    itemKey = currentKeys.getOrNull(index),
    fallbackIndex = index.coerceAtLeast(0),
    scrollOffset = scrollOffset.coerceAtLeast(0),
)

private val KeyedViewportAnchorSaver = listSaver<KeyedViewportAnchor, Any>(
    save = { listOf(it.itemKey.orEmpty(), it.fallbackIndex, it.scrollOffset) },
    restore = {
        KeyedViewportAnchor(
            itemKey = (it[0] as String).ifEmpty { null },
            fallbackIndex = (it[1] as Number).toInt(),
            scrollOffset = (it[2] as Number).toInt(),
        )
    },
)

/** Resolve an exact authored child anchor through the lazy column's bounded
 * chunk projection. ITEM_OFFSETS are child starts inside the target chunk;
 * null means the chunk must first be composed/measured before reconciliation
 * can finish. This preserves chunk amortization without reducing scroll
 * identity to the chunk's first row. */
internal fun resolveLazyColumnViewportAnchor(
    saved: KeyedViewportAnchor,
    projection: LazyColumnProjection,
    itemOffsets: Map<String, Int>,
): ResolvedViewport? {
    if (projection.childKeys.isEmpty() || projection.chunks.isEmpty())
        return ResolvedViewport(0, 0)
    val child = resolveKeyedViewportAnchor(saved, projection.childKeys)
    val chunkIndex = projection.chunks.indexOfFirst {
        child.index >= it.first && child.index < it.endExclusive
    }.takeIf { it >= 0 } ?: return ResolvedViewport(0, 0)
    val range = projection.chunks[chunkIndex]
    val key = projection.childKeys[child.index]
    val start = if (child.index == range.first) 0 else itemOffsets[key] ?: return null
    return ResolvedViewport(chunkIndex, start + child.scrollOffset)
}

/** Capture the exact child currently intersecting the viewport start inside
 * a visible chunk. Offsets include authored spacing because they are measured
 * positions, so a restore reproduces the same pixel within that child. */
internal fun captureLazyColumnViewportAnchor(
    projection: LazyColumnProjection,
    chunkIndex: Int,
    chunkScrollOffset: Int,
    itemOffsets: Map<String, Int>,
): KeyedViewportAnchor? {
    val range = projection.chunks.getOrNull(chunkIndex) ?: return null
    var childIndex = range.first
    var childStart = 0
    for (index in range.first until range.endExclusive) {
        val start = if (index == range.first) 0
            else itemOffsets[projection.childKeys[index]] ?: continue
        if (start <= chunkScrollOffset && start >= childStart) {
            childIndex = index
            childStart = start
        }
    }
    return KeyedViewportAnchor(
        itemKey = projection.childKeys[childIndex],
        fallbackIndex = childIndex,
        scrollOffset = (chunkScrollOffset - childStart).coerceAtLeast(0),
    )
}

/** The authored marker occurrence, including its array index. A stable keyed
 * child moving to a new index is a new scroll_here occurrence under §16.5 and
 * must be applied again even though its presentation identity is unchanged. */
internal fun lazyColumnScrollTargetToken(
    projection: LazyColumnProjection,
): String? {
    val range = projection.chunks.getOrNull(projection.scrollTargetChunk)
        ?: return null
    val childKey = projection.childKeys.getOrNull(range.first) ?: return null
    return "${range.first}:$childKey"
}

/** A newly authored scroll_here directive outranks retained scroll state.
 * Returning null for an already-applied marker occurrence is what lets a user
 * scroll away without every reactivation snapping back to it. */
internal fun pendingLazyColumnScrollTarget(
    projection: LazyColumnProjection,
    scrollTargetToken: String?,
    appliedScrollTargetToken: String?,
): ResolvedViewport? {
    if (scrollTargetToken == null ||
        scrollTargetToken == appliedScrollTargetToken)
        return null
    return projection.scrollTargetChunk.takeIf { it >= 0 }
        ?.let { ResolvedViewport(it, 0) }
}

@Composable
private fun rememberRetainedLazyColumnState(
    projection: LazyColumnProjection,
    itemOffsets: Map<String, Int>,
    itemOffsetGeneration: Any,
    presentationPath: String,
    scrollTargetToken: String?,
    viewportAnchors: RetainedViewportAnchorRegistry?,
    viewportProviderPath: String?,
): LazyListState {
    val emptyAnchor = KeyedViewportAnchor(null, 0, 0)
    // Inside a retained alternative, initialize from the host-owned anchor
    // that sits outside ReusableContentHost's deletion boundary. Elsewhere,
    // preserve the ordinary process-saveable behavior.
    val savedState = if (viewportAnchors != null && viewportProviderPath != null) {
        remember(presentationPath, viewportAnchors, viewportProviderPath) {
            mutableStateOf(viewportAnchors.anchor(viewportProviderPath) ?: emptyAnchor)
        }
    } else {
        rememberSaveable(
            presentationPath,
            stateSaver = KeyedViewportAnchorSaver,
        ) { mutableStateOf(emptyAnchor) }
    }
    var saved by savedState
    fun retain(anchor: KeyedViewportAnchor) {
        saved = anchor
        if (viewportAnchors != null && viewportProviderPath != null)
            viewportAnchors.record(viewportProviderPath, anchor)
    }
    val initialChild = resolveKeyedViewportAnchor(saved, projection.childKeys)
    val initialChunk = projection.chunks.indexOfFirst {
        initialChild.index >= it.first && initialChild.index < it.endExclusive
    }.coerceAtLeast(0)
    // Deliberately plain remember: ReusableContentHost deactivation forgets
    // the numeric-only state, and the keyed saveable anchor reconstructs it.
    val state = remember(presentationPath) {
        LazyListState(initialChunk, 0)
    }
    var appliedScrollTargetToken by rememberSaveable(presentationPath) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(
        state, projection.childKeys, projection.chunks, itemOffsets,
        itemOffsetGeneration, scrollTargetToken,
    ) {
        val forced = pendingLazyColumnScrollTarget(
            projection, scrollTargetToken, appliedScrollTargetToken)
        if (forced != null) {
            // Serialize authored targeting and retained reconciliation in one
            // coroutine. The former wins and cannot leave the latter waiting
            // on a chunk that the separate target effect scrolled away from.
            state.scrollToItem(forced.index, forced.scrollOffset)
            projection.chunks.getOrNull(forced.index)?.first?.let { child ->
                retain(captureKeyedViewportAnchor(
                    projection.childKeys, child, 0))
            }
            appliedScrollTargetToken = scrollTargetToken
        } else {
            if (scrollTargetToken == null) appliedScrollTargetToken = null
            // On restoration or an active keyed reorder, move to the old child
            // before restarting capture. An inner-chunk child needs one cheap
            // chunk-at-zero measure to learn its exact authored start.
            var target = resolveLazyColumnViewportAnchor(saved, projection, itemOffsets)
            if (target == null) {
                val child = resolveKeyedViewportAnchor(saved, projection.childKeys)
                val chunkIndex = projection.chunks.indexOfFirst {
                    child.index >= it.first && child.index < it.endExclusive
                }.coerceAtLeast(0)
                state.scrollToItem(chunkIndex, 0)
                val targetKey = projection.childKeys.getOrNull(child.index)
                if (targetKey != null) {
                    snapshotFlow { itemOffsets[targetKey] }
                        .filterNotNull().first()
                }
                target = resolveLazyColumnViewportAnchor(saved, projection, itemOffsets)
            }
            target?.let { state.scrollToItem(it.index, it.scrollOffset) }
        }
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.firstOrNull()?.let { first ->
                captureLazyColumnViewportAnchor(
                    projection,
                    first.index,
                    state.firstVisibleItemScrollOffset,
                    itemOffsets,
                )
            }
        }.filterNotNull().collect(::retain)
    }
    return state
}

@Composable
private fun rememberRetainedLazyGridState(
    keys: List<String>,
    presentationPath: String,
): LazyGridState {
    var saved by rememberSaveable(
        presentationPath,
        stateSaver = KeyedViewportAnchorSaver,
    ) { mutableStateOf(KeyedViewportAnchor(null, 0, 0)) }
    val initial = resolveKeyedViewportAnchor(saved, keys)
    val state = remember(presentationPath) {
        LazyGridState(initial.index, initial.scrollOffset)
    }
    LaunchedEffect(state, keys) {
        if (keys.isNotEmpty()) {
            val retained = resolveKeyedViewportAnchor(saved, keys)
            if (state.firstVisibleItemIndex != retained.index ||
                state.firstVisibleItemScrollOffset != retained.scrollOffset) {
                state.scrollToItem(retained.index, retained.scrollOffset)
            }
        }
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.firstOrNull()?.let { first ->
                KeyedViewportAnchor(
                    itemKey = first.key as? String,
                    fallbackIndex = first.index,
                    scrollOffset = state.firstVisibleItemScrollOffset,
                )
            }
        }.filterNotNull().collect { saved = it }
    }
    return state
}

/** One-item-per-slot keyed list state used by reorderable_list. Unlike the
 * stock numeric Saver, this reconciles both active and inactive authored
 * insert/reorder changes before it starts capturing the replacement order. */
@Composable
private fun rememberRetainedLazyItemListState(
    keys: List<String>,
    presentationPath: String,
): LazyListState {
    var saved by rememberSaveable(
        presentationPath,
        stateSaver = KeyedViewportAnchorSaver,
    ) { mutableStateOf(KeyedViewportAnchor(null, 0, 0)) }
    val initial = resolveKeyedViewportAnchor(saved, keys)
    val state = remember(presentationPath) {
        LazyListState(initial.index, initial.scrollOffset)
    }
    LaunchedEffect(state, keys) {
        if (keys.isNotEmpty()) {
            val retained = resolveKeyedViewportAnchor(saved, keys)
            if (state.firstVisibleItemIndex != retained.index ||
                state.firstVisibleItemScrollOffset != retained.scrollOffset) {
                state.scrollToItem(retained.index, retained.scrollOffset)
            }
        }
        snapshotFlow {
            captureKeyedViewportAnchor(
                keys,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
            )
        }.collect { saved = it }
    }
    return state
}

@Composable
internal fun RenderLazyColumn(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val children = node.arrOrNull("children") ?: return
    // Build keys and bounded presentation chunks once per accepted snapshot. A marked
    // scroll_here row always starts a chunk, so scrolling to the chunk retains
    // the contract's exact child-at-top behavior.
    val projection = remember(children) { lazyColumnProjection(children) }
    val scrollTargetToken = remember(projection) {
        lazyColumnScrollTargetToken(projection)
    }
    val itemSpacing = (safeDp(node.doubleOr("spacing", 0.0)) ?: 0f).dp
    // New map for every authored child ordering: stale offsets from an old
    // intra-chunk order, content height, or spacing must not be used to
    // reconcile the keyed viewport before the replacement is measured.
    val itemOffsets = remember(ctx.path, children, itemSpacing) {
        mutableStateMapOf<String, Int>()
    }
    val itemOffsetGeneration = remember(ctx.path, children, itemSpacing) { Any() }
    val retainedScope = LocalRetainedSaveableScope.current
    val viewportProviderPath = retainedScope?.providerPath(node)
    val listState = rememberRetainedLazyColumnState(
        projection,
        itemOffsets,
        itemOffsetGeneration,
        ctx.path,
        scrollTargetToken,
        retainedScope?.viewportAnchors,
        viewportProviderPath,
    )
    // The §17.6 body-scroll signal (see BodyScrollSignal).
    LocalBodyScrollSignal.current?.let { signal ->
        val atStart by remember {
            derivedStateOf { listState.firstVisibleItemIndex == 0 }
        }
        LaunchedEffect(atStart) { signal.atStart = atStart }
    }
    LazyColumn(
        state = listState,
        modifier = m.fillMaxSize(),
        contentPadding = PaddingValues(
            (safeDp(node.doubleOr("content_padding", 0.0)) ?: 0f).dp),
        verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
        items(
            count = projection.chunks.size,
            key = { projection.chunks[it].key },
            contentType = { projection.chunks[it].contentType },
        ) { i ->
            // Complete document pushes can add/remove hundreds of rows.
            // Stable chunk/row keys retain state; placement animation would
            // keep stale work alive after replacement and is intentionally off.
            RenderLazyChunk(
                lazyRenderChunk(children, projection, ctx.path, i),
                ctx,
                itemSpacing,
            ) { key, offset ->
                if (itemOffsets[key] != offset) itemOffsets[key] = offset
            }
        }
    }
}

// ----------------------------------------------------------- card + swipes

/** §17.3 swipe side chrome shared by card and collapsible: dispatch
 * on_trigger at most once per completed gesture, never settle dismissed. */
@Composable
internal fun SwipeActionBox(
    swipeStart: JsonObject?,
    swipeEnd: JsonObject?,
    ctx: RenderCtx,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val side = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> swipeStart
                SwipeToDismissBoxValue.EndToStart -> swipeEnd
                else -> null
            }
            side?.objOrNull("on_trigger")?.let {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                // §14.3: the swipe direction rides as an injected member.
                ctx.actionInjecting(it, buildJsonObject {
                    put("direction",
                        if (value == SwipeToDismissBoxValue.StartToEnd) "start" else "end")
                })
            }
            false // §17.3: the item returns to rest; the server's list decides.
        })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = swipeStart != null,
        enableDismissFromEndToStart = swipeEnd != null,
        backgroundContent = {
            val side = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> swipeStart
                SwipeToDismissBoxValue.EndToStart -> swipeEnd
                else -> null
            } ?: return@SwipeToDismissBox
            val bg = resolveColor(side.stringOr("color").takeIf { it.isNotEmpty() })
                ?: MaterialTheme.colorScheme.secondaryContainer
            val fg = if (bg.luminance() < 0.5f)
                androidx.compose.ui.graphics.Color.White
            else androidx.compose.ui.graphics.Color(0xFF1A1A1A)
            val label = side.stringOr("label")
            Box(
                Modifier.fillMaxSize().padding(vertical = 4.dp)
                    .background(bg, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment =
                    if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                        Alignment.CenterStart else Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    side.stringOr("icon").takeIf { it.isNotEmpty() }?.let {
                        Icon(IconMap.get(it), contentDescription = label.ifEmpty { null },
                            tint = fg)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (label.isNotEmpty())
                        Text(label, color = fg,
                            style = MaterialTheme.typography.labelLarge)
                }
            }
        }) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RenderCard(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val onTap = node.objOrNull("on_tap")
    val onLongTap = node.objOrNull("on_long_tap")
    val cardModifier = m.fillMaxWidth().then(
        if (onLongTap != null) Modifier.combinedClickable(
            onClick = { if (onTap != null) ctx.action(onTap) },
            onLongClick = { ctx.action(onLongTap) })
        else Modifier.clickable(enabled = onTap != null) {
            if (onTap != null) ctx.action(onTap)
        })
    val body: @Composable () -> Unit = {
        Box(Modifier.padding(16.dp)) {
            RenderChildren(node.arrOrNull("children"), ctx)
        }
    }
    // §17.3 `card.variant`: omitted stays `elevated`, which is the only card
    // this renderer has ever drawn, so no existing traffic changes meaning.
    val content: @Composable () -> Unit = {
        when (node.stringOr("variant")) {
            "filled" -> Card(modifier = cardModifier) { body() }
            "outlined" -> OutlinedCard(modifier = cardModifier) { body() }
            else -> ElevatedCard(modifier = cardModifier) { body() }
        }
    }
    val swipeStart = node.objOrNull("swipe_start")
    val swipeEnd = node.objOrNull("swipe_end")
    if (swipeStart != null || swipeEnd != null)
        SwipeActionBox(swipeStart, swipeEnd, ctx) { content() }
    else content()
}

// ------------------------------------------------------------- collapsible

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RenderCollapsible(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val collapsed = node.boolOr("collapsed")
    // §17.3: `collapsed` seeds only the FIRST snapshot for a presentation
    // identity; later same-identity pushes keep the user's expansion state.
    var expanded by rememberSaveable(ctx.path) { mutableStateOf(!collapsed) }
    val header = node.objOrNull("header")
    val onLongTap = node.objOrNull("on_long_tap")
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f, label = "chevron")
    Column(modifier = m.fillMaxWidth()) {
        val headerRow: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .ebpSemantics(
                        node = node,
                        onAction = { ctx.action(it) },
                        stateOverride = SemanticStateOverride(expanded = expanded),
                    )
                    .then(
                    if (onLongTap != null) Modifier.combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { ctx.action(onLongTap) })
                    else Modifier.clickable { expanded = !expanded })) {
                Icon(
                    IconMap.get("keyboard_arrow_down"),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.padding(4.dp)
                        .graphicsLayer { rotationZ = chevron })
                header?.let { RenderNode(it, ctx.child(it, 0)) }
            }
        }
        val swipeStart = node.objOrNull("swipe_start")
        val swipeEnd = node.objOrNull("swipe_end")
        if (swipeStart != null || swipeEnd != null)
            SwipeActionBox(swipeStart, swipeEnd, ctx) { headerRow() }
        else headerRow()
        if (expanded) RenderColumnChildren(node.arrOrNull("children"), ctx)
    }
}

// -------------------------------------------------------------------- tabs

/** A smaller same-identity snapshot adopts authored `initial` when either the
 * retained user selection or the pager's numeric state is no longer valid.
 * This reset is authored reconciliation and must never emit on_change. */
internal fun retainedTabsResetIndex(
    lastReported: Int,
    currentPage: Int,
    pageCount: Int,
    initial: Int,
): Int? = initial.takeIf {
    lastReported !in 0 until pageCount || currentPage !in 0 until pageCount
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RenderTabs(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val items = node.arrOrNull("items")
    val children = node.arrOrNull("children") ?: return
    val pageCount = children.size
    if (pageCount == 0) return
    // C6: `initial` is integer-TYPED but validated integral BY VALUE upstream
    // (SpecValidator floors an asDouble read, so `"initial": 2.0` is accepted
    // traffic and org.json's optInt truncated it). A spelling-strict read would
    // default it to 0 and silently open the tabs on the first page.
    val initial = node.intByValue("initial", 0).coerceIn(0, pageCount - 1)
    val onChange = node.objOrNull("on_change")

    // §16.1/§17.3: selection keys on the presentation identity — a new
    // identity resets to `initial`; a same-identity push preserves it.
    key(ctx.path) {
        val pagerState = rememberPagerState(initialPage = initial) { pageCount }
        val scope = rememberCoroutineScope()
        var lastReported by rememberSaveable(ctx.path) {
            mutableIntStateOf(initial)
        }
        // Clamp and user-settle reporting share one ordered coroutine. Two
        // independent effects could observe Compose's numeric page coercion in
        // opposite orders and report the authored no-emit reset as a gesture.
        LaunchedEffect(pagerState, pageCount, initial, onChange) {
            // SPEC 17.3: if the RETAINED index is no longer valid under a
            // smaller snapshot, select `initial` WITHOUT emitting on_change.
            // Check lastReported (the retained user index), not currentPage —
            // Compose silently coerces currentPage into range, which would mask
            // the invalid retained page.
            retainedTabsResetIndex(
                lastReported,
                pagerState.currentPage,
                pageCount,
                initial,
            )?.let { reset ->
                lastReported = reset
                pagerState.scrollToPage(reset)
            }
            snapshotFlow { pagerState.settledPage }.collect { settled ->
                if (settled != lastReported && settled in 0 until pageCount) {
                    lastReported = settled
                    // §17.3: on_change receives the settled index as args.value.
                    onChange?.let { ctx.action(it, JsonPrimitive(settled)) }
                }
            }
        }
        Column(modifier = m.fillMaxWidth()) {
            if (!node.boolOr("pager_only") && items != null) {
                val selected = pagerState.currentPage.coerceIn(0, pageCount - 1)
                val tabs: @Composable () -> Unit = {
                    for (i in 0 until minOf(items.size, pageCount)) {
                        val item = items[i] as? JsonObject ?: continue
                        val icon = item.stringOr("icon")
                        // §17.3: an ABSENT label is the icon-only 48dp tab.
                        // `label ""` is NOT the same thing — an empty Text
                        // still fills the slot and forces the 72dp two-line
                        // tab with a blank line, which is why the member is
                        // optional rather than defaulted.
                        val hasLabel = "label" in item
                        val badge = item.stringOr("badge")
                        val leading = icon.isNotEmpty() &&
                            item.stringOr("icon_position") == "leading"
                        val onClick = { scope.launch { pagerState.animateScrollToPage(i) }; Unit }
                        val badged: @Composable (@Composable () -> Unit) -> Unit = { c ->
                            BadgedBox(badge = {
                                if (badge.isNotEmpty()) Badge { Text(badge) } else Badge()
                            }) { c() }
                        }
                        val iconSlot: @Composable () -> Unit = {
                            val glyph: @Composable () -> Unit = {
                                Icon(IconMap.get(icon), contentDescription =
                                    if (hasLabel) null else item.stringOr("label")
                                        .ifEmpty { icon })
                            }
                            // A LeadingIconTab hangs its badge on the TITLE
                            // (M3's own sample) — badging the glyph there would
                            // stack two ornaments at the row's start.
                            if ("badge" in item && !leading) badged { glyph() }
                            else glyph()
                        }
                        val textSlot: (@Composable () -> Unit)? =
                            if (!hasLabel) null
                            else if ("badge" in item && leading)
                                { { badged { Text(item.stringOr("label")) } } }
                            else { { Text(item.stringOr("label")) } }
                        // §17.3 `content`: the tab draws the authored node
                        // instead of text/icon, `label` staying the
                        // accessibility name; `selected_content` is the face
                        // while selected — the device swaps the two authored
                        // nodes, the two_rows discipline again.
                        val contentNode = item.objOrNull("content")
                        val tab: @Composable () -> Unit = {
                            if (contentNode != null) {
                                val shown = if (selected == i)
                                    item.objOrNull("selected_content") ?: contentNode
                                else contentNode
                                Tab(selected = selected == i, onClick = onClick,
                                    modifier = Modifier.semantics {
                                        contentDescription = item.stringOr("label")
                                    }) {
                                    RenderNode(shown, ctx.child(shown, i))
                                }
                            } else if (leading)
                                LeadingIconTab(
                                    selected = selected == i, onClick = onClick,
                                    text = textSlot ?: {}, icon = iconSlot)
                            else Tab(
                                selected = selected == i, onClick = onClick,
                                text = textSlot,
                                icon = if (icon.isNotEmpty()) iconSlot else null)
                        }
                        // §17.3 `tooltip`: the PlainTooltip M3 wants on an
                        // icon-only tab, anchored Above — its own default.
                        val tip = item.stringOr("tooltip")
                        if (tip.isNotEmpty())
                            TooltipBox(
                                positionProvider = TooltipDefaults
                                    .rememberTooltipPositionProvider(
                                        TooltipAnchorPosition.Above),
                                tooltip = { PlainTooltip { Text(tip) } },
                                state = rememberTooltipState()) { tab() }
                        else tab()
                    }
                }
                // §17.3 `style`: secondary (the default) is the full-width
                // indicator this renderer has always drawn; primary is M3's
                // content-width rounded one. Naming them explicitly also
                // retires the deprecated TabRow/ScrollableTabRow calls.
                val primary = node.stringOr("style") == "primary"
                // §17.3 `indicator`: outline is the bounded FancyIndicator
                // vocabulary — a 2dp border in a RoundedCornerShape(5dp),
                // inset from the selected tab's bounds, still animated by the
                // standard tabIndicatorOffset. underline/absent keeps each
                // row's own default.
                val indSpec = node.objOrNull("indicator")
                val outline = indSpec?.stringOr("kind") == "outline"
                val indColor = resolveColor(
                    indSpec?.stringOr("color")?.takeIf { it.isNotEmpty() })
                    ?: MaterialTheme.colorScheme.primary
                val indInset = (indSpec?.doubleOr("inset", 5.0) ?: 5.0).dp
                if (node.boolOr("scrollable")) {
                    if (primary) PrimaryScrollableTabRow(selectedTabIndex = selected) { tabs() }
                    else SecondaryScrollableTabRow(selectedTabIndex = selected) { tabs() }
                } else {
                    if (primary) PrimaryTabRow(selectedTabIndex = selected) { tabs() }
                    else if (outline) SecondaryTabRow(
                        selectedTabIndex = selected,
                        indicator = {
                            Box(Modifier.tabIndicatorOffset(selected)
                                .fillMaxSize()
                                .padding(indInset)
                                .border(BorderStroke(2.dp, indColor),
                                    RoundedCornerShape(5.dp)))
                        }) { tabs() }
                    else SecondaryTabRow(selectedTabIndex = selected) { tabs() }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top) { page ->
                (children.getOrNull(page) as? JsonObject)?.let {
                    RenderNode(it, ctx.child(it, page))
                }
            }
        }
    }
}

// ------------------------------------------------------------------- table

private sealed interface TableRowSpec
private data class CellsSpec(val cells: List<JsonObject>, val header: Boolean) : TableRowSpec
private data class RuleSpec(val afterHeader: Boolean) : TableRowSpec

/** §17.3 table: columns size to their widest cell; rules draw as dividers;
 * header rows emphasize; on_add_row/on_add_col inject `index` (§14.3). */
@Composable
internal fun RenderTable(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val rowsJson = node.arrOrNull("rows") ?: return
    val specs = buildList {
        var prevHeader = false
        for (i in 0 until rowsJson.size) {
            val row = rowsJson[i] as? JsonObject ?: continue
            when (row.stringOr("kind")) { // The row discriminator is `kind`.
                "rule" -> { add(RuleSpec(afterHeader = prevHeader)); prevHeader = false }
                "header", "data" -> {
                    val cellsJson = row.arrOrNull("cells") ?: continue
                    val cells = buildList {
                        for (c in 0 until cellsJson.size)
                            (cellsJson[c] as? JsonObject)?.let { add(it) }
                    }
                    val header = row.stringOr("kind") == "header"
                    add(CellsSpec(cells, header))
                    prevHeader = header
                }
            }
        }
    }
    if (specs.none { it is CellsSpec && it.cells.isNotEmpty() }) return
    val aligns = buildList {
        node.arrOrNull("aligns")?.let { a ->
            for (i in 0 until a.size) add(a[i].strOrNull().orEmpty())
        }
    }
    val nrows = specs.count { it is CellsSpec }
    val ncols = specs.filterIsInstance<CellsSpec>().maxOf { it.cells.size }
    val onAddRow = node.objOrNull("on_add_row")
    val onAddCol = node.objOrNull("on_add_col")
    Column(modifier = m) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TableGrid(specs, aligns, ctx)
            if (onAddCol != null) AddAffordance("Add column") {
                ctx.actionInjecting(onAddCol, buildJsonObject { put("index", ncols) })
            }
        }
        if (onAddRow != null) AddAffordance("Add row") {
            ctx.actionInjecting(onAddRow, buildJsonObject { put("index", nrows) })
        }
    }
}

@Composable
private fun AddAffordance(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(IconMap.get("add"), contentDescription = description,
            tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TableGrid(specs: List<TableRowSpec>, aligns: List<String>, ctx: RenderCtx) {
    val ruleColor = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.ui.layout.Layout(content = {
        specs.forEach { spec ->
            when (spec) {
                is RuleSpec -> HorizontalDivider(
                    thickness = if (spec.afterHeader) 2.dp else 1.dp, color = ruleColor)
                is CellsSpec -> spec.cells.forEach { TableCell(it, spec.header, ctx) }
            }
        }
    }) { measurables, _ ->
        val loose = androidx.compose.ui.unit.Constraints()
        var mi = 0
        val cellRows = arrayOfNulls<List<androidx.compose.ui.layout.Placeable>>(specs.size)
        val pendingRules = mutableListOf<Pair<Int, Int>>()
        specs.forEachIndexed { r, spec ->
            when (spec) {
                is RuleSpec -> pendingRules.add(r to mi++)
                is CellsSpec -> cellRows[r] = spec.cells.map { measurables[mi++].measure(loose) }
            }
        }
        val ncols = specs.filterIsInstance<CellsSpec>().maxOf { it.cells.size }
        val colW = IntArray(ncols)
        cellRows.forEach { row ->
            row?.forEachIndexed { c, p -> if (p.width > colW[c]) colW[c] = p.width }
        }
        val totalW = colW.sum()
        val rules = arrayOfNulls<androidx.compose.ui.layout.Placeable>(specs.size)
        pendingRules.forEach { (r, i) ->
            rules[r] = measurables[i].measure(
                androidx.compose.ui.unit.Constraints.fixedWidth(totalW))
        }
        val rowH = IntArray(specs.size) { r ->
            rules[r]?.height ?: (cellRows[r]?.maxOfOrNull { it.height } ?: 0)
        }
        layout(totalW, rowH.sum()) {
            var y = 0
            specs.forEachIndexed { r, _ ->
                rules[r]?.placeRelative(0, y)
                cellRows[r]?.let { row ->
                    var x = 0
                    row.forEachIndexed { c, p ->
                        val slack = colW[c] - p.width
                        val dx = when (aligns.getOrNull(c)) {
                            "end" -> slack
                            "center" -> slack / 2
                            else -> 0
                        }
                        p.placeRelative(x + dx, y + (rowH[r] - p.height) / 2)
                        x += colW[c]
                    }
                }
                y += rowH[r]
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableCell(cell: JsonObject, header: Boolean, ctx: RenderCtx) {
    val onTap = cell.objOrNull("on_tap")
    val onLongTap = cell.objOrNull("on_long_tap")
    val scheme = MaterialTheme.colorScheme
    val annotated = buildSpanString(
        cell.arrOrNull("spans"), scheme.primary,
        { resolveColorIn(scheme, it) }, { ctx.action(it) })
    val base = MaterialTheme.typography.bodyMedium
    val clickMod = if (onTap != null || onLongTap != null)
        Modifier.combinedClickable(
            onClick = { onTap?.let { ctx.action(it) } },
            onLongClick = onLongTap?.let { { ctx.action(it) } })
    else Modifier
    Box(
        modifier = clickMod.defaultMinSize(minWidth = 28.dp, minHeight = 24.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart) {
        Text(annotated, style = if (header)
            base.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        else base)
    }
}

// --------------------------------------------------------- reorderable_list

internal data class ReorderablePresentation(
    val authoredRevision: String,
    val order: List<Int>,
)

/** Preserve a user order only for the exact authored item snapshot it came
 * from. A changed snapshot is server truth; malformed restored state also
 * resets defensively instead of indexing the wrong authored item. */
internal fun reconcileReorderablePresentation(
    saved: ReorderablePresentation,
    authoredRevision: String,
    itemCount: Int,
): ReorderablePresentation {
    val authoredOrder = (0 until itemCount.coerceAtLeast(0)).toList()
    return if (saved.authoredRevision == authoredRevision &&
        saved.order.size == authoredOrder.size &&
        saved.order.toSet() == authoredOrder.toSet()) saved
    else ReorderablePresentation(authoredRevision, authoredOrder)
}

/** Compact deterministic token for saveable state. Saving the full authored
 * JSON signature could put a multi-megabyte reorderable list in instance
 * state; the digest keeps the existing exact-snapshot reset behavior without
 * carrying application content. */
internal fun reorderableItemsRevision(items: JsonArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(items.toString().toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

internal fun reorderableDisplayKeys(
    authoredKeys: List<String>,
    order: List<Int>,
): List<String> = order.mapNotNull(authoredKeys::getOrNull)

/** §17.3 reorderable_list: arbitrary item nodes with unique key/id; a
 * completed handle-drag reorders locally and dispatches on_reorder with
 * from/to/order injected (§14.3). The authored list itself never mutates —
 * the server's next snapshot is the truth. */
@Composable
internal fun RenderReorderableList(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val onReorder = node.objOrNull("on_reorder")
    val itemsJson = node.arrOrNull("items") ?: return
    val itemKey = { i: Int ->
        val it = itemsJson.getOrNull(i) as? JsonObject
        it?.stringOr("key").takeIf { k -> !k.isNullOrEmpty() }
            ?: it?.stringOr("id").orEmpty()
    }
    // SPEC 14.3: `order` is an array of closed identity OBJECTS — {key: id} or
    // {id: id}, choosing `key` when the child has both — not bare strings.
    val itemIdentity = { i: Int ->
        val it = itemsJson.getOrNull(i) as? JsonObject
        val key = it?.stringOr("key").takeIf { k -> !k.isNullOrEmpty() }
        if (key != null) buildJsonObject { put("key", key) }
        else buildJsonObject { put("id", it?.stringOr("id").orEmpty()) }
    }
    // Display order as authored indices. Both the compact authored revision
    // and order are saveable so variant deactivation preserves a user reorder;
    // explicit reconciliation adopts a changed authored list even when that
    // update arrived while this branch was inactive (restore beats inputs).
    val itemsRevision = remember(itemsJson) { reorderableItemsRevision(itemsJson) }
    var savedItemsRevision by rememberSaveable(ctx.path) {
        mutableStateOf(itemsRevision)
    }
    var order by rememberSaveable(ctx.path) {
        mutableStateOf(ArrayList((0 until itemsJson.size).toList()))
    }
    val reconciledOrder = reconcileReorderablePresentation(
        ReorderablePresentation(savedItemsRevision, order),
        itemsRevision,
        itemsJson.size,
    )
    if (savedItemsRevision != reconciledOrder.authoredRevision ||
        order != reconciledOrder.order) {
        savedItemsRevision = reconciledOrder.authoredRevision
        order = ArrayList(reconciledOrder.order)
    }
    val authoredPresentationKeys = remember(itemsJson) { lazyChildKeys(itemsJson) }
    val displayKeys = remember(authoredPresentationKeys, order) {
        reorderableDisplayKeys(authoredPresentationKeys, order)
    }
    val listState = rememberRetainedLazyItemListState(displayKeys, ctx.path)
    val haptic = LocalHapticFeedback.current
    var draggedPos by remember { mutableStateOf<Int?>(null) } // position in `order`
    var dragStartPos by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    LazyColumn(
        state = listState,
        modifier = m.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)) {
        items(count = order.size, key = { pos -> displayKeys[pos] }) { pos ->
            val authored = order[pos]
            val item = itemsJson.getOrNull(authored) as? JsonObject ?: return@items
            val isDragged = draggedPos == pos
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().then(
                    if (isDragged) Modifier
                        .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                        .zIndex(1f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(8.dp))
                    else Modifier.animateItem())) {
                Icon(
                    IconMap.get("drag_handle"),
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        // SPEC 17.3: key the gesture on the item's STABLE
                        // identity, not the mutating `order` — keying on `order`
                        // restarts pointerInput mid-drag and aborts the gesture
                        // after the first swap.
                        .pointerInput(itemKey(authored)) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedPos = pos
                                    dragStartPos = pos
                                    dragOffsetY = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                    val dragged = draggedPos ?: return@detectDragGesturesAfterLongPress
                                    val info = listState.layoutInfo
                                    val dInfo = info.visibleItemsInfo
                                        .firstOrNull { it.index == dragged }
                                        ?: return@detectDragGesturesAfterLongPress
                                    val center = dInfo.offset + dInfo.size / 2 +
                                        dragOffsetY.roundToInt()
                                    val target = info.visibleItemsInfo.firstOrNull {
                                        it.index != dragged &&
                                            center in it.offset..(it.offset + it.size)
                                    }
                                    if (target != null) {
                                        val shift = if (target.index < dragged)
                                            target.offset - dInfo.offset
                                        else (target.offset + target.size) -
                                            (dInfo.offset + dInfo.size)
                                        order = ArrayList(order).apply {
                                            add(target.index, removeAt(dragged))
                                        }
                                        dragOffsetY -= shift
                                        draggedPos = target.index
                                    }
                                },
                                onDragEnd = {
                                    val to = draggedPos
                                    if (to != null && onReorder != null &&
                                        to != dragStartPos) {
                                        // §14.3: from/to/order injected; order
                                        // is the post-move identity-object list.
                                        ctx.actionInjecting(onReorder, buildJsonObject {
                                            put("from", dragStartPos)
                                            put("to", to)
                                            put("order", buildJsonArray {
                                                order.forEach { add(itemIdentity(it)) }
                                            })
                                        })
                                        haptic.performHapticFeedback(
                                            HapticFeedbackType.LongPress)
                                    }
                                    draggedPos = null; dragOffsetY = 0f
                                },
                                onDragCancel = { draggedPos = null; dragOffsetY = 0f })
                        }
                        .padding(12.dp))
                Box(Modifier.weight(1f)) {
                    RenderNode(item, ctx.child(item, authored))
                }
            }
            if (pos < order.size - 1)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }
}

/** §17.3 pane_scaffold: M3's adaptive two- or three-pane layout. The panes are
 * placed BY WINDOW SIZE — side by side where there is room, one at a time
 * where there is not — which is why a `row` of two columns was never a
 * substitute for it: a row is the wide layout always, on every screen.
 *
 * The navigator supplies both the directive (derived from the current window)
 * and the scaffold state, so the adaptation and the back stack come from one
 * object rather than needing a window-size message on the wire. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun RenderPaneScaffold(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val list = node.objOrNull("list") ?: return
    val detail = node.objOrNull("detail") ?: return
    val extra = node.objOrNull("extra")
    val supporting = node.stringOr("variant") == "supporting"
    // Each variant needs its OWN navigator. They differ in which pane ROLE is
    // the initial destination: the list-detail navigator opens on Secondary
    // (the list), the supporting one on Primary (the main pane). Sharing the
    // list-detail navigator across both made a single-pane `supporting`
    // scaffold surface its SUPPORTING pane first, where upstream surfaces the
    // main one — the panes were right and the entry point was wrong.
    val navigator =
        if (supporting) rememberSupportingPaneScaffoldNavigator<Any>()
        else rememberListDetailPaneScaffoldNavigator<Any>()
    val listPane: @Composable ThreePaneScaffoldPaneScope.() -> Unit = {
        AnimatedPane { RenderNode(list, ctx.child(list, 0)) }
    }
    val detailPane: @Composable ThreePaneScaffoldPaneScope.() -> Unit = {
        AnimatedPane { RenderNode(detail, ctx.child(detail, 1)) }
    }
    val extraPane: (@Composable ThreePaneScaffoldPaneScope.() -> Unit)? =
        extra?.let { { AnimatedPane { RenderNode(it, ctx.child(it, 2)) } } }
    if (supporting) SupportingPaneScaffold(
        directive = navigator.scaffoldDirective,
        scaffoldState = navigator.scaffoldState,
        mainPane = listPane,
        supportingPane = detailPane,
        extraPane = extraPane,
        modifier = m)
    else ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        scaffoldState = navigator.scaffoldState,
        listPane = listPane,
        detailPane = detailPane,
        extraPane = extraPane,
        modifier = m)
}

/** §17.3 Material app-bar row/column: M3's measuring overflow containers.
 *
 * `items` are {icon, label, on_tap, enabled?} records, rendered inline as
 * icon buttons while they FIT and folded into an overflow menu at measure
 * time — each folded item's label becoming its menu row. Which items fold
 * is a width decision made on the device per layout pass; Emacs never
 * learns it, which is why a static row-plus-menu split could never be
 * this component. `overflow_icon` renames the more_vert indicator;
 * `max_items` caps the inline count below what would fit. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RenderAppBarStrip(
    node: JsonObject, ctx: RenderCtx, m: Modifier, vertical: Boolean,
) {
    val items = node.arrOrNull("items") ?: return
    val overflowIcon = node.stringOr("overflow_icon")
    val maxItems = node.doubleOr("max_items", 0.0).toInt()
        .takeIf { it > 0 } ?: Int.MAX_VALUE
    val indicator: @Composable (AppBarMenuState) -> Unit =
        if (overflowIcon.isEmpty()) { state -> AppBarOverflowIndicator(state) }
        else { state ->
            IconButton(onClick = { state.show() }) {
                Icon(IconMap.get(overflowIcon), contentDescription = "More")
            }
        }
    val fill: AppBarScope.() -> Unit = {
        for (i in 0 until items.size) {
            val item = items[i] as? JsonObject ?: continue
            val onTap = item.objOrNull("on_tap")
            clickableItem(
                onClick = { if (onTap != null) ctx.action(onTap) },
                icon = { Icon(IconMap.get(item.stringOr("icon")),
                    contentDescription = null) },
                label = item.stringOr("label"),
                enabled = item.boolOr("enabled", true))
        }
    }
    if (vertical)
        AppBarColumn(modifier = m, overflowIndicator = indicator,
            maxItemCount = maxItems) { fill() }
    else AppBarRow(modifier = m, overflowIndicator = indicator,
        maxItemCount = maxItems) { fill() }
}

/** §17.3 carousel: M3's keyline carousel. The Companion runs ALL the
 * keyline math and the per-frame item mask; Emacs supplies `children`
 * only and never learns the width — the same device-owns-presentation
 * split as tabs' page and collapsible's expansion.
 *
 * `strategy` picks the M3 form: multi_browse (the default —
 * preferredItemWidth keylines with small items at the edges),
 * uncontained (fixed itemWidth, items run off the edge), or
 * centered_hero (one large centered item). `item_corner` is the
 * maskClip radius, applied to the item's LIVE mask rect rather than
 * the node box — a carousel clip breathes with the keylines. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RenderCarousel(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val children = node.arrOrNull("children") ?: return
    val keys = remember(children) { lazyChildKeys(children) }
    var savedAnchor by rememberSaveable(
        ctx.path,
        stateSaver = KeyedViewportAnchorSaver,
    ) { mutableStateOf(KeyedViewportAnchor(null, 0, 0)) }
    val initial = resolveKeyedViewportAnchor(savedAnchor, keys)
    val currentItemCount = rememberUpdatedState(children.size)
    // CarouselState's Saver is numeric-only like the lazy containers. Its
    // fractional offset is intentionally reset, but the current authored
    // child identity survives insertion/reorder while inactive.
    val state = remember(ctx.path) {
        CarouselState(initial.index, 0f) { currentItemCount.value }
    }
    LaunchedEffect(state, keys) {
        // The state object survives an active snapshot replacement and stores
        // only a numeric page. Reconcile the continuously captured OLD key
        // before observing the new layout, or an insertion before the current
        // page would immediately capture the wrong authored child.
        val retained = resolveKeyedViewportAnchor(savedAnchor, keys)
        if (state.currentItem != retained.index)
            state.scrollToItem(retained.index)
        snapshotFlow { state.currentItem }.collect { index ->
            savedAnchor = captureKeyedViewportAnchor(keys, index, 0)
        }
    }
    val spacing = (safeDp(node.doubleOr("item_spacing", 0.0)) ?: 0f).dp
    val contentPad = PaddingValues(
        horizontal = (safeDp(node.doubleOr("content_padding", 0.0)) ?: 0f).dp)
    val itemWidth = (safeDp(node.doubleOr("item_width", 186.0)) ?: 186f).dp
    val corner = node["item_corner"]?.numOrNull()
    val item: @Composable CarouselItemScope.(Int) -> Unit = { i ->
        val child = children[i] as? JsonObject
        if (child != null) {
            val mask = if (corner != null)
                Modifier.maskClip(RoundedCornerShape(corner.toFloat().dp))
            else Modifier
            Box(mask) { RenderNode(child, ctx.child(child, i)) }
        }
    }
    when (node.stringOr("strategy")) {
        "uncontained" -> HorizontalUncontainedCarousel(
            state = state, modifier = m.fillMaxWidth(),
            itemWidth = itemWidth, itemSpacing = spacing,
            contentPadding = contentPad, content = item)
        "centered_hero" -> HorizontalCenteredHeroCarousel(
            state = state, modifier = m.fillMaxWidth(),
            itemSpacing = spacing, contentPadding = contentPad,
            content = item)
        else -> HorizontalMultiBrowseCarousel(
            state = state, modifier = m.fillMaxWidth(),
            preferredItemWidth = itemWidth, itemSpacing = spacing,
            contentPadding = contentPad, content = item)
    }
}

/** §17.3 Material FAB menu: the checkable FAB whose menu unfolds above it.
 *
 * The ToggleFloatingActionButton drives an Add-to-Close icon morph from
 * its own checked progress (`icon`/`close_icon` name the two ends); the
 * expansion is Companion-local presentation, exactly search_bar's split —
 * a menu that snapped shut on every re-push would be unusable. Items are
 * {icon, label, on_tap} records, each a FloatingActionButtonMenuItem. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RenderFabMenu(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val items = node.arrOrNull("items") ?: return
    val openName = node.stringOr("icon").ifEmpty { "add" }
    val closeName = node.stringOr("close_icon").ifEmpty { "close" }
    var expanded by rememberSaveable { mutableStateOf(false) }
    FloatingActionButtonMenu(
        modifier = m,
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = !expanded },
            ) {
                // The morph swaps the glyph at half progress and animates
                // it with the container — upstream's own recipe.
                val glyph by remember {
                    derivedStateOf {
                        if (checkedProgress > 0.5f) closeName else openName
                    }
                }
                with(ToggleFloatingActionButtonDefaults) {
                    Icon(
                        painter = rememberVectorPainter(IconMap.get(glyph)),
                        contentDescription =
                            if (expanded) "Close menu" else "Open menu",
                        modifier = Modifier.animateIcon({ checkedProgress }),
                    )
                }
            }
        }) {
        for (i in 0 until items.size) {
            val item = items[i] as? JsonObject ?: continue
            val onTap = item.objOrNull("on_tap")
            FloatingActionButtonMenuItem(
                onClick = {
                    expanded = false
                    if (onTap != null) ctx.action(onTap)
                },
                icon = { Icon(IconMap.get(item.stringOr("icon")),
                    contentDescription = null) },
                text = { Text(item.stringOr("label")) })
        }
    }
}

/** §17.3 button_group: M3's ButtonGroup. {label, on_tap, icon?, enabled?}
 * items whose press animation couples neighbours; what does not fit moves
 * into an overflow menu at MEASURE time — the same never-ask-Emacs width
 * rule as the app-bar strips. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RenderButtonGroup(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val items = node.arrOrNull("items") ?: return
    val overflowIcon = node.stringOr("overflow_icon")
    val indicator: @Composable (ButtonGroupMenuState) -> Unit =
        if (overflowIcon.isEmpty()) { state ->
            ButtonGroupDefaults.OverflowIndicator(menuState = state)
        } else { state ->
            IconButton(onClick = { state.show() }) {
                Icon(IconMap.get(overflowIcon), contentDescription = "More")
            }
        }
    ButtonGroup(modifier = m, overflowIndicator = indicator) {
        for (i in 0 until items.size) {
            val item = items[i] as? JsonObject ?: continue
            val onTap = item.objOrNull("on_tap")
            val iconName = item.stringOr("icon")
            clickableItem(
                onClick = { if (onTap != null) ctx.action(onTap) },
                label = item.stringOr("label"),
                icon = iconName.takeIf { it.isNotEmpty() }?.let {
                    { Icon(IconMap.get(it), contentDescription = null) }
                },
                enabled = item.boolOr("enabled", true))
        }
    }
}

/** §17.3 lazy_grid: LazyVerticalGrid over the child array, order preserved
 * exactly as lazy_column. `min_item_width` (GridCells.Adaptive) outranks
 * `columns` (GridCells.Fixed, default 2); `reverse` is reverseLayout —
 * the Companion never asks Emacs which end is the start. */
@Composable
internal fun RenderLazyGrid(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val children = node.arrOrNull("children") ?: return
    val keys = remember(children) { lazyChildKeys(children) }
    val minItem = node["min_item_width"]?.numOrNull()?.let { safeDp(it) }
    val cells = if (minItem != null) GridCells.Adaptive(minItem.dp)
        else GridCells.Fixed(node.doubleOr("columns", 2.0).toInt().coerceAtLeast(1))
    val spacing = (safeDp(node.doubleOr("spacing", 0.0)) ?: 0f).dp
    val pad = (safeDp(node.doubleOr("content_padding", 0.0)) ?: 0f).dp
    val gridState = rememberRetainedLazyGridState(keys, ctx.path)
    // The §17.6 body-scroll signal; the Companion derives at-start itself,
    // reverseLayout included — never Emacs.
    LocalBodyScrollSignal.current?.let { signal ->
        val atStart by remember {
            derivedStateOf { gridState.firstVisibleItemIndex == 0 }
        }
        LaunchedEffect(atStart) { signal.atStart = atStart }
    }
    // Same stable, typed, on-demand projection as lazy_column.
    LazyVerticalGrid(
        columns = cells,
        state = gridState,
        reverseLayout = node.boolOr("reverse"),
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        contentPadding = PaddingValues(pad),
        modifier = m.fillMaxWidth()) {
        items(
            count = children.size,
            key = { keys[it] },
            contentType = {
                (children[it] as? JsonObject)?.stringOrNull("t").orEmpty()
            },
        ) { i ->
            Box(Modifier.animateItem()) {
                RenderLazyItem(lazyRenderItem(children, keys, ctx.path, i), ctx)
            }
        }
    }
}

/** §17.3 `surface.shape`: the M3 MaterialShapes polygon vocabulary, one
 * wire name per polygon, resolved to a Shape so the surface's own clip
 * carries it (upstream clips a bare Spacer the same way). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun materialShapeOf(name: String): Shape? = when (name) {
        "square" -> MaterialShapes.Square
        "slanted" -> MaterialShapes.Slanted
        "arch" -> MaterialShapes.Arch
        "fan" -> MaterialShapes.Fan
        "arrow" -> MaterialShapes.Arrow
        "semi_circle" -> MaterialShapes.SemiCircle
        "oval" -> MaterialShapes.Oval
        "pill" -> MaterialShapes.Pill
        "triangle" -> MaterialShapes.Triangle
        "diamond" -> MaterialShapes.Diamond
        "clam_shell" -> MaterialShapes.ClamShell
        "pentagon" -> MaterialShapes.Pentagon
        "gem" -> MaterialShapes.Gem
        "sunny" -> MaterialShapes.Sunny
        "very_sunny" -> MaterialShapes.VerySunny
        "cookie_4_sided" -> MaterialShapes.Cookie4Sided
        "cookie_6_sided" -> MaterialShapes.Cookie6Sided
        "cookie_7_sided" -> MaterialShapes.Cookie7Sided
        "cookie_9_sided" -> MaterialShapes.Cookie9Sided
        "cookie_12_sided" -> MaterialShapes.Cookie12Sided
        "ghostish" -> MaterialShapes.Ghostish
        "clover_4_leaf" -> MaterialShapes.Clover4Leaf
        "clover_8_leaf" -> MaterialShapes.Clover8Leaf
        "burst" -> MaterialShapes.Burst
        "soft_burst" -> MaterialShapes.SoftBurst
        "boom" -> MaterialShapes.Boom
        "soft_boom" -> MaterialShapes.SoftBoom
        "flower" -> MaterialShapes.Flower
        "puffy" -> MaterialShapes.Puffy
        "puffy_diamond" -> MaterialShapes.PuffyDiamond
        "pixel_circle" -> MaterialShapes.PixelCircle
        "pixel_triangle" -> MaterialShapes.PixelTriangle
        "bun" -> MaterialShapes.Bun
        "heart" -> MaterialShapes.Heart
    else -> null
}?.toShape()
