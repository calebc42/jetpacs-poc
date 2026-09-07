// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 18.4 theme mirroring: a theme.set `colors` role map overlays a base
// Material scheme so token-colored nodes wear the running Emacs theme; a `null`
// colors map (or none) keeps the Companion's native scheme. Every role is
// optional and merged with a legible platform fallback (§18.4). `dark` present
// forces polarity; absent follows the system (amendment #36). The two roles
// with no Material slot — `success` and `warning` — ride an ExtendedColors
// holder exposed through LocalExtendedColors and resolved by ColorModel.
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.renderer.model.*

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calebc42.ebp.renderer.compose.LocalComposeThemeRoles
import com.calebc42.ebp.renderer.compose.ComposeThemeRoles
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.calebc42.jetpacs.material3.ui.ProvideJetpacsStyleTokens
import kotlinx.serialization.json.JsonObject

/** SPEC 18.4: the `success`/`warning` roles, which Material has no slot for. */
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
) {
    companion object {
        /** Legible platform defaults (§18.4) for each polarity. */
        fun defaults(dark: Boolean) = ExtendedColors(
            success = if (dark) Color(0xFF7FB77E) else Color(0xFF2E7D32),
            onSuccess = if (dark) Color(0xFF10281A) else Color.White,
            warning = if (dark) Color(0xFFE0B44C) else Color(0xFFB26A00),
            onWarning = if (dark) Color(0xFF2A1F00) else Color.White,
        )
    }
}

/** Falls back to the light defaults before any EbpTheme provides real ones. */
val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors.defaults(false) }

private fun JsonObject.role(key: String): Color? =
    stringOr(key).takeIf { it.isNotEmpty() }
        ?.let { parseHexColor(it)?.let(::Color) }

/** A legible on-color for a pushed extension role that arrived without one. */
private fun legibleOn(bg: Color): Color =
    if (bg.luminance() < 0.5f) Color.White else Color(0xFF1A1A1A)

/**
 * [base] with every §18.4 role present in [colors] overlaid. The
 * Material-only roles are deliberately not wire vocabulary. They are derived
 * here from EBP's small, renderer-neutral role set so another Compose renderer
 * can interpret the same theme without inheriting Material's token taxonomy.
 */
fun buildColorScheme(colors: JsonObject?, base: ColorScheme): ColorScheme {
    val c = colors ?: return base
    return buildColorScheme({ name -> c.role(name) }, base)
}

/**
 * Derive a Material scheme from a role lookup over the 13 neutral EBP roles.
 *
 * The lookup form is shared by the pushed theme payload and by a design
 * scope's re-declared roles, so both take exactly the same derivation.
 */
