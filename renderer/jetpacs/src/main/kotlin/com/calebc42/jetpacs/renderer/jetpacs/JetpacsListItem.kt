// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.ebp.renderer.compose.RevealSwipeDirection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Jetpacs' list row.
 *
 * `leading · overline/title/subtitle · trailing`, laid out so the flexible
 * text column absorbs the width and the pinned edges keep theirs. The root is
 * the sole click target with a 48-dp interaction bound.
 *
 * Flat by default: a row is a region of a list, not an object floating above
 * one. [style] and the three text styles change visual properties only and are
 * layered after the theme and the active design profile, so a caller can
 * adjust a row without restating it.
 *
 * [selected] non-null makes the row a selectable one; null keeps it an
 * ordinary button. Accessibility beyond the derived role — a merged name,
 * custom actions — belongs on [modifier], which is applied at the root.
 */
@Composable
fun JetpacsListItem(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    subtitle: String? = null,
    titleMaxLines: Int = Int.MAX_VALUE,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    style: Style = Style,
    overlineStyle: Style = Style,
    titleStyle: Style = Style,
    subtitleStyle: Style = Style,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isSelected = selected == true
    }
    val resolvedTitle = resolvedDesignTextStyle(
        DesignComponentStyleSlot.ListItemTitle,
        JetpacsTheme.typography.choice,
        styleState,
    )
    val resolvedOverline = resolvedDesignTextStyle(
        DesignComponentStyleSlot.ListItemOverline,
        JetpacsTheme.typography.fieldLabel,
        styleState,
    )
    val resolvedSubtitle = resolvedDesignTextStyle(
        DesignComponentStyleSlot.ListItemSubtitle,
        JetpacsTheme.typography.fieldSupporting,
        styleState,
    )
    val interactive = when {
        onClick == null && onLongClick == null -> Modifier
        selected != null -> Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.Tab,
            interactionSource = source,
            indication = null,
        ) { onClick?.invoke() }
        else -> Modifier.combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onLongClick = onLongClick,
        ) { onClick?.invoke() }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .hoverable(source, enabled && (onClick != null || onLongClick != null))
            .focusable(enabled && onClick != null, source)
            .then(interactive)
            .styleable(
                styleState,
                JetpacsTheme.styles.listItem,
                designComponentStyle(DesignComponentStyleSlot.ListItemContainer),
                style,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!overline.isNullOrEmpty()) {
                Box(Modifier.styleable(styleState, Style, overlineStyle)) {
                    BasicText(
                        overline,
                        style = resolvedOverline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.styleable(styleState, Style, titleStyle)) {
                BasicText(
                    title,
                    style = resolvedTitle,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrEmpty()) {
                Box(Modifier.styleable(styleState, Style, subtitleStyle)) {
                    BasicText(
                        subtitle,
                        style = resolvedSubtitle,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

/**
 * Present one `jetpacs.list_item` node.
 *
 * The wire reading, the reveal swipe and the semantics an extension node does
 * not inherit live here; the row itself is [JetpacsListItem]. Extension nodes
 * receive no generic semantics projection — that map is keyed on canonical
 * node types — so this states the merged accessible name and the swipe
 * actions, and hands them to the row through its modifier.
 */
@Composable
internal fun RenderListItem(
    node: JsonObject,
    context: ComposeExtensionRenderContext,
    modifier: Modifier,
) {
    val enabled = node.boolean("enabled", true)
    val selected = if ("selected" in node) node.boolean("selected", false) else null
    val onTap = node["on_tap"] as? JsonObject
    val onLongTap = node["on_long_tap"] as? JsonObject
    val title = node.text("title")
    val overline = node.text("overline")
    val subtitle = node.text("subtitle")
    val swipeStart = node["swipe_start"] as? JsonObject
    val swipeEnd = node["swipe_end"] as? JsonObject
    val swipeActions = remember(swipeStart, swipeEnd) {
        listOfNotNull(
            jetpacsSwipeSide(swipeStart)?.let { RevealSwipeDirection.Start to it },
            jetpacsSwipeSide(swipeEnd)?.let { RevealSwipeDirection.End to it },
        )
    }
    // One announcement for the row, rather than three separate labels.
    val name = listOf(overline, title, subtitle)
        .filter(String::isNotEmpty)
        .joinToString(". ")

    JetpacsSwipeRow(
        identity = context.path,
        swipeStart = swipeStart,
        swipeEnd = swipeEnd,
        context = context,
    ) { isOpen, close ->
        JetpacsListItem(
            title = title,
            modifier = modifier.semantics(mergeDescendants = true) {
                if (name.isNotEmpty()) contentDescription = name
                if (!enabled) disabled()
                if (swipeActions.isNotEmpty()) {
                    customActions = swipeActions.flatMap { (direction, side) ->
                        side.actions.map { action ->
                            CustomAccessibilityAction(action.label) {
                                context.swipeAction(action.descriptor, direction)
                                true
                            }
                        }
                    }.distinctBy { it.label }
                }
            },
            overline = overline,
            subtitle = subtitle,
            titleMaxLines = node.positiveInt("title_max_lines") ?: Int.MAX_VALUE,
            subtitleMaxLines = node.positiveInt("subtitle_max_lines") ?: Int.MAX_VALUE,
            leading = (node["leading"] as? JsonObject)?.let { leading ->
                { context.renderChild(leading, 0) }
            },
            trailing = (node["trailing"] as? JsonObject)?.let { trailing ->
                // Index 1: the leading slot owns 0, so both keep a distinct
                // identity path under the row.
                { context.renderChild(trailing, 1) }
            },
            // An open row's tap closes it. Firing the row's own action from
            // under a revealed strip is the one interaction a reveal-first
            // swipe must never allow.
            onClick = if (isOpen) close else onTap?.let { { context.action(it) } },
            onLongClick = if (isOpen) null else onLongTap?.let { { context.action(it) } },
            selected = if (isOpen) null else selected,
            enabled = enabled,
        )
    }
}

private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default

private fun JsonObject.positiveInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        ?.toInt()?.takeIf { it > 0 }
