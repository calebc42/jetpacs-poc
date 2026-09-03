// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** Maximum number of complete options visible before a navigator popup scrolls. */
internal const val MAX_VISIBLE_NAVIGATOR_OPTIONS = 8

/** Renderer-private option shape shared by peer-view and document navigators. */
@Immutable
internal data class JetpacsNavigatorOption(
    val label: String,
    val value: String,
    val accessibleLabel: String,
    val level: Int = 1,
)

/** Keeps the shared interaction machinery from conflating tabs with headings. */
internal enum class JetpacsNavigatorSemantics {
    Tabs,
    Sections,
}

/**
 * Enforce the controlled-selection invariants before any keyed composition.
 *
 * Empty option lists intentionally remain a rendering no-op for compatibility.
 * Every non-empty list must have unique non-empty values and contain [value].
 */
internal fun validateJetpacsControlledOptions(
    componentName: String,
    optionValues: List<String>,
    value: String,
) {
    if (optionValues.isEmpty()) return
    require(optionValues.all { it.isNotEmpty() }) {
        "$componentName option values must be non-empty"
    }
    require(optionValues.size == optionValues.toSet().size) {
        "$componentName option values must be unique"
    }
    require(value in optionValues) {
        "$componentName value must select an authored option"
    }
}

/**
 * A controlled Previous / selector / Next navigator.
 *
 * Popup visibility, focus, and scroll are receiver-local presentation state.
 * [value] remains authoritative, and every selection gesture publishes only
 * the exact next authored option through [onValueChange].
 */
@Composable
internal fun JetpacsControlledNavigator(
    options: List<JetpacsNavigatorOption>,
    value: String,
    onValueChange: (String) -> Unit,
    semantics: JetpacsNavigatorSemantics,
    modifier: Modifier = Modifier,
    optionStyle: Style = Style,
    enabled: Boolean = true,
    labelStyle: Style = Style,
    buttonStyle: Style = Style,
    selectorStyle: Style = Style,
    popupStyle: Style = Style,
    popupItemStyle: Style = Style,
) {
    if (options.isEmpty()) return

    val selectedIndex = options.indexOfFirst { it.value == value }
    val selected = options.getOrNull(selectedIndex)
    val previousEnabled = enabled && selectedIndex > 0
    val nextEnabled = enabled && selectedIndex in 0 until options.lastIndex
    val destinationName = when (semantics) {
        JetpacsNavigatorSemantics.Tabs -> "tab"
        JetpacsNavigatorSemantics.Sections -> "section"
    }
    var expanded by remember { mutableStateOf(false) }
    var restoreTriggerFocus by remember { mutableStateOf(false) }
    var navigatorBounds by remember { mutableStateOf<IntRect?>(null) }
    val triggerFocusRequester = remember { FocusRequester() }

    fun closePopup(restoreFocus: Boolean) {
        expanded = false
        restoreTriggerFocus = restoreFocus
    }

    LaunchedEffect(enabled) {
        if (!enabled && expanded) closePopup(restoreFocus = false)
    }
    LaunchedEffect(restoreTriggerFocus) {
        if (restoreTriggerFocus) {
            withFrameNanos { }
            triggerFocusRequester.requestFocus()
            restoreTriggerFocus = false
        }
    }
    Box(
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            navigatorBounds = IntRect(
                left = bounds.left.roundToInt(),
                top = bounds.top.roundToInt(),
                right = bounds.right.roundToInt(),
                bottom = bounds.bottom.roundToInt(),
            )
        },
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .styleable(
                    remember { MutableStyleState(null) },
                    JetpacsTheme.styles.navigator,
                    if (semantics == JetpacsNavigatorSemantics.Sections) {
                        designComponentStyle(DesignComponentStyleSlot.SectionNavigatorOption)
                    } else {
                        Style
                    },
                ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JetpacsNavigatorArrow(
                towardStart = true,
                enabled = previousEnabled,
                contentDescription = "Previous $destinationName",
                semantics = semantics,
                style = buttonStyle,
                onClick = { onValueChange(options[selectedIndex - 1].value) },
            )
            JetpacsNavigatorSelector(
                text = selected?.accessibleLabel ?: value,
                position = selectedIndex.takeIf { it >= 0 }?.let { it + 1 },
                optionCount = options.size,
                expanded = expanded,
                enabled = enabled,
                semantics = semantics,
                focusRequester = triggerFocusRequester,
                optionStyle = optionStyle,
                labelStyle = labelStyle,
                selectorStyle = selectorStyle,
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
            )
            JetpacsNavigatorArrow(
                towardStart = false,
                enabled = nextEnabled,
                contentDescription = "Next $destinationName",
                semantics = semantics,
                style = buttonStyle,
                onClick = { onValueChange(options[selectedIndex + 1].value) },
            )
        }

        if (expanded) {
            val layoutDirection = LocalLayoutDirection.current
            val density = LocalDensity.current
            val windowHeight = LocalWindowInfo.current.containerSize.height
            val availablePopupHeight = navigatorBounds?.let { bounds ->
                val above = bounds.top.coerceAtLeast(0)
                val below = (windowHeight - bounds.bottom).coerceAtLeast(0)
                with(density) { maxOf(above, below).toDp() }
            }
            Popup(
                popupPositionProvider = remember(layoutDirection) {
                    JetpacsAnchoredPopupPositionProvider(layoutDirection)
                },
                onDismissRequest = { closePopup(restoreFocus = true) },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = true,
                ),
            ) {
                JetpacsNavigatorPopupContent(
                    options = options,
                    value = value,
                    semantics = semantics,
                    enabled = enabled,
                    optionStyle = optionStyle,
                    labelStyle = labelStyle,
                    popupStyle = popupStyle,
                    popupItemStyle = popupItemStyle,
                    requestInitialFocus = LocalWindowInfo.current.isWindowFocused,
                    availableHeight = availablePopupHeight,
                    onOptionClick = { option ->
                        closePopup(restoreFocus = true)
                        if (option.value != value) onValueChange(option.value)
                    },
                )
            }
        }
    }
}

