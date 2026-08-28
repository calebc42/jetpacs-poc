// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JetpacsThemeTest {
    @Test
    fun authoredEbpRolesOverridePrivateBaseTokens() {
        val roles = Json.parseToJsonElement(
            """{"primary":"#123456","on_primary":"#ffffff","surface":"#f0f0f0","on_surface":"#101010","outline":"#778899"}""",
        ).jsonObject
        val colors = deriveJetpacsColors(roles, dark = false)

        assertEquals(Color(0xFF123456), colors.accent)
        assertEquals(Color.White, colors.onAccent)
        assertEquals(Color(0xFFF0F0F0), colors.surface)
        assertEquals(Color(0xFF101010), colors.content)
        assertEquals(Color(0xFF778899), colors.outline)
        assertNotEquals(colors.surface, colors.selectedSurface)
    }

    @Test
    fun missingOnColorIsDerivedForContrast() {
        val lightAccent = Json.parseToJsonElement(
            """{"primary":"#fefefe"}""",
        ).jsonObject
        val colors = deriveJetpacsColors(lightAccent, dark = true)

        assertEquals(Color(0xFF171A1F), colors.onAccent)
    }

    @Test
    fun absentRolesKeepDistinctLightAndDarkDefaults() {
        val light = deriveJetpacsColors(null, dark = false)
        val dark = deriveJetpacsColors(null, dark = true)

        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.content, dark.content)
        assertEquals(Color(0xFF365D8D), light.accent)
        assertEquals(Color(0xFF9FC6FF), dark.accent)
    }
}
