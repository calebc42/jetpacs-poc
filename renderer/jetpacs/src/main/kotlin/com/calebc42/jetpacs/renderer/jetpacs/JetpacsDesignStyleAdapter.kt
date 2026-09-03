// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleScope
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.animate
import androidx.compose.foundation.style.checked
import androidx.compose.foundation.style.contentPadding
import androidx.compose.foundation.style.contentPaddingHorizontal
import androidx.compose.foundation.style.contentPaddingVertical
import androidx.compose.foundation.style.disabled
import androidx.compose.foundation.style.fillWidth
import androidx.compose.foundation.style.focused
import androidx.compose.foundation.style.hovered
import androidx.compose.foundation.style.pressed
import androidx.compose.foundation.style.scale
import androidx.compose.foundation.style.selected
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The only AndroidX Style translation boundary. Public Kotlin and Elisp APIs
 * expose the pure [ComputedDesignStyle] model instead.
 */
internal fun ComputedDesignStyle.toFoundationStyle(
    includeTextProperties: Boolean = true,
): Style = Style {
    for (layer in layers) {
        when (layer.state) {
            null -> applyDesignLayer(layer, includeTextProperties)
            DesignState.Disabled -> disabled {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Selected -> selected {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Toggled -> checked {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Hovered -> hovered {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Focused -> focused {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Pressed -> pressed {
                applyDesignLayer(layer, includeTextProperties)
            }
        }
    }
}

private fun StyleScope.applyDesignLayer(
    layer: DesignStyleLayer,
    includeTextProperties: Boolean,
) {
    val motion = layer.motion
    if (motion == null) {
        applyDesignProperties(layer.properties, includeTextProperties)
    } else {
        animate(motion.animationSpec()) {
            applyDesignProperties(layer.properties, includeTextProperties)
        }
    }
}

private fun StyleScope.applyDesignProperties(
    properties: Map<DesignProperty, DesignValue>,
    includeTextProperties: Boolean,
) {
    // Broad padding setters intentionally run before axis and side setters.
    // This gives the most specific authored property the final Style value.
    for (property in DesignProperty.entries) {
        val value = properties[property] ?: continue
        if (!includeTextProperties && property in textProperties) continue
        when (property) {
            DesignProperty.BackgroundColor -> background(designColor(value))
            DesignProperty.ContentColor -> contentColor(designColor(value))
            DesignProperty.BorderColor -> borderColor(designColor(value))
            DesignProperty.BorderWidth -> borderWidth(value.dp())
            DesignProperty.CornerRadius -> shape(RoundedCornerShape(value.dp()))
            DesignProperty.Padding -> contentPadding(value.dp())
            DesignProperty.PaddingHorizontal -> contentPaddingHorizontal(value.dp())
            DesignProperty.PaddingVertical -> contentPaddingVertical(value.dp())
            DesignProperty.PaddingStart -> contentPaddingStart(value.dp())
            DesignProperty.PaddingTop -> contentPaddingTop(value.dp())
            DesignProperty.PaddingEnd -> contentPaddingEnd(value.dp())
            DesignProperty.PaddingBottom -> contentPaddingBottom(value.dp())
            DesignProperty.Width -> width(value.dp())
            DesignProperty.Height -> height(value.dp())
            DesignProperty.MinWidth -> minWidth(value.dp())
            DesignProperty.MinHeight -> minHeight(value.dp())
            DesignProperty.Alpha -> alpha(value.number())
            DesignProperty.Scale -> scale(value.number())
            DesignProperty.FontSize -> fontSize(value.sp())
            DesignProperty.LineHeight -> lineHeight(value.sp())
            DesignProperty.LetterSpacing -> letterSpacing(value.sp())
            DesignProperty.FontFamily -> fontFamily(value.fontFamily())
            DesignProperty.FontWeight -> fontWeight(FontWeight(value.fontWeight()))
            DesignProperty.TextAlign -> textAlign(value.textAlign())
            DesignProperty.FillWidth -> {
                // Foundation Styles have no "unset width": a NaN fraction
                // collapses the box and an unspecified Dp keeps an inherited
                // fill (both observed on device). `false` therefore only
                // declines to add a fill; a component whose base style fills
                // its row stays full-row, which is why Grove projects its
                // small buttons onto `jetpacs.pressable` instead of Action.
                if (value.boolean()) fillWidth()
            }
        }
    }
}

private val textProperties = setOf(
    DesignProperty.ContentColor,
    DesignProperty.FontSize,
    DesignProperty.LineHeight,
    DesignProperty.LetterSpacing,
    DesignProperty.FontFamily,
    DesignProperty.FontWeight,
    DesignProperty.TextAlign,
)

private fun DesignMotion.animationSpec(): AnimationSpec<Float> = when (easing) {
    DesignEasing.Linear -> tween(durationMillis, easing = LinearEasing)
    DesignEasing.EaseIn -> tween(durationMillis, easing = FastOutLinearInEasing)
    DesignEasing.EaseOut -> tween(durationMillis, easing = LinearOutSlowInEasing)
    DesignEasing.EaseInOut -> tween(durationMillis, easing = FastOutSlowInEasing)
    DesignEasing.Spring -> spring(dampingRatio = Spring.DampingRatioMediumBouncy)
}

private fun StyleScope.designColor(value: DesignValue): Color = when (value) {
    is DesignValue.ColorValue -> Color(value.argb)
    is DesignValue.ThemeRoleValue -> LocalJetpacsTheme.currentValue.roles[value.value]
    else -> error("Design color property received ${value::class.simpleName}")
}

/** Resolve base text properties for drawing APIs that do not accept Style. */
@Composable
@ReadOnlyComposable
internal fun resolvedDesignTextStyle(
    slot: DesignComponentStyleSlot,
    fallback: TextStyle,
    state: StyleState? = null,
): TextStyle {
    val properties = LocalDesignScope.current
        ?.componentStyle(slot)
        ?.resolve(state.activeDesignStates())
        ?.properties
        ?: return fallback
    return fallback.withDesignTextProperties(properties, JetpacsTheme.roles)
}

/**
 * Overlay authored text properties on [this] fallback text style.
 *
 * Only the seven text-bearing design properties participate; layout and draw
 * properties belong to the Foundation Style applied through modifiers. Pure
 * so text resolution can be unit-tested without a composition.
 */
internal fun TextStyle.withDesignTextProperties(
    properties: Map<DesignProperty, DesignValue>,
    roles: JetpacsThemeRoles,
): TextStyle = copy(
    color = properties[DesignProperty.ContentColor]?.let { value ->
        when (value) {
            is DesignValue.ColorValue -> Color(value.argb)
            is DesignValue.ThemeRoleValue -> roles[value.value]
            else -> color
        }
    } ?: color,
    fontSize = properties[DesignProperty.FontSize]?.sp() ?: fontSize,
    lineHeight = properties[DesignProperty.LineHeight]?.sp() ?: lineHeight,
    letterSpacing = properties[DesignProperty.LetterSpacing]?.sp() ?: letterSpacing,
    fontFamily = properties[DesignProperty.FontFamily]?.fontFamily() ?: fontFamily,
    fontWeight = properties[DesignProperty.FontWeight]?.let {
        FontWeight(it.fontWeight())
    } ?: fontWeight,
    textAlign = properties[DesignProperty.TextAlign]?.textAlign() ?: textAlign,
)

/**
 * Resolve one color property of a component slot, or null when it is unbound.
 *
 * Drawing APIs that take a plain color rather than a Style — a swipe cell's
 * background, for one — read the profile through here, so the experimental
 * Style types stay inside this adapter.
 */
@Composable
@ReadOnlyComposable
internal fun resolvedDesignColor(
    slot: DesignComponentStyleSlot,
    property: DesignProperty,
): Color? {
    val value = LocalDesignScope.current
        ?.componentStyle(slot)
        ?.resolve()
        ?.properties
        ?.get(property)
        ?: return null
    return when (value) {
        is DesignValue.ColorValue -> Color(value.argb)
        is DesignValue.ThemeRoleValue -> JetpacsTheme.roles[value.value]
        else -> null
    }
}

internal fun StyleState?.activeDesignStates(): Set<DesignState> {
    if (this == null) return emptySet()
    return buildSet {
        if (!isEnabled) add(DesignState.Disabled)
        if (isSelected) add(DesignState.Selected)
        if (isChecked) add(DesignState.Toggled)
        if (isHovered) add(DesignState.Hovered)
        if (isFocused) add(DesignState.Focused)
        if (isPressed) add(DesignState.Pressed)
    }
}

private fun DesignValue.dp() = (this as DesignValue.DimensionValue).value.dp

private fun DesignValue.sp() = (this as DesignValue.DimensionValue).value.sp

private fun DesignValue.number(): Float = (this as DesignValue.NumberValue).value.toFloat()

private fun DesignValue.boolean(): Boolean = (this as DesignValue.BooleanValue).value

private fun DesignValue.fontWeight(): Int = (this as DesignValue.FontWeightValue).value

private fun DesignValue.textAlign(): TextAlign = when (
    (this as DesignValue.TextAlignValue).value
) {
    DesignTextAlign.Start -> TextAlign.Start
    DesignTextAlign.Center -> TextAlign.Center
    DesignTextAlign.End -> TextAlign.End
}

private fun DesignValue.fontFamily(): FontFamily = when (
    (this as DesignValue.FontFamilyValue).value
) {
    DesignFontFamily.System -> FontFamily.Default
    DesignFontFamily.PlexSans -> PlexFonts.sans
    DesignFontFamily.PlexSerif -> PlexFonts.serif
    DesignFontFamily.PlexMono -> PlexFonts.mono
}

private object PlexFonts {
    val sans = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    )
    val serif = FontFamily(
        Font(R.font.ibm_plex_serif_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_serif_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_serif_semibold, FontWeight.SemiBold),
    )
    val mono = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
    )
}
