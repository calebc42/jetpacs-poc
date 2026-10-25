// SPDX-License-Identifier: GPL-3.0-or-later
// S11: the presented screen's back target, read out of the spec as data.
// CHROME-VOCABULARY's back contract — "every pushed screen participates in the
// stack and the system back gesture" — only holds if the system gesture runs
// the SAME descriptor the on-screen arrow carries, so exactly one reader
// decides what back means and MainActivity's BackHandler dispatches what it
// returns. Pure: no Compose, no bridge, no Android.
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.renderer.model.vocabulary.generated.IconButtonMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.ScaffoldMembers
import com.calebc42.ebp.renderer.model.*

import kotlinx.serialization.json.JsonObject

/**
 * The `view.switch` descriptor of VIEW's chrome back arrow, or null when this
 * screen has none — the stack bottom, a non-scaffold root, or a top bar whose
 * leading slot is something else. A scaffold without an arrow may declare its
 * gesture in `on_back` (amendment #200), which is returned instead. Null means the system default proceeds and
 * the Activity finishes, which is the pre-S11 behavior for every screen.
 *
 * VIEW is the RESOLVED current view (the app host collapses the
 * stack's multi_view before publishing), so "this screen's back arrow" is
 * unambiguous — the views below it are not in this tree at all.
 *
 * The match is deliberately narrow, because whatever this returns HIJACKS the
 * system back gesture. Only the top bar's LEADING spine is considered, so a
 * drawer row, a body button, or a trailing bar action carrying `view.switch`
 * can never claim back — they switch views on tap and must keep doing only
 * that. The spine descends first-children through the plain containers because
 * a bar is authored either as the icon row itself (jetpacs-chrome-screen) or
 * as a box that row fills (the m3 catalog's center-aligned bar, whose title is
 * centered OVER the icons).
 *
 * A `view.switch` naming no view is refused: CompanionEngine.executeBuiltin
 * drops it, and an ENABLED handler that does nothing is strictly worse than
 * the system default it displaced.
 */
fun chromeBackDescriptor(
    view: JsonObject,
    surfaceId: String? = null,
    connected: Boolean = true,
): JsonObject? {
    val scaffold = presentedScaffold(view) ?: return null
    val leading = ScaffoldMembers.TopBar.read(scaffold)?.let(::leadingNode)
    if (leading != null &&
        leading.stringOr("t") == "icon_button" &&
        IconButtonMembers.Icon.read(leading) == "arrow_back"
    ) {
        // A spec-disabled arrow is untappable on screen; the gesture must not
        // out-tap the button it mirrors.
        if (IconButtonMembers.Enabled.read(leading) == false) return null
        val onTap = IconButtonMembers.OnTap.read(leading) ?: return null
        return validBackActionDescriptor(onTap, surfaceId, connected)
    }
    // Amendment #200: a screen drawing no arrow may still declare its back
    // gesture, as a top-level destination above a parent screen does.
    ScaffoldMembers.OnBack.read(scaffold)?.let { onBack ->
        return validBackActionDescriptor(onBack, surfaceId, connected)
    }
    if (!connected) return null
    return primaryDockHomeDescriptor(scaffold, surfaceId)
}

private fun validBackActionDescriptor(
    onTap: JsonObject,
    surfaceId: String?,
    connected: Boolean,
): JsonObject? = when (onTap.stringOr("builtin")) {
    "view.switch" -> onTap.takeIf { it.stringOr("view").isNotEmpty() }
    "surface.open" -> onTap.takeIf { it.stringOr("surface").isNotEmpty() }
    "" -> {
        val action = onTap.stringOr("action")
        val targetSurface = onTap.stringOr("open_surface")
        when {
            action.isEmpty() || targetSurface.isEmpty() -> null
            surfaceId != null && targetSurface == surfaceId && !connected -> null
            else -> onTap
        }
    }
    else -> null
}

private data class DockCandidate(
    val selected: Boolean,
    val onTap: JsonObject?,
)

/**
 * Primary-dock fallback for peer destinations that suppress the top-bar back
 * arrow while away from the primary app's home tab (Invariant I-14).
 *
 * Only a primary `app.open` dock whose first tab is unselected qualifies, and
 * only when the current surface is either that home surface itself or the
 * target surface of whichever peer tab is currently selected.
 */
