// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One stable, non-empty authored selection in a [JetpacsTabs] strip. */
@Immutable
data class JetpacsTabOption(
    val label: String,
    val value: String,
) {
    init {
        require(label.isNotEmpty()) { "Tab label must be non-empty" }
        require(value.isNotEmpty()) { "Tab value must be non-empty" }
    }
}

/** Available presentations for the same controlled set of peer views. */
enum class JetpacsTabVariant {
    Fixed,
    Scrollable,
    Navigator,
    Adaptive,
}

/**
 * Maximum options adaptive Tabs will intrinsically subcompose and measure.
 *
 * Three complete navigator popup pages are enough to preserve the measured
 * fixed/scrollable cutovers for ordinary tab sets. Larger authored sets use
 * Navigator immediately, bounding composition work before layout begins.
 */
internal const val MAX_MEASURED_ADAPTIVE_TAB_OPTIONS =
    MAX_VISIBLE_NAVIGATOR_OPTIONS * 3

/** Platform minimum width retained by every independently focusable tab. */
internal val MINIMUM_JETPACS_TAB_TARGET_WIDTH = 48.dp

/** Whether fixed equal cells would violate the platform minimum target width. */
internal fun fixedTabsRequireScrollableFallback(
    optionCount: Int,
    availableWidth: Dp,
): Boolean {
    require(optionCount >= 0) { "Option count must be non-negative" }
    return availableWidth != Dp.Infinity &&
        MINIMUM_JETPACS_TAB_TARGET_WIDTH * optionCount.toFloat() > availableWidth
}

/** Return a resource-bounded adaptive result, or null when measurement decides. */
internal fun premeasureAdaptiveJetpacsTabVariant(
    optionCount: Int,
): JetpacsTabVariant? = if (optionCount > MAX_MEASURED_ADAPTIVE_TAB_OPTIONS) {
    JetpacsTabVariant.Navigator
} else {
    null
}

/**
 * Resolve an optional additive wire variant with the legacy boolean fallback.
 *
 * Unknown variants deliberately behave like absent variants so a future
 * sender cannot make an older renderer lose the usable `scrollable` fallback.
 */
internal fun resolveJetpacsTabVariant(
    variant: String?,
    scrollable: Boolean,
): JetpacsTabVariant = when (variant) {
    "fixed" -> JetpacsTabVariant.Fixed
    "scrollable" -> JetpacsTabVariant.Scrollable
    "navigator" -> JetpacsTabVariant.Navigator
    "adaptive" -> JetpacsTabVariant.Adaptive
    else -> if (scrollable) JetpacsTabVariant.Scrollable else JetpacsTabVariant.Fixed
}

/**
 * Pure adaptive cutover shared by Compose measurement and unit tests.
 *
 * Equal cells remain the most discoverable presentation when every natural
 * tab width fits. Overflow up to three viewports stays directly scrollable;
 * larger sets collapse into the bounded navigator.
 */
internal fun resolveAdaptiveJetpacsTabVariant(
    naturalWidths: List<Int>,
    availableWidth: Int,
): JetpacsTabVariant {
    if (naturalWidths.isEmpty() || availableWidth <= 0) return JetpacsTabVariant.Fixed
    val cellWidth = availableWidth / naturalWidths.size
    if (naturalWidths.all { it <= cellWidth }) return JetpacsTabVariant.Fixed
    val naturalWidth = naturalWidths.sumOf { it.toLong() }
    return if (naturalWidth <= availableWidth.toLong() * 3L) {
        JetpacsTabVariant.Scrollable
    } else {
        JetpacsTabVariant.Navigator
    }
}

/**
 * Jetpacs' controlled, page-free tabs.
 *
 * [value] is authoritative: the component never retains or publishes a local
 * selection. Selecting a different tab calls [onValueChange], after which the
 * author supplies the next value. This keeps a projection switch synchronized
 * with the inspected Lisp program instead of introducing device-owned state.
 *
 * [variant] selects an explicit presentation. When it is absent, [scrollable]
 * preserves the original boolean API. Adaptive presentation measures the
 * actual styled tab content under the local constraints and font scale.
 * [style] customizes the container and [tabStyle] customizes tab/options.
 * Fixed presentation keeps equal-width cells when every tab can retain a 48dp
 * target, and otherwise degrades to the keyed scrollable presentation.
 *
 * An empty [options] list intentionally emits no UI and does not constrain
 * [value]. A non-empty list requires unique option values and [value] must
 * select one of them; invalid input fails before keyed composition.
 */
