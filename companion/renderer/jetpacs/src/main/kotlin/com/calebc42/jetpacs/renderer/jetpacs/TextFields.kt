// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.contentPadding
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Jetpacs-native interpretations of EBP's neutral field variants. */
enum class JetpacsTextFieldVariant {
    Outlined,
    Filled,
}

/**
 * Jetpacs' state-based Foundation text field.
 *
 * [style] changes the editable work surface only. The incoming [modifier]
 * remains on the single interaction-owning field, while labels, affixes,
 * glyphs, and supporting text are non-interactive presentation decorations.
 */
@Composable
fun JetpacsTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    style: Style = Style,
    enabled: Boolean = true,
    secure: Boolean = false,
    variant: JetpacsTextFieldVariant = JetpacsTextFieldVariant.Outlined,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    prefix: String? = null,
    suffix: String? = null,
    leadingDecoration: (@Composable () -> Unit)? = null,
    trailingDecoration: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    monospace: Boolean = false,
    contentPadding: Dp? = null,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isTextFieldError = isError
    }
    val baseStyle = when (variant) {
        JetpacsTextFieldVariant.Outlined -> JetpacsTheme.styles.textFieldOutlined
        JetpacsTextFieldVariant.Filled -> JetpacsTheme.styles.textFieldFilled
    }
    val paddingStyle = contentPadding?.let { inset ->
        Style { contentPadding(inset) }
    } ?: Style
    val colors = JetpacsTheme.colors
    val typography = JetpacsTheme.typography
    val textStyle = (if (monospace) typography.code else typography.field).copy(
        color = colors.content,
    )
    val decorator = TextFieldDecorator { innerTextField ->
        JetpacsTextFieldDecoration(
            textIsEmpty = state.text.isEmpty(),
            label = label,
            placeholder = placeholder,
            supportingText = supportingText,
            prefix = prefix,
            suffix = suffix,
            leadingDecoration = leadingDecoration,
            trailingDecoration = trailingDecoration,
            isError = isError,
            enabled = enabled,
            styleState = styleState,
            baseStyle = baseStyle,
            paddingStyle = paddingStyle,
            callerStyle = style,
            innerTextField = innerTextField,
        )
    }
    val fieldModifier = modifier.heightIn(min = 48.dp)
    if (secure) {
        BasicSecureTextField(
            state = state,
            modifier = fieldModifier,
            enabled = enabled,
            inputTransformation = inputTransformation,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            interactionSource = source,
            cursorBrush = SolidColor(colors.focus),
            decorator = decorator,
            textObfuscationMode = TextObfuscationMode.Hidden,
        )
    } else {
        BasicTextField(
            state = state,
            modifier = fieldModifier,
            enabled = enabled,
            readOnly = false,
            inputTransformation = inputTransformation,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = lineLimits,
            interactionSource = source,
            cursorBrush = SolidColor(colors.focus),
            outputTransformation = outputTransformation,
            decorator = decorator,
        )
    }
}

/**
 * Value-based field used only when an EBP mask supplies explicit offsets.
 * Decorations remain semantics-free and the [BasicTextField] is still the
 * sole editable owner.
 */
@Composable
internal fun JetpacsMaskedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    visualTransformation: VisualTransformation,
    modifier: Modifier = Modifier,
    style: Style = Style,
    enabled: Boolean = true,
    variant: JetpacsTextFieldVariant = JetpacsTextFieldVariant.Outlined,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    prefix: String? = null,
    suffix: String? = null,
    leadingDecoration: (@Composable () -> Unit)? = null,
    trailingDecoration: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    monospace: Boolean = false,
    contentPadding: Dp? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isTextFieldError = isError
    }
    val baseStyle = when (variant) {
        JetpacsTextFieldVariant.Outlined -> JetpacsTheme.styles.textFieldOutlined
        JetpacsTextFieldVariant.Filled -> JetpacsTheme.styles.textFieldFilled
    }
    val paddingStyle = contentPadding?.let { inset ->
        Style { contentPadding(inset) }
    } ?: Style
    val colors = JetpacsTheme.colors
    val typography = JetpacsTheme.typography
    val textStyle = (if (monospace) typography.code else typography.field).copy(
        color = colors.content,
    )
    val multiLine = lineLimits as? TextFieldLineLimits.MultiLine
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        readOnly = false,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = lineLimits == TextFieldLineLimits.SingleLine,
        minLines = multiLine?.minHeightInLines ?: 1,
        maxLines = multiLine?.maxHeightInLines ?: 1,
        visualTransformation = visualTransformation,
        interactionSource = source,
        cursorBrush = SolidColor(colors.focus),
        decorationBox = { innerTextField ->
            JetpacsTextFieldDecoration(
                textIsEmpty = value.text.isEmpty(),
                label = label,
                placeholder = placeholder,
                supportingText = supportingText,
                prefix = prefix,
                suffix = suffix,
                leadingDecoration = leadingDecoration,
                trailingDecoration = trailingDecoration,
                isError = isError,
                enabled = enabled,
                styleState = styleState,
                baseStyle = baseStyle,
                paddingStyle = paddingStyle,
                callerStyle = style,
                innerTextField = innerTextField,
            )
        },
    )
}