private fun primaryDockHomeDescriptor(
    scaffold: JsonObject,
    surfaceId: String?,
): JsonObject? {
    val candidates = railDockCandidates(scaffold)
        ?: bottomBarDockCandidates(scaffold)
        ?: return null
    if (candidates.size < 2) return null
    val home = candidates.first()
    if (home.selected) return null
    val homeTap = home.onTap ?: return null
    if (homeTap.stringOr("action") != "app.open") return null
    val homeSurface = homeTap.stringOr("open_surface")
    if (homeSurface.isEmpty()) return null
    val matchesHomeSurface = surfaceId == null || homeSurface == surfaceId
    val matchesSelectedPeerSurface = surfaceId != null &&
        candidates.drop(1).any { peer ->
            peer.selected && peer.onTap?.stringOr("open_surface") == surfaceId
        }
    return homeTap.takeIf { matchesHomeSurface || matchesSelectedPeerSurface }
}

private fun railDockCandidates(scaffold: JsonObject): List<DockCandidate>? {
    val rail = ScaffoldMembers.Rail.read(scaffold) ?: return null
    if (rail.stringOr("t") != "navigation_rail") return null
    val items = rail.arrOrNull("items") ?: return null
    return items.mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        DockCandidate(
            selected = item.boolOr("selected"),
            onTap = item.objOrNull("on_tap"),
        )
    }
}

private fun bottomBarDockCandidates(scaffold: JsonObject): List<DockCandidate>? {
    val bottomBar = ScaffoldMembers.BottomBar.read(scaffold) ?: return null
    if (bottomBar.stringOr("t") != "row") return null
    val children = AnyNodeChildren.read(bottomBar) ?: return null
    if (children.isEmpty() || children.any { it.stringOr("t") != "box" }) return null
    return children.map { tab ->
        val column = AnyNodeChildren.read(tab)?.firstOrNull()
        val indicator = column?.let { AnyNodeChildren.read(it)?.firstOrNull() }
        val selected = tab.boolOr("selected") ||
            indicator?.stringOr("bg") == "secondary"
        DockCandidate(
            selected = selected,
            onTap = tab.objOrNull("on_tap"),
        )
    }
}

/** How many presentation wrappers a screen may sit inside, as the host bounds it. */
private const val PRESENTATION_WRAPPER_DEPTH = 4

/**
 * The scaffold VIEW presents, descending presentation wrappers.
 *
 * An app may present its screen inside another node — a design scope is the
 * first such wrapper — and the host composes its chrome into the scaffold
 * underneath. The back arrow lives there too, so this reader descends the
 * same way rather than refusing a presented screen and silently handing the
 * gesture back to the system. A wrapper is any non-scaffold node with exactly
 * one child; anything else is not a presented screen and yields null.
 */
private fun presentedScaffold(view: JsonObject): JsonObject? {
    var node = view
    var depth = 0
    while (true) {
        if (node.stringOr("t") == "scaffold") return node
        if (depth++ >= PRESENTATION_WRAPPER_DEPTH) return null
        val children = AnyNodeChildren.read(node) ?: return null
        if (children.size != 1) return null
        node = children[0]
    }
}

/** The containers a top bar wraps its icons in; each one's FIRST child is the
 * leading position. `tooltip` belongs here because the house style wraps bar
 * icons in one (`jetpacs-tooltip` carries its anchor as its first child, the
 * badge shape) — without it, a tipped back arrow would silently lose the
 * system gesture while still drawing the button. `content_description` is NOT
 * part of the test: it is a11y metadata a hand-authored `jetpacs-scaffold`
 * screen may omit while wearing a structurally identical arrow, and such a
 * screen is exactly what the contract sentence says must participate. */
private val BAR_CONTAINERS = setOf("row", "box", "column", "tooltip")

/** Bounded so a deep first-child chain cannot walk out of the bar and into
 * content that is not the back affordance: the authored shapes need one
 * descent (row), two (box > row), or three with a tipped arrow
 * (box > row > tooltip). */
private const val MAX_BAR_NESTING = 3

private fun leadingNode(topBar: JsonObject): JsonObject? {
    var node = topBar
    var depth = 0
    while (node.stringOr("t") in BAR_CONTAINERS) {
        if (depth++ == MAX_BAR_NESTING) return null
        node = AnyNodeChildren.read(node)?.firstOrNull() ?: return null
    }
    return node
}
