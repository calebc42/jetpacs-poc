// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt

/** Where a menu opens when its list is longer than the space it has. */
enum class JetpacsMenuScroll {
    Start,
    End,
}

/**
 * One authored menu row.
 *
 * [checked] is nullable because its PRESENCE, not its value, selects the
 * checkable form: a checkable row reports a checked state and leaves the menu
 * open, while an ordinary row closes it. That mirrors the wire, where a
 * `checked` member is what distinguishes the two.
 */
@Immutable
data class JetpacsMenuItem(
    val label: String,
    val icon: String? = null,
    val trailingIcon: String? = null,
    val trailingText: String? = null,
    val supportingText: String? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val checkedIcon: String = "check",
    val onSelect: () -> Unit = {},
)

/** A run of rows under one heading, separated from the run above by a rule. */
@Immutable
data class JetpacsMenuGroup(
    val label: String? = null,
    val items: List<JetpacsMenuItem>,
)

/** Leading and trailing glyph size inside a menu row. */
private val MenuGlyphSize = 18.dp

/** Bounds of the popup surface, matching the platform's dropdown metrics. */
private val MenuPopupMaxWidth = 280.dp
private val MenuPopupMinWidth = 112.dp

/**
 * Jetpacs' menu: an overflow control and the popup it opens.
 *
 * Pass either [items] or [groups]; a grouped menu draws each heading above a
 * hairline. Open state is receiver-local and never crosses the wire — the
 * control owns it, exactly as a platform dropdown does.
 *
 * The popup is bounded to the larger of the space above and below the anchor
 * and scrolls inside it, so a long menu stays on screen. Dismissal on back
 * press and outside tap is delegated to the popup window, and focus returns
 * to the trigger afterwards.
 *
 * [style] and the per-slot styles change visual properties only and are
 * layered after the theme and the active design profile.
 */
@Composable
fun JetpacsMenu(
    modifier: Modifier = Modifier,
    items: List<JetpacsMenuItem> = emptyList(),
    groups: List<JetpacsMenuGroup> = emptyList(),
    triggerIcon: String = "more_vert",
    enabled: Boolean = true,
    initialScroll: JetpacsMenuScroll = JetpacsMenuScroll.Start,
    footer: (@Composable () -> Unit)? = null,
    style: Style = Style,
    popupStyle: Style = Style,
    itemStyle: Style = Style,
    itemLabelStyle: Style = Style,
    itemSupportingStyle: Style = Style,
    groupLabelStyle: Style = Style,
) {
    var expanded by remember { mutableStateOf(false) }
    var restoreTriggerFocus by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
    val triggerFocusRequester = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) { it.isEnabled = enabled }

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
    val triggerTint = resolvedDesignTextStyle(
        DesignComponentStyleSlot.MenuTrigger,
        JetpacsTheme.typography.choice,
        styleState,
    ).color
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
        // The host modifier carries the node's accessible name, so it goes on
        // the control itself: name, role and target stay one node rather than
        // a labelled wrapper around an unlabelled button.
        Box(
            modifier = modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .focusRequester(triggerFocusRequester)
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
                    JetpacsTheme.styles.menuTrigger,
                    designComponentStyle(DesignComponentStyleSlot.MenuTrigger),
                    style,
                ),
            contentAlignment = Alignment.Center,
        ) {
            DesignGlyph(triggerIcon, triggerTint, 24.dp)
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
                JetpacsMenuPopupContent(
                    items = items,
                    groups = groups,
                    initialScroll = initialScroll,
                    availableHeight = availablePopupHeight,
                    requestInitialFocus = LocalWindowInfo.current.isWindowFocused,
                    footer = footer,
                    onSelect = { item ->
                        // A checkable row reports state and stays open; an
                        // ordinary one closes before running, so the action
                        // never fires under a popup that is still up.
                        if (item.checked == null) closePopup(restoreFocus = true)
                        item.onSelect()
                    },
                    popupStyle = popupStyle,
                    itemStyle = itemStyle,
                    itemLabelStyle = itemLabelStyle,
                    itemSupportingStyle = itemSupportingStyle,
                    groupLabelStyle = groupLabelStyle,
                )
            }
        }
    }
}