@Composable
private fun JetpacsTextFieldDecoration(
    textIsEmpty: Boolean,
    label: String?,
    placeholder: String?,
    supportingText: String?,
    prefix: String?,
    suffix: String?,
    leadingDecoration: (@Composable () -> Unit)?,
    trailingDecoration: (@Composable () -> Unit)?,
    isError: Boolean,
    enabled: Boolean,
    styleState: StyleState,
    baseStyle: Style,
    paddingStyle: Style,
    callerStyle: Style,
    innerTextField: @Composable () -> Unit,
) {
    val colors = JetpacsTheme.colors
    val typography = JetpacsTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(JetpacsTheme.spacing.unit)) {
        label?.let {
            BasicText(
                it,
                modifier = Modifier.clearAndSetSemantics { },
                style = typography.fieldLabel.copy(
                    color = if (isError) colors.error else colors.mutedContent,
                ),
            )
        }
        Row(
            modifier = Modifier
                .styleable(styleState, baseStyle, paddingStyle, callerStyle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingDecoration?.let {
                Box(Modifier.clearAndSetSemantics { }) { it() }
                Spacer(Modifier.width(8.dp))
            }
            prefix?.let {
                BasicText(
                    it,
                    modifier = Modifier.clearAndSetSemantics { },
                    style = typography.field.copy(color = colors.mutedContent),
                )
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (textIsEmpty) {
                    placeholder?.let {
                        BasicText(
                            it,
                            modifier = Modifier
                                .alpha(if (enabled) 1f else 0.7f)
                                .clearAndSetSemantics { },
                            style = typography.field.copy(color = colors.mutedContent),
                        )
                    }
                }
                innerTextField()
            }
            suffix?.let {
                Spacer(Modifier.width(4.dp))
                BasicText(
                    it,
                    modifier = Modifier.clearAndSetSemantics { },
                    style = typography.field.copy(color = colors.mutedContent),
                )
            }
            trailingDecoration?.let {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clearAndSetSemantics { }) { it() }
            }
        }
        supportingText?.let {
            BasicText(
                it,
                modifier = Modifier.clearAndSetSemantics { },
                style = typography.fieldSupporting.copy(
                    color = if (isError) colors.error else colors.mutedContent,
                ),
            )
        }
    }
}

/** Stable focused visual state used only by deterministic screenshot tests. */
@Composable
internal fun JetpacsTextFieldFocusFixture(
    state: TextFieldState,
    label: String,
) {
    val styleState = remember {
        MutableStyleState(null).apply {
            isEnabled = true
            isFocused = true
            isTextFieldError = false
        }
    }
    JetpacsTextFieldDecoration(
        textIsEmpty = state.text.isEmpty(),
        label = label,
        placeholder = null,
        supportingText = "Focused work surface",
        prefix = null,
        suffix = null,
        leadingDecoration = null,
        trailingDecoration = null,
        isError = false,
        enabled = true,
        styleState = styleState,
        baseStyle = JetpacsTheme.styles.textFieldOutlined,
        paddingStyle = Style,
        callerStyle = Style,
        innerTextField = {
            BasicText(state.text.toString(), style = JetpacsTheme.typography.field)
        },
    )
}

/** Jetpacs-owned non-interactive glyph with a deterministic unknown fallback. */
@Composable
internal fun JetpacsFieldGlyph(name: String, modifier: Modifier = Modifier) {
    val color = JetpacsTheme.colors.mutedContent
    Canvas(modifier.size(16.dp).clearAndSetSemantics { }) {
        val stroke = 1.5.dp.toPx()
        when (name) {
            "search" -> {
                drawCircle(
                    color,
                    radius = size.minDimension * 0.29f,
                    center = Offset(size.width * 0.42f, size.height * 0.42f),
                    style = Stroke(stroke),
                )
                drawLine(
                    color,
                    Offset(size.width * 0.64f, size.height * 0.64f),
                    Offset(size.width * 0.88f, size.height * 0.88f),
                    stroke,
                    cap = StrokeCap.Round,
                )
            }
            "clear", "close" -> {
                drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke)
                drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke)
            }
            "lock", "password" -> {
                drawRect(
                    color,
                    topLeft = Offset(size.width * 0.18f, size.height * 0.45f),
                    size = androidx.compose.ui.geometry.Size(
                        size.width * 0.64f,
                        size.height * 0.45f,
                    ),
                    style = Stroke(stroke),
                )
                drawArc(
                    color,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.30f, size.height * 0.08f),
                    size = androidx.compose.ui.geometry.Size(
                        size.width * 0.40f,
                        size.height * 0.62f,
                    ),
                    style = Stroke(stroke),
                )
            }
            else -> {
                drawCircle(color, radius = size.minDimension * 0.36f, style = Stroke(stroke))
                drawCircle(color, radius = size.minDimension * 0.08f)
            }
        }
    }
}
