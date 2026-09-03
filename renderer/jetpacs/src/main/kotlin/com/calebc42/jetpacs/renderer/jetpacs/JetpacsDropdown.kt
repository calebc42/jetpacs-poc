// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt

/** Complete rows visible before a dropdown's popup scrolls. */
internal const val MAX_VISIBLE_DROPDOWN_OPTIONS = 8

/** One option of a dropdown, read from the wire's `EnumOption` record. */
@Immutable
data class JetpacsDropdownOption(
    val label: String,
    /**
     * The option's `value` in its wire text form. An `EnumOption` value may
     * be a string, number or boolean; the Material renderer publishes its
     * text, and both renderers must agree on what an app receives.
     */
    val value: String,
)

/**
 * Read `options` as the closed list a dropdown offers.
 *
 * A row that is not an object or carries no scalar `value` is skipped, as the
 * Material renderer skips it; the validator has already rejected the shapes
 * SPEC 17.4 forbids, so this is a defensive reading, not a second validator.
 */
internal fun jetpacsDropdownOptions(options: JsonArray): List<JetpacsDropdownOption> =
    options.mapNotNull { element ->
        val option = element as? JsonObject ?: return@mapNotNull null
        val value = (option["value"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        JetpacsDropdownOption(label = option.dropdownText("label"), value = value)
    }

/** The label shown for [value], or the value itself when no option carries it. */
internal fun jetpacsDropdownLabel(options: List<JetpacsDropdownOption>, value: String?): String? =
    value?.let { current -> options.firstOrNull { it.value == current }?.label ?: current }

/**
 * Jetpacs' dropdown: a closed field showing the selected option, and the
 * popup that lists the others.
 *
 * The field is the one target and carries the host's name and role; the
 * label above it is decoration. Open state is receiver-local and never
 * crosses the wire. The popup is at least as wide as the field, bounded to
 * the larger of the space above and below it, and scrolls inside that; it
 * opens on the selected row, dismisses on back press and outside tap, and
 * hands focus back to the field afterwards.
 *
 * [style] and the per-slot styles change visual properties only and are
 * layered after the theme and the active design profile.
 */
@Composable
fun JetpacsDropdown(
    options: List<JetpacsDropdownOption>,
    value: String?,
    onSelect: (JetpacsDropdownOption) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    style: Style = Style,
    textStyle: Style = Style,
    labelStyle: Style = Style,
    popupStyle: Style = Style,
    itemStyle: Style = Style,
) {
    var expanded by remember { mutableStateOf(false) }
    var restoreFieldFocus by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
    val fieldFocusRequester = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isSelected = expanded
    }
    val colors = JetpacsTheme.colors
    val selectedLabel = jetpacsDropdownLabel(options, value)

    fun closePopup(restoreFocus: Boolean) {
        expanded = false
        restoreFieldFocus = restoreFocus
    }

    LaunchedEffect(enabled) {
        if (!enabled && expanded) closePopup(restoreFocus = false)
    }
    LaunchedEffect(restoreFieldFocus) {
        if (restoreFieldFocus) {
            withFrameNanos { }
            fieldFocusRequester.requestFocus()
            restoreFieldFocus = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(JetpacsTheme.spacing.unit)) {
        if (!label.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .styleable(
                        styleState,
                        designComponentNonTextStyle(DesignComponentStyleSlot.DropdownLabel),
                        labelStyle,
                    ),
            ) {
                BasicText(
                    label,
                    style = resolvedDesignTextStyle(
                        DesignComponentStyleSlot.DropdownLabel,
                        JetpacsTheme.typography.fieldLabel.copy(color = colors.mutedContent),
                        styleState,
                    ),
                )
            }
        }
        Box(
            Modifier.onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                anchorBounds = IntRect(
                    left = bounds.left.roundToInt(),
                    top = bounds.top.roundToInt(),
                    right = bounds.right.roundToInt(),
                    bottom = bounds.bottom.roundToInt(),
                )
            },
        ) {
            // The host modifier carries the node's accessible name and role,
            // so it goes on the field itself: name, role, value and target
            // stay one node rather than a labelled wrapper around a button.
            Row(
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .focusRequester(fieldFocusRequester)
                    .hoverable(source, enabled)
                    .jetpacsFocusable(enabled, source)
                    .semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                        if (!enabled) disabled()
                    }
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        enabled = enabled,
                        role = Role.DropdownList,
                    ) { expanded = true }
                    .styleable(
                        styleState,
                        JetpacsTheme.styles.dropdownField,
                        designComponentStyle(DesignComponentStyleSlot.DropdownField),
                        style,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .styleable(
                            styleState,
                            designComponentNonTextStyle(DesignComponentStyleSlot.DropdownText),
                            textStyle,
                        ),
                ) {
                    val showingPlaceholder = selectedLabel == null
                    BasicText(
                        text = selectedLabel ?: placeholder.orEmpty(),
                        style = resolvedDesignTextStyle(
                            DesignComponentStyleSlot.DropdownText,
                            JetpacsTheme.typography.field.copy(
                                color = if (showingPlaceholder) colors.mutedContent else colors.content,
                            ),
                            styleState,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                JetpacsChevron(JetpacsChevronDirection.Down)
            }

            if (expanded) {
                val layoutDirection = LocalLayoutDirection.current
                val density = LocalDensity.current
                val windowHeight = LocalWindowInfo.current.containerSize.height
                val availablePopupHeight = anchorBounds?.let { bounds ->
                    val above = bounds.top.coerceAtLeast(0)
                    val below = (windowHeight - bounds.bottom).coerceAtLeast(0)
                    with(density) { maxOf(above, below).toDp() }
                }
                val fieldWidth = anchorBounds?.let { with(density) { it.width.toDp() } }
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
                    JetpacsDropdownPopupContent(
                        options = options,
                        value = value,
                        enabled = enabled,
                        minWidth = fieldWidth,
                        availableHeight = availablePopupHeight,
                        requestInitialFocus = LocalWindowInfo.current.isWindowFocused,
                        onSelect = { option ->
                            // Close before publishing, so the action never
                            // fires under a popup that is still up.
                            closePopup(restoreFocus = true)
                            onSelect(option)
                        },
                        popupStyle = popupStyle,
                        itemStyle = itemStyle,
                        textStyle = textStyle,
                    )
                }
            }
        }
    }
}

