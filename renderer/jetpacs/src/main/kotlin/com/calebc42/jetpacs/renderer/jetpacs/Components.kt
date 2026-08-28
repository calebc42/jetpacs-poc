// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText

/**
 * Jetpacs' canonical full-width action control.
 *
 * [style] changes visual properties only. The root remains the sole button and
 * click target, with a minimum 48-dp interaction bound.
 */
@Composable
fun JetpacsAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: Style = Style,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
    }
    JetpacsActionLayout(label, onClick, modifier, style, enabled, source, styleState)
}

@Composable
private fun JetpacsActionLayout(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    style: Style,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    styleState: StyleState,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .styleable(styleState, JetpacsTheme.styles.action, style),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = JetpacsTheme.typography.action)
    }
}

/**
 * Jetpacs' binary choice row.
 *
 * The row owns one checkbox target; the drawn indicator is presentation only.
 */
@Composable
fun JetpacsChoice(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    style: Style = Style,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isSelected = checked
    }
    JetpacsChoiceLayout(
        label,
        checked,
        onCheckedChange,
        modifier,
        style,
        enabled,
        source,
        styleState,
    )
}

@Composable
private fun JetpacsChoiceLayout(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    style: Style,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    styleState: StyleState,
) {
    val colors = JetpacsTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .styleable(styleState, JetpacsTheme.styles.choice, style),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp)) {
            drawRoundRect(
                color = if (checked) colors.accent else colors.surface,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
            drawRoundRect(
                color = if (checked) colors.accent else colors.outline,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
            if (checked) {
                drawLine(
                    color = colors.onAccent,
                    start = Offset(size.width * 0.23f, size.height * 0.52f),
                    end = Offset(size.width * 0.43f, size.height * 0.72f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = colors.onAccent,
                    start = Offset(size.width * 0.43f, size.height * 0.72f),
                    end = Offset(size.width * 0.78f, size.height * 0.30f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = JetpacsTheme.typography.choice)
    }
}

/** Stable final interaction states used only by deterministic visual tests. */
@Composable
internal fun JetpacsActionFocusFixture(label: String) {
    val source = remember { MutableInteractionSource() }
    val state = remember {
        MutableStyleState(null).apply {
            isEnabled = true
            isFocused = true
        }
    }
    JetpacsActionLayout(label, {}, Modifier, Style, true, source, state)
}

/** Stable final interaction states used only by deterministic visual tests. */
@Composable
internal fun JetpacsChoiceHoverFixture(label: String) {
    val source = remember { MutableInteractionSource() }
    val state = remember {
        MutableStyleState(null).apply {
            isEnabled = true
            isHovered = true
        }
    }
    JetpacsChoiceLayout(label, false, {}, Modifier, Style, true, source, state)
}

/**
 * Jetpacs' outlined content panel.
 *
 * The label is a heading and [content] remains an unmerged descendant tree.
 */
@Composable
fun JetpacsPanel(
    label: String,
    modifier: Modifier = Modifier,
    style: Style = Style,
    content: @Composable ColumnScope.() -> Unit,
) {
    val styleState = remember { MutableStyleState(null) }
    Column(
        modifier = modifier.styleable(styleState, JetpacsTheme.styles.panel, style),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            label,
            modifier = Modifier.semantics { heading() },
            style = JetpacsTheme.typography.panelLabel,
        )
        content()
    }
}
