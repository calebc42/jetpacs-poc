// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.calebc42.ebp.renderer.compose.TextInputController
import com.calebc42.ebp.renderer.compose.TextInputSuggestions
import kotlin.math.roundToInt

/**
 * SPEC 17.4 `suggestions` in Material: the matches for the word at the local
 * cursor in a menu surface under the field, as wide as it. It never takes
 * focus; a pick replaces the word as the user's own edit.
 */
internal class MaterialWordSuggestions {
    private var focused by mutableStateOf(false)
    private var fieldBounds by mutableStateOf<IntRect?>(null)
    private var dismissedAt by mutableStateOf<String?>(null)

    fun fieldModifier(modifier: Modifier): Modifier = modifier
        .onFocusChanged { focused = it.isFocused }
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            fieldBounds = IntRect(
                bounds.left.roundToInt(), bounds.top.roundToInt(),
                bounds.right.roundToInt(), bounds.bottom.roundToInt(),
            )
        }

    @Composable
    fun Popup(controller: TextInputController, suggestions: List<String>) {
        val text = controller.state.text.toString()
        val selection = controller.state.selection
        val bounds = fieldBounds
        if (!focused || bounds == null || text == dismissedAt) return
        val word = TextInputSuggestions.wordAt(text, selection.start, selection.end) ?: return
        val matches = TextInputSuggestions.matching(text.substring(word), suggestions)
        if (matches.isEmpty()) return
        val density = LocalDensity.current
        val provider = remember { BelowField { fieldBounds } }
        Popup(
            popupPositionProvider = provider,
            onDismissRequest = { dismissedAt = text },
            properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
        ) {
            Surface(
                modifier = Modifier.width(with(density) { bounds.width.toDp() }),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Column {
                    for (suggestion in matches) {
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                controller.applyLocalEdit(word.first, word.last + 1, "$suggestion ")
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Under the field's window bounds, flipped above when there is no room below. */
private class BelowField(private val field: () -> IntRect?) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchor = field() ?: anchorBounds
        val x = anchor.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchor.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchor.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
