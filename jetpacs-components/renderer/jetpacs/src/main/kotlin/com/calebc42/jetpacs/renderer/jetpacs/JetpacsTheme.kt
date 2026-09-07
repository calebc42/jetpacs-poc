// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Private color vocabulary for Jetpacs' editor-native component language. */
@Immutable
data class JetpacsColors(
    val background: Color,
    val content: Color,
    val mutedContent: Color,
    val accent: Color,
    val onAccent: Color,
    val surface: Color,
    val raisedSurface: Color,
    val selectedSurface: Color,
    val pressedSurface: Color,
    val outline: Color,
    val focus: Color,
    val error: Color,
)

/** Exact EBP roles retained beside Jetpacs' locally derived color tokens. */
@Immutable
data class JetpacsThemeRoles(
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val error: Color,
    val onError: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val outline: Color,
    val success: Color,
    val warning: Color,
) {
    operator fun get(role: DesignThemeRole): Color = when (role) {
        DesignThemeRole.Primary -> primary
        DesignThemeRole.OnPrimary -> onPrimary
        DesignThemeRole.Secondary -> secondary
        DesignThemeRole.OnSecondary -> onSecondary
        DesignThemeRole.Error -> error
        DesignThemeRole.OnError -> onError
        DesignThemeRole.Background -> background
        DesignThemeRole.OnBackground -> onBackground
        DesignThemeRole.Surface -> surface
        DesignThemeRole.OnSurface -> onSurface
        DesignThemeRole.Outline -> outline
        DesignThemeRole.Success -> success
        DesignThemeRole.Warning -> warning
    }
}

/** Typography used by the first Jetpacs-owned controls. */
@Immutable
data class JetpacsTypography(
    /** Canonical `text` fallbacks for the six EBP style names in a design scope. */
    val body: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
    val action: TextStyle,
    val choice: TextStyle,
    val panelLabel: TextStyle,
    val code: TextStyle,
    val field: TextStyle,
    val fieldLabel: TextStyle,
    val fieldSupporting: TextStyle,
)

/** Shape tokens deliberately independent of Material's shape taxonomy. */
@Immutable
data class JetpacsShapes(
    val control: Shape,
    val panel: Shape,
    val indicator: Shape,
)

/** Compact spacing tokens on a four-dp grid. */
@Immutable
data class JetpacsSpacing(
    val unit: Dp,
    val controlHorizontal: Dp,
    val controlVertical: Dp,
    val panel: Dp,
)

/** Private token colors for shared syntax roles. */
@Immutable
data class JetpacsSyntaxColors(
    val comment: Color,
    val string: Color,
    val keyword: Color,
    val function: Color,
    val constant: Color,
    val number: Color,
    val link: Color,
    val preprocessor: Color,
    val tag: Color,
    val todo: Color,
    val done: Color,
    val heading: List<Color>,
    val parenthesis: List<Color>,
)

/** Complete private theme value consumed by Jetpacs component Styles. */
@Immutable
data class JetpacsThemeValue(
    val roles: JetpacsThemeRoles,
    val colors: JetpacsColors,
    val typography: JetpacsTypography,
    val shapes: JetpacsShapes,
    val spacing: JetpacsSpacing,
    val syntax: JetpacsSyntaxColors,
    /** Polarity the palette was derived for; a re-derived scope theme keeps it. */
    val dark: Boolean = false,
)

private val lightDefaults = JetpacsColors(
    background = Color(0xFFF6F7F9),
    content = Color(0xFF171A1F),
    mutedContent = Color(0xFF555C68),
    accent = Color(0xFF365D8D),
    onAccent = Color.White,
    surface = Color(0xFFFFFFFF),
    raisedSurface = Color(0xFFF0F3F7),
    selectedSurface = Color(0xFFDCE8F7),
    pressedSurface = Color(0xFFE4E9F0),
    outline = Color(0xFF8A909B),
    focus = Color(0xFF365D8D),
    error = Color(0xFFB3261E),
)

private val darkDefaults = JetpacsColors(
    background = Color(0xFF111318),
    content = Color(0xFFE3E7EE),
    mutedContent = Color(0xFFAEB5C1),
    accent = Color(0xFF9FC6FF),
    onAccent = Color(0xFF082E55),
    surface = Color(0xFF181B21),
    raisedSurface = Color(0xFF20252D),
    selectedSurface = Color(0xFF253A54),
    pressedSurface = Color(0xFF292F38),
    outline = Color(0xFF8B919D),
    focus = Color(0xFF9FC6FF),
    error = Color(0xFFFFB4AB),
)

