// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.After
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
    fun italicAndDecorationPropertiesOverlayTheFallback() {
        val properties = mapOf(
            DesignProperty.Italic to DesignValue.BooleanValue(true),
            DesignProperty.Underline to DesignValue.BooleanValue(true),
            DesignProperty.StrikeThrough to DesignValue.BooleanValue(true),
        )
        val resolved = fallback.withDesignTextProperties(properties, roles)
        assertEquals(FontStyle.Italic, resolved.fontStyle)
        assertEquals(
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)),
            resolved.textDecoration,
        )

        val cleared = fallback.copy(textDecoration = TextDecoration.Underline)
            .withDesignTextProperties(
                mapOf(DesignProperty.Underline to DesignValue.BooleanValue(false)),
                roles,
            )
        assertEquals(TextDecoration.None, cleared.textDecoration)
        assertNull(designTextDecoration(emptyMap()))
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

    @After
    fun clearInstalledFonts() {
        JetpacsFonts.install(JetpacsFontSet.None)
    }

    private fun familyOf(family: DesignFontFamily): FontFamily? =
        fallback.withDesignTextProperties(
            mapOf(DesignProperty.FontFamily to DesignValue.FontFamilyValue(family)),
            roles,
        ).fontFamily

    @Test
    fun installedFamiliesResolveByNameAndFallBackToTheSansRole() {
        val literata = FontFamily.Serif
        val userSans = FontFamily.Cursive

        // Nothing installed: a named family renders in the platform default.
        assertEquals(FontFamily.Default, familyOf(DesignFontFamily.Named("literata")))

        JetpacsFonts.install(JetpacsFontSet(named = mapOf("literata" to literata)))
        assertEquals(literata, familyOf(DesignFontFamily.Named("literata")))
        assertEquals(FontFamily.Default, familyOf(DesignFontFamily.Named("missing")))

        // With a sans role replaced, an unknown name follows that role.
        JetpacsFonts.install(JetpacsFontSet(named = mapOf("literata" to literata), sans = userSans))
        assertEquals(userSans, familyOf(DesignFontFamily.Named("missing")))
        assertEquals(literata, familyOf(DesignFontFamily.Named("literata")))
    }

    @Test
    fun replacedRolesCoverTheBundledAndTheSystemFamilies() {
        val sans = FontFamily.Cursive
        val serif = FontFamily.Serif
        val mono = FontFamily.Monospace
        JetpacsFonts.install(JetpacsFontSet(sans = sans, serif = serif, mono = mono))

        assertEquals(sans, familyOf(DesignFontFamily.System))
        assertEquals(sans, familyOf(DesignFontFamily.PlexSans))
        assertEquals(serif, familyOf(DesignFontFamily.PlexSerif))
        assertEquals(mono, familyOf(DesignFontFamily.PlexMono))
        assertEquals(mono, familyOf(DesignFontFamily.SystemMono))

        // A role left null keeps the bundled family.
        JetpacsFonts.install(JetpacsFontSet(serif = serif))
        assertEquals(PlexFonts.sans, familyOf(DesignFontFamily.PlexSans))
        assertEquals(FontFamily.Default, familyOf(DesignFontFamily.System))
        assertEquals(serif, familyOf(DesignFontFamily.PlexSerif))
    }
}
