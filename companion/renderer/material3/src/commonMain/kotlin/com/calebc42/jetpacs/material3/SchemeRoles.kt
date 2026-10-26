// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 20.1.3 (#205): the device colors report. A Material 3 ColorScheme
// projected as the role map the Companion reports — the thirteen standard
// EBP roles first, then the rest of the scheme as extended roles in
// snake_case, every value a lowercase #rrggbb. The projection is the
// report's whole vocabulary; nothing here resolves a role for drawing.
package com.calebc42.jetpacs.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** [color] as the report's lowercase `#rrggbb`, alpha dropped. */
fun hexOf(color: Color): String =
    "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')

/**
 * The role map for [scheme]: the standard roles the §18.4 vocabulary names
 * (with [extended] supplying `success` and `warning`, which Material has no
 * slot for), then Material's own roles under their snake_case names. The
 * map is ordered so a report serializes the same way every time.
 */
fun schemeRoles(scheme: ColorScheme, extended: ExtendedColors): Map<String, String> {
    val roles = linkedMapOf<String, Color>()
    // The standard roles, in contract.json theme_roles order.
    roles["primary"] = scheme.primary
    roles["on_primary"] = scheme.onPrimary
    roles["secondary"] = scheme.secondary
    roles["on_secondary"] = scheme.onSecondary
    roles["error"] = scheme.error
    roles["on_error"] = scheme.onError
    roles["background"] = scheme.background
    roles["on_background"] = scheme.onBackground
    roles["surface"] = scheme.surface
    roles["on_surface"] = scheme.onSurface
    roles["outline"] = scheme.outline
    roles["success"] = extended.success
    roles["warning"] = extended.warning
    // The extended roles: Material 3's ColorScheme, snake_cased.
    roles["primary_container"] = scheme.primaryContainer
    roles["on_primary_container"] = scheme.onPrimaryContainer
    roles["inverse_primary"] = scheme.inversePrimary
    roles["secondary_container"] = scheme.secondaryContainer
    roles["on_secondary_container"] = scheme.onSecondaryContainer
    roles["tertiary"] = scheme.tertiary
    roles["on_tertiary"] = scheme.onTertiary
    roles["tertiary_container"] = scheme.tertiaryContainer
    roles["on_tertiary_container"] = scheme.onTertiaryContainer
    roles["surface_variant"] = scheme.surfaceVariant
    roles["on_surface_variant"] = scheme.onSurfaceVariant
    roles["surface_tint"] = scheme.surfaceTint
    roles["inverse_surface"] = scheme.inverseSurface
    roles["inverse_on_surface"] = scheme.inverseOnSurface
    roles["error_container"] = scheme.errorContainer
    roles["on_error_container"] = scheme.onErrorContainer
    roles["outline_variant"] = scheme.outlineVariant
    roles["scrim"] = scheme.scrim
    roles["surface_bright"] = scheme.surfaceBright
    roles["surface_dim"] = scheme.surfaceDim
    roles["surface_container"] = scheme.surfaceContainer
    roles["surface_container_high"] = scheme.surfaceContainerHigh
    roles["surface_container_highest"] = scheme.surfaceContainerHighest
    roles["surface_container_low"] = scheme.surfaceContainerLow
    roles["surface_container_lowest"] = scheme.surfaceContainerLowest
    roles["on_success"] = extended.onSuccess
    roles["on_warning"] = extended.onWarning
    return roles.mapValues { (_, color) -> hexOf(color) }
}

/** The baseline scheme's roles at [dark], for a platform with no wallpaper-derived scheme. */
fun baselineSchemeRoles(dark: Boolean): Map<String, String> =
    schemeRoles(if (dark) darkColorScheme() else lightColorScheme(), ExtendedColors.defaults(dark))
