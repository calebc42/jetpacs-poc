// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import com.calebc42.ebp.renderer.compose.RevealSwipeCellWidth
import com.calebc42.ebp.renderer.compose.RevealSwipeDirection
import com.calebc42.ebp.renderer.compose.RevealSwipeRow
import com.calebc42.ebp.renderer.compose.RevealSwipeSide
import com.calebc42.ebp.renderer.compose.SwipePopupAnchor
import com.calebc42.ebp.renderer.compose.SwipePopupRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Reveal-first swipe for the Foundation renderers.
 *
 * The gesture, its thresholds and the one-row-open coordinator all live in the
 * shared `RevealSwipeRow`; this file owns only the wire reading and the drawn
 * action cells, so the Foundation and Material rows travel identically and
 * differ only in how the strip is painted.
 */

/** One authored swipe action after the wire's two shapes are collapsed. */
internal data class JetpacsSwipeAction(
    val label: String,
    val icon: String?,
    val color: String?,
    val descriptor: JsonObject,
)

/** One authored side: its actions and the travel behavior they imply. */
internal data class JetpacsSwipeSide(
    val actions: List<JetpacsSwipeAction>,
    val behavior: RevealSwipeSide,
    /** Amendment #207: `presentation: "popup"` opens a floating row instead of revealing. */
    val popup: Boolean = false,
)

/**
 * Read one wire swipe object, or null when it offers nothing to run.
 *
 * SPEC 17.3 gives a side two shapes: the legacy single action written inline,
 * and the rich `actions` list with an optional deep-swipe `commit`. An action
 * without `on_trigger` cannot run, so it is dropped rather than drawn as a
 * dead cell; a side left with none is not swipeable at all.
 */
internal fun jetpacsSwipeSide(raw: JsonObject?): JetpacsSwipeSide? {
    raw ?: return null
    val rich = raw["actions"] as? JsonArray
    val authored = rich?.mapNotNull { it as? JsonObject } ?: listOf(raw)
    val actions = authored.mapNotNull { action ->
        val descriptor = action["on_trigger"] as? JsonObject ?: return@mapNotNull null
        JetpacsSwipeAction(
            label = action.text("label"),
            icon = action.text("icon").takeIf(String::isNotEmpty),
            color = action.text("color").takeIf(String::isNotEmpty),
            descriptor = descriptor,
        )
    }
    if (actions.isEmpty()) return null
    return JetpacsSwipeSide(
        actions = actions,
        behavior = RevealSwipeSide(
            actionCount = actions.size.coerceAtMost(4),
            commitFirstOnDeepSwipe = rich != null && raw.flag("commit"),
            legacyCommitOnRelease = rich == null,
        ),
        popup = rich != null && raw.text("presentation") == "popup",
    )
}

/**
 * Wrap [foreground] in the reveal-first gesture for the authored sides.
 *
 * [identity] must be the node's presentation path: it keys the offset and the
 * open-row coordinator, so a recycled list row would otherwise inherit its
 * neighbour's revealed state. The coordinator itself is provided once at the
 * surface root and is deliberately not re-provided here.
 *
 * When neither side is authored the foreground is drawn directly, so an
 * ordinary row costs no gesture machinery.
 */
@Composable
internal fun JetpacsSwipeRow(
    identity: Any,
    swipeStart: JsonObject?,
    swipeEnd: JsonObject?,
    context: ComposeNodeRenderContext,
    modifier: Modifier = Modifier,
    foreground: @Composable BoxScope.(isOpen: Boolean, close: () -> Unit) -> Unit,
) {
    val start = jetpacsSwipeSide(swipeStart)
    val end = jetpacsSwipeSide(swipeEnd)
    if (start == null && end == null) {
        androidx.compose.foundation.layout.Box(modifier) { foreground(false) {} }
        return
    }
    // Amendment #207: a popup side opens a floating action row beside the
    // gesture and never displaces the row. The first authored popup side
    // decides for the whole row; a mixed row is a popup row.
    if (start?.popup == true || end?.popup == true) {
        JetpacsSwipePopupRow(identity, start, end, context, modifier, foreground)
        return
    }
    val haptic = LocalHapticFeedback.current
    RevealSwipeRow(
        identity = identity,
        modifier = modifier,
        start = start?.behavior,
        end = end?.behavior,
        onAction = { direction, index, fromGestureCommit ->
            val side = if (direction == RevealSwipeDirection.Start) start else end
            side?.actions?.getOrNull(index)?.let { action ->
                // The commit gesture has no button to press, so the haptic is
                // its only acknowledgement; a tapped cell already has one.
                if (fromGestureCommit) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                context.swipeAction(action.descriptor, direction)
            }
        },
        underlay = { direction, invoke ->
            val side = if (direction == RevealSwipeDirection.Start) start else end
            side?.let { JetpacsSwipeStrip(it, direction, invoke) }
        },
        foreground = { isOpen, close -> foreground(isOpen, close) },
    )
}

