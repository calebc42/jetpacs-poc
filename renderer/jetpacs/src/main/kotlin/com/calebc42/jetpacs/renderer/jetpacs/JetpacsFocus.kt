// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager

/**
 * [focusable] whose focus interactions reach the style state only while the
 * user is driving with a keyboard.
 *
 * A profile's `focused` rule draws the focus ring. Compose still moves focus
 * programmatically in touch mode (a drawer opening hands focus to its first
 * row), and a ring that appears without a key press reads as a stray
 * selection. The node stays focusable for accessibility and keyboard travel
 * either way; only the interaction feed is gated, so the ring appears the
 * moment a key is pressed and never on a tap.
 */
@Composable
fun Modifier.jetpacsFocusable(
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
): Modifier {
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    return focusable(enabled, if (keyboard) interactionSource else null)
}