/** Bounded, keyed popup content shared by runtime and visual test fixtures. */
@Composable
internal fun JetpacsNavigatorPopupContent(
    options: List<JetpacsNavigatorOption>,
    value: String,
    semantics: JetpacsNavigatorSemantics,
    enabled: Boolean,
    optionStyle: Style = Style,
    requestInitialFocus: Boolean = false,
    availableHeight: Dp? = null,
    labelStyle: Style = Style,
    popupStyle: Style = Style,
    popupItemStyle: Style = Style,
    onOptionClick: (JetpacsNavigatorOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = options.indexOfFirst { it.value == value }
    val selectedFocusRequester = remember(value) { FocusRequester() }
    val popupState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - 3).coerceAtLeast(0),
    )
    val windowSize = LocalWindowInfo.current.containerDpSize
    val popupMaxWidth = minOf(480.dp, windowSize.width)
    val popupMinWidth = minOf(220.dp, popupMaxWidth)
    val popupMaxHeight = minOf(
        (48 * MAX_VISIBLE_NAVIGATOR_OPTIONS).dp,
        windowSize.height,
        availableHeight ?: windowSize.height,
    )
    val semanticsModifier = if (semantics == JetpacsNavigatorSemantics.Tabs) {
        Modifier.selectableGroup()
    } else {
        Modifier
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            popupState.scrollToItem((selectedIndex - 3).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = popupState,
        modifier = modifier
            .then(semanticsModifier)
            .widthIn(min = popupMinWidth, max = popupMaxWidth)
            .heightIn(max = popupMaxHeight)
            .styleable(
                remember { MutableStyleState(null) },
                JetpacsTheme.styles.navigatorPopup,
                if (semantics == JetpacsNavigatorSemantics.Sections) {
                    designComponentStyle(DesignComponentStyleSlot.SectionNavigatorPopup)
                } else {
                    Style
                },
                popupStyle,
            ),
    ) {
        itemsIndexed(
            items = options,
            key = { _, option -> option.value },
        ) { index, option ->
            JetpacsNavigatorPopupOption(
                option = option,
                index = index,
                optionCount = options.size,
                selected = index == selectedIndex,
                enabled = enabled,
                semantics = semantics,
                selectedFocusRequester = selectedFocusRequester,
                requestInitialFocus = requestInitialFocus,
                optionStyle = optionStyle,
                labelStyle = labelStyle,
                popupItemStyle = popupItemStyle,
                onClick = { onOptionClick(option) },
            )
        }
    }
}

