// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
 * The field side of SPEC 17.4 `suggestions`: tracks the field's focus and
 * window bounds through [fieldModifier], and [Popup] lists the suggestions
 * the word at the local cursor begins, under the field and as wide as it, in
 * the scope's menu slots. The popup never takes focus, so typing goes on; a
 * pick replaces the word as the user's own edit.
 */
internal class WordSuggestionsState {
    var focused by mutableStateOf(false)
    var fieldBounds by mutableStateOf<IntRect?>(null)
    /** The text an outside tap dismissed the list at; it returns on the next edit. */
    var dismissedAt by mutableStateOf<String?>(null)

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
        val layoutDirection = LocalLayoutDirection.current
        val shadowMargin = LocalDesignScope.current
            ?.componentStyle(DesignComponentStyleSlot.MenuContainer).authoredElevation()
            ?.let { (elevation, _) -> elevation * 2 } ?: 0.dp
        // The keyboard covers the window's bottom: the list flips above the
        // field when it does not fit between the field and the keyboard.
        val imeBottom = WindowInsets.ime.getBottom(density)
        val keyboard = rememberUpdatedState(imeBottom)
        val provider = remember(layoutDirection, density, shadowMargin) {
            FieldAnchoredPositionProvider(
                { fieldBounds },
                { keyboard.value },
                JetpacsAnchoredPopupPositionProvider(
                    layoutDirection,
                    shadowMargin = with(density) { shadowMargin.roundToPx() },
                ),
            )
        }
        Popup(
            popupPositionProvider = provider,
            onDismissRequest = { dismissedAt = text },
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = true,
                clippingEnabled = shadowMargin == 0.dp,
            ),
        ) {
            @OptIn(InternalJetpacsRendererApi::class)
            JetpacsMenuPopupContent(
                modifier = Modifier
                    .padding(shadowMargin)
                    .width(with(density) { bounds.width.toDp() }),
                items = matches.map { suggestion ->
                    JetpacsMenuItem(label = suggestion, onSelect = {
                        controller.applyLocalEdit(word.first, word.last + 1, "$suggestion ")
                    })
                },
                onSelect = { item -> item.onSelect() },
            )
        }
    }
}

/** Places a popup against the field's window bounds rather than its own parent. */
private class FieldAnchoredPositionProvider(
    private val field: () -> IntRect?,
    private val keyboardHeight: () -> Int,
    private val inner: PopupPositionProvider,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = inner.calculatePosition(
        field() ?: anchorBounds,
        IntSize(windowSize.width, (windowSize.height - keyboardHeight()).coerceAtLeast(0)),
        layoutDirection,
        popupContentSize,
    )
}
