// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.createRippleModifierNode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.Dp
import com.calebc42.jetpacs.renderer.jetpacs.DesignRippleFactory

/**
 * The platform touch ripple for the Material-free design renderer: an
 * authored `ripple_color` (its alpha the pressed strength) and, when
 * borderless, `ripple_radius`.
 */
val PlatformDesignRipple = DesignRippleFactory { color, radius -> PlatformRipple(color, radius) }

private class PlatformRipple(private val color: Color, private val radius: Dp?) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        createRippleModifierNode(
            interactionSource = interactionSource,
            bounded = radius == null,
            radius = radius ?: Dp.Unspecified,
            color = { color.copy(alpha = 1f) },
            rippleAlpha = { RippleAlpha(color.alpha, color.alpha, color.alpha, color.alpha) },
        )

    override fun equals(other: Any?): Boolean =
        other is PlatformRipple && other.color == color && other.radius == radius

    override fun hashCode(): Int = 31 * color.hashCode() + radius.hashCode()
}