@Composable
fun JetpacsTabs(
    options: List<JetpacsTabOption>,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: Style = Style,
    tabStyle: Style = Style,
    enabled: Boolean = true,
    scrollable: Boolean = false,
    variant: JetpacsTabVariant? = null,
) {
    validateJetpacsControlledOptions(
        componentName = "JetpacsTabs",
        optionValues = options.map { it.value },
        value = value,
    )
    if (options.isEmpty()) return

    val selectedVariant = variant ?: if (scrollable) {
        JetpacsTabVariant.Scrollable
    } else {
        JetpacsTabVariant.Fixed
    }
    Box(
        modifier = modifier.styleable(
            remember { MutableStyleState(null) },
            JetpacsTheme.styles.tabs,
            style,
        ),
    ) {
        JetpacsTabsPresentation(
            options = options,
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            variant = selectedVariant,
            tabStyle = tabStyle,
        )
    }
}

@Composable
private fun JetpacsTabsPresentation(
    options: List<JetpacsTabOption>,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    variant: JetpacsTabVariant,
    tabStyle: Style,
) {
    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = options.indexOfFirst { it.value == value }
            .coerceAtLeast(0),
    )
    when (variant) {
        JetpacsTabVariant.Fixed -> JetpacsFixedTabs(
            options,
            value,
            onValueChange,
            enabled,
            tabStyle,
            lazyListState,
        )
        JetpacsTabVariant.Scrollable -> JetpacsScrollableTabs(
            options,
            value,
            onValueChange,
            enabled,
            tabStyle,
            lazyListState,
        )
        JetpacsTabVariant.Navigator -> JetpacsTabsNavigator(
            options,
            value,
            onValueChange,
            enabled,
            tabStyle,
        )
        JetpacsTabVariant.Adaptive -> JetpacsAdaptiveTabs(
            options,
            value,
            onValueChange,
            enabled,
            tabStyle,
            lazyListState,
        )
    }
}

@Composable
private fun JetpacsFixedTabs(
    options: List<JetpacsTabOption>,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    tabStyle: Style,
    lazyListState: LazyListState,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (fixedTabsRequireScrollableFallback(options.size, maxWidth)) {
            JetpacsScrollableTabs(
                options = options,
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                tabStyle = tabStyle,
                state = lazyListState,
            )
        } else {
            JetpacsEqualWidthTabs(
                options,
                value,
                onValueChange,
                enabled,
                tabStyle,
                useEqualWeights = maxWidth != Dp.Infinity,
            )
        }
    }
}

@Composable
private fun JetpacsEqualWidthTabs(
    options: List<JetpacsTabOption>,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    tabStyle: Style,
    useEqualWeights: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        options.forEach { option ->
            key(option.value) {
                val targetModifier = Modifier.widthIn(
                    min = MINIMUM_JETPACS_TAB_TARGET_WIDTH,
                ).let { minimumTarget ->
                    // Row ignores weights when its main axis is unbounded. In
                    // that case intrinsic cells let the enclosing scroller own
                    // overflow while retaining real, non-zero tap targets.
                    if (useEqualWeights) minimumTarget.weight(1f)
                    else minimumTarget
                }
                JetpacsTab(
                    label = option.label,
                    selected = option.value == value,
                    enabled = enabled,
                    onClick = {
                        if (option.value != value) onValueChange(option.value)
                    },
                    modifier = targetModifier,
                    style = tabStyle,
                    singleLine = false,
                )
            }
        }
    }
}

