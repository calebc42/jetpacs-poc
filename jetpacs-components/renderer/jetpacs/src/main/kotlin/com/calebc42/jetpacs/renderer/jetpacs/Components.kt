// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
    labelStyle: Style = Style,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
    }
    JetpacsActionLayout(
        label, onClick, modifier, style, labelStyle, enabled, source, styleState,
    )
}

@Composable
private fun JetpacsActionLayout(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    style: Style,
    labelStyle: Style,
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
            .styleable(
                styleState,
                JetpacsTheme.styles.action,
                designComponentStyle(DesignComponentStyleSlot.ActionContainer),
                style,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.styleable(
                styleState,
                designComponentNonTextStyle(DesignComponentStyleSlot.ActionLabel),
                labelStyle,
            ),
        ) {
            BasicText(
                label,
                style = resolvedDesignTextStyle(
                    DesignComponentStyleSlot.ActionLabel,
                    JetpacsTheme.typography.action,
                    styleState,
                ),
            )
        }
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
    indicatorStyle: Style = Style,
    labelStyle: Style = Style,
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
        indicatorStyle,
        labelStyle,
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
    indicatorStyle: Style,
    labelStyle: Style,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    styleState: StyleState,
) {
    val colors = JetpacsTheme.colors
    val hasCustomIndicator =
        LocalDesignScope.current?.componentStyle(
            DesignComponentStyleSlot.ChoiceIndicator,
        ) != null || indicatorStyle !== Style
    val indicatorContentColor = resolvedDesignTextStyle(
        DesignComponentStyleSlot.ChoiceIndicator,
        TextStyle(color = colors.onAccent),
        styleState,
    ).color
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
            .styleable(
                styleState,
                JetpacsTheme.styles.choice,
                designComponentStyle(DesignComponentStyleSlot.ChoiceContainer),
                style,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier
                .size(18.dp)
                .let { base ->
                    if (hasCustomIndicator) {
                        base.styleable(
                            styleState,
                            JetpacsTheme.styles.choiceIndicator,
                            designComponentStyle(DesignComponentStyleSlot.ChoiceIndicator),
                            indicatorStyle,
                        )
                    } else {
                        base
                    }
                },
        ) {
            if (!hasCustomIndicator) {
                drawRoundRect(
                    color = if (checked) colors.accent else colors.surface,
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                drawRoundRect(
                    color = if (checked) colors.accent else colors.outline,
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            if (checked) {
                drawLine(
                    color = indicatorContentColor,
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
        Box(
            modifier = Modifier.styleable(
                styleState,
                designComponentNonTextStyle(DesignComponentStyleSlot.ChoiceLabel),
                labelStyle,
            ),
        ) {
            BasicText(
                label,
                style = resolvedDesignTextStyle(
                    DesignComponentStyleSlot.ChoiceLabel,
                    JetpacsTheme.typography.choice,
                    styleState,
                ),
            )
        }
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
    JetpacsActionLayout(label, {}, Modifier, Style, Style, true, source, state)
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
    JetpacsChoiceLayout(
        label, false, {}, Modifier, Style, Style, Style, true, source, state,
    )
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
    labelStyle: Style = Style,
    content: @Composable ColumnScope.() -> Unit,
) {
    val styleState = remember { MutableStyleState(null) }
    Column(
        modifier = modifier.styleable(
            styleState,
            JetpacsTheme.styles.panel,
            designComponentStyle(DesignComponentStyleSlot.PanelContainer),
            style,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.styleable(
                styleState,
                designComponentNonTextStyle(DesignComponentStyleSlot.PanelLabel),
                labelStyle,
            ),
        ) {
            BasicText(
                label,
                modifier = Modifier.semantics { heading() },
                style = resolvedDesignTextStyle(
                    DesignComponentStyleSlot.PanelLabel,
                    JetpacsTheme.typography.panelLabel,
                    styleState,
                ),
            )
        }
        content()
    }
}
