// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 20.1.3 (#205): the report carries every standard role, Material's own
 * roles in snake_case, lowercase #rrggbb values, and at most 64 members. */
class SchemeRolesTest {
    private val standard = listOf(
        "primary", "on_primary", "secondary", "on_secondary", "error", "on_error",
        "background", "on_background", "surface", "on_surface", "outline",
        "success", "warning",
    )

    @Test
    fun standardRolesLeadAndExtendedRolesFollow() {
        val roles = baselineSchemeRoles(dark = false)
        assertEquals(standard, roles.keys.take(standard.size))
        assertTrue("surface_container_low" in roles)
        assertTrue("on_surface_variant" in roles)
        assertTrue(roles.size <= 64)
        val hex = Regex("^#[0-9a-f]{6}$")
        for ((role, value) in roles) {
            assertTrue("$role is $value", hex.matches(value))
            assertTrue("$role is an identifier", Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]*$").matches(role))
        }
    }

    @Test
    fun valuesAreTheSchemesOwn() {
        val scheme = lightColorScheme(primary = Color(0xFF396286), surfaceContainerLow = Color(0xFFF1F4FA))
        val roles = schemeRoles(scheme, ExtendedColors.defaults(false))
        assertEquals("#396286", roles["primary"])
        assertEquals("#f1f4fa", roles["surface_container_low"])
        assertEquals("#2e7d32", roles["success"])
        assertEquals("#000000", hexOf(Color.Black))
        assertEquals("#0a0b0c", hexOf(Color(0x800A0B0C)))
    }
}