/** Bounded popup body, separated from the window so tests can draw it. */
@Composable
internal fun JetpacsMenuPopupContent(
    items: List<JetpacsMenuItem> = emptyList(),
    groups: List<JetpacsMenuGroup> = emptyList(),
    initialScroll: JetpacsMenuScroll = JetpacsMenuScroll.Start,
    availableHeight: Dp? = null,
    requestInitialFocus: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    onSelect: (JetpacsMenuItem) -> Unit,
    popupStyle: Style = Style,
    itemStyle: Style = Style,
    itemLabelStyle: Style = Style,
    itemSupportingStyle: Style = Style,
    groupLabelStyle: Style = Style,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val windowSize = LocalWindowInfo.current.containerDpSize
    val popupMaxWidth = minOf(MenuPopupMaxWidth, windowSize.width)
    val popupMinWidth = minOf(MenuPopupMinWidth, popupMaxWidth)
    val popupMaxHeight = minOf(
        windowSize.height,
        availableHeight ?: windowSize.height,
    )
    val firstFocusRequester = remember { FocusRequester() }
    // The opening focus goes to the first row that can take it, decided from
    // the authored order rather than by mutating state while composing.
    val focusIndex = (groups.flatMap { it.items } + items)
        .indexOfFirst { it.enabled }
        .takeIf { it >= 0 }

    LaunchedEffect(initialScroll) {
        if (initialScroll == JetpacsMenuScroll.End) {
            // maxValue is zero until the content has been measured, so wait
            // for the measurement rather than scrolling into an empty range.
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    Column(
        modifier = modifier
            .widthIn(min = popupMinWidth, max = popupMaxWidth)
            .heightIn(max = popupMaxHeight)
            .styleable(
                remember { MutableStyleState(null) },
                JetpacsTheme.styles.menuPopup,
                designComponentStyle(DesignComponentStyleSlot.MenuContainer),
                popupStyle,
            )
            .verticalScroll(scrollState),
    ) {
        var rowIndex = 0
        fun focusFor(): FocusRequester? =
            if (requestInitialFocus && rowIndex++ == focusIndex) {
                firstFocusRequester
            } else {
                null
            }
        groups.forEachIndexed { index, group ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            if (!group.label.isNullOrEmpty()) {
                JetpacsMenuGroupHeading(group.label, groupLabelStyle)
            }
            group.items.forEach { item ->
                JetpacsMenuRow(
                    item = item,
                    focusRequester = focusFor(),
                    onSelect = onSelect,
                    itemStyle = itemStyle,
                    itemLabelStyle = itemLabelStyle,
                    itemSupportingStyle = itemSupportingStyle,
                )
            }
        }
        items.forEach { item ->
            JetpacsMenuRow(
                item = item,
                focusRequester = focusFor(),
                onSelect = onSelect,
                itemStyle = itemStyle,
                itemLabelStyle = itemLabelStyle,
                itemSupportingStyle = itemSupportingStyle,
            )
        }
        footer?.invoke()
    }
    if (requestInitialFocus) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { firstFocusRequester.requestFocus() }
        }
    }
}

