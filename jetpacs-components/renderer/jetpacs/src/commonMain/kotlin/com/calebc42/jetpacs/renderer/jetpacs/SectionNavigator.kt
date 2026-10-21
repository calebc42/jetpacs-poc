// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.styleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** One stable, non-empty authored destination in a section outline. */
@Immutable
data class JetpacsSectionOption(
    val label: String,
    val value: String,
    val level: Int,
) {
    init {
        require(label.isNotEmpty()) { "Section label must be non-empty" }
        require(value.isNotEmpty()) { "Section value must be non-empty" }
        require(level in 1..6) { "Section level must be in 1..6" }
    }
}

/**
 * Compute stable visual ancestry from the authored outline order.
 *
 * Missing intermediate levels are intentionally allowed. A new item closes
 * every preceding item at the same or a deeper level, then inherits the
 * remaining ancestor labels.
 */
internal fun jetpacsSectionBreadcrumbs(
    options: List<JetpacsSectionOption>,
): Map<String, String> {
    val ancestors = mutableListOf<Pair<Int, String>>()
    return buildMap {
        options.forEach { option ->
            while (ancestors.lastOrNull()?.first?.let { it >= option.level } == true) {
                ancestors.removeAt(ancestors.lastIndex)
            }
            val breadcrumb = (ancestors.map { it.second } + option.label)
                .joinToString(" › ")
            put(option.value, breadcrumb)
            ancestors += option.level to option.label
        }
    }
}

/**
 * A controlled, hierarchy-aware navigator for an ordered document outline.
 *
 * [value] is the sole selection authority. The receiver retains only popup,
 * focus, and scroll presentation state; Previous, Next, and popup selection
 * report one exact authored option through [onValueChange]. Options are shown
 * in authored order, with [JetpacsSectionOption.level] providing indentation
 * and breadcrumb ancestry rather than introducing a second tree model.
 *
 * An empty [options] list intentionally emits no UI and does not constrain
 * [value]. A non-empty list requires unique option values and [value] must
 * select one of them; invalid input fails before breadcrumb construction or
 * keyed composition.
 */
@Composable
fun JetpacsSectionNavigator(
    options: List<JetpacsSectionOption>,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: Style = Style,
    sectionStyle: Style = Style,
    enabled: Boolean = true,
    labelStyle: Style = Style,
    buttonStyle: Style = Style,
    selectorStyle: Style = Style,
    popupStyle: Style = Style,
    popupItemStyle: Style = Style,
) {
    validateJetpacsControlledOptions(
        componentName = "JetpacsSectionNavigator",
        optionValues = options.map { it.value },
        value = value,
    )
    if (options.isEmpty()) return
    val breadcrumbs = remember(options) { jetpacsSectionBreadcrumbs(options) }
    Box(
        modifier = modifier.styleable(
            remember { MutableStyleState(null) },
            JetpacsTheme.styles.sectionNavigator,
            designComponentStyle(DesignComponentStyleSlot.SectionNavigatorContainer),
            style,
        ),
    ) {
        JetpacsControlledNavigator(
            options = options.map { option ->
                JetpacsNavigatorOption(
                    label = option.label,
                    value = option.value,
                    accessibleLabel = breadcrumbs.getValue(option.value),
                    level = option.level,
                )
            },
            value = value,
            onValueChange = onValueChange,
            semantics = JetpacsNavigatorSemantics.Sections,
            optionStyle = sectionStyle,
            labelStyle = labelStyle,
            buttonStyle = buttonStyle,
            selectorStyle = selectorStyle,
            popupStyle = popupStyle,
            popupItemStyle = popupItemStyle,
            enabled = enabled,
        )
    }
}
