// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure text-resolution rules shared by the design `text` override and slots. */
class JetpacsDesignTextStyleTest {
    private val roles = deriveJetpacsThemeRoles(
        Json.parseToJsonElement(
            """{"primary":"#112233","on_surface":"#445566","error":"#aa0000"}""",
        ) as JsonObject,
        dark = false,
    )

    private val fallback = TextStyle(
        color = Color(0xFF000000),
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    )

    @Test
    fun designPropertiesOverlayOnlyTheAuthoredTextAttributes() {
        val properties = mapOf(
            DesignProperty.FontSize to DesignValue.DimensionValue(18.0),
            DesignProperty.LineHeight to DesignValue.DimensionValue(29.0),
            DesignProperty.FontWeight to DesignValue.FontWeightValue(600),
            DesignProperty.ContentColor to DesignValue.ThemeRoleValue(DesignThemeRole.Primary),
            // Layout properties must be ignored by text resolution.
            DesignProperty.Padding to DesignValue.DimensionValue(99.0),
        )
        val resolved = fallback.withDesignTextProperties(properties, roles)

        assertEquals(18.sp, resolved.fontSize)
        assertEquals(29.sp, resolved.lineHeight)
        assertEquals(FontWeight(600), resolved.fontWeight)
        assertEquals(Color(0xFF112233), resolved.color)
        assertEquals(fallback.letterSpacing, resolved.letterSpacing)
    }

    @Test
    fun literalDesignColorsResolveWithAlpha() {
        val properties = mapOf(
            DesignProperty.ContentColor to DesignValue.ColorValue(0x808A5A2BL),
        )
        assertEquals(
            Color(0x808A5A2B),
            fallback.withDesignTextProperties(properties, roles).color,
        )
    }

    @Test
    fun canonicalColorAndWeightMembersWinOverTheDesignLayers() {
        val node = Json.parseToJsonElement(
            """{"t":"text","text":"x","color":"error","font_weight":"bold"}""",
        ) as JsonObject
        val designed = fallback.copy(color = Color(0xFF112233), fontWeight = FontWeight(300))
        val resolved = designed.withCanonicalMembers(node, roles)

        assertEquals(Color(0xFFAA0000), resolved.color)
        assertEquals(FontWeight.Bold, resolved.fontWeight)
    }

    @Test
    fun hexCanonicalColorAndNumericWeightAreAccepted() {
        val node = Json.parseToJsonElement(
            """{"t":"text","text":"x","color":"#3F6F86","font_weight":500}""",
        ) as JsonObject
        val resolved = fallback.withCanonicalMembers(node, roles)

        assertEquals(Color(0xFF3F6F86), resolved.color)
        assertEquals(FontWeight(500), resolved.fontWeight)
    }

    @Test
    fun unresolvableCanonicalMembersKeepTheDesignValues() {
        val node = Json.parseToJsonElement(
            """{"t":"text","text":"x","color":"not-a-role","font_weight":"9"}""",
        ) as JsonObject
        val designed = fallback.copy(color = Color(0xFF112233), fontWeight = FontWeight(600))
        val resolved = designed.withCanonicalMembers(node, roles)

        assertEquals(Color(0xFF112233), resolved.color)
        assertEquals(FontWeight(600), resolved.fontWeight)
        assertNull(canonicalFontWeight(JsonPrimitive("9")))
        assertNull(canonicalFontWeight(JsonPrimitive(0)))
        assertNull(canonicalFontWeight(JsonPrimitive(1001)))
    }
}