@Composable
private fun JetpacsScrollableTabs(
    options: List<JetpacsTabOption>,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    tabStyle: Style,
    state: LazyListState,
) {
    val selectedIndex = options.indexOfFirst { it.value == value }
    LaunchedEffect(value, selectedIndex) {
        if (selectedIndex >= 0) {
            withFrameNanos { }
            val layout = state.layoutInfo
            val selectedItem = layout.visibleItemsInfo.firstOrNull {
                it.index == selectedIndex
            }
            val fullyVisible = selectedItem != null &&
                selectedItem.offset >= layout.viewportStartOffset &&
                selectedItem.offset + selectedItem.size <= layout.viewportEndOffset
            if (!fullyVisible) state.scrollToItem(selectedIndex)
        }
    }
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        items(
            items = options,
            key = { it.value },
        ) { option ->
            JetpacsTab(
                label = option.label,
                selected = option.value == value,
                enabled = enabled,
                onClick = {
                    if (option.value != value) onValueChange(option.value)
                },
                modifier = Modifier.widthIn(min = 72.dp),
                style = tabStyle,
                singleLine = true,
            )
        }
    }
}

@Composable
private fun JetpacsTabsNavigator(
    options: List<JetpacsTabOption>,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    tabStyle: Style,
) {
    JetpacsControlledNavigator(
        options = options.map { option ->
            JetpacsNavigatorOption(
                label = option.label,
                value = option.value,
                accessibleLabel = option.label,
            )
        },
        value = value,
        onValueChange = onValueChange,
        semantics = JetpacsNavigatorSemantics.Tabs,
        optionStyle = tabStyle,
        enabled = enabled,
    )
}

@Composable
private fun JetpacsAdaptiveTabs(
    options: List<JetpacsTabOption>,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    tabStyle: Style,
    lazyListState: LazyListState,
) {
    if (premeasureAdaptiveJetpacsTabVariant(options.size) == JetpacsTabVariant.Navigator) {
        JetpacsTabsNavigator(
            options = options,
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            tabStyle = tabStyle,
        )
        return
    }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = subcompose("unbounded-fixed") {
                JetpacsFixedTabs(
                    options,
                    value,
                    onValueChange,
                    enabled,
                    tabStyle,
                    lazyListState,
                )
            }.single().measure(constraints)
            return@SubcomposeLayout layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        }

        val naturalConstraints = Constraints(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = constraints.maxHeight,
        )
        val naturalWidths = subcompose("natural-widths") {
            options.forEach { option ->
                key(option.value) {
                    JetpacsTab(
                        label = option.label,
                        selected = option.value == value,
                        enabled = enabled,
                        onClick = { },
                        modifier = Modifier.widthIn(min = 72.dp),
                        style = tabStyle,
                        singleLine = true,
                    )
                }
            }
        }.map { it.measure(naturalConstraints).width }
        val resolved = resolveAdaptiveJetpacsTabVariant(
            naturalWidths = naturalWidths,
            availableWidth = constraints.maxWidth,
        )
        val placeable = subcompose("presentation-$resolved") {
            when (resolved) {
                JetpacsTabVariant.Fixed -> JetpacsFixedTabs(
                    options,
                    value,
                    onValueChange,
                    enabled,
                    tabStyle,
                    lazyListState,
                )
                JetpacsTabVariant.Scrollable -> JetpacsScrollableTabs(
                    options,
                    value,
                    onValueChange,
                    enabled,
                    tabStyle,
                    lazyListState,
                )
                JetpacsTabVariant.Navigator -> JetpacsTabsNavigator(
                    options,
                    value,
                    onValueChange,
                    enabled,
                    tabStyle,
                )
                JetpacsTabVariant.Adaptive -> error("Adaptive resolution must terminate")
            }
        }.single().measure(constraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

/** One independently focusable tab with platform tab semantics. */
@Composable
private fun JetpacsTab(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    style: Style,
    singleLine: Boolean,
) {
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isSelected = selected
    }
    JetpacsTabLayout(
        label = label,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
        style = style,
        interactionSource = source,
        styleState = styleState,
        singleLine = singleLine,
    )
}

@Composable
private fun JetpacsTabLayout(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    style: Style,
    interactionSource: MutableInteractionSource,
    styleState: StyleState,
    singleLine: Boolean,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .styleable(styleState, JetpacsTheme.styles.tab, style),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            style = JetpacsTheme.typography.choice.copy(textAlign = TextAlign.Center),
            maxLines = if (singleLine) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .styleable(
                        remember { MutableStyleState(null) },
                        JetpacsTheme.styles.tabIndicator,
                    ),
            )
        }
    }
}