/** Parse a `#RGB`, `#RGBA`, `#RRGGBB`, or `#RRGGBBAA` wire color, else null. */
internal fun parseHexColor(value: String): Color? {
    if (!value.startsWith('#')) return null
    val hex = value.drop(1)
    if (hex.length !in setOf(3, 4, 6, 8) || hex.any { it.digitToIntOrNull(16) == null }) {
        return null
    }
    fun expand(c: Char): Int = c.digitToInt(16) * 17
    val (red, green, blue, alpha) = when (hex.length) {
        3 -> listOf(expand(hex[0]), expand(hex[1]), expand(hex[2]), 255)
        4 -> listOf(expand(hex[0]), expand(hex[1]), expand(hex[2]), expand(hex[3]))
        6 -> listOf(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
            255,
        )
        else -> listOf(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
            hex.substring(6, 8).toInt(16),
        )
    }
    return Color(red, green, blue, alpha)
}

private fun JsonObject?.role(name: String): Color? =
    ((this?.get(name) as? JsonPrimitive)?.content)?.let(::parseHexColor)

private fun legibleOn(background: Color): Color =
    if (background.luminance() < 0.5f) Color.White else Color(0xFF171A1F)

/** Preserve all thirteen neutral EBP roles for dynamic design references. */
fun deriveJetpacsThemeRoles(colors: JsonObject?, dark: Boolean): JetpacsThemeRoles {
    val base = if (dark) darkDefaults else lightDefaults
    val primary = colors.role("primary") ?: base.accent
    val secondary = colors.role("secondary") ?: primary
    val error = colors.role("error") ?: base.error
    val background = colors.role("background") ?: base.background
    val surface = colors.role("surface") ?: base.surface
    return JetpacsThemeRoles(
        primary = primary,
        onPrimary = colors.role("on_primary") ?: legibleOn(primary),
        secondary = secondary,
        onSecondary = colors.role("on_secondary") ?: legibleOn(secondary),
        error = error,
        onError = colors.role("on_error") ?: legibleOn(error),
        background = background,
        onBackground = colors.role("on_background") ?: base.content,
        surface = surface,
        onSurface = colors.role("on_surface") ?: base.content,
        outline = colors.role("outline") ?: base.outline,
        success = colors.role("success")
            ?: if (dark) Color(0xFF7FD8A2) else Color(0xFF236B3B),
        warning = colors.role("warning")
            ?: if (dark) Color(0xFFFFC56A) else Color(0xFF8A5700),
    )
}

/**
 * Derive Jetpacs-private colors from EBP's neutral role map.
 *
 * Missing roles keep the editor-native fallback. Derived containers are local
 * presentation tokens and never become accepted EBP color names.
 */
fun deriveJetpacsColors(colors: JsonObject?, dark: Boolean): JetpacsColors {
    val roles = deriveJetpacsThemeRoles(colors, dark)
    // A pushed surface without a background has always doubled as the
    // background for the private palette; keep that reading here.
    val background = colors.role("background") ?: colors.role("surface") ?: roles.background
    return deriveJetpacsColors(roles.copy(background = background), dark)
}

/**
 * Derive Jetpacs-private colors from already resolved [roles].
 *
 * This is the path a design scope takes when it re-declares theme roles for
 * its subtree: the same derivation as the receiver theme, from the scope's
 * roles instead of the pushed Emacs palette.
 */
fun deriveJetpacsColors(roles: JetpacsThemeRoles, dark: Boolean): JetpacsColors {
    val base = if (dark) darkDefaults else lightDefaults
    val accent = roles.primary
    val surface = roles.surface
    val content = roles.onSurface
    val outline = roles.outline
    return base.copy(
        background = roles.background,
        content = content,
        mutedContent = lerp(content, surface, if (dark) 0.36f else 0.42f),
        accent = accent,
        onAccent = roles.onPrimary,
        surface = surface,
        raisedSurface = lerp(surface, accent, if (dark) 0.08f else 0.05f),
        selectedSurface = lerp(surface, accent, if (dark) 0.23f else 0.16f),
        pressedSurface = lerp(surface, content, if (dark) 0.12f else 0.08f),
        outline = outline,
        focus = accent,
        error = roles.error,
    )
}

/** The complete private theme a design scope installs for re-declared [roles]. */
internal fun jetpacsThemeFor(roles: JetpacsThemeRoles, dark: Boolean): JetpacsThemeValue =
    defaultTheme(deriveJetpacsColors(roles, dark), roles, dark)

