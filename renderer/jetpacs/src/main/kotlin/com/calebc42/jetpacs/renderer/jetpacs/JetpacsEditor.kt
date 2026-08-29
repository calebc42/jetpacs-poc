// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * Jetpacs' state-based Foundation editor.
 *
 * The incoming [modifier] remains on the sole editable semantics owner.
 * [style] changes only the work-surface visuals, and [lineNumbers] draws one
 * semantics-free logical-line gutter using the editor's own layout and scroll
 * state so wrapped lines never create false line numbers.
 */
@Composable
fun JetpacsEditor(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    style: Style = Style,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    lineNumbers: Boolean = false,
    chromeless: Boolean = false,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.MultiLine(3, Int.MAX_VALUE),
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isEditorReadOnly = readOnly
    }
    val baseStyle = if (chromeless) {
        JetpacsTheme.styles.editorChromeless
    } else {
        JetpacsTheme.styles.editor
    }
    val scrollState = rememberScrollState()
    val textLayoutState = remember { EditorTextLayoutState() }
    val decorator = TextFieldDecorator { innerTextField ->
        JetpacsEditorDecoration(
            text = state.text.toString(),
            lineNumbers = lineNumbers,
            textLayoutState = textLayoutState,
            scrollState = scrollState,
            styleState = styleState,
            baseStyle = baseStyle,
            callerStyle = style,
            innerTextField = innerTextField,
        )
    }
    BasicTextField(
        state = state,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        readOnly = readOnly,
        inputTransformation = inputTransformation,
        textStyle = JetpacsTheme.typography.code.copy(color = JetpacsTheme.colors.content),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = lineLimits,
        onTextLayout = { result -> textLayoutState.result = result },
        interactionSource = source,
        cursorBrush = SolidColor(JetpacsTheme.colors.focus),
        outputTransformation = outputTransformation,
        decorator = decorator,
        scrollState = scrollState,
    )
}

@Composable
private fun JetpacsEditorDecoration(
    text: String,
    lineNumbers: Boolean,
    textLayoutState: EditorTextLayoutState?,
    scrollState: ScrollState,
    styleState: StyleState,
    baseStyle: Style,
    callerStyle: Style,
    innerTextField: @Composable () -> Unit,
) {
    val lineStarts = remember(text) { logicalLineStarts(text) }
    val gutterStyle = JetpacsTheme.typography.code.copy(
        color = JetpacsTheme.colors.mutedContent,
    )
    val gutterDivider = JetpacsTheme.colors.outline.copy(alpha = 0.55f)
    val measurer = rememberTextMeasurer()
    val widest = remember(measurer, lineStarts.size, gutterStyle) {
        measurer.measure(
            AnnotatedString(lineStarts.size.toString()),
            style = gutterStyle,
        )
    }
    val gutterWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
        widest.size.width.toDp()
    } + 20.dp
    if (!lineNumbers) {
        Box(modifier = Modifier.styleable(styleState, baseStyle, callerStyle)) {
            innerTextField()
        }
        return
    }
    Layout(
        modifier = Modifier
            .styleable(styleState, baseStyle, callerStyle)
            .drawWithContent {
                drawContent()
                val gutterWidthPx = gutterWidth.toPx()
                val separator = if (layoutDirection == LayoutDirection.Ltr) {
                    gutterWidthPx - 1.dp.toPx()
                } else {
                    size.width - gutterWidthPx + 1.dp.toPx()
                }
                drawLine(
                    color = gutterDivider,
                    start = Offset(separator, 0f),
                    end = Offset(separator, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                val layoutResult = textLayoutState?.result?.invoke()
                lineStarts.forEachIndexed { index, start ->
                    val baseline = if (layoutResult == null) {
                        widest.firstBaseline + index * widest.size.height.toFloat()
                    } else {
                        val visualLine = layoutResult.getLineForOffset(
                            start.coerceIn(0, layoutResult.layoutInput.text.length),
                        )
                        layoutResult.getLineBaseline(visualLine)
                    } - scrollState.value
                    val top = baseline - widest.firstBaseline
                    if (top + widest.size.height < 0f || top > size.height) {
                        return@forEachIndexed
                    }
                    val number = measurer.measure(
                        AnnotatedString((index + 1).toString()),
                        style = gutterStyle,
                    )
                    val x = if (layoutDirection == LayoutDirection.Ltr) {
                        gutterWidthPx - 8.dp.toPx() - number.size.width
                    } else {
                        size.width - gutterWidthPx + 8.dp.toPx()
                    }
                    drawText(
                        number,
                        topLeft = Offset(x, baseline - number.firstBaseline),
                    )
                }
            },
        content = {
            Box { innerTextField() }
        },
    ) { measurables, constraints ->
        val gutterWidthPx = gutterWidth.roundToPx()
        val maxInnerWidth = if (constraints.maxWidth == Constraints.Infinity) {
            Constraints.Infinity
        } else {
            (constraints.maxWidth - gutterWidthPx).coerceAtLeast(0)
        }
        val inner = measurables.single().measure(
            constraints.copy(
                minWidth = (constraints.minWidth - gutterWidthPx).coerceAtLeast(0),
                maxWidth = maxInnerWidth,
            ),
        )
        val width = constraints.constrainWidth(inner.width + gutterWidthPx)
        val height = constraints.constrainHeight(inner.height)
        layout(width, height) {
            if (layoutDirection == LayoutDirection.Ltr) {
                inner.place(gutterWidthPx, 0)
            } else {
                inner.place(0, 0)
            }
        }
    }
}

/** UTF-16 starts of logical lines, including an empty trailing line. */
internal fun logicalLineStarts(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts += 0
    text.forEachIndexed { index, char ->
        if (char == '\n') starts += index + 1
    }
    return starts.toIntArray()
}

/** Stable read-only visual state used only by deterministic screenshot tests. */
@Composable
internal fun JetpacsEditorReadOnlyFixture(
    state: TextFieldState,
    lineNumbers: Boolean,
) {
    val styleState = remember {
        MutableStyleState(null).apply {
            isEnabled = true
            isEditorReadOnly = true
        }
    }
    JetpacsEditorDecoration(
        text = state.text.toString(),
        lineNumbers = lineNumbers,
        textLayoutState = null,
        scrollState = rememberScrollState(),
        styleState = styleState,
        baseStyle = JetpacsTheme.styles.editor,
        callerStyle = Style,
        innerTextField = {
            BasicText(
                state.text.toString(),
                style = JetpacsTheme.typography.code.copy(
                    color = JetpacsTheme.colors.content,
                ),
            )
        },
    )
}

/** Stable focused visual state used only by deterministic screenshot tests. */
@Composable
internal fun JetpacsEditorFocusFixture(state: TextFieldState) {
    val styleState = remember {
        MutableStyleState(null).apply {
            isEnabled = true
            isFocused = true
            isEditorReadOnly = false
        }
    }
    JetpacsEditorDecoration(
        text = state.text.toString(),
        lineNumbers = false,
        textLayoutState = null,
        scrollState = rememberScrollState(),
        styleState = styleState,
        baseStyle = JetpacsTheme.styles.editor,
        callerStyle = Style,
        innerTextField = {
            BasicText(
                state.text.toString(),
                style = JetpacsTheme.typography.code.copy(
                    color = JetpacsTheme.colors.content,
                ),
            )
        },
    )
}

/** Measure-to-draw handoff; the getter is sampled only after field layout. */
private class EditorTextLayoutState {
    var result: () -> TextLayoutResult? = { null }
}
