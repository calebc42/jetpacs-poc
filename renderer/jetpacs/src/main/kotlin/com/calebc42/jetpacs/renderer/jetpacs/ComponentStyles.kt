// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleScope
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.StyleStateKey
import androidx.compose.foundation.style.animate
import androidx.compose.foundation.style.border
import androidx.compose.foundation.style.contentPadding
import androidx.compose.foundation.style.disabled
import androidx.compose.foundation.style.fillWidth
import androidx.compose.foundation.style.focused
import androidx.compose.foundation.style.hovered
import androidx.compose.foundation.style.pressed
import androidx.compose.foundation.style.selected
import androidx.compose.foundation.style.state
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.dp

private val StyleScope.tokens: JetpacsThemeValue
    get() = LocalJetpacsTheme.currentValue

private val textFieldErrorKey = StyleStateKey(false)
private val editorReadOnlyKey = StyleStateKey(false)

internal var MutableStyleState.isTextFieldError: Boolean
    get() = this[textFieldErrorKey]
    set(value) { this[textFieldErrorKey] = value }

private fun StyleScope.textFieldError(block: () -> Unit) {
    state(textFieldErrorKey, block) { key, current -> current[key] }
}

internal var MutableStyleState.isEditorReadOnly: Boolean
    get() = this[editorReadOnlyKey]
    set(value) { this[editorReadOnlyKey] = value }

private fun StyleScope.editorReadOnly(block: () -> Unit) {
    state(editorReadOnlyKey, block) { key, current -> current[key] }
}

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

    /** Page-free projection strip; selection behavior remains in [JetpacsTabs]. */
    val tabs = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
    }

    /** One state-aware tab in the controlled projection strip. */
    val tab = Style {
        background(tokens.colors.surface)
        contentPadding(
            // Fixed rows must keep four common projection labels readable at
            // enlarged font scales; the tab itself remains the 48dp target.
            horizontal = tokens.spacing.unit,
            vertical = tokens.spacing.controlVertical,
        )
        selected { animate { background(tokens.colors.selectedSurface) } }
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    /** Selected marker kept separate so tab labels remain stable during animation. */
    val tabIndicator = Style {
        shape(tokens.shapes.indicator)
        background(tokens.colors.accent)
    }

    /** Shared inline layout for controlled tab and section navigators. */
    val navigator = Style {
        fillWidth()
        background(tokens.colors.surface)
    }

    /** Previous and Next controls retain a full target in every input mode. */
    val navigatorButton = Style {
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    /** The selected-value trigger for the bounded option popup. */
    val navigatorSelector = Style {
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
        selected { background(tokens.colors.selectedSurface) }
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    /** Window-bounded popup shared by peer-view and document navigation. */
    val navigatorPopup = Style {
        shape(tokens.shapes.panel)
        background(tokens.colors.raisedSurface)
        border(1.dp, tokens.colors.outline)
    }

    /** One keyed popup destination with state-aware focus and selection. */
    val navigatorPopupItem = Style {
        fillWidth()
        background(tokens.colors.surface)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
        selected { background(tokens.colors.selectedSurface) }
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    /** Inspectable outline navigator container; behavior remains controlled. */
    val sectionNavigator = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
    }

    val textFieldOutlined = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        textFieldError {
            border(1.dp, tokens.colors.error)
            focused { border(2.dp, tokens.colors.error) }
        }
        disabled { alpha(0.38f) }
    }

    val textFieldFilled = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.raisedSurface)
        border(1.dp, androidx.compose.ui.graphics.Color.Transparent)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
        hovered { background(tokens.colors.selectedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        textFieldError {
            border(1.dp, tokens.colors.error)
            focused { border(2.dp, tokens.colors.error) }
        }
        disabled { alpha(0.38f) }
    }

    /** Stable editor work-surface visuals; text layout never participates in animation. */
    val editor = Style {
        fillWidth()
        shape(tokens.shapes.panel)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentPadding(tokens.spacing.controlVertical)
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        editorReadOnly { background(tokens.colors.raisedSurface) }
        disabled { alpha(0.38f) }
    }

    /** Borderless editor visuals for EBP `chromeless`; behavior is unchanged. */
    val editorChromeless = Style {
        fillWidth()
        background(androidx.compose.ui.graphics.Color.Transparent)
        contentPadding(horizontal = 0.dp, vertical = tokens.spacing.unit)
        editorReadOnly { background(tokens.colors.raisedSurface) }
        disabled { alpha(0.38f) }
    }

    /** Non-animated rail containing independently accessible editor actions. */
    val editorToolbar = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.raisedSurface)
        border(1.dp, tokens.colors.outline)
        contentPadding(tokens.spacing.unit)
        disabled { alpha(0.38f) }
    }

    /** Compact non-animated lifecycle notice below a synchronized editor. */
    val editorSyncStatus = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.selectedSurface)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.unit,
        )
    }

    /** Bounded inline completion surface that remains attached to editor layout. */
    val editorCompletionList = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.raisedSurface)
        border(1.dp, tokens.colors.outline)
        contentPadding(tokens.spacing.unit)
    }

    /** One independently focusable completion row; behavior stays in modifiers. */
    val editorCompletionItem = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
        hovered { background(tokens.colors.selectedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { background(tokens.colors.pressedSurface) }
    }

    /** Non-interactive plain-text candidate documentation peek. */
    val editorCandidateDocument = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
    }

    /** Diagnostic or eldoc status below the sole editable semantics owner. */
    val editorToolingStatus = Style {
        fillWidth()
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.unit,
        )
    }

    /** Compact, non-animated toolbar item with a full platform touch target. */
    val editorToolbarItem = Style {
        shape(tokens.shapes.control)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentPadding(horizontal = 10.dp, vertical = tokens.spacing.unit)
        hovered { background(tokens.colors.selectedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { background(tokens.colors.pressedSurface) }
        disabled { alpha(0.38f) }
    }
}
