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
import androidx.compose.ui.graphics.Color
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
internal fun ComputedDesignStyle.toFoundationStyle(): Style = Style {
    for (layer in layers) {
        when (layer.state) {
            null -> applyDesignLayer(layer)
            DesignState.Disabled -> disabled { applyDesignLayer(layer) }
            DesignState.Selected -> selected { applyDesignLayer(layer) }
            DesignState.Toggled -> checked { applyDesignLayer(layer) }
            DesignState.Hovered -> hovered { applyDesignLayer(layer) }
            DesignState.Focused -> focused { applyDesignLayer(layer) }
            DesignState.Pressed -> pressed { applyDesignLayer(layer) }
        }
    }
}

private fun StyleScope.applyDesignLayer(layer: DesignStyleLayer) {
    val motion = layer.motion
    if (motion == null) {
        applyDesignProperties(layer.properties)
    } else {
        animate(motion.animationSpec()) {
            applyDesignProperties(layer.properties)
        }
    }
}

private fun StyleScope.applyDesignProperties(
    properties: Map<DesignProperty, DesignValue>,
) {
    // Broad padding setters intentionally run before axis and side setters.
    // This gives the most specific authored property the final Style value.
    for (property in DesignProperty.entries) {
        val value = properties[property] ?: continue
        when (property) {
            DesignProperty.BackgroundColor -> background(value.color())
            DesignProperty.ContentColor -> contentColor(value.color())
            DesignProperty.BorderColor -> borderColor(value.color())
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
                if (value.boolean()) fillWidth() else width(Float.NaN)
            }
        }
    }
}

private fun DesignMotion.animationSpec(): AnimationSpec<Float> = when (easing) {
    DesignEasing.Linear -> tween(durationMillis, easing = LinearEasing)
    DesignEasing.EaseIn -> tween(durationMillis, easing = FastOutLinearInEasing)
    DesignEasing.EaseOut -> tween(durationMillis, easing = LinearOutSlowInEasing)
    DesignEasing.EaseInOut -> tween(durationMillis, easing = FastOutSlowInEasing)
    DesignEasing.Spring -> spring(dampingRatio = Spring.DampingRatioMediumBouncy)
}

private fun DesignValue.color(): Color =
    Color((this as DesignValue.ColorValue).argb.toULong())

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
