// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleScope
import androidx.compose.foundation.style.animate
import androidx.compose.foundation.style.border
import androidx.compose.foundation.style.contentPadding
import androidx.compose.foundation.style.disabled
import androidx.compose.foundation.style.fillWidth
import androidx.compose.foundation.style.focused
import androidx.compose.foundation.style.hovered
import androidx.compose.foundation.style.pressed
import androidx.compose.foundation.style.selected
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.dp

private val StyleScope.tokens: JetpacsThemeValue
    get() = LocalJetpacsTheme.currentValue

/** Theme-wide visual definitions for Jetpacs-owned components. */
@Stable
object JetpacsComponentStyles {
    val action = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.raisedSurface)
        border(1.dp, tokens.colors.outline)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
        hovered { background(tokens.colors.selectedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed {
            animate { background(tokens.colors.pressedSurface) }
        }
        disabled { alpha(0.38f) }
    }

    val choice = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
        selected {
            animate {
                background(tokens.colors.selectedSurface)
                border(1.dp, tokens.colors.accent)
            }
        }
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    val panel = Style {
        fillWidth()
        shape(tokens.shapes.panel)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentPadding(tokens.spacing.panel)
    }
}