private fun defaultTheme(
    colors: JetpacsColors,
    roles: JetpacsThemeRoles,
    dark: Boolean = false,
) = JetpacsThemeValue(
    roles = roles,
    colors = colors,
    dark = dark,
    typography = JetpacsTypography(
        body = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        title = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.SansSerif,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 28.sp,
        ),
        headline = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.SansSerif,
            fontSize = 24.sp,
            lineHeight = 32.sp,
        ),
        caption = TextStyle(
            color = colors.mutedContent,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        label = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
        ),
        mono = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        action = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp,
        ),
        choice = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        ),
        panelLabel = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp,
        ),
        code = TextStyle(
            color = colors.mutedContent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        ),
        field = TextStyle(
            color = colors.content,
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        ),
        fieldLabel = TextStyle(
            color = colors.mutedContent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
        ),
        fieldSupporting = TextStyle(
            color = colors.mutedContent,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
    ),
    shapes = JetpacsShapes(
        control = RoundedCornerShape(4.dp),
        panel = RoundedCornerShape(8.dp),
        indicator = RoundedCornerShape(3.dp),
    ),
    spacing = JetpacsSpacing(
        unit = 4.dp,
        controlHorizontal = 12.dp,
        controlVertical = 8.dp,
        panel = 12.dp,
    ),
    syntax = JetpacsSyntaxColors(
        comment = colors.mutedContent,
        string = lerp(colors.content, Color(0xFF2E8B57), 0.65f),
        keyword = colors.accent,
        function = lerp(colors.content, colors.accent, 0.70f),
        constant = lerp(colors.content, Color(0xFF8A4B82), 0.62f),
        number = lerp(colors.content, colors.error, 0.45f),
        link = colors.accent,
        preprocessor = lerp(colors.mutedContent, colors.accent, 0.45f),
        tag = lerp(colors.content, Color(0xFF1F6F5C), 0.58f),
        todo = colors.error,
        done = lerp(colors.content, Color(0xFF2E8B57), 0.65f),
        heading = listOf(
            colors.accent,
            lerp(colors.accent, colors.content, 0.25f),
            lerp(colors.accent, colors.error, 0.28f),
        ),
        parenthesis = listOf(
            colors.accent,
            lerp(colors.accent, colors.error, 0.30f),
            lerp(colors.accent, colors.content, 0.42f),
        ),
    ),
)

internal val LocalJetpacsTheme = staticCompositionLocalOf {
    defaultTheme(lightDefaults, deriveJetpacsThemeRoles(null, false))
}

/** Stable access to the active Jetpacs design tokens and component Styles. */
object JetpacsTheme {
    val roles: JetpacsThemeRoles
        @Composable @ReadOnlyComposable get() = LocalJetpacsTheme.current.roles
    val colors: JetpacsColors
        @Composable @ReadOnlyComposable get() = LocalJetpacsTheme.current.colors
    val typography: JetpacsTypography
        @Composable @ReadOnlyComposable get() = LocalJetpacsTheme.current.typography
    val shapes: JetpacsShapes
        @Composable @ReadOnlyComposable get() = LocalJetpacsTheme.current.shapes
    val spacing: JetpacsSpacing
        @Composable @ReadOnlyComposable get() = LocalJetpacsTheme.current.spacing
    val syntax: JetpacsSyntaxColors
        @Composable @ReadOnlyComposable get() = LocalJetpacsTheme.current.syntax
    val styles: JetpacsComponentStyles = JetpacsComponentStyles
}

/**
 * Install the Jetpacs theme derived from an accepted EBP theme payload.
 * MaterialTheme is intentionally neither read nor provided here.
 */
@Composable
fun ProvideJetpacsTheme(payload: JsonObject?, content: @Composable () -> Unit) {
    val forcedDark = (payload?.get("dark") as? JsonPrimitive)?.booleanOrNull
    val dark = forcedDark ?: isSystemInDarkTheme()
    val colors = deriveJetpacsColors(payload?.get("colors") as? JsonObject, dark)
    val roles = deriveJetpacsThemeRoles(payload?.get("colors") as? JsonObject, dark)
    CompositionLocalProvider(
        LocalJetpacsTheme provides defaultTheme(colors, roles, dark),
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.accent,
            backgroundColor = colors.accent.copy(alpha = 0.28f),
        ),
        content = content,
    )
}
