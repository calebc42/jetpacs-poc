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
import androidx.compose.foundation.style.scale
import androidx.compose.foundation.style.selected
import androidx.compose.foundation.style.state
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.TextStyle
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

    val actionLabel = Style { textStyle(tokens.typography.action) }

    /** Shared geometry of every canonical `button` variant: a pill with a full target. */
    private fun StyleScope.buttonBase() {
        shape(androidx.compose.foundation.shape.RoundedCornerShape(50))
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal + tokens.spacing.unit,
            vertical = tokens.spacing.controlVertical,
        )
        hovered { alpha(0.92f) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { scale(0.98f) } }
        disabled { alpha(0.38f) }
    }

    val buttonFilled = Style {
        buttonBase()
        background(tokens.colors.accent)
        contentColor(tokens.colors.onAccent)
    }

    val buttonTonal = Style {
        buttonBase()
        background(tokens.colors.selectedSurface)
        contentColor(tokens.colors.content)
    }

    /** No shadow model exists in the design language; elevation reads as a raised surface. */
    val buttonElevated = Style {
        buttonBase()
        background(tokens.colors.raisedSurface)
        border(1.dp, tokens.colors.outline)
        contentColor(tokens.colors.accent)
    }

    val buttonOutlined = Style {
        buttonBase()
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentColor(tokens.colors.accent)
    }

    val buttonText = Style {
        buttonBase()
        background(androidx.compose.ui.graphics.Color.Transparent)
        contentColor(tokens.colors.accent)
    }

    val buttonLabel = Style { textStyle(tokens.typography.action) }

    /** Canonical `chip`: a compact selectable pill with a selected face. */
    val chip = Style {
        shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentColor(tokens.colors.content)
        contentPadding(horizontal = tokens.spacing.controlHorizontal, vertical = 6.dp)
        selected {
            animate {
                background(tokens.colors.selectedSurface)
                border(1.dp, tokens.colors.accent)
                contentColor(tokens.colors.accent)
            }
        }
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    val chipLabel = Style { textStyle(tokens.typography.choice) }

    /** Canonical `divider`: a hairline in the outline color. */
    val divider = Style {
        fillWidth()
        background(tokens.colors.outline)
    }

    val sectionHeader = Style {
        fillWidth()
        contentPadding(horizontal = 0.dp, vertical = tokens.spacing.unit)
    }

    /** Shared geometry of every canonical `card` variant. */
    private fun StyleScope.cardBase() {
        fillWidth()
        shape(tokens.shapes.panel)
        contentPadding(tokens.spacing.panel)
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    val cardFilled = Style {
        cardBase()
        background(tokens.colors.selectedSurface)
        contentColor(tokens.colors.content)
    }

    /** No shadow model exists here; elevation reads as a raised surface. */
    val cardElevated = Style {
        cardBase()
        background(tokens.colors.raisedSurface)
        contentColor(tokens.colors.content)
    }

    val cardOutlined = Style {
        cardBase()
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentColor(tokens.colors.content)
    }

    /**
     * One list row.
     *
     * Deliberately flat by default — no border, no raised fill — because a
     * row is a region of a list rather than an object floating above it. A
     * profile that wants cards binds `list-item.container` to a surface style.
     */
    val listItem = Style {
        fillWidth()
        shape(tokens.shapes.control)
        background(androidx.compose.ui.graphics.Color.Transparent)
        contentColor(tokens.colors.content)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        selected { animate { background(tokens.colors.selectedSurface) } }
        disabled { alpha(0.38f) }
    }

    val listItemOverline = Style {
        textStyle(tokens.typography.fieldLabel)
        contentColor(tokens.colors.mutedContent)
    }

    val listItemTitle = Style { textStyle(tokens.typography.choice) }

    val listItemSubtitle = Style {
        textStyle(tokens.typography.fieldSupporting)
        contentColor(tokens.colors.mutedContent)
    }

    /** The hairline a list draws between rows. */
    val listItemSeparator = Style {
        fillWidth()
        background(tokens.colors.outline)
    }

    /**
     * A bare icon target.
     *
     * Transparent by default: most icon buttons live in a bar or a row where
     * a container would be visual noise. `variant` opts into a filled or
     * outlined face, as the canonical member says.
     */
    val iconButton = Style {
        shape(androidx.compose.foundation.shape.RoundedCornerShape(50))
        background(androidx.compose.ui.graphics.Color.Transparent)
        contentColor(tokens.colors.content)
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    val iconButtonFilled = Style {
        background(tokens.colors.accent)
        contentColor(tokens.colors.onAccent)
    }

    val iconButtonOutlined = Style {
        border(1.dp, tokens.colors.outline)
        contentColor(tokens.colors.accent)
    }

    /** A compact status pill; an empty label is the bare attention dot. */
    val badge = Style {
        shape(androidx.compose.foundation.shape.RoundedCornerShape(50))
        background(tokens.colors.selectedSurface)
        contentColor(tokens.colors.content)
        contentPadding(horizontal = 6.dp, vertical = 2.dp)
    }

    val badgeLabel = Style { textStyle(tokens.typography.fieldLabel) }

    val emptyState = Style {
        fillWidth()
        contentColor(tokens.colors.mutedContent)
        contentPadding(tokens.spacing.panel + tokens.spacing.panel)
    }

    val emptyStateTitle = Style {
        textStyle(tokens.typography.choice)
        contentColor(tokens.colors.content)
    }

    val emptyStateCaption = Style {
        textStyle(tokens.typography.fieldSupporting)
        contentColor(tokens.colors.mutedContent)
    }

    val swipeCell = Style { background(tokens.colors.selectedSurface) }

    val swipeLabel = Style { textStyle(tokens.typography.fieldLabel) }

    val sectionHeaderTitle = Style {
        textStyle(tokens.typography.panelLabel)
        contentColor(tokens.colors.accent)
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

    val choiceIndicator = Style {
        shape(tokens.shapes.indicator)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        selected {
            background(tokens.colors.accent)
            border(1.dp, tokens.colors.accent)
            contentColor(tokens.colors.onAccent)
        }
    }

    val choiceLabel = Style { textStyle(tokens.typography.choice) }

    val panel = Style {
        fillWidth()
        shape(tokens.shapes.panel)
        background(tokens.colors.surface)
        border(1.dp, tokens.colors.outline)
        contentPadding(tokens.spacing.panel)
    }

    val panelLabel = Style { textStyle(tokens.typography.panelLabel) }

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

    val tabLabel = Style {
        textStyle(tokens.typography.choice)
        textAlign(androidx.compose.ui.text.style.TextAlign.Center)
    }

    /** The overflow control that opens a menu; a full target in every mode. */
    val menuTrigger = Style {
        shape(tokens.shapes.control)
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
        disabled { alpha(0.38f) }
    }

    /** The window-bounded menu surface; its own padding frames the rows. */
    val menuPopup = Style {
        shape(tokens.shapes.panel)
        background(tokens.colors.raisedSurface)
        border(1.dp, tokens.colors.outline)
        contentPadding(horizontal = 0.dp, vertical = tokens.spacing.unit)
    }

    /** One menu row, checkable or not, with the shared five-state face. */
    val menuItem = Style {
        fillWidth()
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

    val menuItemLabel = Style { textStyle(tokens.typography.choice) }

    val menuItemSupporting = Style { textStyle(tokens.typography.caption) }

    /** A group heading inside a menu, above its hairline. */
    val menuGroupLabel = Style {
        textStyle(tokens.typography.label)
        contentPadding(
            horizontal = tokens.spacing.controlHorizontal,
            vertical = tokens.spacing.controlVertical,
        )
    }

    /** The switch row: one full-width toggle target with the label leading. */
    val switchRow = Style {
        fillWidth()
        minHeight(48.dp)
        contentPadding(horizontal = 0.dp, vertical = tokens.spacing.controlVertical)
    }

    /** The track, whose selected face is the whole "on" signal. */
    val switchTrack = Style {
        width(52.dp)
        height(32.dp)
        shape(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        background(tokens.colors.raisedSurface)
        border(1.dp, tokens.colors.outline)
        selected { animate { background(tokens.colors.accent) } }
        focused { border(2.dp, tokens.colors.focus) }
        disabled { alpha(0.38f) }
    }

    /** The thumb that slides along the track; its glyph, if any, rides on it. */
    val switchThumb = Style {
        width(24.dp)
        height(24.dp)
        shape(androidx.compose.foundation.shape.CircleShape)
        background(tokens.colors.mutedContent)
        selected { animate { background(tokens.colors.onAccent) } }
    }

    val switchLabel = Style { textStyle(tokens.typography.choice) }

    /** The disclosure header: chevron then the authored header, one row. */
    val collapsibleHeader = Style {
        fillWidth()
        minHeight(48.dp)
        hovered { background(tokens.colors.raisedSurface) }
        focused { border(2.dp, tokens.colors.focus) }
        pressed { animate { background(tokens.colors.pressedSurface) } }
    }

    /** The chevron's cell: a full target, since it is what toggles. */
    val collapsibleChevron = Style {
        width(40.dp)
        height(48.dp)
        textStyle(TextStyle(color = tokens.colors.mutedContent))
    }

    /** The revealed children, indented under the header's content. */
    val collapsibleBody = Style {
        fillWidth()
        contentPadding(horizontal = 0.dp, vertical = 0.dp)
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

    val navigatorLabel = Style { textStyle(tokens.typography.choice) }

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

    val textFieldText = Style { textStyle(tokens.typography.field) }
    val textFieldCode = Style {
        textStyle(tokens.typography.code)
        contentColor(tokens.colors.content)
    }
    val textFieldLabel = Style { textStyle(tokens.typography.fieldLabel) }
    val textFieldPlaceholder = Style {
        textStyle(tokens.typography.field)
        contentColor(tokens.colors.mutedContent)
    }
    val textFieldSupporting = Style { textStyle(tokens.typography.fieldSupporting) }
    val textFieldAffix = Style { textStyle(tokens.typography.fieldLabel) }

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

    val editorText = Style {
        textStyle(tokens.typography.code)
        contentColor(tokens.colors.content)
    }
    val editorGutter = Style {
        textStyle(tokens.typography.code)
        contentColor(tokens.colors.mutedContent)
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