/** Bounded popup body, separated from the window so tests can draw it. */
@Composable
internal fun JetpacsDropdownPopupContent(
    options: List<JetpacsDropdownOption>,
    value: String?,
    enabled: Boolean,
    onSelect: (JetpacsDropdownOption) -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp? = null,
    availableHeight: Dp? = null,
    requestInitialFocus: Boolean = false,
    popupStyle: Style = Style,
    itemStyle: Style = Style,
    textStyle: Style = Style,
) {
    val selectedIndex = options.indexOfFirst { it.value == value }
    val focusedIndex = if (selectedIndex >= 0) selectedIndex else 0
    val initialFocusRequester = remember(value) { FocusRequester() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - 3).coerceAtLeast(0),
    )
    val windowSize = LocalWindowInfo.current.containerDpSize
    val popupMaxWidth = windowSize.width
    val popupMinWidth = minOf(minWidth ?: 220.dp, popupMaxWidth)
    val popupMaxHeight = minOf(
        (48 * MAX_VISIBLE_DROPDOWN_OPTIONS).dp,
        windowSize.height,
        availableHeight ?: windowSize.height,
    )
    LazyColumn(
        state = listState,
        modifier = modifier
            .widthIn(min = popupMinWidth, max = popupMaxWidth)
            .heightIn(max = popupMaxHeight)
            .styleable(
                remember { MutableStyleState(null) },
                JetpacsTheme.styles.dropdownPopup,
                designComponentStyle(DesignComponentStyleSlot.DropdownPopup),
                popupStyle,
            ),
    ) {
        itemsIndexed(items = options, key = { _, option -> option.value }) { index, option ->
            JetpacsDropdownRow(
                option = option,
                index = index,
                optionCount = options.size,
                selected = index == selectedIndex,
                enabled = enabled,
                focusRequester = initialFocusRequester.takeIf { index == focusedIndex },
                requestInitialFocus = requestInitialFocus,
                itemStyle = itemStyle,
                textStyle = textStyle,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun JetpacsDropdownRow(
    option: JetpacsDropdownOption,
    index: Int,
    optionCount: Int,
    selected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    requestInitialFocus: Boolean,
    itemStyle: Style,
    textStyle: Style,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isSelected = selected
    }
    if (focusRequester != null && requestInitialFocus) {
        LaunchedEffect(option.value, requestInitialFocus) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .jetpacsFocusable(enabled = enabled, interactionSource = source)
            .semantics {
                contentDescription = "${option.label}, ${index + 1} of $optionCount"
            }
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Button,
                interactionSource = source,
                indication = null,
                onClick = onClick,
            )
            .styleable(
                styleState,
                JetpacsTheme.styles.dropdownItem,
                designComponentStyle(DesignComponentStyleSlot.DropdownItem),
                itemStyle,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .styleable(
                    styleState,
                    designComponentNonTextStyle(DesignComponentStyleSlot.DropdownText),
                    textStyle,
                ),
        ) {
            BasicText(
                text = option.label,
                style = resolvedDesignTextStyle(
                    DesignComponentStyleSlot.DropdownText,
                    JetpacsTheme.typography.choice,
                    styleState,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Design-scoped `dropdown`: the closed single-select form on Foundation.
 *
 * Handles id, options, value, label, hint, on_change and enabled. The
 * editable form (`editable`, and with it `report_caret`) is a completion
 * text field, not a select, and declines to the Material renderer.
 *
 * State is the host's: the live value is seeded from the store at the
 * node's epoch and falls back to the authored `value`, and a pick publishes
 * `state.changed` before `on_change`, in that order, carrying the option
 * value's text exactly as the Material renderer does.
 */
object JetpacsDesignDropdownRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.dropdown.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("dropdown")

    override fun appliesTo(node: JsonObject): Boolean = !node.dropdownFlag("editable", false)

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val id = node.dropdownText("id")
        val epoch = context.epochOf(id)
        val options = remember(node) {
            jetpacsDropdownOptions(node["options"] as? JsonArray ?: JsonArray(emptyList()))
        }
        var value by rememberSaveable(context.surface, id, epoch) {
            mutableStateOf(
                (context.storeValue(id) as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                    ?: (node["value"] as? JsonPrimitive)?.content,
            )
        }
        val onChange = node["on_change"] as? JsonObject
        JetpacsDropdown(
            options = options,
            value = value,
            onSelect = { option ->
                value = option.value
                val published = JsonPrimitive(option.value)
                context.state(id, published)
                context.action(onChange, published)
            },
            modifier = modifier,
            label = node.dropdownText("label").takeIf { it.isNotEmpty() },
            placeholder = node.dropdownText("hint").takeIf { it.isNotEmpty() },
            enabled = node.dropdownFlag("enabled", true),
        )
    }
}

private fun JsonObject.dropdownText(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.dropdownFlag(name: String, default: Boolean): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default