@Composable
private fun JetpacsNavigatorSelector(
    text: String,
    position: Int?,
    optionCount: Int,
    expanded: Boolean,
    enabled: Boolean,
    semantics: JetpacsNavigatorSemantics,
    focusRequester: FocusRequester,
    optionStyle: Style,
    labelStyle: Style,
    selectorStyle: Style,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isSelected = expanded
    }
    val positionText = position?.let { ", $it of $optionCount" }.orEmpty()
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .focusRequester(focusRequester)
            .focusable(enabled = enabled, interactionSource = source)
            .semantics {
                contentDescription = "$text$positionText"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.DropdownList,
                onClick = onClick,
            )
            .styleable(
                styleState,
                JetpacsTheme.styles.navigatorSelector,
                if (semantics == JetpacsNavigatorSemantics.Sections) {
                    designComponentStyle(DesignComponentStyleSlot.SectionNavigatorSelector)
                } else {
                    designComponentStyle(DesignComponentStyleSlot.TabsItem)
                },
                optionStyle,
                selectorStyle,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .styleable(
                    styleState,
                    if (semantics == JetpacsNavigatorSemantics.Sections) {
                        designComponentNonTextStyle(
                            DesignComponentStyleSlot.SectionNavigatorLabel,
                        )
                    } else {
                        designComponentNonTextStyle(DesignComponentStyleSlot.TabsLabel)
                    },
                    labelStyle,
                ),
        ) {
            BasicText(
                text = text,
                style = resolvedDesignTextStyle(
                    if (semantics == JetpacsNavigatorSemantics.Sections) {
                        DesignComponentStyleSlot.SectionNavigatorLabel
                    } else {
                        DesignComponentStyleSlot.TabsLabel
                    },
                    JetpacsTheme.typography.choice,
                    styleState,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        JetpacsChevron(JetpacsChevronDirection.Down)
    }
}

@Composable
private fun JetpacsNavigatorArrow(
    towardStart: Boolean,
    enabled: Boolean,
    contentDescription: String,
    semantics: JetpacsNavigatorSemantics,
    style: Style,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) { it.isEnabled = enabled }
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .styleable(
                styleState,
                JetpacsTheme.styles.navigatorButton,
                if (semantics == JetpacsNavigatorSemantics.Sections) {
                    designComponentStyle(DesignComponentStyleSlot.SectionNavigatorButton)
                } else {
                    designComponentStyle(DesignComponentStyleSlot.TabsItem)
                },
                style,
            ),
        contentAlignment = Alignment.Center,
    ) {
        JetpacsChevron(
            if (towardStart) {
                JetpacsChevronDirection.Start
            } else {
                JetpacsChevronDirection.End
            },
        )
    }
}