/** The revealed action strip: one cell per action, anchored to its own edge. */
@Composable
private fun BoxScope.JetpacsSwipeStrip(
    side: JetpacsSwipeSide,
    direction: RevealSwipeDirection,
    invoke: (Int) -> Unit,
) {
    val roles = JetpacsTheme.roles
    val colors = JetpacsTheme.colors
    val slotBackground = resolvedDesignColor(
        DesignComponentStyleSlot.SwipeCell,
        DesignProperty.BackgroundColor,
    )
    val labelStyle = resolvedDesignTextStyle(
        DesignComponentStyleSlot.SwipeLabel,
        JetpacsTheme.typography.choice,
    )
    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = if (direction == RevealSwipeDirection.Start) {
            Arrangement.Start
        } else {
            Arrangement.End
        },
    ) {
        side.actions.forEachIndexed { index, action ->
            // The authored color is the action's own signal and outranks the
            // profile; the slot supplies the resting face when none is given.
            val background = action.color
                ?.let { spec -> DesignThemeRole.fromWireName(spec)?.let { roles[it] } ?: parseHexColor(spec) }
                ?: slotBackground
                ?: colors.selectedSurface
            val content = if (background.luminance() < 0.5f) Color.White else Color(0xFF1A1A1A)
            Column(
                Modifier
                    .width(RevealSwipeCellWidth)
                    .fillMaxHeight()
                    .background(background)
                    .clickable { invoke(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                action.icon?.let { icon ->
                    DesignGlyph(icon, content, 17.dp)
                    Spacer(Modifier.size(2.dp))
                }
                BasicText(
                    action.label,
                    style = labelStyle.copy(color = content, textAlign = TextAlign.Center),
                    maxLines = 2,
                )
            }
        }
    }
}

private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.flag(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false


/**
 * Amendment #207: the popup presentation. A horizontal gesture past the
 * reveal threshold opens the side's actions as a floating row of tonal icon
 * buttons at the gesture's end, above the touch and never above the row —
 * Revived's NotePopup. The row itself never moves.
 */
@Composable
private fun JetpacsSwipePopupRow(
    identity: Any,
    start: JetpacsSwipeSide?,
    end: JetpacsSwipeSide?,
    context: ComposeNodeRenderContext,
    modifier: Modifier,
    foreground: @Composable BoxScope.(isOpen: Boolean, close: () -> Unit) -> Unit,
) {
    var anchor by remember(identity) { mutableStateOf<SwipePopupAnchor?>(null) }
    SwipePopupRow(
        hasStart = start != null,
        hasEnd = end != null,
        onOpen = { anchor = it },
        modifier = modifier,
    ) {
        foreground(false) {}
        anchor?.let { open ->
            val side = if (open.direction == RevealSwipeDirection.Start) start else end
            if (side != null) {
                JetpacsSwipePopup(
                    side = side,
                    offset = IntOffset(open.x, open.y),
                    onPick = { action ->
                        anchor = null
                        context.swipeAction(action.descriptor, open.direction)
                    },
                    onDismiss = { anchor = null },
                )
            }
        }
    }
}

/** The floating action row: one tonal icon button per action, in the popup slots. */
@Composable
private fun JetpacsSwipePopup(
    side: JetpacsSwipeSide,
    offset: IntOffset,
    onPick: (JetpacsSwipeAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val styleState = remember { MutableStyleState(null) }
    val colors = JetpacsTheme.colors
    val provider = remember(offset) { SwipePopupPositionProvider(offset) }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // Revived's NotePopup is a bare row of buttons: no card behind them.
        // The `swipe.popup` slot may give it one.
        Row(
            modifier = Modifier.styleable(
                styleState,
                designComponentStyle(DesignComponentStyleSlot.SwipePopup),
            ),
        ) {
            side.actions.forEach { action ->
                val background = resolvedDesignColor(
                    DesignComponentStyleSlot.SwipePopupButton,
                    DesignProperty.BackgroundColor,
                ) ?: colors.selectedSurface
                val content = resolvedDesignColor(
                    DesignComponentStyleSlot.SwipePopupButton,
                    DesignProperty.ContentColor,
                ) ?: colors.content
                Column(
                    modifier = Modifier
                        .padding(
                            horizontal = SwipePopupButtonInsetX,
                            vertical = SwipePopupButtonInsetY,
                        )
                        .size(SwipePopupButtonSize)
                        .clip(RoundedCornerShape(SwipePopupButtonSize / 2))
                        .background(background)
                        .clickable { onPick(action) }
                        .semantics { contentDescription = action.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val icon = action.icon
                    if (icon != null) {
                        DesignGlyph(icon, content, SwipePopupIconSize)
                    } else {
                        BasicText(
                            action.label,
                            style = JetpacsTheme.typography.choice.copy(color = content),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Place the popup at [offset] inside the row, kept wholly inside the window
 * as Android's PopupWindow keeps Revived's: near the edge the row slides
 * back rather than running off it.
 */
private class SwipePopupPositionProvider(private val offset: IntOffset) :
    androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + offset.x)
            .coerceAtMost(windowSize.width - popupContentSize.width)
            .coerceAtLeast(0)
        val y = (anchorBounds.top + offset.y)
            .coerceAtMost(windowSize.height - popupContentSize.height)
            .coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * Revived's NotePopupButtonFilledTonal, as drawn on the Pixel Tablet: a 44dp
 * disc with a 24dp glyph, 52dp apart, 9dp below the row's top.
 */
private val SwipePopupButtonSize = 44.dp
private val SwipePopupIconSize = 24.dp
private val SwipePopupButtonInsetX = 4.dp
private val SwipePopupButtonInsetY = 9.dp