/** A group heading and the rule that separates its run from the one above. */
@Composable
private fun JetpacsMenuGroupHeading(label: String, groupLabelStyle: Style) {
    // A heading, not a field label: sans and medium like the rows it heads,
    // but muted so it reads as structure rather than as another choice.
    val labelStyle = resolvedDesignTextStyle(
        DesignComponentStyleSlot.MenuGroupLabel,
        JetpacsTheme.typography.label.copy(color = JetpacsTheme.colors.mutedContent),
    )
    Box(
        Modifier
            .fillMaxWidth()
            // Merged, so the heading is one announced node rather than a
            // heading marker with unrelated text inside it.
            .semantics(mergeDescendants = true) { heading() }
            .styleable(
                remember { MutableStyleState(null) },
                JetpacsTheme.styles.menuGroupLabel,
                designComponentNonTextStyle(DesignComponentStyleSlot.MenuGroupLabel),
                groupLabelStyle,
            ),
    ) {
        BasicText(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // The same hairline the canonical divider draws, so one profile binding
    // governs every rule in the design.
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .styleable(
                remember { MutableStyleState(null) },
                JetpacsTheme.styles.divider,
                designComponentStyle(DesignComponentStyleSlot.DividerLine),
            ),
    )
}

/** One menu row: leading glyph, label over supporting text, trailing glyph. */
@Composable
private fun JetpacsMenuRow(
    item: JetpacsMenuItem,
    focusRequester: FocusRequester?,
    onSelect: (JetpacsMenuItem) -> Unit,
    itemStyle: Style,
    itemLabelStyle: Style,
    itemSupportingStyle: Style,
) {
    val checked = item.checked
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = item.enabled
        it.isSelected = checked == true
    }
    val labelStyle = resolvedDesignTextStyle(
        DesignComponentStyleSlot.MenuItemLabel,
        JetpacsTheme.typography.choice,
        styleState,
    )
    val supportingStyle = resolvedDesignTextStyle(
        DesignComponentStyleSlot.MenuItemSupporting,
        JetpacsTheme.typography.caption,
        styleState,
    )
    val interactive = if (checked == null) {
        Modifier.clickable(
            interactionSource = source,
            indication = null,
            enabled = item.enabled,
            role = Role.Button,
        ) { onSelect(item) }
    } else {
        Modifier.toggleable(
            value = checked,
            interactionSource = source,
            indication = null,
            enabled = item.enabled,
            role = Role.Checkbox,
        ) { onSelect(item) }
    }
    // While checked, the check glyph replaces the authored leading icon.
    val leadingGlyph = if (checked == true) item.checkedIcon else item.icon
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .hoverable(source, item.enabled)
            .jetpacsFocusable(item.enabled, source)
            .semantics { if (!item.enabled) disabled() }
            .then(interactive)
            .styleable(
                styleState,
                JetpacsTheme.styles.menuItem,
                designComponentStyle(DesignComponentStyleSlot.MenuItem),
                itemStyle,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!leadingGlyph.isNullOrEmpty()) {
            DesignGlyph(leadingGlyph, labelStyle.color, MenuGlyphSize)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Box(Modifier.styleable(styleState, Style, itemLabelStyle)) {
                BasicText(
                    item.label,
                    style = labelStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.supportingText.isNullOrEmpty()) {
                Box(Modifier.styleable(styleState, Style, itemSupportingStyle)) {
                    BasicText(
                        item.supportingText,
                        style = supportingStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // One trailing slot: the wire makes the pair mutually exclusive, and a
        // shortcut hint is text rather than a glyph the icon set would have to
        // contain.
        if (!item.trailingIcon.isNullOrEmpty()) {
            Spacer(Modifier.width(12.dp))
            DesignGlyph(item.trailingIcon, labelStyle.color, MenuGlyphSize)
        } else if (!item.trailingText.isNullOrEmpty()) {
            Spacer(Modifier.width(12.dp))
            BasicText(
                item.trailingText,
                style = supportingStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Design-scoped `menu`: the overflow control and its popup.
 *
 * This honors the whole node — flat items, groups, the checkable item form,
 * `initial_scroll`, and a footer node — so it never declines. There is no
 * member here whose state a Foundation presentation would have to drop, and
 * declining part of the vocabulary would leave a design profile styling some
 * menus and not others.
 */
object JetpacsDesignMenuRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.menu.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("menu")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val footerNode = node["footer"] as? JsonObject
        val dispatch: (JsonObject) -> Unit = { context.action(it) }
        JetpacsMenu(
            modifier = modifier,
            items = jetpacsMenuItems(node["items"] as? JsonArray, dispatch),
            groups = jetpacsMenuGroups(node["groups"] as? JsonArray, dispatch),
            triggerIcon = node.menuText("icon").takeIf { it.isNotEmpty() } ?: "more_vert",
            enabled = node.menuFlag("enabled", true),
            initialScroll = if (node.menuText("initial_scroll") == "end") {
                JetpacsMenuScroll.End
            } else {
                JetpacsMenuScroll.Start
            },
            // Index 0: the footer is the node's only child, so it keeps a
            // stable identity path of its own beneath the menu.
            footer = footerNode?.let { { context.renderChild(it, 0) } },
        )
    }
}

/**
 * Read one wire item array.
 *
 * An item without a `label` cannot be announced, so it is dropped rather than
 * drawn as a nameless row. [dispatch] runs an item's `on_trigger`-equivalent
 * `on_tap`; keeping it a parameter is what lets the wire reading be tested
 * without a composition or a host.
 */
internal fun jetpacsMenuItems(
    raw: JsonArray?,
    dispatch: (JsonObject) -> Unit,
): List<JetpacsMenuItem> =
    raw?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val label = item.menuText("label").takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val onTap = item["on_tap"] as? JsonObject
        JetpacsMenuItem(
            label = label,
            icon = item.menuText("icon").takeIf(String::isNotEmpty),
            trailingIcon = item.menuText("trailing_icon").takeIf(String::isNotEmpty),
            trailingText = item.menuText("trailing_text").takeIf(String::isNotEmpty),
            supportingText = item.menuText("supporting_text").takeIf(String::isNotEmpty),
            enabled = item.menuFlag("enabled", true),
            // Presence, not value: a `checked` member makes the row checkable.
            checked = if ("checked" in item) item.menuFlag("checked", false) else null,
            checkedIcon = item.menuText("checked_icon").takeIf(String::isNotEmpty) ?: "check",
            // An item with no on_tap is drawn but inert; SPEC requires the
            // label, not the action.
            onSelect = { onTap?.let(dispatch) },
        )
    }.orEmpty()

/** Read the grouped shape; a group with no usable item is not a group. */
internal fun jetpacsMenuGroups(
    raw: JsonArray?,
    dispatch: (JsonObject) -> Unit,
): List<JetpacsMenuGroup> =
    raw?.mapNotNull { element ->
        val group = element as? JsonObject ?: return@mapNotNull null
        val items = jetpacsMenuItems(group["items"] as? JsonArray, dispatch)
        items.takeIf { it.isNotEmpty() }?.let {
            JetpacsMenuGroup(label = group.menuText("label"), items = it)
        }
    }.orEmpty()

private fun JsonObject.menuText(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.menuFlag(name: String, default: Boolean): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default