fun buildColorScheme(lookup: (String) -> Color?, base: ColorScheme): ColorScheme {
    val c = object {
        fun role(name: String): Color? = lookup(name)
    }
    val primary = c.role("primary") ?: base.primary
    val secondary = c.role("secondary") ?: base.secondary
    val error = c.role("error") ?: base.error
    val surface = c.role("surface") ?: base.surface
    val surfaceVariant = lerp(surface, c.role("on_surface") ?: base.onSurface, 0.10f)
    val primaryContainer = lerp(surface, primary, 0.24f)
    val secondaryContainer = lerp(surface, secondary, 0.24f)
    val tertiary = lerp(primary, secondary, 0.5f)
    val tertiaryContainer = lerp(surface, tertiary, 0.24f)
    val errorContainer = lerp(surface, error, 0.24f)
    // SPEC 18.4 (amendment #56): when a base role is pushed but its paired
    // on-color is absent, derive a contrast-legible on-color from the PUSHED
    // color, not the base scheme's on-color (which may be illegible against the
    // foreign color). When neither is pushed, keep the base on-color.
    fun on(onRole: String, baseRole: String, baseOn: Color): Color {
        c.role(onRole)?.let { return it }
        c.role(baseRole)?.let { return legibleOn(it) }
        return baseOn
    }
    return base.copy(
        primary = primary,
        onPrimary = on("on_primary", "primary", base.onPrimary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = legibleOn(primaryContainer),
        secondary = secondary,
        onSecondary = on("on_secondary", "secondary", base.onSecondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = legibleOn(secondaryContainer),
        tertiary = tertiary,
        onTertiary = legibleOn(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = legibleOn(tertiaryContainer),
        error = error,
        onError = on("on_error", "error", base.onError),
        errorContainer = errorContainer,
        onErrorContainer = legibleOn(errorContainer),
        background = c.role("background") ?: c.role("surface") ?: base.background,
        onBackground = on("on_background", "background", c.role("on_surface")
            ?: c.role("surface")?.let { legibleOn(it) } ?: base.onBackground),
        surface = surface,
        onSurface = on("on_surface", "surface", base.onSurface),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = c.role("on_surface") ?: base.onSurfaceVariant,
        outline = c.role("outline") ?: base.outline,
        outlineVariant = lerp(c.role("outline") ?: base.outline, surface, 0.55f),
        surfaceContainerLow = lerp(surface, surfaceVariant, 0.25f),
        surfaceContainer = lerp(surface, surfaceVariant, 0.5f),
        surfaceContainerHigh = lerp(surface, surfaceVariant, 0.75f),
    )
}

/** The ExtendedColors a theme selects: pushed success/warning over defaults. */
fun buildExtendedColors(colors: JsonObject?, dark: Boolean): ExtendedColors {
    val c = colors ?: return ExtendedColors.defaults(dark)
    return buildExtendedColors({ name -> c.role(name) }, dark)
}

/** The lookup form of [buildExtendedColors], shared with design-scope roles. */
fun buildExtendedColors(lookup: (String) -> Color?, dark: Boolean): ExtendedColors {
    val d = ExtendedColors.defaults(dark)
    val success = lookup("success") ?: d.success
    val warning = lookup("warning") ?: d.warning
    return ExtendedColors(
        success = success,
        onSuccess = if (lookup("success") != null) legibleOn(success) else d.onSuccess,
        warning = warning,
        onWarning = if (lookup("warning") != null) legibleOn(warning) else d.onWarning,
    )
}

/**
 * The one theme entry point. [payload] is the persisted §18.4 theme
 * (`{dark, colors, syntax, dynamic, font_scale, layout_direction}`) or null
 * for the native scheme. `dark` decides polarity (present forces it, absent
 * follows the system); `colors` mirrors the Emacs palette. `dynamic` selects
 * the wallpaper-derived Material You base where the platform has one — Emacs
 * could never supply that as `colors`, since the palette derives from the
 * device wallpaper. `font_scale` (0.4..2.0) scales every text node together
 * through LocalDensity; `layout_direction` (ltr|rtl) mirrors the whole layout,
 * absent following the system for both — the tri-state `dark` convention.
 * `syntax` is read separately by the editor (W9-h2).
 */
@Composable
fun EbpTheme(payload: JsonObject?, content: @Composable () -> Unit) {
    // C6: `boolOrNull` is the by-primitive form of the old `is Boolean` arm —
    // only a real JSON boolean forces polarity; absent, JSON null and a
    // non-boolean all fall to the system (amendment #36). The system read stays
    // inside the else arm so it is not evaluated when the payload forces it.
    val dark = when (val d = payload?.boolOrNull("dark")) {
        is Boolean -> d
        else -> isSystemInDarkTheme()
    }
    val colors = payload?.objOrNull("colors")
    // §18.4 `dynamic`: the wallpaper-derived scheme as the BASE, where the
    // platform has one (S+); the pushed roles still overlay it, and below S
    // the baseline scheme stands in.
    val context = LocalContext.current
    val base = if (payload?.boolOrNull("dynamic") == true &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    val scheme = buildColorScheme(colors, base)
    val extended = buildExtendedColors(colors, dark)
    // SPEC 18.4: the pushed `syntax` SyntaxStyle map overlays the polarity
    // palette; the editor/text nodes read it from LocalSyntaxColors.
    val syntax = emacsSyntaxColors(
        payload?.objOrNull("syntax"), SyntaxColors.forBackground(dark))
    // §18.4 `font_scale`: one number scaling every text node together —
    // a per-node member would be the wrong shape. Clamped to 0.4..2.0;
    // absent follows the device's own setting.
    val density = LocalDensity.current
    val fontScale = payload?.get("font_scale")?.numOrNull()
        ?.toFloat()?.coerceIn(0.4f, 2.0f)
    // §18.4 `layout_direction`: absent follows the system, deliberately
    // mirroring the tri-state `dark` rather than inventing a convention.
    val direction = when (payload?.stringOr("layout_direction")) {
        "ltr" -> LayoutDirection.Ltr
        "rtl" -> LayoutDirection.Rtl
        else -> LocalLayoutDirection.current
    }
    CompositionLocalProvider(
        LocalExtendedColors provides extended,
        LocalSyntaxColors provides syntax,
        LocalDensity provides (fontScale?.let { Density(density.density, it) }
            ?: density),
        LocalLayoutDirection provides direction,
    ) {
        MaterialTheme(colorScheme = scheme) {
            ProvideJetpacsStyleTokens(
                colors = MaterialTheme.colorScheme,
                shapes = MaterialTheme.shapes,
                content = content,
            )
        }
    }
}

/** The roles already applied by an enclosing [ScopedThemeRoles], so nesting re-themes only on change. */
private val LocalAppliedThemeRoles = staticCompositionLocalOf<ComposeThemeRoles?> { null }

/**
 * Re-theme [content] from the roles a design scope declared, when any.
 *
 * Reads [LocalComposeThemeRoles] at the dispatcher's scope boundary. A scope
 * that declares no roles, or the same roles an enclosing scope already
 * applied, renders [content] under the ambient theme unchanged. Otherwise the
 * Material scheme, the extended success/warning pair, and the receiver's
 * style tokens are all re-derived from the declared roles, which is what
 * lets a scaffold, top bar, or tab indicator follow the profile instead of
 * the Emacs theme. Polarity, density, direction, and syntax colors stay
 * ambient: roles change colors, not the theme's other axes.
 */
@Composable
fun ScopedThemeRoles(content: @Composable () -> Unit) {
    val roles = LocalComposeThemeRoles.current
    if (roles == null || roles == LocalAppliedThemeRoles.current) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = remember(roles, base) { buildColorScheme(roles::byWireName, base) }
    val extended = remember(roles, dark) { buildExtendedColors(roles::byWireName, dark) }
    CompositionLocalProvider(
        LocalAppliedThemeRoles provides roles,
        LocalExtendedColors provides extended,
    ) {
        MaterialTheme(colorScheme = scheme) {
            ProvideJetpacsStyleTokens(
                colors = MaterialTheme.colorScheme,
                shapes = MaterialTheme.shapes,
                content = content,
            )
        }
    }
}
