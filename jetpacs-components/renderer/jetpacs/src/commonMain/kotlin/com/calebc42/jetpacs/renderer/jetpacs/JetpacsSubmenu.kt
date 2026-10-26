// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.style.Style
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Whether this platform cascades a submenu beside its parent popup rather
 * than showing it in the parent's place. Android cascades on a display whose
 * smallest side is at least 720dp, as AppCompat's menus do; a desktop always
 * has the room.
 */
@Composable
internal expect fun platformCascadesSubmenus(): Boolean

/**
 * A cascading submenu (amendment #203): [parent]'s items in a popup of their
 * own beside the parent row, its first row level with the parent row. Back
 * or a tap outside closes it alone; a pick runs through [onSelect].
 */
@Composable
@OptIn(InternalJetpacsRendererApi::class)
internal fun CascadedSubmenu(
    parent: JetpacsMenuItem,
    shadowMargin: Dp,
    onDismiss: () -> Unit,
    onSelect: (JetpacsMenuItem) -> Unit,
    popupStyle: Style,
    itemStyle: Style,
    itemLabelStyle: Style,
    itemSupportingStyle: Style,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val provider = remember(layoutDirection, density, shadowMargin) {
        with(density) {
            BesideRowPositionProvider(
                layoutDirection,
                shadowMargin = shadowMargin.roundToPx(),
                firstRowInset = SubmenuFirstRowInset.roundToPx(),
            )
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = shadowMargin == 0.dp,
        ),
    ) {
        Box(Modifier.padding(shadowMargin)) {
            JetpacsMenuPopupContent(
                items = parent.children,
                onSelect = onSelect,
                popupStyle = popupStyle,
                itemStyle = itemStyle,
                itemLabelStyle = itemLabelStyle,
                itemSupportingStyle = itemSupportingStyle,
            )
        }
    }
}

/** AppCompat's cascading popup tops level with its parent row. */
private val SubmenuFirstRowInset = 0.dp

/**
 * Beside the anchor row: after its end edge, or before its start edge when
 * the window has no room after it, and level with its top less
 * [firstRowInset], clamped into the window. [shadowMargin] is transparent
 * room around the content the window carries for its shadow.
 */
internal class BesideRowPositionProvider(
    private val preferredLayoutDirection: LayoutDirection,
    private val shadowMargin: Int = 0,
    private val firstRowInset: Int = 0,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = (popupContentSize.width - 2 * shadowMargin).coerceAtLeast(0)
        val height = (popupContentSize.height - 2 * shadowMargin).coerceAtLeast(0)
        val after = anchorBounds.right
        val before = anchorBounds.left - width
        val rtl = preferredLayoutDirection == LayoutDirection.Rtl
        val x = when {
            !rtl && after + width <= windowSize.width -> after
            rtl && before >= 0 -> before
            !rtl -> before
            else -> after
        }.coerceIn(0, (windowSize.width - width).coerceAtLeast(0))
        val y = (anchorBounds.top - firstRowInset)
            .coerceIn(0, (windowSize.height - height).coerceAtLeast(0))
        return IntOffset(x - shadowMargin, y - shadowMargin)
    }
}