@Composable
private fun JetpacsNavigatorPopupOption(
    option: JetpacsNavigatorOption,
    index: Int,
    optionCount: Int,
    selected: Boolean,
    enabled: Boolean,
    semantics: JetpacsNavigatorSemantics,
    selectedFocusRequester: FocusRequester,
    requestInitialFocus: Boolean,
    optionStyle: Style,
    labelStyle: Style,
    popupItemStyle: Style,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isSelected = selected
    }
    val role = when (semantics) {
        JetpacsNavigatorSemantics.Tabs -> Role.Tab
        JetpacsNavigatorSemantics.Sections -> Role.Button
    }
    val spokenPosition = when (semantics) {
        JetpacsNavigatorSemantics.Tabs -> "tab ${index + 1} of $optionCount"
        JetpacsNavigatorSemantics.Sections ->
            "heading level ${option.level}, ${index + 1} of $optionCount"
    }
    val focusModifier = if (selected) {
        Modifier.focusRequester(selectedFocusRequester)
    } else {
        Modifier
    }
    if (selected && requestInitialFocus) {
        LaunchedEffect(option.value, requestInitialFocus) {
            withFrameNanos { }
            selectedFocusRequester.requestFocus()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(focusModifier)
            .focusable(enabled = enabled, interactionSource = source)
            .semantics {
                contentDescription = "${option.accessibleLabel}, $spokenPosition"
            }
            .selectable(
                selected = selected,
                enabled = enabled,
                role = role,
                interactionSource = source,
                indication = null,
                onClick = onClick,
            )
            .styleable(
                styleState,
                JetpacsTheme.styles.navigatorPopupItem,
                if (semantics == JetpacsNavigatorSemantics.Sections) {
                    designComponentStyle(DesignComponentStyleSlot.SectionNavigatorOption)
                } else {
                    designComponentStyle(DesignComponentStyleSlot.TabsItem)
                },
                if (semantics == JetpacsNavigatorSemantics.Sections) {
                    designComponentStyle(DesignComponentStyleSlot.SectionNavigatorPopupItem)
                } else {
                    Style
                },
                optionStyle,
                popupItemStyle,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (semantics == JetpacsNavigatorSemantics.Sections && option.level > 1) {
            Spacer(Modifier.width(((option.level - 1) * 12).dp))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .styleable(
                    styleState,
                    if (semantics == JetpacsNavigatorSemantics.Sections) {
                        designComponentNonTextStyle(
                            DesignComponentStyleSlot.SectionNavigatorLabel,
                        )
                    } else {
                        designComponentNonTextStyle(DesignComponentStyleSlot.TabsLabel)
                    },
                    labelStyle,
                ),
        ) {
            BasicText(
                text = option.label,
                style = resolvedDesignTextStyle(
                    if (semantics == JetpacsNavigatorSemantics.Sections) {
                        DesignComponentStyleSlot.SectionNavigatorLabel
                    } else {
                        DesignComponentStyleSlot.TabsLabel
                    },
                    JetpacsTheme.typography.choice,
                    styleState,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private enum class JetpacsChevronDirection { Start, End, Down }

/** A font-independent directional mark that mirrors start/end under RTL. */
@Composable
private fun JetpacsChevron(direction: JetpacsChevronDirection) {
    val layoutDirection = LocalLayoutDirection.current
    val color = JetpacsTheme.colors.content
    Canvas(
        Modifier
            .size(18.dp)
            .clearAndSetSemantics { },
    ) {
        val stroke = 2.dp.toPx()
        val startPointsRight = layoutDirection == LayoutDirection.Rtl
        val pointsRight = when (direction) {
            JetpacsChevronDirection.Start -> startPointsRight
            JetpacsChevronDirection.End -> !startPointsRight
            JetpacsChevronDirection.Down -> false
        }
        val first: Offset
        val vertex: Offset
        val last: Offset
        if (direction == JetpacsChevronDirection.Down) {
            first = Offset(size.width * 0.24f, size.height * 0.38f)
            vertex = Offset(size.width * 0.50f, size.height * 0.64f)
            last = Offset(size.width * 0.76f, size.height * 0.38f)
        } else if (pointsRight) {
            first = Offset(size.width * 0.36f, size.height * 0.22f)
            vertex = Offset(size.width * 0.66f, size.height * 0.50f)
            last = Offset(size.width * 0.36f, size.height * 0.78f)
        } else {
            first = Offset(size.width * 0.64f, size.height * 0.22f)
            vertex = Offset(size.width * 0.34f, size.height * 0.50f)
            last = Offset(size.width * 0.64f, size.height * 0.78f)
        }
        drawLine(color, first, vertex, stroke, StrokeCap.Round)
        drawLine(color, vertex, last, stroke, StrokeCap.Round)
    }
}

/**
 * Places a popup against its selector edge, preferring below while keeping the
 * complete popup inside the available window whenever its measured size fits.
 */
internal class JetpacsAnchoredPopupPositionProvider(
    private val preferredLayoutDirection: LayoutDirection,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val direction = layoutDirection.takeIf { it == preferredLayoutDirection }
            ?: preferredLayoutDirection
        val unboundedX = if (direction == LayoutDirection.Ltr) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = unboundedX.coerceIn(0, maxX)
        val below = windowSize.height - anchorBounds.bottom
        val above = anchorBounds.top
        val unboundedY = if (popupContentSize.height <= below || below >= above) {
            anchorBounds.bottom
        } else {
            anchorBounds.top - popupContentSize.height
        }
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, unboundedY.coerceIn(0, maxY))
    }
}
